# Temp-Table Response Serialization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Recognize DTO properties annotated as temp-tables and serialize them as JSON arrays (default) or JSON objects (`@Object`) in the generated route shim.

**Architecture:** Extend `DtoParser` to recognize `@TempTable`/`@Object`/`@Array` annotations and add `isTempTable` + `tempTableKind` to `DtoProperty`. Extend `GenerateRouteTask.emitResponseJson` to emit inline `oJson:Add(name, handle)` for `@Array` (default) and `oJson:Add(name, NEW JsonObject())` + `Read(handle)` for `@Object`. The `Tatara.Api.DtoSerializer` runtime class is **unchanged** — temp-table handling lives entirely in the generated shim.

**Tech Stack:** Kotlin (Gradle plugin code), ABL/OpenEdge PASOE (runtime), JUnit 5 (Kotlin unit tests), Gradle `kotlin-dsl`.

## Global Constraints

- Branch: `feat/swagger-typed-dto`.
- DTO property must be typed `AS HANDLE` for temp-table support. Class-ref-typed temp-table properties (`AS SomeApp.TempTable`) are not supported in this iteration.
- Default annotation: `@Array` (or no annotation after `@TempTable`).
- Temp-table serialization is inline in the shim — no DtoSerializer signature change.
- Out of scope: nested temp-tables, per-row key renaming, row filtering.

---

## File Structure

| File | Responsibility |
| --- | --- |
| `src/main/kotlin/com/pyoif/tatara/DtoParser.kt` (modify) | Add `isTempTable`, `tempTableKind`, recognize `@TempTable`/`@Object`/`@Array`. |
| `src/main/kotlin/com/pyoif/tatara/GenerateRouteTask.kt` (modify) | Extend `emitResponseJson` with temp-table branch (inline). |
| `src/test/kotlin/com/pyoif/tatara/DtoParserTest.kt` (modify) | Tests for temp-table parsing. |
| `src/test/kotlin/com/pyoif/tatara/GenerateRouteTaskEmitTest.kt` (modify) | Tests for temp-table emission. |

---

## Task 1: Extend DtoParser with temp-table annotations

**Files:**
- Modify: `src/main/kotlin/com/pyoif/tatara/DtoParser.kt`
- Modify: `src/test/kotlin/com/pyoif/tatara/DtoParserTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces:
  - `enum class TempTableKind { NONE, OBJECT, ARRAY }`
  - `DtoProperty(... , isTempTable: Boolean = false, tempTableKind: TempTableKind = TempTableKind.NONE)`
  - `annotationRegex` matches `Required|Path|Query|Body|TempTable|Object|Array`.
  - When a prop has `@Object`/`@Array`/`@TempTable` annotation: `isTempTable=true`, `tempTableKind` set accordingly. Properties with `isTempTable=true` skip the existing primitive/cycle/nested-DTO classification.

- [ ] **Step 1: Add failing tests**

Append to `src/test/kotlin/com/pyoif/tatara/DtoParserTest.kt` (inside the class, after the existing tests):

```kotlin
    @Test
    fun `recognizes @TempTable annotation as ARRAY by default`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    // @TempTable
                    DEFINE PUBLIC PROPERTY orders AS HANDLE.
            """.trimIndent())
        }
        val info = DtoParser.parse("com.example.User", src)
        assertEquals(1, info.properties.size)
        val prop = info.properties[0]
        assertEquals("orders", prop.name)
        assertTrue(prop.isTempTable)
        assertEquals(DtoParser.TempTableKind.ARRAY, prop.tempTableKind)
        assertFalse(prop.isDto)
    }

    @Test
    fun `recognizes @Array annotation explicitly`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    // @Array
                    DEFINE PUBLIC PROPERTY items AS HANDLE.
            """.trimIndent())
        }
        val info = DtoParser.parse("com.example.User", src)
        val prop = info.properties[0]
        assertTrue(prop.isTempTable)
        assertEquals(DtoParser.TempTableKind.ARRAY, prop.tempTableKind)
    }

    @Test
    fun `recognizes @Object annotation`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    // @Object
                    DEFINE PUBLIC PROPERTY summary AS HANDLE.
            """.trimIndent())
        }
        val info = DtoParser.parse("com.example.User", src)
        val prop = info.properties[0]
        assertTrue(prop.isTempTable)
        assertEquals(DtoParser.TempTableKind.OBJECT, prop.tempTableKind)
    }

    @Test
    fun `HANDLE prop without annotation is not a temp-table`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    DEFINE PUBLIC PROPERTY rawHandle AS HANDLE.
            """.trimIndent())
        }
        val info = DtoParser.parse("com.example.User", src)
        val prop = info.properties[0]
        assertFalse(prop.isTempTable)
        assertEquals(DtoParser.TempTableKind.NONE, prop.tempTableKind)
    }
```

- [ ] **Step 2: Run the new tests to verify they fail**

```bash
./gradlew test --tests com.pyoif.tatara.DtoParserTest
```

Expected: the 4 new tests fail with compile errors (unresolved `TempTableKind`, `isTempTable`, `tempTableKind`).

- [ ] **Step 3: Add `TempTableKind` enum and update `DtoProperty`**

In `src/main/kotlin/com/pyoif/tatara/DtoParser.kt`:

1. Add the enum just before `data class DtoProperty`:

```kotlin
enum class TempTableKind { NONE, OBJECT, ARRAY }
```

2. Update `DtoProperty` to:

```kotlin
data class DtoProperty(
    val name: String,
    val ablType: String,
    val isRequired: Boolean = false,
    val location: ParamLocation = ParamLocation.UNKNOWN,
    val isExtent: Boolean = false,
    val isDto: Boolean = false,
    val nested: DtoInfo? = null,
    val isTempTable: Boolean = false,
    val tempTableKind: TempTableKind = TempTableKind.NONE
)
```

3. Update `annotationRegex` to:

```kotlin
private val annotationRegex = Regex("""(?i)//\s*@(Required|Path|Query|Body|TempTable|Object|Array)""")
```

4. Update the annotation switch in `parse` to:

```kotlin
annotationRegex.findAll(trimmed).forEach { m ->
    when (m.groupValues[1].lowercase()) {
        "required" -> isReq = true
        "path" -> { loc = ParamLocation.PATH; isReq = false }
        "query" -> { loc = ParamLocation.QUERY; isReq = false }
        "body" -> { loc = ParamLocation.BODY; isReq = false }
        "object" -> { isTempTable = true; tempTableKind = TempTableKind.OBJECT; isReq = false }
        "array", "temptable" -> { isTempTable = true; tempTableKind = TempTableKind.ARRAY; isReq = false }
    }
}
```

5. In the property-emission block, change:

```kotlin
propDefRegex.find(trimmed)?.let { m ->
    val name = m.groupValues[1]
    val ablType = m.groupValues[2]
    val isExtent = m.groups[3]?.value != null

    val upperType = ablType.uppercase()
    val isScalar = primitives.contains(upperType) || upperType == "VOID"

    var isDto = false
    var nested: DtoInfo? = null
    if (!isScalar) {
        if (ablType in visited) {
            isDto = false
            nested = null
        } else {
            isDto = true
            nested = parse(ablType, srcRoot, visited)
        }
    }

    properties.add(DtoProperty(
        name = name,
        ablType = ablType,
        isRequired = isReq,
        location = loc,
        isExtent = isExtent,
        isDto = isDto,
        nested = nested
    ))
    // Reset @Required only — location sticks until next @Path/@Query/@Body
    isReq = false
}
```

to:

```kotlin
propDefRegex.find(trimmed)?.let { m ->
    val name = m.groupValues[1]
    val ablType = m.groupValues[2]
    val isExtent = m.groups[3]?.value != null

    var isDto = false
    var nested: DtoInfo? = null
    if (!isTempTable) {
        val upperType = ablType.uppercase()
        val isScalar = primitives.contains(upperType) || upperType == "VOID"
        if (!isScalar) {
            if (ablType in visited) {
                isDto = false
                nested = null
            } else {
                isDto = true
                nested = parse(ablType, srcRoot, visited)
            }
        }
    }

    properties.add(DtoProperty(
        name = name,
        ablType = ablType,
        isRequired = isReq,
        location = loc,
        isExtent = isExtent,
        isDto = isDto,
        nested = nested,
        isTempTable = isTempTable,
        tempTableKind = tempTableKind
    ))
    // Reset @Required and @TempTable kind — location sticks until next @Path/@Query/@Body
    isReq = false
    isTempTable = false
    tempTableKind = TempTableKind.NONE
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
./gradlew test --tests com.pyoif.tatara.DtoParserTest
```

Expected: all 8 tests pass (4 existing + 4 new).

- [ ] **Step 5: Run the full test suite to verify no regressions**

```bash
./gradlew test
```

Expected: all tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/pyoif/tatara/DtoParser.kt src/test/kotlin/com/pyoif/tatara/DtoParserTest.kt
git commit -m "feat(dto): recognize @TempTable/@Object/@Array annotations"
```

---

## Task 2: Emit inline temp-table serialization in shim

**Files:**
- Modify: `src/main/kotlin/com/pyoif/tatara/GenerateRouteTask.kt` (the `emitResponseJson` helper)
- Modify: `src/test/kotlin/com/pyoif/tatara/GenerateRouteTaskEmitTest.kt`

**Interfaces:**
- Consumes: `DtoProperty.isTempTable`, `DtoProperty.tempTableKind` from Task 1.
- Produces: in `emitResponseJson`, when `prop.isTempTable` is true, emit:
  - For `kind=ARRAY` (default):
    ```progress
    oJson:Add("<name>", <accessor>).
    ```
  - For `kind=OBJECT`:
    ```progress
    oJson:Add("<name>", NEW Progress.Json.ObjectModel.JsonObject()).
    oJson:GetJsonObject("<name>"):Read(<accessor>).
    ```
  Where `<accessor>` is the chained property path (e.g. `oResult:orders` for top-level, `oResult:parent:child` for nested). No DtoSerializer call is emitted for temp-table properties.

- [ ] **Step 1: Add failing emission tests**

Append to `src/test/kotlin/com/pyoif/tatara/GenerateRouteTaskEmitTest.kt` (inside the class):

```kotlin
    @Test
    fun `emits inline Add for @Array temp-table prop`(@TempDir tmp: Path) {
        val src = tmp.resolve("src").toFile()
        File(src, "com/example/Order.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.Order:
                    // @Array
                    DEFINE PUBLIC PROPERTY items AS HANDLE.
            """.trimIndent())
        }

        val genDir = tmp.resolve("gen").toFile()
        val task = ShimEmitHelper.createTask()
        val shim = ShimEmitHelper.invokeWriteShim(
            task = task,
            srcDir = src,
            genDir = genDir,
            routePath = "svc/orders",
            routeDef = com.pyoif.tatara.GenerateRoutesTask.RouteDef(
                routePath = "svc/orders",
                httpMethod = "GET",
                className = "com.example.OrderController",
                ablMethod = "GetOrder",
                responseDtoClassName = "com.example.Order"
            )
        )

        assertTrue(shim.contains("oJson:Add(\"items\", oResult:items)."),
            "shim should emit inline Add for @Array temp-table prop. SHIM WAS:\n$shim")
        assertFalse(shim.contains("Tatara.Api.DtoSerializer:ToJsonObject"),
            "shim should not call DtoSerializer for temp-table prop")
        assertFalse(shim.contains("NEW Progress.Json.ObjectModel.JsonArray()"),
            "shim should not construct JsonArray for @Array (Add handles HANDLE natively)")
    }

    @Test
    fun `emits Read path for @Object temp-table prop`(@TempDir tmp: Path) {
        val src = tmp.resolve("src").toFile()
        File(src, "com/example/Order.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.Order:
                    // @Object
                    DEFINE PUBLIC PROPERTY summary AS HANDLE.
            """.trimIndent())
        }

        val genDir = tmp.resolve("gen").toFile()
        val task = ShimEmitHelper.createTask()
        val shim = ShimEmitHelper.invokeWriteShim(
            task = task,
            srcDir = src,
            genDir = genDir,
            routePath = "svc/orders",
            routeDef = com.pyoif.tatara.GenerateRoutesTask.RouteDef(
                routePath = "svc/orders",
                httpMethod = "GET",
                className = "com.example.OrderController",
                ablMethod = "GetOrder",
                responseDtoClassName = "com.example.Order"
            )
        )

        assertTrue(shim.contains("oJson:Add(\"summary\", NEW Progress.Json.ObjectModel.JsonObject())."),
            "shim should construct empty JsonObject for @Object. SHIM WAS:\n$shim")
        assertTrue(shim.contains("oJson:GetJsonObject(\"summary\"):Read(oResult:summary)."),
            "shim should call Read for @Object")
    }
```

- [ ] **Step 2: Run the new tests to verify they fail**

```bash
./gradlew test --tests com.pyoif.tatara.GenerateRouteTaskEmitTest
```

Expected: the 2 new tests fail. Existing tests still pass (regression baseline).

- [ ] **Step 3: Add temp-table branch to `emitResponseJson`**

In `src/main/kotlin/com/pyoif/tatara/GenerateRouteTask.kt`, find the `emitResponseJson` method. Add a temp-table branch before the `isDto` check:

Replace the current `dtoInfo.properties.forEach { prop -> ... }` block. The new version:

```kotlin
private fun emitResponseJson(
    sb: StringBuilder,
    oResultAccessor: String,
    dtoInfo: DtoParser.DtoInfo
) {
    dtoInfo.properties.forEach { prop ->
        val propAccessor = "$oResultAccessor:${prop.name}"
        when {
            prop.isTempTable -> {
                when (prop.tempTableKind) {
                    DtoParser.TempTableKind.ARRAY -> {
                        sb.append("\t\toJson:Add(\"${prop.name}\", $propAccessor).\r\n")
                    }
                    DtoParser.TempTableKind.OBJECT -> {
                        sb.append("\t\toJson:Add(\"${prop.name}\", NEW Progress.Json.ObjectModel.JsonObject()).\r\n")
                        sb.append("\t\toJson:GetJsonObject(\"${prop.name}\"):Read($propAccessor).\r\n")
                    }
                    DtoParser.TempTableKind.NONE -> {
                        // unreachable: isTempTable=true implies kind != NONE
                    }
                }
            }
            prop.isDto && prop.nested != null -> {
                val nestedNames = prop.nested.properties.joinToString(", ") { "\"${it.name}\"" }
                val nestedTypes = prop.nested.properties.joinToString(", ") { "\"${it.ablType}\"" }
                val nestedIsDto = prop.nested.properties.joinToString(", ") {
                    if (it.isDto) "yes" else "no"
                }
                val n = prop.nested.properties.size
                sb.append("\t\toJson:Add(\"${prop.name}\", Tatara.Api.DtoSerializer:ToJsonObject(\r\n")
                sb.append("\t\t\t$propAccessor,\r\n")
                sb.append("\t\t\tNEW CHARACTER EXTENT [$n] [$nestedNames],\r\n")
                sb.append("\t\t\tNEW CHARACTER EXTENT [$n] [$nestedTypes],\r\n")
                sb.append("\t\t\tNEW LOGICAL   EXTENT [$n] [$nestedIsDto])).\r\n")
            }
            else -> {
                sb.append("\t\toJson:Add(\"${prop.name}\", Tatara.Api.DtoSerializer:ToJsonObject(\r\n")
                sb.append("\t\t\t$propAccessor,\r\n")
                sb.append("\t\t\tNEW CHARACTER EXTENT [1] [\"${prop.name}\"],\r\n")
                sb.append("\t\t\tNEW CHARACTER EXTENT [1] [\"${prop.ablType}\"],\r\n")
                sb.append("\t\t\tNEW LOGICAL   EXTENT [1] [no])).\r\n")
            }
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
./gradlew test --tests com.pyoif.tatara.GenerateRouteTaskEmitTest
```

Expected: all 4 tests in `GenerateRouteTaskEmitTest` pass (2 existing + 2 new).

- [ ] **Step 5: Run the full test suite**

```bash
./gradlew test
```

Expected: all tests pass (12 total: 1 smoke + 8 parser + 4 emit - 1 shared = 12; exact count depends on suite).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/pyoif/tatara/GenerateRouteTask.kt src/test/kotlin/com/pyoif/tatara/GenerateRouteTaskEmitTest.kt
git commit -m "feat(shim): inline temp-table serialization via Add/Read"
```

---

## Task 3: End-to-end manual verification (in PASOE)

**Files:** none modified.

This task is a manual smoke test inside a downstream project that uses the Tatara plugin. It cannot run from the plugin repo itself.

- [ ] **Step 1: Build the plugin to a local Maven repo**

```bash
./gradlew publishToMavenLocal
```

Expected: `BUILD SUCCESSFUL`. The artifact lands in `~/.m2/repository/com/pyoif/tatara/1.0.0/`.

- [ ] **Step 2: In a downstream project, regenerate shims and inspect a temp-table response route**

In a downstream ABL project:

```bash
./gradlew tataraGenerateRoutes
```

Inspect the generated `.cls` for a route whose response DTO has a `HANDLE` property annotated `@Array` or `@Object`. Confirm:
- The temp-table prop emits `oJson:Add("<name>", oResult:<propname>).` (for `@Array`).
- No `Tatara.Api.DtoSerializer:ToJsonObject` call for the temp-table prop.
- No `NEW Progress.Json.ObjectModel.JsonArray()` for `@Array` (rely on `Add` overload).

- [ ] **Step 3: Start PASOE and curl the endpoint**

```bash
curl -i http://localhost:<port>/<svc>/<route>
```

Expected: HTTP 200, `Content-Type: application/json`, body contains the temp-table as a JSON array of objects (for `@Array`) or a single JSON object (for `@Object`).

- [ ] **Step 4: Test empty temp-table**

In a sample controller, return a DTO whose temp-table prop is an empty temp-table. Re-run the request.

Expected: HTTP 200, body contains `"<name>": []` (for `@Array`) or `"<name>": {}` (for `@Object`).

- [ ] **Step 5: No commit**

This task produces no code changes. Skip the commit step.

---

## Self-Review Notes

- Spec §1 annotations → Task 1.
- Spec §2 DtoParser → Task 1.
- Spec §3 GenerateRouteTask emit → Task 2.
- Spec §4 DtoSerializer unchanged → Tasks 1 & 2 make no edits to `DtoSerializer.cls`.
- Spec §5 error handling → existing shim CATCH blocks cover invalid handles.
- Spec §6 testing → Tasks 1, 2, 3.
- No placeholders. All test code shown. All Kotlin code shown. Signatures match across tasks.
- Type consistency: `TempTableKind` enum and `DtoProperty.isTempTable`/`tempTableKind` introduced in Task 1, consumed in Task 2.
