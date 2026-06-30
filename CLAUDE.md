# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

**Java version requirement:** Lombok 1.18.32 is incompatible with Java 24. Always use Java 21.

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
export MVN=/Users/jimrice1959/.sdkman/candidates/maven/current/bin/mvn
```

```bash
# App only — starts infrastructure + app container (port 8080); no Traefik
docker compose up -d

# App + Traefik — adds Traefik reverse proxy (port 80) and dashboard (port 9000)
docker compose --profile traefik up -d

# Compile
JAVA_HOME=... $MVN compile

# Run application locally (against docker compose infrastructure only)
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
  -e KAFKA_BOOTSTRAP_SERVERS=host.docker.internal:9092 \
  event-manager
```

The Dockerfile is a two-stage build:
- **Build stage** (`maven:3.9-eclipse-temurin-21`): `pom.xml` is copied first and `dependency:go-offline` is run as a separate layer, so Maven dependencies are cached between builds and only re-downloaded when `pom.xml` changes.
- **Runtime stage** (`eclipse-temurin:21-jre-alpine`): copies only the packaged jar into a minimal Alpine JRE image. No JDK, no Maven, no source in the final image.

`.dockerignore` excludes `target/`, `.git/`, `.claude/`, `*.md`, and `dump.rdb`.

All config values default to localhost with `postgres/postgres` credentials. Override via env vars: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `CASSANDRA_HOST`, `CASSANDRA_PORT`, `CASSANDRA_KEYSPACE`, `CASSANDRA_DATACENTER`, `KAFKA_BOOTSTRAP_SERVERS`, `JWT_SECRET`, `JWT_EXPIRATION_MS`, `OAUTH2_ISSUER`.

`docker compose up -d` also starts Prometheus (port 9090) and Grafana (port 3000, `admin`/`admin`). Prometheus scrapes `/actuator/prometheus` from both `app:8080` (docker-compose mode) and `host.docker.internal:8080` (local `mvn spring-boot:run` mode) — unreachable targets show as DOWN without affecting the other. In Grafana, add `http://prometheus:9090` as a Prometheus data source and import dashboard ID **4701** (JVM Micrometer) for HTTP and JVM metrics.

**Traefik** is optional, activated via the `traefik` profile:

| Command | What starts | App access |
|---|---|---|
| `docker compose up -d` | Infrastructure + app | `http://localhost:8080` |
| `docker compose --profile traefik up -d` | Infrastructure + app + Traefik | `http://localhost` (port 80) or `http://localhost:8080` (direct) |

Traefik dashboard: `http://localhost:9000` (only when running with the `traefik` profile). Traefik auto-discovers the `app` container via Docker labels; `exposedbydefault=false` ensures only labeled services are routed. Scale with `docker compose --profile traefik up -d --scale app=3` — Traefik load-balances across all instances automatically.

## Architecture

### Request flow
```
HTTP Request
  → Filter chain @Order(1): OAuth2AuthorizationServerConfigurer
  │    matches /oauth2/**, /.well-known/** only
  │    (all other requests fall through to @Order(2) or higher)
  │
  → Filter chain @Order(2): form login
  │    matches /login only — used by authorization_code flow
  │
  → Filter chain @Order(3): Web UI (session-based)
  │    matches /ui/** only
  │    → Spring Security form login (/ui/login)
  │    → Session + CSRF enabled
  │    → WebEventController / WebVenueController / WebPerformerController / WebAuthController
  │    → Same services as REST API
  │    → Thymeleaf template → HTML response
  │
  → Filter chain @Order(4): API (stateless)
       → JwtAuthenticationFilter (tries custom HS256 JWT first)
       → BearerTokenAuthenticationFilter (tries OAuth2 RS256 JWT if context not set)
       → SecurityFilterChain authorization rules
       → Controller (validates input with @Valid)
       → Service (business logic, Redis cache annotations)
       → Repository (JPA / PostgreSQL)
            ↓ (event and performer writes, fire-and-forget)
       → CassandraAsyncWriter (@Async → cassandraExecutor thread pool → EventCassandraRepository / PerformerCassandraRepository)
            ↓ (performer video add/delete, fire-and-forget with retry)
       → PerformerVideoEventPublisher (@Async → kafkaExecutor thread pool → KafkaTemplate → performer-video-events topic)
```

### API endpoints

**OAuth2 / OIDC endpoints** (handled by `@Order(1)` Authorization Server filter chain):

| Method | Path | Notes |
|---|---|---|
| GET | `/.well-known/openid-configuration` | OIDC discovery document |
| GET | `/.well-known/oauth-authorization-server` | OAuth2 server metadata |
| GET | `/oauth2/jwks` | Public key set (JWK Set) for token verification |
| POST | `/oauth2/token` | Issue access/refresh tokens (`client_credentials`, `authorization_code`, `refresh_token`) |
| GET | `/oauth2/authorize` | Start authorization_code flow (redirects to `/login`) |
| POST | `/oauth2/revoke` | Revoke a token |
| POST | `/oauth2/introspect` | Introspect a token |

**Application endpoints** (handled by `@Order(3)` API filter chain):

| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/api/auth/register` | public | returns custom JWT |
| POST | `/api/auth/login` | public | returns custom JWT |
| GET | `/api/events` | public | optional `?venueId=` or `?start=&end=` (ISO datetime) |
| GET | `/api/events/{id}` | public | cached |
| POST | `/api/events` | authenticated | Cassandra dual-write |
| PUT | `/api/events/{id}` | authenticated | Cassandra dual-write |
| POST | `/api/events/{id}/tickets/reserve` | authenticated | decrement `ticketsAvailable`; 400 if would go negative |
| POST | `/api/events/{id}/tickets/release` | authenticated | increment `ticketsAvailable`; 400 if would exceed venue capacity |
| DELETE | `/api/events/{id}` | ADMIN | Cassandra dual-write |
| GET | `/api/venues` | public | optional `?city=` |
| GET | `/api/venues/{id}` | public | cached |
| POST | `/api/venues` | ADMIN | |
| PUT | `/api/venues/{id}` | ADMIN | |
| DELETE | `/api/venues/{id}` | ADMIN | |
| GET | `/api/performers` | public | optional `?name=` or `?genre=` |
| GET | `/api/performers/{id}` | public | cached |
| POST | `/api/performers` | ADMIN | Cassandra dual-write |
| PUT | `/api/performers/{id}` | ADMIN | Cassandra dual-write |
| POST | `/api/performers/{id}/videos` | ADMIN | add video URL; deduplicates shared URLs; Cassandra dual-write; Kafka event |
| DELETE | `/api/performers/{id}/videos` | ADMIN | remove video URL from performer; Cassandra dual-write; Kafka event |
| DELETE | `/api/performers/{id}` | ADMIN | Cassandra dual-write |

### Disabling authentication for development

`DevSecurityConfig` (`com.eventmanager.config`, `@Profile("dev")`) defines a single `@Order(0)` filter chain that matches `/**` and calls `permitAll()` with CSRF disabled. Because `@Order(0)` is lower than all production chains (orders 1–4), it intercepts every request before any auth logic runs. The production `SecurityConfig` and `AuthorizationServerConfig` beans still load — they just never see any traffic.

Activate with any of:

```bash
# Maven
JAVA_HOME=... $MVN spring-boot:run -Dspring-boot.run.profiles=dev

# Packaged jar
java -Dspring.profiles.active=dev -jar target/event-manager-1.0.0.jar

# Environment variable (shell or docker compose)
SPRING_PROFILES_ACTIVE=dev
```

`application-dev.yml` is also loaded when the profile is active. It enables SQL logging (`spring.jpa.show-sql: true`) and sets `DEBUG` level for `com.eventmanager` and `org.springframework.security`. To re-enable authentication, remove the profile flag — no code changes needed.

### Authorization model
Defined in `SecurityConfig.securityFilterChain` (`@Order(4)`; bypassed when the `dev` profile is active):
- Public (no token): `GET /api/events/**`, `GET /api/venues/**`, `GET /api/performers/**`, `POST /api/auth/**`, `/actuator/health`, `/actuator/prometheus`
- Authenticated (`ROLE_USER` or `ROLE_ADMIN`): `POST/PUT /api/events/**` (includes ticket reserve/release sub-routes)
- Admin only (`ROLE_ADMIN`): `POST/PUT/DELETE /api/venues/**`, `POST/PUT/DELETE /api/performers/**` (includes video sub-routes), `DELETE /api/events/**`, `/api/admin/**`

Fine-grained rules use `@PreAuthorize` on controller methods; the filter chain rules are the outer gate.

Unauthenticated requests to protected endpoints return **401** — `oauth2ResourceServer` installs a `BearerTokenAuthenticationEntryPoint`. Authenticated-but-insufficient-role requests return **403** (from `GlobalExceptionHandler.handleAccessDeniedException`).

### Thymeleaf web UI

`spring-boot-starter-thymeleaf` and `thymeleaf-extras-springsecurity6` are on the classpath. All UI pages are served under `/ui/**` by a dedicated set of `@Controller` classes in `com.eventmanager.web`. The REST API under `/api/**` is entirely unchanged.

**Authentication for the web UI** is session-based and completely separate from the JWT-based REST API. When a user POSTs to `POST /ui/login`, Spring Security validates credentials against the same `UserDetailsServiceImpl` / user table, creates an `HttpSession`, and redirects to `/ui/events`. The session cookie is used for all subsequent `/ui/**` requests. CSRF protection is enabled on the web filter chain (Spring Security default); Thymeleaf injects the CSRF token automatically into all `th:action` forms.

**Web security filter chain** (`SecurityConfig.webFilterChain`, `@Order(3)`, `securityMatcher("/ui/**")`):
- `GET /ui/login` — public (login page)
- `GET /ui/events`, `GET /ui/events/{id}` — public (read-only event browsing)
- `GET /ui/venues`, `GET /ui/venues/{id}` — public
- `GET /ui/performers`, `GET /ui/performers/{id}` — public
- All other `/ui/**` — requires authentication; individual write/delete methods also use `@PreAuthorize` for role checks

**Web controllers** (`com.eventmanager.web`):

| Controller | Base path | Notes |
|---|---|---|
| `WebAuthController` | `/ui/login`, `/ui` | Login page GET; `GET /ui` redirects to `/ui/events` |
| `WebEventController` | `/ui/events` | Full CRUD; `@InitBinder` handles `datetime-local` input format |
| `WebVenueController` | `/ui/venues` | Full CRUD; city filter on list |
| `WebPerformerController` | `/ui/performers` | Full CRUD + video add/remove; name/genre filter on list |

All web controllers delegate directly to the existing services (`EventService`, `VenueService`, `PerformerService`) — no duplicate business logic. Flash attributes (`RedirectAttributes.addFlashAttribute`) carry success messages across the POST-redirect-GET cycle.

**`datetime-local` binding** — HTML `<input type="datetime-local">` produces values in the format `yyyy-MM-dd'T'HH:mm` (no seconds). Spring MVC's default `LocalDateTime` converter expects full ISO-8601. `WebEventController` registers a `PropertyEditorSupport` via `@InitBinder` that parses and formats `LocalDateTime` using `DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")`, avoiding any change to `EventRequest` or application.yml.

**Delete pattern** — HTML forms only support GET and POST. Delete actions use `POST /ui/{entity}/{id}/delete` with a JavaScript `confirm()` dialog on the submit button. No `HiddenHttpMethodFilter` is needed.

**Thymeleaf templates** (`src/main/resources/templates/`):

| File | Purpose |
|---|---|
| `login.html` | Standalone login card; no nav |
| `fragments/nav.html` | `th:fragment="nav"` — Bootstrap 5 navbar included in every page via `th:replace`; uses `sec:authorize` to show/hide Login vs. username + Logout |
| `events/list.html` | Card grid of all events; Create button visible to authenticated users |
| `events/view.html` | Event detail: venue link, performer badges, ticket counts |
| `events/form.html` | Create/edit form; venue select dropdown, performer checkboxes; reused for both create (`POST /ui/events`) and edit (`POST /ui/events/{id}/edit`) via conditional `th:action` |
| `venues/list.html` | Table with city filter form |
| `venues/view.html` | Venue detail |
| `venues/form.html` | Create/edit form |
| `performers/list.html` | Card grid with name/genre search |
| `performers/view.html` | Performer detail; admin panel for adding/removing video URLs inline |
| `performers/form.html` | Create/edit form (name, genre, bio only — videos managed on the view page) |

**`sec:authorize` in templates** — `thymeleaf-extras-springsecurity6` provides the `sec:` namespace. Admin-only buttons (Create venue, Edit/Delete venue, Edit/Delete performer, video management panel) are wrapped in `sec:authorize="hasRole('ADMIN')"` so they are not rendered for non-admin users. The server-side `@PreAuthorize` annotation on each controller method enforces the same rules independently.

**URL summary:**

| Method | Path | Auth | Action |
|---|---|---|---|
| GET | `/ui` | public | Redirect to `/ui/events` |
| GET | `/ui/login` | public | Login page |
| POST | `/ui/login` | public | Spring Security processes credentials |
| POST | `/ui/logout` | authenticated | Invalidates session |
| GET | `/ui/events` | public | List all events |
| GET | `/ui/events/{id}` | public | View event |
| GET | `/ui/events/new` | authenticated | New event form |
| POST | `/ui/events` | authenticated | Create event |
| GET | `/ui/events/{id}/edit` | authenticated | Edit form pre-filled from existing event |
| POST | `/ui/events/{id}/edit` | authenticated | Update event |
| POST | `/ui/events/{id}/delete` | ADMIN | Delete event |
| GET | `/ui/venues` | public | List venues (optional `?city=`) |
| GET | `/ui/venues/{id}` | public | View venue |
| GET | `/ui/venues/new` | ADMIN | New venue form |
| POST | `/ui/venues` | ADMIN | Create venue |
| GET | `/ui/venues/{id}/edit` | ADMIN | Edit form |
| POST | `/ui/venues/{id}/edit` | ADMIN | Update venue |
| POST | `/ui/venues/{id}/delete` | ADMIN | Delete venue |
| GET | `/ui/performers` | public | List performers (optional `?name=` or `?genre=`) |
| GET | `/ui/performers/{id}` | public | View performer; admin video panel |
| GET | `/ui/performers/new` | ADMIN | New performer form |
| POST | `/ui/performers` | ADMIN | Create performer |
| GET | `/ui/performers/{id}/edit` | ADMIN | Edit form |
| POST | `/ui/performers/{id}/edit` | ADMIN | Update performer |
| POST | `/ui/performers/{id}/videos/add` | ADMIN | Add video URL |
| POST | `/ui/performers/{id}/videos/delete` | ADMIN | Remove video URL |
| POST | `/ui/performers/{id}/delete` | ADMIN | Delete performer |

### Exception handling
`GlobalExceptionHandler` (`@RestControllerAdvice`) maps exceptions to HTTP responses:

| Exception | Status | Notes |
|---|---|---|
| `ResourceNotFoundException` | 404 | Message from exception |
| `AccessDeniedException` | 403 | Fixed message "Access denied"; must be declared before the `Exception` catch-all or `@PreAuthorize` rejections return 500 |
| `BadCredentialsException` | 401 | Fixed message "Invalid username or password" |
| `IllegalArgumentException` | 400 | Message from exception; used by `AuthService` for duplicate username/email and by `EventService` for invalid ticket counts |
| `MethodArgumentNotValidException` | 400 | Returns `{ status, errors: { field: message }, timestamp }` — different shape from `ErrorResponse` |
| `Exception` (catch-all) | 500 | Generic message |

`ErrorResponse` is a record `(int status, String message)` with a computed `timestamp()` method.

### Redis caching
All three services cache individual records by ID with a 1-hour TTL:

| Cache name | Service | Cacheable | CachePut | CacheEvict |
|---|---|---|---|---|
| `"events"` | `EventService` | `getEventById` | `createEvent`, `updateEvent`, `reserveTickets`, `releaseTickets` | `deleteEvent` |
| `"venues"` | `VenueService` | `getVenueById` | `createVenue`, `updateVenue` | `deleteVenue` |
| `"performers"` | `PerformerService` | `getPerformerById` | `createPerformer`, `updatePerformer`, `addVideo`, `deleteVideo` | `deletePerformer` |

List endpoints (`getAll*`, `getVenuesByCity`, `searchPerformers`, `getPerformersByGenre`, `getEventsByVenue`, `getEventsBetween`) are not cached — list invalidation is not implemented.

Cache is configured in `RedisConfig` with JSON serialization (`GenericJackson2JsonRedisSerializer`) and a 1-hour TTL. `EventResponse`, `VenueDto`, and `PerformerDto` all implement `Serializable` for this reason.

### Data model relationships
- `Event` → `Venue`: `@ManyToOne` (an event has exactly one venue; venue is not owning side)
- `Event` ↔ `Performer`: `@ManyToMany` via join table `event_performers`; `Event` is the owning side
- `Performer` ↔ `Video`: `@ManyToMany` via join table `performer_videos` (`performer_id`, `video_id`); `Performer` is the owning side. `Video` rows are deduplicated by URL — `addVideo` reuses an existing `Video` if the URL already exists. Removing a video from a performer does not delete the `Video` row (other performers may reference it).
- All associations are `FETCH_TYPE.LAZY`; `EventRepository` uses JPQL `JOIN FETCH` queries (`findByIdWithDetails`, `findAllWithDetails`) and `PerformerRepository` uses `LEFT JOIN FETCH` queries (`findAllWithVideos`, `findByIdWithVideos`, etc.) to load associations in a single query and avoid N+1. `LEFT JOIN FETCH` is used for performers so that performers without any videos are still returned.

### Authentication flows

**Custom JWT** (`POST /api/auth/login` → `Authorization: Bearer <token>`)
`JwtTokenProvider` reads `jwt.secret` (BASE64-encoded, HMAC-SHA) and `jwt.expiration-ms` from config. Token contains only the username as subject. On each request, `JwtAuthenticationFilter` runs before `BearerTokenAuthenticationFilter`: it validates the HMAC signature, loads `UserDetails` from DB, and sets `UsernamePasswordAuthenticationToken` in the `SecurityContext`. `validateToken` catches all JJWT exceptions including `JwtException` (catch-all for algorithm mismatches when an RS256 OAuth2 token arrives) — returning false allows `BearerTokenAuthenticationFilter` to try next.

**OAuth2 `client_credentials`** (M2M — no user login):
```bash
curl -X POST http://localhost:8080/oauth2/token \
  -u event-manager-client:secret \
  -d "grant_type=client_credentials&scope=read write"
```
Returns an RS256-signed JWT with a `scope` claim (`SCOPE_read`, `SCOPE_write`). Custom JWT filter returns false (wrong algorithm) → `BearerTokenAuthenticationFilter` validates it and produces a `JwtAuthenticationToken` with `SCOPE_*` authorities.

**OAuth2 `authorization_code`** (user-delegated):
1. `GET /oauth2/authorize?response_type=code&client_id=event-manager-client&scope=openid+read+write&redirect_uri=http://localhost:8080/authorized`
2. AS redirects browser to `/login` → user logs in with username/password via form
3. AS issues auth code, redirects to `redirect_uri?code=...`
4. `POST /oauth2/token` with `grant_type=authorization_code&code=...`
5. Returns RS256 JWT with both `scope` and `roles` claims (e.g. `["ROLE_ADMIN"]`). `JwtAuthenticationConverter` maps `roles` → `ROLE_*` authorities, so `@PreAuthorize("hasRole('ADMIN')")` works.

Registered client: `clientId=event-manager-client`, `clientSecret=secret` (BCrypt-encoded). Secret rotates on restart unless externalized.

### DTO separation
Controllers accept/return DTOs, never entities. `EventRequest` carries `venueId` and `Set<Long> performerIds` for write operations. `EventResponse` carries embedded `VenueDto` and `Set<PerformerDto>` for reads. `TicketRequest` carries a single `count` field (`@NotNull @Min(1)`) used by the reserve/release endpoints. `VideoRequest` carries a single `url` field (`@NotBlank @URL`) used by the performer video endpoints. `PerformerDto` includes `Set<String> videoUrls` as output (populated from the join); video management is done through dedicated endpoints, not through the create/update payload. Mapping is done in service `toResponse()` / `toDto()` methods, not via a separate mapper library.

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
| `EventService` | `EventRepository`, `VenueRepository`, `PerformerRepository`, `VenueService`, `PerformerService`, `CassandraAsyncWriter` |
| `VenueService` | `VenueRepository` |
| `PerformerService` | `PerformerRepository`, `VideoRepository`, `CassandraAsyncWriter`, `PerformerVideoEventPublisher` |
| `UserDetailsServiceImpl` | `UserRepository` |
| `JwtAuthenticationFilter` | `JwtTokenProvider`, `UserDetailsServiceImpl` |
| `SecurityConfig` | `UserDetailsServiceImpl`, `JwtAuthenticationFilter` |

**Field injection via `@Autowired(required = false)` — optional infrastructure beans**
`CassandraAsyncWriter` holds two optional repositories (`PerformerCassandraRepository`, `EventCassandraRepository`), both field-injected with `required = false`. `PerformerVideoEventPublisher` holds an optional `KafkaTemplate<String, VideoEvent>`, also field-injected with `required = false`. Spring skips injection when the beans are absent (test profile excludes Cassandra and Kafka autoconfiguration). Each method short-circuits with a null check. Constructor injection cannot express optionality without an `Optional<>` wrapper.

**`@Bean` factory methods in `@Configuration` classes — explicit bean registration**
`SecurityConfig` registers `PasswordEncoder`, `DaoAuthenticationProvider`, `AuthenticationManager`, and `CorsConfigurationSource` manually because they require configuration logic Spring cannot infer. `RedisConfig` registers a custom `RedisCacheManager` with JSON serialization and a 1-hour TTL, overriding Spring's default Java-serialization cache manager. `AuthorizationServerConfig` registers `RegisteredClientRepository`, `JWKSource`, `JwtDecoder`, `AuthorizationServerSettings`, and `OAuth2TokenCustomizer` — all of these have `@ConditionalOnMissingBean` in the Spring Boot auto-configuration so providing them explicitly prevents any auto-config from firing.

Spring Data JPA repositories (`EventRepository`, `VenueRepository`, etc.) are registered automatically by the `spring-boot-starter-data-jpa` infrastructure — no annotation is needed on them beyond `extends JpaRepository`. `@EnableJpaRepositories(basePackages = "com.eventmanager.repository")` on `EventManagerApplication` scopes this scan to exclude the Cassandra repository package.

### I/O model
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

### Transaction conventions
All service methods are explicitly annotated — no implicit transaction boundary is relied upon:
- Read-only methods use `@Transactional(readOnly = true)` — allows connection reuse and DB-side read optimization
- Write methods use `@Transactional` — rolls back on any unchecked exception
- `AuthService.login` is `@Transactional(readOnly = true)`: it makes two DB reads (one via `authenticationManager.authenticate` → `UserDetailsServiceImpl`, one direct `userRepository.findByUsername`) and wrapping them ensures a single connection

Cassandra writes in `PerformerService` and `EventService` happen after the JPA call and are outside the Postgres transaction boundary. A Cassandra failure after Postgres commits is not rolled back — accepted limitation of dual-store without a distributed transaction coordinator.

### Logging
`PerformerService` uses `@Slf4j` (Lombok) with structured parameterized log statements. Log level conventions:

| Level | When |
|---|---|
| `INFO` | Mutation entry and success (`createPerformer`, `updatePerformer`, `deletePerformer`) |
| `WARN` | Not-found paths before throwing `ResourceNotFoundException` |
| `DEBUG` | Read operations (query params, result counts) |

`CassandraAsyncWriter` also uses `@Slf4j`: `DEBUG` on successful async save/delete, `ERROR` on exception (with stack trace).

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

### OAuth 2.0 Authorization Server + Resource Server
`spring-boot-starter-oauth2-authorization-server` is on the classpath. The app acts as both an OAuth2 Authorization Server (issues RS256 JWTs) and a Resource Server (validates them). No external auth server is needed.

**Four security filter chains** (order matters — first match wins):

| Order | Class | `securityMatcher` | Purpose |
|---|---|---|---|
| 1 | `AuthorizationServerConfig.authorizationServerSecurityFilterChain` | AS endpoints (`/oauth2/**`, `/.well-known/**`) | Issues and manages tokens; redirects browsers to `/login` |
| 2 | `AuthorizationServerConfig.formLoginSecurityFilterChain` | `/login` | Provides form login page for `authorization_code` user authentication |
| 3 | `SecurityConfig.webFilterChain` | `/ui/**` | Session-based web UI: form login, CSRF enabled, Thymeleaf pages |
| 4 | `SecurityConfig.securityFilterChain` | everything else | Stateless API: custom JWT → OAuth2 Bearer → authorization rules |

**`AuthorizationServerConfig`** — all AS beans live here:
- `JWKSource<SecurityContext>`: RSA 2048-bit key pair generated at startup. Used to sign tokens and exposed at `/oauth2/jwks`. Rotates on every restart — externalize to a persistent key store for production.
- `JwtDecoder`: wraps the JWK source; used by both the AS internally and the `@Order(4)` Resource Server chain to validate tokens.
- `AuthorizationServerSettings`: issuer = `${OAUTH2_ISSUER:http://localhost:8080}`. Sets the `iss` claim in all tokens and the `issuer` field in the OIDC discovery document.
- `RegisteredClientRepository` (in-memory): one client — `event-manager-client` / `secret` (BCrypt), grants `client_credentials` + `authorization_code` + `refresh_token`, scopes `openid read write`, redirect URI `http://localhost:8080/authorized`.
- `OAuth2TokenCustomizer<JwtEncodingContext>`: for `authorization_code` access tokens only, loads the authenticated user's `GrantedAuthority` list and adds it as a `roles` claim (e.g. `["ROLE_ADMIN"]`). `client_credentials` tokens carry only `scope` claims (no user principal exists).

**Resource Server side** (`SecurityConfig.securityFilterChain`):
- `addFilterBefore(jwtAuthenticationFilter, BearerTokenAuthenticationFilter.class)`: custom JWT filter runs first. If it succeeds (HMAC validates, UserDetails loaded), SecurityContext is set and `BearerTokenAuthenticationFilter` skips. If the token is RS256 (OAuth2), `validateToken` returns false (JJWT's `JwtException` catch-all handles algorithm mismatches) and `BearerTokenAuthenticationFilter` takes over.
- `JwtAuthenticationConverter`: maps `scope` claim → `SCOPE_*` authorities (for `client_credentials` tokens) and `roles` claim → `ROLE_*` authorities (for `authorization_code` tokens), so `@PreAuthorize("hasRole('ADMIN')")` works for both token types.

**OIDC discovery:** `GET /.well-known/openid-configuration` — returns issuer, authorization endpoint, token endpoint, JWKS URI, supported grant types, scopes, and signing algorithms. Useful for configuring external clients.

### Kafka messaging (performer video events)
`spring-kafka` + `spring-retry` are on the classpath. Kafka is optional — `KafkaTemplate` is `@Autowired(required = false)` and `KafkaAutoConfiguration` is excluded in the test profile, so the app starts and tests pass without a broker.

**Infrastructure:** `apache/kafka:3.9.0` (official Apache image) in KRaft mode (no ZooKeeper). Dual listeners: `EXTERNAL://localhost:9092` for host-to-container access, `INTERNAL://kafka:29092` for container-to-container. The `app` service connects via `KAFKA_BOOTSTRAP_SERVERS: kafka:29092`. Healthcheck uses `kafka-topics.sh --list` with `start_period: 30s`. A fixed `CLUSTER_ID` env var is required by the Apache image for KRaft storage formatting (Bitnami generated this automatically). Env vars use `KAFKA_` prefix (e.g. `KAFKA_NODE_ID`) rather than the `KAFKA_CFG_` prefix Bitnami used.

**`VideoEvent`** — `kafka/VideoEvent.java` — Java record: `(String operation, Long performerId, Long videoId)`. `operation` is `"ADD"` or `"DELETE"`. Serialized to JSON by `JsonSerializer`.

**`PerformerVideoEventPublisher`** — `kafka/PerformerVideoEventPublisher.java`:
- Topic: `performer-video-events` (constant `TOPIC`)
- Performer ID is used as the Kafka message key so all events for a given performer land on the same partition (ordered delivery)
- `publish(VideoEvent)` is `@Async("kafkaExecutor")` — dispatched to the `kafkaExecutor` thread pool, HTTP response returns before any Kafka I/O
- Inside the background thread, a `RetryTemplate` with `ExponentialBackOffPolicy(initialInterval=10ms, multiplier=2.0)` and `SimpleRetryPolicy(maxAttempts=10)` wraps `kafkaTemplate.send(TOPIC, key, event).get()`
- `.get()` blocks the background thread until the broker ACKs or fails — this is required to make send failures visible to the retry mechanism
- On each attempt failure: `WARN` logged with attempt number, performer ID, video ID, and error message
- After all 10 attempts exhausted: recovery callback logs `ERROR` with final exception; no exception propagates
- If `KafkaTemplate` is null (test profile): returns immediately

**Retry schedule** (10ms start, 2× doubling, no cap configured):

| Attempt | Delay before attempt |
|---|---|
| 1 | — |
| 2 | 10 ms |
| 3 | 20 ms |
| 4 | 40 ms |
| 5 | 80 ms |
| 6 | 160 ms |
| 7 | 320 ms |
| 8 | 640 ms |
| 9 | 1280 ms |
| 10 | 2560 ms |

**Why `RetryTemplate` not `@Retryable`:** Stacking `@Async` and `@Retryable` on the same method is broken — `@Async` submits to a thread pool and returns a proxy future immediately, so the `@Retryable` proxy on the calling thread has nothing to retry. Using `RetryTemplate` programmatically inside the already-dispatched `@Async` method avoids the AOP proxy ordering conflict. No `@EnableRetry` annotation is needed.

### Cassandra dual-write (events and performers)
Both `EventService` and `PerformerService` write to Postgres first (synchronous, within the JPA transaction), then fire an async Cassandra write via `CassandraAsyncWriter`. Postgres is the source of truth; Cassandra is a secondary store with no read path yet.

**Performers**
- Entity: `cassandra/model/CassandraPerformer.java` — `@Table("performers")` with `id` as partition key; fields: `name`, `genre`, `bio`, `videoUrls` (`Set<String>` → Cassandra `SET<text>`)
- Repository: `cassandra/repository/PerformerCassandraRepository.java` — `CassandraRepository<CassandraPerformer, Long>`
- `PerformerService` calls `cassandraAsyncWriter.savePerformer()` / `deletePerformer()` after the Postgres write; `addVideo` and `deleteVideo` also call `savePerformer()` so the Cassandra `video_urls` set stays in sync

**Events**
- Entity: `cassandra/model/CassandraEvent.java` — `@Table("events")` with `id` as partition key; fields: `name`, `description`, `eventDate`, `ticketPrice`, `ticketsAvailable`, `venueId`, `createdAt`, `updatedAt`. The `ManyToMany` performers relationship is not stored — it maps poorly to a Cassandra column and `CassandraPerformer` does not store event IDs either.
- Repository: `cassandra/repository/EventCassandraRepository.java` — `CassandraRepository<CassandraEvent, Long>`
- `EventService` calls `cassandraAsyncWriter.saveEvent()` / `deleteEvent()` after the Postgres write; `toCassandraEntity(EventResponse)` maps the response DTO to `CassandraEvent`, deriving `venueId` from `response.getVenue().getId()`. `reserveTickets` and `releaseTickets` also call `cassandraAsyncWriter.saveEvent()` after updating Postgres.

**`CassandraAsyncWriter`**
Holds both repositories with `@Autowired(required = false)` field injection. Each method null-checks its repository before writing, so the bean operates safely when Cassandra is excluded (test profile). Methods: `savePerformer`, `deletePerformer`, `saveEvent`, `deleteEvent` — all `@Async("cassandraExecutor")`.

`spring.cassandra.schema-action: create_if_not_exists` auto-creates both tables on startup. The `event_manager` keyspace is created by the `cassandra-init` container in docker-compose on first `docker compose up -d`. Cassandra takes ~60 s to start; the healthcheck has `start_period: 60s`.

`@EnableJpaRepositories(basePackages = "com.eventmanager.repository")` on `EventManagerApplication` prevents Spring Data JPA from scanning the `cassandra.repository` package, avoiding multi-store conflicts.

### EventManagerClient

`com.eventmanager.client.EventManagerClient` is a `RestTemplate`-based client that covers every API endpoint. It is intended for use by other Spring applications that need to call this service programmatically.

**Construction:**

```java
// Default RestTemplate
EventManagerClient client = new EventManagerClient("http://localhost:8080");

// Inject a pre-configured RestTemplate (custom timeouts, interceptors, etc.)
EventManagerClient client = new EventManagerClient("http://localhost:8080", restTemplate);
```

**Authentication:** Call `login()` once — the returned JWT is stored internally and added as a `Bearer` token to all subsequent authenticated requests. Alternatively, call `setToken()` to inject an externally obtained token (e.g. an OAuth2 `client_credentials` token).

```java
client.login("admin", "password");   // stores JWT automatically
// or
client.setToken(oauthAccessToken);   // inject any bearer token
```

Public `GET` endpoints (read operations on events, venues, performers) send no `Authorization` header and work without calling `login()` first.

**Error handling:** 4xx responses throw `HttpClientErrorException`; 5xx responses throw `HttpServerErrorException`. Both carry the HTTP status and response body.

**Method reference:**

| Method | HTTP | Endpoint | Auth |
|---|---|---|---|
| `register(RegisterRequest)` | POST | `/api/auth/register` | public |
| `login(username, password)` | POST | `/api/auth/login` | public |
| `getEvents()` | GET | `/api/events` | public |
| `getEventsByVenue(venueId)` | GET | `/api/events?venueId=` | public |
| `getEventsBetween(start, end)` | GET | `/api/events?start=&end=` | public |
| `getEvent(id)` | GET | `/api/events/{id}` | public |
| `createEvent(EventRequest)` | POST | `/api/events` | authenticated |
| `updateEvent(id, EventRequest)` | PUT | `/api/events/{id}` | authenticated |
| `reserveTickets(id, count)` | POST | `/api/events/{id}/tickets/reserve` | authenticated |
| `releaseTickets(id, count)` | POST | `/api/events/{id}/tickets/release` | authenticated |
| `deleteEvent(id)` | DELETE | `/api/events/{id}` | ADMIN |
| `getVenues()` | GET | `/api/venues` | public |
| `getVenuesByCity(city)` | GET | `/api/venues?city=` | public |
| `getVenue(id)` | GET | `/api/venues/{id}` | public |
| `createVenue(VenueDto)` | POST | `/api/venues` | ADMIN |
| `updateVenue(id, VenueDto)` | PUT | `/api/venues/{id}` | ADMIN |
| `deleteVenue(id)` | DELETE | `/api/venues/{id}` | ADMIN |
| `getPerformers()` | GET | `/api/performers` | public |
| `searchPerformersByName(name)` | GET | `/api/performers?name=` | public |
| `getPerformersByGenre(genre)` | GET | `/api/performers?genre=` | public |
| `getPerformer(id)` | GET | `/api/performers/{id}` | public |
| `createPerformer(PerformerDto)` | POST | `/api/performers` | ADMIN |
| `updatePerformer(id, PerformerDto)` | PUT | `/api/performers/{id}` | ADMIN |
| `addVideo(performerId, url)` | POST | `/api/performers/{id}/videos` | ADMIN |
| `deleteVideo(performerId, url)` | DELETE | `/api/performers/{id}/videos` | ADMIN |
| `deletePerformer(id)` | DELETE | `/api/performers/{id}` | ADMIN |

List responses use `ParameterizedTypeReference` to preserve generic type information at runtime. `deleteEvent`, `deleteVenue`, and `deletePerformer` return `void` — a 204/200 with no body is a success.

### Test profile
`src/main/resources/application-test.yml` (activated by `@ActiveProfiles("test")`) swaps Postgres for H2 in-memory, sets `spring.cache.type: none` so Redis is not required, and excludes `CassandraAutoConfiguration`, `CassandraRepositoriesAutoConfiguration`, and `KafkaAutoConfiguration` so neither Cassandra nor Kafka is required during tests.

### Test classes

| Class | Style | Tests | Notes |
|---|---|---|---|
| `EventManagerApplicationTests` | `@SpringBootTest` | 1 | Context load smoke test |
| `PerformerServiceTest` | Mockito (`@ExtendWith(MockitoExtension.class)`) | 13 | Pure unit tests, no Spring context |
| `PerformerControllerTest` | `@SpringBootTest + @AutoConfigureMockMvc` | 16 | Full context with H2; mocks `PerformerService` |

**`PerformerServiceTest`** covers: `getAllPerformers`, `getPerformerById` (found/not found), `searchPerformers`, `getPerformersByGenre`, `createPerformer` (verifies `cassandraAsyncWriter.savePerformer` is called), `updatePerformer` (found/not found), `deletePerformer` (found/not found). Mocks `CassandraAsyncWriter`, `VideoRepository`, and `PerformerVideoEventPublisher` directly via constructor — no `ReflectionTestUtils` needed. Constructor: `new PerformerService(performerRepository, videoRepository, cassandraAsyncWriter, videoEventPublisher)`. The "absent Cassandra/Kafka" scenarios are handled by null-check guards in `CassandraAsyncWriter` and `PerformerVideoEventPublisher` and are not tested at the service level. Repository stubs use the new video-aware method names (`findAllWithVideos`, `findByIdWithVideos`, etc.).

**`PerformerControllerTest`** covers: happy-path responses, query param routing (`?name=`, `?genre=`), 400 on validation failure, 403 for `ROLE_USER` on admin endpoints, **401** for unauthenticated requests (changed from 403 when `oauth2ResourceServer` installed `BearerTokenAuthenticationEntryPoint`), 404 with error body.

**`@WebMvcTest` caveat:** `@EnableJpaRepositories` on `EventManagerApplication` causes `@WebMvcTest` slices to fail (JPA is forced into the context but `entityManagerFactory` isn't auto-configured by the slice). Controller tests use `@SpringBootTest + @AutoConfigureMockMvc + @ActiveProfiles("test")` instead.

**Async Cassandra/Kafka in service tests:** `@Async` is an AOP proxy feature — in a plain Mockito test with no Spring context, `CassandraAsyncWriter` and `PerformerVideoEventPublisher` methods run synchronously. This is fine: tests verify that the collaborators are called, not that they ran on a background thread. All four dependencies are constructor-injected into `PerformerService`, so `new PerformerService(performerRepository, videoRepository, cassandraAsyncWriter, videoEventPublisher)` with Mockito mocks is all that is needed — no `ReflectionTestUtils`.

**`AccessDeniedException` handling:** `GlobalExceptionHandler` has an explicit `@ExceptionHandler(AccessDeniedException.class)` returning 403. Without it, the catch-all `Exception` handler intercepts `@PreAuthorize` rejections and returns 500 instead of 403. Unauthenticated requests return **401** (not 403) because `oauth2ResourceServer` registers a `BearerTokenAuthenticationEntryPoint`; prior to adding OAuth2 support, no entry point was configured and Spring Security's default `Http403ForbiddenEntryPoint` applied.
