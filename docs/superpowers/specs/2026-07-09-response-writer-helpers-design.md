# ResponseWriter JSON/Error Helpers

## Problem

The generated route shim has a repetitive 5-line `MemoryOutputStream`/`MEMPTR` pattern after every code path (success and each error catch). The `Tatara.Api.ResponseWriter:Write(poRequest, poResponse)` method is too generic and forces the shim to manage the JSON-to-MEMPTR serialization and writer lifecycle itself.

We want the shim to call a single helper per response — one for successful JSON output, one for error output — and have both helpers own the entire writer/stream/cleanup dance internally.

## Goals

- Single shim call per response path.
- `ResponseWriter` opens the writer, serializes the JSON, writes to the response, closes the writer, frees the MEMPTR, and deletes the stream. All in one helper.
- Drop backward compatibility with the old `ResponseWriter:Write(poRequest, poResponse)` method.

## Non-Goals

- Streaming/large response support.
- Custom content types (always `application/json`).
- Custom serialization of the response DTO (still emitted inline by the shim).
- Caching or stateful helpers.

## Design

### 1. `Tatara.Api.ResponseWriter` (rewrite)

**File:** `src/main/resources/Tatara/Api/ResponseWriter.cls`

Drop the existing `Write(poRequest, poResponse)` method. Add two static methods, both fully self-contained:

```progress
METHOD PUBLIC STATIC VOID WriteJsonObject(
    INPUT poResponse AS OpenEdge.Web.WebResponse,
    INPUT poJson     AS Progress.Json.ObjectModel.JsonObject):
    /* Set status 200, content type "application/json", serialize poJson to MEMPTR,
       write to response, close, free. */
END METHOD.

METHOD PUBLIC STATIC VOID WriteError(
    INPUT poResponse     AS OpenEdge.Web.WebResponse,
    INPUT piStatusCode   AS INTEGER,
    INPUT pcErrorMessage AS CHARACTER):
    /* Set status to piStatusCode, build {"error": pcErrorMessage} JsonObject,
       serialize, write, close, free. */
END METHOD.
```

Both methods internally:
- Create `MemoryOutputStream` (`oMemStream`).
- Open `WebResponseWriter` on the response.
- `oJson:Write(oMemStream)`.
- `mPayload = oMemStream:Data`.
- `oWriter:Write(mPayload)`.
- `oWriter:Close()`.
- `SET-SIZE(mPayload) = 0.`
- `DELETE OBJECT oMemStream.`

Helpers are the only consumers of `MemoryOutputStream`/`MEMPTR`; the shim never sees them.

### 2. Shim emission changes

**File:** `src/main/kotlin/com/pyoif/tatara/GenerateRouteTask.kt`

In the per-handler preamble, **remove** these `DEFINE VARIABLE` lines:
- `oWriter AS OpenEdge.Web.WebResponseWriter`
- `oMemStream AS MemoryOutputStream`
- `mPayload AS MEMPTR`

In the error catches, replace the 5-line MEMPTR dance with a single call:
```progress
Tatara.Api.ResponseWriter:WriteError(oResponse, 500, errApp:GetMessage(1)).
```
The helper sets the status code internally — shim no longer sets `oResponse:StatusCode` in the catch.

In the success path, replace the 5-line MEMPTR dance + `oResponse:ContentType` line with a single call:
```progress
Tatara.Api.ResponseWriter:WriteJsonObject(oResponse, oJson).
```

The `RequestContext`-only path (no DTOs) at line ~416 also calls `Tatara.Api.ResponseWriter:Write(poRequest, oResponse)`. Replace with `WriteJsonObject(oResponse, NEW JsonObject())` (empty body, status 200).

### 3. Error handling

- Helpers do not catch errors internally. Any failure in the MEMPTR dance propagates to the shim's `BLOCK-LEVEL ON ERROR UNDO, THROW`.
- Status code is set by the helper; the shim no longer sets it before calling the error helper.

### 4. Testing

- **Unit (Kotlin):** Generated shim for a flat response DTO contains `Tatara.Api.ResponseWriter:WriteJsonObject(oResponse, oJson).` and does **not** contain `oWriter:Open()`, `oMemStream`, `mPayload`, `oWriter:Write(mPayload)`, `oJson:Write(oMemStream)`, `SET-SIZE(mPayload)`.
- **Emission regression:** Error catches contain `Tatara.Api.ResponseWriter:WriteError(oResponse, <code>, <msg>).` and no longer set `oResponse:StatusCode` (helper does it).
- **RequestContext path:** Generated shim still compiles (regression baseline — no shim for this path may exist in current test fixtures; manual smoke required).

## Files Changed

| File | Change |
| --- | --- |
| `src/main/resources/Tatara/Api/ResponseWriter.cls` | Rewrite: drop `Write`, add `WriteJsonObject` + `WriteError` |
| `src/main/kotlin/com/pyoif/tatara/GenerateRouteTask.kt` | Shim emission: drop MEMPTR dance, emit single helper call per response |
| `src/test/kotlin/com/pyoif/tatara/GenerateRouteTaskEmitTest.kt` | New assertions for helper call, negative assertions for MEMPTR variables |

## Out of Scope

- Streaming/large response support.
- Custom content types.
- The `Write(poRequest, poResponse)` overload (intentionally dropped — backward compatibility removed per user direction).
