# gRPC API Support

This document describes this application's support for gRPC as a transport for its own domain
API — events, tickets, venues, and performers. It does not cover the separate, unrelated gRPC
**client** this application uses to call an external payment service during ticket purchase; see
`payment-service-grpc.md` and `docs/payment-service-integration.md` for that. Everything here is
about gRPC calls *into* this application, not calls this application makes *out*.

## Overview and calls

Alongside the REST API (`/api/v1/**`) and the Thymeleaf web UI (`/ui/**`), this application runs a
gRPC server on its own port (`9091` by default, independent of the HTTP port `8080`). It is started
and stopped automatically with the Spring application context (`GrpcServerLifecycle`, a
`SmartLifecycle` bean) and can be disabled entirely via `grpc-api.enabled=false` (it is disabled
under the test profile so `mvn test` never binds a real socket). The server is built directly on
plain grpc-java — no Spring Boot gRPC starter — registering four services, each a thin wrapper
around the same business services the REST controllers and web UI call.

Authentication reuses the application's existing custom JWT: a token obtained from
`POST /api/v1/auth/login` is sent as gRPC metadata (`authorization: Bearer <token>`) and validated
by `JwtServerInterceptor`, which performs the same validation `JwtAuthenticationFilter` does for
REST. No token is required for public calls; an invalid or expired token is rejected immediately.

### `EventGrpcService`

| RPC | Access | Description |
|---|---|---|
| `GetEvent` | public | Fetch one event by id |
| `ListEvents` | public | All events, or filtered by `venue_id` |
| `GetAvailableTicketCount` | public | Live count of `AVAILABLE` tickets for an event |
| `CreateEvent` | ROLE_ADMIN | Create an event (and its tickets) |
| `UpdateEvent` | ROLE_ADMIN | Update an event's mutable fields (no `tickets_total` — fixed at creation) |
| `DeleteEvent` | ROLE_ADMIN | Delete an event and archive its tickets |

### `TicketGrpcService`

| RPC | Access | Description |
|---|---|---|
| `GetTicket` | public | Fetch one ticket by id |
| `GetMyTickets` | authenticated | Paginated list of the caller's own tickets |
| `ReserveTicket` | authenticated | `AVAILABLE` → `RESERVED` |
| `ReleaseTicket` | authenticated | `RESERVED` → `AVAILABLE` |
| `PurchaseTicket` | authenticated | `RESERVED` → `PAYING` → `SOLD`/`AVAILABLE`, via the payment-service saga |
| `CancelTicket` | authenticated | `SOLD` → `AVAILABLE` |

Ticket actions are gated by ownership inside `TicketService` (the caller must be the user who
reserved/bought the ticket), not by role — the same rule the REST API enforces.

### `VenueGrpcService`

| RPC | Access | Description |
|---|---|---|
| `GetVenue` | public | Fetch one venue by id |
| `ListVenues` | public | All venues, or filtered by `city` |
| `CreateVenue` | ROLE_ADMIN | Create a venue |
| `UpdateVenue` | ROLE_ADMIN | Update a venue |
| `DeleteVenue` | ROLE_ADMIN | Delete a venue |

### `PerformerGrpcService`

| RPC | Access | Description |
|---|---|---|
| `GetPerformer` | public | Fetch one performer by id |
| `ListPerformers` | public | All performers, or filtered by `name` (substring) or `genre` (exact) |
| `CreatePerformer` | ROLE_ADMIN | Create a performer |
| `UpdatePerformer` | ROLE_ADMIN | Update a performer |
| `AddVideo` | ROLE_ADMIN | Add a video URL to a performer |
| `RemoveVideo` | ROLE_ADMIN | Remove a video URL from a performer |
| `DeletePerformer` | ROLE_ADMIN | Delete a performer |

That's 21 RPCs across 4 services — effectively the whole REST domain API, minus the `/api/v1/auth/**`
endpoints themselves (a JWT is obtained once over REST and reused for gRPC calls) and the OAuth2/OIDC
authorization-server endpoints, which are a protocol-level concern orthogonal to this application's
own domain model.

## gRPC vs. REST API support

Both transports expose the same domain operations, but the underlying mechanics differ in ways
that show up directly in how each is implemented here:

| | REST API | gRPC API |
|---|---|---|
| **Wire format** | JSON over HTTP/1.1 (via Jackson) | Binary Protocol Buffers over HTTP/2 |
| **Contract** | Implicit — DTOs document the shape; enforced only by Jackson at runtime | Explicit `.proto` schema (`event_manager_api.proto`, `venue_api.proto`, `performer_api.proto`); client/server stubs generated at build time |
| **Discoverability** | springdoc-openapi generates a Swagger UI / OpenAPI document automatically | No reflection service registered; the `.proto` files themselves are the API reference |
| **Request routing** | Spring MVC dispatches by HTTP method + path | grpc-java dispatches by fully-qualified RPC method name |
| **Auth transport** | `Authorization` HTTP header | `authorization` gRPC metadata entry — same bearer-token value, different carrier |
| **Auth enforcement** | Spring Security filter chain (`SecurityConfig`) + `@PreAuthorize` on controller methods | `JwtServerInterceptor` (one interceptor, all RPCs) + explicit `GrpcCallSupport.run`/`runAuthenticated`/`runAdmin` calls inside each RPC method — see "Dual support" below |
| **Error signaling** | HTTP status codes (404, 400, 403, 401, 409, 502, 500) + a JSON error body from `GlobalExceptionHandler` | gRPC `Status` codes (`NOT_FOUND`, `INVALID_ARGUMENT`, `PERMISSION_DENIED`, `UNAUTHENTICATED`, `FAILED_PRECONDITION`, `UNAVAILABLE`, `INTERNAL`) from `GrpcExceptionMapper` — same exceptions, different status vocabulary |
| **Input validation** | `@Valid` + Bean Validation annotations run automatically by Spring MVC | No automatic equivalent — `GrpcValidationSupport` runs the same `jakarta.validation.Validator` bean by hand against the mapped DTO before any write proceeds |
| **Nullability** | JSON `null` / absent field, handled by Jackson | proto3 `optional` keyword for genuinely-nullable fields (explicit presence via `hasX()`), since proto3 otherwise defaults every field to its zero value rather than `null` |
| **Pagination shape** | `PagedResponse<T>` JSON wrapper | A dedicated `TicketPageResponse` message with the same fields |
| **Streaming** | Not used (plain request/response) | Not used here either, though gRPC supports it natively — every RPC in this API is unary |
| **Browser access** | Works directly from any HTTP client, including a browser | Needs a gRPC-capable client (or a gRPC-Web proxy) — not directly callable from browser `fetch` |
| **Typical consumer** | Browsers, the Thymeleaf UI's own HTTP calls, simple scripting (`curl`), third-party HTTP integrations | Service-to-service calls from other backends, especially where the generated strongly-typed client (`EventGrpcServiceGrpc.EventGrpcServiceBlockingStub`, etc.) and lower per-call overhead matter |

Neither transport is a strict superset of the other operationally: REST remains the only way to
register/log in (`/api/v1/auth/**`) and the only transport the browser-based Thymeleaf UI and
`EventManagerClient` currently use; gRPC adds a second, equally complete way to drive the same
domain model for callers that want a typed schema and a binary wire format.

## How the controller/service split enables dual support

Neither `EventController` (REST) nor `EventGrpcServiceImpl` (gRPC) contains business logic. Both
are translation layers that convert their transport's request shape into a call on the same
`EventService` bean, and convert the result back into their transport's response shape:

```
HTTP JSON  → EventController       → EventService → EventResponse → JSON
gRPC proto → EventGrpcServiceImpl  → EventService → EventResponse → EventMessage
```

(`WebEventController`, the Thymeleaf UI's controller, follows the identical shape — this project
already had two controllers sharing one service layer before gRPC was added; gRPC is a third.)

This split is what makes adding a whole new transport additive rather than invasive:

- **All validation, authorization logic, caching, and persistence live in the service layer.**
  `EventService.createEvent` still does the same venue-capacity check, the same `@CachePut`, the
  same Cassandra dual-write, the same ticket-creation side effect, regardless of which controller
  called it. Adding gRPC required zero changes to any `*Service` class (`TicketService`'s
  `purchaseTicket` saga, `VenueService`, `PerformerService` — none of them were touched).
- **Each transport owns only its own request/response mapping and transport-specific concerns.**
  `EventGrpcServiceImpl` maps `CreateEventRequest` ↔ `EventRequest`/`EventResponse` ↔ `EventMessage`
  and nothing else business-related; `EventController` does the equivalent JSON mapping (mostly
  implicit, via Jackson). Fields that don't exist on one side (gRPC's `UpdateEventRequest` has no
  `tickets_total`; proto3 has no native decimal/date type, so `ticket_price`/`event_date` travel as
  strings) are handled entirely inside the translation layer, never by changing the service.
- **Each transport reimplements the cross-cutting concerns Spring gives REST for free.** Spring
  Security's filter chain + `@PreAuthorize` has no gRPC equivalent, so `JwtServerInterceptor` +
  `GrpcCallSupport.run`/`runAuthenticated`/`runAdmin` exist specifically to recreate
  "authenticate the caller" and "require `ROLE_ADMIN`" for gRPC, landing on the same
  `SecurityContextHolder`-based `Authentication` that `TicketService.currentUserId()` already
  reads — so that method needed no gRPC-awareness at all. Likewise `@Valid` has no gRPC
  equivalent, so `GrpcValidationSupport` runs the real `Validator` bean by hand, and
  `GlobalExceptionHandler` has no gRPC equivalent, so `GrpcExceptionMapper` translates the same
  thrown exceptions to `Status` codes instead of HTTP statuses. In every case, the thing being
  reused is the exact same object Spring already builds for REST (the same `Authentication`, the
  same `Validator` bean, the same exception types) — only the code that produces or consumes it at
  the transport boundary is new.
- **One source of truth for "what can go wrong."** Because both transports throw/catch the same
  exception types (`ResourceNotFoundException`, `AccessDeniedException`,
  `TicketPaymentInProgressException`, etc.), a new failure mode added to a service (say, a new
  validation rule in `EventService.createEvent`) is automatically visible on both transports
  without either translation layer needing to know about it specifically — `GlobalExceptionHandler`
  and `GrpcExceptionMapper` already have a mapping for that exception's type.

The net effect: this project went from two transports sharing a service layer (REST, Web UI) to
three (REST, Web UI, gRPC) by adding translation-only code, with the service layer — where the
actual business rules live — completely unaware a second RPC-style transport exists.
