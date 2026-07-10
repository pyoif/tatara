# Annotation Cross-Class & Explicit-Buffer Binding — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend `@Object` / `@Array` annotations to accept an optional `"ClassName[:BufferName]"` parameter, so a HANDLE prop can resolve its temp-table from a different class file and/or with an explicit buffer name. OpenAPI schema generation only — shim is unchanged.

**Architecture:** Extend the annotation regex in `DtoParser` to capture an optional quoted parameter; parse it into two new `DtoProperty` fields (`tempTableClass`, `tempTableName`); consume them in `GenerateOpenApiTask.addDtoToSchemas` to drive the existing `parseInlineTempTableByName` lookup. Backward compatible: no parens = current behavior.

**Tech Stack:** Kotlin, Gradle, JUnit 5, Gson, ABL/OpenEdge annotation comments (`// @...`).

## Global Constraints

- Parameter syntax: `"ClassName[:BufferName]"`; no parens = current behavior. `@TempTable` does NOT accept parameters.
- Lookup rule: `tempTableClass ?: dtoClass` for source class; `tempTableName ?: ("tt" + p.name.pascalCase)` for buffer name.
- Fallback when explicit class is given and lookup misses: emit generic schema (same shape as today), log `logger.warn` with class/buffer/prop context.
- Backward compatibility is required: all existing `// @Array`, `// @Object`, `// @TempTable` annotations must keep working unchanged.
- `GenerateRouteTask.kt` is NOT modified. The shim does not need to know the buffer name.
- Follow the existing test patterns: `@TempDir`, `File(src, "com/example/X.cls").apply { ... writeText(...) }`, `DtoParser.parse(className, src)`.
- **Nested TTs (Tasks 3-4):** annotation must be present on a HANDLE-typed FIELD to be a nested TT; unannotated HANDLE fields stay as plain handles. Recursion uses a `"Class::Buffer"` visited set to break cycles.

---

## File Structure

| File | Change |
|------|--------|
| `src/main/kotlin/com/pyoif/tatara/DtoParser.kt` | Task 1: add 2 fields to `DtoProperty`, extend `annotationRegex`, add `parseTempTableParam` helper, wire new fields. Task 3: refactor `parseAllInlineTempTables` to per-line processing with annotation handling for FIELD lines. |
| `src/main/kotlin/com/pyoif/tatara/GenerateOpenApiTask.kt` | Task 2: in `addDtoToSchemas`, swap hardcoded `dtoClass` / `tt<PropName>` for the new prop fields; add `logger.warn` on miss. Task 4: `buildTempTableObjectSchema` gains `pkgRoot` parameter + recursive nested-TT resolution with visited set. |
| `src/test/kotlin/com/pyoif/tatara/DtoParserTest.kt` | Task 1: 6 new tests. Task 3: 5 new tests for nested TT field parsing. |
| `src/test/kotlin/com/pyoif/tatara/GenerateOpenApiTaskTest.kt` | Task 2: 5 new tests. Task 4: 4 new tests for nested TT OpenAPI emission. |

`GenerateRouteTask.kt`, existing tests, and all other files are untouched.

---

### Task 1: Parser changes — capture and store `"Class[:Buffer]"` parameter

**Files:**
- Modify: `src/main/kotlin/com/pyoif/tatara/DtoParser.kt:5-21` (data class), `:38` (regex), `:55-117` (handler + reset)
- Test: `src/test/kotlin/com/pyoif/tatara/DtoParserTest.kt` (add 6 new tests at end of class)

**Interfaces:**
- Consumes: existing `DtoProperty` data class; existing `annotationRegex`; existing parse loop.
- Produces:
  - `DtoProperty.tempTableClass: String?` (null = same class).
  - `DtoProperty.tempTableName: String?` (null = `tt<PropName>` convention).
  - `parseTempTableParam(raw: String?): TempTableParam` (private; not part of public API).

- [ ] **Step 1: Add the 6 failing tests to `DtoParserTest.kt`**

Append the following inside `class DtoParserTest` (after the last existing `@Test`):

```kotlin
    @Test
    fun `parses @Array with class name only`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    // @Array("com.example.Order")
                    DEFINE PUBLIC PROPERTY items AS HANDLE.
            """.trimIndent())
        }
        val info = DtoParser.parse("com.example.User", src)
        val prop = info.properties[0]
        assertTrue(prop.isTempTable)
        assertEquals(DtoParser.TempTableKind.ARRAY, prop.tempTableKind)
        assertEquals("com.example.Order", prop.tempTableClass)
        assertNull(prop.tempTableName)
    }

    @Test
    fun `parses @Object with class name only`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    // @Object("com.example.Summary")
                    DEFINE PUBLIC PROPERTY summary AS HANDLE.
            """.trimIndent())
        }
        val info = DtoParser.parse("com.example.User", src)
        val prop = info.properties[0]
        assertTrue(prop.isTempTable)
        assertEquals(DtoParser.TempTableKind.OBJECT, prop.tempTableKind)
        assertEquals("com.example.Summary", prop.tempTableClass)
        assertNull(prop.tempTableName)
    }

    @Test
    fun `parses @Array with class and buffer name`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    // @Array("com.example.Order:ttOrders")
                    DEFINE PUBLIC PROPERTY items AS HANDLE.
            """.trimIndent())
        }
        val info = DtoParser.parse("com.example.User", src)
        val prop = info.properties[0]
        assertTrue(prop.isTempTable)
        assertEquals("com.example.Order", prop.tempTableClass)
        assertEquals("ttOrders", prop.tempTableName)
    }

    @Test
    fun `parses @Array with current class and explicit buffer via leading colon`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    // @Array(":ttCustom")
                    DEFINE PUBLIC PROPERTY items AS HANDLE.
            """.trimIndent())
        }
        val info = DtoParser.parse("com.example.User", src)
        val prop = info.properties[0]
        assertTrue(prop.isTempTable)
        assertNull(prop.tempTableClass)
        assertEquals("ttCustom", prop.tempTableName)
    }

    @Test
    fun `parses @Array without parameter preserves null fields`(@TempDir tmp: Path) {
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
        assertNull(prop.tempTableClass)
        assertNull(prop.tempTableName)
    }

    @Test
    fun `TempTable annotation drops any parameter syntax`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    // @TempTable("com.example.X:ttY")
                    DEFINE PUBLIC PROPERTY items AS HANDLE.
            """.trimIndent())
        }
        val info = DtoParser.parse("com.example.User", src)
        val prop = info.properties[0]
        assertTrue(prop.isTempTable)
        assertEquals(DtoParser.TempTableKind.ARRAY, prop.tempTableKind)
        assertNull(prop.tempTableClass)
        assertNull(prop.tempTableName)
    }
```

- [ ] **Step 2: Run the 6 new tests to verify they fail**

Run: `./gradlew test --tests "com.pyoif.tatara.DtoParserTest.parses @Array with class name only" --tests "com.pyoif.tatara.DtoParserTest.parses @Object with class name only" --tests "com.pyoif.tatara.DtoParserTest.parses @Array with class and buffer name" --tests "com.pyoif.tatara.DtoParserTest.parses @Array with current class and explicit buffer via leading colon" --tests "com.pyoif.tatara.DtoParserTest.parses @Array without parameter preserves null fields" --tests "com.pyoif.tatara.DtoParserTest.TempTable annotation drops any parameter syntax"`
Expected: FAIL with "unresolved reference: tempTableClass" / "unresolved reference: tempTableName" on each test.

- [ ] **Step 3: Add the two new fields to `DtoProperty`**

In `src/main/kotlin/com/pyoif/tatara/DtoParser.kt`, replace the `DtoProperty` data class (lines 8-20) with:

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
        val tempTableKind: TempTableKind = TempTableKind.NONE,
        val tempTableClass: String? = null,
        val tempTableName: String? = null
    )
```

- [ ] **Step 4: Extend `annotationRegex` to capture the optional quoted parameter**

In `src/main/kotlin/com/pyoif/tatara/DtoParser.kt`, replace the `annotationRegex` declaration (line 38) with:

```kotlin
    private val annotationRegex = Regex("""(?i)//\s*@(Required|Path|Query|Body|TempTable|Object|Array)(?:\("([^"]*)"\))?""")
```

- [ ] **Step 5: Add the `parseTempTableParam` helper at the bottom of the object**

In `src/main/kotlin/com/pyoif/tatara/DtoParser.kt`, just before the closing `}` of the `object DtoParser` (after the existing `resolveFile` function at the end of the file), add:

```kotlin
    private data class TempTableParam(val tempTableClass: String?, val tempTableName: String?)

    private fun parseTempTableParam(raw: String?): TempTableParam {
        if (raw.isNullOrEmpty()) return TempTableParam(null, null)
        val colon = raw.indexOf(':')
        return when {
            colon < 0  -> TempTableParam(raw, null)
            colon == 0 -> TempTableParam(null, raw.substring(1))
            else       -> TempTableParam(raw.substring(0, colon), raw.substring(colon + 1))
        }
    }
```

- [ ] **Step 6: Wire the parsed parameter into the annotation handler and the property emission**

In `src/main/kotlin/com/pyoif/tatara/DtoParser.kt`, in the `parse` function, make the following changes:

(a) Just below the existing `var isTempTable = false` / `var tempTableKind = TempTableKind.NONE` block (around line 59-60), add two more locals:

```kotlin
        var currentTempTableClass: String? = null
        var currentTempTableName: String? = null
```

(b) Replace the annotation handler's `object` / `array` / `temptable` branches (currently lines 76-77):

```kotlin
                    "object" -> { isTempTable = true; tempTableKind = TempTableKind.OBJECT; isReq = false }
                    "array", "temptable" -> { isTempTable = true; tempTableKind = TempTableKind.ARRAY; isReq = false }
```

with:

```kotlin
                    "object" -> {
                        isTempTable = true
                        tempTableKind = TempTableKind.OBJECT
                        isReq = false
                        val (cls, name) = parseTempTableParam(m.groupValues[2])
                        currentTempTableClass = cls
                        currentTempTableName = name
                    }
                    "array" -> {
                        isTempTable = true
                        tempTableKind = TempTableKind.ARRAY
                        isReq = false
                        val (cls, name) = parseTempTableParam(m.groupValues[2])
                        currentTempTableClass = cls
                        currentTempTableName = name
                    }
                    "temptable" -> {
                        isTempTable = true
                        tempTableKind = TempTableKind.ARRAY
                        isReq = false
                        // @TempTable is a bare alias — drop any parameter syntax.
                    }
```

(c) In the `DtoProperty(...)` constructor call (around line 99-110), add the two new fields just after `tempTableKind = tempTableKind`:

```kotlin
                properties.add(DtoProperty(
                    name = name,
                    ablType = ablType,
                    isRequired = isReq,
                    location = loc,
                    isExtent = isExtent,
                    extentSize = extentSize,
                    isDto = isDto,
                    nested = nested,
                    isTempTable = isTempTable,
                    tempTableKind = tempTableKind,
                    tempTableClass = currentTempTableClass,
                    tempTableName = currentTempTableName
                ))
```

(d) In the reset block at the end of the prop block (around lines 115-118), extend it to:

```kotlin
                // Reset @Required and @TempTable kind — location sticks until next @Path/@Query/@Body
                isReq = false
                isTempTable = false
                tempTableKind = TempTableKind.NONE
                currentTempTableClass = null
                currentTempTableName = null
```

- [ ] **Step 7: Run the 6 new tests to verify they pass**

Run: `./gradlew test --tests "com.pyoif.tatara.DtoParserTest.parses @Array with class name only" --tests "com.pyoif.tatara.DtoParserTest.parses @Object with class name only" --tests "com.pyoif.tatara.DtoParserTest.parses @Array with class and buffer name" --tests "com.pyoif.tatara.DtoParserTest.parses @Array with current class and explicit buffer via leading colon" --tests "com.pyoif.tatara.DtoParserTest.parses @Array without parameter preserves null fields" --tests "com.pyoif.tatara.DtoParserTest.TempTable annotation drops any parameter syntax"`
Expected: all 6 PASS.

- [ ] **Step 8: Run the full DtoParserTest suite to confirm no regressions**

Run: `./gradlew test --tests "com.pyoif.tatara.DtoParserTest"`
Expected: all tests PASS (existing + 6 new).

- [ ] **Step 9: Commit**

```bash
git add src/main/kotlin/com/pyoif/tatara/DtoParser.kt src/test/kotlin/com/pyoif/tatara/DtoParserTest.kt
git commit -m "feat(dto): parse optional \"Class[:Buffer]\" parameter on @Array/@Object"
```

---

### Task 2: OpenAPI task — consume `tempTableClass` / `tempTableName`

**Files:**
- Modify: `src/main/kotlin/com/pyoif/tatara/GenerateOpenApiTask.kt:162-205` (the `if (p.isTempTable)` branch in `addDtoToSchemas`)
- Test: `src/test/kotlin/com/pyoif/tatara/GenerateOpenApiTaskTest.kt` (add 5 new tests at end of class)

**Interfaces:**
- Consumes: `DtoProperty.tempTableClass: String?`, `DtoProperty.tempTableName: String?` (from Task 1).
- Produces: updated `addDtoToSchemas` that uses those fields as the source class and buffer name inputs to `parseInlineTempTableByName`.

- [ ] **Step 1: Add the 5 failing tests to `GenerateOpenApiTaskTest.kt`**

Append the following inside `class GenerateOpenApiTaskTest` (after the last existing `@Test`):

```kotlin
    @Test
    fun `cross-class @Array resolves TT from target class file`(@TempDir tmp: Path) {
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
                    // @Array("com.example.OrderHolder")
                    DEFINE PUBLIC PROPERTY items AS HANDLE.
            """.trimIndent())
        }
        File(src, "com/example/OrderHolder.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.OrderHolder:
                    DEFINE TEMP-TABLE ttItems NO-UNDO
                        FIELD orderId AS INTEGER
                        FIELD sku     AS CHARACTER.
            """.trimIndent())
        }

        writeHandlers(handlers, "svc", "com.example.OrderController", "/svc/orders")
        runGenerateOpenApi(src, handlers, out)

        val swagger = out.resolve("swagger.json").readText()
        assertTrue(swagger.contains("\"orderId\""), "should include orderId field from target TT. Got:\n$swagger")
        assertTrue(swagger.contains("\"sku\""), "should include sku field from target TT. Got:\n$swagger")
        assertTrue(swagger.contains("\"type\": \"array\""), "should emit array type. Got:\n$swagger")
    }

    @Test
    fun `cross-class @Array with explicit buffer name picks that buffer`(@TempDir tmp: Path) {
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
                    // @Array("com.example.OrderHolder:ttOrders")
                    DEFINE PUBLIC PROPERTY items AS HANDLE.
            """.trimIndent())
        }
        File(src, "com/example/OrderHolder.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.OrderHolder:
                    DEFINE TEMP-TABLE ttOther NO-UNDO
                        FIELD ignored AS CHARACTER.
                    DEFINE TEMP-TABLE ttOrders NO-UNDO
                        FIELD orderId AS INTEGER.
            """.trimIndent())
        }

        writeHandlers(handlers, "svc", "com.example.OrderController", "/svc/orders")
        runGenerateOpenApi(src, handlers, out)

        val swagger = out.resolve("swagger.json").readText()
        assertTrue(swagger.contains("\"orderId\""), "should include orderId from ttOrders. Got:\n$swagger")
        assertFalse(swagger.contains("\"ignored\""), "should not include 'ignored' field from ttOther. Got:\n$swagger")
    }

    @Test
    fun `cross-class @Array falls back to generic schema when target class missing`(@TempDir tmp: Path) {
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
                    // @Array("com.example.Missing")
                    DEFINE PUBLIC PROPERTY items AS HANDLE.
            """.trimIndent())
        }
        // No Missing.cls — lookup must miss.

        writeHandlers(handlers, "svc", "com.example.OrderController", "/svc/orders")
        runGenerateOpenApi(src, handlers, out)

        val swagger = out.resolve("swagger.json").readText()
        assertTrue(swagger.contains("\"type\": \"array\""), "should still emit array type on fallback. Got:\n$swagger")
        assertTrue(swagger.contains("\"additionalProperties\": true"), "should emit generic items on fallback. Got:\n$swagger")
        assertTrue(swagger.contains("ABL temp-table"), "should keep description on fallback. Got:\n$swagger")
    }

    @Test
    fun `current-class @Array with leading-colon explicit buffer`(@TempDir tmp: Path) {
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
                    // @Array(":ttCustom")
                    DEFINE PUBLIC PROPERTY items AS HANDLE.
                    DEFINE TEMP-TABLE ttOther  NO-UNDO FIELD skipped AS CHARACTER.
                    DEFINE TEMP-TABLE ttCustom NO-UNDO FIELD a AS INTEGER.
            """.trimIndent())
        }

        writeHandlers(handlers, "svc", "com.example.OrderController", "/svc/orders")
        runGenerateOpenApi(src, handlers, out)

        val swagger = out.resolve("swagger.json").readText()
        assertTrue(swagger.contains("\"a\""), "should include 'a' from ttCustom. Got:\n$swagger")
        assertFalse(swagger.contains("\"skipped\""), "should not include 'skipped' from ttOther. Got:\n$swagger")
    }

    @Test
    fun `backward-compat @Array without parameter uses tt<PropName> convention`(@TempDir tmp: Path) {
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
                    DEFINE TEMP-TABLE ttItems NO-UNDO FIELD orderId AS INTEGER.
            """.trimIndent())
        }

        writeHandlers(handlers, "svc", "com.example.OrderController", "/svc/orders")
        runGenerateOpenApi(src, handlers, out)

        val swagger = out.resolve("swagger.json").readText()
        assertTrue(swagger.contains("\"orderId\""), "should resolve ttItems by convention. Got:\n$swagger")
    }
```

- [ ] **Step 2: Run the 5 new tests to verify they fail**

Run: `./gradlew test --tests "com.pyoif.tatara.GenerateOpenApiTaskTest.cross-class @Array resolves TT from target class file" --tests "com.pyoif.tatara.GenerateOpenApiTaskTest.cross-class @Array with explicit buffer name picks that buffer" --tests "com.pyoif.tatara.GenerateOpenApiTaskTest.cross-class @Array falls back to generic schema when target class missing" --tests "com.pyoif.tatara.GenerateOpenApiTaskTest.current-class @Array with leading-colon explicit buffer" --tests "com.pyoif.tatara.GenerateOpenApiTaskTest.backward-compat @Array without parameter uses tt<PropName> convention"`
Expected: the 3 cross-class tests and the leading-colon test FAIL (no typed schema, no field names emitted); the backward-compat test PASSES (current behavior unchanged).

- [ ] **Step 3: Update `addDtoToSchemas` to use the new fields**

In `src/main/kotlin/com/pyoif/tatara/GenerateOpenApiTask.kt`, replace the lines that compute the temp-table lookup inside the `if (p.isTempTable) { ... }` branch (the current `val expectedTtName = "tt" + ...` and `val inlineTt = DtoParser.parseInlineTempTableByName(...)` lines around line 164-166):

```kotlin
            if (p.isTempTable) {
                val srcClass = p.tempTableClass ?: dtoClass
                val bufName = p.tempTableName ?: ("tt" + p.name.replaceFirstChar { it.uppercase() })
                val inlineTt = DtoParser.parseInlineTempTableByName(srcClass, pkgRoot, bufName)
                if (inlineTt == null && p.tempTableClass != null) {
                    logger.warn("Temp-table '$bufName' not found in class '$srcClass' " +
                                "(prop '${p.name}' on '$dtoClass'); falling back to generic schema.")
                }
```

(Leave the rest of the `if (p.isTempTable)` block — the `desc` `when` and the `propSchema` `when` — unchanged.)

- [ ] **Step 4: Run the 5 new tests to verify they pass**

Run: `./gradlew test --tests "com.pyoif.tatara.GenerateOpenApiTaskTest.cross-class @Array resolves TT from target class file" --tests "com.pyoif.tatara.GenerateOpenApiTaskTest.cross-class @Array with explicit buffer name picks that buffer" --tests "com.pyoif.tatara.GenerateOpenApiTaskTest.cross-class @Array falls back to generic schema when target class missing" --tests "com.pyoif.tatara.GenerateOpenApiTaskTest.current-class @Array with leading-colon explicit buffer" --tests "com.pyoif.tatara.GenerateOpenApiTaskTest.backward-compat @Array without parameter uses tt<PropName> convention"`
Expected: all 5 PASS.

- [ ] **Step 5: Run the full test suite to confirm no regressions**

Run: `./gradlew test`
Expected: all tests PASS (including existing `DtoParserTest`, existing `GenerateOpenApiTaskTest`, `GenerateRouteTaskEmitTest`, and all 11 new tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/pyoif/tatara/GenerateOpenApiTask.kt src/test/kotlin/com/pyoif/tatara/GenerateOpenApiTaskTest.kt
git commit -m "feat(openapi): bind @Array/@Object to cross-class or explicit buffer via \"Class[:Buffer]\" parameter"
```

---

### Task 3: Parser changes — annotation handling for FIELD lines in temp-tables

**Files:**
- Modify: `src/main/kotlin/com/pyoif/tatara/DtoParser.kt:134-158` (the body of `parseAllInlineTempTables`)
- Test: `src/test/kotlin/com/pyoif/tatara/DtoParserTest.kt` (add 5 new tests at end of class)

**Interfaces:**
- Consumes: existing `parseTempTableParam` helper (from Task 1), existing `annotationRegex` (extended in Task 1), existing `fieldDefRegex`.
- Produces: a `DtoProperty` per FIELD where the `isTempTable` / `tempTableKind` / `tempTableClass` / `tempTableName` fields are populated when (a) the line has a `// @Array` / `// @Object` / `// @TempTable` annotation AND (b) the field type is `HANDLE`. Otherwise `isTempTable=false`.

- [ ] **Step 1: Add the 5 failing tests to `DtoParserTest.kt`**

Append the following inside `class DtoParserTest` (after the last existing `@Test`):

```kotlin
    @Test
    fun `parses nested @Array on FIELD line with class only`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/Order.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.Order:
                    DEFINE TEMP-TABLE ttItems NO-UNDO
                        FIELD orderId AS INTEGER
                        // @Array("com.example.Nested")
                        FIELD lines   AS HANDLE
                        FIELD sku     AS CHARACTER.
            """.trimIndent())
        }
        val tt = DtoParser.parseInlineTempTable("com.example.Order", src)!!
        val linesField = tt.fields.properties.find { it.name == "lines" }!!
        assertTrue(linesField.isTempTable)
        assertEquals(DtoParser.TempTableKind.ARRAY, linesField.tempTableKind)
        assertEquals("com.example.Nested", linesField.tempTableClass)
        assertNull(linesField.tempTableName)
    }

    @Test
    fun `parses nested @Object on FIELD line with class and buffer name`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/Order.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.Order:
                    DEFINE TEMP-TABLE ttItems NO-UNDO
                        // @Object("com.example.Nested:ttHeader")
                        FIELD summary AS HANDLE.
            """.trimIndent())
        }
        val tt = DtoParser.parseInlineTempTable("com.example.Order", src)!!
        val summaryField = tt.fields.properties.find { it.name == "summary" }!!
        assertTrue(summaryField.isTempTable)
        assertEquals(DtoParser.TempTableKind.OBJECT, summaryField.tempTableKind)
        assertEquals("com.example.Nested", summaryField.tempTableClass)
        assertEquals("ttHeader", summaryField.tempTableName)
    }

    @Test
    fun `parses bare @Array on FIELD line using convention`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/Order.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.Order:
                    DEFINE TEMP-TABLE ttItems NO-UNDO
                        // @Array
                        FIELD lines   AS HANDLE.
            """.trimIndent())
        }
        val tt = DtoParser.parseInlineTempTable("com.example.Order", src)!!
        val linesField = tt.fields.properties.find { it.name == "lines" }!!
        assertTrue(linesField.isTempTable)
        assertEquals(DtoParser.TempTableKind.ARRAY, linesField.tempTableKind)
        assertNull(linesField.tempTableClass)
        assertEquals("ttLines", linesField.tempTableName)
    }

    @Test
    fun `HANDLE field without annotation is not a nested temp-table`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/Order.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.Order:
                    DEFINE TEMP-TABLE ttItems NO-UNDO
                        FIELD raw   AS HANDLE
                        FIELD orderId AS INTEGER.
            """.trimIndent())
        }
        val tt = DtoParser.parseInlineTempTable("com.example.Order", src)!!
        val rawField = tt.fields.properties.find { it.name == "raw" }!!
        assertFalse(rawField.isTempTable)
        assertEquals(DtoParser.TempTableKind.NONE, rawField.tempTableKind)
    }

    @Test
    fun `non-HANDLE field with @Array annotation is still not a temp-table`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/Order.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.Order:
                    DEFINE TEMP-TABLE ttItems NO-UNDO
                        // @Array
                        FIELD label AS CHARACTER.
            """.trimIndent())
        }
        val tt = DtoParser.parseInlineTempTable("com.example.Order", src)!!
        val labelField = tt.fields.properties.find { it.name == "label" }!!
        assertFalse(labelField.isTempTable)
        assertEquals(DtoParser.TempTableKind.NONE, labelField.tempTableKind)
    }
```

- [ ] **Step 2: Run the 5 new tests to verify they fail**

Run: `./gradlew test --tests "com.pyoif.tatara.DtoParserTest.parses nested @Array on FIELD line with class only" --tests "com.pyoif.tatara.DtoParserTest.parses nested @Object on FIELD line with class and buffer name" --tests "com.pyoif.tatara.DtoParserTest.parses bare @Array on FIELD line using convention" --tests "com.pyoif.tatara.DtoParserTest.HANDLE field without annotation is not a nested temp-table" --tests "com.pyoif.tatara.DtoParserTest.non-HANDLE field with @Array annotation is still not a temp-table"`
Expected: the 3 nested-annotation tests FAIL (no fields are detected as temp-table fields); the 2 negative tests PASS (current behavior is already correct for these cases).

- [ ] **Step 3: Refactor `parseAllInlineTempTables` to per-line processing**

In `src/main/kotlin/com/pyoif/tatara/DtoParser.kt`, replace the `parseAllInlineTempTables` function (lines 134-158) with:

```kotlin
    private fun parseAllInlineTempTables(
        dtoClassName: String,
        srcRoot: File
    ): List<InlineTempTable> {
        val file = resolveFile(dtoClassName, srcRoot) ?: return emptyList()
        val content = file.readText()
        val results = mutableListOf<InlineTempTable>()
        ttDefRegex.findAll(content).forEach { ttMatch ->
            val bufferName = ttMatch.groupValues[1]
            val body = ttMatch.groupValues[2]
            val properties = mutableListOf<DtoProperty>()

            body.lines().forEach { line ->
                val trimmed = line.trim()
                var isTempTable = false
                var tempTableKind = TempTableKind.NONE
                var currentTempTableClass: String? = null
                var currentTempTableName: String? = null

                annotationRegex.findAll(trimmed).forEach { m ->
                    when (m.groupValues[1].lowercase()) {
                        "object" -> {
                            isTempTable = true
                            tempTableKind = TempTableKind.OBJECT
                            val (cls, name) = parseTempTableParam(m.groupValues[2])
                            currentTempTableClass = cls
                            currentTempTableName = name
                        }
                        "array" -> {
                            isTempTable = true
                            tempTableKind = TempTableKind.ARRAY
                            val (cls, name) = parseTempTableParam(m.groupValues[2])
                            currentTempTableClass = cls
                            currentTempTableName = name
                        }
                        "temptable" -> {
                            isTempTable = true
                            tempTableKind = TempTableKind.ARRAY
                            // bare alias — drop any parameter
                        }
                    }
                }

                fieldDefRegex.find(trimmed)?.let { m ->
                    val name = m.groupValues[1]
                    val ablType = m.groupValues[2]
                    val isExtent = m.groups[3]?.value != null
                    val extentSize = m.groups[3]?.value?.trim()?.split(Regex("\\s+"))?.lastOrNull()?.toIntOrNull()

                    val promoteToTempTable = isTempTable && ablType.uppercase() == "HANDLE"
                    val effectiveClass = if (promoteToTempTable) (currentTempTableClass ?: dtoClassName) else null
                    val effectiveName = if (promoteToTempTable) {
                        currentTempTableName ?: ("tt" + name.replaceFirstChar { it.uppercase() })
                    } else null

                    properties.add(DtoProperty(
                        name = name,
                        ablType = ablType,
                        isExtent = isExtent,
                        extentSize = extentSize,
                        isTempTable = promoteToTempTable,
                        tempTableKind = if (promoteToTempTable) tempTableKind else TempTableKind.NONE,
                        tempTableClass = effectiveClass,
                        tempTableName = effectiveName
                    ))
                }
            }

            results.add(InlineTempTable(bufferName, DtoInfo(properties)))
        }
        return results
    }
```

- [ ] **Step 4: Run the 5 new tests to verify they pass**

Run: `./gradlew test --tests "com.pyoif.tatara.DtoParserTest.parses nested @Array on FIELD line with class only" --tests "com.pyoif.tatara.DtoParserTest.parses nested @Object on FIELD line with class and buffer name" --tests "com.pyoif.tatara.DtoParserTest.parses bare @Array on FIELD line using convention" --tests "com.pyoif.tatara.DtoParserTest.HANDLE field without annotation is not a nested temp-table" --tests "com.pyoif.tatara.DtoParserTest.non-HANDLE field with @Array annotation is still not a temp-table"`
Expected: all 5 PASS.

- [ ] **Step 5: Run the full test suite to confirm no regressions**

Run: `./gradlew test --tests "com.pyoif.tatara.DtoParserTest"`
Expected: all tests PASS (existing + 6 from Task 1 + 5 new = 20 total in this file).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/pyoif/tatara/DtoParser.kt src/test/kotlin/com/pyoif/tatara/DtoParserTest.kt
git commit -m "feat(dto): parse @Array/@Object annotations on FIELD lines for nested temp-tables"
```

---

### Task 4: OpenAPI task — recursive nested-TT schema emission

**Files:**
- Modify: `src/main/kotlin/com/pyoif/tatara/GenerateOpenApiTask.kt:227-240` (`buildTempTableObjectSchema`), plus its 2 call sites in `addDtoToSchemas` (lines ~175, ~194)
- Test: `src/test/kotlin/com/pyoif/tatara/GenerateOpenApiTaskTest.kt` (add 4 new tests at end of class)

**Interfaces:**
- Consumes: `DtoProperty.isTempTable` / `tempTableKind` / `tempTableClass` / `tempTableName` (set per-field by Task 3).
- Produces: `buildTempTableObjectSchema(fields: DtoParser.DtoInfo, pkgRoot: File): JsonObject` that recursively resolves nested HANDLE fields annotated as temp-tables. Cycles emit generic schema. Misses log a warning and emit generic schema.

- [ ] **Step 1: Add the 4 failing tests to `GenerateOpenApiTaskTest.kt`**

Append the following inside `class GenerateOpenApiTaskTest` (after the last existing `@Test`):

```kotlin
    @Test
    fun `emits typed nested array schema from inline temp-table fields`(@TempDir tmp: Path) {
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
                    DEFINE TEMP-TABLE ttItems NO-UNDO
                        FIELD orderId AS INTEGER
                        // @Array("com.example.Nested:ttLines")
                        FIELD lines   AS HANDLE.
            """.trimIndent())
        }
        File(src, "com/example/Nested.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.Nested:
                    DEFINE TEMP-TABLE ttLines NO-UNDO
                        FIELD lineNo  AS INTEGER
                        FIELD text    AS CHARACTER.
            """.trimIndent())
        }

        writeHandlers(handlers, "svc", "com.example.OrderController", "/svc/orders")
        runGenerateOpenApi(src, handlers, out)

        val swagger = out.resolve("swagger.json").readText()
        assertTrue(swagger.contains("\"lineNo\""), "should include nested lineNo. Got:\n$swagger")
        assertTrue(swagger.contains("\"text\""), "should include nested text. Got:\n$swagger")
    }

    @Test
    fun `nested @Array falls back to generic when target class missing`(@TempDir tmp: Path) {
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
                    DEFINE TEMP-TABLE ttItems NO-UNDO
                        FIELD orderId AS INTEGER
                        // @Array("com.example.Missing:ttX")
                        FIELD lines AS HANDLE.
            """.trimIndent())
        }
        // No Missing.cls

        writeHandlers(handlers, "svc", "com.example.OrderController", "/svc/orders")
        runGenerateOpenApi(src, handlers, out)

        val swagger = out.resolve("swagger.json").readText()
        assertTrue(swagger.contains("\"orderId\""), "outer orderId should be present. Got:\n$swagger")
        assertTrue(swagger.contains("\"additionalProperties\": true"), "should include generic fallback. Got:\n$swagger")
    }

    @Test
    fun `nested temp-table cycle emits generic schema and does not infinite loop`(@TempDir tmp: Path) {
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
                    DEFINE TEMP-TABLE ttItems NO-UNDO
                        FIELD orderId AS INTEGER
                        // @Array("com.example.Order:ttItems")
                        FIELD children AS HANDLE.
            """.trimIndent())
        }

        writeHandlers(handlers, "svc", "com.example.OrderController", "/svc/orders")
        runGenerateOpenApi(src, handlers, out)

        val swagger = out.resolve("swagger.json").readText()
        assertTrue(swagger.contains("\"orderId\""), "outer orderId should still be present. Got:\n$swagger")
    }

    @Test
    fun `HANDLE field without annotation stays as generic handle`(@TempDir tmp: Path) {
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
                    DEFINE TEMP-TABLE ttItems NO-UNDO
                        FIELD orderId AS INTEGER
                        FIELD rawHandle AS HANDLE.
            """.trimIndent())
        }

        writeHandlers(handlers, "svc", "com.example.OrderController", "/svc/orders")
        runGenerateOpenApi(src, handlers, out)

        val swagger = out.resolve("swagger.json").readText()
        assertTrue(swagger.contains("\"orderId\""), "orderId should be present. Got:\n$swagger")
    }
```

- [ ] **Step 2: Run the 4 new tests to verify they fail**

Run: `./gradlew test --tests "com.pyoif.tatara.GenerateOpenApiTaskTest.emits typed nested array schema from inline temp-table fields" --tests "com.pyoif.tatara.GenerateOpenApiTaskTest.nested @Array falls back to generic when target class missing" --tests "com.pyoif.tatara.GenerateOpenApiTaskTest.nested temp-table cycle emits generic schema and does not infinite loop" --tests "com.pyoif.tatara.GenerateOpenApiTaskTest.HANDLE field without annotation stays as generic handle"`
Expected: the typed-nested test FAILS (nested fields not emitted); the cycle test either hangs/OOMs or FAILS (no recursion yet); the missing-target and unannotated-handle tests PASS (current behavior is correct for these).

- [ ] **Step 3: Update `buildTempTableObjectSchema` to recursively resolve nested temp-tables**

In `src/main/kotlin/com/pyoif/tatara/GenerateOpenApiTask.kt`, replace the helper (lines 227-240) with:

```kotlin
    private fun buildTempTableObjectSchema(
        fields: DtoParser.DtoInfo,
        pkgRoot: File,
        visited: MutableSet<String> = mutableSetOf()
    ): JsonObject {
        val innerProps = JsonObject()
        fields.properties.forEach { p ->
            if (p.isTempTable) {
                val srcClass = p.tempTableClass ?: return@forEach  // unreachable; parser always sets
                val bufName = p.tempTableName ?: ("tt" + p.name.replaceFirstChar { it.uppercase() })
                val key = "$srcClass::$bufName"
                if (key in visited) {
                    logger.warn("Cycle detected for nested temp-table '$key' (prop '${p.name}'); emitting generic schema.")
                    innerProps.add(p.name, JsonObject().apply {
                        addProperty("type", "object")
                        addProperty("additionalProperties", true)
                    })
                } else {
                    val nestedTt = DtoParser.parseInlineTempTableByName(srcClass, pkgRoot, bufName)
                    if (nestedTt == null) {
                        logger.warn("Nested temp-table '$bufName' not found in class '$srcClass' (prop '${p.name}'); emitting generic schema.")
                        innerProps.add(p.name, JsonObject().apply {
                            addProperty("type", "object")
                            addProperty("additionalProperties", true)
                        })
                    } else {
                        visited.add(key)
                        val typedObj = buildTempTableObjectSchema(nestedTt.fields, pkgRoot, visited)
                        visited.remove(key)
                        when (p.tempTableKind) {
                            DtoParser.TempTableKind.ARRAY -> innerProps.add(p.name, JsonObject().apply {
                                addProperty("type", "array")
                                add("items", typedObj)
                            })
                            DtoParser.TempTableKind.OBJECT -> innerProps.add(p.name, typedObj)
                            DtoParser.TempTableKind.NONE -> { /* unreachable */ }
                        }
                    }
                }
            } else {
                innerProps.add(p.name, mapAblType(p.ablType, if (p.isExtent) "EXTENT" else null, JsonObject()))
            }
        }
        return JsonObject().apply {
            addProperty("type", "object")
            add("properties", innerProps)
        }
    }
```

- [ ] **Step 4: Update the 2 call sites in `addDtoToSchemas` to pass `pkgRoot`**

In `src/main/kotlin/com/pyoif/tatara/GenerateOpenApiTask.kt`, find both `buildTempTableObjectSchema(inlineTt.fields)` calls (in the `ARRAY` and `OBJECT` branches of the `isTempTable` block in `addDtoToSchemas`, around lines 175 and 194). Replace each with:

```kotlin
val typedObj = buildTempTableObjectSchema(inlineTt.fields, pkgRoot)
```

- [ ] **Step 5: Run the 4 new tests to verify they pass**

Run: `./gradlew test --tests "com.pyoif.tatara.GenerateOpenApiTaskTest.emits typed nested array schema from inline temp-table fields" --tests "com.pyoif.tatara.GenerateOpenApiTaskTest.nested @Array falls back to generic when target class missing" --tests "com.pyoif.tatara.GenerateOpenApiTaskTest.nested temp-table cycle emits generic schema and does not infinite loop" --tests "com.pyoif.tatara.GenerateOpenApiTaskTest.HANDLE field without annotation stays as generic handle"`
Expected: all 4 PASS.

- [ ] **Step 6: Run the full test suite to confirm no regressions**

Run: `./gradlew test`
Expected: all tests PASS (existing + 6 from Task 1 + 5 from Task 2 + 5 from Task 3 + 4 from Task 4 = 20 new tests added; nothing regresses).

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/pyoif/tatara/GenerateOpenApiTask.kt
git commit -m "feat(openapi): recursively resolve nested temp-tables in field schemas"
```
