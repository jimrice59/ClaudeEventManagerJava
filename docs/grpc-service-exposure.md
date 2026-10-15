# Exposing This Service as gRPC

> **Implemented** — see `docs/grpc-api-exposure.md` for what was actually built (a public
> `EventGrpcService` plus an authenticated `TicketGrpcService`, `JwtServerInterceptor` for auth,
> `GrpcExceptionMapper` for error mapping) and `.claude/rules/grpc-api-server.md` for the code map.
> This file is kept as the original design discussion.

How can this service be exposed as a gRPC service? It fits cleanly because of how the app is
already structured — the controllers are thin wrappers around `EventService`/`TicketService`/
`PerformerService`/`VenueService`, so gRPC would become a **third transport** alongside the REST
API and the Thymeleaf UI, all three calling the same service layer.

## 1. Dependencies + codegen

Add to `pom.xml`:
- `net.devh:grpc-spring-boot-starter` — auto-configures an embedded gRPC server inside the Spring
  context (Netty-based, its own port, default `9090`) and wires `@GrpcService` beans into it, the
  same way `@RestController` beans get wired into the servlet container.
- `io.grpc:grpc-protobuf`, `io.grpc:grpc-stub`, `com.google.protobuf:protobuf-java`
- `org.xolstice.maven.plugins:protobuf-maven-plugin` — runs `protoc` + the `grpc-java` codegen
  plugin during `generate-sources`, so `mvn compile` produces message/stub classes into
  `target/generated-sources/protobuf` automatically, no separate build step.

## 2. Proto definitions mirror the existing DTOs, not the JPA entities

```protobuf
// src/main/proto/event.proto
service EventGrpcService {
  rpc GetEvent(GetEventRequest) returns (EventResponse);
  rpc ListEvents(ListEventsRequest) returns (ListEventsResponse);
  rpc ReserveTicket(ReserveTicketRequest) returns (TicketResponse);
}

message EventResponse {
  int64 id = 1;
  string name = 2;
  EventStatus status = 3;
  // ...
}

enum EventStatus { AVAILABLE = 0; SOLD_OUT = 1; DELETING = 2; CANCELLED = 3; }
```

This tracks `EventResponse`/`TicketResponse`/`PagedResponse` in `com.eventmanager.dto` almost 1:1 —
proto3 just forces you to be explicit about field numbers and gives you `EventStatus`/
`TicketStatus` as real enums instead of strings.

## 3. Implementation calls the existing service beans

```java
@GrpcService
public class EventGrpcServiceImpl extends EventGrpcServiceGrpc.EventGrpcServiceImplBase {

    private final EventService eventService; // same bean EventController uses

    @Override
    public void getEvent(GetEventRequest request, StreamObserver<EventResponse> responseObserver) {
        Event event = eventService.getEvent(request.getId()); // unchanged
        responseObserver.onNext(toGrpcResponse(event));
        responseObserver.onCompleted();
    }
}
```

No business logic duplicated — `ReserveTicket` still goes through `TicketService.reserveTicket`,
hits the same status-machine checks, same Redis cache eviction, same Cassandra/Kafka
fire-and-forget paths.

## 4. Auth is the part that needs real work

This is the biggest gap: gRPC's Netty server runs on its own port, completely outside the Spring
Security filter chain described in `architecture-overview.md` — `RateLimitFilter`,
`JwtAuthenticationFilter`, the `@Order` chains, none of it sees gRPC traffic. You'd need a
`ServerInterceptor`:

```java
@GrpcGlobalServerInterceptor
public class JwtGrpcInterceptor implements ServerInterceptor {
    // read "authorization" from gRPC Metadata, reuse the existing
    // JwtTokenProvider.validateToken()/extractUserId(), set a context value
    // other services can read instead of SecurityContextHolder
}
```

Same custom HS256 JWT validation logic as today, just invoked from a different entry point instead
of a servlet `Filter`. The rate limiter would need its own interceptor too if you want parity
(Redis sliding-window check, keyed by peer address instead of `X-Forwarded-For`).

## 5. Error mapping

REST uses `@ExceptionHandler`/HTTP status; gRPC uses `Status` codes instead —
`ResourceNotFoundException → NOT_FOUND`, a validation failure → `INVALID_ARGUMENT`, the
ticket-status-machine violations (e.g., reserving a non-`AVAILABLE` ticket) →
`FAILED_PRECONDITION`. Cleanest as another global interceptor or a `@GrpcAdvice`-annotated
exception handler (devh starter supports this, mirroring `@ControllerAdvice`).

## 6. Infra

- Dockerfile/`docker-compose.yml`: expose `9090` alongside `8080`.
- Traefik: supports gRPC/h2c backends via labels
  (`traefik.http.services.app.loadbalancer.server.scheme=h2c`), so the existing load-balancing
  across `--scale app=3` would keep working.
- Tests: `grpc-java` ships `InProcessServerBuilder`/`InProcessChannelBuilder` for in-JVM testing
  without binding a real port — the gRPC-side equivalent of `MockMvc`, usable alongside the
  existing H2-backed `@SpringBootTest` setup without needing a real network listener.

## Net effect

REST stays the primary/public API; gRPC becomes an additive, typed, binary-protocol transport for
the same domain logic — most relevant if there's a specific driver (internal service-to-service
calls, a mobile client wanting smaller payloads/streaming, etc.).

---

No implementation yet — this captures the design discussion for review before any code is written.
