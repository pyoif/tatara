# Swagger Integration with Typed Request/Response DTOs

**Branch:** `feat/oe-dot-compile-task`
**Date:** 2026-07-07
**Status:** draft

## Motivation

Current handlers accept generic `RequestContext` + `ResponseContext` and build JSON manually. No API documentation exists. Adding Swagger requires:

1. Typed request DTOs so task can generate parameter schemas
2. Typed response DTOs so task can generate response schemas
3. Structured error handling for non-200 responses
4. OpenAPI JSON generation at build time
5. Swagger UI served from the WAR

## Design

### 1. Request DTO (inferred from method signature)

No `@Request` annotation needed. The request DTO type is inferred directly from the method's INPUT parameter type. One DTO per endpoint. Lives in `app/ports/`. Contains three nested classes:

- **PathSection** — properties matching `{param}` in route → filled from URL path
- **QuerySection** — all other scalar properties → filled from query string
- **BodySection** — filled from request body (JSON parsed by shim)

```abl
// app/ports/BudgetRequest.cls
CLASS App.Ports.BudgetRequest:
    DEFINE PUBLIC PROPERTY path  AS PathSection  NO-UNDO GET. SET.
    DEFINE PUBLIC PROPERTY query AS QuerySection NO-UNDO GET. SET.
    DEFINE PUBLIC PROPERTY body  AS BodySection  NO-UNDO GET. SET.

    CLASS PathSection:
        DEFINE PUBLIC PROPERTY budgetId AS CHARACTER NO-UNDO GET. SET.
    END CLASS.

    CLASS QuerySection:
        // @Required
        DEFINE PUBLIC PROPERTY divisionCode AS CHARACTER NO-UNDO GET. SET.
        DEFINE PUBLIC PROPERTY page        AS INTEGER   NO-UNDO GET. SET.
    END CLASS.

    CLASS BodySection:
        DEFINE PUBLIC PROPERTY filters AS LONGCHAR NO-UNDO GET. SET.
    END CLASS.
END CLASS.
```

**Mapping rules:**
- `{param}` in route → if PathSection has matching property name → path param. Otherwise → QuerySection.
- BodySection properties → request body (JSON deserialized by shim).
- `@Required` annotation on property → shim returns 400 before calling handler if missing.

**Inference rules:**

| What | Inferred From | Fallback |
|------|--------------|----------|
| Request DTO type | Method INPUT parameter type (`INPUT poReq AS App.Ports.SomeRequest`) | No INPUT param or INPUT type is `Tatara.Api.RequestContext` → zero-parameter handler (no typed DTO, backwards compat) |
| 200 response schema | Method return type | `VOID` return type → no response body |
| Non-200 response schemas | `@Response(code, Type)` annotation (optional) | Global `Tatara.Api.ErrorResponse` for all unlisted error codes |

**Handler signature (new):**
```abl
// @GET("/api/budget/{budgetId}/projects")
// @Response(401, App.Ports.ForbiddenResponse)
METHOD PUBLIC App.Ports.BudgetProjectsResult GetProjects(
    INPUT poRequest AS App.Ports.BudgetRequest):
```

The task infers:
- Request DTO: `App.Ports.BudgetRequest` (from `INPUT ... AS App.Ports.BudgetRequest`)
- 200 response: `App.Ports.BudgetProjectsResult` (from return type)
- 401 response: `App.Ports.ForbiddenResponse` (from `@Response(401,...)` — optional override)
- All other errors: global `Tatara.Api.ErrorResponse`

**Handler variants:**

Zero-param handler (no request DTO):
```abl
// @GET("/api/health")
METHOD PUBLIC App.Ports.HealthResult CheckHealth():
```

VOID handler (no response body):
```abl
// @DELETE("/api/item/{id}")
METHOD PUBLIC VOID DeleteItem(
    INPUT poRequest AS App.Ports.DeleteItemRequest):
```

Backwards compat (old-style RequestContext):
```abl
// @GET("/api/legacy")
METHOD PUBLIC VOID LegacyHandler(
    INPUT oContext AS Tatara.Api.RequestContext):
```

**Shim codegen:**
```abl
// Build typed request DTO from IWebRequest
oPath = NEW App.Ports.BudgetRequest.PathSection().
oPath:budgetId = poRequest:GetPathParameter("budgetId").
oQuery = NEW App.Ports.BudgetRequest.QuerySection().
oQuery:divisionCode = poRequest:GetQueryParameter("divisionCode").
IF oQuery:divisionCode = ? THEN
    RETURN 400.  // @Required
oQuery:page = INTEGER(poRequest:GetQueryParameter("page")).
IF oQuery:page = ? THEN oQuery:page = 1.  // optional

// Parse JSON body
IF VALID-OBJECT(poRequest:Entity) THEN
    oBody = CAST(poRequest:Entity, Progress.Lang.Object):ToString().
ELSE
    oBody = "".

// Assemble
ASSIGN
    oReq:path = oPath
    oReq:query = oQuery
    oReq:body = oBody.

// Call handler with typed INPUT, capture typed return
ctrl0 = NEW App.Ports.BudgetController().
oResult = ctrl0:GetProjects(INPUT oReq)
    CATCH err AS Tatara.Api.ForbiddenError:
        oResponse:StatusCode = 403.
        oResponse:Entity = NEW App.Ports.ForbiddenResponse(err:GetMessage()).
        RETURN 0.
    CATCH err AS Tatara.Api.ApiError:
        oResponse:StatusCode = err:HttpCode.
        oResponse:Entity = NEW Tatara.Api.ErrorResponse(err:GetMessage()).
        RETURN 0.
    CATCH err AS Progress.Lang.AppError:
        oResponse:StatusCode = 500.
        oResponse:Entity = NEW Tatara.Api.ErrorResponse(err:GetMessage()).
        RETURN 0.
    END CATCH.

oResponse:StatusCode = 200.
oResponse:Entity = oResult.  // typed return serialized by shim
RETURN 0.
```

### 2. Response DTO (inferred + optional `@Response` for errors)

Handler returns typed DTO directly — the 200 response schema is inferred from the method's return type. Shim serializes it to JSON response.

```abl
// app/ports/BudgetProjectsResult.cls
CLASS App.Ports.BudgetProjectsResult:
    DEFINE PUBLIC PROPERTY data  AS App.Ports.ProjectInfo EXTENT NO-UNDO GET. SET.
    DEFINE PUBLIC PROPERTY total AS INTEGER NO-UNDO GET. SET.
END CLASS.
```

**`@Response` only for non-200 codes (optional):**
```abl
// @Response(401, App.Ports.ForbiddenResponse)   // overrides global ErrorResponse for 401
// @Response(403, App.Ports.ForbiddenResponse)   // multiple non-200 codes supported
METHOD PUBLIC App.Ports.ContractResult GetDetail(
    INPUT poRequest AS App.Ports.ContractRequest):
```

- `@Response(200, Type)` is NOT written. The return type already says this.
- `@Response(non-200, Type)` is optional. When absent, that status code falls back to global `Tatara.Api.ErrorResponse` schema.
- Multiple `@Response(non-200,...)` annotations on same method → each overrides a specific status code.

### 3. Error Handling

Bundled exception classes in `Tatara.Api`:
- `Tatara.Api.BadRequestError` → 400
- `Tatara.Api.UnauthorizedError` → 401
- `Tatara.Api.ForbiddenError` → 403
- `Tatara.Api.NotFoundError` → 404
- `Tatara.Api.ConflictError` → 409
- `Tatara.Api.InternalError` → 500

**Shim catches typed exceptions:**
```abl
ctrl0:GetProject(INPUT oReq, INPUT-OUTPUT poResponse)
    CATCH err AS Tatara.Api.NotFoundError:
        poResponse:HttpCode = 404.
        poResponse:Body = NEW Tatara.Api.ErrorResponse(err:Message):ToJson().
    CATCH err AS Tatara.Api.ApiError:
        poResponse:HttpCode = err:HttpCode.
        poResponse:Body = NEW Tatara.Api.ErrorResponse(err:Message):ToJson().
    END CATCH.
```

Global OpenAPI: all non-200 status codes → `ErrorResponse` schema by default. Per-route `@Response(401, customType)` overrides for specific codes.

### 4. Swagger UI

- Single `swagger/index.html` (already exists at repo root) — loads Swagger UI from unpkg CDN
- Copied to `pasoeTemplate/docs/swagger/index.html` at WAR build time
- `url` in the HTML points to `/api/swagger.json` (the generated OpenAPI JSON served from WAR)
- Bundled in WAR by `PackageWarTask` at `docs/swagger/`
- Accessible at `http://host/docs/swagger/`

```html
<!-- swagger/index.html -->
<script>
  window.onload = () => {
    window.ui = SwaggerUIBundle({
      url: '/api/swagger.json',
      dom_id: '#swagger-ui',
      presets: [SwaggerUIBundle.presets.apis, SwaggerUIStandalonePreset],
      layout: "StandaloneLayout",
    });
  };
</script>
```

### 5. OpenAPI JSON Generation

New `GenerateOpenApiTask` (runs after `GenerateRouteTask`, before `compileABL`).

**Inputs:**
- Route definitions: `GenerateRouteTask` writes route metadata (`.handlers` + route metadata)
- Annotated handler source files in `packagedDir` (prefixed ABL)
- DTO port classes in `packagedDir/app/ports/`

**Output:** `build/generated/openapi/swagger.json`

**ABL → OpenAPI type mapping:**

| ABL type | OpenAPI |
|----------|---------|
| `CHARACTER` | `{ type: "string" }` |
| `INTEGER` | `{ type: "integer" }` |
| `DECIMAL` | `{ type: "number" }` |
| `LOGICAL` | `{ type: "boolean" }` |
| `LONGCHAR` | `{ type: "string" }` |
| `DATETIME` | `{ type: "string", format: "date-time" }` |
| `EXTENT` / `EXTENT N` | `{ type: "array" }` |
| Custom CLASS | `{ $ref: "#/components/schemas/<ClassName>" }` |

**Schema generation from DTO class:**
- Walk CLASS property definitions
- Map each property's type → OpenAPI schema
- Nested CLASS → inline schema or `$ref`
- Build `components/schemas` by collecting:
  - Method return types (200 response schemas)
  - Method INPUT param types (request DTO schemas)
  - Types from `@Response(non-200, Type)` annotations (error response schemas)
  - Global `Tatara.Api.ErrorResponse` (fallback error schema)

**Generated swagger.json includes:**
- `info` block (title, version from gradle properties)
- `servers` (single entry, host from config)
- `paths` entries for each route with parameters, request body, responses
- `components/schemas` for all referenced DTO types

### 6. Propath & Compilation

Generated OpenAPI JSON at `build/generated/openapi/swagger.json` → WAR step picks it up from build output. Swagger UI files at `pasoeTemplate/docs/swagger/` → included in WAR via existing `packageClasspathResources`.

No propath change needed — OpenAPI generation is build-time only.

### 7. Files Changed

**Tatara plugin:**
- `GenerateRouteTask.kt` — extend RouteDef with `requestDtoClassName`, `responseDtoClassName`, `errorResponses`; parse INPUT type + return type from method signature; parse optional `@Response(non-200, Type)` annotation; update shim codegen for typed DTO construction + error catch blocks
- `GenerateOpenApiTask.kt` — new: read DTO classes, method signatures/annotations, generate swagger.json
- `TataraPlugin.kt` — register `GenerateOpenApiTask`, copy `swagger/index.html` to WAR resources
- `RouteShimTemplate.cls` — update shim template: typed DTO construction, error catch blocks
- `swagger/index.html` — update `url` to `/api/swagger.json`
- `src/main/resources/Tatara/Api/` — add error classes (ApiError, BadRequestError, NotFoundError, etc.)

**Backend:**
- `build.gradle.kts` — register `GenerateOpenApiTask`, add to build pipeline
- `app/handlers/*.cls` — refactor: request/response annotations, remove manual JSON, return DTO
- `app/ports/*.cls` — new: request/response DTO per endpoint
- `app/common/` — simplify: only ServiceFactory, JsonHelper remain (handler code moves to DTOs)

## Error handling

- Missing required query param → shim returns 400 before calling handler
- Route mismatch → PASOE returns 404 natively
- Handler throws `ApiError` subclass → shim catches, writes JSON error
- Uncaught exception → shim returns 500 with stack trace (dev) or generic error (prod)

## Testing

**Manual verification:**
1. `./gradlew generateOpenApi` — verify `build/generated/openapi/swagger.json`
2. Open in Swagger Editor — validate schema
3. `./gradlew compileABL` — verify typed shims compile
4. `./gradlew packageWar` — verify `docs/swagger/` in WAR
5. Deploy WAR, browse `/docs/swagger/`, test endpoints

**Regression checks:**
- All 18 existing endpoints still work with new handler signatures
- Error responses match OpenAPI schema
- `packageWar` still produces valid PASOE WAR

## Acceptance criteria

- [ ] Request DTO type inferred from method INPUT param type — no separate annotation needed
- [ ] 200 response schema inferred from method return type — no separate annotation needed
- [ ] Zero-param handlers (no INPUT) generate shim with no DTO construction
- [ ] VOID-return handlers generate shim with no response body
- [ ] Backwards compat: `RequestContext`-style handlers still generate correctly
- [ ] `@Response(non-200, Type)` optional, overrides global ErrorResponse for that status code; absent codes fall back to ErrorResponse
- [ ] `@Required` properties validated in shim before handler call
- [ ] Handler exceptions caught by shim, mapped to correct HTTP codes
- [ ] `GenerateOpenApiTask` produces valid swagger.json from method signatures + DTO classes
- [ ] Swagger UI served at `/docs/swagger/` in WAR using `swagger/index.html`
- [ ] `swagger/index.html` points to `/api/swagger.json`
- [ ] Full pipeline: generateRoutes → generateOpenApi → compileABL → packageWar passes
