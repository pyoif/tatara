# Explicit Temp-Table Name Binding

## Problem

When a DTO has `DEFINE PUBLIC PROPERTY items AS HANDLE` with `@Array` annotation, plus an inline `DEFINE TEMP-TABLE` block, the OpenAPI generator doesn't automatically link the HANDLE prop to the right temp-table. Currently `parseInlineTempTable` returns the FIRST `DEFINE TEMP-TABLE` block in the class; if a class has multiple inline TTs, the wrong one might be picked.

We need a name-based binding convention: `items` prop → `ttItems` buffer. The user declares the TT with a name that mirrors the property name (PascalCase + `tt` prefix).

## Goals

- HANDLE prop with `@Array`/`@Object` annotation looks up the matching inline temp-table by name: `tt<PropNamePascalCase>`.
- Falls back to single-TT class behavior if no named match (the existing `parseInlineTempTable` already returns the first/only TT).
- Falls back to generic schema if no inline temp-table is found.
- Naming convention is mechanical — no new annotation needed.

## Non-Goals

- Multi-TT classes where the binding is ambiguous (e.g. two TTs neither matching the prop name convention). Falls back to current behavior.
- Explicit annotation to override the name binding (e.g. `@TempTable("customName")`). Could be added later if needed.
- Shim changes.

## Design

### 1. DtoParser extensions

**File:** `src/main/kotlin/com/pyoif/tatara/DtoParser.kt`

Add new public method:

```kotlin
fun parseInlineTempTableByName(
    dtoClassName: String,
    srcRoot: File,
    bufferName: String
): InlineTempTable?
```

Looks for a `DEFINE TEMP-TABLE <bufferName>` block in the class file. Returns the matching `InlineTempTable` or `null` if no such TT exists.

Add private helper:

```kotlin
private fun parseAllInlineTempTables(
    dtoClassName: String,
    srcRoot: File
): List<InlineTempTable>
```

Iterates all `DEFINE TEMP-TABLE <name> ... .` blocks in the class file and returns one `InlineTempTable` per match.

Refactor: `parseInlineTempTable` (existing, returns first) becomes `parseAllInlineTempTables().firstOrNull()`.

### 2. OpenAPI emission change

**File:** `src/main/kotlin/com/pyoif/tatara/GenerateOpenApiTask.kt`

In `addDtoToSchemas`, for `isTempTable` props without `nested`:

1. Compute `expectedTtName = "tt" + p.name.replaceFirstChar { it.uppercase() }`.
2. Call `DtoParser.parseInlineTempTableByName(dtoClass, pkgRoot, expectedTtName)`.
3. If null, fall back to `DtoParser.parseInlineTempTable(dtoClass, pkgRoot)` (first TT — handles single-TT class case).
4. If both null, fall back to generic schema (current behavior).

The rest of the emission logic (typed vs. generic schema, ARRAY vs. OBJECT, description) is unchanged from the previous inline-TT work.

### 3. Examples

```progress
CLASS com.example.Order:
    DEFINE TEMP-TABLE ttItems FIELD orderId AS INTEGER FIELD sku AS CHARACTER.
    // @Array
    DEFINE PUBLIC PROPERTY items AS HANDLE.
```

`items` prop → `ttItems` (PascalCase of `items` = `Items`, prefixed with `tt` = `ttItems`). Match found. Schema:
```json
{
  "type": "array",
  "items": {
    "type": "object",
    "properties": {
      "orderId": {"type": "integer"},
      "sku":     {"type": "string"}
    }
  }
}
```

```progress
CLASS com.example.Order:
    DEFINE TEMP-TABLE ttData FIELD total AS DECIMAL.
    // @Array
    DEFINE PUBLIC PROPERTY data AS HANDLE.
```

`data` → `ttData`. Match found.

```progress
CLASS com.example.Order:
    DEFINE TEMP-TABLE ttFoo FIELD x AS INTEGER.
    // @Array
    DEFINE PUBLIC PROPERTY items AS HANDLE.
```

`items` → `ttItems` (no match). Class has one TT but doesn't match name. Fall back to single-TT behavior? No — name binding fails AND `parseInlineTempTable` returns the first TT. Currently `parseInlineTempTable` returns the first TT (`ttFoo`), so the user would get the wrong fields.

**Behavior decision:** if the name-based binding doesn't find a match, do NOT fall back to `parseInlineTempTable`. Fall back to generic schema. This forces the user to use the naming convention or fall back to current generic behavior.

### 4. Error handling

- Class file with no `DEFINE TEMP-TABLE` and HANDLE prop with `@Array`/`@Object` → generic schema (current behavior).
- Class file with `DEFINE TEMP-TABLE` but name doesn't match `tt<PropName>` → generic schema.
- Class file with multiple `DEFINE TEMP-TABLE` blocks, none matching → generic schema.
- Class file with matching name → use that TT's fields.

### 5. Testing

- **Parser unit:** Class with `DEFINE TEMP-TABLE ttItems FIELD orderId AS INTEGER.` and `parseInlineTempTableByName("Order", src, "ttItems")` returns the matching TT.
- **Parser unit:** Class with no matching name → returns null.
- **OpenAPI unit:** DTO with `items` HANDLE prop + `ttItems` inline TT → typed array schema with `orderId` property.
- **OpenAPI unit:** DTO with `items` HANDLE prop + `ttOther` inline TT (name mismatch) → generic schema (fall back).
- **OpenAPI unit:** DTO with `data` HANDLE prop + `ttData` inline TT → typed array schema with `data` property's TT fields.

## Files Changed

| File | Change |
| --- | --- |
| `src/main/kotlin/com/pyoif/tatara/DtoParser.kt` | Add `parseInlineTempTableByName` + `parseAllInlineTempTables`. Refactor `parseInlineTempTable` to use the new helper. |
| `src/main/kotlin/com/pyoif/tatara/GenerateOpenApiTask.kt` | In `addDtoToSchemas`, compute name binding + look up. |
| `src/test/kotlin/com/pyoif/tatara/DtoParserTest.kt` | `parseInlineTempTableByName` tests. |
| `src/test/kotlin/com/pyoif/tatara/GenerateOpenApiTaskTest.kt` | Name-binding schema tests. |

## Out of Scope

- Explicit annotation to override name binding.
- Multi-TT disambiguation.
- Shim changes.
