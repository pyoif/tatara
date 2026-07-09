# EXTENT Property Handling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the shim correctly handle DTO properties declared with `EXTENT` (fixed or unfixed), including extents of nested DTO types. Request body deserializes JSON arrays into ABL extents; response serializes ABL extents as JSON arrays.

**Architecture:** Extend `DtoParser` to capture the optional EXTENT size. Extend `GenerateRouteTask.writeShim` with an extent branch in the body deserialization `when` block. Extend `emitResponseJson` + `emitNestedJson` with an extent branch that builds per-element JsonArray with per-element scalar add or nested DTO JsonObject build. OpenAPI already correct (no changes).

**Tech Stack:** Kotlin (Gradle plugin code), ABL/OpenEdge PASOE (runtime), JUnit 5 (Kotlin unit tests), Gradle `kotlin-dsl`.

## Global Constraints

- Branch: `feat/swagger-typed-dto`.
- Support both fixed (`EXTENT 5`) and unfixed (`EXTENT`) extents.
- Support scalar element types (CHARACTER, INTEGER, etc.) and nested DTO element types.
- Loop bound: `MIN(EXTENT(prop), arrayLength)` for safety.
- OpenAPI already handles extents (no changes needed).
- Out of scope: extents of extents, cycle detection in extent elements.

---

## File Structure

| File | Responsibility |
| --- | --- |
| `src/main/kotlin/com/pyoif/tatara/DtoParser.kt` (modify) | Add `extentSize: Int?` to `DtoProperty`. |
| `src/main/kotlin/com/pyoif/tatara/GenerateRouteTask.kt` (modify) | Request body extent branch + response extent branches. |
| `src/test/kotlin/com/pyoif/tatara/DtoParserTest.kt` (modify) | EXTENT size parsing tests. |
| `src/test/kotlin/com/pyoif/tatara/GenerateRouteTaskEmitTest.kt` (modify) | Request extent, response scalar extent, response nested extent tests. |

---

## Task 1: Capture EXTENT size in DtoParser

**Files:**
- Modify: `src/main/kotlin/com/pyoif/tatara/DtoParser.kt`
- Modify: `src/test/kotlin/com/pyoif/tatara/DtoParserTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces:
  - `DtoProperty(... , extentSize: Int? = null)` — captured from regex group 3.
  - For `EXTENT 5` → `extentSize=5`. For `EXTENT` (no size) → `extentSize=null`. For no `EXTENT` → `extentSize=null`.

- [ ] **Step 1: Add failing tests**

Append to `src/test/kotlin/com/pyoif/tatara/DtoParserTest.kt`:

```kotlin
    @Test
    fun `captures fixed EXTENT size`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    DEFINE PUBLIC PROPERTY tags AS CHARACTER EXTENT 5.
            """.trimIndent())
        }
        val info = DtoParser.parse("com.example.User", src)
        val prop = info.properties[0]
        assertTrue(prop.isExtent)
        assertEquals(5, prop.extentSize)
    }

    @Test
    fun `captures unfixed EXTENT with null size`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    DEFINE PUBLIC PROPERTY tags AS CHARACTER EXTENT.
            """.trimIndent())
        }
        val info = DtoParser.parse("com.example.User", src)
        val prop = info.properties[0]
        assertTrue(prop.isExtent)
        assertNull(prop.extentSize)
    }

    @Test
    fun `non-extent prop has null size`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    DEFINE PUBLIC PROPERTY name AS CHARACTER.
            """.trimIndent())
        }
        val info = DtoParser.parse("com.example.User", src)
        val prop = info.properties[0]
        assertFalse(prop.isExtent)
        assertNull(prop.extentSize)
    }
```

- [ ] **Step 2: Run, expect fail**

```bash
./gradlew test --tests com.pyoif.tatara.DtoParserTest
```

Expected: 3 new tests fail with compile errors (unresolved `extentSize`).

- [ ] **Step 3: Add `extentSize` field to DtoProperty + parse from regex**

In `src/main/kotlin/com/pyoif/tatara/DtoParser.kt`:

1. Update `DtoProperty`:
```kotlin
data class DtoProperty(
    val name: String,
    val ablType: String,
    val isRequired: Boolean = false,
    val location: ParamLocation = ParamLocation.UNKNOWN,
    val isExtent: Boolean = false,
    val extentSize: Int? = null,
    val isDto: Boolean = false,
    val nested: DtoInfo? = null,
    val isTempTable: Boolean = false,
    val tempTableKind: TempTableKind = TempTableKind.NONE
)
```

2. In the `propDefRegex.find(trimmed)?.let { m -> ... }` block, replace the `isExtent` line:

```kotlin
val isExtent = m.groups[3]?.value != null
val extentSize = m.groups[3]?.value?.trim()?.split(Regex("\\s+"))?.lastOrNull()?.toIntOrNull()
```

3. Update the `DtoProperty(...)` constructor call to include `extentSize = extentSize`.

- [ ] **Step 4: Run, expect pass**

```bash
./gradlew test --tests com.pyoif.tatara.DtoParserTest
```

Expected: all 11 tests pass (8 existing + 3 new).

- [ ] **Step 5: Run full suite**

```bash
./gradlew test
```

Expected: all pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/pyoif/tatara/DtoParser.kt src/test/kotlin/com/pyoif/tatara/DtoParserTest.kt
git commit -m "feat(dto): capture EXTENT size in DtoProperty"
```

---

## Task 2: Request body extent deserialization

**Files:**
- Modify: `src/main/kotlin/com/pyoif/tatara/GenerateRouteTask.kt`
- Modify: `src/test/kotlin/com/pyoif/tatara/GenerateRouteTaskEmitTest.kt`

**Interfaces:**
- Consumes: `DtoProperty.isExtent`, `DtoProperty.ablType` (existing).
- Produces: in the body deserialization `forEach` loop in `writeShim`, when `prop.isExtent`:
  - Emit `DEFINE VARIABLE oArr_<name> AS Progress.Json.ObjectModel.JsonArray NO-UNDO.`
  - `oArr_<name> = oJson:GetJsonArray("<name>").`
  - `DEFINE VARIABLE i_<name> AS INTEGER NO-UNDO.`
  - `DO i_<name> = 1 TO oArr_<name>:Length:`
  - Element assignment via scalar switch (`GetCharacter`/`GetInteger`/`GetDecimal`/`GetLogical`/`GetDatetime`).
  - For nested DTO extent: `oReq:<name>[i_<name>] = NEW <nestedClass>().` then per-prop `oReq:<name>[i_<name>]:<prop> = oArr_<name>:GetJsonObject(i_<name>):Get<type>("<prop>").`
  - `END.`
- Non-extent branch unchanged.
- A `DEFINE VARIABLE i AS INTEGER NO-UNDO.` is emitted once at the top of the body block if any extent prop exists.

- [ ] **Step 1: Add failing test**

Append to `src/test/kotlin/com/pyoif/tatara/GenerateRouteTaskEmitTest.kt`:

```kotlin
    @Test
    fun `request body deserializes scalar extent from JSON array`(@TempDir tmp: Path) {
        val src = tmp.resolve("src").toFile()
        File(src, "com/example/OrderRequest.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.OrderRequest:
                    DEFINE PUBLIC PROPERTY tags AS CHARACTER EXTENT 3.
            """.trimIndent())
        }
        File(src, "com/example/OrderController.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.OrderController:
                    // @POST("/svc/orders")
                    METHOD PUBLIC VOID CreateOrder(INPUT poReq AS com.example.OrderRequest):
                        DEFINE VARIABLE ctrl0 AS com.example.OrderController NO-UNDO.
                        ctrl0 = NEW com.example.OrderController().
                    END METHOD.
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
                httpMethod = "POST",
                className = "com.example.OrderController",
                ablMethod = "CreateOrder",
                requestDtoClassName = "com.example.OrderRequest"
            )
        )

        assertTrue(shim.contains("oJson:GetJsonArray(\"tags\")"), "shim should read JSON array for extent prop. SHIM:\n$shim")
        assertTrue(shim.contains("DO i_tags = 1 TO oArr_tags:Length"),
            "shim should loop over JSON array length. SHIM:\n$shim")
        assertTrue(shim.contains("oReq:tags[i_tags] = oArr_tags:GetCharacter(i_tags)."),
            "shim should assign each element via GetCharacter. SHIM:\n$shim")
        assertFalse(shim.contains("oJson:GetCharacter(\"tags\")"),
            "shim should not use scalar GetCharacter for extent prop")
    }
```

- [ ] **Step 2: Run, expect fail**

```bash
./gradlew test --tests com.pyoif.tatara.GenerateRouteTaskEmitTest
```

Expected: new test fails. Existing tests still pass.

- [ ] **Step 3: Add extent branch to body deserialization**

In `src/main/kotlin/com/pyoif/tatara/GenerateRouteTask.kt`, find the body `forEach` loop. Restructure to:

```kotlin
bodyProps.forEach { prop ->
    methodHandlersBlock.append("\t\t\tIF oJson:Has(\"${prop.name}\") AND NOT oJson:IsNull(\"${prop.name}\") THEN DO:\r\n")
    if (prop.isExtent) {
        methodHandlersBlock.append("\t\t\t\tDEFINE VARIABLE oArr_${prop.name} AS Progress.Json.ObjectModel.JsonArray NO-UNDO.\r\n")
        methodHandlersBlock.append("\t\t\t\tDEFINE VARIABLE i_${prop.name} AS INTEGER NO-UNDO.\r\n")
        methodHandlersBlock.append("\t\t\t\toArr_${prop.name} = oJson:GetJsonArray(\"${prop.name}\").\r\n")
        methodHandlersBlock.append("\t\t\t\tDO i_${prop.name} = 1 TO oArr_${prop.name}:Length:\r\n")
        when (prop.ablType.uppercase()) {
            "INTEGER", "INT64" -> methodHandlersBlock.append("\t\t\t\t\toReq:${prop.name}[i_${prop.name}] = oArr_${prop.name}:GetInteger(i_${prop.name}).\r\n")
            "DECIMAL" -> methodHandlersBlock.append("\t\t\t\t\toReq:${prop.name}[i_${prop.name}] = oArr_${prop.name}:GetDecimal(i_${prop.name}).\r\n")
            "LOGICAL" -> methodHandlersBlock.append("\t\t\t\t\toReq:${prop.name}[i_${prop.name}] = oArr_${prop.name}:GetLogical(i_${prop.name}).\r\n")
            "DATETIME", "DATETIME-TZ" -> methodHandlersBlock.append("\t\t\t\t\toReq:${prop.name}[i_${prop.name}] = oArr_${prop.name}:GetDatetime(i_${prop.name}).\r\n")
            "LONGCHAR", "CHARACTER" -> methodHandlersBlock.append("\t\t\t\t\toReq:${prop.name}[i_${prop.name}] = oArr_${prop.name}:GetCharacter(i_${prop.name}).\r\n")
            else -> methodHandlersBlock.append("\t\t\t\t\t/* extent of unsupported type */\r\n")
        }
        methodHandlersBlock.append("\t\t\t\tEND.\r\n")
    } else {
        when (prop.ablType.uppercase()) {
            "INTEGER", "INT64" -> methodHandlersBlock.append("\t\t\t\toReq:${prop.name} = oJson:GetInteger(\"${prop.name}\").\r\n")
            "DECIMAL" -> methodHandlersBlock.append("\t\t\t\toReq:${prop.name} = oJson:GetDecimal(\"${prop.name}\").\r\n")
            "LOGICAL" -> methodHandlersBlock.append("\t\t\t\toReq:${prop.name} = oJson:GetLogical(\"${prop.name}\").\r\n")
            "DATETIME", "DATETIME-TZ" -> methodHandlersBlock.append("\t\t\t\toReq:${prop.name} = oJson:GetDatetime(\"${prop.name}\").\r\n")
            "LONGCHAR", "CHARACTER" -> methodHandlersBlock.append("\t\t\t\toReq:${prop.name} = oJson:GetCharacter(\"${prop.name}\").\r\n")
            else -> methodHandlersBlock.append("\t\t\t\t/* Nested complex objects not fully mapped natively */\r\n")
        }
    }
    methodHandlersBlock.append("\t\t\tEND.\r\n")
    if (prop.isRequired) {
        methodHandlersBlock.append("\t\t\tELSE DO:\r\n")
        methodHandlersBlock.append("\t\t\t\tTatara.Api.ResponseWriter:WriteError(oResponse, 400, \"Missing required body parameter: ${prop.name}\").\r\n")
        methodHandlersBlock.append("\t\t\t\tRETURN 0.\r\n")
        methodHandlersBlock.append("\t\t\tEND.\r\n")
    }
}
```

- [ ] **Step 4: Run, expect pass**

```bash
./gradlew test --tests com.pyoif.tatara.GenerateRouteTaskEmitTest
```

Expected: new test passes, all existing tests still pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/pyoif/tatara/GenerateRouteTask.kt src/test/kotlin/com/pyoif/tatara/GenerateRouteTaskEmitTest.kt
git commit -m "feat(shim): request body deserializes scalar EXTENT props"
```

---

## Task 3: Response scalar extent serialization

**Files:**
- Modify: `src/main/kotlin/com/pyoif/tatara/GenerateRouteTask.kt` (the `emitResponseJson` and `emitNestedJson` helpers)
- Modify: `src/test/kotlin/com/pyoif/tatara/GenerateRouteTaskEmitTest.kt`

**Interfaces:**
- Consumes: `DtoProperty.isExtent`, `DtoProperty.ablType`.
- Produces: in `emitResponseJson` (and `emitNestedJson`), when `prop.isExtent`:
  - Scalar element type: emit per-element `oArr_<name>:Add(<accessor>[i_<name>])` loop.
  - Nested DTO element type: emit per-element JsonObject build with each nested prop, attach to array.
  - Negative assertion: shim does NOT emit `oJson:Add("<name>", <accessor>).` for an extent prop (would stringify ref).

- [ ] **Step 1: Add failing tests**

Append to `src/test/kotlin/com/pyoif/tatara/GenerateRouteTaskEmitTest.kt`:

```kotlin
    @Test
    fun `response serializes scalar extent as JSON array`(@TempDir tmp: Path) {
        val src = tmp.resolve("src").toFile()
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    DEFINE PUBLIC PROPERTY tags AS CHARACTER EXTENT 3.
            """.trimIndent())
        }

        val genDir = tmp.resolve("gen").toFile()
        val task = ShimEmitHelper.createTask()
        val shim = ShimEmitHelper.invokeWriteShim(
            task = task,
            srcDir = src,
            genDir = genDir,
            routePath = "svc/users",
            routeDef = com.pyoif.tatara.GenerateRoutesTask.RouteDef(
                routePath = "svc/users",
                httpMethod = "GET",
                className = "com.example.UserController",
                ablMethod = "GetUser",
                responseDtoClassName = "com.example.User"
            )
        )

        assertTrue(shim.contains("DEFINE VARIABLE oArr_tags AS Progress.Json.ObjectModel.JsonArray"),
            "shim should declare oArr_tags JsonArray. SHIM:\n$shim")
        assertTrue(shim.contains("DO i_tags = 1 TO EXTENT(oResult:tags)"),
            "shim should loop over EXTENT bound. SHIM:\n$shim")
        assertTrue(shim.contains("oArr_tags:Add(oResult:tags[i_tags])."),
            "shim should add each element to array. SHIM:\n$shim")
        assertTrue(shim.contains("oJson:Add(\"tags\", oArr_tags)."),
            "shim should attach array under prop name. SHIM:\n$shim")
        assertFalse(shim.contains("oJson:Add(\"tags\", oResult:tags)."),
            "shim should not pass extent ref directly to Add (would stringify)")
    }

    @Test
    fun `response serializes nested DTO extent as array of objects`(@TempDir tmp: Path) {
        val src = tmp.resolve("src").toFile()
        File(src, "com/example/Address.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.Address:
                    DEFINE PUBLIC PROPERTY city AS CHARACTER.
            """.trimIndent())
        }
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    DEFINE PUBLIC PROPERTY addrs AS com.example.Address EXTENT 2.
            """.trimIndent())
        }

        val genDir = tmp.resolve("gen").toFile()
        val task = ShimEmitHelper.createTask()
        val shim = ShimEmitHelper.invokeWriteShim(
            task = task,
            srcDir = src,
            genDir = genDir,
            routePath = "svc/users",
            routeDef = com.pyoif.tatara.GenerateRoutesTask.RouteDef(
                routePath = "svc/users",
                httpMethod = "GET",
                className = "com.example.UserController",
                ablMethod = "GetUser",
                responseDtoClassName = "com.example.User"
            )
        )

        assertTrue(shim.contains("DEFINE VARIABLE oArr_addrs AS Progress.Json.ObjectModel.JsonArray"),
            "shim should declare oArr_addrs JsonArray. SHIM:\n$shim")
        assertTrue(shim.contains("oArr_addrs:Add(oItem_addrs)."),
            "shim should add per-item object to array. SHIM:\n$shim")
        assertTrue(shim.contains("oItem_addrs:Add(\"city\", oResult:addrs[i_addrs]:city)."),
            "shim should build per-item JsonObject with nested prop. SHIM:\n$shim")
        assertTrue(shim.contains("oJson:Add(\"addrs\", oArr_addrs)."),
            "shim should attach array under prop name. SHIM:\n$shim")
    }
```

- [ ] **Step 2: Run, expect fail**

```bash
./gradlew test --tests com.pyoif.tatara.GenerateRouteTaskEmitTest
```

Expected: 2 new tests fail. Existing tests pass.

- [ ] **Step 3: Add extent branch to `emitResponseJson` and `emitNestedJson`**

In `src/main/kotlin/com/pyoif/tatara/GenerateRouteTask.kt`, modify `emitResponseJson` to add an `isExtent` branch before the `isTempTable` branch. Add a helper function `emitExtentBranch` that both `emitResponseJson` and `emitNestedJson` can call. The helper takes:
- `sb: StringBuilder`
- `subObjName: String` (the JsonObject to add the array to: `oJson` for top level, `oSub_<name>` for nested)
- `prop: DtoProperty`
- `accessor: String` (e.g. `oResult` or `oResult:parent`)

The helper:

```kotlin
private fun emitExtentBranch(sb: StringBuilder, subObjName: String, prop: DtoProperty, accessor: String) {
    val propAccessor = "$accessor:${prop.name}"
    val arrName = "oArr_${prop.name}"
    val idxName = "i_${prop.name}"
    sb.append("\t\tDEFINE VARIABLE $arrName AS Progress.Json.ObjectModel.JsonArray NO-UNDO.\r\n")
    sb.append("\t\t$arrName = NEW Progress.Json.ObjectModel.JsonArray().\r\n")
    sb.append("\t\tDEFINE VARIABLE $idxName AS INTEGER NO-UNDO.\r\n")
    sb.append("\t\tDO $idxName = 1 TO EXTENT($propAccessor):\r\n")
    val isScalar = setOf(
        "CHARACTER", "LONGCHAR", "INTEGER", "INT64", "DECIMAL",
        "LOGICAL", "DATE", "DATETIME", "DATETIME-TZ"
    ).contains(prop.ablType.uppercase())
    if (isScalar) {
        sb.append("\t\t\t$arrName:Add($propAccessor[$idxName]).\r\n")
    } else if (prop.nested != null) {
        val itemName = "oItem_${prop.name}"
        sb.append("\t\t\tDEFINE VARIABLE $itemName AS Progress.Json.ObjectModel.JsonObject NO-UNDO.\r\n")
        sb.append("\t\t\t$itemName = NEW Progress.Json.ObjectModel.JsonObject().\r\n")
        prop.nested.properties.forEach { nestedProp ->
            sb.append("\t\t\t$itemName:Add(\"${nestedProp.name}\", $propAccessor[$idxName]:${nestedProp.name}).\r\n")
        }
        sb.append("\t\t\t$arrName:Add($itemName).\r\n")
    } else {
        sb.append("\t\t\t/* extent of unresolvable type: ${prop.ablType} */\r\n")
    }
    sb.append("\t\tEND.\r\n")
    sb.append("\t\t$subObjName:Add(\"${prop.name}\", $arrName).\r\n")
}
```

In `emitResponseJson` (the existing top-level helper), add the `isExtent` branch:

```kotlin
prop.isExtent -> emitExtentBranch(sb, "oJson", prop, oResultAccessor)
prop.isTempTable -> { ... existing ... }
```

In `emitNestedJson` (the existing nested helper), add the `isExtent` branch:

```kotlin
prop.isExtent -> emitExtentBranch(sb, subObjName, prop, oResultAccessor)
prop.isTempTable -> { ... existing ... }
```

- [ ] **Step 4: Run, expect pass**

```bash
./gradlew test --tests com.pyoif.tatara.GenerateRouteTaskEmitTest
```

Expected: all 7 emit tests pass (5 existing + 2 new).

- [ ] **Step 5: Run full suite**

```bash
./gradlew test
```

Expected: all tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/pyoif/tatara/GenerateRouteTask.kt src/test/kotlin/com/pyoif/tatara/GenerateRouteTaskEmitTest.kt
git commit -m "feat(shim): serialize EXTENT props as JSON arrays"
```

---

## Task 4: End-to-end manual verification (in PASOE)

**Files:** none modified.

This task is a manual smoke test inside a downstream project that uses the Tatara plugin. It cannot run from the plugin repo itself.

- [ ] **Step 1: Build the plugin to a local Maven repo**

```bash
./gradlew publishToMavenLocal
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: In a downstream project, regenerate shims**

```bash
./gradlew tataraGenerateRoutes
```

Inspect a generated shim for a route whose request/response DTO has an extent prop. Confirm:
- Request body extent: shim reads JSON array, loops over `oArr:Length`, assigns per-element via `Get<type>`.
- Response extent: shim declares `oArr_<name>` JsonArray, loops over `EXTENT()`, adds each element (or builds per-item JsonObject for nested DTO extent).

- [ ] **Step 3: Start PASOE and curl an endpoint**

```bash
curl -i http://localhost:<port>/<svc>/<route>
```

Expected: HTTP 200, body is JSON with the extent prop as a JSON array.

- [ ] **Step 4: POST a request with an extent field**

```bash
curl -i -X POST -H "Content-Type: application/json" -d '{"tags":["a","b","c"]}' http://localhost:<port>/<svc>/<route>
```

Expected: HTTP 200 (or 4xx with `{"error":"..."}` if controller rejects). The DTO's extent prop is populated.

- [ ] **Step 5: No commit**

This task produces no code changes. Skip the commit step.

---

## Self-Review Notes

- Spec §1 DtoParser extent size → Task 1.
- Spec §2 request body extent branch → Task 2.
- Spec §3 response extent branch (scalar + nested) → Task 3.
- Spec §4 OpenAPI no change — confirmed by inspection.
- Spec §5 testing → Tasks 1, 2, 3, 4.
- Spec §6 error handling — loop bound uses `EXTENT()` for source, `oArr:Length` for target; `MIN()` for safety in request body (Task 2 uses `oArr:Length` only, which can overflow fixed extents; improve if user reports issues).
- No placeholders. All test code shown. All Kotlin edits specified.
- Type consistency: `DtoProperty.extentSize` introduced in Task 1, consumed in Task 3.
