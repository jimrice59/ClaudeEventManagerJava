---
paths:
  - "src/main/proto/payment_service.proto"
  - "src/main/java/com/eventmanager/payment/**"
  - "src/main/java/com/eventmanager/config/PaymentGrpcConfig.java"
  - "src/main/java/com/eventmanager/exception/TicketPaymentInProgressException.java"
  - "src/main/java/com/eventmanager/exception/PaymentServiceUnavailableException.java"
---

# Payment Service gRPC Client

This application is a gRPC **client** to an external payment service — see
docs/payment-service-integration.md for the full design and ticket-status-state-machine.md for how
this fits into the ticket state machine. There is no gRPC server in this project (a separate,
unrelated discussion about exposing *this* service's own API over gRPC exists in
docs/grpc-service-exposure.md — not implemented, different feature).

## Codegen

`src/main/proto/payment_service.proto` defines `PaymentService` (`PurchaseTicket`,
`GetPaymentStatus`) in proto package `eventmanager.payment.v1`, generating into Java package
`com.eventmanager.grpc.payment` (`option java_package`). `pom.xml`'s `protobuf-maven-plugin`
(bound to `generate-sources`/`compile`) runs `protoc` + the `grpc-java` plugin automatically on
every `mvn compile` — no manual codegen step. Generated sources land in
`target/generated-sources/protobuf/{java,grpc-java}/com/eventmanager/grpc/payment/` and are not
checked in. The `os-maven-plugin` build extension resolves the right native `protoc`/
`protoc-gen-grpc-java` classifier for the build machine; the first build on a machine needs network
access to download those binaries from Maven Central.

`com.eventmanager.grpc.payment.PurchaseTicketRequest` (generated) is a different class from
`com.eventmanager.dto.PurchaseTicketRequest` (the REST API's request DTO, in a different package) —
don't confuse the two. Nothing outside `com.eventmanager.payment` imports the generated classes
directly; `PaymentServiceClient` is the one seam that translates between them and this
application's own `PaymentOutcome`/`PaymentOutcomeStatus`, the same way `CassandraAsyncWriter` is
the only place that touches Cassandra repository types directly (see cassandra-dual-write.md).

## Classes

- `PaymentServiceProperties` (`com.eventmanager.payment`) — `@ConfigurationProperties(prefix =
  "payment-service")`: `host`, `port`, `deadline` (120s default — the per-call gRPC deadline), and
  `connectTimeout` (5s default, used as the deadline on the lighter-weight `GetPaymentStatus`
  call). Bound from `payment-service.*` in `application.yml`
  (`PAYMENT_SERVICE_HOST`/`PAYMENT_SERVICE_PORT`/`PAYMENT_SERVICE_DEADLINE`/
  `PAYMENT_SERVICE_CONNECT_TIMEOUT` env vars).
- `PaymentGrpcConfig` (`com.eventmanager.config`) — builds the `ManagedChannel` (plaintext, no TLS —
  matches every other local/dev-profile infrastructure connection in this project) and the
  `PaymentServiceGrpc.PaymentServiceBlockingStub` bean from it. Channel creation is lazy — it does
  not connect eagerly, so this config loads safely under the test profile (no payment service
  running) as long as no test actually invokes an RPC through the stub; `EventManagerApplicationTests`
  (a full `@SpringBootTest`) confirms this. `@PreDestroy` shuts the channel down gracefully.
- `PaymentServiceClient` (`com.eventmanager.payment`) — wraps the blocking stub.
  `purchaseTicket(paymentRequestId, userId, paymentToken, ticketId, purchasePrice)` applies the
  configured `deadline` via `withDeadlineAfter`; `getPaymentStatus(paymentRequestId)` (not yet
  called from anywhere — see "Reconciling a stuck PAYING ticket" in
  docs/payment-service-integration.md) applies `connectTimeout` instead, since it's meant to be a
  quick poll. Both catch `StatusRuntimeException` and return a `PaymentOutcome` with
  `PaymentOutcomeStatus.ERROR` rather than letting the exception propagate — `TicketService` never
  needs a separate catch block for "business decline" vs. "the call itself failed," both come back
  as the same return type.
- `PaymentOutcome`/`PaymentOutcomeStatus` (`com.eventmanager.payment`) — this application's own
  plain mirror of the proto `PaymentResponse`/`PaymentStatus`, plus `ERROR` (no proto equivalent —
  synthesized by `PaymentServiceClient` when the call itself fails).
- `PaymentCacheService` (`com.eventmanager.payment`) — see docs/payment-service-integration.md,
  "Redis caching", for the two cache keys/TTLs. Uses `StringRedisTemplate` directly (ad-hoc
  `SET ... EX`), the same way `RateLimiter` talks to Redis directly rather than through
  `@Cacheable` — see redis-caching.md.
- `TicketPaymentInProgressException` (409) / `PaymentServiceUnavailableException` (502)
  (`com.eventmanager.exception`) — both `@ResponseStatus`-annotated like `ResourceNotFoundException`,
  and both also have an explicit `@ExceptionHandler` in `GlobalExceptionHandler` to get the same
  `ErrorResponse` JSON body shape as every other handled exception.

## Testing

Nothing under this path has its own dedicated unit test file — `PaymentServiceClient`/
`PaymentCacheService`/`PaymentGrpcConfig` are exercised indirectly via `TicketServiceTest`'s mocked
`PaymentServiceClient`/`PaymentCacheService` (see ticket-status-state-machine.md) and via
`EventManagerApplicationTests` loading the full context. There is no integration test that actually
calls a running payment service (none exists in this project's docker-compose stack either).
