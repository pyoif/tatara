# Temp-Table Name Binding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bind a HANDLE prop with `@Array`/`@Object` to the inline `DEFINE TEMP-TABLE` whose name follows the convention `tt<PropNamePascalCase>`. Emit a typed OpenAPI schema using that TT's fields. If no matching TT is found, fall back to the generic schema.

**Architecture:** Extend `DtoParser` with `parseInlineTempTableByName` and a private `parseAllInlineTempTables` helper. Refactor existing `parseInlineTempTable` to use the new helper. In `GenerateOpenApiTask.addDtoToSchemas`, look up the temp-table by the prop's derived name; fall back to generic schema on miss.

**Tech Stack:** Kotlin (Gradle plugin code), Gson (JSON construction), JUnit 5 (Kotlin unit tests), Gradle `kotlin-dsl`.

## Global Constraints

- Branch: `feat/swagger-typed-dto`.
- Naming convention: HANDLE prop `items` → `ttItems` (PascalCase + `tt` prefix).
- No name match → fall back to generic schema (do NOT silently use the first TT in the class).
- Out of scope: explicit annotation override, multi-TT disambiguation, shim changes.

---

## File Structure

| File | Responsibility |
| --- | --- |
| `src/main/kotlin/com/pyoif/tatara/DtoParser.kt` (modify) | New `parseInlineTempTableByName` + `parseAllInlineTempTables`. Refactor `parseInlineTempTable`. |
| `src/main/kotlin/com/pyoif/tatara/GenerateOpenApiTask.kt` (modify) | Name-based lookup in `addDtoToSchemas`. |
| `src/test/kotlin/com/pyoif/tatara/DtoParserTest.kt` (modify) | `parseInlineTempTableByName` tests. |
| `src/test/kotlin/com/pyoif/tatara/GenerateOpenApiTaskTest.kt` (modify) | Name-binding schema tests. |

---

## Task 1: Extend DtoParser with named lookup

**Files:**
- Modify: `src/main/kotlin/com/pyoif/tatara/DtoParser.kt`
- Modify: `src/test/kotlin/com/pyoif/tatara/DtoParserTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces:
  - `parseInlineTempTableByName(dtoClassName, srcRoot, bufferName): InlineTempTable?` — finds the `DEFINE TEMP-TABLE <bufferName>` block in the class file; returns its parsed `InlineTempTable` or `null`.
  - `parseAllInlineTempTables(dtoClassName, srcRoot): List<InlineTempTable>` (private) — returns all `DEFINE TEMP-TABLE` blocks in the class.
  - `parseInlineTempTable` refactored to call `parseAllInlineTempTables(...).firstOrNull()`.

- [ ] **Step 1: Add failing tests**

Append to `src/test/kotlin/com/pyoif/tatara/DtoParserTest.kt`:

```kotlin
    @Test
    fun `parseInlineTempTableByName returns matching temp-table`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/Order.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.Order:
                    DEFINE TEMP-TABLE ttItems FIELD orderId AS INTEGER.
                    DEFINE TEMP-TABLE ttOther FIELD x AS INTEGER.
            """.trimIndent())
        }
        val tt = DtoParser.parseInlineTempTableByName("com.example.Order", src, "ttItems")
        assertNotNull(tt)
        assertEquals("ttItems", tt!!.bufferName)
        assertEquals("orderId", tt.fields.properties[0].name)
    }

    @Test
    fun `parseInlineTempTableByName returns null when name not found`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/Order.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.Order:
                    DEFINE TEMP-TABLE ttItems FIELD orderId AS INTEGER.
            """.trimIndent())
        }
        val tt = DtoParser.parseInlineTempTableByName("com.example.Order", src, "ttNotPresent")
        assertNull(tt)
    }

    @Test
    fun `parseInlineTempTableByName returns null when no temp-table`(@TempDir tmp: Path) {
        val src = tmp.toFile()
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    DEFINE PUBLIC PROPERTY id AS INTEGER.
            """.trimIndent())
        }
        val tt = DtoParser.parseInlineTempTableByName("com.example.User", src, "ttAnything")
        assertNull(tt)
    }
```

- [ ] **Step 2: Run, expect fail**

```bash
./gradlew test --tests com.pyoif.tatara.DtoParserTest
```

Expected: 3 new tests fail (unresolved `parseInlineTempTableByName`).

- [ ] **Step 3: Add `parseInlineTempTableByName` + refactor `parseInlineTempTable`**

In `src/main/kotlin/com/pyoif/tatara/DtoParser.kt`:

1. Refactor `parseInlineTempTable` to use a new private helper:

```kotlin
fun parseInlineTempTable(dtoClassName: String, srcRoot: File): InlineTempTable? =
    parseAllInlineTempTables(dtoClassName, srcRoot).firstOrNull()

fun parseInlineTempTableByName(
    dtoClassName: String,
    srcRoot: File,
    bufferName: String
): InlineTempTable? = parseAllInlineTempTables(dtoClassName, srcRoot)
    .firstOrNull { it.bufferName == bufferName }

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
        results.add(InlineTempTable(bufferName, DtoInfo(properties)))
    }
    return results
}
```

2. Verify the existing `parseInlineTempTable` callers in `GenerateOpenApiTask` still work (returns first TT).

- [ ] **Step 4: Run, expect pass**

```bash
./gradlew test --tests com.pyoif.tatara.DtoParserTest
```

Expected: all 17 tests pass (14 existing + 3 new).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/pyoif/tatara/DtoParser.kt src/test/kotlin/com/pyoif/tatara/DtoParserTest.kt
git commit -m "feat(dto): named lookup for inline TEMP-TABLE"
```

---

## Task 2: Name-based binding in OpenAPI emission

**Files:**
- Modify: `src/main/kotlin/com/pyoif/tatara/GenerateOpenApiTask.kt`
- Modify: `src/test/kotlin/com/pyoif/tatara/GenerateOpenApiTaskTest.kt`

**Interfaces:**
- Consumes: `DtoParser.parseInlineTempTableByName` (new in Task 1).
- Produces: in `addDtoToSchemas`, for `isTempTable` props without `nested`:
  - Compute `expectedTtName = "tt" + p.name.replaceFirstChar { it.uppercase() }`.
  - Call `DtoParser.parseInlineTempTableByName(dtoClass, pkgRoot, expectedTtName)`.
  - If null, fall back to generic schema (NOT to first-TT fallback — strict name matching).
  - If non-null, emit typed schema as before.
- All other cases unchanged.

- [ ] **Step 1: Add failing tests**

Append to `src/test/kotlin/com/pyoif/tatara/GenerateOpenApiTaskTest.kt`:

```kotlin
    @Test
    fun `HANDLE prop maps to tt<PropName> temp-table by name`(@TempDir tmp: Path) {
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
                    DEFINE TEMP-TABLE ttData FIELD total AS DECIMAL.
                    // @Array
                    DEFINE PUBLIC PROPERTY data AS HANDLE.
            """.trimIndent())
        }

        writeHandlers(handlers, "svc", "com.example.OrderController", "/svc/orders")
        runGenerateOpenApi(src, handlers, out)

        val swagger = out.resolve("swagger.json").readText()
        assertTrue(swagger.contains("\"total\""), "should expose total field from ttData. Got:\n$swagger")
        assertTrue(swagger.contains("\"type\": \"number\""),
            "total should be typed as number. Got:\n$swagger")
        assertFalse(swagger.contains("\"additionalProperties\": true"),
            "should not fall back to generic. Got:\n$swagger")
    }

    @Test
    fun `HANDLE prop with mismatched temp-table name falls back to generic`(@TempDir tmp: Path) {
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
                    DEFINE TEMP-TABLE ttFoo FIELD x AS INTEGER.
                    // @Array
                    DEFINE PUBLIC PROPERTY items AS HANDLE.
            """.trimIndent())
        }

        writeHandlers(handlers, "svc", "com.example.OrderController", "/svc/orders")
        runGenerateOpenApi(src, handlers, out)

        val swagger = out.resolve("swagger.json").readText()
        assertTrue(swagger.contains("\"additionalProperties\": true"),
            "should fall back to generic when no name match. Got:\n$swagger")
        assertFalse(swagger.contains("\"x\""),
            "should not leak unrelated TT fields. Got:\n$swagger")
    }
```

- [ ] **Step 2: Run, expect fail**

```bash
./gradlew test --tests com.pyoif.tatara.GenerateOpenApiTaskTest
```

Expected: 2 new tests fail. The "mismatched name" test may already pass if the current code doesn't use the TT at all. The "name match" test may also pass if there's a single TT and the previous single-TT fallback picks it up. Run and see.

- [ ] **Step 3: Update `addDtoToSchemas` to use name-based lookup**

In `src/main/kotlin/com/pyoif/tatara/GenerateOpenApiTask.kt`, find the existing `isTempTable` branch in `addDtoToSchemas`. Change the inline TT lookup:

Replace:
```kotlin
val inlineTt = DtoParser.parseInlineTempTable(dtoClass, pkgRoot)
```

With:
```kotlin
val expectedTtName = "tt" + p.name.replaceFirstChar { it.uppercase() }
val inlineTt = DtoParser.parseInlineTempTableByName(dtoClass, pkgRoot, expectedTtName)
```

- [ ] **Step 4: Run, expect pass**

```bash
./gradlew test --tests com.pyoif.tatara.GenerateOpenApiTaskTest
```

Expected: all 9 OpenAPI tests pass (7 existing + 2 new).

- [ ] **Step 5: Run full suite**

```bash
./gradlew test
```

Expected: all tests pass (32 total).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/pyoif/tatara/GenerateOpenApiTask.kt src/test/kotlin/com/pyoif/tatara/GenerateOpenApiTaskTest.kt
git commit -m "feat(openapi): bind HANDLE prop to tt<PropName> temp-table by convention"
```

---

## Task 3: End-to-end manual verification (in PASOE)

**Files:** none modified.

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

For a DTO with:
```progress
CLASS com.example.Order:
    DEFINE TEMP-TABLE ttData FIELD total AS DECIMAL.
    // @Array
    DEFINE PUBLIC PROPERTY data AS HANDLE.
```

Confirm: the `data` prop's schema is a typed array with `total` (number) — not the generic schema.

For a mismatched name (e.g. `data` prop + `ttOther` TT), confirm: generic schema with `additionalProperties: true`.

- [ ] **Step 4: No commit**

This task produces no code changes. Skip the commit step.

---

## Self-Review Notes

- Spec §1 DtoParser extension → Task 1.
- Spec §2 OpenAPI emission → Task 2.
- Spec §3 examples — covered by tests.
- Spec §4 error handling — strict name match; no silent fallback to first TT.
- Spec §5 testing → Tasks 1, 2, 3.
- No placeholders. All test code shown. All Kotlin edits specified.
- Type consistency: `parseInlineTempTableByName` introduced in Task 1, consumed in Task 2.
