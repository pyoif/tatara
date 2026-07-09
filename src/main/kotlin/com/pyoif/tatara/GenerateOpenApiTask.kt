package com.pyoif.tatara

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
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
    abstract val generatedDir: DirectoryProperty

    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:InputDirectory
    abstract val packagedDir: DirectoryProperty

    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:InputDirectory
    abstract val handlersDir: DirectoryProperty

    @get:OutputFile
    abstract val swaggerFile: RegularFileProperty

    @get:Input
    abstract val apiTitle: Property<String>

    @get:Input
    abstract val apiVersion: Property<String>

    @get:Input
    abstract val apiServerUrl: Property<String>

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

    @TaskAction
    fun generate() {
        val genRoot = generatedDir.get().asFile
        val pkgRoot = packagedDir.get().asFile
        val handlersRoot = handlersDir.get().asFile
        val gson = GsonBuilder().setPrettyPrinting().create()
        val routeParser = RouteParser()

        logger.lifecycle("GenerateOpenApiTask - generatedDir (shims): ${genRoot.absolutePath}")
        logger.lifecycle("GenerateOpenApiTask - packagedDir (sources): ${pkgRoot.absolutePath}")
        logger.lifecycle("GenerateOpenApiTask - handlersDir: ${handlersRoot.absolutePath}")

        val schemas = JsonObject()
        val parameters = JsonObject()
        val paths = JsonObject()
        val handlersFiles = handlersRoot.walkTopDown().filter { it.isFile && it.extension == "handlers" }.toList()
        val responseDtoClasses = mutableSetOf<String>()

        // First pass: collect response DTOs only
        handlersFiles.forEach { handlersFile ->
            collectResponseDtosFromHandlers(handlersFile, genRoot, pkgRoot, responseDtoClasses, routeParser)
        }

        // Add all response DTOs to schemas
        responseDtoClasses.forEach { dtoClass ->
            addDtoToSchemas(dtoClass, pkgRoot, schemas)
        }

        schemas.add("ErrorResponse", JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject().apply {
                add("error", JsonObject().apply { addProperty("type", "string") })
                add("message", JsonObject().apply { addProperty("type", "string") })
            })
        })

        // Second pass: build paths
        handlersFiles.forEach { handlersFile ->
            buildPathsFromHandlers(handlersFile, genRoot, pkgRoot, paths, schemas, parameters, routeParser)
        }

        val swagger = JsonObject().apply {
            addProperty("openapi", "3.0.3")
            add("info", JsonObject().apply {
                addProperty("title", apiTitle.get())
                addProperty("version", apiVersion.get())
            })
            add("servers", JsonArray().apply {
                add(JsonObject().apply { addProperty("url", apiServerUrl.get()) })
            })
            add("paths", paths)
            add("components", JsonObject().apply {
                add("schemas", schemas)
                if (parameters.size() > 0) add("parameters", parameters)
            })
        }

        val outFile = swaggerFile.get().asFile
        outFile.parentFile.mkdirs()
        outFile.writeText(gson.toJson(swagger))
        logger.lifecycle("Generated OpenAPI spec: ${outFile.absolutePath} (${paths.size()} paths, ${schemas.size()} schemas)")
    }

    private fun collectResponseDtosFromHandlers(handlersFile: File, genRoot: File, pkgRoot: File, dtoClasses: MutableSet<String>, routeParser: RouteParser) {
        val handlersJson = try {
            JsonParser.parseString(handlersFile.readText()).asJsonObject
        } catch (e: Exception) {
            return
        }
        val handlers = handlersJson.getAsJsonArray("handlers") ?: return

        handlers.forEach { handlerEl ->
            val handler = handlerEl.asJsonObject
            val clsName = handler.get("class")?.asString?.replace(".", "/") ?: return@forEach
            val shimFile = File(genRoot, "$clsName.cls")
            
            if (!shimFile.exists()) return@forEach
            
            val shimContent = shimFile.readText()
            val ctrlRegex = Regex("""ctrl\d+\s*=\s*NEW\s+([\w.]+)\(\)""")
            val ctrlMatch = ctrlRegex.find(shimContent) ?: return@forEach
            val ctrlClass = ctrlMatch.groupValues[1]
            
            val ctrlFile = findSourceFile(pkgRoot, ctrlClass) ?: return@forEach
            val routes = routeParser.extractRoutesFromFile(ctrlFile)
            
            routes.forEach { route ->
                if (route.responseDtoClassName != null && route.responseDtoClassName.uppercase() != "VOID") {
                    dtoClasses.add(route.responseDtoClassName)
                }
            }
        }
    }

    private fun addDtoToSchemas(dtoClass: String, pkgRoot: File, schemas: JsonObject) {
        val dtoFile = findSourceFile(pkgRoot, dtoClass) ?: return
        val dto = DtoParser.parse(dtoClass, pkgRoot)
        if (dto == DtoParser.DtoInfo.EMPTY) return

        val nameOnly = dtoClass.substringAfterLast('.')
        if (schemas.has(nameOnly)) return  // Already added

        val innerProps = JsonObject()
        val requiredArr = JsonArray()
        dto.properties.forEach { p ->
            innerProps.add(p.name, mapAblType(p.ablType, if (p.isExtent) "EXTENT" else null, schemas))
            if (p.isRequired) requiredArr.add(p.name)
        }

        val schema = JsonObject().apply {
            addProperty("type", "object")
            add("properties", innerProps)
            if (requiredArr.size() > 0) add("required", requiredArr)
        }
        schemas.add(nameOnly, schema)
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

    private fun findSourceFile(root: File, className: String): File? {
        val relative = className.replace(".", "/") + ".cls"
        val file = File(root, relative)
        logger.lifecycle("Searching for controller source: $relative in ${root.absolutePath}")
        logger.lifecycle("File exists: ${file.exists()} at ${file.absolutePath}")
        return file.takeIf { it.exists() }
    }

    private fun buildPathsFromHandlers(handlersFile: File, genRoot: File, pkgRoot: File, paths: JsonObject, schemas: JsonObject, parameters: JsonObject, routeParser: RouteParser) {
        val handlersJson = try {
            JsonParser.parseString(handlersFile.readText()).asJsonObject
        } catch (e: Exception) {
            logger.warn("Skipping invalid handlers file: ${handlersFile.absolutePath}")
            return
        }
        val handlers = handlersJson.getAsJsonArray("handlers") ?: return
        val serviceName = handlersFile.nameWithoutExtension
        logger.lifecycle("Processing handlers file: $serviceName (${handlers.size()} handlers)")

        handlers.forEach { handlerEl ->
            val handler = handlerEl.asJsonObject
            val uri = handler.get("uri")?.asString ?: return@forEach
            val clsName = handler.get("class")?.asString?.replace(".", "/") ?: return@forEach
            val shimFile = File(genRoot, "$clsName.cls")
            
            if (!shimFile.exists()) {
                logger.warn("Shim file not found: $shimFile")
                return@forEach
            }
            
            val shimContent = shimFile.readText()

            val ctrlRegex = Regex("""ctrl\d+\s*=\s*NEW\s+([\w.]+)\(\)""")
            val ctrlMatch = ctrlRegex.find(shimContent)
            if (ctrlMatch == null) {
                logger.warn("No controller found in shim: $shimFile")
                return@forEach
            }
            
            val ctrlClass = ctrlMatch.groupValues[1]
            logger.lifecycle("Found controller: $ctrlClass for handler uri: $uri")
            
            val ctrlFile = findSourceFile(pkgRoot, ctrlClass)
            if (ctrlFile == null) {
                logger.warn("Controller source file not found: $ctrlClass")
                return@forEach
            }

            val routes = routeParser.extractRoutesFromFile(ctrlFile)
            logger.lifecycle("Extracted ${routes.size} routes from $ctrlClass")
            routes.forEach { route ->
                val fullPath = "/" + route.routePath
                logger.lifecycle("Adding path: $fullPath [${route.httpMethod}]")
                buildPathFromRoute(route, fullPath, paths, schemas, parameters, pkgRoot)
            }
        }
    }

    private fun buildPathFromRoute(route: RouteDef, fullPath: String, paths: JsonObject, schemas: JsonObject, parameters: JsonObject, pkgRoot: File) {
        val pathObj = JsonObject()
        val params = JsonArray()
        val pathParamNames = route.pathParams.toSet()
        val supportsRequestBody = route.httpMethod.uppercase() in setOf("POST", "PUT", "PATCH")

        // Explicit route path params (inline)
        pathParamNames.forEach { pName ->
            params.add(JsonObject().apply {
                addProperty("name", pName)
                addProperty("in", "path")
                addProperty("required", true)
                add("schema", JsonObject().apply { addProperty("type", "string") })
            })
        }

        // Handle request DTO
        if (route.requestDtoClassName != null && route.requestDtoClassName != "Tatara.Api.RequestContext") {
            if (supportsRequestBody) {
                // For POST/PUT/PATCH: add full DTO to schemas as requestBody
                val reqName = route.requestDtoClassName.substringAfterLast('.')
                
                // Add request DTO to schemas if not already there
                if (!schemas.has(reqName)) {
                    addDtoToSchemas(route.requestDtoClassName, pkgRoot, schemas)
                }
                
                pathObj.add("requestBody", JsonObject().apply {
                    addProperty("required", true)
                    add("content", JsonObject().apply {
                        add("application/json", JsonObject().apply {
                            add("schema", JsonObject().apply {
                                addProperty("\$ref", "#/components/schemas/$reqName")
                            })
                        })
                    })
                })
            }
            
            // Extract query parameters from DTO (for all methods) — inline in path
            val dtoInfo = DtoParser.parse(route.requestDtoClassName, pkgRoot)
            dtoInfo.properties.forEach { prop ->
                if (prop.location == DtoParser.ParamLocation.QUERY) {
                    val paramSchema = if (prop.ablType.uppercase() in typeMap) {
                        JsonObject().apply {
                            typeMap[prop.ablType.uppercase()]!!.forEach { (k, v) ->
                                if (v is Number) addProperty(k, v.toInt())
                                else addProperty(k, v.toString())
                            }
                        }
                    } else {
                        val refName = prop.ablType.substringAfterLast('.')
                        if (!schemas.has(refName)) {
                            addDtoToSchemas(prop.ablType, pkgRoot, schemas)
                        }
                        JsonObject().apply { addProperty("\$ref", "#/components/schemas/$refName") }
                    }

                    if (prop.isExtent) {
                        params.add(JsonObject().apply {
                            addProperty("name", prop.name)
                            addProperty("in", "query")
                            addProperty("required", prop.isRequired)
                            add("schema", JsonObject().apply {
                                addProperty("type", "array")
                                add("items", paramSchema)
                            })
                        })
                    } else {
                        params.add(JsonObject().apply {
                            addProperty("name", prop.name)
                            addProperty("in", "query")
                            addProperty("required", prop.isRequired)
                            add("schema", paramSchema)
                        })
                    }
                }
            }
        }

        if (params.size() > 0) {
            pathObj.add("parameters", params)
        }

        val responses = JsonObject()
        val resp200 = JsonObject().apply {
            addProperty("description", "Success")
            if (route.responseDtoClassName != null && route.responseDtoClassName.uppercase() != "VOID") {
                val respName = route.responseDtoClassName.substringAfterLast('.')
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

        route.errorResponses.forEach { (code, type) ->
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

        setOf(400, 401, 403, 404, 409, 500).filter { it !in route.errorResponses }.forEach { code ->
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

        val method = route.httpMethod.lowercase()
        val existingPath = paths.getAsJsonObject(fullPath) ?: JsonObject()
        existingPath.add(method, pathObj)
        paths.add(fullPath, existingPath)
    }

    private fun httpStatusDescription(code: Int): String = when (code) {
        200 -> "Success"; 400 -> "Bad Request"; 401 -> "Unauthorized"
        403 -> "Forbidden"; 404 -> "Not Found"; 409 -> "Conflict"
        500 -> "Internal Server Error"; else -> "HTTP $code"
    }
}