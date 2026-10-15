# Exposing This Application's API via gRPC

This documents the implementation of `docs/grpc-service-exposure.md`'s design: gRPC as a third
transport alongside the REST API (`/api/v1/**`) and the Thymeleaf web UI (`/ui/**`), all three
calling the same `EventService`/`TicketService` beans. Not to be confused with the separate,
unrelated gRPC **client** this application runs to an external payment service — see
payment-service-grpc.md and docs/payment-service-integration.md.

## Scope

- `EventGrpcService`: `GetEvent`, `ListEvents` (all events, or filtered by `venue_id`),
  `GetAvailableTicketCount` — all public, no auth required.
- `TicketGrpcService`: `GetTicket` (public), `GetMyTickets`, `ReserveTicket`, `ReleaseTicket`,
  `PurchaseTicket`, `CancelTicket` (all require authentication).

**What's not exposed**: venue/performer CRUD, admin-only event writes
(create/update/delete), and performer video management. This mirrors the public-read-plus-
ownership-gated-ticket-actions slice `docs/grpc-service-exposure.md` originally sketched, not the
full REST surface — extending it to the rest of the API would mean adding the corresponding
proto messages/RPCs and (for the ADMIN-only routes) a role check, neither of which exists yet.

## Proto and codegen

`src/main/proto/event_manager_api.proto` defines both services, generating into Java package
`com.eventmanager.grpc.api` — the same `protobuf-maven-plugin` setup payment-service-grpc.md
documents (bound to `generate-sources`/`compile`, no manual codegen step). Request/response
messages mirror the existing REST DTOs field-for-field (`EventMessage` ≈ `EventResponse` with
nested `VenueMessage`/`PerformerMessage`; `TicketMessage` ≈ `TicketResponse`), with two
simplifications made deliberately to keep the proto self-contained:

- **Dates as ISO-8601 strings** (`LocalDateTime.toString()`), not `google.protobuf.Timestamp` — a
  real production API would likely prefer the typed well-known type, but a string matches the JSON
  API's own textual representation exactly and avoids importing another proto file for this scope.
- **Status fields as plain strings** (`event.getStatus().name()`/`ticket.getStatus().name()`), not
  proto enums — `EventStatus` and `TicketStatus` both declare an `AVAILABLE` value, and proto enums
  are flat/global by default within a file, so two same-named enum constants would collide without
  nesting each enum inside its own message. A string sidesteps that for this scope; a future
  revision could nest proto enums per-message if stronger typing is wanted.

`int64 user_id`/`payment_request_id` on `TicketMessage`, and `venue_id` on `ListEventsRequest`, use
proto3's `optional` keyword for explicit field presence (`hasUserId()`, etc.) rather than a
sentinel value — these are genuinely absent sometimes (`userId` is null while `AVAILABLE`;
`venueId` is an optional filter), and `optional` is the correct proto3 idiom for that, not just a
style preference.

## Server bootstrap

No `net.devh` (grpc-spring-boot-starter) dependency — this server is hand-built from plain
grpc-java, the same way `PaymentGrpcConfig` hand-builds the client side, keeping one fewer
third-party integration dependency and full control over startup/shutdown:

- `GrpcServerProperties` (`grpc-api.*`: `enabled`, default `true`; `port`, default `9091` —
  distinct from this app's own HTTP port 8080 and from the payment-service client's target port
  9095) — `GRPC_API_ENABLED`/`GRPC_API_PORT` env vars.
- `GrpcServerLifecycle` — a `SmartLifecycle` bean that builds a `NettyServerBuilder` server on
  `grpc-api.port`, registering `EventGrpcServiceImpl`/`TicketGrpcServiceImpl` each wrapped with
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
`GrpcCallSupport.runAuthenticated` — there's no gRPC equivalent of `@PreAuthorize`).

**The one genuinely tricky part**: `interceptCall()` only sets up the call's `Listener` and
returns immediately — the actual RPC method body runs later, in a callback on that listener,
potentially on a different thread than `interceptCall` ran on. A naive `SecurityContextHolder.set
(...)` inside `interceptCall` followed by a `finally` clear would clear the context *before* the
real method body ever sees it. The fix is gRPC's own `Context` (`io.grpc.Context`), which — unlike
a raw `ThreadLocal` — is correctly propagated across that async hop by grpc-java itself:
`JwtServerInterceptor` attaches the resolved `SecurityContext` as a `Context` value via
`Contexts.interceptCall(...)`, and `GrpcCallSupport.run`/`runAuthenticated` (which every RPC method
body runs through) reads it back out of `Context.current()` and only *then* copies it into
`SecurityContextHolder`, right as that specific call's body starts — clearing it again in a
`finally`, since grpc-java's executor threads are pooled and reused across unrelated calls. This
makes `TicketService.currentUserId()` (which reads `SecurityContextHolder` directly, with no idea
it might be called from gRPC) resolve the right caller without any change to `TicketService`
itself. `EventManagerApiGrpcTest.reserveTicket_succeedsWithValidTokenAndCallerIsResolvedCorrectly`
exists specifically to prove this bridge works against the real async dispatch, not just by
inspection.

## Error mapping

`GrpcExceptionMapper` is the gRPC-transport equivalent of `GlobalExceptionHandler`: the same
business exceptions `EventService`/`TicketService` already throw are reused as-is, just mapped to
`Status` codes instead of HTTP statuses — `ResourceNotFoundException` → `NOT_FOUND`,
`AccessDeniedException` → `PERMISSION_DENIED`, `IllegalArgumentException` (every ticket-status-
machine violation) → `FAILED_PRECONDITION`, `TicketPaymentInProgressException` (409-equivalent) →
`FAILED_PRECONDITION`, `PaymentServiceUnavailableException` (502-equivalent) → `UNAVAILABLE`, a
missing/invalid JWT on an authenticated-only RPC → `UNAUTHENTICATED`, anything else → `INTERNAL`.
`GrpcCallSupport.run`/`runAuthenticated` catch whatever the action throws and hand it to this
mapper, so no individual RPC method needs its own try/catch.

## Testing

`EventManagerApiGrpcTest` is a real `@SpringBootTest` (not `InProcessServerBuilder` — the thing
being tested is the real `GrpcServerLifecycle` wiring, so a real socket is more honest) with
`EventService`/`TicketService` as `@MockBean`s. It proves: a public RPC works with no token; an
authenticated RPC is rejected `UNAUTHENTICATED` with no token and with a garbage token; an
authenticated RPC succeeds with a real JWT (generated via the same `JwtTokenProvider` the REST API
uses) and resolves the correct caller inside `TicketService`; and a thrown business exception
arrives at the client as the right `Status` code. It does not test business logic — that's
`EventServiceTest`/`TicketServiceTest`'s job; this only proves the transport/auth/error-mapping
plumbing.

---

What's left if this needs to grow further: venue/performer CRUD, ADMIN-only event writes (would
need a role check — see authorization-model.md for how `@PreAuthorize("hasRole('ADMIN')")` does
this on the REST side — the gRPC equivalent would be another check inside `GrpcCallSupport` reading
`Authentication.getAuthorities()`), and TLS/client-auth on the server (currently plaintext,
matching every other local/dev-profile connection in this project).
