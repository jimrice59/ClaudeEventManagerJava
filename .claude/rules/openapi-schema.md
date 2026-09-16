---
paths:
  - "src/main/java/com/eventmanager/controller/**"
  - "src/main/java/com/eventmanager/config/OpenApiConfig.java"
---

# OpenAPI Schema

`springdoc-openapi-starter-webmvc-ui:2.5.0` is on the classpath. It scans all `@RestController` classes at startup and auto-generates an OpenAPI 3.0 schema. No code generation step is needed — the schema is produced at runtime from the live application.

**Endpoints (no auth required):**

| URL | Format | Notes |
|---|---|---|
| `http://localhost:8080/v3/api-docs` | JSON | Full OpenAPI 3.0 schema |
| `http://localhost:8080/v3/api-docs.yaml` | YAML | Same schema in YAML format |
| `http://localhost:8080/swagger-ui.html` | HTML | Interactive Swagger UI; supports "Try it out" |

Both `/v3/api-docs/**` and `/swagger-ui/**` are explicitly permitted in `SecurityConfig` so they are accessible without a token.

**What the schema captures automatically** (from Spring MVC metadata):
- All routes, HTTP methods, path parameters, and query parameters
- Request body shapes (from `@RequestBody` DTOs)
- Response body shapes (from declared return types)
- Bean validation constraints (`@NotBlank`, `@Min`, `@DecimalMax`, etc.) translated to JSON Schema keywords

**What the manual annotations add** (beyond what springdoc can infer):
- `@Tag` on each controller — groups endpoints into named sections in Swagger UI (Authentication, Events, Venues, Performers)
- `@Operation(summary, description)` on each method — human-readable summary line and longer description including auth requirements and business rules (e.g. ticket count limits, deduplication behaviour for video URLs)
- `@SecurityRequirement(name = "bearerAuth")` on write/admin endpoints — renders the lock icon in Swagger UI and wires to the `bearerAuth` scheme
- `@ApiResponse` per status code — documents 400/401/403/404 error cases and what triggers them (e.g. "Would exceed venue capacity" for 400 on release tickets)
- `@Parameter(description)` on path variables and query params — clarifies filter semantics (e.g. that `?name=` is a case-insensitive substring match while `?genre=` is an exact match)
- `@Schema(description, example)` on every DTO field — populates the "Example Value" panel and "Try it out" form with realistic data

**Security scheme:** `OpenApiConfig` registers a single `bearerAuth` HTTP Bearer JWT scheme. Clicking the **Authorize** button in Swagger UI and pasting a JWT from `POST /api/v1/auth/login` adds `Authorization: Bearer <token>` to all subsequent "Try it out" requests automatically.

**Downloading the schema** for use with code generators or API clients:
```bash
# JSON
curl http://localhost:8080/v3/api-docs -o openapi.json

# YAML
curl http://localhost:8080/v3/api-docs.yaml -o openapi.yaml
```
