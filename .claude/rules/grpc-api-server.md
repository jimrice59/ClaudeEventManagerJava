---
paths:
  - "src/main/proto/event_manager_api.proto"
  - "src/main/proto/venue_api.proto"
  - "src/main/proto/performer_api.proto"
  - "src/main/java/com/eventmanager/grpcserver/**"
  - "src/test/java/com/eventmanager/grpcserver/**"
---

# gRPC API Server

This application's own gRPC server — a third transport alongside the REST API (`/api/v1/**`) and
the Thymeleaf web UI (`/ui/**`), all three calling the same `EventService`/`TicketService`/
`VenueService`/`PerformerService` beans. Full design in docs/grpc-api-exposure.md; this file is the
code map. Not to be confused with payment-service-grpc.md, which is this application acting as a
gRPC *client* to a separate, external payment service — unrelated feature, opposite direction.

## Proto and codegen

Three files, one proto package (`eventmanager.api.v1`) / Java package (`com.eventmanager.grpc.api`
— distinct from `com.eventmanager.grpc.payment`, the payment-service client's generated package):

- `event_manager_api.proto` — `EventGrpcService`, `TicketGrpcService`, plus the shared
  `VenueMessage`/`PerformerMessage` the other two files import.
- `venue_api.proto` — `VenueGrpcService`. `import "event_manager_api.proto";` for `VenueMessage`.
- `performer_api.proto` — `PerformerGrpcService`. Same import, for `PerformerMessage`. Its
  `VideoGrpcRequest` message is deliberately named to avoid any confusion with
  `com.eventmanager.dto.VideoRequest` (a different package — no actual collision), the same way
  `PurchaseTicketGrpcRequest` is distinguished from the REST `PurchaseTicketRequest` DTO.

Same `protobuf-maven-plugin` setup as payment-service-grpc.md describes — no separate codegen
step, runs on every `mvn compile`. All three files `import "google/protobuf/empty.proto";` for
delete-RPC responses. Each `*GrpcService` generates an `ImplBase` to extend and a `BlockingStub`
(used by `EventManagerApiGrpcTest`).

## Classes (`com.eventmanager.grpcserver`)

- `GrpcServerProperties` — `grpc-api.enabled` (default `true`, `false` in application-test.yml),
  `grpc-api.port` (default `9091`).
- `GrpcServerLifecycle` — `SmartLifecycle` bean; builds a `NettyServerBuilder` server registering
  all four `*GrpcServiceImpl` beans, each wrapped with `JwtServerInterceptor` via
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
- `GrpcCallSupport` — every RPC method body in the four `*GrpcServiceImpl` classes runs through
  `run(responseObserver, action)` (public), `runAuthenticated(...)` (authenticated-only), or
  `runAdmin(...)` (`ROLE_ADMIN`-only). Reads the `SecurityContext` back out of gRPC `Context` and
  copies it into `SecurityContextHolder` only for the duration of `action.run()` (cleared in a
  `finally`) — this is what makes `TicketService.currentUserId()` resolve the right caller with
  zero changes to `TicketService` itself. `runAuthenticated` throws `GrpcUnauthenticatedException`
  if `SecurityContextHolder` ends up empty — the gRPC equivalent of
  `@PreAuthorize("isAuthenticated()")`. `runAdmin` builds on `runAuthenticated` (so the 401-vs-403
  precedence matches REST exactly — no token → `UNAUTHENTICATED` first; wrong role →
  `AccessDeniedException` → `PERMISSION_DENIED` second) and additionally requires `ROLE_ADMIN`
  among the resolved `Authentication`'s authorities — the gRPC equivalent of
  `@PreAuthorize("hasRole('ADMIN')")`. All three variants catch whatever `action` throws and hand
  it to `GrpcExceptionMapper`.
- `GrpcValidationSupport` — `validate(validator, dto)` runs a mapped REST DTO (e.g. a
  `CreateVenueRequest` message mapped to a `VenueDto`) through the real `jakarta.validation.Validator`
  bean, throwing `ConstraintViolationException` on failure. proto3 has no `@Valid` equivalent of
  its own, so every Create/Update RPC calls this before delegating to the business service —
  without it, a malformed request would reach `VenueService`/`PerformerService`/`EventService`
  completely unvalidated.
- `GrpcExceptionMapper` — `ResourceNotFoundException`→`NOT_FOUND`,
  `AccessDeniedException`→`PERMISSION_DENIED`, `ConstraintViolationException`→`INVALID_ARGUMENT`,
  `DateTimeParseException`/`NumberFormatException`→`INVALID_ARGUMENT` (malformed `event_date`/
  `ticket_price` strings — see event_manager_api.proto), `IllegalArgumentException` (every
  ticket-status-machine violation — see ticket-status-state-machine.md)→`FAILED_PRECONDITION`,
  `TicketPaymentInProgressException`→`FAILED_PRECONDITION` (409-equivalent),
  `PaymentServiceUnavailableException`→`UNAVAILABLE` (502-equivalent),
  `GrpcUnauthenticatedException`→`UNAUTHENTICATED`, anything else→`INTERNAL`. The gRPC-transport
  equivalent of `GlobalExceptionHandler` — same exceptions, different wire format.
- `GrpcUnauthenticatedException` — package-private marker exception, only ever thrown by
  `GrpcCallSupport.runAuthenticated` and only ever caught by `GrpcExceptionMapper`.
- `EventGrpcServiceImpl` — `GetEvent`/`ListEvents` (optional `venue_id` filter)/
  `GetAvailableTicketCount` (public, `GrpcCallSupport.run`); `CreateEvent`/`UpdateEvent`/
  `DeleteEvent` (`GrpcCallSupport.runAdmin`), each mapping the proto request to an `EventRequest`
  DTO and validating it first. `UpdateEventRequest` has no `tickets_total` field (immutable after
  creation; `EventService.updateEvent` ignores it anyway — see event_manager_api.proto), so
  `updateEvent` fills a placeholder `0` into the mapped DTO purely to satisfy its `@NotNull @Min(0)`
  constraint; it's never read. Translates `EventResponse`↔`EventMessage` (nested `VenueMessage`/
  `PerformerMessage`) — dates/ticket price as strings, statuses as plain strings (not proto enums,
  since `EventStatus`/`TicketStatus` both declare `AVAILABLE` and proto enums are flat per-file
  unless nested).
- `TicketGrpcServiceImpl` — `GetTicket` (public) plus `GetMyTickets`/`ReserveTicket`/
  `ReleaseTicket`/`PurchaseTicket`/`CancelTicket` (all `GrpcCallSupport.runAuthenticated` —
  ownership-gated inside `TicketService`, not role-gated), delegating straight to `TicketService` —
  including the full saga-style `purchaseTicket` (see ticket-status-state-machine.md);
  `PurchaseTicket`'s gRPC call blocks the handling thread for up to the payment service's
  configured deadline (120s default), the same trade-off the REST endpoint already accepts.
  Translates `TicketResponse`↔`TicketMessage` (`optional int64 user_id`/
  `optional string payment_request_id`/`optional string date_purchased` for genuinely-nullable
  fields — only set on the builder when non-null; `date_purchased`, like `event_date`, travels as
  a plain ISO-8601 string).
- `VenueGrpcServiceImpl` — `GetVenue`/`ListVenues` (optional `city` filter) (public);
  `CreateVenue`/`UpdateVenue`/`DeleteVenue` (`runAdmin`, validated). Translates
  `VenueDto`↔`VenueMessage` (`optional string zip_code` for the one genuinely-nullable `VenueDto`
  field).
- `PerformerGrpcServiceImpl` — `GetPerformer`/`ListPerformers` (optional `name`/`genre` filter,
  `name` wins if both set — mirroring `PerformerController.getAllPerformers`'s own priority, not
  an enforced mutual exclusion) (public); `CreatePerformer`/`UpdatePerformer`/`AddVideo`/
  `RemoveVideo`/`DeletePerformer` (`runAdmin`, create/update validated) — video management is just
  as ADMIN-gated here as on the REST API (`PerformerController`'s own
  `@PreAuthorize("hasRole('ADMIN')")` on `/videos` sub-routes). Translates `PerformerDto`↔
  `PerformerMessage` (`optional string genre`/`bio` — both genuinely nullable on `PerformerDto`;
  `video_urls` stays output-only, never part of `CreatePerformerRequest`/`UpdatePerformerRequest`,
  matching data-model.md's note that video management is dedicated-endpoint-only, not part of the
  create/update payload).

## Config

`grpc-api.*` in application.yml (`GRPC_API_ENABLED`/`GRPC_API_PORT` env vars); `grpc-api.enabled:
false` in application-test.yml. Port `9091` exposed in `docker-compose.yml`'s `app` service
alongside `8080` — distinct from the payment-service client's own target port `9095`
(payment-service-grpc.md) and from this app's own HTTP port `8080`.

**TLS (`grpc-api.tls.*`, `GrpcServerProperties.Tls`):** plaintext by default
(`grpc-api.tls.enabled: false`). When enabled, `GrpcServerLifecycle.start()` calls
`GrpcSslContexts.forServer(certChainFile, privateKeyFile).build()` and attaches it to the
`NettyServerBuilder` before starting — grpc-java wants a PEM cert chain + PEM private key
(`grpc-api.tls.cert-chain-file`/`private-key-file`, env `GRPC_API_TLS_CERT_CHAIN_FILE`/
`GRPC_API_TLS_PRIVATE_KEY_FILE`), a different shape from `server.ssl.key-store`'s PKCS12/JKS
keystore (that one covers the REST API/web UI's Tomcat HTTPS instead, auto-configured by Spring
Boot — no code change needed there). `certs/generate-dev-certs.sh` generates a matching self-signed
cert for local testing in both formats from one key pair; see certs/README.md. Both are off by
default so no existing environment (local, docker-compose, current k8s) is affected until it opts
in — this is orthogonal to the L7 TLS-termination discussion in docs/load-balancer-options.md,
which is about an Ingress/load balancer in front of the app, not the app terminating TLS itself.

## Testing

`EventManagerApiGrpcTest` (`@SpringBootTest`, `grpc-api.enabled=true`, `grpc-api.port=0` to get an
OS-assigned port) with `EventService`/`TicketService`/`VenueService`/`PerformerService` all as
`@MockBean`s, talking to the real server over a real socket via generated `BlockingStub`s — not
`InProcessServerBuilder`, since `GrpcServerLifecycle`'s real startup/shutdown wiring (now
registering four services) is itself part of what's being proven. A generic
`<S extends AbstractStub<S>> S withBearerToken(S stub, String token)` helper attaches the
`authorization` header to any of the four stub types via
`stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))`. Covers: a public RPC
with no token; `UNAUTHENTICATED` on an authenticated RPC with no token and with a garbage token; a
real `ROLE_USER` JWT (generated via the actual `JwtTokenProvider`/`UserDetailsServiceImpl` beans, a
test user inserted into H2 if missing) succeeding and resolving the correct caller inside a mocked
`TicketService.reserveTicket` call (proving the `Context`→`SecurityContextHolder` bridge survives
the real async dispatch); a `ROLE_ADMIN` JWT (same mechanism, second test user) succeeding on an
admin-only RPC (`CreateVenue`, `DeletePerformer`) while the same call with a `ROLE_USER` token gets
`PERMISSION_DENIED` and with no token gets `UNAUTHENTICATED`; a deliberately-blank required field
on a `CreateVenueRequest` mapping to `INVALID_ARGUMENT` via `GrpcValidationSupport`; and a thrown
`TicketPaymentInProgressException` arriving at the client as `FAILED_PRECONDITION`.
