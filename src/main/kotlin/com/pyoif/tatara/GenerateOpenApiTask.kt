package com.pyoif.tatara

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File

@DisableCachingByDefault(because = "Generated from source files that may change outside Gradle tracking")
abstract class GenerateOpenApiTask : DefaultTask() {

    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:InputDirectory
    abstract val packagedDir: DirectoryProperty

    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:InputDirectory
    abstract val handlersDir: DirectoryProperty

    @get:OutputFile
    abstract val swaggerFile: RegularFileProperty

    private val typeMap = mapOf(
        "CHARACTER" to mapOf("type" to "string"),
        "INTEGER"   to mapOf<String,Any>("type" to "integer"),
        "DECIMAL"   to mapOf<String,Any>("type" to "number"),
        "LOGICAL"   to mapOf<String,Any>("type" to "boolean"),
        "LONGCHAR"  to mapOf("type" to "string"),
        "DATETIME"  to mapOf("type" to "string", "format" to "date-time"),
        "DATETIME-TZ" to mapOf("type" to "string", "format" to "date-time"),
        "INT64"     to mapOf<String,Any>("type" to "integer", "format" to "int64")
    )

    private val httpVerbRegex = Regex("""(?i)@(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\(\s*(["'])([^"']+)\2\s*\)""")
    private val methodSigRegex = Regex(
        """(?i)^\s*METHOD\s+(?:PUBLIC|PROTECTED)\s+(\w+(?:\.\w+)*)\s+(\w+)\s*\(\s*(?:INPUT\s+\w+\s+AS\s+([\w.]+)\s*)?\)\s*[.:]"""
    )
    private val responseRegex = Regex("""@Response\(\s*(\d{3})\s*,\s*([\w.]+)\s*\)""")
    private val pathParamRegex = Regex("""\{(\w+)\}""")

    @TaskAction
    fun generate() {
        val pkgRoot = packagedDir.get().asFile
        val handlersRoot = handlersDir.get().asFile
        val gson = GsonBuilder().setPrettyPrinting().create()

        val schemas = JsonObject()
        collectDtoSchemas(pkgRoot, schemas)

        schemas.add("ErrorResponse", JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject().apply {
                add("error", JsonObject().apply { addProperty("type", "string") })
                add("message", JsonObject().apply { addProperty("type", "string") })
            })
        })

        val paths = JsonObject()
        val handlersFiles = handlersRoot.walkTopDown().filter { it.isFile && it.extension == "handlers" }.toList()

        handlersFiles.forEach { handlersFile ->
            buildPathsFromHandlers(handlersFile, pkgRoot, paths, schemas)
        }

        val swagger = JsonObject().apply {
            addProperty("openapi", "3.0.3")
            add("info", JsonObject().apply {
                addProperty("title", project.name)
                addProperty("version", project.version.toString())
            })
            add("servers", JsonArray().apply {
                add(JsonObject().apply { addProperty("url", "/api") })
            })
            add("paths", paths)
            add("components", JsonObject().apply { add("schemas", schemas) })
        }

        val outFile = swaggerFile.get().asFile
        outFile.parentFile.mkdirs()
        outFile.writeText(gson.toJson(swagger))
        logger.lifecycle("Generated OpenAPI spec: ${outFile.absolutePath} (${paths.size()} paths, ${schemas.size()} schemas)")
    }

    private fun collectDtoSchemas(root: File, schemas: JsonObject) {
        val portsDir = File(root, "app/ports")
        if (!portsDir.exists()) return

        portsDir.walkTopDown().filter { it.isFile && it.extension == "cls" }.forEach { file ->
            val content = file.readText()
            val className = content.lines()
                .firstOrNull { it.trimStart().startsWith("CLASS ") }
                ?.let { Regex("CLASS\\s+([\\w.]+)").find(it)?.groupValues?.get(1) }
                ?: return@forEach
            val nameOnly = className.substringAfterLast('.')

            val dto = DtoParser.parse(className, root)
            if (dto == DtoParser.DtoInfo.EMPTY) return@forEach

            val schema = JsonObject().apply { addProperty("type", "object") }
            val properties = JsonObject()
            val innerSchemas = mutableMapOf<String, JsonObject>()

            if (dto.pathProperties.isNotEmpty()) {
                properties.add("path", JsonObject().apply {
                    addProperty("\$ref", "#/components/schemas/${nameOnly}_PathSection")
                })
            }
            if (dto.queryProperties.isNotEmpty()) {
                properties.add("query", JsonObject().apply {
                    addProperty("\$ref", "#/components/schemas/${nameOnly}_QuerySection")
                })
            }
            if (dto.bodyProperties.isNotEmpty()) {
                properties.add("body", JsonObject().apply {
                    addProperty("\$ref", "#/components/schemas/${nameOnly}_BodySection")
                })
            }

            fun buildSectionSchema(props: List<DtoParser.DtoProperty>): JsonObject {
                val innerProps = JsonObject()
                val requiredArr = JsonArray()
                props.forEach { p ->
                    val extent = content.contains("AS ${p.ablType} EXTENT", ignoreCase = true)
                    val propSchema = mapAblType(p.ablType, if (extent) "EXTENT" else null, schemas)
                    innerProps.add(p.name, propSchema)
                    if (p.isRequired) requiredArr.add(p.name)
                }
                return JsonObject().apply {
                    addProperty("type", "object")
                    add("properties", innerProps)
                    if (requiredArr.size() > 0) add("required", requiredArr)
                }
            }

            if (dto.pathProperties.isNotEmpty())
                innerSchemas["${nameOnly}_PathSection"] = buildSectionSchema(dto.pathProperties)
            if (dto.queryProperties.isNotEmpty())
                innerSchemas["${nameOnly}_QuerySection"] = buildSectionSchema(dto.queryProperties)
            if (dto.bodyProperties.isNotEmpty())
                innerSchemas["${nameOnly}_BodySection"] = buildSectionSchema(dto.bodyProperties)

            schema.add("properties", properties)
            schemas.add(nameOnly, schema)
            innerSchemas.forEach { (key, value) -> schemas.add(key, value) }
        }
    }

    private fun mapAblType(abType: String, extent: String?, schemas: JsonObject): JsonObject {
        val key = abType.uppercase()
        val typeObj = if (key in typeMap) {
            JsonObject().apply {
                typeMap[key]!!.forEach { (k, v) ->
                    if (v is Number) addProperty(k, v.toInt())
                    else addProperty(k, v.toString())
                }
            }
        } else {
            val nameOnly = abType.substringAfterLast('.')
            JsonObject().apply { addProperty("\$ref", "#/components/schemas/$nameOnly") }
        }
        if (extent != null) {
            return JsonObject().apply {
                addProperty("type", "array")
                add("items", typeObj)
            }
        }
        return typeObj
    }

    private fun buildPathsFromHandlers(handlersFile: File, pkgRoot: File, paths: JsonObject, schemas: JsonObject) {
        val handlersJson = try {
            JsonParser.parseString(handlersFile.readText()).asJsonObject
        } catch (e: Exception) {
            logger.warn("Skipping invalid handlers file: ${handlersFile.absolutePath}")
            return
        }
        val handlers = handlersJson.getAsJsonArray("handlers") ?: return

        handlers.forEach { handlerEl ->
            val handler = handlerEl.asJsonObject
            val uri = handler.get("uri")?.asString ?: return@forEach
            val clsName = handler.get("class")?.asString?.replace(".", "/") ?: return@forEach
            val shimFile = File(pkgRoot, "$clsName.cls")
            if (!shimFile.exists()) return@forEach
            val shimContent = shimFile.readText()

            val ctrlRegex = Regex("""ctrl\d+\s*=\s*NEW\s+([\w.]+)\(\)""")
            val ctrlMatch = ctrlRegex.find(shimContent) ?: return@forEach
            val ctrlClass = ctrlMatch.groupValues[1]
            val ctrlFile = findSourceFile(pkgRoot, ctrlClass) ?: return@forEach

            parseRouteSource(ctrlFile, uri, paths, schemas)
        }
    }

    private fun findSourceFile(root: File, className: String): File? {
        val relative = className.replace(".", "/") + ".cls"
        return File(root, relative).takeIf { it.exists() }
    }

    private fun parseRouteSource(sourceFile: File, uri: String, paths: JsonObject, schemas: JsonObject) {
        val content = sourceFile.readText()

        var pendingVerb: String? = null
        var pendingPath: String? = null
        val pendingErrorResponses = mutableMapOf<Int, String>()

        sourceFile.forEachLine { line ->
            responseRegex.find(line)?.let { m ->
                pendingErrorResponses[m.groupValues[1].toInt()] = m.groupValues[2]
            }
            httpVerbRegex.find(line)?.let { m ->
                pendingVerb = m.groupValues[1]
                pendingPath = m.groupValues[3]
            }
            methodSigRegex.find(line)?.let { m ->
                val returnType = m.groupValues[1]
                val inputType = m.groupValues.getOrNull(3)?.takeIf { it.isNotEmpty() }

                val pathObj = JsonObject()

                val params = JsonArray()
                pathParamRegex.findAll(pendingPath ?: "").forEach { pm ->
                    params.add(JsonObject().apply {
                        addProperty("name", pm.groupValues[1])
                        addProperty("in", "path")
                        addProperty("required", true)
                        add("schema", JsonObject().apply { addProperty("type", "string") })
                    })
                }
                if (params.size() > 0) pathObj.add("parameters", params)

                if (inputType != null && inputType != "Tatara.Api.RequestContext") {
                    val dtoName = inputType.substringAfterLast('.')
                    val querySchemaName = "${dtoName}_QuerySection"
                    if (schemas.has(querySchemaName)) {
                        val querySchema = schemas.getAsJsonObject(querySchemaName)
                        val queryProps = querySchema.getAsJsonObject("properties") ?: JsonObject()
                        val requiredArr = querySchema.getAsJsonArray("required")
                        queryProps.keySet().forEach { propName ->
                            val propSchema = queryProps.getAsJsonObject(propName)
                            val isRequired = requiredArr?.contains(com.google.gson.JsonPrimitive(propName)) ?: false
                            params.add(JsonObject().apply {
                                addProperty("name", propName)
                                addProperty("in", "query")
                                addProperty("required", isRequired)
                                add("schema", propSchema)
                            })
                        }
                    }

                    val bodySchemaName = "${dtoName}_BodySection"
                    if (schemas.has(bodySchemaName)) {
                        pathObj.add("requestBody", JsonObject().apply {
                            addProperty("required", true)
                            add("content", JsonObject().apply {
                                add("application/json", JsonObject().apply {
                                    add("schema", JsonObject().apply {
                                        addProperty("\$ref", "#/components/schemas/$bodySchemaName")
                                    })
                                })
                            })
                        })
                    }
                }
                if (params.size() > 0) pathObj.add("parameters", params)

                val responses = JsonObject()
                val resp200 = JsonObject().apply {
                    addProperty("description", "Success")
                    if (returnType.uppercase() != "VOID") {
                        val respName = returnType.substringAfterLast('.')
                        add("content", JsonObject().apply {
                            add("application/json", JsonObject().apply {
                                add("schema", JsonObject().apply {
                                    addProperty("\$ref", "#/components/schemas/$respName")
                                })
                            })
                        })
                    }
                }
                responses.add("200", resp200)

                pendingErrorResponses.forEach { (code, type) ->
                    val typeName = type.substringAfterLast('.')
                    responses.add(code.toString(), JsonObject().apply {
                        addProperty("description", httpStatusDescription(code))
                        add("content", JsonObject().apply {
                            add("application/json", JsonObject().apply {
                                add("schema", JsonObject().apply {
                                    addProperty("\$ref", "#/components/schemas/$typeName")
                                })
                            })
                        })
                    })
                }

                setOf(400, 401, 403, 404, 409, 500).filter { it !in pendingErrorResponses }.forEach { code ->
                    responses.add(code.toString(), JsonObject().apply {
                        addProperty("description", httpStatusDescription(code))
                        add("content", JsonObject().apply {
                            add("application/json", JsonObject().apply {
                                add("schema", JsonObject().apply {
                                    addProperty("\$ref", "#/components/schemas/ErrorResponse")
                                })
                            })
                        })
                    })
                }

                pathObj.add("responses", responses)

                val method = pendingVerb?.lowercase() ?: return@let
                val fullPath = pendingPath ?: return@let
                val existingPath = paths.getAsJsonObject(fullPath) ?: JsonObject()
                existingPath.add(method, pathObj)
                paths.add(fullPath, existingPath)
            }
        }
    }

    private fun httpStatusDescription(code: Int): String = when (code) {
        200 -> "Success"; 400 -> "Bad Request"; 401 -> "Unauthorized"
        403 -> "Forbidden"; 404 -> "Not Found"; 409 -> "Conflict"
        500 -> "Internal Server Error"; else -> "HTTP $code"
    }
}
