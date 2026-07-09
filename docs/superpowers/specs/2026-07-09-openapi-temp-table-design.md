# OpenAPI Spec: Temp-Table Props + Error Schema Alignment

## Problem

The OpenAPI generator (`GenerateOpenApiTask`) doesn't reflect three runtime behaviors added in recent shim iterations:

1. **Temp-table props (`@Array` / `@Object` / `@TempTable`)** — `DtoParser` now classifies `HANDLE` props annotated as temp-tables, but the OpenAPI generator doesn't know about this. It treats the `HANDLE` type as an unknown class and emits a broken `$ref` to a non-existent `HANDLE` schema.
2. **`ErrorResponse` schema mismatch** — the shim emits `{"error": "<message>"}` (single key) for all error paths, but the OpenAPI generator hardcodes a schema with `error` + `message` (two keys).
3. **Custom error DTO references** — declared custom error DTOs (e.g. `com.example.NotFoundError`) get `$ref` schemas pointing to their class shape, but the shim doesn't actually serialize the custom DTO instance — it always emits `errCustom:GetMessage(1)` as a plain `{"error": "msg"}` string.

We need the OpenAPI spec to match what the shim actually produces at runtime.

## Goals

- Temp-table props in response DTOs produce valid OpenAPI schemas.
- All error response schemas (custom, ApiError, AppError, fallback 4xx/5xx) use the same `ErrorResponse` shape.
- `ErrorResponse` schema matches the runtime payload.

## Non-Goals

- Static field-level modeling of temp-table rows. ABL temp-table fields are not visible to `DtoParser` at gen time; the user defines the TT class in business logic. Modeling TTs accurately would require parsing the TT class file's `FIELD` definitions — out of scope.
- Runtime change: shim continues to emit the same JSON shapes it does today.
- Per-error-class schemas (e.g. `NotFoundError` having a `resourceId` field).

## Design

### 1. `addDtoToSchemas` updates

**File:** `src/main/kotlin/com/pyoif/tatara/GenerateOpenApiTask.kt`

When iterating over DTO properties in `addDtoToSchemas`:

- If `p.isTempTable == true`:
  - If `p.tempTableKind == ARRAY` (or the default for `@TempTable`): emit
    ```json
    {
      "type": "array",
      "items": { "type": "object", "additionalProperties": true },
      "description": "ABL temp-table; field-level schema not modeled"
    }
    ```
  - If `p.tempTableKind == OBJECT`: emit
    ```json
    {
      "type": "object",
      "additionalProperties": true,
      "description": "ABL temp-table (single-row); field-level schema not modeled"
    }
    ```
- Otherwise: existing behavior via `mapAblType` (scalar via `typeMap` or `$ref` to nested DTO schema).

### 2. `ErrorResponse` schema correction

In the schema-construction block (around line 92), the hardcoded `ErrorResponse`:

```kotlin
schemas.add("ErrorResponse", JsonObject().apply {
    addProperty("type", "object")
    add("properties", JsonObject().apply {
        add("error", JsonObject().apply { addProperty("type", "string") })
        add("message", JsonObject().apply { addProperty("type", "string") })
    })
})
```

becomes:

```kotlin
schemas.add("ErrorResponse", JsonObject().apply {
    addProperty("type", "object")
    add("properties", JsonObject().apply {
        add("error", JsonObject().apply { addProperty("type", "string") })
    })
    add("required", JsonArray().apply { add("error") })
})
```

### 3. Custom error responses

In `buildPathFromRoute`, the loop that adds `route.errorResponses` to the `responses` object:

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

becomes:

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

The custom DTO class no longer gets a per-class schema reference; every error response uses the same `ErrorResponse` shape.

### 4. Testing

- **Unit:** `addDtoToSchemas` for a DTO with a `@Array` HANDLE prop → emitted schema for that prop is `{"type":"array","items":{"type":"object","additionalProperties":true}}` with a `description`.
- **Unit:** DTO with a `@Object` HANDLE prop → emitted schema is `{"type":"object","additionalProperties":true}`.
- **Unit:** `ErrorResponse` schema contains only `error` (string, required); no `message` key.
- **Unit:** Path with a declared custom error (`@Response(404, com.example.NotFoundError)`) generates the 404 response with `$ref: #/components/schemas/ErrorResponse`, not `#/components/schemas/NotFoundError`.
- **Regression:** Flat DTO and nested DTO schemas still emit correctly.

## Files Changed

| File | Change |
| --- | --- |
| `src/main/kotlin/com/pyoif/tatara/GenerateOpenApiTask.kt` | `addDtoToSchemas` temp-table branch, `ErrorResponse` schema fix, custom error `$ref` fix |
| `src/test/kotlin/com/pyoif/tatara/GenerateOpenApiTaskTest.kt` (new) | Unit tests for the above |

## Out of Scope

- Static temp-table field modeling.
- Per-error-class schemas.
- Runtime shim changes.
