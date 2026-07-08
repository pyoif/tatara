# Swagger Integration with Typed DTOs — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace generic `RequestContext`/`ResponseContext` with typed DTOs inferred from handler method signatures, validate `@Required` properties in shim before handler call, add typed error handling via `@Response(non-200, Type)`, generate OpenAPI `swagger.json`, and bundle Swagger UI in the WAR.

**Architecture:** `GenerateRouteTask` parses ABL handler method signatures to extract request DTO type (INPUT param), response DTO type (return type), and optional `@Response(non-200, Type)` annotations. A DTO parser walks the request DTO class file to extract PathSection/QuerySection/BodySection properties and `@Required` flags. The shim generator uses this metadata to construct typed DTOs with parameter extraction and validation, call handlers with typed INPUT, and wrap calls in CATCH blocks. A new `GenerateOpenApiTask` walks DTO classes to produce `swagger.json`. A single `swagger/index.html` is bundled into the WAR.

**Tech Stack:** Kotlin (Gradle plugin), OE ABL 12+ (shim output), OpenAPI 3.0 (JSON), Swagger UI 5.11.0 (unpkg CDN), Gson 2.10.1 (JSON building)

## Global Constraints

- ABL source: OpenEdge 12+; no generics, no declared exceptions
- Plugin: Kotlin Gradle DSL
- Dependencies: `gradleApi()` + `com.google.code.gson:gson:2.10.1`
- Backwards compat: existing `RequestContext`-style handlers must still work
- All file paths use forward slashes in code, platform agnostic at runtime
- Swagger UI: single `swagger/index.html` file, assets loaded from unpkg CDN
- DTO source files live in `srcDir` alongside handlers under `app/ports/`; the task reads them from the same input directory

---

### Task 1: Add Gson dependency and extend RouteDef

**Files:**
- Modify: `build.gradle.kts`
- Modify: `src/main/kotlin/com/pyoif/tatara/GenerateRouteTask.kt`

**Interfaces:**
- Produces: `RouteDef` with `requestDtoClassName: String?`, `responseDtoClassName: String?`, `errorResponses: Map<Int, String>`

- [ ] **Step 1: Add Gson to build.gradle.kts**

Add inside the `dependencies` block:
```kotlin
    implementation("com.google.code.gson:gson:2.10.1")
```

Run:
```bash
./gradlew build
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Extend RouteDef data class**

In `GenerateRouteTask.kt`, replace:
```kotlin
    data class RouteDef(
        val routePath: String,
        val httpMethod: String,
        val className: String,
        val ablMethod: String,
        val pathParams: List<String> = emptyList()
    )
```
with:
```kotlin
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
```

Run: `./gradlew build`

- [ ] **Step 3: Commit**

```bash
git add build.gradle.kts src/main/kotlin/com/pyoif/tatara/GenerateRouteTask.kt
git commit -m "feat: add Gson dependency, extend RouteDef with DTO type fields"
```

---

### Task 2: Parse method signatures for INPUT type, return type, and @Response

**Files:**
- Modify: `src/main/kotlin/com/pyoif/tatara/GenerateRouteTask.kt`

**Interfaces:**
- Consumes: `RouteDef` fields from Task 1
- Produces: populated `RouteDef.requestDtoClassName`, `.responseDtoClassName`, `.errorResponses`

- [ ] **Step 1: Add new regex patterns**

After existing `pathParamRegex`, add:
```kotlin
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
```

Run: `./gradlew build`

- [ ] **Step 2: Rewrite extractRoutesFromFile**

Replace the entire `extractRoutesFromFile` method:
```kotlin
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
```

Run: `./gradlew build`

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/pyoif/tatara/GenerateRouteTask.kt
git commit -m "feat: parse method INPUT type, return type, and @Response annotation"
```

---

### Task 3: Create DtoParser for DTO class property extraction

**Files:**
- Create: `src/main/kotlin/com/pyoif/tatara/DtoParser.kt`

**Interfaces:**
- Consumes: DTO `.cls` file locations, `srcRoot` directory
- Produces: `DtoParser.DtoProperty`, `DtoParser.DtoInfo`, `DtoParser.parse(dtoClassName: String, srcRoot: File): DtoInfo`

- [ ] **Step 1: Write DtoParser.kt**

```kotlin
package com.pyoif.tatara

import java.io.File

object DtoParser {

    data class DtoProperty(
        val name: String,
        val ablType: String,
        val isRequired: Boolean = false
    )

    data class DtoInfo(
        val pathProperties: List<DtoProperty> = emptyList(),
        val queryProperties: List<DtoProperty> = emptyList(),
        val bodyProperties: List<DtoProperty> = emptyList()
    ) {
        companion object {
            val EMPTY = DtoInfo()
        }
    }

    private val propDefRegex = Regex(
        """(?i)DEFINE\s+PUBLIC\s+PROPERTY\s+(\w+)\s+AS\s+(\w+(?:[.-]\w+)*)"""
    )
    private val requiredRegex = Regex("""(?i)//\s*@Required""")
    private val sectionRegex = Regex(
        """(?i)CLASS\s+(PathSection|QuerySection|BodySection)\s*[:\s](.*?)END\s+CLASS\.""",
        RegexOption.DOT_MATCHES_ALL
    )

    fun parse(dtoClassName: String, srcRoot: File): DtoInfo {
        val file = resolveFile(dtoClassName, srcRoot) ?: return DtoInfo.EMPTY
        val content = file.readText()
        val pathProps = mutableListOf<DtoProperty>()
        val queryProps = mutableListOf<DtoProperty>()
        val bodyProps = mutableListOf<DtoProperty>()

        sectionRegex.findAll(content).forEach { sectionMatch ->
            val sectionName = sectionMatch.groupValues[1]
            val sectionBody = sectionMatch.groupValues[2]
            val targetList = when (sectionName) {
                "PathSection" -> pathProps
                "QuerySection" -> queryProps
                "BodySection" -> bodyProps
                else -> return@forEach
            }
            parseSection(sectionBody, targetList)
        }
        return DtoInfo(pathProps, queryProps, bodyProps)
    }

    private fun resolveFile(dtoClassName: String, srcRoot: File): File? {
        val relativePath = dtoClassName.replace(".", "/") + ".cls"
        val file = File(srcRoot, relativePath)
        return if (file.exists()) file else null
    }

    private fun parseSection(sectionBody: String, targetList: MutableList<DtoProperty>) {
        val lines = sectionBody.lines()
        var previousLineHasRequired = false
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (requiredRegex.containsMatchIn(line)) {
                previousLineHasRequired = true
                continue
            }
            propDefRegex.find(line)?.let { m ->
                targetList.add(DtoProperty(
                    m.groupValues[1],
                    m.groupValues[2],
                    previousLineHasRequired || requiredRegex.containsMatchIn(line)
                ))
                previousLineHasRequired = false
            }
        }
    }
}
```

Run:
```bash
./gradlew build
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Commit**

```bash
git add src/main/kotlin/com/pyoif/tatara/DtoParser.kt
git commit -m "feat: add DtoParser for DTO class property extraction and @Required detection"
```

---

### Task 4: Update route cache serialization

**Files:**
- Modify: `src/main/kotlin/com/pyoif/tatara/GenerateRouteTask.kt`

**Interfaces:**
- Consumes: new `RouteDef` fields from Task 2
- Produces: updated `routeKey()`, `loadStateCache()`, `saveStateCache()`

- [ ] **Step 1: Update routeKey**

Replace:
```kotlin
    private fun routeKey(r: RouteDef): String = "${r.routePath}|${r.httpMethod}|${r.ablMethod}"
```
with:
```kotlin
    private fun routeKey(r: RouteDef): String =
        "${r.routePath}|${r.httpMethod}|${r.ablMethod}|${r.requestDtoClassName ?: ""}|${r.responseDtoClassName ?: ""}|" +
        r.errorResponses.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
```

- [ ] **Step 2: Update loadStateCache**

Replace entire `loadStateCache`:
```kotlin
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
```

- [ ] **Step 3: Update saveStateCache**

Replace entire `saveStateCache`:
```kotlin
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
```

Run: `./gradlew build`

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/pyoif/tatara/GenerateRouteTask.kt
git commit -m "feat: update route cache serialization for DTO type fields"
```

---

### Task 5: Create Tatara.Api error classes

**Files:**
- Create: `src/main/resources/Tatara/Api/ApiError.cls`
- Create: `src/main/resources/Tatara/Api/ErrorResponse.cls`
- Create: `src/main/resources/Tatara/Api/BadRequestError.cls`
- Create: `src/main/resources/Tatara/Api/UnauthorizedError.cls`
- Create: `src/main/resources/Tatara/Api/ForbiddenError.cls`
- Create: `src/main/resources/Tatara/Api/NotFoundError.cls`
- Create: `src/main/resources/Tatara/Api/ConflictError.cls`
- Create: `src/main/resources/Tatara/Api/InternalError.cls`

**Interfaces:**
- Produces: `Tatara.Api.ApiError` base class (HttpCode + Message), six subclasses for HTTP codes, `ErrorResponse` serializable DTO

- [ ] **Step 1: Create all 8 files**

**ApiError.cls:**
```
CLASS Tatara.Api.ApiError INHERITS Progress.Lang.AppError:

    DEFINE PUBLIC PROPERTY HttpCode AS INTEGER   NO-UNDO GET. SET.
    DEFINE PUBLIC PROPERTY Message AS CHARACTER NO-UNDO GET. SET.

    CONSTRUCTOR PUBLIC ApiError(INPUT piCode AS INTEGER, INPUT pcMessage AS CHARACTER):
        SUPER().
        HttpCode = piCode.
        Message = pcMessage.
    END CONSTRUCTOR.

END CLASS.
```

**ErrorResponse.cls:**
```
CLASS Tatara.Api.ErrorResponse:

    DEFINE PUBLIC PROPERTY error   AS CHARACTER NO-UNDO GET. SET.
    DEFINE PUBLIC PROPERTY message AS CHARACTER NO-UNDO GET. SET.

    CONSTRUCTOR PUBLIC ErrorResponse(INPUT pcMessage AS CHARACTER):
        ASSIGN error = "" message = pcMessage.
    END CONSTRUCTOR.

END CLASS.
```

**BadRequestError.cls:**
```
CLASS Tatara.Api.BadRequestError INHERITS Tatara.Api.ApiError:

    CONSTRUCTOR PUBLIC BadRequestError(INPUT pcMessage AS CHARACTER):
        SUPER(400, pcMessage).
    END CONSTRUCTOR.

END CLASS.
```

**UnauthorizedError.cls:**
```
CLASS Tatara.Api.UnauthorizedError INHERITS Tatara.Api.ApiError:

    CONSTRUCTOR PUBLIC UnauthorizedError(INPUT pcMessage AS CHARACTER):
        SUPER(401, pcMessage).
    END CONSTRUCTOR.

END CLASS.
```

**ForbiddenError.cls:**
```
CLASS Tatara.Api.ForbiddenError INHERITS Tatara.Api.ApiError:

    CONSTRUCTOR PUBLIC ForbiddenError(INPUT pcMessage AS CHARACTER):
        SUPER(403, pcMessage).
    END CONSTRUCTOR.

END CLASS.
```

**NotFoundError.cls:**
```
CLASS Tatara.Api.NotFoundError INHERITS Tatara.Api.ApiError:

    CONSTRUCTOR PUBLIC NotFoundError(INPUT pcMessage AS CHARACTER):
        SUPER(404, pcMessage).
    END CONSTRUCTOR.

END CLASS.
```

**ConflictError.cls:**
```
CLASS Tatara.Api.ConflictError INHERITS Tatara.Api.ApiError:

    CONSTRUCTOR PUBLIC ConflictError(INPUT pcMessage AS CHARACTER):
        SUPER(409, pcMessage).
    END CONSTRUCTOR.

END CLASS.
```

**InternalError.cls:**
```
CLASS Tatara.Api.InternalError INHERITS Tatara.Api.ApiError:

    CONSTRUCTOR PUBLIC InternalError(INPUT pcMessage AS CHARACTER):
        SUPER(500, pcMessage).
    END CONSTRUCTOR.

END CLASS.
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/Tatara/Api/
git commit -m "feat: add Tatara.Api error class hierarchy for typed exception handling"
```

---

### Task 6: Rewrite shim codegen with typed DTO construction, @Required validation, and CATCH blocks

**Files:**
- Modify: `src/main/kotlin/com/pyoif/tatara/GenerateRouteTask.kt` (writeShim method)

**Interfaces:**
- Consumes: `RouteDef` with `requestDtoClassName`, `responseDtoClassName`, `errorResponses`; `DtoParser` from Task 3
- Produces: generated `.cls` shim files with typed DTO construction + `@Required` validation + error catch blocks

- [ ] **Step 1: Rewrite writeShim method**

Replace entire `writeShim`:
```kotlin
    private fun writeShim(routePath: String, routes: List<RouteDef>, outDir: File, template: String) {
        val methodHandlersBlock = StringBuilder()
        val usingSet = mutableSetOf<String>()
        val firstClassName = routes.firstOrNull()?.className ?: ""
        usingSet.add(firstClassName)

        routes.forEachIndexed { index, def ->
            val handleName = "Handle" + def.httpMethod.lowercase().replaceFirstChar { it.uppercase() }
            if (index > 0) methodHandlersBlock.append("\r\n\r\n")

            val controllerVar = "ctrl${index}"
            val ctrlClassName = def.className
            val ctrlType = if (ctrlClassName.contains('.')) ctrlClassName else ctrlClassName
            usingSet.add(ctrlClassName)

            val hasRequestDto = def.requestDtoClassName != null
            val hasResponseDto = def.responseDtoClassName != null
            val dtoInfo = if (hasRequestDto) DtoParser.parse(def.requestDtoClassName!!, srcDir.get().asFile) else DtoParser.DtoInfo.EMPTY

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
                // --- PathSection: fill from route path parameters ---
                methodHandlersBlock.append("\t\toPath = NEW ${def.requestDtoClassName}.PathSection().\r\n")
                dtoInfo.pathProperties.forEach { prop ->
                    // Path params are always present (route wouldn't match otherwise)
                    methodHandlersBlock.append("\t\toPath:${prop.name} = poRequest:GetPathParameter(\"${prop.name}\").\r\n")
                }
                methodHandlersBlock.append("\r\n")

                // --- QuerySection: fill from query string with @Required validation ---
                methodHandlersBlock.append("\t\toQuer = NEW ${def.requestDtoClassName}.QuerySection().\r\n")
                // Declare temp variable for each required query param for ? check
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

                // --- BodySection: assign from request entity (PASOE deserializes) ---
                methodHandlersBlock.append("\t\toBody = NEW ${def.requestDtoClassName}.BodySection().\r\n")
                methodHandlersBlock.append("\t\tIF VALID-OBJECT(poRequest:Entity) THEN\r\n")
                methodHandlersBlock.append("\t\t\toBody = CAST(poRequest:Entity, ${def.requestDtoClassName}.BodySection).\r\n")
                methodHandlersBlock.append("\r\n")

                // --- Assemble top-level DTO ---
                methodHandlersBlock.append("\t\toReq = NEW ${def.requestDtoClassName}().\r\n")
                methodHandlersBlock.append("\t\tASSIGN\r\n")
                methodHandlersBlock.append("\t\t\toReq:path  = oPath\r\n")
                methodHandlersBlock.append("\t\t\toReq:query = oQuer\r\n")
                methodHandlersBlock.append("\t\t\toReq:body  = oBody.\r\n")
                methodHandlersBlock.append("\r\n")
            }

            // === Controller call + CATCH blocks ===
            methodHandlersBlock.append("\t\t$controllerVar = NEW $ctrlType().\r\n")
            methodHandlersBlock.append("\t\toResponse = NEW OpenEdge.Web.WebResponse().\r\n")

            // Legacy path: no typed DTOs at all → old RequestContext + ResponseContext pattern
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

            // Typed path: build CATCH cascade
            val catchLines = StringBuilder()

            // Specific @Response error types first (most specific catch wins)
            def.errorResponses.forEach { (code, type) ->
                catchLines.append("\tCATCH err AS $type:\r\n")
                catchLines.append("\t\toResponse:StatusCode = $code.\r\n")
                catchLines.append("\t\toResponse:Entity = err.\r\n")
                catchLines.append("\t\tRETURN 0.\r\n")
            }

            // Tatara.Api.ApiError catch-all
            catchLines.append("\tCATCH err AS Tatara.Api.ApiError:\r\n")
            catchLines.append("\t\toResponse:StatusCode = err:HttpCode.\r\n")
            catchLines.append("\t\toResponse:Entity = NEW Tatara.Api.ErrorResponse(err:Message).\r\n")
            catchLines.append("\t\tRETURN 0.\r\n")

            // Unexpected error → 500
            catchLines.append("\tCATCH err AS Progress.Lang.AppError:\r\n")
            catchLines.append("\t\toResponse:StatusCode = 500.\r\n")
            catchLines.append("\t\toResponse:Entity = NEW Tatara.Api.ErrorResponse(err:GetMessage()).\r\n")
            catchLines.append("\t\tRETURN 0.\r\n")
            catchLines.append("\tEND CATCH.\r\n")

            // Emit the handler call
            if (hasRequestDto && hasResponseDto) {
                methodHandlersBlock.append("\t\toResult = $controllerVar:${def.ablMethod}(INPUT oReq)\r\n")
            } else if (hasRequestDto) {
                methodHandlersBlock.append("\t\t$controllerVar:${def.ablMethod}(INPUT oReq)\r\n")
            } else {
                // hasResponseDto only (zero-param input)
                methodHandlersBlock.append("\t\toResult = $controllerVar:${def.ablMethod}()\r\n")
            }
            methodHandlersBlock.append(catchLines)
            methodHandlersBlock.append("\r\n")

            // Success response
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
```

Run: `./gradlew build`

- [ ] **Step 2: Commit**

```bash
git add src/main/kotlin/com/pyoif/tatara/GenerateRouteTask.kt
git commit -m "feat: typed DTO shim codegen with @Required validation and CATCH blocks"
```

---

### Task 7: Create GenerateOpenApiTask

**Files:**
- Create: `src/main/kotlin/com/pyoif/tatara/GenerateOpenApiTask.kt`

**Interfaces:**
- Consumes: `packagedDir` (prefixed ABL sources with DTOs in `app/ports/`), `handlersDir` (`.handlers` from GenerateRouteTask)
- Produces: `swaggerFile` — `build/generated/openapi/swagger.json`

- [ ] **Step 1: Write GenerateOpenApiTask.kt**

```kotlin
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

            // Top-level: path/query/body → $ref to inner schemas
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

            // Build inner section schemas from DtoProperty lists
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
                typeMap[key]!!.forEach { (k, v) -> addProperty(k, if (v is Number) v.toInt() else v.toString()) }
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

                // Path parameters
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

                // Query parameters from DTO QuerySection
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

                    // Request body from BodySection
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

                // Responses
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
```

Run: `./gradlew build`

- [ ] **Step 2: Commit**

```bash
git add src/main/kotlin/com/pyoif/tatara/GenerateOpenApiTask.kt
git commit -m "feat: add GenerateOpenApiTask for swagger.json with DTO schema generation"
```

---

### Task 8: Register GenerateOpenApiTask and wire Swagger UI

**Files:**
- Modify: `src/main/kotlin/com/pyoif/tatara/TataraPlugin.kt`

**Interfaces:**
- Registers `generateOpenApi` task, `copySwaggerUi` task
- Wires `copySwaggerUi` before `PackageWarTask`

- [ ] **Step 1: Update TataraPlugin.kt**

Replace entire file:
```kotlin
package com.pyoif.tatara

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Copy

class TataraPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val setupPct = project.tasks.register("setupPct", SetupPctTask::class.java) {
            group = "tatara"
            description = "Downloads latest PCT.jar from GitHub Releases for ABL compilation."
        }

        project.tasks.withType(OeCompileTask::class.java).configureEach {
            dependsOn(setupPct)
            pctJarPath.convention(setupPct.map { it.pctJar.absolutePath })
        }

        project.tasks.withType(PrependPackageTask::class.java).configureEach {
            dependsOn(setupPct)
        }

        project.tasks.register("generateOpenApi", GenerateOpenApiTask::class.java) {
            group = "tatara"
            description = "Generates OpenAPI 3.0 swagger.json from handler annotations and DTO classes"
        }

        val copySwaggerUi = project.tasks.register("copySwaggerUi", Copy::class.java) {
            group = "tatara"
            description = "Copies swagger/index.html to pasoeTemplate/docs/swagger/ for WAR bundling"
            from(project.rootDir.resolve("swagger/index.html"))
            into(project.layout.buildDirectory.dir("resources/main/pasoeTemplate/docs/swagger"))
        }

        project.tasks.withType(PackageWarTask::class.java).configureEach {
            dependsOn(copySwaggerUi)
        }
    }
}
```

Run: `./gradlew build`

- [ ] **Step 2: Commit**

```bash
git add src/main/kotlin/com/pyoif/tatara/TataraPlugin.kt
git commit -m "feat: register GenerateOpenApiTask and wire Swagger UI copy task"
```

---

### Task 9: End-to-end verification

**Files:**
- None (verification only)

- [ ] **Step 1: Verify plugin compiles**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Verify all tatara tasks registered**

```bash
./gradlew tasks --group=tatara
```

Expected output includes: `copySwaggerUi`, `generateOpenApi`, `setupPct`

- [ ] **Step 3: Verify Swagger index.html is bundled**

```bash
./gradlew copySwaggerUi
cat build/resources/main/pasoeTemplate/docs/swagger/index.html
```

Expected: HTML contains `url: '/api/swagger.json'`

- [ ] **Step 4: Verify full pipeline runs**

```bash
./gradlew generateOpenApi --info
```

Expected: Task runs without crash (empty schema if no source).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "chore: verify end-to-end build pipeline"
```
