# Temp-Table Response Serialization

## Problem

The shim's response DTO serializer treats `HANDLE`-typed properties as scalar (per the `DtoParser` primitive set) and emits `null` for them. ABL temp-tables are commonly exposed via `HANDLE`-typed DTO properties. We need the shim to recognize temp-table properties and serialize them as JSON arrays or objects.

## Goals

- Recognize a DTO property as a temp-table via annotation.
- Emit `JsonArray` (default) or `JsonObject` (opt-in) for the temp-table's content.
- No DtoSerializer runtime class changes; all logic lives at shim generation time.

## Non-Goals

- Nested temp-tables require special handling — out of scope. (ABL `JsonObject:Add`/`JsonArray:Add` with a HANDLE do not recursively walk nested TTs; users flatten via temp-table relationships at the controller level.)
- Per-row JSON key renaming.
- Filtering rows (the whole table is emitted).
- ABL field type → JSON value type customization.

## Design

### 1. Annotations

Two new annotation markers on DTO property comments:

- `@Object` — emit the temp-table as a single JSON object.
- `@Array` — emit the temp-table as a JSON array. **Default** if neither annotation is present but a `@TempTable` marker is given.

Pre-existing `@TempTable` marker remains valid and means `@Array`.

Examples:
```progress
// @Array
DEFINE PUBLIC PROPERTY orders AS HANDLE NO-UNDO.

// @Object
DEFINE PUBLIC PROPERTY summary AS HANDLE NO-UNDO.

// @TempTable
DEFINE PUBLIC PROPERTY items AS HANDLE NO-UNDO.  // same as @Array
```

Properties must be declared `AS HANDLE`. Class-ref-typed properties (`AS SomeApp.TempTable`) are not supported in this iteration; document as a follow-up.

### 2. `DtoParser` changes

**File:** `src/main/kotlin/com/pyoif/tatara/DtoParser.kt`

- Add to `DtoProperty`:
  - `isTempTable: Boolean = false`
  - `tempTableKind: TempTableKind = TempTableKind.NONE`
- New enum: `enum class TempTableKind { NONE, OBJECT, ARRAY }`.
- Update `annotationRegex` to recognize `Required|Path|Query|Body|TempTable|Object|Array`.
- In the per-line annotation switch:
  - `@Object` → `isTempTable=true`, `tempTableKind=OBJECT`, reset `isReq`.
  - `@Array` or `@TempTable` → `isTempTable=true`, `tempTableKind=ARRAY`, reset `isReq`.
- Temp-table flag sticks across properties until a different annotation marker is encountered (same behavior as `@Path`/`@Query`/`@Body`).
- A property with `isTempTable=true` skips the primitive/cycle/nested-DTO classification logic — it's neither scalar nor DTO; it's a temp-table. `isDto=false`, `nested=null`.

### 3. `GenerateRouteTask` changes

**File:** `src/main/kotlin/com/pyoif/tatara/GenerateRouteTask.kt`

Extend `emitResponseJson` with a new branch for `prop.isTempTable`:

- **`@Array` (or default):** emit one line:
  ```progress
  oJson:Add("<name>", <accessor>).
  ```
  Where `<accessor>` is `oResult` (top-level) or `oResult:<parent>:<child>` for nested emission.

- **`@Object`:** emit two lines:
  ```progress
  oJson:Add("<name>", NEW Progress.Json.ObjectModel.JsonObject()).
  oJson:GetJsonObject("<name>"):Read(<accessor>).
  ```

The `JsonObject:Add(name, HANDLE)` overload handles the array case natively. For `@Object`, the explicit `JsonObject:Read(handle)` invocation produces a single JSON object via ABL's runtime.

### 4. DtoSerializer runtime class

**Unchanged.** No signature change, no new params. Temp-table serialization is entirely inline in the generated shim.

### 5. Error handling

- If the DTO property is `?` at runtime, `oJson:Add(name, ?)` will pass `?` as the value — ABL will throw or emit `null` depending on version. The shim's existing `CATCH errApp` catches it → 500.
- If the HANDLE is not actually a temp-table (user error), `Add`/`Read` will throw at runtime → 500 via existing catch.

### 6. Testing

- **Parser unit tests:** DTO with `@TempTable`, `@Object`, `@Array` annotations on `HANDLE` props; default behavior; no annotation on HANDLE prop (regression: `isTempTable=false`).
- **Emission unit tests:**
  - `@Array` on HANDLE prop → shim contains `oJson:Add("orders", oResult:orders).` (no `:Handle` suffix, no `NEW JsonArray()`).
  - `@Object` on HANDLE prop → shim contains `oJson:Add("summary", NEW JsonObject()).` and `oJson:GetJsonObject("summary"):Read(oResult:summary).`.
  - Regression: flat DTO and nested-DTO tests still pass.

## Files Changed

| File | Change |
| --- | --- |
| `src/main/kotlin/com/pyoif/tatara/DtoParser.kt` | Add `isTempTable`, `tempTableKind`, annotation cases |
| `src/main/kotlin/com/pyoif/tatara/GenerateRouteTask.kt` | Extend `emitResponseJson` with temp-table branch |
| `src/test/kotlin/com/pyoif/tatara/DtoParserTest.kt` | New tests for temp-table parsing |
| `src/test/kotlin/com/pyoif/tatara/GenerateRouteTaskEmitTest.kt` | New tests for temp-table emission |

## Out of Scope

- Class-ref-typed temp-table properties (`AS SomeApp.TempTable`).
- Nested temp-table serialization.
- Per-row key renaming.
- Row filtering.
