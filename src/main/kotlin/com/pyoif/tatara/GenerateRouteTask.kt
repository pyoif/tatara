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
        val pathParams: List<String> = emptyList()
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

    private fun routeKey(r: RouteDef): String = "${r.routePath}|${r.httpMethod}|${r.ablMethod}"

    private fun extractRoutesFromFile(file: File): List<RouteDef> {
        val classMatch = classRegex.find(file.readText()) ?: return emptyList()
        val className = classMatch.groupValues[1]

        val results = mutableListOf<RouteDef>()
        var pendingVerb: String? = null
        var pendingPath: String? = null
        var pendingPathParams: List<String> = emptyList()

        file.forEachLine { line ->
            httpVerbRegex.find(line)?.let { m ->
                pendingVerb = m.groupValues[1].uppercase()
                pendingPath = m.groupValues[3].let { if (it.startsWith("/")) it.substring(1) else it }
                pendingPathParams = pathParamRegex.findAll(m.groupValues[3]).map { it.groupValues[1] }.toList()
            }
            methodDefRegex.find(line)?.let { m ->
                if (pendingVerb != null && pendingPath != null) {
                    val ablMethod = m.groupValues[1]
                    if (results.none { it.routePath == pendingPath && it.httpMethod == pendingVerb }) {
                        results.add(RouteDef(pendingPath!!, pendingVerb!!, className, ablMethod, pendingPathParams))
                    }
                }
                // Always clear on METHOD line, even if the route was incomplete, so a stray
                // @VERB above a different method doesn't leak to the next one.
                pendingVerb = null
                pendingPath = null
                pendingPathParams = emptyList()
            }
        }
        return results
    }

    private fun loadStateCache(cacheFile: File): Map<String, List<RouteDef>> {
        val state = mutableMapOf<String, MutableList<RouteDef>>()
        if (cacheFile.exists()) {
            cacheFile.forEachLine { line ->
                val parts = line.split('|')
                if (parts.size == 6) {
                    val params = if (parts[5].isEmpty()) emptyList() else parts[5].split(',')
                    state.getOrPut(parts[0]) { mutableListOf() }
                        .add(RouteDef(parts[1], parts[2], parts[3], parts[4], params))
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
                    writer.println("$filePath|${r.routePath}|${r.httpMethod}|${r.className}|${r.ablMethod}|$paramsCsv")
                }
            }
        }
    }

    private fun pathSanitize(routePath: String): String {
        return routePath.replace(pathParamRegex, "_")
    }

    private fun writeShim(routePath: String, routes: List<RouteDef>, outDir: File, template: String) {
        val methodHandlersBlock = StringBuilder()

        routes.forEachIndexed { index, def ->
            val handleName = "Handle" + def.httpMethod.lowercase().replaceFirstChar { it.uppercase() }
            if (index > 0) methodHandlersBlock.append("\r\n\r\n")
            methodHandlersBlock.append("\tMETHOD OVERRIDE PROTECTED INTEGER $handleName(INPUT poRequest AS OpenEdge.Web.IWebRequest):\r\n")
            methodHandlersBlock.append("\t\tDEFINE VARIABLE oContext    AS common.RequestContext  NO-UNDO.\r\n")
            methodHandlersBlock.append("\t\tDEFINE VARIABLE oResponse   AS common.ResponseContext NO-UNDO.\r\n")
            methodHandlersBlock.append("\t\tDEFINE VARIABLE oController AS Progress.Lang.Object   NO-UNDO.\r\n")
            val sz = if (def.pathParams.isEmpty()) 0 else def.pathParams.size
            if (sz > 0) {
                methodHandlersBlock.append("\t\tDEFINE VARIABLE cParams     AS CHARACTER EXTENT $sz NO-UNDO.\r\n")
            }
            methodHandlersBlock.append("\r\n")
            if (sz > 0) {
                def.pathParams.forEachIndexed { i, name ->
                    methodHandlersBlock.append("\t\tcParams[${i + 1}] = \"$name\".\r\n")
                }
                methodHandlersBlock.append("\t\toContext = common.RequestContextBuilder:FromWebRequest(poRequest, cParams).\r\n")
            } else {
                methodHandlersBlock.append("\t\toContext = common.RequestContextBuilder:FromWebRequest(poRequest).\r\n")
            }
            methodHandlersBlock.append("\t\toResponse   = NEW common.ResponseContext().\r\n")
            methodHandlersBlock.append("\t\toController = NEW ${def.className}().\r\n")
            methodHandlersBlock.append("\r\n")
            methodHandlersBlock.append("\t\tDYNAMIC-INVOKE(oController, '${def.ablMethod}', oContext, oResponse).\r\n")
            methodHandlersBlock.append("\r\n")
            methodHandlersBlock.append("\t\tcommon.ResponseWriter:Write(poRequest, oResponse).\r\n")
            methodHandlersBlock.append("\r\n")
            methodHandlersBlock.append("\t\tRETURN 0.\r\n")
            methodHandlersBlock.append("\tEND METHOD.")
        }

        val shimClassName = pathSanitize(routePath).replace("/", ".")
        val fileContent = template
            .replace("{{SHIM_CLASS_NAME}}", shimClassName)
            .replace("{{METHOD_HANDLERS}}", methodHandlersBlock.toString())

        // Write to <outDir>/<sanitized-path>.cls — strip {param} from filesystem path.
        // The param placeholders are used ONLY in the .handlers file URI.
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
