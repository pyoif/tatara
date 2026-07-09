# OpenAPI Temp-Table + Error Schema Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Update the OpenAPI generator to (1) recognize temp-table props in response DTOs and emit valid array/object schemas, (2) align the `ErrorResponse` schema with the runtime payload shape, (3) use a uniform `ErrorResponse` reference for all error responses.

**Architecture:** Three localized changes in `GenerateOpenApiTask.kt`: extend `addDtoToSchemas` with a temp-table branch, fix the hardcoded `ErrorResponse` schema, and replace the per-class `$ref` in custom error responses with the same `ErrorResponse` `$ref`. New unit tests for each.

**Tech Stack:** Kotlin (Gradle plugin code), Gson (JSON construction), JUnit 5 (Kotlin unit tests), Gradle `kotlin-dsl`.

## Global Constraints

- Branch: `feat/swagger-typed-dto`.
- Temp-table field-level schema is not modeled (we have no visibility into TT class files at gen time).
- All error responses use the same `ErrorResponse` shape — no per-error-class schemas.
- `ErrorResponse` schema matches runtime: `{"error": "string"}` only.

---

## File Structure

| File | Responsibility |
| --- | --- |
| `src/main/kotlin/com/pyoif/tatara/GenerateOpenApiTask.kt` (modify) | Three localized changes. |
| `src/test/kotlin/com/pyoif/tatara/GenerateOpenApiTaskTest.kt` (new) | Unit tests for the three changes. |

---

## Task 1: Emit temp-table prop schemas in `addDtoToSchemas`

**Files:**
- Modify: `src/main/kotlin/com/pyoif/tatara/GenerateOpenApiTask.kt`
- Modify: `src/test/kotlin/com/pyoif/tatara/GenerateOpenApiTaskTest.kt` (new file)

**Interfaces:**
- Consumes: `DtoProperty.isTempTable`, `DtoProperty.tempTableKind` (existing).
- Produces: in the per-property loop in `addDtoToSchemas`, when `p.isTempTable`:
  - `ARRAY` kind → `{"type":"array","items":{"type":"object","additionalProperties":true},"description":"ABL temp-table; field-level schema not modeled"}`.
  - `OBJECT` kind → `{"type":"object","additionalProperties":true,"description":"ABL temp-table (single-row); field-level schema not modeled"}`.
- The non-temp-table branch is unchanged.

- [ ] **Step 1: Create the test file with failing tests**

Create `src/test/kotlin/com/pyoif/tatara/GenerateOpenApiTaskTest.kt`:

```kotlin
package com.pyoif.tatara

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class GenerateOpenApiTaskTest {

    private fun runGenerateOpenApi(
        src: File,
        handlers: File,
        out: File
    ) {
        val project = org.gradle.testfixtures.ProjectBuilder.builder().build()
        val task = project.tasks.register("testOpenApi", GenerateOpenApiTask::class.java).get()

        fun dirProp(f: File) = project.objects.directoryProperty().apply { set(f) }
        fun fileProp(f: File) = project.objects.fileProperty().apply { set(f) }
        fun strProp(s: String) = project.objects.property(String::class.java).apply { set(s) }

        task.generatedDir = dirProp(src)
        task.packagedDir = dirProp(src)
        task.handlersDir = dirProp(handlers)
        task.swaggerFile = fileProp(out.resolve("swagger.json"))
        task.apiTitle = strProp("Test API")
        task.apiVersion = strProp("1.0.0")
        task.apiServerUrl = strProp("http://localhost")

        out.mkdirs()
        task.generate()
    }

    private fun writeHandlers(dir: File, serviceName: String, className: String, uri: String) {
        dir.mkdirs()
        File(dir, "$serviceName.handlers").writeText("""
            {
                "version": "2.0",
                "serviceName": "$serviceName",
                "handlers": [
                    { "uri": "$uri", "class": "$className", "enabled": true }
                ]
            }
        """.trimIndent())
    }

    @Test
    fun `emits array schema for @Array temp-table prop`(@TempDir tmp: Path) {
        val src = tmp.resolve("src").toFile()
        val handlers = tmp.resolve("handlers").toFile()
        val out = tmp.resolve("out").toFile()

        // Controller class — DtoParser just needs the class file
        File(src, "com/example/OrderController.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.OrderController:
                    METHOD PUBLIC com.example.Order GetOrder():
                        RETURN NEW com.example.Order().
                    END METHOD.
            """.trimIndent())
        }
        // DTO with @Array temp-table
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
        assertTrue(swagger.contains("\"type\": \"array\""), "should emit array type. Got:\n$swagger")
        assertTrue(swagger.contains("\"additionalProperties\": true"), "should emit additionalProperties. Got:\n$swagger")
        assertTrue(swagger.contains("ABL temp-table"), "should include temp-table description. Got:\n$swagger")
    }

    @Test
    fun `emits object schema for @Object temp-table prop`(@TempDir tmp: Path) {
        val src = tmp.resolve("src").toFile()
        val handlers = tmp.resolve("handlers").toFile()
        val out = tmp.resolve("out").toFile()

        File(src, "com/example/OrderController.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.OrderController:
                    METHOD PUBLIC com.example.Order GetOrder():
                        RETURN NEW com.example.Order().
                    END METHOD.
            """.trimIndent())
        }
        File(src, "com/example/Order.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.Order:
                    // @Object
                    DEFINE PUBLIC PROPERTY summary AS HANDLE.
            """.trimIndent())
        }

        writeHandlers(handlers, "svc", "com.example.OrderController", "/svc/orders")
        runGenerateOpenApi(src, handlers, out)

        val swagger = out.resolve("swagger.json").readText()
        // @Object should emit a plain object schema (NOT an array)
        assertTrue(swagger.contains("\"additionalProperties\": true"), "should emit additionalProperties. Got:\n$swagger")
        assertTrue(swagger.contains("ABL temp-table (single-row)"), "should include single-row description. Got:\n$swagger")
    }
}
```

- [ ] **Step 2: Run the new tests to verify they fail**

```bash
./gradlew test --tests com.pyoif.tatara.GenerateOpenApiTaskTest
```

Expected: both tests fail. `addDtoToSchemas` doesn't handle temp-table props.

- [ ] **Step 3: Add temp-table branch to `addDtoToSchemas`**

In `src/main/kotlin/com/pyoif/tatara/GenerateOpenApiTask.kt`, find the `addDtoToSchemas` method. Change the per-property loop from:

```kotlin
dto.properties.forEach { p ->
    innerProps.add(p.name, mapAblType(p.ablType, if (p.isExtent) "EXTENT" else null, schemas))
    if (p.isRequired) requiredArr.add(p.name)
}
```

to:

```kotlin
dto.properties.forEach { p ->
    if (p.isTempTable) {
        val desc = when (p.tempTableKind) {
            DtoParser.TempTableKind.ARRAY -> "ABL temp-table; field-level schema not modeled"
            DtoParser.TempTableKind.OBJECT -> "ABL temp-table (single-row); field-level schema not modeled"
            DtoParser.TempTableKind.NONE -> null
        }
        val propSchema = when (p.tempTableKind) {
            DtoParser.TempTableKind.ARRAY -> JsonObject().apply {
                addProperty("type", "array")
                add("items", JsonObject().apply {
                    addProperty("type", "object")
                    addProperty("additionalProperties", true)
                })
                if (desc != null) addProperty("description", desc)
            }
            DtoParser.TempTableKind.OBJECT -> JsonObject().apply {
                addProperty("type", "object")
                addProperty("additionalProperties", true)
                if (desc != null) addProperty("description", desc)
            }
            DtoParser.TempTableKind.NONE -> JsonObject().apply { addProperty("type", "null") }
        }
        innerProps.add(p.name, propSchema)
    } else {
        innerProps.add(p.name, mapAblType(p.ablType, if (p.isExtent) "EXTENT" else null, schemas))
    }
    if (p.isRequired) requiredArr.add(p.name)
}
```

- [ ] **Step 4: Run the new tests to verify they pass**

```bash
./gradlew test --tests com.pyoif.tatara.GenerateOpenApiTaskTest
```

Expected: both new tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/pyoif/tatara/GenerateOpenApiTask.kt src/test/kotlin/com/pyoif/tatara/GenerateOpenApiTaskTest.kt
git commit -m "feat(openapi): emit array/object schema for temp-table props"
```

---

## Task 2: Fix `ErrorResponse` schema + use it for all error responses

**Files:**
- Modify: `src/main/kotlin/com/pyoif/tatara/GenerateOpenApiTask.kt`
- Modify: `src/test/kotlin/com/pyoif/tatara/GenerateOpenApiTaskTest.kt`

**Interfaces:**
- Consumes: existing code.
- Produces:
  - `ErrorResponse` schema is `{"type":"object","properties":{"error":{"type":"string"}},"required":["error"]}`.
  - Custom error responses in `buildPathFromRoute` reference `#/components/schemas/ErrorResponse` instead of the custom DTO class name.

- [ ] **Step 1: Add failing tests**

Append to `src/test/kotlin/com/pyoif/tatara/GenerateOpenApiTaskTest.kt` (inside the class):

```kotlin
    @Test
    fun `ErrorResponse schema only contains error key`(@TempDir tmp: Path) {
        val src = tmp.resolve("src").toFile()
        val handlers = tmp.resolve("handlers").toFile()
        val out = tmp.resolve("out").toFile()

        File(src, "com/example/UserController.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.UserController:
                    METHOD PUBLIC com.example.User GetUser():
                        RETURN NEW com.example.User().
                    END METHOD.
            """.trimIndent())
        }
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    DEFINE PUBLIC PROPERTY id AS INTEGER.
            """.trimIndent())
        }

        writeHandlers(handlers, "svc", "com.example.UserController", "/svc/users")
        runGenerateOpenApi(src, handlers, out)

        val swagger = out.resolve("swagger.json").readText()
        // ErrorResponse schema should NOT contain a "message" key
        assertFalse(swagger.contains("\"message\": \"string\""), "ErrorResponse should not have message key. Got:\n$swagger")
        assertTrue(swagger.contains("\"ErrorResponse\""), "should still include ErrorResponse. Got:\n$swagger")
    }

    @Test
    fun `custom error response uses ErrorResponse schema not custom class`(@TempDir tmp: Path) {
        val src = tmp.resolve("src").toFile()
        val handlers = tmp.resolve("handlers").toFile()
        val out = tmp.resolve("out").toFile()

        File(src, "com/example/NotFoundError.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.NotFoundError:
                    DEFINE PUBLIC PROPERTY resourceId AS INTEGER.
            """.trimIndent())
        }
        File(src, "com/example/UserController.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.UserController:
                    // @Response(404, com.example.NotFoundError)
                    METHOD PUBLIC com.example.User GetUser():
                        RETURN NEW com.example.User().
                    END METHOD.
            """.trimIndent())
        }
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    DEFINE PUBLIC PROPERTY id AS INTEGER.
            """.trimIndent())
        }

        writeHandlers(handlers, "svc", "com.example.UserController", "/svc/users")
        runGenerateOpenApi(src, handlers, out)

        val swagger = out.resolve("swagger.json").readText()
        // 404 response should $ref ErrorResponse, not NotFoundError
        assertTrue(swagger.contains("\"404\""), "should have 404 response. Got:\n$swagger")
        assertFalse(swagger.contains("#/components/schemas/NotFoundError"),
            "should not reference custom DTO class for error. Got:\n$swagger")
        assertTrue(swagger.contains("#/components/schemas/ErrorResponse"),
            "should reference ErrorResponse for 404. Got:\n$swagger")
    }
```

- [ ] **Step 2: Run the new tests to verify they fail**

```bash
./gradlew test --tests com.pyoif.tatara.GenerateOpenApiTaskTest
```

Expected: 2 new tests fail (the existing 2 may pass).

- [ ] **Step 3: Fix `ErrorResponse` schema definition**

In `src/main/kotlin/com/pyoif/tatara/GenerateOpenApiTask.kt`, find the `ErrorResponse` schema block (around line 92). Replace:

```kotlin
schemas.add("ErrorResponse", JsonObject().apply {
    addProperty("type", "object")
    add("properties", JsonObject().apply {
        add("error", JsonObject().apply { addProperty("type", "string") })
        add("message", JsonObject().apply { addProperty("type", "string") })
    })
})
```

with:

```kotlin
schemas.add("ErrorResponse", JsonObject().apply {
    addProperty("type", "object")
    add("properties", JsonObject().apply {
        add("error", JsonObject().apply { addProperty("type", "string") })
    })
    add("required", JsonArray().apply { add("error") })
})
```

- [ ] **Step 4: Update custom error response to use `ErrorResponse` schema**

In `buildPathFromRoute`, find the `route.errorResponses.forEach` block. Replace:

```kotlin
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
```

with:

```kotlin
route.errorResponses.forEach { (code, _) ->
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
```

- [ ] **Step 5: Run the new tests to verify they pass**

```bash
./gradlew test --tests com.pyoif.tatara.GenerateOpenApiTaskTest
```

Expected: all 4 tests in `GenerateOpenApiTaskTest` pass.

- [ ] **Step 6: Run the full test suite**

```bash
./gradlew test
```

Expected: all tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/pyoif/tatara/GenerateOpenApiTask.kt src/test/kotlin/com/pyoif/tatara/GenerateOpenApiTaskTest.kt
git commit -m "fix(openapi): align ErrorResponse schema and use it for all errors"
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

Confirm:
- `ErrorResponse` schema contains only `error: string` (no `message` key).
- For any path with a custom error response (e.g. `@Response(404, SomeError)`), the 404 response references `#/components/schemas/ErrorResponse`, not `#/components/schemas/SomeError`.
- For any DTO with a `@Array` HANDLE prop, the prop schema is `{type: array, items: {type: object, additionalProperties: true}, description: ...}`.
- For any DTO with a `@Object` HANDLE prop, the prop schema is `{type: object, additionalProperties: true, description: ...}`.

- [ ] **Step 4: No commit**

This task produces no code changes. Skip the commit step.

---

## Self-Review Notes

- Spec §1 `addDtoToSchemas` temp-table branch → Task 1.
- Spec §2 `ErrorResponse` schema fix → Task 2.
- Spec §3 custom error `$ref` fix → Task 2.
- Spec §4 testing → Tasks 1, 2, 3.
- No placeholders. All test code shown. All Kotlin edits specified. Signatures match.
- Type consistency: uses existing `DtoProperty.isTempTable`/`tempTableKind` and `DtoParser.TempTableKind` enum (no new types).
