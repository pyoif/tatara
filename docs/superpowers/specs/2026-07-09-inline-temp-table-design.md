# Inline Temp-Table Field Modeling in OpenAPI

## Problem

When a DTO has a `@Array` or `@Object` HANDLE prop, the OpenAPI generator emits a generic schema: `{type: array, items: {type: object, additionalProperties: true}}` (or the object equivalent). The user can't introspect the row shape from the spec.

ABL allows `DEFINE TEMP-TABLE` at class scope. The user can put the temp-table structure in the same class file as the DTO. We can scan the class file, extract the `FIELD` declarations, and emit a typed schema for the temp-table rows.

## Goals

- DTO class can have an inline `DEFINE TEMP-TABLE <name> FIELD <name> AS <type> ...` block.
- The OpenAPI schema for a `@Array` HANDLE prop on that DTO uses the field declarations to emit a typed `items` schema (object with named properties).
- Same for `@Object` HANDLE props (typed object schema, not generic).
- ABL field types map to OpenAPI types (CHARACTER → string, INTEGER → integer, DECIMAL → number, LOGICAL → boolean, DATE/DATETIME/DATETIME-TZ → string+format, EXTENT → array).

## Non-Goals

- Multiple temp-tables in a single class.
- Nested temp-tables (TT field whose type is another TT).
- Datasets (multiple related TTs).
- `LIKE` field references.
- Runtime changes (shim still calls `DtoSerializer:ReadTempTableAsArray/AsObject`; ABL's `JsonArray:Read` walks the TT's fields itself).

## Design

### 1. DtoParser extension

**File:** `src/main/kotlin/com/pyoif/tatara/DtoParser.kt`

Add a new method:

```kotlin
fun parseInlineTempTable(dtoClassName: String, srcRoot: File): DtoInfo? {
    val file = resolveFile(dtoClassName, srcRoot) ?: return null
    val content = file.readText()
    val ttMatch = ttDefRegex.find(content) ?: return null
    // ttDefRegex matches: DEFINE TEMP-TABLE <name> ... FIELD ... FIELD ... .
    // ...
}
```

New regex:
```kotlin
private val ttDefRegex = Regex(
    """(?is)DEFINE\s+TEMP-TABLE\s+(\w+)([^.]+?)\."""
)
```

The second capture group is everything between the buffer name and the terminating `.`. Then split by `FIELD ` to get individual field declarations.

Parse each field:
- `FIELD name AS TYPE` — scalar.
- `FIELD name AS TYPE EXTENT [N]` — array.
- `FIELD name AS DATASET` or `LIKE` — fall back to `type: object, additionalProperties: true`.

Returns a `DtoInfo` with one synthetic property whose name is the buffer name and whose nested schema is the field list. Or: returns a `DtoInfo` whose `properties` list is the field list directly (cleaner — no synthetic wrapper).

**Decision:** return a `DtoInfo` whose `properties` are the fields directly. The buffer name is preserved as a field on a new `InlineTempTable` data class:

```kotlin
data class InlineTempTable(
    val bufferName: String,
    val fields: DtoInfo
)
```

### 2. OpenAPI emission change

**File:** `src/main/kotlin/com/pyoif/tatara/GenerateOpenApiTask.kt`

In `addDtoToSchemas`, when a prop is `isTempTable`:
- If `prop.nested != null` (nested DTO class — existing path) → emit `$ref` (no change).
- Else (HANDLE prop, current generic schema) → call `DtoParser.parseInlineTempTable(dtoClassName, pkgRoot)`. If it returns a non-null `InlineTempTable`, emit the typed schema:
  - `@Array`: `{type: array, items: <typed object schema>}` where the object schema has the field list as properties.
  - `@Object`: `<typed object schema>` with the field list as properties.
- Else (no inline TT found) → fall back to the generic `{type: array, items: {type: object, additionalProperties: true}}` (current behavior).

Typed object schema example:
```json
{
  "type": "object",
  "properties": {
    "orderId": {"type": "integer"},
    "sku":     {"type": "string"},
    "total":   {"type": "number"}
  }
}
```

### 3. Shim emission: no change

The shim still calls `Tatara.Api.DtoSerializer:ReadTempTableAsArray(oResult:items)`. ABL's `JsonArray:Read(handle)` walks the TT's fields and emits them as JSON keys. The OpenAPI schema is a contract description; runtime is unchanged.

### 4. Error handling

- Class file with `DEFINE TEMP-TABLE` but parse fails → fall back to generic schema.
- Class file without `DEFINE TEMP-TABLE` → no change to current behavior.
- Unknown field type → emit `type: object, additionalProperties: true` for that field.

### 5. Testing

- **Parser unit:** Class with `DEFINE TEMP-TABLE ttItems FIELD orderId AS INTEGER FIELD sku AS CHARACTER.` → `parseInlineTempTable` returns `InlineTempTable(bufferName="ttItems", fields=[orderId, sku])`.
- **Parser unit:** Class without `DEFINE TEMP-TABLE` → returns `null`.
- **OpenAPI unit:** DTO with `@Array` HANDLE prop and inline temp-table → emitted schema is `{type: array, items: {type: object, properties: {orderId: {type: integer}, sku: {type: string}}}}` (not generic).
- **OpenAPI regression:** DTO with `@Array` HANDLE prop and no inline temp-table → still emits generic schema (current behavior).
- **Field type coverage:** `FIELD x AS CHARACTER` → string. `FIELD x AS INTEGER` → integer. `FIELD x AS DECIMAL` → number. `FIELD x AS LOGICAL` → boolean. `FIELD x AS DATE` → string+format=date. `FIELD x AS DATETIME` → string+format=date-time. `FIELD x AS CHARACTER EXTENT 3` → array of string.

## Files Changed

| File | Change |
| --- | --- |
| `src/main/kotlin/com/pyoif/tatara/DtoParser.kt` | New `InlineTempTable` data class + `parseInlineTempTable` method + `ttDefRegex` + `fieldDefRegex`. |
| `src/main/kotlin/com/pyoif/tatara/GenerateOpenApiTask.kt` | In `addDtoToSchemas`, for `isTempTable` props without `nested`, look up inline temp-table; emit typed schema. |
| `src/test/kotlin/com/pyoif/tatara/DtoParserTest.kt` | Inline temp-table parsing tests. |
| `src/test/kotlin/com/pyoif/tatara/GenerateOpenApiTaskTest.kt` | Typed vs. generic schema emission tests. |

## Out of Scope

- Multiple temp-tables per class.
- Nested temp-tables.
- Datasets.
- `LIKE` field references.
- Shim changes.
