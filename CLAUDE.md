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
JAVA_HOME=... $MVN test -Dtest=EventServiceTest

# Package jar
JAVA_HOME=... $MVN package -DskipTests

# Run packaged jar
java -jar target/event-manager-1.0.0.jar
```

All config values default to localhost with `postgres/postgres` credentials. Override via env vars: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `CASSANDRA_HOST`, `CASSANDRA_PORT`, `CASSANDRA_KEYSPACE`, `CASSANDRA_DATACENTER`, `JWT_SECRET`, `JWT_EXPIRATION_MS`.

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

### Authorization model
Defined in `SecurityConfig.securityFilterChain`:
- Public (no token): `GET /api/events/**`, `GET /api/venues/**`, `GET /api/performers/**`, `POST /api/auth/**`
- Authenticated (`ROLE_USER` or `ROLE_ADMIN`): `POST/PUT /api/events/**`
- Admin only (`ROLE_ADMIN`): `POST/PUT/DELETE /api/venues/**`, `POST/PUT/DELETE /api/performers/**`, `DELETE /api/events/**`, `/api/admin/**`

Fine-grained rules use `@PreAuthorize` on controller methods; the filter chain rules are the outer gate.

### Redis caching (events only)
`EventService` is the only service with cache annotations. The cache name is `"events"`, keyed by event ID:
- `@Cacheable` on `getEventById` — cache miss hits DB, subsequent calls return cached `EventResponse`
- `@CachePut` on `createEvent` and `updateEvent` — always writes through to cache
- `@CacheEvict` on `deleteEvent` — removes the entry

`VenueService` and `PerformerService` have no caching. `getAllEvents` is not cached (list result invalidation is not implemented).

Cache is configured in `RedisConfig` with JSON serialization (`GenericJackson2JsonRedisSerializer`) and a 1-hour TTL. `EventResponse`, `VenueDto`, and `PerformerDto` all implement `Serializable` for this reason.

### Data model relationships
- `Event` → `Venue`: `@ManyToOne` (an event has exactly one venue; venue is not owning side)
- `Event` ↔ `Performer`: `@ManyToMany` via join table `event_performers`; `Event` is the owning side
- All associations are `FETCH_TYPE.LAZY`; `EventRepository` uses JPQL `JOIN FETCH` queries (`findByIdWithDetails`, `findAllWithDetails`) to load associations in a single query and avoid N+1

### JWT flow
`JwtTokenProvider` reads `jwt.secret` (BASE64-encoded) and `jwt.expiration-ms` from config. Token contains only the username as subject. On each request, `JwtAuthenticationFilter` extracts the token, validates it, loads `UserDetails` from DB, and sets `UsernamePasswordAuthenticationToken` in the `SecurityContext`.

### DTO separation
Controllers accept/return DTOs, never entities. `EventRequest` carries `venueId` and `Set<Long> performerIds` for write operations. `EventResponse` carries embedded `VenueDto` and `Set<PerformerDto>` for reads. Mapping is done in service `toResponse()` methods, not via a separate mapper library.

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
`application-test.yml` (activated by `@ActiveProfiles("test")`) swaps Postgres for H2 in-memory, sets `spring.cache.type: none` so Redis is not required, and excludes `CassandraAutoConfiguration` + `CassandraRepositoriesAutoConfiguration` so Cassandra is not required during tests.
