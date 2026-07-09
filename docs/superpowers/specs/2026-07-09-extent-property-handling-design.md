# EXTENT Property Handling in Request/Response DTOs

## Problem

The shim and the OpenAPI generator handle scalar and nested DTO properties, but properties declared with `EXTENT` (e.g. `DEFINE PUBLIC PROPERTY tags AS CHARACTER EXTENT 5.`) are not handled:

- **Request body deserialization** (shim) — the body-emission switch in `GenerateRouteTask.writeShim` ignores `prop.isExtent`. A `CHARACTER EXTENT` prop gets the scalar branch (`oJson:GetCharacter("tags")`), which assigns a scalar into an extent — runtime ABL type mismatch.
- **Response serialization** (shim) — `emitResponseJson` doesn't check `prop.isExtent`. An extent prop is emitted as `oJson:Add("tags", oResult:tags)`, which stringifies the extent reference rather than producing a JSON array.
- **OpenAPI** — `mapAblType` already wraps with `type: array, items: ...` for extents. ✅ Already correct.

We need both shim paths to handle extents, including extents of nested DTO types (e.g. `ADDRESS EXTENT`).

## Goals

- Request body: JSON arrays map into ABL extents (scalar elements or nested DTO elements).
- Response: ABL extents serialize as JSON arrays (scalar elements or nested DTO element objects).
- Support both fixed-size (`EXTENT 5`) and unfixed (`EXTENT`) declarations.
- Support extents of nested DTO classes (each element serialized/deserialized via the DTO's properties).
- Maintain the existing 1-level deep recursion for nested DTOs in the non-extent case.

## Non-Goals

- Extents of extents (`CHARACTER EXTENT EXTENT 5`).
- Cycle detection across extent elements (would need a per-element visited set; not needed if DTO cycles are already broken at the property level).
- Streaming/large arrays (whole extent is materialized).
- OpenAPI changes (already correct).

## Design

### 1. DtoParser: capture EXTENT size

The existing regex `(?i)DEFINE\s+PUBLIC\s+PROPERTY\s+(\w+)\s+AS\s+(\w+(?:[.-]\w+)*)(?:\s+(EXTENT(?:\s+\d+)?))?` already captures `EXTENT` and optional size as `m.groups[3]?.value`. Parse the size into a nullable `Int`:

- Add to `DtoProperty`: `val extentSize: Int? = null`.
- Parse: if `m.groups[3]?.value != null`, take the trailing digits as Int (or null if just `EXTENT` with no size).

### 2. Request body: extent branch in `writeShim`

In the body-deserialization `when` block (~line 360 in `GenerateRouteTask.kt`), add an extent branch before the scalar switch:

```progress
IF oJson:Has("tags") AND NOT oJson:IsNull("tags") THEN DO:
    IF prop.isExtent THEN DO:
        DEFINE VARIABLE oArr AS JsonArray NO-UNDO.
        oArr = oJson:GetJsonArray("tags").
        DO i = 1 TO oArr:Length:
            oReq:tags[i] = oArr:GetCharacter(i).   /* or GetInteger / etc. */
        END.
    END.
    ELSE DO:
        /* existing scalar switch */
    END.
END.
```

For nested DTO extents:

```progress
IF prop.isExtent AND NOT <isScalarType> THEN DO:
    DEFINE VARIABLE oArr AS JsonArray NO-UNDO.
    oArr = oJson:GetJsonArray("addrs").
    DO i = 1 TO oArr:Length:
        oReq:addrs[i] = NEW Address().
        oReq:addrs[i]:city = oArr:GetJsonObject(i):GetCharacter("city").
        /* etc., per nested DTO property */
    END.
END.
```

Emit a `DEFINE VARIABLE i AS INTEGER NO-UNDO.` at the top of the body block (if not already declared).

### 3. Response: extent branch in `emitResponseJson`

Add a new branch in `emitResponseJson` (and its nested helper `emitNestedJson`):

```progress
if (prop.isExtent) {
    val propAccessor = "$oResultAccessor:${prop.name}"
    val propInfo = prop
    sb.append("\t\tDEFINE VARIABLE oArr_${prop.name} AS Progress.Json.ObjectModel.JsonArray NO-UNDO.\r\n")
    sb.append("\t\toArr_${prop.name} = NEW Progress.Json.ObjectModel.JsonArray().\r\n")
    sb.append("\t\tDEFINE VARIABLE i_${prop.name} AS INTEGER NO-UNDO.\r\n")
    sb.append("\t\tDO i_${prop.name} = 1 TO EXTENT($propAccessor):\r\n")
    if (isScalarType(prop.ablType)) {
        sb.append("\t\t\toArr_${prop.name}:Add($propAccessor[i_${prop.name}]).\r\n")
    } else if (prop.nested != null) {
        // Emit per-element JsonObject build with all nested props
        val itemObjName = "oItem_${prop.name}"
        sb.append("\t\t\tDEFINE VARIABLE $itemObjName AS Progress.Json.ObjectModel.JsonObject NO-UNDO.\r\n")
        sb.append("\t\t\t$itemObjName = NEW Progress.Json.ObjectModel.JsonObject().\r\n")
        prop.nested.properties.forEach { nestedProp ->
            // Emit $itemObjName:Add("nestedPropName", propAccessor[i]:nestedPropName)
            sb.append("\t\t\t$itemObjName:Add(\"${nestedProp.name}\", $propAccessor[i_${prop.name}]:${nestedProp.name}).\r\n")
        }
        sb.append("\t\t\toArr_${prop.name}:Add($itemObjName).\r\n")
    }
    sb.append("\t\tEND.\r\n")
    sb.append("\t\toJson:Add(\"${prop.name}\", oArr_${prop.name}).\r\n")
}
```

Scalar type detection uses the same set as `DtoParser`'s primitives.

### 4. OpenAPI: no changes

`mapAblType` already wraps extents in `type: array, items: ...`. Confirmed by inspection.

### 5. Testing

- **Parser unit:** DTO with `CHARACTER EXTENT 5` → `isExtent=true`, `extentSize=5`. With `CHARACTER EXTENT` → `isExtent=true`, `extentSize=null`. With `INTEGER` (no extent) → `isExtent=false`, `extentSize=null`.
- **Emission unit (request):** DTO with `tags AS CHARACTER EXTENT 3` body prop → shim contains `oJson:GetJsonArray("tags")` and a `DO` loop over `oArr:Length`. Negative assertion: no `oJson:GetCharacter("tags")` for extent prop.
- **Emission unit (response):** DTO with `tags AS CHARACTER EXTENT 3` response prop → shim contains `oArr_tags` JsonArray + `EXTENT(oResult:tags)` + `oArr_tags:Add(oResult:tags[i_tags])`.
- **Emission unit (nested extent):** DTO with `addrs AS com.example.Address EXTENT 2` → response shim builds per-item `oItem_addrs` with each Address property chained through extent index, attaches to array.
- **OpenAPI regression:** extent prop schema still emits `type: array, items: <scalar or ref>`.

### 6. Error handling

- If request body has a JSON array shorter than the fixed extent, ABL will leave the unused extent slots as `?` (or `INIT` value). The shim does not crash.
- If request body has a JSON array longer than the fixed extent, only the first N elements are read (loop bound = `oArr:Length` would overflow; use `MIN(EXTENT(oReq:tags), oArr:Length)` to be safe).
- If the nested DTO class is missing at gen time, `prop.nested == null` and the extent branch emits a `/* extent of unresolvable type */` comment.

## Files Changed

| File | Change |
| --- | --- |
| `src/main/kotlin/com/pyoif/tatara/DtoParser.kt` | Add `extentSize: Int?` to `DtoProperty`; parse it from regex group 3. |
| `src/main/kotlin/com/pyoif/tatara/GenerateRouteTask.kt` | Request body extent branch; response extent branch in `emitResponseJson` + `emitNestedJson`. |
| `src/test/kotlin/com/pyoif/tatara/DtoParserTest.kt` | EXTENT size parsing tests. |
| `src/test/kotlin/com/pyoif/tatara/GenerateRouteTaskEmitTest.kt` | Request extent, response extent, nested extent emission tests. |

## Out of Scope

- Extent of extent.
- Cycle detection in extent elements.
- Streaming/large arrays.
- OpenAPI changes.
