---
paths:
  - "src/main/proto/event_manager_api.proto"
  - "src/main/java/com/eventmanager/grpcserver/**"
  - "src/test/java/com/eventmanager/grpcserver/**"
---

# gRPC API Server

This application's own gRPC server — a third transport alongside the REST API (`/api/v1/**`) and
the Thymeleaf web UI (`/ui/**`), all three calling the same `EventService`/`TicketService` beans.
Full design in docs/grpc-api-exposure.md; this file is the code map. Not to be confused with
payment-service-grpc.md, which is this application acting as a gRPC *client* to a separate,
external payment service — unrelated feature, opposite direction.

## Proto and codegen

`src/main/proto/event_manager_api.proto` → generates into `com.eventmanager.grpc.api` (distinct
from `com.eventmanager.grpc.payment`, the payment-service client's generated package). Same
`protobuf-maven-plugin` setup as payment-service-grpc.md describes — no separate codegen step,
runs on every `mvn compile`. `EventGrpcServiceGrpc`/`TicketGrpcServiceGrpc` each expose an
`ImplBase` to extend and a `BlockingStub` (used by `EventManagerApiGrpcTest`, and by anything else
that wants to call this server).

## Classes (`com.eventmanager.grpcserver`)

- `GrpcServerProperties` — `grpc-api.enabled` (default `true`, `false` in application-test.yml),
  `grpc-api.port` (default `9091`).
- `GrpcServerLifecycle` — `SmartLifecycle` bean; builds a `NettyServerBuilder` server registering
  `EventGrpcServiceImpl`/`TicketGrpcServiceImpl`, each wrapped with `JwtServerInterceptor` via
  `ServerInterceptors.intercept`. Starts/stops with the Spring context automatically. No `net.devh`
  starter — hand-built from plain grpc-java, same as `PaymentGrpcConfig` on the client side.
  `getBoundPort()` exposes the actual bound port (needed when `grpc-api.port=0`, as
  `EventManagerApiGrpcTest` does to avoid port clashes).
- `JwtServerInterceptor` — the entire auth layer (none of the servlet filter chain sees gRPC
  traffic). Validates `authorization: Bearer <jwt>` gRPC metadata using the same
  `JwtTokenProvider`/`UserDetailsServiceImpl` the REST `JwtAuthenticationFilter` uses. No token at
  all → proceeds anonymously (public RPCs must still work); invalid/expired token → immediate
  `UNAUTHENTICATED`. On success, attaches the resolved `SecurityContext` via gRPC's own `Context`
  (`Contexts.interceptCall`), **not** `SecurityContextHolder` directly — see `GrpcCallSupport`'s
  javadoc for why that distinction is load-bearing, not stylistic.
- `GrpcCallSupport` — every RPC method body in `EventGrpcServiceImpl`/`TicketGrpcServiceImpl` runs
  through `run(responseObserver, action)` or `runAuthenticated(responseObserver, action)`. Reads
  the `SecurityContext` back out of gRPC `Context` and copies it into `SecurityContextHolder` only
  for the duration of `action.run()` (cleared in a `finally`) — this is what makes
  `TicketService.currentUserId()` resolve the right caller with zero changes to `TicketService`
  itself. `runAuthenticated` additionally throws `GrpcUnauthenticatedException` if
  `SecurityContextHolder` ends up empty — the gRPC equivalent of `@PreAuthorize("isAuthenticated()")`,
  since gRPC has no method-security annotation layer. Both variants catch whatever `action` throws
  and hand it to `GrpcExceptionMapper`.
- `GrpcExceptionMapper` — `ResourceNotFoundException`→`NOT_FOUND`,
  `AccessDeniedException`→`PERMISSION_DENIED`, `IllegalArgumentException` (every ticket-status-
  machine violation — see ticket-status-state-machine.md)→`FAILED_PRECONDITION`,
  `TicketPaymentInProgressException`→`FAILED_PRECONDITION` (409-equivalent),
  `PaymentServiceUnavailableException`→`UNAVAILABLE` (502-equivalent),
  `GrpcUnauthenticatedException`→`UNAUTHENTICATED`, anything else→`INTERNAL`. The gRPC-transport
  equivalent of `GlobalExceptionHandler` — same exceptions, different wire format.
- `GrpcUnauthenticatedException` — package-private marker exception, only ever thrown by
  `GrpcCallSupport.runAuthenticated` and only ever caught by `GrpcExceptionMapper`.
- `EventGrpcServiceImpl` — `GetEvent`/`ListEvents` (optional `venue_id` filter)/
  `GetAvailableTicketCount`, all public (`GrpcCallSupport.run`), delegating straight to
  `EventService`. Translates `EventResponse`↔`EventMessage` (nested `VenueMessage`/
  `PerformerMessage`) — dates as ISO-8601 strings, statuses as plain strings (not proto enums,
  since `EventStatus`/`TicketStatus` both declare `AVAILABLE` and proto enums are flat per-file
  unless nested).
- `TicketGrpcServiceImpl` — `GetTicket` (public) plus `GetMyTickets`/`ReserveTicket`/
  `ReleaseTicket`/`PurchaseTicket`/`CancelTicket` (all `GrpcCallSupport.runAuthenticated`),
  delegating straight to `TicketService` — including the full saga-style `purchaseTicket` (see
  ticket-status-state-machine.md); `PurchaseTicket`'s gRPC call blocks the handling thread for up
  to the payment service's configured deadline (120s default), the same trade-off the REST
  endpoint already accepts. Translates `TicketResponse`↔`TicketMessage`
  (`optional int64 user_id`/`optional string payment_request_id` for genuinely-nullable fields —
  only set on the builder when non-null, never set-to-zero/empty-string).

## Config

`grpc-api.*` in application.yml (`GRPC_API_ENABLED`/`GRPC_API_PORT` env vars); `grpc-api.enabled:
false` in application-test.yml. Port `9091` exposed in `docker-compose.yml`'s `app` service
alongside `8080` — distinct from the payment-service client's own target port `9095`
(payment-service-grpc.md) and from this app's own HTTP port `8080`.

## Testing

`EventManagerApiGrpcTest` (`@SpringBootTest`, `grpc-api.enabled=true`, `grpc-api.port=0` to get an
OS-assigned port) with `EventService`/`TicketService` as `@MockBean`s, talking to the real server
over a real socket via generated `BlockingStub`s — not `InProcessServerBuilder`, since
`GrpcServerLifecycle`'s real startup/shutdown wiring is itself part of what's being proven. Covers:
a public RPC with no token; `UNAUTHENTICATED` on an authenticated RPC with no token and with a
garbage token; a real JWT (generated via the actual `JwtTokenProvider` bean) succeeding and
resolving the correct caller inside a mocked `TicketService.reserveTicket` call (proving the
`Context`→`SecurityContextHolder` bridge survives the real async dispatch, not just a single-
threaded unit test); and a thrown `TicketPaymentInProgressException` arriving at the client as
`FAILED_PRECONDITION`. A real JWT is minted via `JwtTokenProvider.generateToken(Authentication)`
using a `UserDetails` from the real `UserDetailsServiceImpl`/H2-backed `UserRepository` (a test
user is inserted in `@BeforeEach` if missing) — exactly how `POST /api/v1/auth/login` does it,
just without going through the HTTP layer.
