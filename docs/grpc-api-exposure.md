# Exposing This Application's API via gRPC

This documents the implementation of `docs/grpc-service-exposure.md`'s design: gRPC as a third
transport alongside the REST API (`/api/v1/**`) and the Thymeleaf web UI (`/ui/**`), all three
calling the same `EventService`/`TicketService`/`VenueService`/`PerformerService` beans. Not to be
confused with the separate, unrelated gRPC **client** this application runs to an external payment
service — see payment-service-grpc.md and docs/payment-service-integration.md.

## Scope

- `EventGrpcService`: `GetEvent`, `ListEvents` (all events, or filtered by `venue_id`),
  `GetAvailableTicketCount` (public); `CreateEvent`, `UpdateEvent`, `DeleteEvent` (`ROLE_ADMIN`).
- `TicketGrpcService`: `GetTicket` (public); `GetMyTickets`, `ReserveTicket`, `ReleaseTicket`,
  `PurchaseTicket`, `CancelTicket` (authenticated — ownership-gated inside `TicketService`, not
  role-gated, matching `TicketController`).
- `VenueGrpcService`: `GetVenue`, `ListVenues` (optional `city` filter) (public); `CreateVenue`,
  `UpdateVenue`, `DeleteVenue` (`ROLE_ADMIN`).
- `PerformerGrpcService`: `GetPerformer`, `ListPerformers` (optional `name`/`genre` filter, `name`
  wins if both set) (public); `CreatePerformer`, `UpdatePerformer`, `AddVideo`, `RemoveVideo`,
  `DeletePerformer` (`ROLE_ADMIN`).

This is now the full REST API surface, minus auth (`/api/v1/auth/**` has no gRPC equivalent — a
JWT from `POST /api/v1/auth/login` is reused as-is) and the OAuth2/OIDC authorization-server
endpoints (`/oauth2/**`, `/.well-known/**`), which are a protocol-level concern orthogonal to this
application's own domain API.

## Proto and codegen

Three proto files, all in proto package `eventmanager.api.v1` / Java package
`com.eventmanager.grpc.api` (the same `protobuf-maven-plugin` setup payment-service-grpc.md
documents — bound to `generate-sources`/`compile`, no manual codegen step):

- `event_manager_api.proto` — `EventGrpcService`, `TicketGrpcService`, and the shared
  `VenueMessage`/`PerformerMessage` types `venue_api.proto`/`performer_api.proto` import.
- `venue_api.proto` — `VenueGrpcService`.
- `performer_api.proto` — `PerformerGrpcService`.

Cross-file message references need an explicit `import` even within the same proto package —
proto's import model is per-file, not per-package — so `venue_api.proto`/`performer_api.proto`
each `import "event_manager_api.proto";` to reach `VenueMessage`/`PerformerMessage`. All three also
`import "google/protobuf/empty.proto";` for delete-RPC responses (`google.protobuf.Empty` — the
standard well-known type, zero extra maintenance, resolved automatically from the bundled protoc
distribution).

Request/response messages mirror the existing REST DTOs field-for-field (`EventMessage` ≈
`EventResponse`; `VenueMessage` ≈ `VenueDto`; `PerformerMessage` ≈ `PerformerDto`; `TicketMessage`
≈ `TicketResponse`), with a few deliberate simplifications to keep the proto self-contained:

- **Dates as ISO-8601 strings** (`LocalDateTime.toString()`), not `google.protobuf.Timestamp` — a
  real production API would likely prefer the typed well-known type, but a string matches the JSON
  API's own textual representation exactly and avoids importing another proto file for this scope.
  A malformed one on a write RPC surfaces as `INVALID_ARGUMENT` (`DateTimeParseException`, mapped
  in `GrpcExceptionMapper`), not `INTERNAL`.
- **`ticket_price` as a decimal string**, same reasoning; a malformed one is `INVALID_ARGUMENT` via
  `NumberFormatException`.
- **Status fields as plain strings** (`event.getStatus().name()`/`ticket.getStatus().name()`), not
  proto enums — `EventStatus` and `TicketStatus` both declare an `AVAILABLE` value, and proto enums
  are flat/global by default within a file, so two same-named enum constants would collide without
  nesting each enum inside its own message. A string sidesteps that for this scope.

Genuinely-optional fields (`user_id`/`payment_request_id` on `TicketMessage`; `zip_code` on
`VenueMessage`; `genre`/`bio` on `PerformerMessage`; `venue_id`/`city`/`name`/`genre` as list
filters) use proto3's `optional` keyword for explicit presence (`hasZipCode()`, etc.) rather than a
sentinel value or treating absence as empty-string — mapping code only calls the builder's setter
when the underlying DTO field is non-null, so `hasX()` genuinely means "was set," not "was set to
something non-default."

**`UpdateEventRequest` deliberately omits `tickets_total`**, unlike `CreateEventRequest`:
`EventService.updateEvent` already silently ignores that field even when present in the REST
`EventRequest` body (it's immutable after creation), so this new surface simply doesn't offer a
field that would be dropped — a conscious choice not to replicate an existing REST-side rough
edge into a brand-new wire format. `EventGrpcServiceImpl.updateEvent` fills a placeholder `0` into
the mapped `EventRequest` DTO's `ticketsTotal` purely so the shared `@NotNull @Min(0)` Bean
Validation constraint passes — it's never read by `updateEvent`.

## Server bootstrap

No `net.devh` (grpc-spring-boot-starter) dependency — this server is hand-built from plain
grpc-java, the same way `PaymentGrpcConfig` hand-builds the client side, keeping one fewer
third-party integration dependency and full control over startup/shutdown:

- `GrpcServerProperties` (`grpc-api.*`: `enabled`, default `true`; `port`, default `9091` —
  distinct from this app's own HTTP port 8080 and from the payment-service client's target port
  9095) — `GRPC_API_ENABLED`/`GRPC_API_PORT` env vars.
- `GrpcServerLifecycle` — a `SmartLifecycle` bean that builds a `NettyServerBuilder` server on
  `grpc-api.port`, registering all four `*GrpcServiceImpl` beans, each wrapped with
  `JwtServerInterceptor` via `ServerInterceptors.intercept`. Starts/stops automatically with the
  Spring context — no manual lifecycle calls needed anywhere. `grpc-api.enabled: false` in
  application-test.yml means `mvn test` never binds a real socket (the same reason
  `rate-limit.enabled` is false there too) — except `EventManagerApiGrpcTest`, which overrides it
  back to `true` with `grpc-api.port=0` (OS-assigned, avoiding port clashes) specifically to test
  the real server.

## Authentication — the part with no REST equivalent to lean on

None of the servlet filter chain (`RateLimitFilter`, `JwtAuthenticationFilter`, the `@Order`
chains — see architecture-overview.md) sees gRPC traffic; the gRPC server is a separate Netty
listener on its own port. `JwtServerInterceptor` is the entire auth layer: it reads an
`authorization: Bearer <jwt>` gRPC metadata header, validates it with the exact same
`JwtTokenProvider`/`UserDetailsServiceImpl` the REST filter uses (same custom HS256 JWT — a token
from `POST /api/v1/auth/login` works here unchanged), and is deliberately permissive at the
transport layer (no token at all is not rejected, since public RPCs must still work anonymously)
but strict at the call layer (each RPC that needs authentication checks for it itself, via
`GrpcCallSupport.runAuthenticated`/`runAdmin` — there's no gRPC equivalent of `@PreAuthorize`).

**The one genuinely tricky part**: `interceptCall()` only sets up the call's `Listener` and
returns immediately — the actual RPC method body runs later, in a callback on that listener,
potentially on a different thread than `interceptCall` ran on. A naive `SecurityContextHolder.set
(...)` inside `interceptCall` followed by a `finally` clear would clear the context *before* the
real method body ever sees it. The fix is gRPC's own `Context` (`io.grpc.Context`), which — unlike
a raw `ThreadLocal` — is correctly propagated across that async hop by grpc-java itself:
`JwtServerInterceptor` attaches the resolved `SecurityContext` as a `Context` value via
`Contexts.interceptCall(...)`, and `GrpcCallSupport.run`/`runAuthenticated`/`runAdmin` (which every
RPC method body runs through) reads it back out of `Context.current()` and only *then* copies it
into `SecurityContextHolder`, right as that specific call's body starts — clearing it again in a
`finally`, since grpc-java's executor threads are pooled and reused across unrelated calls. This
makes `TicketService.currentUserId()` (which reads `SecurityContextHolder` directly, with no idea
it might be called from gRPC) resolve the right caller without any change to `TicketService`
itself. `EventManagerApiGrpcTest.reserveTicket_succeedsWithValidTokenAndCallerIsResolvedCorrectly`
exists specifically to prove this bridge works against the real async dispatch, not just by
inspection.

**ROLE_ADMIN gating**: `GrpcCallSupport.runAdmin` builds on top of `runAuthenticated`, so the
precedence matches the REST API exactly — no token at all → `UNAUTHENTICATED` (the inner
`runAuthenticated` check runs first); a valid token but the wrong role → `AccessDeniedException` →
`PERMISSION_DENIED` (reusing the exact exception type and mapping `TicketService`'s ownership
checks already use, rather than inventing a second "access denied" concept). This was a real design
choice among a few options (discussed and rejected: a central method-name→role policy map in the
interceptor, mirroring `SecurityConfig`'s filter-chain rules, rejected because the string keys
aren't compile-checked against real RPCs; a custom `@GrpcAdminOnly` annotation read via reflection
or a dynamic proxy, rejected as more machinery than two new service classes justify, and closer to
reintroducing the `net.devh` starter's own approach, which was deliberately avoided) — `runAdmin`
was chosen because it's consistent with the already-shipped `run`/`runAuthenticated` pattern and
needs zero new exception-mapping code.

## Validation — proto3 has no `@Valid` equivalent

Bean Validation (`@NotBlank`, `@Positive`, `@DecimalMin`, `@URL`, etc. on `VenueDto`/
`PerformerDto`/`EventRequest`) is enforced on the REST API only via `@Valid` at the controller
boundary — the services themselves trust the DTO is already validated. proto3 has no field
constraint mechanism of its own, so every Create/Update RPC maps its proto message to the same REST
DTO and runs it through `GrpcValidationSupport.validate(validator, dto)` — a thin wrapper around
the real `jakarta.validation.Validator` bean (auto-configured by `spring-boot-starter-validation`)
that throws `ConstraintViolationException` on failure, mapped by `GrpcExceptionMapper` to
`INVALID_ARGUMENT`. Without this, a malformed create/update request would reach
`VenueService`/`PerformerService`/`EventService` completely unchecked.

## Error mapping

`GrpcExceptionMapper` is the gRPC-transport equivalent of `GlobalExceptionHandler`: the same
business exceptions `EventService`/`TicketService`/`VenueService`/`PerformerService` already throw
are reused as-is, just mapped to `Status` codes instead of HTTP statuses:

| Exception | Status | REST equivalent |
|---|---|---|
| `ResourceNotFoundException` | `NOT_FOUND` | 404 |
| `AccessDeniedException` | `PERMISSION_DENIED` | 403 |
| `ConstraintViolationException` | `INVALID_ARGUMENT` | 400 (`@Valid`) |
| `DateTimeParseException` / `NumberFormatException` | `INVALID_ARGUMENT` | 400 |
| `IllegalArgumentException` (ticket-status-machine violation) | `FAILED_PRECONDITION` | 400 |
| `TicketPaymentInProgressException` | `FAILED_PRECONDITION` | 409 |
| `PaymentServiceUnavailableException` | `UNAVAILABLE` | 502 |
| `GrpcUnauthenticatedException` (missing/invalid JWT) | `UNAUTHENTICATED` | 401 |
| anything else | `INTERNAL` | 500 |

`GrpcCallSupport.run`/`runAuthenticated`/`runAdmin` catch whatever the action throws and hand it to
this mapper, so no individual RPC method needs its own try/catch.

## Testing

`EventManagerApiGrpcTest` is a real `@SpringBootTest` (not `InProcessServerBuilder` — the thing
being tested is the real `GrpcServerLifecycle` wiring, so a real socket is more honest) with all
four business services (`EventService`/`TicketService`/`VenueService`/`PerformerService`) as
`@MockBean`s. It mints both a `ROLE_USER` and a `ROLE_ADMIN` JWT via the real `JwtTokenProvider`/
`UserDetailsServiceImpl` beans (inserting each test user into H2 if missing) and proves: a public
RPC works with no token; an authenticated RPC is rejected `UNAUTHENTICATED` with no token and with
a garbage token; a real `ROLE_USER` JWT succeeds on an authenticated RPC and resolves the correct
caller inside `TicketService`; an admin-only RPC is rejected `PERMISSION_DENIED` for a `ROLE_USER`
token and `UNAUTHENTICATED` for no token, and succeeds for the `ROLE_ADMIN` token; a thrown business
exception (including `ConstraintViolationException` from a deliberately-blank required field)
arrives at the client as the right `Status` code. It does not test business logic — that's
`EventServiceTest`/`VenueServiceTest`/`PerformerServiceTest`/`TicketServiceTest`'s job; this only
proves the transport/auth/validation/error-mapping plumbing.

---

What's left if this needs to grow further: TLS/client-auth on the server (currently plaintext,
matching every other local/dev-profile connection in this project), and a reconciliation sweep for
stuck-`PAYING` tickets (unrelated to the gRPC surface itself — see
docs/payment-service-integration.md).
