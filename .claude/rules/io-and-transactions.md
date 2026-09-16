---
paths:
  - "src/main/java/com/eventmanager/service/**"
  - "src/main/java/com/eventmanager/repository/**"
  - "src/main/java/com/eventmanager/config/AsyncConfig.java"
---

# I/O Model & Transaction Conventions

## I/O model

**Postgres** calls are synchronous and blocking. Spring Data JPA uses JDBC — every `findById`, `save`, and `deleteById` holds the request thread until Postgres responds. The app is servlet-based (`spring-boot-starter-web`, Tomcat), so each request occupies one thread for its full duration. Concurrency is handled via **virtual threads** (see below) rather than a reactive stack.

**Virtual threads** (`spring.threads.virtual.enabled: true` in `application.yml`) configure Tomcat to dispatch every inbound request on a Java 21 virtual thread instead of a platform thread. When a virtual thread blocks on JDBC, the underlying carrier thread is unmounted by the JVM and picks up other work — effectively giving unlimited concurrent requests without the memory and scheduling cost of thousands of platform threads. No code changes are required: all existing synchronous JDBC/JPA calls benefit automatically. This is the correct approach for a servlet-based app with blocking I/O; making service methods `@Async` with `CompletableFuture` would add a thread-hop without benefit since the request still has to wait for the result. True non-blocking Postgres would require a reactive stack (R2DBC + WebFlux), which is a significant architectural rewrite.

**Cassandra** writes are asynchronous via Spring's `@Async` mechanism. After the Postgres write commits, both `PerformerService` and `EventService` call the appropriate `CassandraAsyncWriter` method (`savePerformer`/`deletePerformer` or `saveEvent`/`deleteEvent`), which returns immediately — the actual Cassandra I/O runs on the `cassandraExecutor` virtual-thread executor (configured in `AsyncConfig`). The HTTP response is returned before the Cassandra write completes.

`@Async` requires the annotated method to be on a different bean — calling an `@Async` method on `this` bypasses the Spring AOP proxy and runs synchronously. `CassandraAsyncWriter` is a dedicated `@Service` for this reason; both `PerformerService` and `EventService` inject it via constructor and delegate to it.

`AsyncConfig` registers two named executor beans backed by `Executors.newVirtualThreadPerTaskExecutor()`:
- `cassandraExecutor` — used by `CassandraAsyncWriter`; each async Cassandra task gets its own virtual thread
- `kafkaExecutor` — used by `PerformerVideoEventPublisher`; each async Kafka publish gets its own virtual thread

Using virtual-thread executors instead of fixed `ThreadPoolTaskExecutor` pools removes the artificial cap on concurrent async tasks (previously core 2, max 5, queue 100 per executor). Keeping the two executors separate ensures Cassandra and Kafka I/O never compete for the same threads.

Cassandra write failures are caught inside `CassandraAsyncWriter` and logged as `ERROR` — there is no caller to propagate them to once the method returns asynchronously.

**Kafka** publishes are asynchronous with retry. `PerformerService.addVideo` and `deleteVideo` call `PerformerVideoEventPublisher.publish(VideoEvent)` after the Postgres write. The publisher method is `@Async("kafkaExecutor")`, so the HTTP response is returned before the Kafka send starts. On the background thread, a `RetryTemplate` with `ExponentialBackOffPolicy` (initial 10 ms, multiplier 2.0) and `SimpleRetryPolicy` (10 attempts) wraps `kafkaTemplate.send(...).get()`. Calling `.get()` blocks the background thread until the broker acknowledges, which is the only way to surface send failures to the retry mechanism. On retry, `WARN` is logged per attempt. After all 10 attempts fail, the recovery callback logs `ERROR` with the final exception and returns — no exception propagates. If `KafkaTemplate` is absent (test profile), `publish()` returns immediately.

`@Async` and `@Retryable` cannot be stacked on the same method — `@Async` submits to a thread pool and returns a proxy immediately, so `@Retryable` on the calling thread sees no failure to retry. Using `RetryTemplate` programmatically inside the `@Async` method avoids this AOP ordering problem. No `@EnableRetry` is needed.

**Alternative async approaches (not implemented):**
- `ReactiveCassandraRepository` (`Mono`/`Flux`) — works with `.subscribe()` in a servlet app but error handling is harder and there is no clean integration with the servlet thread model.
- Full reactive stack (`spring-boot-starter-webflux` + R2DBC + `ReactiveCassandraRepository`) — non-blocking end-to-end but a significant architectural rewrite.

## Transaction conventions

All service methods are explicitly annotated — no implicit transaction boundary is relied upon:
- Read-only methods use `@Transactional(readOnly = true)` — allows connection reuse and DB-side read optimization
- Write methods use `@Transactional` — rolls back on any unchecked exception
- `AuthService.login` is `@Transactional(readOnly = true)`: it makes two DB reads (one via `authenticationManager.authenticate` → `UserDetailsServiceImpl`, one direct `userRepository.findByUsername`) and wrapping them ensures a single connection

Cassandra writes in `PerformerService` and `EventService` happen after the JPA call and are outside the Postgres transaction boundary. A Cassandra failure after Postgres commits is not rolled back — accepted limitation of dual-store without a distributed transaction coordinator.
