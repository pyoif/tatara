# Shim: Serialize Response DTO to JsonObject

## Problem

The generated route shim currently handles response DTOs by writing `oResult:data` (a longchar) to the response in 32KB chunks. This requires the controller's response DTO to expose a pre-serialized JSON string as `data`, which couples serialization to the controller layer and bypasses the standard PASOE `Entity` pipeline.

We want the shim to serialize the response DTO instance itself, property-by-property, into a `Progress.Json.ObjectModel.JsonObject`, and assign that to `oResponse:Entity`. PASOE then handles the actual JSON wire output natively.

## Goals

- Shim serializes response DTO to `JsonObject` at runtime.
- Support arbitrary nesting depth (DTO of DTO of DTO) via recursion.
- Cycle-safe at code-generation time.
- `?` / unset properties serialize as JSON `null`; all properties are always emitted.
- No new runtime dependencies.

## Non-Goals

- No runtime reflection on arbitrary objects — schema is known at shim-generation time.
- No support for `HANDLE`, `RAW`, `MEMPTR`, `BLOB`, `CLOB` property types (skip / emit null).
- No serializer caching or memoization.
- No changes to request DTO handling (already deserializes from JSON body via DtoParser).

## Design

### 1. `Tatara.Api.DtoSerializer` (new runtime class)

**File:** `src/main/resources/Tatara/Api/DtoSerializer.cls`

Single static entry point:

```progress
METHOD PUBLIC STATIC Progress.Json.ObjectModel.JsonObject ToJsonObject(
    INPUT poDto   AS Progress.Lang.Object,
    INPUT pcNames AS CHARACTER EXTENT,
    INPUT pcTypes AS CHARACTER EXTENT,
    INPUT plIsDto AS LOGICAL EXTENT)
```

Behavior:
- If `poDoc = ?` → return empty `JsonObject`.
- For each `i` in `1..extent(pcNames)`:
  - Read property: `oVal = poDoc:GetClass():GetProperty(pcNames[i]):Get(poDoc) NO-ERROR.`
  - If `plIsDto[i] = TRUE`:
    - If `oVal = ?` → `oJson:AddNull(pcNames[i])`.
    - Else → recurse: `oJson:Add(pcNames[i], ToJsonObject(oVal, nestedNames, nestedTypes, nestedIsDto))`.
  - Else (scalar):
    - If `oVal = ?` → `oJson:AddNull(pcNames[i])`.
    - Else dispatch on `pcTypes[i]`:
      - `INTEGER` / `INT64` → `oJson:Add(pcNames[i], INTEGER(oVal))`
      - `DECIMAL` → `oJson:Add(pcNames[i], DECIMAL(oVal))`
      - `LOGICAL` → `oJson:Add(pcNames[i], LOGICAL(oVal))`
      - `DATE` → `oJson:Add(pcNames[i], DATE(oVal))`
      - `DATETIME` / `DATETIME-TZ` → `oJson:Add(pcNames[i], DATETIME(oVal))`
      - `CHARACTER` / `LONGCHAR` → `oJson:Add(pcNames[i], CHARACTER(oVal))`
      - Anything else (incl. `HANDLE`, `RAW`, `MEMPTR`, `BLOB`, `CLOB`, unrecognized) → `oJson:AddNull(pcNames[i])`.
- Return `oJson`.

Nested DTO prop tables (`nestedNames`, `nestedTypes`, `nestedIsDto`) are computed at shim-generation time and passed in by the shim.

### 2. `DtoParser` (modify)

**File:** `src/main/kotlin/com/pyoif/tatara/DtoParser.kt`

Change `parse` to recurse into nested DTO types:

```kotlin
fun parse(
    dtoClassName: String,
    srcRoot: File,
    visited: MutableSet<String> = mutableSetOf()
): DtoInfo
```

Logic:
- Add `dtoClassName` to `visited` at entry.
- For each property in the class:
  - If property's `ablType` is a known primitive (`CHARACTER`, `LONGCHAR`, `INTEGER`, `INT64`, `DECIMAL`, `LOGICAL`, `DATE`, `DATETIME`, `DATETIME-TZ`) → emit `DtoProperty(name, type, isRequired, location, isExtent, isDto=false)`.
  - Else (treat as nested DTO class name):
    - If `ablType` is in `visited` → emit `DtoProperty(name, type, isRequired, location, isExtent, isDto=false)` (cycle break — emit as null at runtime).
    - Else → recursively `parse(ablType, srcRoot, visited)`, attach the returned `DtoInfo` to the property. Emit `DtoProperty(name, type, isRequired, location, isExtent, isDto=true, nested=childDtoInfo)`.
- Return root `DtoInfo` with full transitive schema.

Add a `nested: DtoInfo? = null` field to `DtoProperty` carrying the child's schema.

Recursion depth is unbounded in principle; in practice bounded by DTO tree depth in the user's project. No runtime stack risk beyond typical ABL call-stack limits (hundreds of frames).

### 3. `GenerateRouteTask` (modify)

**File:** `src/main/kotlin/com/pyoif/tatara/GenerateRouteTask.kt`

In `writeShim`, when `hasResponseDto`:

1. Call `DtoParser.parse(def.responseDtoClassName!!, srcRoot)` to obtain the full transitive schema.
2. Emit, after the CATCH block and before `Tatara.Api.ResponseWriter:Write`:

```progress
DEFINE VARIABLE oJson AS Progress.Json.ObjectModel.JsonObject NO-UNDO.
oJson = Tatara.Api.DtoSerializer:ToJsonObject(
    oResult,
    NEW CHARACTER EXTENT [<N>] [<"name1", "name2", ...>],
    NEW CHARACTER EXTENT [<N>] [<"TYPE1", "TYPE2", ...>],
    NEW LOGICAL   EXTENT [<N>] [<no, no, ...>]).

oResponse:StatusCode = 200.
oResponse:ContentType = "application/json".
oResponse:Entity = oJson.
Tatara.Api.ResponseWriter:Write(poRequest, oResponse).
RETURN 0.
```

3. For nested properties, the Kotlin generator flattens the transitive `DtoInfo` into three parallel arrays: `names`, `types`, `isDto`. The order is a depth-first traversal of the property tree. The runtime `ToJsonObject` recurses naturally because for each `isDto=true` slot it receives the nested DTO object (`oResult:<propname>`) and the *child's* prop subtable is passed in by the shim — but wait: the shim only passes one flat table.

**Correction:** the runtime `ToJsonObject` cannot recurse on its own unless the shim also passes a *nested table* per nested prop. Two viable shapes:

- **Shape A (deep recursion in shim, single call per DTO level):** Shim emits one `ToJsonObject` call per DTO level. For each nested prop, shim emits an inline call: `oJson:Add("addr", Tatara.Api.DtoSerializer:ToJsonObject(oResult:addr, [street,city], [CHAR,CHAR], [no,no]))`. Generated code grows with DTO depth.
- **Shape B (single call, table-of-tables):** `pcNames`/`pcTypes`/`plIsDto` are `EXTENT` of `EXTENT`. Outer index = property slot; inner = subtable for nested DTOs, or single-element `[name]/[type]/[isDto]` for scalars. Runtime knows how to recurse.

We choose **Shape A**. Rationale: simple runtime API (no nested-extent parsing), generated shim is verbose but trivially debuggable, no need to teach `ToJsonObject` about table-of-tables.

Concretely, the shim generator emits a nested call site for every `isDto=true` property. For the example above with `addr` being nested and `contact` being nested inside `addr`, the generated shim contains:

```progress
oJson:Add("addr",
    Tatara.Api.DtoSerializer:ToJsonObject(
        oResult:addr,
        NEW CHARACTER EXTENT [3] ["street", "city", "contact"],
        NEW CHARACTER EXTENT [3] ["CHARACTER", "CHARACTER", "MyApp.DtoContact"],
        NEW LOGICAL   EXTENT [3] [no, no, yes])).
oJson:Add("contact",
    Tatara.Api.DtoSerializer:ToJsonObject(
        oResult:addr:contact,
        NEW CHARACTER EXTENT [2] ["phone", "email"],
        NEW CHARACTER EXTENT [2] ["CHARACTER", "CHARACTER"],
        NEW LOGICAL   EXTENT [2] [no, no])).
```

For DTOs with no nesting, the shim emits a single top-level call and that's it.

4. Remove the `oWriter` / `iOffset` definitions and the `DO WHILE iOffset <= LENGTH(oResult:data)` chunked-write block, but only in the `hasResponseDto` branch.

5. `Tatara.Api` is already in the using block (`USING Tatara.Api.*`).

### 4. Error handling

- `DtoSerializer:ToJsonObject` has no internal CATCH; property read errors fall through to the shim's existing `CATCH errApp AS Progress.Lang.AppError` block → 500 with `ErrorResponse` body. Same fallback as today.
- `?` DTO input → empty `{}`, not an error.
- Cycle (already broken at gen time) → nested prop serialized as `null`; no runtime error.

### 5. Testing

- **Unit (Kotlin):** DtoParser on a fixture DTO with nested DTO and cycle (A→B→A) returns tree with B's reference to A marked `isDto=false`.
- **Integration:** Generated shim for a sample route with a 3-level nested response DTO produces JSON matching the expected shape.
- **Null:** Unset property → JSON `null`; empty DTO → `{}`.
- **Regression:** Routes without a response DTO continue to work unchanged.

## Files Changed

| File | Change |
| --- | --- |
| `src/main/resources/Tatara/Api/DtoSerializer.cls` | New runtime class |
| `src/main/kotlin/com/pyoif/tatara/DtoParser.kt` | Recursive parse, cycle-safe, carries nested DtoInfo |
| `src/main/kotlin/com/pyoif/tatara/GenerateRouteTask.kt` | Emit recursive `ToJsonObject` calls; remove chunked-write block |

## Out of Scope

- Per-DTO generated serializer classes (rejected — runtime helper is sufficient).
- Support for `HANDLE`/`BLOB`/`CLOB` property types.
- Custom JSON property names (e.g. `@JsonProperty`).
- Serializer caching.
