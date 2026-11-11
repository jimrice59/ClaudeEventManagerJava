---
paths:
  - "src/test/**"
  - "src/main/resources/application-test.yml"
---

# Test Profile & Test Classes

## Test profile

`src/main/resources/application-test.yml` (activated by `@ActiveProfiles("test")`) swaps Postgres for H2 in-memory, sets `spring.cache.type: none` so Redis is not required, and excludes `CassandraAutoConfiguration`, `CassandraRepositoriesAutoConfiguration`, and `KafkaAutoConfiguration` so neither Cassandra nor Kafka is required during tests. It also disables the rate limiter (`rate-limit.enabled: false`, see rate-limiting.md) since no Redis broker runs there and `@AutoConfigureMockMvc` would otherwise route every `MockMvc` request through that filter.

## Test classes

| Class | Style | Tests | Notes |
|---|---|---|---|
| `EventManagerApplicationTests` | `@SpringBootTest` | 1 | Context load smoke test |
| `VenueServiceTest` | Mockito (`@ExtendWith(MockitoExtension.class)`) | 11 | Pure unit tests, no Spring context |
| `EventServiceTest` | Mockito (`@ExtendWith(MockitoExtension.class)`) | 18 | Pure unit tests, no Spring context |
| `PerformerServiceTest` | Mockito (`@ExtendWith(MockitoExtension.class)`) | 13 | Pure unit tests, no Spring context |
| `TicketServiceTest` | Mockito (`@ExtendWith(MockitoExtension.class)`) | 35 | Pure unit tests, no Spring context — see ticket-status-state-machine.md |
| `WebTicketControllerTest` | Mockito (`@ExtendWith(MockitoExtension.class)`) | 4 | Pure unit tests, no Spring context; calls `WebTicketController.purchase()` directly with a `RedirectAttributesModelMap` and asserts the flash message per outcome — approved (`SOLD`) → `successMessage`, declined (returned normally as `AVAILABLE`, not thrown) → `errorMessage` only, `PaymentServiceUnavailableException` → `errorMessage` mentioning `PAYING`, `TicketPaymentInProgressException` → its own message. Note `getFlashAttributes()` is `Map<String, ?>`, so assert with `.get(key)).isEqualTo(...)` rather than `containsEntry` (won't compile) |
| `PaymentServiceClientTest` | Mockito (`@ExtendWith(MockitoExtension.class)`) | 3 | Pure unit tests, no Spring context; mocks the generated `PaymentServiceBlockingStub` (a final class — works because Mockito 5's default inline mock maker can mock finals). Covers the `payment-service.enabled` toggle: disabled → `purchaseTicket`/`getPaymentStatus` return a simulated `APPROVED` (`SIMULATED-` confirmation id, idempotency token = `paymentRequestId`) with `verifyNoInteractions(blockingStub)`; enabled (the default) → calls the stub and maps the proto response |
| `PerformerControllerTest` | `@SpringBootTest + @AutoConfigureMockMvc` | 16 | Full context with H2; mocks `PerformerService` |
| `RateLimiterTest` | Mockito (`@ExtendWith(MockitoExtension.class)`) | 3 | Pure unit tests, no Spring context, no Redis — see rate-limiting.md |
| `EdgeMetricsConfigTest` | Plain JUnit | 3 | Pure unit tests, no Spring context, no real `MeterRegistry` — see monitoring.md |
| `EventManagerApiGrpcTest` | `@SpringBootTest` (real socket, `grpc-api.enabled=true`/`port=0`) | 15 | Mocks `EventService`/`TicketService`/`VenueService`/`PerformerService`; real `JwtTokenProvider`-minted JWTs (`ROLE_USER` and `ROLE_ADMIN`) over a real gRPC channel — see grpc-api-server.md |

**`VenueServiceTest`** covers: `getAllVenues` (list, empty), `getVenueById` (found/not found), `getVenuesByCity` (match, no match), `createVenue` (saves and returns DTO), `updateVenue` (updates all fields, throws when not found), `deleteVenue` (deletes when exists, throws when not found). Single dependency `VenueRepository`; constructor: `new VenueService(venueRepository)`.

**`EventServiceTest`** covers: `getAllEvents` (list, empty), `getEventById` (found/not found), `getEventsByVenue`, `getEventsBetween`, `createEvent` (verifies Cassandra write, `status=AVAILABLE` on the response, and `ticketService.createAvailableTickets(event, request.ticketsTotal)` — note this is the event's own `ticketsTotal` (100 in the fixture), not venue capacity (20000); throws `IllegalArgumentException` when `ticketsTotal` exceeds venue capacity — no save/ticket/Cassandra interaction; throws when venue missing — no ticket/Cassandra interaction; throws when performer ID not in DB — no ticket interaction), `updateEvent` (updates fields and Cassandra write; asserts `ticketsTotal` on the response is unchanged regardless of the request, since `Event.ticketsTotal` has no setter; throws when event missing; throws when new venue missing), `getNumAvailableTickets` (delegates to and returns `ticketService.getNumAvailableTickets(id)`; propagates `ResourceNotFoundException` when the ticket service throws it), `deleteEvent` (sets `status=DELETING` and saves, calls `ticketService.backupAndDeleteAllForEvent`, deletes from Postgres, and verifies **no** Cassandra interaction — the event's Cassandra copy is deliberately left in place; throws when not found — no ticket/Cassandra interaction). Constructor: `new EventService(eventRepository, venueRepository, performerRepository, venueService, performerService, cassandraAsyncWriter, ticketService)`. `venueService`, `performerService`, and `ticketService` are mocked — `toDto` is stubbed wherever `toResponse` is exercised. Note: `resolvePerformers` short-circuits on an empty `Set` without calling the repository, so stubbing `findAllById(Set.of())` triggers Mockito strict-mode UnnecessaryStubbing; omit that stub when `performerIds` is empty. There are no `reserveTickets`/`releaseTickets` tests — those methods and the event-level `ticketsAvailable` counter they mutated were removed.

**`PerformerServiceTest`** covers: `getAllPerformers`, `getPerformerById` (found/not found), `searchPerformers`, `getPerformersByGenre`, `createPerformer` (verifies `cassandraAsyncWriter.savePerformer` is called), `updatePerformer` (found/not found), `deletePerformer` (found/not found). Mocks `CassandraAsyncWriter`, `VideoRepository`, and `PerformerVideoEventPublisher` directly via constructor — no `ReflectionTestUtils` needed. Constructor: `new PerformerService(performerRepository, videoRepository, cassandraAsyncWriter, videoEventPublisher)`. The "absent Cassandra/Kafka" scenarios are handled by null-check guards in `CassandraAsyncWriter` and `PerformerVideoEventPublisher` and are not tested at the service level. Repository stubs use the new video-aware method names (`findAllWithVideos`, `findByIdWithVideos`, etc.).

**`PerformerControllerTest`** covers: happy-path responses, query param routing (`?name=`, `?genre=`), 400 on validation failure, 403 for `ROLE_USER` on admin endpoints, **401** for unauthenticated requests (changed from 403 when `oauth2ResourceServer` installed `BearerTokenAuthenticationEntryPoint`), 404 with error body.

**`@WebMvcTest` caveat:** `@EnableJpaRepositories` on `EventManagerApplication` causes `@WebMvcTest` slices to fail (JPA is forced into the context but `entityManagerFactory` isn't auto-configured by the slice). Controller tests use `@SpringBootTest + @AutoConfigureMockMvc + @ActiveProfiles("test")` instead.

**Async Cassandra/Kafka in service tests:** `@Async` is an AOP proxy feature — in a plain Mockito test with no Spring context, `CassandraAsyncWriter` and `PerformerVideoEventPublisher` methods run synchronously. This is fine: tests verify that the collaborators are called, not that they ran on a background thread. All four dependencies are constructor-injected into `PerformerService`, so `new PerformerService(performerRepository, videoRepository, cassandraAsyncWriter, videoEventPublisher)` with Mockito mocks is all that is needed — no `ReflectionTestUtils`.
