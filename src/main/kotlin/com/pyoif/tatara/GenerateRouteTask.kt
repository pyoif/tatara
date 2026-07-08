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

    private val httpVerbRegex = Regex(
        """(?i)@(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\(\s*(["'])([^"']+)\2\s*\)"""
    )
    private val classRegex = Regex("""CLASS\s+([\w.]+)""")
    private val methodDefRegex = Regex(
        """(?i)^\s*METHOD\s+(?:PUBLIC|PROTECTED)\s+(?:\w+\s+)?(\w+)\s*[(]"""
    )
    private val pathParamRegex = Regex("""\{(\w+)\}""")
    private val responseAnnotationRegex = Regex(
        """@Response\(\s*(\d{3})\s*,\s*([\w.]+)\s*\)"""
    )
    private val methodSigRegex = Regex(
        """(?i)^\s*METHOD\s+(?:PUBLIC|PROTECTED)\s+(\w+(?:\.\w+)*)\s+(\w+)\s*\(\s*(?:INPUT\s+\w+\s+AS\s+([\w.]+)\s*)?\)\s*:"""
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

        previousRoutesByFile.forEach { (path, routes) ->
            if (!currentRoutesByFile.containsKey(path)) {
                currentRoutesByFile[path] = routes
            }
        }

        val allRoutes = currentRoutesByFile.values.flatten()
        val routesByPath = allRoutes.groupBy { it.routePath }
        val template = javaClass.getResource("/RouteShimTemplate.cls")?.readText()
            ?: throw IllegalStateException("Missing RouteShimTemplate.cls resource")

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

        val allHandlersRoutes = currentRoutesByFile.values.flatten()
        val routesByService = allHandlersRoutes.groupBy { it.routePath.substringBefore("/", missingDelimiterValue = "") }
            .filter { it.key.isNotEmpty() }

        val handlersOutDir = handlersDir.get().asFile
        handlersOutDir.mkdirs()
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
            responseAnnotationRegex.find(line)?.let { m ->
                pendingErrorResponses[m.groupValues[1].toInt()] = m.groupValues[2]
            }
            httpVerbRegex.find(line)?.let { m ->
                pendingVerb = m.groupValues[1].uppercase()
                pendingPath = m.groupValues[3].let { if (it.startsWith("/")) it.substring(1) else it }
                pendingPathParams = pathParamRegex.findAll(m.groupValues[3]).map { it.groupValues[1] }.toList()
            }
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

            if (hasRequestDto) usingSet.add(def.requestDtoClassName!!)
            if (hasResponseDto) usingSet.add(def.responseDtoClassName!!)
            def.errorResponses.values.forEach { usingSet.add(it) }

            methodHandlersBlock.append("\tMETHOD OVERRIDE PROTECTED INTEGER $handleName(INPUT poRequest AS OpenEdge.Web.IWebRequest):\r\n")

            if (hasRequestDto) {
                methodHandlersBlock.append("\t\tDEFINE VARIABLE oReq   AS ${def.requestDtoClassName} NO-UNDO.\r\n")
            }
            if (hasResponseDto) {
                methodHandlersBlock.append("\t\tDEFINE VARIABLE oResult AS ${def.responseDtoClassName} NO-UNDO.\r\n")
            }
            methodHandlersBlock.append("\t\tDEFINE VARIABLE oResponse AS OpenEdge.Web.WebResponse NO-UNDO.\r\n")
            if (index == 0 || def.className != routes[0].className) {
                methodHandlersBlock.append("\t\tDEFINE VARIABLE $controllerVar AS $ctrlType NO-UNDO.\r\n")
            }
            methodHandlersBlock.append("\r\n")

            if (hasRequestDto) {
                methodHandlersBlock.append("\t\toReq = NEW ${def.requestDtoClassName}().\r\n")
                
                val pathProps = dtoInfo.properties.filter { it.location == DtoParser.ParamLocation.PATH || (it.location == DtoParser.ParamLocation.UNKNOWN && def.pathParams.contains(it.name)) }
                val bodyProps = dtoInfo.properties.filter { it.location == DtoParser.ParamLocation.BODY || (it.location == DtoParser.ParamLocation.UNKNOWN && it.name.equals("body", ignoreCase = true)) }
                val queryProps = dtoInfo.properties.filter { !pathProps.contains(it) && !bodyProps.contains(it) }

                // Path mappings
                def.pathParams.forEach { paramName ->
                    methodHandlersBlock.append("\t\toReq:$paramName = poRequest:GetPathParameter(\"$paramName\").\r\n")
                }

                // Query mappings
                if (queryProps.isNotEmpty()) {
                    methodHandlersBlock.append("\t\tDEFINE VARIABLE oQueryParams AS Progress.Json.ObjectModel.JsonObject NO-UNDO.\r\n")
                    methodHandlersBlock.append("\t\tDEFINE VARIABLE cQuery AS CHARACTER NO-UNDO.\r\n")
                    methodHandlersBlock.append("\t\tDEFINE VARIABLE cPair AS CHARACTER NO-UNDO.\r\n")
                    methodHandlersBlock.append("\t\tDEFINE VARIABLE cKey AS CHARACTER NO-UNDO.\r\n")
                    methodHandlersBlock.append("\t\tDEFINE VARIABLE cValue AS CHARACTER NO-UNDO.\r\n")
                    methodHandlersBlock.append("\t\tDEFINE VARIABLE iPos AS INTEGER NO-UNDO.\r\n")
                    methodHandlersBlock.append("\t\tDEFINE VARIABLE ix AS INTEGER NO-UNDO.\r\n")
                    methodHandlersBlock.append("\t\toQueryParams = NEW Progress.Json.ObjectModel.JsonObject().\r\n")
                    methodHandlersBlock.append("\t\tcQuery = poRequest:URI:QueryString.\r\n")
                    methodHandlersBlock.append("\t\tIF cQuery <> \"\" AND cQuery <> ? THEN DO:\r\n")
                    methodHandlersBlock.append("\t\t\tIF cQuery BEGINS \"?\" THEN cQuery = SUBSTRING(cQuery, 2).\r\n")
                    methodHandlersBlock.append("\t\t\tDO ix = 1 TO NUM-ENTRIES(cQuery, '&'):\r\n")
                    methodHandlersBlock.append("\t\t\t\tcPair = ENTRY(ix, cQuery, '&').\r\n")
                    methodHandlersBlock.append("\t\t\t\tiPos = INDEX(cPair, \"=\").\r\n")
                    methodHandlersBlock.append("\t\t\t\tIF iPos > 0 THEN DO:\r\n")
                    methodHandlersBlock.append("\t\t\t\t\tcKey = TRIM(SUBSTRING(cPair, 1, iPos - 1)).\r\n")
                    methodHandlersBlock.append("\t\t\t\t\tcValue = TRIM(SUBSTRING(cPair, iPos + 1)).\r\n")
                    methodHandlersBlock.append("\t\t\t\t\tcValue = OpenEdge.Net.URI:Decode(cValue).\r\n")
                    methodHandlersBlock.append("\t\t\t\t\toQueryParams:Add(cKey, cValue).\r\n")
                    methodHandlersBlock.append("\t\t\t\tEND.\r\n")
                    methodHandlersBlock.append("\t\t\t\tELSE oQueryParams:Add(TRIM(cPair), \"\").\r\n")
                    methodHandlersBlock.append("\t\t\tEND.\r\n")
                    methodHandlersBlock.append("\t\tEND.\r\n")
                    methodHandlersBlock.append("\r\n")
                    
                    queryProps.forEach { prop ->
                        methodHandlersBlock.append("\t\tIF oQueryParams:Has(\"${prop.name}\") AND NOT oQueryParams:IsNull(\"${prop.name}\") THEN DO:\r\n")
                        when (prop.ablType.uppercase()) {
                            "INTEGER", "INT64" -> methodHandlersBlock.append("\t\t\toReq:${prop.name} = oQueryParams:GetInteger(\"${prop.name}\").\r\n")
                            "DECIMAL" -> methodHandlersBlock.append("\t\t\toReq:${prop.name} = oQueryParams:GetDecimal(\"${prop.name}\").\r\n")
                            "LOGICAL" -> methodHandlersBlock.append("\t\t\toReq:${prop.name} = oQueryParams:GetLogical(\"${prop.name}\").\r\n")
                            else -> methodHandlersBlock.append("\t\t\toReq:${prop.name} = oQueryParams:GetCharacter(\"${prop.name}\").\r\n")
                        }
                        methodHandlersBlock.append("\t\tEND.\r\n")
                        if (prop.isRequired) {
                            methodHandlersBlock.append("\t\tELSE DO:\r\n")
                            methodHandlersBlock.append("\t\t\toResponse:StatusCode = 400.\r\n")
                            methodHandlersBlock.append("\t\t\toResponse:Entity = NEW Tatara.Api.ErrorResponse(\"Missing required query parameter: ${prop.name}\").\r\n")
                            methodHandlersBlock.append("\t\t\tRETURN 0.\r\n")
                            methodHandlersBlock.append("\t\tEND.\r\n")
                        }
                    }
                }
                
                // Native JSON Deserialization mappings
                if (bodyProps.isNotEmpty()) {
                    methodHandlersBlock.append("\t\tIF VALID-OBJECT(poRequest:Entity) AND TYPE-OF(poRequest:Entity, Progress.Json.ObjectModel.JsonObject) THEN DO:\r\n")
                    methodHandlersBlock.append("\t\t\tDEFINE VARIABLE oJson AS Progress.Json.ObjectModel.JsonObject NO-UNDO.\r\n")
                    methodHandlersBlock.append("\t\t\toJson = CAST(poRequest:Entity, Progress.Json.ObjectModel.JsonObject).\r\n")
                    bodyProps.forEach { prop ->
                        methodHandlersBlock.append("\t\t\tIF oJson:Has(\"${prop.name}\") AND NOT oJson:IsNull(\"${prop.name}\") THEN DO:\r\n")
                        when (prop.ablType.uppercase()) {
                            "INTEGER", "INT64" -> methodHandlersBlock.append("\t\t\t\toReq:${prop.name} = oJson:GetInteger(\"${prop.name}\").\r\n")
                            "DECIMAL" -> methodHandlersBlock.append("\t\t\t\toReq:${prop.name} = oJson:GetDecimal(\"${prop.name}\").\r\n")
                            "LOGICAL" -> methodHandlersBlock.append("\t\t\t\toReq:${prop.name} = oJson:GetLogical(\"${prop.name}\").\r\n")
                            "DATETIME", "DATETIME-TZ" -> methodHandlersBlock.append("\t\t\t\toReq:${prop.name} = oJson:GetDatetime(\"${prop.name}\").\r\n")
                            "LONGCHAR", "CHARACTER" -> methodHandlersBlock.append("\t\t\t\toReq:${prop.name} = oJson:GetCharacter(\"${prop.name}\").\r\n")
                            else -> methodHandlersBlock.append("\t\t\t\t/* Nested complex objects not fully mapped natively */\r\n")
                        }
                        methodHandlersBlock.append("\t\t\tEND.\r\n")
                        if (prop.isRequired) {
                            methodHandlersBlock.append("\t\t\tELSE DO:\r\n")
                            methodHandlersBlock.append("\t\t\t\toResponse:StatusCode = 400.\r\n")
                            methodHandlersBlock.append("\t\t\t\toResponse:Entity = NEW Tatara.Api.ErrorResponse(\"Missing required body parameter: ${prop.name}\").\r\n")
                            methodHandlersBlock.append("\t\t\t\tRETURN 0.\r\n")
                            methodHandlersBlock.append("\t\t\tEND.\r\n")
                        }
                    }
                    methodHandlersBlock.append("\t\tEND.\r\n")

                    // Fallback block if entity happens to be plain text or object bound directly to body
                    val rawBodyProp = bodyProps.find { it.name.equals("body", ignoreCase = true) && (it.ablType.uppercase() == "LONGCHAR" || it.ablType.uppercase() == "CHARACTER") }
                    if (rawBodyProp != null) {
                        methodHandlersBlock.append("\t\tELSE IF VALID-OBJECT(poRequest:Entity) THEN DO:\r\n")
                        methodHandlersBlock.append("\t\t\tIF TYPE-OF(poRequest:Entity, OpenEdge.Core.String) THEN\r\n")
                        methodHandlersBlock.append("\t\t\t\toReq:${rawBodyProp.name} = CAST(poRequest:Entity, OpenEdge.Core.String):Value.\r\n")
                        methodHandlersBlock.append("\t\t\tELSE oReq:${rawBodyProp.name} = poRequest:Entity:ToString().\r\n")
                        methodHandlersBlock.append("\t\tEND.\r\n")
                    }
                }
                methodHandlersBlock.append("\r\n")
            }

            methodHandlersBlock.append("\t\t$controllerVar = NEW $ctrlType().\r\n")
            methodHandlersBlock.append("\t\toResponse = NEW OpenEdge.Web.WebResponse().\r\n")
            methodHandlersBlock.append("\t\tDO ON ERROR UNDO, THROW:\r\n")

            if (!hasRequestDto && !hasResponseDto) {
                val sz = def.pathParams.size
                methodHandlersBlock.append("\t\t\tDEFINE VARIABLE oContext AS Tatara.Api.RequestContext NO-UNDO.\r\n")
                if (sz > 0) {
                    methodHandlersBlock.append("\t\t\tDEFINE VARIABLE cParams AS CHARACTER EXTENT $sz NO-UNDO.\r\n")
                    def.pathParams.forEachIndexed { i, name ->
                        methodHandlersBlock.append("\t\t\tcParams[${i + 1}] = \"$name\".\r\n")
                    }
                    methodHandlersBlock.append("\t\t\toContext = Tatara.Api.RequestContextBuilder:FromWebRequest(poRequest, cParams).\r\n")
                } else {
                    methodHandlersBlock.append("\t\t\toContext = Tatara.Api.RequestContextBuilder:FromWebRequest(poRequest).\r\n")
                }
                methodHandlersBlock.append("\t\t\toResponse:StatusCode = 200.\r\n")
                methodHandlersBlock.append("\t\t\t$controllerVar:${def.ablMethod}(INPUT oContext, INPUT-OUTPUT oResponse).\r\n")
                methodHandlersBlock.append("\t\t\tTatara.Api.ResponseWriter:Write(poRequest, oResponse).\r\n")
                methodHandlersBlock.append("\t\t\tRETURN 0.\r\n")
                methodHandlersBlock.append("\t\tEND.\r\n")
                methodHandlersBlock.append("\tEND METHOD.")
                return@forEachIndexed
            }

            if (hasRequestDto && hasResponseDto) {
                methodHandlersBlock.append("\t\t\tASSIGN oResult = $controllerVar:${def.ablMethod}(INPUT oReq).\r\n")
            } else if (hasRequestDto) {
                methodHandlersBlock.append("\t\t\t$controllerVar:${def.ablMethod}(INPUT oReq).\r\n")
            } else {
                methodHandlersBlock.append("\t\t\tASSIGN oResult = $controllerVar:${def.ablMethod}().\r\n")
            }

            def.errorResponses.forEach { (code, type) ->
                methodHandlersBlock.append("\t\t\tCATCH errCustom AS $type:\r\n")
                methodHandlersBlock.append("\t\t\t\toResponse:StatusCode = $code.\r\n")
                methodHandlersBlock.append("\t\t\t\toResponse:Entity = errCustom.\r\n")
                methodHandlersBlock.append("\t\t\t\tRETURN 0.\r\n")
                methodHandlersBlock.append("\t\t\tEND.\r\n")
            }
            methodHandlersBlock.append("\t\t\tCATCH errApi AS Tatara.Api.ApiError:\r\n")
            methodHandlersBlock.append("\t\t\t\toResponse:StatusCode = errApi:HttpCode.\r\n")
            methodHandlersBlock.append("\t\t\t\toResponse:Entity = NEW Tatara.Api.ErrorResponse(errApi:GetMessage(1)).\r\n")
            methodHandlersBlock.append("\t\t\t\tRETURN 0.\r\n")
            methodHandlersBlock.append("\t\t\tEND.\r\n")
            methodHandlersBlock.append("\t\t\tCATCH errApp AS Progress.Lang.AppError:\r\n")
            methodHandlersBlock.append("\t\t\t\toResponse:StatusCode = 500.\r\n")
            methodHandlersBlock.append("\t\t\t\toResponse:Entity = NEW Tatara.Api.ErrorResponse(errApp:GetMessage(1)).\r\n")
            methodHandlersBlock.append("\t\t\t\tRETURN 0.\r\n")
            methodHandlersBlock.append("\t\t\tEND.\r\n")
            methodHandlersBlock.append("\t\tEND.\r\n")
            methodHandlersBlock.append("\r\n")

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