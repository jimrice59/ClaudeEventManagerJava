# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

**Java version requirement:** Lombok 1.18.32 is incompatible with Java 24. Always use Java 21.

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
export MVN=/Users/jimrice1959/.sdkman/candidates/maven/current/bin/mvn
```

```bash
# Start dependencies
docker compose up -d

# Compile
JAVA_HOME=... $MVN compile

# Run application
JAVA_HOME=... $MVN spring-boot:run

# Run all tests (requires H2; Redis/Postgres not needed for tests)
JAVA_HOME=... $MVN test

# Run a single test class
JAVA_HOME=... $MVN test -Dtest=PerformerServiceTest

# Package jar
JAVA_HOME=... $MVN package -DskipTests

# Run packaged jar
java -jar target/event-manager-1.0.0.jar
```

### Docker

```bash
# Build image (~137 MB content size)
docker build -t event-manager .

# Run against the compose stack (dependencies on host)
docker run -p 8080:8080 \
  -e DB_HOST=host.docker.internal \
  -e REDIS_HOST=host.docker.internal \
  -e CASSANDRA_HOST=host.docker.internal \
  event-manager
```

The Dockerfile is a two-stage build:
- **Build stage** (`maven:3.9-eclipse-temurin-21`): `pom.xml` is copied first and `dependency:go-offline` is run as a separate layer, so Maven dependencies are cached between builds and only re-downloaded when `pom.xml` changes.
- **Runtime stage** (`eclipse-temurin:21-jre-alpine`): copies only the packaged jar into a minimal Alpine JRE image. No JDK, no Maven, no source in the final image.

`.dockerignore` excludes `target/`, `.git/`, `.claude/`, `*.md`, and `dump.rdb`.

All config values default to localhost with `postgres/postgres` credentials. Override via env vars: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `CASSANDRA_HOST`, `CASSANDRA_PORT`, `CASSANDRA_KEYSPACE`, `CASSANDRA_DATACENTER`, `JWT_SECRET`, `JWT_EXPIRATION_MS`.

`docker compose up -d` also starts Prometheus (port 9090) and Grafana (port 3000, `admin`/`admin`). Prometheus scrapes `/actuator/prometheus` on `host.docker.internal:8080` every 15 s. In Grafana, add `http://prometheus:9090` as a Prometheus data source and import dashboard ID **4701** (JVM Micrometer) for HTTP and JVM metrics.

## Architecture

### Request flow
```
HTTP Request
  → JwtAuthenticationFilter (extracts/validates Bearer token, sets SecurityContext)
  → SecurityFilterChain (enforces authorization rules)
  → Controller (validates input with @Valid)
  → Service (business logic, Redis cache annotations)
  → Repository (JPA / PostgreSQL)
       ↓ (performer writes only)
  → PerformerCassandraRepository (Cassandra dual-write)
```

### API endpoints

| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/api/auth/register` | public | returns JWT |
| POST | `/api/auth/login` | public | returns JWT |
| GET | `/api/events` | public | optional `?venueId=` or `?start=&end=` (ISO datetime) |
| GET | `/api/events/{id}` | public | cached |
| POST | `/api/events` | authenticated | |
| PUT | `/api/events/{id}` | authenticated | |
| DELETE | `/api/events/{id}` | ADMIN | |
| GET | `/api/venues` | public | optional `?city=` |
| GET | `/api/venues/{id}` | public | cached |
| POST | `/api/venues` | ADMIN | |
| PUT | `/api/venues/{id}` | ADMIN | |
| DELETE | `/api/venues/{id}` | ADMIN | |
| GET | `/api/performers` | public | optional `?name=` or `?genre=` |
| GET | `/api/performers/{id}` | public | cached |
| POST | `/api/performers` | ADMIN | Cassandra dual-write |
| PUT | `/api/performers/{id}` | ADMIN | Cassandra dual-write |
| DELETE | `/api/performers/{id}` | ADMIN | Cassandra dual-write |

### Authorization model
Defined in `SecurityConfig.securityFilterChain`:
- Public (no token): `GET /api/events/**`, `GET /api/venues/**`, `GET /api/performers/**`, `POST /api/auth/**`, `/actuator/health`, `/actuator/prometheus`
- Authenticated (`ROLE_USER` or `ROLE_ADMIN`): `POST/PUT /api/events/**`
- Admin only (`ROLE_ADMIN`): `POST/PUT/DELETE /api/venues/**`, `POST/PUT/DELETE /api/performers/**`, `DELETE /api/events/**`, `/api/admin/**`

Fine-grained rules use `@PreAuthorize` on controller methods; the filter chain rules are the outer gate.

Unauthenticated requests to protected endpoints return **403** (not 401) — no `AuthenticationEntryPoint` is configured, so Spring Security's default `Http403ForbiddenEntryPoint` applies.

### Exception handling
`GlobalExceptionHandler` (`@RestControllerAdvice`) maps exceptions to HTTP responses:

| Exception | Status | Notes |
|---|---|---|
| `ResourceNotFoundException` | 404 | Message from exception |
| `AccessDeniedException` | 403 | Fixed message "Access denied"; must be declared before the `Exception` catch-all or `@PreAuthorize` rejections return 500 |
| `BadCredentialsException` | 401 | Fixed message "Invalid username or password" |
| `IllegalArgumentException` | 400 | Message from exception; used by `AuthService` for duplicate username/email |
| `MethodArgumentNotValidException` | 400 | Returns `{ status, errors: { field: message }, timestamp }` — different shape from `ErrorResponse` |
| `Exception` (catch-all) | 500 | Generic message |

`ErrorResponse` is a record `(int status, String message)` with a computed `timestamp()` method.

### Redis caching
All three services cache individual records by ID with a 1-hour TTL:

| Cache name | Service | Cacheable | CachePut | CacheEvict |
|---|---|---|---|---|
| `"events"` | `EventService` | `getEventById` | `createEvent`, `updateEvent` | `deleteEvent` |
| `"venues"` | `VenueService` | `getVenueById` | `createVenue`, `updateVenue` | `deleteVenue` |
| `"performers"` | `PerformerService` | `getPerformerById` | `createPerformer`, `updatePerformer` | `deletePerformer` |

List endpoints (`getAll*`, `getVenuesByCity`, `searchPerformers`, `getPerformersByGenre`, `getEventsByVenue`, `getEventsBetween`) are not cached — list invalidation is not implemented.

Cache is configured in `RedisConfig` with JSON serialization (`GenericJackson2JsonRedisSerializer`) and a 1-hour TTL. `EventResponse`, `VenueDto`, and `PerformerDto` all implement `Serializable` for this reason.

### Data model relationships
- `Event` → `Venue`: `@ManyToOne` (an event has exactly one venue; venue is not owning side)
- `Event` ↔ `Performer`: `@ManyToMany` via join table `event_performers`; `Event` is the owning side
- All associations are `FETCH_TYPE.LAZY`; `EventRepository` uses JPQL `JOIN FETCH` queries (`findByIdWithDetails`, `findAllWithDetails`) to load associations in a single query and avoid N+1

### JWT flow
`JwtTokenProvider` reads `jwt.secret` (BASE64-encoded) and `jwt.expiration-ms` from config. Token contains only the username as subject. On each request, `JwtAuthenticationFilter` extracts the token, validates it, loads `UserDetails` from DB, and sets `UsernamePasswordAuthenticationToken` in the `SecurityContext`.

### DTO separation
Controllers accept/return DTOs, never entities. `EventRequest` carries `venueId` and `Set<Long> performerIds` for write operations. `EventResponse` carries embedded `VenueDto` and `Set<PerformerDto>` for reads. Mapping is done in service `toResponse()` methods, not via a separate mapper library.

### Dependency injection
Three DI forms are used, each for a different reason:

**Constructor injection via `@RequiredArgsConstructor` (Lombok) — primary pattern**
Every service, controller, and security class declares dependencies as `private final` fields. Lombok generates the constructor; Spring injects the beans at startup. Dependencies are immutable and the class is testable without a Spring context (just call `new` with mocks).

| Class | Injected dependencies |
|---|---|
| `AuthController` | `AuthService` |
| `EventController` | `EventService` |
| `VenueController` | `VenueService` |
| `PerformerController` | `PerformerService` |
| `AuthService` | `AuthenticationManager`, `UserRepository`, `PasswordEncoder`, `JwtTokenProvider` |
| `EventService` | `EventRepository`, `VenueRepository`, `PerformerRepository` |
| `VenueService` | `VenueRepository` |
| `PerformerService` | `PerformerRepository` |
| `UserDetailsServiceImpl` | `UserRepository` |
| `JwtAuthenticationFilter` | `JwtTokenProvider`, `UserDetailsServiceImpl` |
| `SecurityConfig` | `UserDetailsServiceImpl`, `JwtAuthenticationFilter` |

**Field injection via `@Autowired(required = false)` — one special case**
`PerformerService.cassandraRepository` uses field injection because it is optional. `required = false` tells Spring to skip the field if no `PerformerCassandraRepository` bean is present (test profile excludes Cassandra autoconfiguration). Constructor injection cannot express this without an `Optional<>` wrapper.

**`@Bean` factory methods in `@Configuration` classes — explicit bean registration**
`SecurityConfig` registers `PasswordEncoder`, `DaoAuthenticationProvider`, `AuthenticationManager`, and `CorsConfigurationSource` manually because they require configuration logic Spring cannot infer. `RedisConfig` registers a custom `RedisCacheManager` with JSON serialization and a 1-hour TTL, overriding Spring's default Java-serialization cache manager.

Spring Data JPA repositories (`EventRepository`, `VenueRepository`, etc.) are registered automatically by the `spring-boot-starter-data-jpa` infrastructure — no annotation is needed on them beyond `extends JpaRepository`. `@EnableJpaRepositories(basePackages = "com.eventmanager.repository")` on `EventManagerApplication` scopes this scan to exclude the Cassandra repository package.

### Synchronous I/O model
All datastore calls are synchronous and blocking — there is no async machinery (`@Async`, reactive types, `CompletableFuture`) in this project.

**Postgres:** Spring Data JPA uses JDBC, which is a blocking API. Every `findById`, `save`, and `deleteById` call holds the request thread until Postgres responds.

**Cassandra:** `PerformerCassandraRepository extends CassandraRepository<CassandraPerformer, Long>` — the synchronous Spring Data Cassandra variant. The reactive equivalent (`ReactiveCassandraRepository`) returns `Mono`/`Flux` and is not used here.

The app is servlet-based (`spring-boot-starter-web`, Tomcat thread pool). Each HTTP request occupies one thread for its full duration. The Cassandra dual-write in `PerformerService` is sequential on that thread: the Postgres call completes first, then the Cassandra call blocks before the response is returned.

**Making Cassandra writes asynchronous — options in order of effort:**

1. **`@Async` on a helper method (lowest effort).** Add `@EnableAsync` to `EventManagerApplication` and extract the Cassandra write into a `@Async`-annotated method. Spring dispatches it to a `ThreadPoolTaskExecutor`, freeing the request thread immediately after the Postgres commit. Best fit here because Cassandra is fire-and-forget (Postgres is source of truth). Failures surface via a configurable `AsyncUncaughtExceptionHandler` rather than propagating to the caller.

2. **`ReactiveCassandraRepository` in a servlet app (awkward).** Swap `CassandraRepository` for `ReactiveCassandraRepository`, which returns `Mono<CassandraPerformer>`. In a servlet context you must call `.subscribe()` (fire-and-forget) or `.block()` (defeats the purpose). `.subscribe()` works but error handling is harder; there is no clean integration with the servlet thread model.

3. **Full reactive stack (largest change).** Replace `spring-boot-starter-web` with `spring-boot-starter-webflux`, swap JDBC/JPA for R2DBC (`spring-boot-starter-data-r2dbc`), and use `ReactiveCassandraRepository`. All I/O becomes non-blocking end-to-end. This is a significant architectural rewrite — controllers return `Mono`/`Flux`, services chain reactive operators, and the Tomcat thread pool is replaced by a small Netty event loop.

### Transaction conventions
All service methods are explicitly annotated — no implicit transaction boundary is relied upon:
- Read-only methods use `@Transactional(readOnly = true)` — allows connection reuse and DB-side read optimization
- Write methods use `@Transactional` — rolls back on any unchecked exception
- `AuthService.login` is `@Transactional(readOnly = true)`: it makes two DB reads (one via `authenticationManager.authenticate` → `UserDetailsServiceImpl`, one direct `userRepository.findByUsername`) and wrapping them ensures a single connection

Cassandra writes in `PerformerService` happen after the JPA call and are outside the Postgres transaction boundary. A Cassandra failure after Postgres commits is not rolled back — accepted limitation of dual-store without a distributed transaction coordinator.

### Logging
`PerformerService` uses `@Slf4j` (Lombok) with structured parameterized log statements. Log level conventions:

| Level | When |
|---|---|
| `INFO` | Mutation entry and success (`createPerformer`, `updatePerformer`, `deletePerformer`) |
| `WARN` | Not-found paths before throwing `ResourceNotFoundException` |
| `DEBUG` | Read operations (query params, result counts), Cassandra sync confirmations |

Root log level is `DEBUG` for `com.eventmanager` (set in `application.yml`). `VenueService` and `EventService` do not have logging yet.

### Monitoring
`spring-boot-starter-actuator` + `micrometer-registry-prometheus` add observability with no instrumentation code:

| Endpoint | Auth | What it shows |
|---|---|---|
| `GET /actuator/health` | public | Composite status: Postgres, Redis, Cassandra, disk |
| `GET /actuator/prometheus` | public | All Micrometer metrics in Prometheus text format |
| `GET /actuator/metrics` | authenticated | Individual metric lookup |
| `GET /actuator/info` | authenticated | App info |

`show-details: always` and `show-components: always` in `application.yml` expose per-component health (each datastore reported individually). All metrics carry an `application=event-manager` tag.

`/actuator/health` and `/actuator/prometheus` are explicitly permitted in `SecurityConfig` so Prometheus can scrape without a token. Other actuator endpoints require authentication.

Scrape config is in `monitoring/prometheus.yml`. Grafana dashboard **4701** (JVM Micrometer) covers HTTP request rate/latency, JVM heap, GC, and Hikari pool usage.

`management.endpoint.health.probes.enabled: true` exposes `/actuator/health/liveness` and `/actuator/health/readiness` as dedicated Kubernetes probe endpoints. Liveness checks only that the JVM/application context is alive; readiness checks datastores. Using `/actuator/health` for liveness would restart pods whenever a database is unreachable, which is wrong.

### Kubernetes
`k8s/deployment.yml` contains four resources applied with `kubectl apply -f k8s/deployment.yml`:

| Resource | Name | Notes |
|---|---|---|
| `Secret` | `event-manager-secrets` | `DB_PASSWORD`, `REDIS_PASSWORD`, `JWT_SECRET` — replace before applying |
| `ConfigMap` | `event-manager-config` | Non-sensitive config; host names are in-cluster service names |
| `Deployment` | `event-manager` | 3 replicas; rolling update with `maxUnavailable: 0` |
| `Service` | `event-manager-service` | `LoadBalancer` on port 80 → pod port 8080 |

Resource sizing: 250m CPU / 512Mi memory requests; 1 CPU / 1Gi limits. Rolling update strategy keeps all 3 replicas live during a deploy (`maxUnavailable: 0`, `maxSurge: 1`).

Probes use the dedicated Spring Boot Kubernetes endpoints:
- **Readiness**: `/actuator/health/readiness` — checks datastores; pod removed from load balancing if unhealthy
- **Liveness**: `/actuator/health/liveness` — checks JVM only; does not restart pods on DB outage

Update the `image:` field to your registry path before applying.

### Cassandra dual-write (performers)
`PerformerService` writes to Postgres first, then Cassandra. Postgres is the source of truth; Cassandra is a secondary store with no read path yet.

- Entity: `cassandra/model/CassandraPerformer.java` — `@Table("performers")` with `id` as partition key
- Repository: `cassandra/repository/PerformerCassandraRepository.java` — `CassandraRepository<CassandraPerformer, Long>`
- `PerformerService` injects `PerformerCassandraRepository` with `@Autowired(required = false)`; Cassandra writes are skipped if the bean is absent (test profile)
- `spring.cassandra.schema-action: create_if_not_exists` auto-creates the `performers` table
- The `event_manager` keyspace is created by the `cassandra-init` container in docker-compose on first `docker compose up -d`

The `cassandra-init` container runs `cqlsh` against the `cassandra` service after it passes its healthcheck, then exits. Cassandra takes ~60 s to start; the healthcheck has `start_period: 60s`.

`@EnableJpaRepositories(basePackages = "com.eventmanager.repository")` on `EventManagerApplication` prevents Spring Data JPA from scanning the `cassandra.repository` package, avoiding multi-store conflicts.

### Test profile
`src/main/resources/application-test.yml` (activated by `@ActiveProfiles("test")`) swaps Postgres for H2 in-memory, sets `spring.cache.type: none` so Redis is not required, and excludes `CassandraAutoConfiguration` + `CassandraRepositoriesAutoConfiguration` so Cassandra is not required during tests.

### Test classes

| Class | Style | Tests | Notes |
|---|---|---|---|
| `EventManagerApplicationTests` | `@SpringBootTest` | 1 | Context load smoke test |
| `PerformerServiceTest` | Mockito (`@ExtendWith(MockitoExtension.class)`) | 14 | Pure unit tests, no Spring context |
| `PerformerControllerTest` | `@SpringBootTest + @AutoConfigureMockMvc` | 16 | Full context with H2; mocks `PerformerService` |

**`PerformerServiceTest`** covers: `getAllPerformers`, `getPerformerById` (found/not found), `searchPerformers`, `getPerformersByGenre`, `createPerformer` (with and without Cassandra), `updatePerformer` (found/not found), `deletePerformer` (found/not found).

**`PerformerControllerTest`** covers: happy-path responses, query param routing (`?name=`, `?genre=`), 400 on validation failure, 403 for `ROLE_USER` on admin endpoints, 403 for unauthenticated requests, 404 with error body.

**`@WebMvcTest` caveat:** `@EnableJpaRepositories` on `EventManagerApplication` causes `@WebMvcTest` slices to fail (JPA is forced into the context but `entityManagerFactory` isn't auto-configured by the slice). Controller tests use `@SpringBootTest + @AutoConfigureMockMvc + @ActiveProfiles("test")` instead.

**Cassandra field injection in service tests:** `PerformerService` has `@Autowired(required = false) PerformerCassandraRepository` (field-injected, not constructor-injected). Mockito's `@InjectMocks` stops after satisfying the required-args constructor and never sets the optional field. `PerformerServiceTest` constructs the service manually and uses `ReflectionTestUtils.setField` to inject the mock.

**`AccessDeniedException` handling:** `GlobalExceptionHandler` has an explicit `@ExceptionHandler(AccessDeniedException.class)` returning 403. Without it, the catch-all `Exception` handler intercepts `@PreAuthorize` rejections and returns 500 instead of 403. Unauthenticated requests return 403 (not 401) because no `AuthenticationEntryPoint` is configured in `SecurityConfig`.
