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

### 1. Request DTO (`@Request`)

One DTO per endpoint. Lives in `app/ports/`. Contains three nested classes:

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

**Handler signature (new):**
```abl
// @GET("/api/budget/{budgetId}/projects")
// @Request(App.Ports.BudgetRequest)
// @Response(200, App.Ports.BudgetProjectsResult)
METHOD PUBLIC App.Ports.BudgetProjectsResult GetProjects(
    INPUT poRequest AS App.Ports.BudgetRequest):
```

**Shim codegen:**
```abl
oReq = NEW App.Ports.BudgetRequest().
oPath = NEW App.Ports.BudgetRequest.PathSection().
oPath:budgetId = poWebRequest:GetPathParam("budgetId").  // from {budgetId}
oQuery = NEW App.Ports.BudgetRequest.QuerySection().
oQuery:divisionCode = poWebRequest:GetQueryParam("divisionCode").
IF oQuery:divisionCode = ? THEN RETURN 400.  // @Required
oQuery:page = INTEGER(poWebRequest:GetQueryParam("page")).
IF oQuery:page = ? THEN oQuery:page = 1.  // no @Required, optional
oBody = NEW App.Ports.BudgetRequest.BodySection().
oReq:path = oPath. oReq:query = oQuery. oReq:body = oBody.
ctrl0:GetProjects(INPUT oReq, ...).
```

### 2. Response DTO (`@Response`)

Handler returns typed DTO. Shim serializes it to JSON response.

```abl
// app/ports/BudgetProjectsResult.cls
CLASS App.Ports.BudgetProjectsResult:
    DEFINE PUBLIC PROPERTY data  AS App.Ports.ProjectInfo EXTENT NO-UNDO GET. SET.
    DEFINE PUBLIC PROPERTY total AS INTEGER NO-UNDO GET. SET.
END CLASS.
```

**Multiple `@Response` per method:**
```abl
// @Response(200, App.Ports.ContractResult)
// @Response(401, App.Ports.ForbiddenResponse)   // overrides global error schema
METHOD PUBLIC App.Ports.ContractResult GetDetail(...)
```

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

- Source: unpkg `swagger-ui-dist` (pure HTML/JS, no build)
- Extracted to `pasoeTemplate/docs/swagger/` at plugin build time
- Bundled in WAR by `PackageWarTask` at `docs/swagger/`
- `swagger-initializer.js` points to `/api/swagger.json`
- Accessible at `http://host/docs/swagger/`

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
- Build `components/schemas` from all `@Response` types + `@Request` types

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
- `GenerateRouteTask.kt` — extend route metadata with request/response type info, pass to OpenAPI task
- `GenerateOpenApiTask.kt` — new task: read DTO classes, annotations, generate swagger.json
- `TataraPlugin.kt` — register `GenerateOpenApiTask`, wire dependencies
- `RouteShimTemplate.cls` — update shim template: typed DTO construction, error catch blocks
- `pasoeTemplate/docs/swagger/` — swagger-ui-dist static files
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

- [ ] `@Request(App.Ports.X)` annotation parsed, shim constructs typed DTO
- [ ] `@Required` properties validated in shim before handler call
- [ ] `@Response(200, App.Ports.Y)` generates typed return and OpenAPI schema
- [ ] Handler exceptions caught by shim, mapped to correct HTTP codes
- [ ] `GenerateOpenApiTask` produces valid swagger.json
- [ ] Swagger UI served at `/docs/swagger/` in WAR
- [ ] Full pipeline: generateOpenApi → compileABL → packageWar passes
- [ ] Load the Swagger UI and see all endpoints documented
