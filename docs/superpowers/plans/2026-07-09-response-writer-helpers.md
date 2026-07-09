# ResponseWriter JSON/Error Helpers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Consolidate the shim's repetitive 5-line `MemoryOutputStream`/`MEMPTR` pattern into two `Tatara.Api.ResponseWriter` helpers — one for successful JSON, one for error responses. Drop the old `ResponseWriter:Write(poRequest, poResponse)` method entirely.

**Architecture:** Rewrite `ResponseWriter.cls` with two static methods (`WriteJsonObject`, `WriteError`) that own the writer/stream/cleanup lifecycle. Update `GenerateRouteTask.kt` to emit single helper calls per response path; remove the `oWriter`/`oMemStream`/`mPayload` variable defs and the MEMPTR dance from the shim.

**Tech Stack:** Kotlin (Gradle plugin code), ABL/OpenEdge PASOE (runtime), JUnit 5 (Kotlin unit tests), Gradle `kotlin-dsl`.

## Global Constraints

- Branch: `feat/swagger-typed-dto`.
- Helpers own writer/stream/cleanup; shim never touches them.
- Drop backward compat: no `Write(poRequest, poResponse)` method remains.
- Status code is set inside the helper, not in the shim.
- Content type is always `application/json` (success path).
- Out of scope: streaming, custom content types.

---

## File Structure

| File | Responsibility |
| --- | --- |
| `src/main/resources/Tatara/Api/ResponseWriter.cls` (rewrite) | Two static helpers. |
| `src/main/kotlin/com/pyoif/tatara/GenerateRouteTask.kt` (modify) | Shim emission uses helpers; remove MEMPTR dance. |
| `src/test/kotlin/com/pyoif/tatara/GenerateRouteTaskEmitTest.kt` (modify) | New assertions + negative assertions. |

---

## Task 1: Rewrite `ResponseWriter.cls`

**Files:**
- Modify: `src/main/resources/Tatara/Api/ResponseWriter.cls`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - Drop: existing `Write(INPUT poRequest AS IWebRequest, INPUT poResponse AS WebResponse)`.
  - Add: `WriteJsonObject(INPUT poResponse AS OpenEdge.Web.WebResponse, INPUT poJson AS Progress.Json.ObjectModel.JsonObject)`. Sets status 200, content type `application/json`, serializes `poJson` to MEMPTR, writes, closes, cleans up.
  - Add: `WriteError(INPUT poResponse AS OpenEdge.Web.WebResponse, INPUT piStatusCode AS INTEGER, INPUT pcErrorMessage AS CHARACTER)`. Sets `poResponse:StatusCode = piStatusCode`, builds `{"error": pcErrorMessage}` `JsonObject`, serializes, writes, closes, cleans up.

- [ ] **Step 1: Rewrite `ResponseWriter.cls`**

Replace the file contents with:

```progress
USING Progress.Lang.*.
USING Progress.Json.ObjectModel.*.
USING OpenEdge.Web.WebResponseWriter.

BLOCK-LEVEL ON ERROR UNDO, THROW.

CLASS Tatara.Api.ResponseWriter:

    METHOD PUBLIC STATIC VOID WriteJsonObject(
        INPUT poResponse AS OpenEdge.Web.WebResponse,
        INPUT poJson     AS Progress.Json.ObjectModel.JsonObject):

        DEFINE VARIABLE oWriter    AS OpenEdge.Web.WebResponseWriter NO-UNDO.
        DEFINE VARIABLE oMemStream AS MemoryOutputStream              NO-UNDO.
        DEFINE VARIABLE mPayload   AS MEMPTR                          NO-UNDO.

        poResponse:StatusCode    = 200.
        poResponse:ContentType   = "application/json".

        oWriter    = NEW OpenEdge.Web.WebResponseWriter(poResponse).
        oMemStream = NEW MemoryOutputStream().
        oWriter:Open().
        poJson:Write(oMemStream).
        mPayload = oMemStream:Data.
        oWriter:Write(mPayload).
        oWriter:Close().
        SET-SIZE(mPayload) = 0.
        DELETE OBJECT oMemStream.
    END METHOD.

    METHOD PUBLIC STATIC VOID WriteError(
        INPUT poResponse     AS OpenEdge.Web.WebResponse,
        INPUT piStatusCode   AS INTEGER,
        INPUT pcErrorMessage AS CHARACTER):

        DEFINE VARIABLE oWriter    AS OpenEdge.Web.WebResponseWriter NO-UNDO.
        DEFINE VARIABLE oMemStream AS MemoryOutputStream              NO-UNDO.
        DEFINE VARIABLE mPayload   AS MEMPTR                          NO-UNDO.
        DEFINE VARIABLE oJson      AS JsonObject                       NO-UNDO.

        poResponse:StatusCode = piStatusCode.

        oJson = NEW JsonObject().
        oJson:Add("error", pcErrorMessage).

        oWriter    = NEW OpenEdge.Web.WebResponseWriter(poResponse).
        oMemStream = NEW MemoryOutputStream().
        oWriter:Open().
        oJson:Write(oMemStream).
        mPayload = oMemStream:Data.
        oWriter:Write(mPayload).
        oWriter:Close().
        SET-SIZE(mPayload) = 0.
        DELETE OBJECT oMemStream.
    END METHOD.

END CLASS.
```

- [ ] **Step 2: Build the plugin and confirm the resource is bundled**

```bash
./gradlew build
```

Expected: `BUILD SUCCESSFUL`. Verify the file is bundled:

```bash
ls -la build/resources/main/Tatara/Api/ResponseWriter.cls
```

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/Tatara/Api/ResponseWriter.cls
git commit -m "refactor(runtime): rewrite ResponseWriter with WriteJsonObject/WriteError helpers"
```

---

## Task 2: Update shim emission to use helpers

**Files:**
- Modify: `src/main/kotlin/com/pyoif/tatara/GenerateRouteTask.kt`
- Modify: `src/test/kotlin/com/pyoif/tatara/GenerateRouteTaskEmitTest.kt`

**Interfaces:**
- Consumes: existing `RouteDef` shape, `DtoInfo` from `DtoParser`.
- Produces: generated shim contains, in the success path:
  - `Tatara.Api.ResponseWriter:WriteJsonObject(oResponse, oJson).`
  - No `oWriter:Open()`, no `oMemStream`, no `mPayload`, no `oJson:Write(oMemStream)`, no `oWriter:Write(mPayload)`, no `SET-SIZE(mPayload) = 0`, no `DELETE OBJECT oMemStream`.
- In the error catches: `Tatara.Api.ResponseWriter:WriteError(oResponse, <code>, <msg>).` Shallow no longer sets `oResponse:StatusCode` before calling.
- Variable defs `oWriter AS WebResponseWriter`, `oMemStream AS MemoryOutputStream`, `mPayload AS MEMPTR` are no longer emitted.
- The `RequestContext`-only path (no DTOs) calls `Tatara.Api.ResponseWriter:WriteJsonObject(oResponse, NEW JsonObject()).`

- [ ] **Step 1: Update failing tests**

Replace the contents of `src/test/kotlin/com/pyoif/tatara/GenerateRouteTaskEmitTest.kt` with:

```kotlin
package com.pyoif.tatara

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class GenerateRouteTaskEmitTest {

    @Test
    fun `emits WriteJsonObject call for flat response DTO`(@TempDir tmp: Path) {
        val src = tmp.resolve("src").toFile()
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    DEFINE PUBLIC PROPERTY id   AS INTEGER.
                    DEFINE PUBLIC PROPERTY name AS CHARACTER.
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

        assertTrue(shim.contains("oJson:Add(\"id\", oResult:id)."), "shim should emit direct Add for scalar id")
        assertTrue(shim.contains("oJson:Add(\"name\", oResult:name)."), "shim should emit direct Add for scalar name")
        assertTrue(shim.contains("Tatara.Api.ResponseWriter:WriteJsonObject(oResponse, oJson)."),
            "shim should call WriteJsonObject helper. SHIM:\n$shim")
        // Negative assertions: MEMPTR dance must be gone
        assertFalse(shim.contains("oWriter:Open()"), "shim should not open oWriter manually")
        assertFalse(shim.contains("oMemStream"), "shim should not reference oMemStream")
        assertFalse(shim.contains("mPayload"), "shim should not reference mPayload")
        assertFalse(shim.contains("oJson:Write(oMemStream)"), "shim should not serialize oJson to stream manually")
        assertFalse(shim.contains("oWriter:Write(mPayload)"), "shim should not write MEMPTR manually")
        assertFalse(shim.contains("SET-SIZE(mPayload)"), "shim should not free MEMPTR manually")
        assertFalse(shim.contains("oResult:data"), "shim should NOT chunk-write oResult:data")
    }

    @Test
    fun `emits WriteError calls for error catches`(@TempDir tmp: Path) {
        val src = tmp.resolve("src").toFile()
        File(src, "com/example/NotFoundError.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.NotFoundError INHERITS Progress.Lang.AppError:
                END CLASS.
            """.trimIndent())
        }
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    DEFINE PUBLIC PROPERTY id AS INTEGER.
            """.trimIndent())
        }

        val genDir = tmp.resolve("gen").toFile()
        val task = ShimEmitHelper.createTask()
        val shim = ShimEmitHelper.invokeWriteShim(
            task = task,
            srcDir = genDir,
            genDir = genDir,
            routePath = "svc/users/{id}",
            routeDef = com.pyoif.tatara.GenerateRoutesTask.RouteDef(
                routePath = "svc/users/{id}",
                httpMethod = "GET",
                className = "com.example.UserController",
                ablMethod = "GetUser",
                pathParams = listOf("id"),
                responseDtoClassName = "com.example.User",
                errorResponses = mapOf(404 to "com.example.NotFoundError")
            )
        )

        assertTrue(shim.contains("Tatara.Api.ResponseWriter:WriteError(oResponse, 404, errCustom:GetMessage(1))."),
            "shim should call WriteError for custom error with status 404")
        assertTrue(shim.contains("Tatara.Api.ResponseWriter:WriteError(oResponse, errApi:HttpCode, errApi:GetMessage(1))."),
            "shim should call WriteError for ApiError with HttpCode status")
        assertTrue(shim.contains("Tatara.Api.ResponseWriter:WriteError(oResponse, 500, errApp:GetMessage(1))."),
            "shim should call WriteError for AppError with status 500")
        // Negative: shim should not set StatusCode before the error helper call
        assertFalse(shim.contains("oResponse:StatusCode = errApi:HttpCode."),
            "shim should not set StatusCode for ApiError (helper does it)")
        assertFalse(shim.contains("oResponse:StatusCode = 500."),
            "shim should not set StatusCode for AppError (helper does it)")
    }

    @Test
    fun `emits recursive sub-objects for nested DTO`(@TempDir tmp: Path) {
        val src = tmp.resolve("src").toFile()
        File(src, "com/example/Address.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.Address:
                    DEFINE PUBLIC PROPERTY city AS CHARACTER.
                    DEFINE PUBLIC PROPERTY zip  AS CHARACTER.
            """.trimIndent())
        }
        File(src, "com/example/User.cls").apply {
            parentFile.mkdirs()
            writeText("""
                CLASS com.example.User:
                    DEFINE PUBLIC PROPERTY id   AS INTEGER.
                    DEFINE PUBLIC PROPERTY addr AS com.example.Address.
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

        assertTrue(shim.contains("oSub_addr"), "shim should define sub-object variable for nested DTO")
        assertTrue(shim.contains("oSub_addr:Add(\"city\", oResult:addr:city)."), "shim should inline nested DTO prop with chained accessor")
        assertTrue(shim.contains("oSub_addr:Add(\"zip\", oResult:addr:zip)."), "shim should inline nested DTO prop with chained accessor")
        assertTrue(shim.contains("oJson:Add(\"addr\", oSub_addr)."), "shim should attach sub-object to parent under prop name")
        assertTrue(shim.contains("Tatara.Api.ResponseWriter:WriteJsonObject(oResponse, oJson)."),
            "shim should call WriteJsonObject helper for nested DTO")
    }

    @Test
    fun `emits ReadTempTableAsArray for @Array temp-table prop`(@TempDir tmp: Path) {
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

        assertTrue(shim.contains("oJson:Add(\"items\", Tatara.Api.DtoSerializer:ReadTempTableAsArray(oResult:items))."),
            "shim should call ReadTempTableAsArray for @Array")
        assertTrue(shim.contains("Tatara.Api.ResponseWriter:WriteJsonObject(oResponse, oJson)."),
            "shim should call WriteJsonObject helper for @Array response")
    }

    @Test
    fun `emits ReadTempTableAsObject for @Object temp-table prop`(@TempDir tmp: Path) {
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

        assertTrue(shim.contains("oJson:Add(\"summary\", Tatara.Api.DtoSerializer:ReadTempTableAsObject(oResult:summary))."),
            "shim should call ReadTempTableAsObject for @Object")
    }
}
```

Note: this rewrites the test class. The previous tests' assertions about the MEMPTR dance are replaced with negative assertions that the dance is gone.

- [ ] **Step 2: Run the new tests to verify they fail**

```bash
./gradlew test --tests com.pyoif.tatara.GenerateRouteTaskEmitTest
```

Expected: at least 2 tests fail (the success path one and the error catches one). The 3 DTO-shape tests may still pass because they don't reference the MEMPTR dance.

- [ ] **Step 3: Drop MEMPTR-related defs and replace MEMPTR dance with helper calls in `GenerateRouteTask.kt`**

Edit `src/main/kotlin/com/pyoif/tatara/GenerateRouteTask.kt`.

**3a. Remove `oWriter`/`oMemStream`/`mPayload` defs from preamble.**

In the per-handler preamble block (around line 285–295), the existing line:

```kotlin
methodHandlersBlock.append("\t\tDEFINE VARIABLE oResponse AS OpenEdge.Web.WebResponse NO-UNDO.\r\n")
if (hasResponseDto) {
    methodHandlersBlock.append("\t\tDEFINE VARIABLE oJson AS Progress.Json.ObjectModel.JsonObject NO-UNDO.\r\n")
}
```

stays. The lines below it (the old `oWriter`/`oMemStream`/`mPayload` defs, the `oWriter = NEW`, `oWriter:Open()`, `oMemStream = NEW` lines) must be removed. Use `git diff` to find them or search for `oWriter:Open()` in the file. Remove all of:

```kotlin
methodHandlersBlock.append("\t\tDEFINE VARIABLE oWriter AS OpenEdge.Web.WebResponseWriter NO-UNDO.\r\n")
methodHandlersBlock.append("\t\tDEFINE VARIABLE oMemStream AS MemoryOutputStream NO-UNDO.\r\n")
methodHandlersBlock.append("\t\tDEFINE VARIABLE mPayload AS MEMPTR NO-UNDO.\r\n")
```

and:

```kotlin
methodHandlersBlock.append("\t\toWriter = NEW OpenEdge.Web.WebResponseWriter(oResponse).\r\n")
methodHandlersBlock.append("\t\toWriter:Open().\r\n")
methodHandlersBlock.append("\t\toMemStream = NEW MemoryOutputStream().\r\n")
```

**3b. Replace error catch bodies with `WriteError` calls.**

In each of the 3 error catches (around lines 425–440), replace the 5-line MEMPTR dance with a single call.

Existing shape (for ApiError):
```kotlin
methodHandlersBlock.append("\t\t\tCATCH errApi AS Tatara.Api.ApiError:\r\n")
methodHandlersBlock.append("\t\t\t\toResponse:StatusCode = errApi:HttpCode.\r\n")
methodHandlersBlock.append("\t\t\t\toJson = NEW Progress.Json.ObjectModel.JsonObject().\r\n")
methodHandlersBlock.append("\t\t\t\toJson:Add(\"error\", errApi:GetMessage(1)).\r\n")
methodHandlersBlock.append("\t\t\t\toJson:Write(oMemStream).\r\n")
methodHandlersBlock.append("\t\t\t\tASSIGN mPayload = oMemStream:Data.\r\n")
methodHandlersBlock.append("\t\t\t\toWriter:Write(mPayload).\r\n")
methodHandlersBlock.append("\t\t\t\toWriter:Close().\r\n")
methodHandlersBlock.append("\t\t\t\tRETURN 0.\r\n")
methodHandlersBlock.append("\t\t\tEND.\r\n")
```

Replace with:
```kotlin
methodHandlersBlock.append("\t\t\tCATCH errApi AS Tatara.Api.ApiError:\r\n")
methodHandlersBlock.append("\t\t\t\tTatara.Api.ResponseWriter:WriteError(oResponse, errApi:HttpCode, errApi:GetMessage(1)).\r\n")
methodHandlersBlock.append("\t\t\t\tRETURN 0.\r\n")
methodHandlersBlock.append("\t\t\tEND.\r\n")
```

Apply the same pattern to:
- Custom error catch (around line 422): `WriteError(oResponse, $code, errCustom:GetMessage(1)).` (the `oResponse:StatusCode = $code.` line is removed — helper does it).
- AppError catch (around line 438): `WriteError(oResponse, 500, errApp:GetMessage(1)).`

**3c. Replace success-path MEMPTR dance with `WriteJsonObject` call.**

Find the success-path block (around line 445–455). Existing shape:

```kotlin
methodHandlersBlock.append("\t\toResponse:StatusCode = 200.\r\n")
if (hasResponseDto) {
    methodHandlersBlock.append("\t\toResponse:ContentType = \"application/json\".\r\n")
    methodHandlersBlock.append("\t\toJson = NEW Progress.Json.ObjectModel.JsonObject().\r\n")
    val responseInfo = DtoParser.parse(def.responseDtoClassName!!, srcRoot)
    emitResponseJson(methodHandlersBlock, "oResult", responseInfo)
    methodHandlersBlock.append("\t\toJson:Write(oMemStream)\r\n")
}
methodHandlersBlock.append("\t\tASSIGN mPayload = oMemStream:Data.\r\n")
methodHandlersBlock.append("\t\toWriter:Write(mPayload).\r\n")
methodHandlersBlock.append("\t\toWriter:Close().\r\n")
methodHandlersBlock.append("\t\tSET-SIZE(mPayload) = 0.\r\n")
methodHandlersBlock.append("\t\tDELETE OBJECT oMemStream.\r\n")
methodHandlersBlock.append("\t\tRETURN 0.\r\n")
```

Replace with:
```kotlin
if (hasResponseDto) {
    methodHandlersBlock.append("\t\toJson = NEW Progress.Json.ObjectModel.JsonObject().\r\n")
    val responseInfo = DtoParser.parse(def.responseDtoClassName!!, srcRoot)
    emitResponseJson(methodHandlersBlock, "oResult", responseInfo)
    methodHandlersBlock.append("\t\tTatara.Api.ResponseWriter:WriteJsonObject(oResponse, oJson).\r\n")
} else {
    methodHandlersBlock.append("\t\tTatara.Api.ResponseWriter:WriteJsonObject(oResponse, NEW Progress.Json.ObjectModel.JsonObject()).\r\n")
}
methodHandlersBlock.append("\t\tRETURN 0.\r\n")
```

**3d. Update the `RequestContext`-only path.**

Find line ~416: `methodHandlersBlock.append("\t\t\tTatara.Api.ResponseWriter:Write(poRequest, oResponse).\r\n")`. Replace with:

```kotlin
methodHandlersBlock.append("\t\t\tTatara.Api.ResponseWriter:WriteJsonObject(oResponse, NEW Progress.Json.ObjectModel.JsonObject()).\r\n")
```

(Empty `JsonObject` body; the RequestContext path has no JSON content of its own.)

- [ ] **Step 4: Run the new tests to verify they pass**

```bash
./gradlew test --tests com.pyoif.tatara.GenerateRouteTaskEmitTest
```

Expected: all 5 tests pass.

- [ ] **Step 5: Run the full test suite**

```bash
./gradlew test
```

Expected: all tests pass (18 total: 1 smoke + 8 parser + 4 emit + 1 error + 1 nested + 1 array + 1 object = 17; exact count depends on suite).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/pyoif/tatara/GenerateRouteTask.kt src/test/kotlin/com/pyoif/tatara/GenerateRouteTaskEmitTest.kt
git commit -m "refactor(shim): emit single ResponseWriter helper call per response"
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

- [ ] **Step 2: In a downstream project, regenerate shims and inspect**

```bash
./gradlew tataraGenerateRoutes
```

Inspect a generated shim for a route with a response DTO. Confirm:
- The success path contains exactly one call: `Tatara.Api.ResponseWriter:WriteJsonObject(oResponse, oJson).`
- No `oWriter`, `oMemStream`, `mPayload` variable defs.
- No `oJson:Write(oMemStream)` or `oWriter:Write(mPayload)` lines.

- [ ] **Step 3: Start PASOE and curl the endpoint**

```bash
curl -i http://localhost:<port>/<svc>/<route>
```

Expected: HTTP 200, `Content-Type: application/json`, body is the DTO serialized as JSON.

- [ ] **Step 4: Test error path**

In a sample controller, throw a known error (e.g. `UNDO, THROW NEW Progress.Lang.AppError("test").`). Re-run the request.

Expected: HTTP 500, body is `{"error": "test"}`.

- [ ] **Step 5: No commit**

This task produces no code changes. Skip the commit step.

---

## Self-Review Notes

- Spec §1 ResponseWriter rewrite → Task 1.
- Spec §2 shim emission changes → Task 2.
- Spec §3 error handling → covered by helper delegating to BLOCK-LEVEL ON ERROR UNDO, THROW.
- Spec §4 testing → Tasks 1, 2, 3.
- No placeholders. All test code shown. All Kotlin edits specified. Signatures match.
- The 5 emit tests in Task 2 are a complete rewrite of the existing test class (previous tests had stale MEMPTR-dance assertions). The 4th test (`emits recursive sub-objects for nested DTO`) replaces the previous "recursive DtoSerializer call" test.
