package com.pyoif.tatara

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileType
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.ChangeType
import org.gradle.work.DisableCachingByDefault
import org.gradle.work.InputChanges
import org.gradle.work.Incremental
import java.io.File

@DisableCachingByDefault(because = "Incremental task using InputChanges, not suitable for build cache")
abstract class GenerateRoutesTask : DefaultTask() {

    @get:Incremental
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:InputDirectory
    abstract val srcDir: DirectoryProperty

    @get:OutputDirectory
    abstract val generatedDir: DirectoryProperty

    @get:LocalState
    abstract val cacheDir: DirectoryProperty

    @get:OutputDirectory
    abstract val handlersDir: DirectoryProperty

    data class RouteDef(
        val routePath: String,
        val httpMethod: String,
        val className: String,
        val ablMethod: String,
        val pathParams: List<String> = emptyList(),
        val requestDtoClassName: String? = null,
        val responseDtoClassName: String? = null,
        val errorResponses: Map<Int, String> = emptyMap()
    )

    // Single annotation: @VERB("/path"). Verb is case-insensitive; path preserves case
    // (URL paths are case-sensitive in HTTP). Accepts both ' and " quotes via backreference.
    // Group 1 = verb, group 2 = quote, group 3 = path.
    private val httpVerbRegex = Regex(
        """(?i)@(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\(\s*(["'])([^"']+)\2\s*\)"""
    )
    private val classRegex = Regex("""CLASS\s+([\w.]+)""")
    // Matches ABL METHOD definition lines. Requires PUBLIC or PROTECTED (PRIVATE is not
    // routable; CONSTRUCTOR is excluded by requiring PUBLIC|PROTECTED).
    // Return type is optional: "METHOD PUBLIC VOID Name(" or "METHOD PUBLIC Name(" both match.
    private val methodDefRegex = Regex(
        """(?i)^\s*METHOD\s+(?:PUBLIC|PROTECTED)\s+(?:\w+\s+)?(\w+)\s*[(]"""
    )
    private val pathParamRegex = Regex("""\{(\w+)\}""")
    // @Response(code, Type) for non-200 error overrides. Group 1 = code, group 2 = type.
    private val responseAnnotationRegex = Regex(
        """@Response\(\s*(\d{3})\s*,\s*([\w.]+)\s*\)"""
    )
    // Full method signature: return type, method name, optional INPUT param type.
    // "METHOD PUBLIC ReturnType Name(INPUT x AS Type):"
    // "METHOD PUBLIC VOID Name():"
    // Group 1 = return type (or "VOID"), group 2 = method name, group 3 = input type (optional)
    private val methodSigRegex = Regex(
        """(?i)^\s*METHOD\s+(?:PUBLIC|PROTECTED)\s+(\w+(?:\.\w+)*)\s+(\w+)\s*\(\s*(?:INPUT\s+\w+\s+AS\s+([\w.]+)\s*)?\)\s*[.:]"""
    )

    @TaskAction
    fun execute(inputChanges: InputChanges) {
        val outDir = generatedDir.get().asFile
        val cacheFile = cacheDir.file("routeCache.txt").get().asFile
        outDir.mkdirs()

        val previousRoutesByFile = loadStateCache(cacheFile)
        val currentRoutesByFile = mutableMapOf<String, List<RouteDef>>()
        val dirtyRoutes = mutableSetOf<String>()

        inputChanges.getFileChanges(srcDir).forEach { change ->
            if (change.fileType == FileType.DIRECTORY) return@forEach
            val path = change.file.absolutePath

            when (change.changeType) {
                ChangeType.REMOVED -> {
                    previousRoutesByFile[path].orEmpty().forEach { dirtyRoutes.add(it.routePath) }
                    currentRoutesByFile[path] = emptyList()
                }
                ChangeType.ADDED, ChangeType.MODIFIED -> {
                    val newRoutes = extractRoutesFromFile(change.file)
                    val oldRoutes = previousRoutesByFile[path].orEmpty()
                    currentRoutesByFile[path] = newRoutes

                    val oldByKey = oldRoutes.associateBy(::routeKey)
                    val newByKey = newRoutes.associateBy(::routeKey)

                    (newByKey.keys - oldByKey.keys).forEach { dirtyRoutes.add(newByKey[it]!!.routePath) }
                    (oldByKey.keys - newByKey.keys).forEach { dirtyRoutes.add(oldByKey[it]!!.routePath) }
                    (newByKey.keys intersect oldByKey.keys).forEach {
                        if (newByKey[it] != oldByKey[it]) dirtyRoutes.add(newByKey[it]!!.routePath)
                    }
                }
            }
        }

        // Carry forward unchanged files so a per-file diff doesn't drop their routes from
        // the global view when other files change in the same run.
        previousRoutesByFile.forEach { (path, routes) ->
            if (!currentRoutesByFile.containsKey(path)) {
                currentRoutesByFile[path] = routes
            }
        }

        val allRoutes = currentRoutesByFile.values.flatten()
        val routesByPath = allRoutes.groupBy { it.routePath }
        val template = javaClass.getResource("/RouteShimTemplate.cls")?.readText()
            ?: throw IllegalStateException("Missing RouteShimTemplate.cls resource")

        // Recovery: if a shim is missing on disk (e.g. user cleared build/generated) but the
        // cache says it should exist, treat it as dirty. The diff-based check above only fires
        // when annotations actually change.
        routesByPath.keys.forEach { routePath ->
            val guardPath = pathSanitize(routePath)
            val shimFile = File(outDir, "$guardPath.cls")
            if (!shimFile.exists()) dirtyRoutes.add(routePath)
        }

        dirtyRoutes.forEach { routePath ->
            val routes = routesByPath[routePath]
            if (routes.isNullOrEmpty()) {
                deleteShim(routePath, outDir)
            } else {
                writeShim(routePath, routes, outDir, template)
            }
        }

        saveStateCache(cacheFile, currentRoutesByFile)

        // Group all current routes (not just dirty) by service prefix and write .handlers
        val allHandlersRoutes = currentRoutesByFile.values.flatten()
        val routesByService = allHandlersRoutes.groupBy { it.routePath.substringBefore("/", missingDelimiterValue = "") }
            .filter { it.key.isNotEmpty() }

        val handlersOutDir = handlersDir.get().asFile
        handlersOutDir.mkdirs()
        // Remove stale .handlers files from previous runs
        handlersOutDir.listFiles()?.filter { it.extension == "handlers" }?.forEach { it.delete() }

        routesByService.forEach { (serviceName, routes) ->
            writeHandlersFile(serviceName, routes, handlersOutDir)
        }
    }

    private fun routeKey(r: RouteDef): String =
        "${r.routePath}|${r.httpMethod}|${r.ablMethod}|${r.requestDtoClassName ?: ""}|${r.responseDtoClassName ?: ""}|" +
        r.errorResponses.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }

    private fun extractRoutesFromFile(file: File): List<RouteDef> {
        val classMatch = classRegex.find(file.readText()) ?: return emptyList()
        val className = classMatch.groupValues[1]

        val results = mutableListOf<RouteDef>()
        var pendingVerb: String? = null
        var pendingPath: String? = null
        var pendingPathParams: List<String> = emptyList()
        var pendingErrorResponses = mutableMapOf<Int, String>()

        file.forEachLine { line ->
            // Collect @Response annotations before the method line
            responseAnnotationRegex.find(line)?.let { m ->
                pendingErrorResponses[m.groupValues[1].toInt()] = m.groupValues[2]
            }
            // @VERB annotation
            httpVerbRegex.find(line)?.let { m ->
                pendingVerb = m.groupValues[1].uppercase()
                pendingPath = m.groupValues[3].let { if (it.startsWith("/")) it.substring(1) else it }
                pendingPathParams = pathParamRegex.findAll(m.groupValues[3]).map { it.groupValues[1] }.toList()
            }
            // Full method signature: captures return type + INPUT type
            methodSigRegex.find(line)?.let { m ->
                if (pendingVerb != null && pendingPath != null) {
                    val returnTypeRaw = m.groupValues[1]
                    val methodName = m.groupValues[2]
                    val inputType = m.groupValues.getOrNull(3)?.takeIf { it.isNotEmpty() }

                    val reqDto = if (inputType != null && inputType != "Tatara.Api.RequestContext") inputType else null
                    val respDto = if (returnTypeRaw.uppercase() == "VOID") null else returnTypeRaw

                    if (results.none { it.routePath == pendingPath && it.httpMethod == pendingVerb }) {
                        results.add(RouteDef(
                            routePath = pendingPath!!,
                            httpMethod = pendingVerb!!,
                            className = className,
                            ablMethod = methodName,
                            pathParams = pendingPathParams,
                            requestDtoClassName = reqDto,
                            responseDtoClassName = respDto,
                            errorResponses = pendingErrorResponses.toMap()
                        ))
                    }
                }
                pendingVerb = null; pendingPath = null
                pendingPathParams = emptyList(); pendingErrorResponses = mutableMapOf()
            }
            // Fallback: old-style METHOD line (no return type, no INPUT type)
            methodDefRegex.find(line)?.let { m ->
                if (pendingVerb != null && pendingPath != null) {
                    val ablMethod = m.groupValues[1]
                    if (results.none { it.routePath == pendingPath && it.httpMethod == pendingVerb }) {
                        results.add(RouteDef(pendingPath!!, pendingVerb!!, className, ablMethod, pendingPathParams,
                            errorResponses = pendingErrorResponses.toMap()))
                    }
                }
                pendingVerb = null; pendingPath = null
                pendingPathParams = emptyList(); pendingErrorResponses = mutableMapOf()
            }
        }
        return results
    }

    private fun loadStateCache(cacheFile: File): Map<String, List<RouteDef>> {
        val state = mutableMapOf<String, MutableList<RouteDef>>()
        if (cacheFile.exists()) {
            cacheFile.forEachLine { line ->
                val parts = line.split('|')
                when {
                    parts.size >= 8 -> {
                        val params = if (parts[5].isEmpty()) emptyList() else parts[5].split(',')
                        val reqDto = parts[6].takeIf { it.isNotEmpty() }
                        val respDto = parts[7].takeIf { it.isNotEmpty() }
                        val errorMap = mutableMapOf<Int, String>()
                        if (parts.size > 8 && parts[8].isNotEmpty()) {
                            parts[8].split(',').forEach { pair ->
                                val kv = pair.split('=')
                                if (kv.size == 2) errorMap[kv[0].toInt()] = kv[1]
                            }
                        }
                        state.getOrPut(parts[0]) { mutableListOf() }
                            .add(RouteDef(parts[1], parts[2], parts[3], parts[4], params, reqDto, respDto, errorMap))
                    }
                    parts.size == 6 -> {
                        val params = if (parts[5].isEmpty()) emptyList() else parts[5].split(',')
                        state.getOrPut(parts[0]) { mutableListOf() }
                            .add(RouteDef(parts[1], parts[2], parts[3], parts[4], params))
                    }
                }
            }
        }
        return state
    }

    private fun saveStateCache(cacheFile: File, state: Map<String, List<RouteDef>>) {
        cacheFile.parentFile.mkdirs()
        cacheFile.printWriter().use { writer ->
            state.forEach { (filePath, routes) ->
                routes.forEach { r ->
                    val paramsCsv = r.pathParams.joinToString(",")
                    val errorCsv = r.errorResponses.entries.sortedBy { it.key }
                        .joinToString(",") { "${it.key}=${it.value}" }
                    writer.println("$filePath|${r.routePath}|${r.httpMethod}|${r.className}|${r.ablMethod}|$paramsCsv|${r.requestDtoClassName ?: ""}|${r.responseDtoClassName ?: ""}|$errorCsv")
                }
            }
        }
    }

    private fun pathSanitize(routePath: String): String {
        return routePath.replace(pathParamRegex, "_")
    }

    private fun writeShim(routePath: String, routes: List<RouteDef>, outDir: File, template: String) {
        val methodHandlersBlock = StringBuilder()
        val usingSet = mutableSetOf<String>()
        val firstClassName = routes.firstOrNull()?.className ?: ""
        usingSet.add(firstClassName)

        val srcRoot = srcDir.get().asFile

        routes.forEachIndexed { index, def ->
            val handleName = "Handle" + def.httpMethod.lowercase().replaceFirstChar { it.uppercase() }
            if (index > 0) methodHandlersBlock.append("\r\n\r\n")

            val controllerVar = "ctrl${index}"
            val ctrlClassName = def.className
            val ctrlType = if (ctrlClassName.contains('.')) ctrlClassName else ctrlClassName
            usingSet.add(ctrlClassName)

            val hasRequestDto = def.requestDtoClassName != null
            val hasResponseDto = def.responseDtoClassName != null
            val dtoInfo = if (hasRequestDto) DtoParser.parse(def.requestDtoClassName!!, srcRoot) else DtoParser.DtoInfo.EMPTY

            // Collect USING entries for DTO + error types
            if (hasRequestDto) usingSet.add(def.requestDtoClassName!!)
            if (hasResponseDto) usingSet.add(def.responseDtoClassName!!)
            def.errorResponses.values.forEach { usingSet.add(it) }

            methodHandlersBlock.append("\tMETHOD OVERRIDE PROTECTED INTEGER $handleName(INPUT poRequest AS OpenEdge.Web.IWebRequest):\r\n")

            // === Variable declarations ===
            if (hasRequestDto) {
                methodHandlersBlock.append("\t\tDEFINE VARIABLE oReq   AS ${def.requestDtoClassName} NO-UNDO.\r\n")
                methodHandlersBlock.append("\t\tDEFINE VARIABLE oPath  AS ${def.requestDtoClassName}.PathSection  NO-UNDO.\r\n")
                methodHandlersBlock.append("\t\tDEFINE VARIABLE oQuer  AS ${def.requestDtoClassName}.QuerySection NO-UNDO.\r\n")
                methodHandlersBlock.append("\t\tDEFINE VARIABLE oBody  AS ${def.requestDtoClassName}.BodySection  NO-UNDO.\r\n")
            }
            if (hasResponseDto) {
                methodHandlersBlock.append("\t\tDEFINE VARIABLE oResult AS ${def.responseDtoClassName} NO-UNDO.\r\n")
            }
            methodHandlersBlock.append("\t\tDEFINE VARIABLE oResponse AS OpenEdge.Web.WebResponse NO-UNDO.\r\n")
            if (index == 0 || def.className != routes[0].className) {
                methodHandlersBlock.append("\t\tDEFINE VARIABLE $controllerVar AS $ctrlType NO-UNDO.\r\n")
            }
            methodHandlersBlock.append("\r\n")

            // === Request DTO construction ===
            if (hasRequestDto) {
                // PathSection from route path parameters
                methodHandlersBlock.append("\t\toPath = NEW ${def.requestDtoClassName}.PathSection().\r\n")
                dtoInfo.pathProperties.forEach { prop ->
                    methodHandlersBlock.append("\t\toPath:${prop.name} = poRequest:GetPathParameter(\"${prop.name}\").\r\n")
                }
                methodHandlersBlock.append("\r\n")

                // QuerySection with @Required validation
                methodHandlersBlock.append("\t\toQuer = NEW ${def.requestDtoClassName}.QuerySection().\r\n")
                dtoInfo.queryProperties.forEach { prop ->
                    when {
                        prop.ablType.uppercase() == "INTEGER" || prop.ablType.uppercase() == "INT64" -> {
                            methodHandlersBlock.append("\t\toQuer:${prop.name} = INTEGER(poRequest:GetQueryParameter(\"${prop.name}\")).\r\n")
                        }
                        prop.ablType.uppercase() == "DECIMAL" -> {
                            methodHandlersBlock.append("\t\toQuer:${prop.name} = DECIMAL(poRequest:GetQueryParameter(\"${prop.name}\")).\r\n")
                        }
                        prop.ablType.uppercase() == "LOGICAL" -> {
                            methodHandlersBlock.append("\t\toQuer:${prop.name} = LOGICAL(poRequest:GetQueryParameter(\"${prop.name}\")).\r\n")
                        }
                        else -> {
                            methodHandlersBlock.append("\t\toQuer:${prop.name} = poRequest:GetQueryParameter(\"${prop.name}\").\r\n")
                        }
                    }
                    if (prop.isRequired) {
                        methodHandlersBlock.append("\t\tIF oQuer:${prop.name} = ? THEN DO:\r\n")
                        methodHandlersBlock.append("\t\t\toResponse:StatusCode = 400.\r\n")
                        methodHandlersBlock.append("\t\t\toResponse:Entity = NEW Tatara.Api.ErrorResponse(\"Missing required parameter: ${prop.name}\").\r\n")
                        methodHandlersBlock.append("\t\t\tRETURN 0.\r\n")
                        methodHandlersBlock.append("\t\tEND.\r\n")
                    }
                }
                methodHandlersBlock.append("\r\n")

                // BodySection
                methodHandlersBlock.append("\t\toBody = NEW ${def.requestDtoClassName}.BodySection().\r\n")
                methodHandlersBlock.append("\t\tIF VALID-OBJECT(poRequest:Entity) THEN\r\n")
                methodHandlersBlock.append("\t\t\toBody = CAST(poRequest:Entity, ${def.requestDtoClassName}.BodySection).\r\n")
                methodHandlersBlock.append("\r\n")

                // Assemble top-level DTO
                methodHandlersBlock.append("\t\toReq = NEW ${def.requestDtoClassName}().\r\n")
                methodHandlersBlock.append("\t\tASSIGN\r\n")
                methodHandlersBlock.append("\t\t\toReq:path  = oPath\r\n")
                methodHandlersBlock.append("\t\t\toReq:query = oQuer\r\n")
                methodHandlersBlock.append("\t\t\toReq:body  = oBody.\r\n")
                methodHandlersBlock.append("\r\n")
            }

            // === Controller call ===
            methodHandlersBlock.append("\t\t$controllerVar = NEW $ctrlType().\r\n")
            methodHandlersBlock.append("\t\toResponse = NEW OpenEdge.Web.WebResponse().\r\n")

            // Legacy path: no typed DTOs at all
            if (!hasRequestDto && !hasResponseDto) {
                val sz = def.pathParams.size
                methodHandlersBlock.append("\t\tDEFINE VARIABLE oContext AS Tatara.Api.RequestContext NO-UNDO.\r\n")
                if (sz > 0) {
                    methodHandlersBlock.append("\t\tDEFINE VARIABLE cParams AS CHARACTER EXTENT $sz NO-UNDO.\r\n")
                }
                methodHandlersBlock.append("\r\n")
                if (sz > 0) {
                    def.pathParams.forEachIndexed { i, name ->
                        methodHandlersBlock.append("\t\tcParams[${i + 1}] = \"$name\".\r\n")
                    }
                    methodHandlersBlock.append("\t\toContext = Tatara.Api.RequestContextBuilder:FromWebRequest(poRequest, cParams).\r\n")
                } else {
                    methodHandlersBlock.append("\t\toContext = Tatara.Api.RequestContextBuilder:FromWebRequest(poRequest).\r\n")
                }
                methodHandlersBlock.append("\t\toResponse:StatusCode = 200.\r\n")
                methodHandlersBlock.append("\t\t$controllerVar:${def.ablMethod}(INPUT oContext, INPUT-OUTPUT oResponse).\r\n")
                methodHandlersBlock.append("\t\tTatara.Api.ResponseWriter:Write(poRequest, oResponse).\r\n")
                methodHandlersBlock.append("\t\tRETURN 0.\r\n")
                methodHandlersBlock.append("\tEND METHOD.")
                return@forEachIndexed
            }

            // === Typed handler call with CATCH blocks ===
            val catchLines = StringBuilder()
            def.errorResponses.forEach { (code, type) ->
                catchLines.append("\tCATCH err AS $type:\r\n")
                catchLines.append("\t\toResponse:StatusCode = $code.\r\n")
                catchLines.append("\t\toResponse:Entity = err.\r\n")
                catchLines.append("\t\tRETURN 0.\r\n")
            }
            catchLines.append("\tCATCH err AS Tatara.Api.ApiError:\r\n")
            catchLines.append("\t\toResponse:StatusCode = err:HttpCode.\r\n")
            catchLines.append("\t\toResponse:Entity = NEW Tatara.Api.ErrorResponse(err:Message).\r\n")
            catchLines.append("\t\tRETURN 0.\r\n")
            catchLines.append("\tCATCH err AS Progress.Lang.AppError:\r\n")
            catchLines.append("\t\toResponse:StatusCode = 500.\r\n")
            catchLines.append("\t\toResponse:Entity = NEW Tatara.Api.ErrorResponse(err:GetMessage()).\r\n")
            catchLines.append("\t\tRETURN 0.\r\n")
            catchLines.append("\tEND CATCH.\r\n")

            if (hasRequestDto && hasResponseDto) {
                methodHandlersBlock.append("\t\toResult = $controllerVar:${def.ablMethod}(INPUT oReq)\r\n")
            } else if (hasRequestDto) {
                methodHandlersBlock.append("\t\t$controllerVar:${def.ablMethod}(INPUT oReq)\r\n")
            } else {
                methodHandlersBlock.append("\t\toResult = $controllerVar:${def.ablMethod}()\r\n")
            }
            methodHandlersBlock.append(catchLines)
            methodHandlersBlock.append("\r\n")

            // Success
            methodHandlersBlock.append("\t\toResponse:StatusCode = 200.\r\n")
            if (hasResponseDto) {
                methodHandlersBlock.append("\t\toResponse:Entity = oResult.\r\n")
            }
            methodHandlersBlock.append("\t\tRETURN 0.\r\n")
            methodHandlersBlock.append("\tEND METHOD.")
        }

        val usingBlock = usingSet.joinToString("\r\n") { "USING $it." }
        val shimClassName = pathSanitize(routePath).replace("/", ".")
        val fileContent = template
            .replace("{{SHIM_CLASS_NAME}}", shimClassName)
            .replace("{{USING_BLOCK}}", usingBlock)
            .replace("{{METHOD_HANDLERS}}", methodHandlersBlock.toString())

        val fsPath = pathSanitize(routePath)
        val outputFile = File(outDir, "$fsPath.cls")
        outputFile.parentFile.mkdirs()
        outputFile.writeText(fileContent)
        logger.lifecycle("Rebuilt route shim: /$routePath [${routes.joinToString { it.httpMethod }}]")
    }

    private fun deleteShim(routePath: String, outDir: File) {
        val fsPath = pathSanitize(routePath)
        val shimFile = File(outDir, "$fsPath.cls")
        if (shimFile.exists() && shimFile.delete()) {
            logger.lifecycle("Deleted shim for abandoned route: /$routePath")
        }
    }

    private fun writeHandlersFile(serviceName: String, routes: List<RouteDef>, outDir: File) {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("    \"version\": \"2.0\",\n")
        sb.append("    \"serviceName\": \"$serviceName\",\n")
        sb.append("    \"handlers\": [\n")

        // Sort: most-specific (longest URI, most segments) first.
        // PASOE uses first-match-wins, so /project/{projectId}/spk must be checked
        // before /project/{projectId} or the generic entry swallows all sub-routes.
        val sorted = routes.sortedByDescending { it.routePath.count { c -> c == '/' } }

        sorted.forEachIndexed { idx, r ->
            val uri = "/" + r.routePath.removePrefix("$serviceName/")
            val clsName = pathSanitize(r.routePath).replace("/", ".")
            sb.append("        {\n")
            sb.append("            \"uri\": \"$uri\",\n")
            sb.append("            \"class\": \"$clsName\",\n")
            sb.append("            \"enabled\": true\n")
            sb.append("        }")
            if (idx < sorted.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("    ]\n")
        sb.append("}\n")

        val outFile = File(outDir, "$serviceName.handlers")
        outFile.parentFile.mkdirs()
        outFile.writeText(sb.toString())
        logger.lifecycle("Wrote handlers file: ${outFile.absolutePath} [${routes.size} route(s)]")
    }
}
