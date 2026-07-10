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

---

## File Structure

| File | Change |
|------|--------|
| `src/main/kotlin/com/pyoif/tatara/DtoParser.kt` | Add 2 fields to `DtoProperty`. Extend `annotationRegex`. Add `parseTempTableParam` helper. Wire new fields through the annotation handler. |
| `src/main/kotlin/com/pyoif/tatara/GenerateOpenApiTask.kt` | In `addDtoToSchemas`, swap hardcoded `dtoClass` and `tt<PropName>` for the new prop fields. Add `logger.warn` on miss with explicit class. |
| `src/test/kotlin/com/pyoif/tatara/DtoParserTest.kt` | 6 new tests for parameter parsing. |
| `src/test/kotlin/com/pyoif/tatara/GenerateOpenApiTaskTest.kt` | 5 new tests for cross-class / explicit-buffer OpenAPI emission. |

`GenerateRouteTask.kt`, `GenerateOpenApiTaskTest.kt`'s existing tests, and all other files are untouched.

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
