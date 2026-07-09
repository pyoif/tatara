# Inline Temp-Table Modeling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When a DTO class has an inline `DEFINE TEMP-TABLE` block, emit a typed `items` (or object) schema in the OpenAPI spec instead of the current generic `additionalProperties: true` fallback.

**Architecture:** Extend `DtoParser` with `parseInlineTempTable` that scans a class file for `DEFINE TEMP-TABLE` + `FIELD` declarations. Extend `GenerateOpenApiTask.addDtoToSchemas` to call this when emitting a schema for an `isTempTable` prop without a `nested` DTO. Shim emission unchanged.

**Tech Stack:** Kotlin (Gradle plugin code), Gson (JSON construction), JUnit 5 (Kotlin unit tests), Gradle `kotlin-dsl`.

## Global Constraints

- Branch: `feat/swagger-typed-dto`.
- One inline temp-table per class (first one wins; multiple TTs out of scope).
- ABL field types map to OpenAPI types via the same `typeMap` used elsewhere.
- `EXTENT` field types emit `type: array, items: <type>`.
- Unknown field types emit `{type: object, additionalProperties: true}`.
- Shim emission unchanged.

---

## File Structure

| File | Responsibility |
| --- | --- |
| `src/main/kotlin/com/pyoif/tatara/DtoParser.kt` (modify) | New `InlineTempTable` data class + `parseInlineTempTable` + regexes. |
| `src/main/kotlin/com/pyoif/tatara/GenerateOpenApiTask.kt` (modify) | Inline temp-table schema emission in `addDtoToSchemas`. |
| `src/test/kotlin/com/pyoif/tatara/DtoParserTest.kt` (modify) | Inline temp-table parsing tests. |
| `src/test/kotlin/com/pyoif/tatara/GenerateOpenApiTaskTest.kt` (modify) | Typed vs. generic schema tests. |

---

## Task 1: Parse inline temp-table in DtoParser

**Files:**
- Modify: `src/main/kotlin/com/pyoif/tatara/DtoParser.kt`
- Modify: `src/test/kotlin/com/pyoif/tatara/DtoParserTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces:
  - `data class InlineTempTable(val bufferName: String, val fields: DtoInfo)`.
  - `fun parseInlineTempTable(dtoClassName: String, srcRoot: File): InlineTempTable?` — returns the first `DEFINE TEMP-TABLE` block in the class file, or `null` if none. Field list is a `DtoInfo` with one `DtoProperty` per `FIELD` (no `nested`/`isDto` for fields; only `name`/`ablType`/`isExtent`/`extentSize` populated).
  - `private val ttDefRegex = Regex("""(?is)DEFINE\s+TEMP-TABLE\s+(\w+)([^.]+?)\.""")` — matches `DEFINE TEMP-TABLE <name> <body>.`.
  - `private val fieldDefRegex = Regex("""(?i)FIELD\s+(\w+)\s+AS\s+(\w+(?:[.-]\w+)*)(?:\s+(EXTENT(?:\s+\d+)?))?""")` — matches `FIELD <name> AS <type> [EXTENT [N]]`.

- [ ] **Step 1: Add failing tests**

Append to `src/test/kotlin/com/pyoif/tatara/DtoParserTest.kt`:

```kotlin
    @Test
    fun `parseInlineTempTable extracts fields from DEFINE TEMP-TABLE`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/Order.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.Order:
                    DEFINE TEMP-TABLE ttItems NO-UNDO
                        FIELD orderId AS INTEGER
                        FIELD sku     AS CHARACTER.
                    DEFINE PUBLIC PROPERTY id AS INTEGER.
            """.trimIndent())
        }
        val tt = DtoParser.parseInlineTempTable("com.example.Order", src)
        assertNotNull(tt)
        assertEquals("ttItems", tt!!.bufferName)
        assertEquals(2, tt.fields.properties.size)
        assertEquals("orderId", tt.fields.properties[0].name)
        assertEquals("INTEGER", tt.fields.properties[0].ablType)
        assertEquals("sku", tt.fields.properties[1].name)
        assertEquals("CHARACTER", tt.fields.properties[1].ablType)
    }

    @Test
    fun `parseInlineTempTable returns null when no TEMP-TABLE`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    DEFINE PUBLIC PROPERTY id AS INTEGER.
            """.trimIndent())
        }
        val tt = DtoParser.parseInlineTempTable("com.example.User", src)
        assertNull(tt)
    }

    @Test
    fun `parseInlineTempTable captures EXTENT on field`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/Order.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.Order:
                    DEFINE TEMP-TABLE ttItems FIELD tags AS CHARACTER EXTENT 3.
            """.trimIndent())
        }
        val tt = DtoParser.parseInlineTempTable("com.example.Order", src)
        assertNotNull(tt)
        val field = tt!!.fields.properties[0]
        assertTrue(field.isExtent)
        assertEquals(3, field.extentSize)
    }
```

- [ ] **Step 2: Run, expect fail**

```bash
./gradlew test --tests com.pyoif.tatara.DtoParserTest
```

Expected: 3 new tests fail (unresolved `InlineTempTable`/`parseInlineTempTable`).

- [ ] **Step 3: Add `InlineTempTable` data class + `parseInlineTempTable`**

In `src/main/kotlin/com/pyoif/tatara/DtoParser.kt`:

1. Add the data class near the top of the object (after the other enums/data classes):

```kotlin
data class InlineTempTable(
    val bufferName: String,
    val fields: DtoInfo
)
```

2. Add new private regexes (after the existing `propDefRegex`/`annotationRegex`):

```kotlin
private val ttDefRegex = Regex(
    """(?is)DEFINE\s+TEMP-TABLE\s+(\w+)([^.]+?)\."""
)
private val fieldDefRegex = Regex(
    """(?i)FIELD\s+(\w+)\s+AS\s+(\w+(?:[.-]\w+)*)(?:\s+(EXTENT(?:\s+\d+)?))?"""
)
```

3. Add the new public method (after `parse`):

```kotlin
fun parseInlineTempTable(dtoClassName: String, srcRoot: File): InlineTempTable? {
    val file = resolveFile(dtoClassName, srcRoot) ?: return null
    val content = file.readText()
    val ttMatch = ttDefRegex.find(content) ?: return null
    val bufferName = ttMatch.groupValues[1]
    val body = ttMatch.groupValues[2]
    val properties = mutableListOf<DtoProperty>()
    fieldDefRegex.findAll(body).forEach { m ->
        val name = m.groupValues[1]
        val ablType = m.groupValues[2]
        val isExtent = m.groups[3]?.value != null
        val extentSize = m.groups[3]?.value?.trim()?.split(Regex("\\s+"))?.lastOrNull()?.toIntOrNull()
        properties.add(DtoProperty(
            name = name,
            ablType = ablType,
            isExtent = isExtent,
            extentSize = extentSize
        ))
    }
    return InlineTempTable(bufferName, DtoInfo(properties))
}
```

- [ ] **Step 4: Run, expect pass**

```bash
./gradlew test --tests com.pyoif.tatara.DtoParserTest
```

Expected: all 14 tests pass (11 existing + 3 new).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/pyoif/tatara/DtoParser.kt src/test/kotlin/com/pyoif/tatara/DtoParserTest.kt
git commit -m "feat(dto): parse inline TEMP-TABLE FIELD declarations"
```

---

## Task 2: Emit typed schema in `addDtoToSchemas` for inline temp-tables

**Files:**
- Modify: `src/main/kotlin/com/pyoif/tatara/GenerateOpenApiTask.kt`
- Modify: `src/test/kotlin/com/pyoif/tatara/GenerateOpenApiTaskTest.kt`

**Interfaces:**
- Consumes: `DtoParser.parseInlineTempTable` (new in Task 1).
- Produces: in `addDtoToSchemas`, when a prop is `isTempTable` and has no `nested`:
  - Call `DtoParser.parseInlineTempTable(dtoClass, pkgRoot)`.
  - If non-null: emit a typed object schema with the field list as properties. Wrap in array for `ARRAY` kind.
  - If null: fall back to current generic schema.
- Type mapping uses the same `typeMap` as elsewhere.

- [ ] **Step 1: Add failing tests**

Append to `src/test/kotlin/com/pyoif/tatara/GenerateOpenApiTaskTest.kt`:

```kotlin
    @Test
    fun `emits typed array schema from inline temp-table fields`(@TempDir tmp: Path) {
        val src = tmp.resolve("src").toFile()
        val handlers = tmp.resolve("handlers").toFile()
        val out = tmp.resolve("out").toFile()

        File(src, "com/example/OrderController.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.OrderController:
                    // @GET("/svc/orders")
                    METHOD PUBLIC com.example.Order GetOrder():
                        DEFINE VARIABLE ctrl0 AS com.example.OrderController NO-UNDO.
                        ctrl0 = NEW com.example.OrderController().
                        RETURN NEW com.example.Order().
                    END METHOD.
            """.trimIndent())
        }
        File(src, "com/example/Order.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.Order:
                    DEFINE TEMP-TABLE ttItems NO-UNDO
                        FIELD orderId AS INTEGER
                        FIELD sku     AS CHARACTER.
                    // @Array
                    DEFINE PUBLIC PROPERTY items AS HANDLE.
            """.trimIndent())
        }

        writeHandlers(handlers, "svc", "com.example.OrderController", "/svc/orders")
        runGenerateOpenApi(src, handlers, out)

        val swagger = out.resolve("swagger.json").readText()
        assertTrue(swagger.contains("\"orderId\""), "should expose orderId field. Got:\n$swagger")
        assertTrue(swagger.contains("\"sku\""), "should expose sku field. Got:\n$swagger")
        assertTrue(swagger.contains("\"type\": \"integer\"") && swagger.contains("\"orderId\""),
            "orderId should be typed as integer. Got:\n$swagger")
        assertTrue(swagger.contains("\"type\": \"string\"") && swagger.contains("\"sku\""),
            "sku should be typed as string. Got:\n$swagger")
    }

    @Test
    fun `falls back to generic schema when no inline temp-table`(@TempDir tmp: Path) {
        val src = tmp.resolve("src").toFile()
        val handlers = tmp.resolve("handlers").toFile()
        val out = tmp.resolve("out").toFile()

        File(src, "com/example/OrderController.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.OrderController:
                    // @GET("/svc/orders")
                    METHOD PUBLIC com.example.Order GetOrder():
                        DEFINE VARIABLE ctrl0 AS com.example.OrderController NO-UNDO.
                        ctrl0 = NEW com.example.OrderController().
                        RETURN NEW com.example.Order().
                    END METHOD.
            """.trimIndent())
        }
        File(src, "com/example/Order.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.Order:
                    // @Array
                    DEFINE PUBLIC PROPERTY items AS HANDLE.
            """.trimIndent())
        }

        writeHandlers(handlers, "svc", "com.example.OrderController", "/svc/orders")
        runGenerateOpenApi(src, handlers, out)

        val swagger = out.resolve("swagger.json").readText()
        assertTrue(swagger.contains("\"additionalProperties\": true"),
            "should fall back to generic schema. Got:\n$swagger")
    }
```

- [ ] **Step 2: Run, expect fail**

```bash
./gradlew test --tests com.pyoif.tatara.GenerateOpenApiTaskTest
```

Expected: 2 new tests fail. Existing tests pass.

- [ ] **Step 3: Update `addDtoToSchemas` to use inline temp-table**

In `src/main/kotlin/com/pyoif/tatara/GenerateOpenApiTask.kt`, find the `addDtoToSchemas` per-property loop. Currently it has a `prop.isTempTable` branch that emits the generic schema. Replace the existing temp-table branches with calls to a new helper:

```kotlin
prop.isTempTable -> {
    val inlineTt = DtoParser.parseInlineTempTable(dtoClass, pkgRoot)
    val typedSchema = if (inlineTt != null) buildTempTableObjectSchema(inlineTt.fields) else null
    when (prop.tempTableKind) {
        DtoParser.TempTableKind.ARRAY -> {
            if (typedSchema != null) {
                sb.append("\t\toJson:Add(\"${prop.name}\", ").add(/* typed array wrapper */)
                // ... emit the array wrapper inline
            } else {
                // current generic array
            }
        }
        // ...
    }
}
```

The exact emission form is detailed below. Add a private helper `buildTempTableObjectSchema` to `GenerateOpenApiTask.kt`:

```kotlin
private fun buildTempTableObjectSchema(fields: DtoParser.DtoInfo): JsonObject {
    val innerProps = JsonObject()
    fields.properties.forEach { p ->
        innerProps.add(p.name, mapAblType(p.ablType, if (p.isExtent) "EXTENT" else null, JsonObject()))
    }
    return JsonObject().apply {
        addProperty("type", "object")
        add("properties", innerProps)
    }
}
```

In the `isTempTable` branch, replace the existing emission with:

```kotlin
prop.isTempTable -> {
    val inlineTt = DtoParser.parseInlineTempTable(dtoClass, pkgRoot)
    val desc = when (prop.tempTableKind) {
        DtoParser.TempTableKind.ARRAY -> "ABL temp-table; field-level schema from inline DEFINE TEMP-TABLE"
        DtoParser.TempTableKind.OBJECT -> "ABL temp-table (single-row); field-level schema from inline DEFINE TEMP-TABLE"
        DtoParser.TempTableKind.NONE -> null
    }
    val propSchema = when (prop.tempTableKind) {
        DtoParser.TempTableKind.ARRAY -> {
            if (inlineTt != null) {
                val typedObj = buildTempTableObjectSchema(inlineTt.fields)
                JsonObject().apply {
                    addProperty("type", "array")
                    add("items", typedObj)
                    if (desc != null) addProperty("description", desc)
                }
            } else {
                JsonObject().apply {
                    addProperty("type", "array")
                    add("items", JsonObject().apply {
                        addProperty("type", "object")
                        addProperty("additionalProperties", true)
                    })
                    if (desc != null) addProperty("description", desc)
                }
            }
        }
        DtoParser.TempTableKind.OBJECT -> {
            if (inlineTt != null) {
                val typedObj = buildTempTableObjectSchema(inlineTt.fields)
                JsonObject().apply {
                    add("type", typedObj.get("type"))
                    add("properties", typedObj.get("properties"))
                    if (desc != null) addProperty("description", desc)
                }
            } else {
                JsonObject().apply {
                    addProperty("type", "object")
                    addProperty("additionalProperties", true)
                    if (desc != null) addProperty("description", desc)
                }
            }
        }
        DtoParser.TempTableKind.NONE -> JsonObject().apply { addProperty("type", "null") }
    }
    innerProps.add(prop.name, propSchema)
}
```

(For `OBJECT` kind, the existing pattern wraps the schema in an outer `{type, additionalProperties, description}` — the new typed version flattens `type` and `properties` from the typed object into the outer schema. Adjust the code above to use the typed object's properties directly.)

- [ ] **Step 4: Run, expect pass**

```bash
./gradlew test --tests com.pyoif.tatara.GenerateOpenApiTaskTest
```

Expected: all 7 OpenAPI tests pass (5 existing + 2 new).

- [ ] **Step 5: Run full suite**

```bash
./gradlew test
```

Expected: all tests pass (29 total).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/pyoif/tatara/GenerateOpenApiTask.kt src/test/kotlin/com/pyoif/tatara/GenerateOpenApiTaskTest.kt
git commit -m "feat(openapi): emit typed schema from inline temp-table fields"
```

---

## Task 3: End-to-end manual verification (in PASOE)

**Files:** none modified.

This task is a manual smoke test inside a downstream project that uses the Tatara plugin. It cannot run from the plugin repo itself.

- [ ] **Step 1: Build the plugin to a local Maven repo**

```bash
./gradlew publishToMavenLocal
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: In a downstream project, regenerate the OpenAPI spec**

```bash
./gradlew tataraGenerateOpenApi
```

- [ ] **Step 3: Inspect the generated `swagger.json`**

For a DTO with an inline temp-table:
```progress
CLASS com.example.Order:
    DEFINE TEMP-TABLE ttItems FIELD orderId AS INTEGER FIELD sku AS CHARACTER.
    // @Array
    DEFINE PUBLIC PROPERTY items AS HANDLE.
```

Confirm:
- The schema for `Order` has `items` as a typed object with `orderId` (integer) and `sku` (string) properties.
- The schema does NOT use `additionalProperties: true` (the generic fallback).

For a DTO without an inline temp-table (just `// @Array DEFINE PUBLIC PROPERTY items AS HANDLE.`):
- Confirm the generic `{type: array, items: {type: object, additionalProperties: true}}` is still emitted (regression).

- [ ] **Step 4: No commit**

This task produces no code changes. Skip the commit step.

---

## Self-Review Notes

- Spec §1 DtoParser extension → Task 1.
- Spec §2 OpenAPI emission change → Task 2.
- Spec §3 shim unchanged → no task.
- Spec §4 error handling → fall back to generic schema if parsing fails.
- Spec §5 testing → Tasks 1, 2, 3.
- No placeholders. All test code shown. All Kotlin edits specified.
- Type consistency: `InlineTempTable` introduced in Task 1, consumed in Task 2. `DtoParser.parseInlineTempTable` signature is pure read-only.
