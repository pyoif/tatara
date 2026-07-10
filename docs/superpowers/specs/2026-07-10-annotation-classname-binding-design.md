# Annotation Cross-Class & Explicit-Buffer Binding — Design

**Date:** 2026-07-10
**Status:** Approved (brainstorming complete)
**Scope:** OpenAPI schema generation only. Shim generation unchanged.

## Goal

Extend `@Object` and `@Array` annotations to accept an optional `"ClassName[:BufferName]"` parameter. This lets a DTO property point to a `DEFINE TEMP-TABLE` defined in a *different* class file, and/or override the buffer name instead of using the `tt<PropNamePascalCase>` convention.

Currently `isTempTable` HANDLE props are resolved against the same class using the `tt<PropNamePascalCase>` convention (see `GenerateOpenApiTask.addDtoToSchemas` line 164). This works for the common case (one DTO, one inline `DEFINE TEMP-TABLE ttItems`) but breaks when:

- The temp-table lives in a separate holder class (reusable across DTOs).
- The buffer name does not follow the `tt<PropName>` convention.

## Syntax

```abl
// @Object                              ; current class, tt<PropName> convention
// @Object("Other.Class")               ; target class, tt<PropName> convention
// @Object("Other.Class:ttSummary")     ; target class, explicit buffer name
// @Object(":ttSummary")                ; current class, explicit buffer name

// @Array                               ; current class, tt<PropName> convention
// @Array("Other.Class")
// @Array("Other.Class:ttOrders")
// @Array(":ttOrders")

// @TempTable                           ; bare alias for @Array (no parameter syntax)
```

Parameter parsing rules on the string between the quotes:

| Raw string      | `tempTableClass` | `tempTableName` |
|-----------------|------------------|-----------------|
| (no parens)     | `null`           | `null`          |
| `"X"`           | `"X"`            | `null`          |
| `"X:Y"`         | `"X"`            | `"Y"`           |
| `":Y"`          | `null`           | `"Y"`           |

Empty quotes (`@Array("")`) is equivalent to no parens.

`@TempTable` does not accept the parameter syntax; existing usages stay as bare markers. New code should prefer `@Array`.

## Data Model

`DtoProperty` in `DtoParser.kt` gains two nullable fields:

```kotlin
data class DtoProperty(
    ...existing fields...,
    val isTempTable: Boolean = false,
    val tempTableKind: TempTableKind = TempTableKind.NONE,
    val tempTableClass: String? = null,   // NEW: external class name, null = same class
    val tempTableName: String? = null     // NEW: explicit buffer, null = tt<PropName> convention
)
```

These are populated by the parser and read by `GenerateOpenApiTask`. `GenerateRouteTask` is not affected (it only needs `isTempTable` and `tempTableKind`).

## Parser Changes

`DtoParser.kt`:

1. Extend `annotationRegex` to capture the optional quoted parameter:
   ```kotlin
   private val annotationRegex = Regex(
       """(?i)//\s*@(Required|Path|Query|Body|TempTable|Object|Array)(?:\("([^"]*)"\))?"""
   )
   ```
   Capture group 2 = raw parameter string, or unmatched (empty) when no parens.

2. Add a private helper to split the raw string into the two fields:
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

3. In the annotation handler, when matching `object` / `array` / `temptable`, call the helper to set two new local variables `currentTempTableClass` and `currentTempTableName` (declared alongside the existing `isTempTable` / `tempTableKind` / `isReq` / `loc`). The `temptable` branch ignores the parameter (the regex will still match it, but the parser drops it — `@TempTable` is a bare alias).

4. When emitting a `DtoProperty` after a successful `propDefRegex.find`, pass the two new locals into the constructor.

5. At the end of the prop block, reset all four to defaults:
   ```kotlin
   isReq = false
   isTempTable = false
   tempTableKind = TempTableKind.NONE
   currentTempTableClass = null
   currentTempTableName = null
   ```

## OpenAPI Task Changes

`GenerateOpenApiTask.addDtoToSchemas` (lines 162-205). Replace the temp-table branch's lookup inputs:

```kotlin
if (p.isTempTable) {
    val srcClass = p.tempTableClass ?: dtoClass
    val bufName  = p.tempTableName  ?: ("tt" + p.name.replaceFirstChar { it.uppercase() })

    val inlineTt = DtoParser.parseInlineTempTableByName(srcClass, pkgRoot, bufName)
    if (inlineTt == null && p.tempTableClass != null) {
        logger.warn("Temp-table '$bufName' not found in class '$srcClass' " +
                    "(prop '${p.name}' on '$dtoClass'); falling back to generic schema.")
    }

    // existing typed-vs-generic emission logic, unchanged in shape
    val desc = when (p.tempTableKind) { ... }
    val propSchema = when (p.tempTableKind) { ... }
    innerProps.add(p.name, propSchema)
}
```

The `inlineTt != null` vs `null` branch logic (typed vs generic) is unchanged. Only the source class and buffer name inputs change.

When `tempTableClass == null` (current class), behavior is identical to today — same `dtoClass`, same `tt<PropName>` convention, no warning. Full backward compatibility.

When `tempTableClass != null` and the lookup misses, log a warning and fall back to the generic schema (with description). This matches the principle: the user opted in to an explicit binding; a miss is user error, but the build still succeeds so consumers see a typed-but-generic schema rather than nothing.

## Out of Scope

- Shim emission changes (not needed; the HANDLE walks itself at ABL runtime).
- Parameter syntax on `@TempTable` (kept as bare alias).
- Multi-buffer resolution from one annotation (one prop, one TT).
- Cross-package or fully-qualified path conventions beyond what `resolveFile` already supports (`x.y.z` → `x/y/z.cls`).
- **Unannotated HANDLE fields silently treated as nested TTs** — see "Nested temp-tables" below.

## Test Plan

### `DtoParserTest.kt` (new tests)

1. `parses @Array with class name only` — asserts `tempTableClass` set, `tempTableName` null.
2. `parses @Object with class name only` — same for OBJECT kind.
3. `parses @Array with class and buffer name` — asserts both fields set.
4. `parses @Array with current class and explicit buffer (leading colon)` — `tempTableClass` null, `tempTableName` set.
5. `parses @Array without parameter (backward compat)` — both fields null.
6. `@TempTable ignores parameter syntax` — even if user writes `// @TempTable("X:Y")`, the parser drops it: `tempTableClass` null, `tempTableName` null. Locks in lenient behavior.

### `GenerateOpenApiTaskTest.kt` (new tests)

7. `cross-class @Array resolves TT from target class file` — DTO with `// @Array("com.example.Order")`, target file with `DEFINE TEMP-TABLE ttItems FIELD orderId AS INTEGER FIELD sku AS CHARACTER.`. Assert emitted schema has `items.type=array`, `items.items.properties.orderId.type=integer`, `items.items.properties.sku.type=string`.

8. `cross-class @Array with explicit buffer name` — DTO with `// @Array("com.example.Order:ttOrders")`, target file with two TTs (`ttOrders` and `ttOther`). Assert schema uses the `ttOrders` fields, not `ttOther`.

9. `cross-class @Array falls back to generic when TT not found` — DTO with `// @Array("com.example.Missing")`, no `Missing.cls` (or file with no matching TT). Assert schema is `{type: array, items: {type: object, additionalProperties: true}}` + description.

10. `current-class @Array with leading-colon explicit buffer` — `// @Array(":ttCustom")` on a prop in a DTO that has its own `DEFINE TEMP-TABLE ttCustom FIELD a AS INTEGER.`. Assert schema uses field `a`.

11. `backward-compat: @Array without parameter uses same-class tt<PropName> convention` — regression confirming no behavior change for existing bare `@Array`.

## Files Changed

| File | Change |
|------|--------|
| `src/main/kotlin/com/pyoif/tatara/DtoParser.kt` | Add `tempTableClass` / `tempTableName` to `DtoProperty`. Extend `annotationRegex`. Add `parseTempTableParam` helper. Wire params through annotation handling. |
| `src/main/kotlin/com/pyoif/tatara/GenerateOpenApiTask.kt` | In `addDtoToSchemas`, replace hardcoded `dtoClass` and `tt<PropName>` with `p.tempTableClass ?: dtoClass` and `p.tempTableName ?: "tt" + ...`. Add `logger.warn` on miss with explicit class. |
| `src/test/kotlin/com/pyoif/tatara/DtoParserTest.kt` | 6 new tests. |
| `src/test/kotlin/com/pyoif/tatara/GenerateOpenApiTaskTest.kt` | 5 new tests. |

## Commit

`feat(openapi): bind @Array/@Object to cross-class or explicit buffer via "Class[:Buffer]" parameter`

---

# Nested Temp-Tables

**Scope:** OpenAPI schema generation only (consistent with parent feature). ABL's `JsonObject:Add(handle)` / `JsonArray:Add(handle)` do not recursively walk nested TTs at runtime, so the shim is unchanged.

## Goal

Extend the cross-class / explicit-buffer binding to `FIELD` declarations inside `DEFINE TEMP-TABLE` blocks. A temp-table field typed `AS HANDLE` can itself be a nested temp-table; the user expresses this with the same `// @Array(...)` / `// @Object(...)` annotation syntax on the FIELD line.

## Behavior

A FIELD inside a `DEFINE TEMP-TABLE` block follows these rules:

| FIELD annotation | Type | Behavior |
|---|---|---|
| `// @Array` / `// @Object` (bare) | `AS HANDLE` | nested TT, current class + `tt<FieldName>` convention |
| `// @Array("X")` / `// @Object("X")` | `AS HANDLE` | nested TT, X class + `tt<FieldName>` convention |
| `// @Array("X:Y")` / `// @Object("X:Y")` | `AS HANDLE` | nested TT, X class + Y buffer |
| `// @Array(":Y")` / `// @Object(":Y")` | `AS HANDLE` | nested TT, current class + Y buffer |
| `// @TempTable` (bare alias) | `AS HANDLE` | nested TT, current class + `tt<FieldName>` convention |
| (no annotation) | `AS HANDLE` | **plain handle, generic schema** (no change from prior behavior) |
| any | non-HANDLE | type-mapped by `mapAblType` (no change) |

**Critical:** an unannotated HANDLE field is **not** silently treated as a nested temp-table. A HANDLE can point to anything in ABL (a socket, a dataset, a procedure handle, etc.); promoting to a nested TT requires an explicit opt-in. This is the change from the initial brainstorming pass.

## Parser Changes

`DtoParser.parseAllInlineTempTables` is restructured to process the TT body line by line (instead of `findAll` over the body string). For each line:

1. Run `annotationRegex` to find any `@Array` / `@Object` / `@TempTable` annotation. Capture `tempTableClass` and `tempTableName` via the existing `parseTempTableParam` helper (the same one Task 1 introduced).
2. Run `fieldDefRegex` to find the field declaration.
3. Build a `DtoProperty` for the field. If the line has one of the recognized annotations AND the field type is `HANDLE`, set `isTempTable=true` and the class/name fields. Otherwise leave `isTempTable=false` (plain handle or typed scalar field).

`parseInlineTempTableByName` and the rest of the public surface stay unchanged. The returned `InlineTempTable.fields` carries the per-field `isTempTable` / `tempTableKind` / `tempTableClass` / `tempTableName` metadata, and `buildTempTableObjectSchema` in `GenerateOpenApiTask` walks it recursively.

## OpenAPI Task Changes

`buildTempTableObjectSchema(fields)` gains recursion. New signature:

```kotlin
private fun buildTempTableObjectSchema(
    fields: DtoParser.DtoInfo,
    pkgRoot: File
): JsonObject
```

Internally, the helper uses a `MutableSet<String>` of `"ClassName::BufferName"` keys to break cycles. The set is threaded through the recursion as a parameter (default empty when called from the top). For each field:

- If `field.isTempTable`: resolve via `DtoParser.parseInlineTempTableByName(srcClass, pkgRoot, bufName)`. On hit, recurse with `visited` augmented. On miss, log a warning and emit generic `{type: object, additionalProperties: true}` for that field. On cycle (key already in `visited`), log a warning and emit generic.
- Else: emit `mapAblType(field.ablType, extent?, JsonObject())` (unchanged).

The outer shape (`type: array` with `items: <typedObj>` for `@Array`, or `<typedObj>` for `@Object`) is unchanged from Task 2.

The two call sites in `addDtoToSchemas` (the `@Array` branch and the `@Object` branch) pass `pkgRoot` through. No other changes to `addDtoToSchemas` or any other method.

## Out of Scope (Nested)

- Shim emission changes for nested TTs. ABL's runtime does not walk nested TTs via `JsonObject:Add(handle)`. Users wanting nested-TT data in JSON must flatten via temp-table relationships at the controller level (same constraint as before).
- Unannotated HANDLE fields promoted to nested TTs (explicit annotation required).

## Test Plan (Nested)

### `DtoParserTest.kt` (5 new tests)

1. `parses nested @Array on FIELD line with class only` — explicit `"Class"` parameter on a FIELD line; asserts `field.isTempTable=true`, `tempTableKind=ARRAY`, `tempTableClass="X"`, `tempTableName=null`.
2. `parses nested @Object on FIELD line with class and buffer name` — explicit `"X:Y"` parameter; asserts OBJECT kind and both fields populated.
3. `parses bare @Array on FIELD line using convention` — no parens; asserts `tempTableClass=null`, `tempTableName="tt<FieldName>"`.
4. `HANDLE field without annotation is not a nested temp-table` — asserts `isTempTable=false`.
5. `non-HANDLE field with @Array annotation is still not a temp-table` — `AS CHARACTER` with `// @Array`; asserts `isTempTable=false` (only HANDLE-typed fields qualify).

### `GenerateOpenApiTaskTest.kt` (4 new tests)

6. `emits typed nested array schema from inline temp-table fields` — outer `// @Array` + inner `// @Array("X:ttY")` resolves and emits `lineNo`/`text` fields from the nested TT.
7. `nested @Array falls back to generic when target class missing` — outer TT is typed; nested field falls back to generic.
8. `nested temp-table cycle emits generic schema and does not infinite loop` — `ttItems.children` annotated to point back to `ttItems`; build terminates and `orderId` still emitted.
9. `HANDLE field without annotation stays as generic handle` — regression: unannotated HANDLE field does not promote to nested TT; build succeeds and `orderId` is present.

## Files Changed (Nested)

| File | Change |
|------|--------|
| `src/main/kotlin/com/pyoif/tatara/DtoParser.kt` | Refactor `parseAllInlineTempTables` to per-line processing; add annotation handling for FIELD lines. |
| `src/main/kotlin/com/pyoif/tatara/GenerateOpenApiTask.kt` | `buildTempTableObjectSchema` gains `pkgRoot` parameter + recursive nested-TT resolution with visited set. |
| `src/test/kotlin/com/pyoif/tatara/DtoParserTest.kt` | 5 new tests. |
| `src/test/kotlin/com/pyoif/tatara/GenerateOpenApiTaskTest.kt` | 4 new tests. |
