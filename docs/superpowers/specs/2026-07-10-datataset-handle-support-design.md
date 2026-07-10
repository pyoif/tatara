# DATASET-HANDLE Support — Design

**Date:** 2026-07-10
**Status:** Approved (brainstorming complete)
**Scope:** OpenAPI schema generation + shim emission. Runtime helpers (`ReadDatasetAsArray` / `ReadDatasetAsObject`) already exist in `Tatara.Api.DtoSerializer.cls`.

## Goal

Extend the temp-table annotation system to recognize `AS DATASET-HANDLE` properties. The annotation `@Array("Dataset.Class:datasetName")` or `@Object("Dataset.Class:datasetName")` causes the OpenAPI generator to:

1. Resolve the dataset in `Dataset.Class`
2. Walk the dataset's temp-tables (parent + children via `DATA-RELATION`)
3. Emit a typed schema using field-level `SERIALIZE-NAME` / `SERIALIZE-HIDDEN`
4. Use each child TT's `SERIALIZE-NAME` (or buffer name) as the schema property key
5. Generate shim code that calls `Tatara.Api.DtoSerializer:ReadDatasetAsArray(handle)` or `:ReadDatasetAsObject(handle)` instead of the per-TT helpers

## ABL Patterns Supported

```abl
CLASS Dataset.Class:
    DEFINE TEMP-TABLE ttOrder SERIALIZE-NAME "order"
        FIELD orderId AS INTEGER SERIALIZE-NAME "id"
        FIELD total   AS DECIMAL
        FIELD lines   AS HANDLE.
    DEFINE TEMP-TABLE ttLine SERIALIZE-NAME "line"
        FIELD lineNo AS INTEGER SERIALIZE-NAME "no"
        FIELD sku    AS CHARACTER.
    DEFINE DATASET dsOrder FOR ttOrder, ttLine
        DATA-RELATION rel1 FOR ttOrder, ttLine RELATION-FIELDS(orderId, orderId).
```

Child TT annotations control shape (default = array):
```abl
DEFINE TEMP-TABLE ttLine SERIALIZE-NAME "line"
    // @Object
    FIELD lineNo AS INTEGER SERIALIZE-NAME "no"
    FIELD sku    AS CHARACTER.
```

## DTO Pattern

```abl
// @Array("repositories.project.OrderRepository:dsOrder")
DEFINE PUBLIC PROPERTY data AS DATASET-HANDLE.
```

## Naming Rules

- **Top-level:** DTO property name (`data`) is the property key. `@Object` → schema is a single object. `@Array` → schema is an array of objects.
- **Parent TT:** its fields become the object's properties. Field `SERIALIZE-NAME` (if any) is the property key; otherwise ABL field name. `SERIALIZE-HIDDEN` fields are omitted.
- **HANDLE fields in parent that point to children:** REPLACED in the schema by the child's typed data under the child's `SERIALIZE-NAME` (not the HANDLE field name). This matches what ABL's `WRITE-JSON` produces.
- **Child TTs:** property key = child's `SERIALIZE-NAME` (or buffer name if no SERIALIZE). Shape: `@Array` → array of objects; `@Object` → single object; default = array.
- **TTs in the dataset not related to the parent via `DATA-RELATION`:** ignored (not in JSON output).

## Output Schema Example

For the ABL above + `@Array("X:dsOrder")`:

```json
"data": {
  "type": "array",
  "items": {
    "type": "object",
    "properties": {
      "id":    { "type": "integer" },
      "total": { "type": "number" },
      "line": {
        "type": "array",
        "items": {
          "type": "object",
          "properties": {
            "no":  { "type": "integer" },
            "sku": { "type": "string"  }
          }
        }
      }
    }
  }
}
```

The parent TT's `lines` HANDLE field does NOT appear as a separate property — its child data is nested under the child's `SERIALIZE-NAME` `line`.

## Parser Changes

`DtoParser.kt`:

1. **Extend `propDefRegex`** to also match `DATASET-HANDLE` as the type:
   ```
   DEFINE\s+PUBLIC\s+PROPERTY\s+([\w-]+)\s+AS\s+DATASET-HANDLE
   ```
   The existing `propDefRegex` already accepts any type via `(\w+(?:[.-]\w+)*)`, so a property declared `AS DATASET-HANDLE` would be captured as `DATASET-HANDLE`. A new `isDataset` flag on `DtoProperty` is set when `ablType.uppercase() == "DATASET-HANDLE"`.

2. **New `DtoProperty.isDataset: Boolean = false`** field.

3. **New `data class DatasetInfo(val name: String, val parentTable: String, val childTables: List<String>)`** — metadata for a parsed dataset.

4. **New `fun parseDataset(dtoClassName: String, srcRoot: File, datasetName: String): DatasetInfo?`** — finds `DEFINE DATASET <name> FOR <tt1>, <tt2>, ...` line in the class file. Returns the dataset with its TTs in order. First TT is the parent; the rest are children.

5. **New regexes:**
   - `datasetDefRegex`: `(?is)DEFINE\s+DATASET\s+([\w-]+)\s+FOR\s+([\w\-, ]+?)\s*(?:\.|$)` — captures name and comma-separated TT list. Stops at the first `DATA-RELATION`, the next `DEFINE`, or end of line — whichever comes first.
   - `ttSerializeNameRegex`: `SERIALIZE-NAME\s+"([^"]+)"`
   - `fieldSerializeNameRegex`: `SERIALIZE-NAME\s+"([^"]+)"` (same pattern, applied to FIELD lines)
   - `serializeHiddenRegex`: `SERIALIZE-HIDDEN` (boolean flag on a field)

6. **Update `parseAllInlineTempTables` field emission:** for each FIELD, also capture `SERIALIZE-NAME` (overrides the field name in JSON) and `SERIALIZE-HIDDEN` (skip the field). Store on the `DtoProperty`:
   - `serializeName: String? = null`
   - `serializeHidden: Boolean = false`

7. **New `fun parseInlineTempTableRaw(dtoClassName, srcRoot, bufferName): Pair<InlineTempTable, String?>?`** — returns the `InlineTempTable` plus its TT-level `SERIALIZE-NAME` (null if none).

## OpenAPI Changes

`GenerateOpenApiTask.kt`:

1. **New `buildDatasetObjectSchema(...)`** — given a `DatasetInfo`, builds the parent schema (object) with:
   - Parent TT fields, each as a typed property (use `field.serializeName ?: field.name` as key, skip if `serializeHidden`)
   - For each child TT in the dataset (in order, except the parent):
     - Recursively look up the child's fields
     - Emit as nested typed object under `child.serializeName ?: child.bufferName`
     - Child shape: `@Array` or `@Object` (from child's annotation, default = array)
   - For `@Object` DTO: return the object directly
   - For `@Array` DTO: wrap in `{type: array, items: <object>}`

2. **Extend `addDtoToSchemas`:** when `p.isTempTable && p.isDataset`, call `buildDatasetObjectSchema` instead of `buildTempTableObjectSchema`.

## Shim Changes

`GenerateRouteTask.kt`:

When emitting the response for a property with `p.isTempTable && p.isDataset`:
- `@Object` → `oJson:Add("data", Tatara.Api.DtoSerializer:ReadDatasetAsObject(oResult:data)).`
- `@Array` → `oJson:Add("data", Tatara.Api.DtoSerializer:ReadDatasetAsArray(oResult:data)).`

(Generic fallback if dataset lookup fails, same as the current temp-table fallback.)

## Test Plan

### `DtoParserTest.kt` (5 new tests)

1. `parses DATASET-HANDLE property type` — DTO with `AS DATASET-HANDLE` sets `isDataset=true`.
2. `parses DEFINE DATASET FOR t1, t2` — extracts dataset name and TT list.
3. `parses TT-level SERIALIZE-NAME` — captures the rename on a `DEFINE TEMP-TABLE ... SERIALIZE-NAME "..."` line.
4. `parses field-level SERIALIZE-NAME` — captures per-field rename.
5. `omits SERIALIZE-HIDDEN fields` — fields with the flag are not in the parsed `DtoInfo`.

### `GenerateOpenApiTaskTest.kt` (4 new tests)

6. `dataset @Array emits parent + nested child TTs with SERIALIZE names` — DTO has `data AS DATASET-HANDLE` with `@Array`. Schema has parent fields (using field SERIALIZE-NAME) + nested child TT under child's SERIALIZE-NAME.
7. `dataset @Object emits single record schema` — DTO has `@Object`. Top is a single object (no array wrapper).
8. `child TT annotation @Object changes nested shape to single object` — child TT has `// @Object` → emitted as `{type: object, properties: ...}` (not array).
9. `dataset lookup miss falls back to generic with warning` — class not found → generic object schema + warn.

## Out of Scope

- Nested datasets (dataset in a dataset)
- Dataset-level `SERIALIZE-NAME` (on `DEFINE DATASET` itself)
- Custom `SERIALIZE-NAME` overrides in the DTO annotation
- Multi-dataset-relationship graphs beyond parent → child

## Files Changed

| File | Change |
|------|--------|
| `src/main/kotlin/com/pyoif/tatara/DtoParser.kt` | Add `isDataset`, `serializeName`, `serializeHidden` to `DtoProperty`. Add `DatasetInfo` data class. Add `parseDataset`, `parseInlineTempTableRaw`. New regexes. Update `parseAllInlineTempTables` to capture field-level SERIALIZE. |
| `src/main/kotlin/com/pyoif/tatara/GenerateOpenApiTask.kt` | Add `buildDatasetObjectSchema`. Update `addDtoToSchemas` to dispatch to it when `p.isDataset`. |
| `src/main/kotlin/com/pyoif/tatara/GenerateRouteTask.kt` | For `isDataset` props, emit `Tatara.Api.DtoSerializer:ReadDatasetAsArray/Object` call. |
| `src/test/kotlin/com/pyoif/tatara/DtoParserTest.kt` | 5 new tests. |
| `src/test/kotlin/com/pyoif/tatara/GenerateOpenApiTaskTest.kt` | 4 new tests. |
