# Event Manager — Design Document

This document describes the architecture, conventions, and design decisions behind the Event Manager
application: a Spring Boot backend (REST API, Thymeleaf web UI, and OpenAPI schema) backed by
PostgreSQL, Redis, Cassandra, and Kafka, with a standalone React frontend. It consolidates the same
material that lives in `.claude/rules/` (split there for Claude Code's per-file context loading) into
one human-readable reference.

## Table of Contents

- [Build & Run Commands](#build--run-commands)
  - [Docker](#docker)
- [Frontend](#frontend)
- [Architecture](#architecture)
  - [Request flow](#request-flow)
  - [API endpoints](#api-endpoints)
  - [OpenAPI schema](#openapi-schema)
  - [Connecting to PostgreSQL](#connecting-to-postgresql)
  - [Development test users](#development-test-users)
  - [Disabling authentication for development](#disabling-authentication-for-development)
  - [Authorization model](#authorization-model)
  - [Rate limiting](#rate-limiting)
  - [Thymeleaf web UI](#thymeleaf-web-ui)
  - [gRPC API server](#grpc-api-server)
  - [Exception handling](#exception-handling)
  - [Redis caching](#redis-caching)
  - [Data model relationships](#data-model-relationships)
  - [Ticket status state machine](#ticket-status-state-machine)
  - [Payment service integration (gRPC client)](#payment-service-integration-grpc-client)
  - [Authentication flows](#authentication-flows)
  - [DTO separation](#dto-separation)
  - [Dependency injection](#dependency-injection)
  - [I/O model](#io-model)
  - [Transaction conventions](#transaction-conventions)
  - [Logging](#logging)
  - [Monitoring](#monitoring)
  - [Kubernetes](#kubernetes)
  - [OAuth 2.0 Authorization Server + Resource Server](#oauth-20-authorization-server--resource-server)
  - [Kafka messaging (performer video events)](#kafka-messaging-performer-video-events)
  - [Kafka consumer skeleton](#kafka-consumer-skeleton)
  - [Cassandra dual-write (events, performers, and ticket operations) and ticket backup](#cassandra-dual-write-events-performers-and-ticket-operations-and-ticket-backup)
  - [EventManagerClient](#eventmanagerclient)
  - [Test profile](#test-profile)
  - [Test classes](#test-classes)

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

# Compile — also runs protobuf-maven-plugin, generating gRPC stubs for both this app's own gRPC
# server (src/main/proto/event_manager_api.proto, venue_api.proto, performer_api.proto) and its
# gRPC client to the external payment service (payment_service.proto). The first build on a
# machine needs network access: os-maven-plugin + protobuf-maven-plugin download a native
# protoc/protoc-gen-grpc-java binary from Maven Central for the host's OS/arch classifier.
JAVA_HOME=... $MVN compile

# Run application locally (against docker compose infrastructure only)
JAVA_HOME=... $MVN spring-boot:run

# Run all tests (requires H2; Redis/Postgres not needed for tests)
JAVA_HOME=... $MVN test

# Run a single test class
JAVA_HOME=... $MVN test -Dtest=VenueServiceTest
JAVA_HOME=... $MVN test -Dtest=EventServiceTest
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
docker run -p 8080:8080 -p 9091:9091 \
  -e DB_HOST=host.docker.internal \
  -e REDIS_HOST=host.docker.internal \
  -e CASSANDRA_HOST=host.docker.internal \
  -e KAFKA_BOOTSTRAP_SERVERS=host.docker.internal:9092 \
  event-manager
```

The Dockerfile is a two-stage build:
- **Build stage** (`maven:3.9-eclipse-temurin-21`): `pom.xml` is copied first and `dependency:go-offline` is run as a separate layer, so Maven dependencies are cached between builds and only re-downloaded when `pom.xml` changes. `dependency:go-offline` only pre-fetches declared `<dependencies>`, not plugin-execution artifacts — `protobuf-maven-plugin`'s native protoc/protoc-gen-grpc-java binaries are still fetched during the actual build step, so the image build needs network access even with a warm dependency cache.
- **Runtime stage** (`eclipse-temurin:21-jre-alpine`): copies only the packaged jar into a minimal Alpine JRE image. No JDK, no Maven, no source in the final image.

`.dockerignore` excludes `target/`, `.git/`, `.claude/`, `*.md`, and `dump.rdb`. `EXPOSE 8080 9091` covers both the REST API/web UI and this app's own gRPC server.

All config values default to localhost with `postgres/postgres` credentials. Override via env vars: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `CASSANDRA_HOST`, `CASSANDRA_PORT`, `CASSANDRA_KEYSPACE`, `CASSANDRA_DATACENTER`, `KAFKA_BOOTSTRAP_SERVERS`, `JWT_SECRET`, `JWT_EXPIRATION_MS`, `OAUTH2_ISSUER`, `PAYMENT_SERVICE_HOST`, `PAYMENT_SERVICE_PORT`, `PAYMENT_SERVICE_DEADLINE`, `PAYMENT_SERVICE_CONNECT_TIMEOUT`, `GRPC_API_ENABLED`, `GRPC_API_PORT`, `SERVER_SSL_ENABLED`, `SERVER_SSL_KEY_STORE`, `SERVER_SSL_KEY_STORE_PASSWORD`, `SERVER_SSL_KEY_STORE_TYPE`, `SERVER_SSL_KEY_ALIAS`, `GRPC_API_TLS_ENABLED`, `GRPC_API_TLS_CERT_CHAIN_FILE`, `GRPC_API_TLS_PRIVATE_KEY_FILE`. There is no payment service in docker-compose today — `PaymentGrpcConfig`'s channel is lazy (doesn't connect until a purchase is attempted), so the app starts fine without one. Both the REST API/web UI (HTTPS, via `server.ssl.*`) and this app's own gRPC server (`grpc-api.tls.*`) are plaintext by default; `certs/generate-dev-certs.sh` generates a matching self-signed cert for local TLS testing of both (see certs/README.md).

`docker compose up -d` also starts Prometheus (port 9090) and Grafana (port 3000, `admin`/`admin`). Prometheus scrapes `/actuator/prometheus` from both `app:8080` (docker-compose mode) and `host.docker.internal:8080` (local `mvn spring-boot:run` mode) — unreachable targets show as DOWN without affecting the other. In Grafana, add `http://prometheus:9090` as a Prometheus data source and import dashboard ID **4701** (JVM Micrometer) for HTTP and JVM metrics.

**Traefik** is optional, activated via the `traefik` profile:

| Command | What starts | App access |
|---|---|---|
| `docker compose up -d` | Infrastructure + app | `http://localhost:8080` |
| `docker compose --profile traefik up -d` | Infrastructure + app + Traefik | `http://localhost` (port 80) or `http://localhost:8080` (direct) |

Traefik dashboard: `http://localhost:9000` (only when running with the `traefik` profile). Traefik auto-discovers the `app` container via Docker labels; `exposedbydefault=false` ensures only labeled services are routed. Scale with `docker compose --profile traefik up -d --scale app=3` — Traefik load-balances across all instances automatically.

## Frontend

`frontend/` is a standalone Vite + React 19 + TypeScript single-page app that consumes the JSON REST API under `/api/v1/**`. It is independent of the server-rendered Thymeleaf UI under `/ui/**` (see [Thymeleaf web UI](#thymeleaf-web-ui)) — same backend, two separate frontends, no shared code.

```bash
cd frontend
npm install
npm run dev      # dev server on http://localhost:5173, proxies /api -> http://localhost:8080
npm run build     # tsc -b && vite build; output in frontend/dist
```

The backend must be running on port 8080 (`mvn spring-boot:run`, `docker compose up -d`, or the `dev` profile to skip auth) before `npm run dev` will return data — the frontend has no mock/offline mode.

**Structure:**

| Path | Purpose |
|---|---|
| `src/types.ts` | TypeScript interfaces mirroring every backend DTO 1:1 (`EventRequest`, `EventResponse`, `VenueDto`, `PerformerDto`, `TicketResponse`, `PerformerSummary`, `PurchaseTicketRequest`, `PagedResponse<T>`, `VideoRequest`, `AuthResponse`, etc.) |
| `src/api/client.ts` | Axios instance with an auth interceptor that attaches `Authorization: Bearer <token>` from `localStorage`; `extractErrorMessage()` unwraps the backend's `{status, message}` / `{status, errors}` error shapes |
| `src/api/{auth,events,performers,venues,tickets}.ts` | One thin wrapper function per backend endpoint — no business logic, just typed request/response. `tickets.ts` covers `getTicket`, `getAvailableTickets` (paginated, per event), `getMyTickets` (paginated, current user), `reserveTicket`/`releaseTicket`/`purchaseTicket`/`cancelTicket` |
| `src/context/AuthContext.tsx` | Holds the logged-in user (`username`, `email`, `role`); persists token + user to `localStorage` (`event-manager.token`, `event-manager.user`) so a page refresh doesn't lose the session — there is no `GET /api/v1/auth/me` endpoint to re-fetch from |
| `src/components/ProtectedRoute.tsx` | `AdminRoute` (`ROLE_ADMIN` only) and `RequireAuth` (any authenticated user, no role check) route guards, mirroring the backend's `@PreAuthorize` rules per endpoint — `AdminRoute` gates event/performer/venue create and edit routes, `RequireAuth` gates `/my-tickets`. Both redirect to `/login` with `state={{from: location}}` when unauthenticated, so `LoginPage` can send the user back after a successful login. **Client-side only** — a UX convenience, not a security boundary; the backend re-enforces every rule independently |
| `src/pages/*` | One component per screen: `LoginPage`, `RegisterPage`, `EventsPage`/`EventDetailPage`/`EventFormPage` (create/edit share one form; `ticketsTotal` is `readOnly` in the form when editing since it's immutable; `EventDetailPage` shows the live available-ticket count via `getNumAvailableTickets` plus a paginated, browsable list of `AVAILABLE` tickets with a per-ticket Reserve button), `MyTicketsPage` (paginated list of the current user's own tickets across all events, with Purchase/Release for `RESERVED` tickets and Cancel for `SOLD` ones — the only screen that surfaces the full ticket lifecycle), `PerformersPage`/`PerformerDetailPage`/`PerformerFormPage` (video add/remove lives on the detail page, not the form), `VenuesPage`/`VenueFormPage` |

**Dev proxy vs. prod:** `vite.config.ts` proxies `/api/*` to `http://localhost:8080` in dev so no CORS configuration is needed locally. In production, set `VITE_API_BASE_URL` to the deployed backend origin (`src/api/client.ts` prepends it to `/api/v1`); if unset, requests go to the frontend's own origin, which only works behind a reverse proxy that forwards `/api` to the backend.

**Date handling:** `EventFormPage` reads/writes `<input type="datetime-local">`, which produces `yyyy-MM-ddTHH:mm` (no seconds). `EventRequest.eventDate` is built by appending `:00` before sending, matching `LocalDateTime` parsing on the backend (see `EventController`'s `@DateTimeFormat(iso = DATE_TIME)` for the equivalent list-filter parsing).

## Architecture

### Request flow

**Three transports, one service layer:** REST (`/api/v1/**`), the Thymeleaf web UI (`/ui/**`), and
this app's own gRPC server (port 9091 by default, separate from the HTTP port 8080) all call the
same `EventService`/`TicketService`/`VenueService`/`PerformerService` beans — no business logic is
duplicated across transports. The gRPC server has its own auth layer (`JwtServerInterceptor`,
validating the same custom JWT outside the servlet filter chain below) with its own `ROLE_ADMIN`
gate, mirroring the REST API's rules — see [gRPC API server](#grpc-api-server). (Separately, this
application is also a gRPC *client* to an external payment service — see
[Payment service integration (gRPC client)](#payment-service-integration-grpc-client) — unrelated,
opposite direction.) The diagram below covers the HTTP-based transports (REST and the web UI); gRPC
requests never pass through this filter chain at all.

```
HTTP Request
  → RateLimitFilter (highest precedence — runs before every filter chain below)
  │    Redis sliding-window check keyed by client IP; 429 short-circuits here,
  │    never reaching authentication/session/CSRF — see "Rate limiting" below
  │
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
| POST | `/api/v1/auth/register` | public | returns custom JWT |
| POST | `/api/v1/auth/login` | public | returns custom JWT |
| GET | `/api/v1/events` | public | optional `?venueId=` or `?start=&end=` (ISO datetime) |
| GET | `/api/v1/events/{id}` | public | cached |
| POST | `/api/v1/events` | ADMIN | 400 if `ticketsTotal` exceeds venue capacity; sets `status=AVAILABLE`; synchronously creates one AVAILABLE ticket per unit of `ticketsTotal` (Postgres only, not Cassandra); Cassandra dual-write of the event itself |
| PUT | `/api/v1/events/{id}` | ADMIN | `ticketsTotal` in the body is ignored — fixed at creation, immutable; Cassandra dual-write |
| DELETE | `/api/v1/events/{id}` | ADMIN | sets `status=DELETING`; archives the event's **SOLD** tickets to Cassandra (AVAILABLE/RESERVED/PAYING tickets never had a completed sale and are skipped by the archive), then deletes **all** of the event's tickets from Postgres regardless of status (every ticket must be gone before the event row can be removed, FK constraint); also deletes the event's `TicketOperation` rows from Postgres only (their Cassandra copies are left in place); deletes the event from Postgres only — its last-synced Cassandra copy is left in place |
| GET | `/api/v1/events/{id}/tickets/available` | public | paginated (`?page=&size=`, 0-based, size 1–100, default 0/20); tickets with status `AVAILABLE`, ordered by ticket id |
| GET | `/api/v1/events/{id}/tickets/available/count` | public | live count of tickets with status `AVAILABLE`, computed by querying Postgres directly (`TicketRepository.countByEventIdAndStatus`) — not a stored counter |
| GET | `/api/v1/tickets/{id}` | public | cached; tickets have no external create/delete operation — see `POST /api/v1/events` and `DELETE /api/v1/events/{id}` above |
| GET | `/api/v1/tickets/me` | authenticated | paginated (`?page=&size=`, 0-based, size 1–100, default 0/20); every ticket owned by the caller, across all events/statuses, ordered by ticket id; the caller's own JWT `user_id` is the only one that can ever be queried — no way to look up another user's tickets |
| POST | `/api/v1/tickets/{id}/reserve` | authenticated | requires current status AVAILABLE; stamps `user_id` from the caller's JWT; moves to RESERVED |
| POST | `/api/v1/tickets/{id}/release` | authenticated | requires current status RESERVED and the caller's JWT `user_id` to match the ticket's; moves back to AVAILABLE and clears `user_id` |
| POST | `/api/v1/tickets/{id}/purchase` | authenticated | body: `{ paymentToken, paymentRequestId? }`; requires current status RESERVED (409 if already PAYING — a concurrent attempt in flight) and the caller's JWT `user_id` to match the ticket's; charges the ticket via an external payment service over gRPC (see [Payment service integration (gRPC client)](#payment-service-integration-grpc-client)) — approved moves to SOLD and stamps `datePurchased`, declined moves back to AVAILABLE, and a failed payment-service call (502) leaves the ticket PAYING for later reconciliation |
| POST | `/api/v1/tickets/{id}/cancel` | authenticated | requires current status SOLD and the caller's JWT `user_id` to match the ticket's; moves back to AVAILABLE, clears `user_id`, and records a CANCEL `TicketOperation` (see **Data model relationships**) |
| GET | `/api/v1/venues` | public | optional `?city=` |
| GET | `/api/v1/venues/{id}` | public | cached |
| POST | `/api/v1/venues` | ADMIN | |
| PUT | `/api/v1/venues/{id}` | ADMIN | |
| DELETE | `/api/v1/venues/{id}` | ADMIN | |
| GET | `/api/v1/performers` | public | optional `?name=` or `?genre=` |
| GET | `/api/v1/performers/{id}` | public | cached |
| POST | `/api/v1/performers` | ADMIN | Cassandra dual-write |
| PUT | `/api/v1/performers/{id}` | ADMIN | Cassandra dual-write |
| POST | `/api/v1/performers/{id}/videos` | ADMIN | add video URL; deduplicates shared URLs; Cassandra dual-write; Kafka event |
| DELETE | `/api/v1/performers/{id}/videos` | ADMIN | remove video URL from performer; Cassandra dual-write; Kafka event |
| DELETE | `/api/v1/performers/{id}` | ADMIN | Cassandra dual-write |

### OpenAPI schema

`springdoc-openapi-starter-webmvc-ui:2.5.0` is on the classpath. It scans all `@RestController` classes at startup and auto-generates an OpenAPI 3.0 schema. No code generation step is needed — the schema is produced at runtime from the live application.

**Endpoints (no auth required):**

| URL | Format | Notes |
|---|---|---|
| `http://localhost:8080/v3/api-docs` | JSON | Full OpenAPI 3.0 schema |
| `http://localhost:8080/v3/api-docs.yaml` | YAML | Same schema in YAML format |
| `http://localhost:8080/swagger-ui.html` | HTML | Interactive Swagger UI; supports "Try it out" |

Both `/v3/api-docs/**` and `/swagger-ui/**` are explicitly permitted in `SecurityConfig` so they are accessible without a token.

**What the schema captures automatically** (from Spring MVC metadata):
- All routes, HTTP methods, path parameters, and query parameters
- Request body shapes (from `@RequestBody` DTOs)
- Response body shapes (from declared return types)
- Bean validation constraints (`@NotBlank`, `@Min`, `@DecimalMax`, etc.) translated to JSON Schema keywords

**What the manual annotations add** (beyond what springdoc can infer):
- `@Tag` on each controller — groups endpoints into named sections in Swagger UI (Authentication, Events, Venues, Performers)
- `@Operation(summary, description)` on each method — human-readable summary line and longer description including auth requirements and business rules (e.g. ticket count limits, deduplication behaviour for video URLs)
- `@SecurityRequirement(name = "bearerAuth")` on write/admin endpoints — renders the lock icon in Swagger UI and wires to the `bearerAuth` scheme
- `@ApiResponse` per status code — documents 400/401/403/404 error cases and what triggers them (e.g. "Would exceed venue capacity" for 400 on release tickets)
- `@Parameter(description)` on path variables and query params — clarifies filter semantics (e.g. that `?name=` is a case-insensitive substring match while `?genre=` is an exact match)
- `@Schema(description, example)` on every DTO field — populates the "Example Value" panel and "Try it out" form with realistic data

**Security scheme:** `OpenApiConfig` registers a single `bearerAuth` HTTP Bearer JWT scheme. Clicking the **Authorize** button in Swagger UI and pasting a JWT from `POST /api/v1/auth/login` adds `Authorization: Bearer <token>` to all subsequent "Try it out" requests automatically.

**Downloading the schema** for use with code generators or API clients:
```bash
# JSON
curl http://localhost:8080/v3/api-docs -o openapi.json

# YAML
curl http://localhost:8080/v3/api-docs.yaml -o openapi.yaml
```

### Connecting to PostgreSQL

**Via Docker (always works, no local psql required):**

```bash
docker exec -it eventmanager-postgres psql -U postgres -d eventdb
```

**From the host with a local psql install:**

```bash
psql -h localhost -p 5432 -U postgres -d eventdb
```

If this connects to a local PostgreSQL instead of the Docker container (error: `role "postgres" does not exist`), another process is already bound to port 5432. Check with `lsof -i :5432`. Stop the local instance first:

```bash
brew services stop postgresql@16   # adjust version as needed
```

**Useful psql commands once connected:**

```sql
\dt                          -- list tables
\d users                     -- describe the users table
SELECT * FROM users;
SELECT * FROM events;
SELECT * FROM venues;
SELECT * FROM performers;
\q                           -- quit
```

### Development test users

The `POST /api/v1/auth/register` endpoint always creates users as `ROLE_USER`. There is no API path to create an admin — the role must be updated directly in the database after registration.

**Create both users via the register endpoint:**

```bash
# Regular user
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"user1","email":"user1@example.com","password":"password123"}'

# Admin (registers as ROLE_USER; promoted in the next step)
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","email":"admin@example.com","password":"password123"}'
```

**Promote the admin user:**

```bash
docker exec -it eventmanager-postgres psql -U postgres -d eventdb
```

```sql
UPDATE users SET role = 'ROLE_ADMIN' WHERE username = 'admin';

-- verify
SELECT id, username, email, role, created_at FROM users;
```

Passwords are BCrypt-encoded by the app. Inserting rows directly via SQL would require pre-computing a BCrypt hash; using the register endpoint and then flipping the role column is the correct approach.

**Login and use the token:**

```bash
# Login and capture token
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"password123"}' | jq -r '.token')

# Use token in subsequent requests
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/events
```

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
- Public (no token): `GET /api/v1/events/**`, `GET /api/v1/venues/**`, `GET /api/v1/performers/**`, `GET /api/v1/tickets/**` (except `/me`, see below), `POST /api/v1/auth/**`, `/actuator/health`, `/actuator/prometheus`
- Authenticated (`ROLE_USER` or `ROLE_ADMIN`): `GET /api/v1/tickets/me`, `POST /api/v1/tickets/{id}/reserve`, `POST /api/v1/tickets/{id}/release`, `POST /api/v1/tickets/{id}/purchase`, `POST /api/v1/tickets/{id}/cancel` (any authenticated user — not ADMIN-only; see **Tickets** ticket ownership below)
- Admin only (`ROLE_ADMIN`): `POST/PUT/DELETE /api/v1/events/**` (create, update, delete), `POST/PUT/DELETE /api/v1/venues/**`, `POST/PUT/DELETE /api/v1/performers/**` (includes video sub-routes), `/api/v1/admin/**`

Tickets have no external create or delete route: they're created synchronously as a side effect of `POST /api/v1/events` (one AVAILABLE ticket per unit of `ticketsTotal`, which `createEvent` validates is `<= venue.capacity` before saving) and removed as a side effect of `DELETE /api/v1/events/{id}` (backed up to Cassandra, then deleted). There is also no generic "set status" route: `TicketController` exposes `GET /api/v1/tickets/{id}` (public), `GET /api/v1/tickets/me` (authenticated), and one dedicated endpoint per valid transition — `POST /{id}/reserve`, `POST /{id}/release`, `POST /{id}/purchase`, `POST /{id}/cancel` — each any authenticated user, with ownership rules enforced in `TicketService` rather than by role — see the `Ticket.userId` note under **Data model relationships**. This was changed from ADMIN-only once ticket reservation needed to be a self-service action any user can perform on their own behalf — there's no separate ADMIN override; every authenticated user hits the same rules. There is no event-level reserve/release operation — `ticketsTotal` is a fixed capacity, not a live counter, and per-ticket status is the only way to reserve/release/purchase/cancel; the closest read is `EventService.getNumAvailableTickets`/`GET /api/v1/events/{id}/tickets/available/count`, a public, unauthenticated query.

The filter chain's own rule for `/api/v1/events/**` only requires *authentication* (`.anyRequest().authenticated()`), but every write under that prefix (`POST`/`PUT`/`DELETE`) is further narrowed to ADMIN by `@PreAuthorize` — there's no longer an authenticated-but-not-admin action under `/api/v1/events/**` now that event-level ticket reserve/release is gone. The role split is enforced by `@PreAuthorize` on each controller method (`hasRole('ADMIN')` on `EventController.createEvent`/`updateEvent`/`deleteEvent`; `isAuthenticated()` on `TicketController.getMyTickets`/`reserveTicket`/`releaseTicket`/`purchaseTicket`/`cancelTicket`). Fine-grained rules use `@PreAuthorize` on controller methods; the filter chain rules are the outer gate. `GET /api/v1/tickets/**` is a special case worth calling out: the filter-chain rule `.requestMatchers(HttpMethod.GET, "/api/v1/tickets/**").permitAll()` exists for `getTicketById`, which really is public — but Spring evaluates `authorizeHttpRequests` matchers in declared order and takes the first match, so a *more specific* `.requestMatchers(HttpMethod.GET, "/api/v1/tickets/me").authenticated()` is declared immediately before it. Without that, an anonymous request to `/me` would fall through the broad `permitAll` at the filter layer and only get rejected by `@PreAuthorize("isAuthenticated()")` at the method-security layer — which throws a plain `AccessDeniedException` (403), not the `AuthenticationException` that triggers `BearerTokenAuthenticationEntryPoint` (401), breaking the 401-vs-403 convention described below for this one path.

Unauthenticated requests to protected endpoints return **401** — `oauth2ResourceServer` installs a `BearerTokenAuthenticationEntryPoint`. Authenticated-but-insufficient-role requests return **403** (from `GlobalExceptionHandler.handleAccessDeniedException`).

### Rate limiting
`RateLimitFilter` (`com.eventmanager.ratelimit`) enforces a global, Redis-backed rolling-window rate limit
across the whole app — both `/api/v1/**` and `/ui/**` — keyed by client IP (`X-Forwarded-For` if present,
falling back to the raw socket address), regardless of authentication state. It is registered by
`RateLimitConfig` as a plain `FilterRegistrationBean` (not a `@Component` on the filter class itself, which
would cause Spring Boot to auto-register it a second time) with `Ordered.HIGHEST_PRECEDENCE`, so it runs before
every Spring Security filter chain — a rejected request never reaches authentication, session, or CSRF handling.
`/actuator/**` is exempted unconditionally so Prometheus scraping and the Kubernetes liveness/readiness probes
are never throttled.

**Algorithm — sliding window, not a fixed bucket:** `RateLimiter` records each accepted request as a member of a
Redis sorted set (ZSET) scored by its timestamp in epoch millis, under key `rate-limit:ip:<address>`. A single
Lua script (`EVAL`, so the prune → count → conditionally-add sequence is atomic and race-free across concurrent
requests on the same key) removes members older than the current window before counting what's left:

```lua
redis.call('ZREMRANGEBYSCORE', key, '-inf', now - window)
local count = redis.call('ZCARD', key)
if count < limit then
    redis.call('ZADD', key, now, member)
    redis.call('PEXPIRE', key, window)
    return 1
end
redis.call('PEXPIRE', key, window)
return 0
```

This is a true rolling window: a fixed-window counter (`INCR` + `EXPIRE` on a clock-aligned bucket) would let a
client burst up to 2x the limit by timing requests around the reset boundary, which is exactly the burst
behavior a rate limiter is meant to prevent. The key's TTL is (re)set to the window length on every call — "TTL
per time window" — so a key that goes idle for a full window expires and cleans itself up with no separate
eviction job.

**Configuration** (`RateLimitProperties`, bound from `rate-limit.*`): `enabled` (`RATE_LIMIT_ENABLED`, default
`true`), `window-duration` (`RATE_LIMIT_WINDOW_DURATION`, a Spring `Duration` in simple format like `1s`/`500ms`,
default `1s`), `max-requests` (`RATE_LIMIT_MAX_REQUESTS`, default `1000`) — i.e. 1000 requests per rolling
1-second window out of the box, generous enough to absorb legitimate bursts while still bounding runaway/abusive
traffic. Disabled entirely in the `test` profile (`application-test.yml`) since no Redis broker runs there and
`@AutoConfigureMockMvc` would otherwise route every `MockMvc` request through this filter.

**Failure mode:** `RateLimiter.isAllowed` catches any exception from the Redis call and fails open (returns
`true`), logging a `WARN`. A rate limiter's own datastore being unreachable should never itself take the whole
API down — that would turn a transient Redis blip into a hard outage for every client, which is a worse outcome
than temporarily not rate-limiting at all.

**Response on rejection:** `429 Too Many Requests` with a `Retry-After` header (the window length, in seconds)
and a JSON body matching `GlobalExceptionHandler.ErrorResponse`'s shape (`{"status":429,"message":"..."}`,
constructed by hand here since this filter runs ahead of Spring MVC/`GlobalExceptionHandler` entirely).

### Thymeleaf web UI

`spring-boot-starter-thymeleaf` and `thymeleaf-extras-springsecurity6` are on the classpath. All UI pages are served under `/ui/**` by a dedicated set of `@Controller` classes in `com.eventmanager.web`. The REST API under `/api/v1/**` is entirely unchanged.

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
| `WebTicketController` | `/ui/tickets` | `GET /me` (paginated "My Tickets" list) plus `POST /{id}/reserve`, `/release`, `/purchase`, `/cancel` — no admin gate, same ownership rules as the REST API |

All web controllers delegate directly to the existing services (`EventService`, `VenueService`, `PerformerService`, `TicketService`) — no duplicate business logic. Flash attributes (`RedirectAttributes.addFlashAttribute`) carry success (`successMessage`) and, for `WebTicketController` only, error (`errorMessage`) messages across the POST-redirect-GET cycle — `errorMessage` is a pattern introduced specifically for ticket actions, since `IllegalArgumentException`/`AccessDeniedException` from `TicketService` (stale status, wrong owner) are real, expected outcomes a user needs to see, not exceptional failures. `WebTicketController` catches both directly and flashes the message rather than letting them propagate to `GlobalExceptionHandler`, which (being a `@RestControllerAdvice` with no package/type scoping) applies to every controller app-wide including these `@Controller` classes, and would otherwise hand the browser a raw JSON body instead of a redirect back to an HTML page.

**`datetime-local` binding** — HTML `<input type="datetime-local">` produces values in the format `yyyy-MM-dd'T'HH:mm` (no seconds). Spring MVC's default `LocalDateTime` converter expects full ISO-8601. `WebEventController` registers a `PropertyEditorSupport` via `@InitBinder` that parses and formats `LocalDateTime` using `DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")`, avoiding any change to `EventRequest` or application.yml.

**Delete pattern** — HTML forms only support GET and POST. Delete actions use `POST /ui/{entity}/{id}/delete` with a JavaScript `confirm()` dialog on the submit button. No `HiddenHttpMethodFilter` is needed.

**Thymeleaf templates** (`src/main/resources/templates/`):

| File | Purpose |
|---|---|
| `login.html` | Standalone login card; no nav |
| `fragments/nav.html` | `th:fragment="nav"` — Bootstrap 5 navbar included in every page via `th:replace`; uses `sec:authorize` to show/hide Login vs. username + Logout, and to show a "My Tickets" link only when authenticated |
| `events/list.html` | Card grid of all events; Create button visible to authenticated users |
| `events/view.html` | Event detail: venue link, performer badges, ticket counts (total + live available count), and a paginated "Available Tickets" list with a per-ticket Reserve form (any authenticated user; anonymous visitors see a "Log in to reserve" link instead) |
| `events/form.html` | Create/edit form; venue select dropdown, performer checkboxes; reused for both create (`POST /ui/events`) and edit (`POST /ui/events/{id}/edit`) via conditional `th:action` |
| `venues/list.html` | Table with city filter form |
| `venues/view.html` | Venue detail |
| `venues/form.html` | Create/edit form |
| `performers/list.html` | Card grid with name/genre search |
| `performers/view.html` | Performer detail; admin panel for adding/removing video URLs inline |
| `performers/form.html` | Create/edit form (name, genre, bio only — videos managed on the view page) |
| `tickets/my-tickets.html` | Paginated list of the current user's own tickets across all events; Purchase (inline `paymentToken` text input — no `paymentRequestId` field, `TicketService` generates one) + Release forms for `RESERVED` tickets, Cancel for `SOLD` ones; empty state and `successMessage`/`errorMessage` banners |

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
| GET | `/ui/events/new` | ADMIN | New event form |
| POST | `/ui/events` | ADMIN | Create event |
| GET | `/ui/events/{id}/edit` | ADMIN | Edit form pre-filled from existing event |
| POST | `/ui/events/{id}/edit` | ADMIN | Update event |
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
| GET | `/ui/tickets/me` | authenticated | Paginated list of the caller's own tickets (`?page=&size=`) |
| POST | `/ui/tickets/{id}/reserve` | authenticated | Body: `eventId` (hidden field, so both success and failure redirect back to the event page); redirects to `/ui/events/{eventId}` |
| POST | `/ui/tickets/{id}/release` | authenticated | Redirects to `/ui/tickets/me` |
| POST | `/ui/tickets/{id}/purchase` | authenticated | Body: `paymentToken` (no `paymentRequestId` field — `TicketService` generates one); redirects to `/ui/tickets/me`; a `TicketPaymentInProgressException`/`PaymentServiceUnavailableException` is caught and surfaced as a flash `errorMessage` rather than propagating |
| POST | `/ui/tickets/{id}/cancel` | authenticated | Redirects to `/ui/tickets/me` |

### gRPC API server

A third transport alongside the REST API and the Thymeleaf web UI, all three calling the same
`EventService`/`TicketService`/`VenueService`/`PerformerService` beans — no business logic is
duplicated. Not to be confused with the payment-service gRPC *client* (see
[Payment service integration (gRPC client)](#payment-service-integration-grpc-client)) — unrelated
feature, opposite direction.

**Proto and codegen:** three files, one proto package (`eventmanager.api.v1`) / Java package
(`com.eventmanager.grpc.api`) — `event_manager_api.proto` (`EventGrpcService`, `TicketGrpcService`,
plus the shared `VenueMessage`/`PerformerMessage`), `venue_api.proto` (`VenueGrpcService`), and
`performer_api.proto` (`PerformerGrpcService`), both importing `event_manager_api.proto` for the
shared messages. Dates/decimals travel as plain strings (not `google.protobuf.Timestamp`/typed
decimal), nullable fields use the `optional` keyword, and statuses travel as plain strings (not
proto enums, since `EventStatus`/`TicketStatus` both declare `AVAILABLE` and proto enums are flat
per-file). Same `protobuf-maven-plugin` codegen as the payment-service client — runs on every `mvn
compile`, no separate step.

**Classes** (`com.eventmanager.grpcserver`):
- `GrpcServerProperties` — `grpc-api.enabled` (default `true`, `false` in application-test.yml so
  `mvn test` never binds a real socket), `grpc-api.port` (default `9091`), and a nested
  `grpc-api.tls.*` block (`enabled`, `cert-chain-file`, `private-key-file`; off by default).
- `GrpcServerLifecycle` — a `SmartLifecycle` bean; builds a `NettyServerBuilder` registering all
  four `*GrpcServiceImpl` beans, each wrapped with `JwtServerInterceptor`. Hand-built from plain
  grpc-java, the same approach `PaymentGrpcConfig` uses on the client side — no `net.devh` starter.
  When `grpc-api.tls.enabled`, attaches an `SslContext` built via `GrpcSslContexts.forServer(certChainFile,
  privateKeyFile)` before starting (grpc-java wants a PEM cert chain + PEM private key, a different
  shape from Tomcat's PKCS12/JKS keystore — see `certs/generate-dev-certs.sh`, which generates a
  matching self-signed dev cert in both formats from one key pair).
- `JwtServerInterceptor` — the entire auth layer for gRPC traffic (none of the servlet filter chain
  below sees it). Validates `authorization: Bearer <jwt>` gRPC metadata using the same
  `JwtTokenProvider`/`UserDetailsServiceImpl` the REST `JwtAuthenticationFilter` uses; no token →
  proceeds anonymously (public RPCs still work); invalid/expired token → immediate `UNAUTHENTICATED`.
  Attaches the resolved `SecurityContext` via gRPC's own `Context`, not `SecurityContextHolder`
  directly.
- `GrpcCallSupport` — every RPC runs through `run` (public), `runAuthenticated` (authenticated-only,
  throws `UNAUTHENTICATED` if unresolved), or `runAdmin` (`ROLE_ADMIN`-only, throws
  `PERMISSION_DENIED`) — copying the gRPC `Context`'s `SecurityContext` into
  `SecurityContextHolder` only for the duration of the call, which is what lets
  `TicketService.currentUserId()` resolve the right caller with zero changes to `TicketService`
  itself.
- `GrpcValidationSupport` — runs a mapped REST DTO through the real `jakarta.validation.Validator`
  bean before every Create/Update RPC delegates to the business service, since proto3 has no
  `@Valid` equivalent of its own.
- `GrpcExceptionMapper` — the gRPC-transport equivalent of `GlobalExceptionHandler`:
  `ResourceNotFoundException`→`NOT_FOUND`, `AccessDeniedException`→`PERMISSION_DENIED`,
  `ConstraintViolationException`→`INVALID_ARGUMENT`, every ticket-status-machine violation
  (`IllegalArgumentException`)→`FAILED_PRECONDITION`,
  `TicketPaymentInProgressException`→`FAILED_PRECONDITION` (409-equivalent),
  `PaymentServiceUnavailableException`→`UNAVAILABLE` (502-equivalent), anything else→`INTERNAL`.
- `EventGrpcServiceImpl`/`TicketGrpcServiceImpl`/`VenueGrpcServiceImpl`/`PerformerGrpcServiceImpl` —
  cover the full CRUD surface for events/venues/performers plus every ticket transition (including
  the full saga-style `purchaseTicket`, blocking the handling thread for up to the payment
  service's configured deadline), delegating straight to the same services the REST API uses.

**Config:** `grpc-api.*` in `application.yml` (`GRPC_API_ENABLED`/`GRPC_API_PORT` env vars, plus
`GRPC_API_TLS_*` for TLS); `grpc-api.enabled: false` in `application-test.yml`. Port `9091` exposed
in `docker-compose.yml`'s `app` service and in the Dockerfile (`EXPOSE 8080 9091`), alongside this
app's own HTTP port `8080` — distinct from the payment-service client's own target port `9095`.

**Testing:** `EventManagerApiGrpcTest` (`@SpringBootTest`, `grpc-api.enabled=true`,
`grpc-api.port=0` for an OS-assigned port) talks to the real server over a real socket via
generated `BlockingStub`s, with `EventService`/`TicketService`/`VenueService`/`PerformerService`
all mocked — see [Test classes](#test-classes).

### Exception handling
`GlobalExceptionHandler` (`@RestControllerAdvice`) maps exceptions to HTTP responses:

| Exception | Status | Notes |
|---|---|---|
| `ResourceNotFoundException` | 404 | Message from exception |
| `AccessDeniedException` | 403 | Fixed message "Access denied"; must be declared before the `Exception` catch-all or `@PreAuthorize` rejections return 500 |
| `TicketPaymentInProgressException` | 409 | A purchase attempt is already in flight for this ticket (status is already `PAYING`) — see [Payment service integration (gRPC client)](#payment-service-integration-grpc-client) |
| `PaymentServiceUnavailableException` | 502 | The payment-service gRPC call itself failed (timeout/connection error); the ticket is left `PAYING` for later reconciliation |
| `BadCredentialsException` | 401 | Fixed message "Invalid username or password" |
| `IllegalArgumentException` | 400 | Message from exception; used by `AuthService` for duplicate username/email and by `EventService` for invalid ticket counts |
| `MethodArgumentNotValidException` | 400 | Returns `{ status, errors: { field: message }, timestamp }` — different shape from `ErrorResponse` |
| `Exception` (catch-all) | 500 | Generic message; logged server-side at `ERROR` (`log.error("Unhandled exception", ex)`) since this is the only place an unexpected failure is ever surfaced — the client only ever sees the generic message, never a stack trace |

`ErrorResponse` is a record `(int status, String message)` with a computed `timestamp()` method.

### Redis caching
All four services cache individual records by ID with a 1-hour TTL:

| Cache name | Service | Cacheable | CachePut | CacheEvict |
|---|---|---|---|---|
| `"events"` | `EventService` | `getEventById` | `createEvent`, `updateEvent` | `deleteEvent` |
| `"venues"` | `VenueService` | `getVenueById` | `createVenue`, `updateVenue` | `deleteVenue` |
| `"performers"` | `PerformerService` | `getPerformerById` | `createPerformer`, `updatePerformer`, `addVideo`, `deleteVideo` | `deletePerformer` |
| `"tickets"` | `TicketService` | `getTicketById` | `reserveTicket`, `releaseTicket`, `purchaseTicket`, `cancelTicket` | — (see note) |

`TicketService.getNumAvailableTickets(eventId)` is cached separately in a `"availableTicketCounts"` cache with its own short **5-second TTL** (configured as a per-cache override in `RedisConfig`, distinct from the 1-hour default above), keyed by `#eventId`. Unlike the four caches above, nothing `@CacheEvict`s or `@CachePut`s it on `reserveTicket`/`purchaseTicket`/`releaseTicket`/`cancelTicket` — the short TTL is the only staleness control, a deliberate trade-off: it absorbs read bursts against a value that changes on nearly every ticket write, at the cost of the count being up to 5 seconds stale.

Five list/search endpoints are also cached, `@Cacheable` only (no `@CachePut`/`@CacheEvict` — list invalidation on write isn't implemented for these either, so each relies entirely on its TTL to bound staleness):

| Cache name | Service method | Key | TTL |
|---|---|---|---|
| `"allEvents"` | `EventService.getAllEvents()` | none (no-arg method — one entry for the whole list) | 30 seconds |
| `"eventsByVenue"` | `EventService.getEventsByVenue(venueId)` | `#venueId` | 30 minutes |
| `"venuesByCity"` | `VenueService.getVenuesByCity(city)` | `#city` | 30 minutes |
| `"performersByName"` | `PerformerService.searchPerformers(name)` | `#name` | 30 minutes |
| `"performersByGenre"` | `PerformerService.getPerformersByGenre(genre)` | `#genre` | 30 minutes |

`getAllEvents` gets a much shorter TTL than the other four because it has no filter — any event create/update/delete invalidates it, so a long TTL would mean stale results after almost any write; the filtered `venuesByCity`/`performersByName`/`performersByGenre`/`eventsByVenue` queries are hit less often and change less often relative to reads, so a 30-minute TTL was judged an acceptable staleness/hit-rate trade-off. `getEventsBetween` and `getAvailableTickets` remain uncached — an unbounded range of possible `start`/`end` or `page`/`size` argument combinations would fragment the cache into many near-unique, rarely-reused entries. `createAvailableTickets` (bulk-creates a whole event's worth of tickets) has no per-id cache entries to populate, so it isn't annotated. `backupAndDeleteAllForEvent` uses `@CacheEvict(value = "tickets", allEntries = true)` instead of the usual `key = "#id"` pattern, since it removes an unbounded, dynamically-sized set of ticket ids in one call — it does not evict `"availableTicketCounts"` either, again relying on the 5-second TTL. `TicketOperation` has no cache of its own — there's no `getById`-style read path for it, only writes (see **Cassandra dual-write ... and ticket backup**).

Cache is configured in `RedisConfig` with JSON serialization (`GenericJackson2JsonRedisSerializer`) and a 1-hour TTL by default. Every cache with a non-default TTL (`"availableTicketCounts"` at 5 seconds; `"allEvents"` at 30 seconds; `"eventsByVenue"`, `"venuesByCity"`, `"performersByName"`, `"performersByGenre"` at 30 minutes) is built from that same default config via `.entryTtl(Duration)` and registered individually with `.withCacheConfiguration(name, config)` on the `RedisCacheManager` builder — `cacheDefaults(...)` only applies to caches with no such override (`"events"`, `"venues"`, `"performers"`, `"tickets"`, at 1 hour). `EventResponse`, `VenueDto`, `PerformerDto`, and `TicketResponse` all implement `Serializable` for this reason (the cached `Long` from `getNumAvailableTickets` and the cached `List<...>` results from the list endpoints need no such treatment — `List` and the DTOs it holds are already covered).

**`GenericJackson2JsonRedisSerializer`'s own no-arg `ObjectMapper` cannot serialize `java.time.LocalDateTime`** — it has no `JavaTimeModule` registered, unlike the app-wide `ObjectMapper` Spring Boot auto-configures for the REST API (which gets JSR-310 support automatically via `spring-boot-starter-json`). Every DTO with a `LocalDateTime` field (`EventResponse.eventDate`/`createdAt`/`updatedAt`, `TicketResponse.eventDate`) would throw `SerializationException` on first cache write, surfacing as a 500 on `getEventById`/`getAllEvents`/etc. — and since caching is disabled in the test profile (`spring.cache.type: none`), this class of bug is invisible to `mvn test` and only reproduces against a real Redis. `RedisConfig.redisObjectMapper()` fixes this by building its own `ObjectMapper` that replicates what the no-arg serializer does internally (all-field visibility via `setVisibility(PropertyAccessor.ALL, ANY)`, plus `activateDefaultTyping(..., NON_FINAL, PROPERTY)` so cached JSON carries `@class` metadata and deserializes back to the right concrete DTO) and additionally registers `JavaTimeModule`, then passes that mapper to `new GenericJackson2JsonRedisSerializer(objectMapper)`.

### Data model relationships
- `Event` → `Venue`: `@ManyToOne` (an event has exactly one venue; venue is not owning side)
- `Event` ↔ `Performer`: `@ManyToMany` via join table `event_performers`; `Event` is the owning side
- `Performer` ↔ `Video`: `@ManyToMany` via join table `performer_videos` (`performer_id`, `video_id`); `Performer` is the owning side. `Video` rows are deduplicated by URL — `addVideo` reuses an existing `Video` if the URL already exists. Removing a video from a performer does not delete the `Video` row (other performers may reference it).
- `Ticket` → `Event`: `@ManyToOne` (a ticket belongs to exactly one event). A ticket has its own `id`, `description` (always `null` — nothing currently sets it; see below), `status` (`AVAILABLE`, `RESERVED`, `PAYING`, `SOLD` — `@Enumerated(EnumType.STRING)`; there is no `CANCELLED` status — a cancelled ticket returns to `AVAILABLE`, see below), `userId` (`Long`, column `user_id`, nullable — the id of the user who reserved/bought it), `paymentRequestId` (`String`, column `payment_request_id`, nullable — stamped when a purchase attempt commits RESERVED→PAYING and never cleared afterward), and `datePurchased` (`LocalDateTime`, column `date_purchased`, nullable — stamped only when a purchase is `APPROVED`, i.e. the moment the ticket moves to `SOLD`; like `paymentRequestId`, never cleared by a later `cancelTicket`, so it always reflects the most recent approved sale). Venue and performer details are **not** duplicated onto `Ticket`; `TicketResponse` derives `venueId`/`venueName` and a `Set<PerformerSummary>` (id + name) from `ticket.getEvent()` at read time, the same normalized approach `EventResponse` uses for `VenueDto`/`PerformerDto`. Tickets have no external create/delete API — `TicketService.createAvailableTickets(event, count)` is called synchronously from `EventService.createEvent`, once per unit of the event's own `ticketsTotal` (not venue capacity — `createEvent` rejects with `IllegalArgumentException` → 400 up front if `ticketsTotal > venue.capacity`, so a partial house never over-issues tickets), all with `status = AVAILABLE`, `description = null`, and `userId = null`; `TicketService.backupAndDeleteAllForEvent(eventId)` is called synchronously from `EventService.deleteEvent` to archive-then-remove them — only `SOLD` tickets are archived to Cassandra, but all of them are deleted from Postgres (see **Cassandra dual-write ... and ticket backup**). `Event.ticketsTotal` has no setter (`@Setter(AccessLevel.NONE)`) — it is fixed at creation and `EventService.updateEvent` never touches it, so the request body's value is silently ignored on update. There is no live "tickets remaining" counter on `Event`/`EventResponse`; the number of AVAILABLE tickets is always computed on demand from Postgres (see `TicketService.getNumAvailableTickets`/`EventService.getNumAvailableTickets` under **Ticket status state machine**). `Event` also carries a `status` (`EventStatus`: `AVAILABLE` or `DELETING`, `@Enumerated(EnumType.STRING)`) set to `AVAILABLE` on create and `DELETING` at the start of `deleteEvent` — see **Cassandra dual-write ... and ticket backup** below for the full delete sequence.
- `Ticket.userId` and JWT-driven ownership rules: there is no generic status-update operation — `TicketService` exposes one method per valid transition (`reserveTicket`, `releaseTicket`, `purchaseTicket`, `cancelTicket`), each a thin public wrapper around a private validation helper (`reserve`/`release`/`sell`/`cancel`) plus a shared `currentUserId()`. The `user_id` is never accepted from the client — `currentUserId()` reads the authenticated username off `SecurityContextHolder.getContext().getAuthentication().getName()` (populated by `JwtAuthenticationFilter` for custom JWTs, or by Spring's OAuth2 resource server support for RS256 tokens — both put the username in the `Authentication`'s name) and resolves it to a `User.id` via `UserRepository.findByUsername`, throwing `ResourceNotFoundException` (404) if the username has no matching row. `reserveTicket(id)` requires the ticket's current status to be `AVAILABLE` (else `IllegalArgumentException` → 400) and sets `userId` to the caller's id, moving it to `RESERVED`. `releaseTicket(id)` requires the ticket's current status to be `RESERVED` (else 400) *and* the caller's id to equal the ticket's existing `userId` (else `AccessDeniedException` → 403), moving it back to `AVAILABLE` and clearing `userId` back to `null` — the reverse of `reserveTicket`. `purchaseTicket(id, paymentToken, paymentRequestId)` resolves the caller's id and delegates to an internal overload; it is a **saga, not a single validate-and-save call** — see **Ticket status state machine** and **Payment service integration (gRPC client)** below. Its first transaction requires the ticket's current status to be `RESERVED` (else 400, or 409 if it's already `PAYING` — a concurrent attempt in flight) *and* the caller's id to equal the ticket's existing `userId` (else `AccessDeniedException` → 403 — reusing `GlobalExceptionHandler`'s existing mapping, so the response body is the same fixed `"Access denied"` message as a `@PreAuthorize` rejection), and commits RESERVED→PAYING. A gRPC call to the payment service happens next with no transaction open. A second transaction then commits PAYING→`SOLD` (recording a `PURCHASE` `TicketOperation`) or PAYING→`AVAILABLE` with `userId` cleared (recording a `PAYMENT_DECLINED` `TicketOperation`), depending on the outcome; if the gRPC call itself fails, the ticket is left `PAYING` with no operation recorded. `cancelTicket(id)` requires the ticket's current status to be `SOLD` (else 400) *and* the caller's id to equal the ticket's existing `userId` (else 403); unlike before, there is no `CANCELLED` status to move to — it instead moves the ticket back to `AVAILABLE` and clears `userId` (the same end state `releaseTicket` produces, just from `SOLD` instead of `RESERVED`), and records a `CANCEL` `TicketOperation` as the durable record that this ticket was purchased and then cancelled (since the ticket row itself no longer retains any trace of that history once its `userId` is cleared). `TicketController`'s `reserveTicket`/`releaseTicket`/`purchaseTicket`/`cancelTicket` are all `isAuthenticated()`, not `hasRole('ADMIN')`, precisely so any user can reserve/release/buy/cancel a ticket for themselves — see **Authorization model**.
- `TicketOperation` has no JPA association to `Ticket`/`Event`/`User` — `eventId`, `ticketId`, and `userId` are plain foreign-key columns on a flat audit-log row, since nothing reads an operation back through a relation. One is recorded by `TicketService.recordOperation` whenever a `purchaseTicket` call resolves (`PURCHASE` on approval, `PAYMENT_DECLINED` on decline — not written if the payment-service call itself fails) and on every `cancelTicket` (`CANCEL`) call — see **Cassandra dual-write (events, performers, and ticket operations) and ticket backup** for the full field list and dual-write/deletion behavior.
- All associations are `FETCH_TYPE.LAZY`; `EventRepository` uses JPQL `JOIN FETCH` queries (`findByIdWithDetails`, `findAllWithDetails`) and `PerformerRepository` uses `LEFT JOIN FETCH` queries (`findAllWithVideos`, `findByIdWithVideos`, etc.) to load associations in a single query and avoid N+1. `LEFT JOIN FETCH` is used for performers so that performers without any videos are still returned. `TicketRepository.findByEventIdAndStatus` fetch-joins `ticket.event` and `event.venue` (both `@ManyToOne`, safe to paginate); `event.performers` is a collection association, so pairing a `JOIN FETCH` on it with pagination would trigger Hibernate's in-memory pagination — it is left lazy and loads per distinct event, inside the same read-only transaction, while mapping to `TicketResponse`.

### Ticket status state machine

`TicketStatus` has four values (`AVAILABLE`, `RESERVED`, `PAYING`, `SOLD` — still no `CANCELLED`)
and five transitions. `reserve`/`release`/`sell`/`cancel` are the original four private validation
helpers; `purchaseTicket` additionally calls out to the payment service via gRPC between its two
transitions — see **Payment service integration (gRPC client)** below.

```
        reserveTicket(id)              purchaseTicket(id, token, reqId)   [payment service call]
   ┌─────────────────────►┌──────────►┌──────────────►┌─── APPROVED ────►┌──────
   │   sets userId=caller   RESERVED    userId must      PAYING             SOLD
   │                                    match ticket's        │
AVAILABLE◄─────────────────┘ releaseTicket(id)                │ DECLINED
   userId=null                clears userId                   ▼
        ▲                                                 AVAILABLE (userId cleared)
        └───────────────────────── cancelTicket(id) ──────────┘ (from SOLD)
                                    clears userId
```

| Transition | Method | Precondition (else 400, or 409 for PAYING) | Ownership check (else 403) | Result |
|---|---|---|---|---|
| → RESERVED | `reserveTicket(id)` | status is `AVAILABLE` | none — anyone can reserve an open ticket | `userId` = caller, status = `RESERVED` |
| RESERVED → AVAILABLE | `releaseTicket(id)` | status is `RESERVED` | caller's id equals ticket's `userId` | `userId` = `null`, status = `AVAILABLE` |
| RESERVED → PAYING | `purchaseTicket(id, paymentToken, paymentRequestId)` — Txn 1 (`beginPayment`) | status is `RESERVED`, **else 409 if already `PAYING`** (a concurrent attempt is in flight) | caller's id equals ticket's `userId` | status = `PAYING`; `paymentRequestId` stamped (caller-supplied or generated) |
| PAYING → SOLD / AVAILABLE | same call, Txn 2 (`completePayment`), after the gRPC call returns | n/a (status is already `PAYING` from Txn 1) | n/a | APPROVED → `SOLD`, `datePurchased` stamped to now, records `PURCHASE`; DECLINED → `AVAILABLE` + `userId=null` (`datePurchased` untouched), records `PAYMENT_DECLINED`; call failure (timeout/connection error) → **ticket stays `PAYING`**, no operation recorded, throws `PaymentServiceUnavailableException` (502) |
| SOLD → AVAILABLE | `cancelTicket(id)` | status is `SOLD` | caller's id equals ticket's `userId` | `userId` = `null`, status = `AVAILABLE`; records a `CANCEL` `TicketOperation` |

- **The PAYING→409 check is distinct from the generic "not RESERVED" 400**: `beginPayment` checks `status == PAYING` first and throws `TicketPaymentInProgressException` (409 — a conflict with an attempt already in flight) before falling through to `sell()`'s ordinary "not RESERVED" check (400 — a precondition failure), so a double-submitted purchase request never starts a second concurrent payment-service call for the same ticket.
- **`purchaseTicket` is a saga, not one transaction** — `beginPayment` (RESERVED→PAYING) and `completePayment` (PAYING→SOLD/AVAILABLE) are each their own short transaction via `TransactionTemplate`, with the (up to 120-second) gRPC call to the payment service running with no transaction open at all in between — see [I/O model](#io-model) and docs/payment-service-integration.md, "Why two transactions instead of one".
- **No `CANCELLED` status** — cancelling a `SOLD` ticket returns it all the way to `AVAILABLE` (the same end state `releaseTicket` produces), not a terminal dead state, so a cancelled ticket can be reserved and sold again.
- **`userId` is the ownership token**: it's set on `reserveTicket` and cleared on both paths back to `AVAILABLE` (`releaseTicket`, `cancelTicket`, and a `DECLINED` purchase outcome), so every `AVAILABLE` ticket has `userId = null` as an invariant. It is never accepted from the client — see **Data model relationships** for how `currentUserId()` resolves it from the JWT.
- **`paymentRequestId` is not an ownership token and is never cleared** — once a ticket has gone through `beginPayment` at least once, its most recent `paymentRequestId` stays on the row (visible in `TicketResponse`) regardless of how that attempt resolved, for support/reconciliation lookups. It has no bearing on which transitions are valid.
- **`datePurchased` is stamped only on an APPROVED outcome** (the RESERVED/PAYING→`SOLD` edge) and, like `paymentRequestId`, is never cleared afterward — including by `cancelTicket`, which returns the ticket to `AVAILABLE` but leaves `datePurchased` as a record of the most recent sale. A ticket that has never been sold has `datePurchased = null`.
- **`purchaseTicket`/`cancelTicket` write an audit row** (`TicketOperation` — `PURCHASE`, `PAYMENT_DECLINED`, or `CANCEL`) — the only durable record that a ticket was ever sold, declined, or cancelled, since the ticket row itself loses most of that history once `userId` is cleared. `reserveTicket`/`releaseTicket` don't log operations, and neither does a purchase attempt whose gRPC call itself fails (nothing was resolved to record).
- **Initial state**: tickets are created `AVAILABLE` with `userId = null` as a side effect of `POST /api/v1/events` (`TicketService.createAvailableTickets`, one per unit of `ticketsTotal`) — there is no direct "create ticket" entry point.
- All five transitions require authentication but not `ROLE_ADMIN` — ownership (`userId` match), not role, is what gates `release`/`purchase`/`cancel`; see **Authorization model**.
- **No live "available" counter is stored anywhere** — `Event.ticketsTotal` is a fixed capacity set at creation (immutable; no setter), not a count these transitions decrement/increment. The number of tickets currently `AVAILABLE` is computed on demand: `TicketService.getNumAvailableTickets(eventId)` runs `TicketRepository.countByEventIdAndStatus(eventId, AVAILABLE)` against Postgres directly, `EventService.getNumAvailableTickets(id)` delegates to it, and `GET /api/v1/events/{id}/tickets/available/count` (public) exposes it over the API. The result is cached in Redis for 5 seconds (see **Redis caching**) — short enough that it's still effectively live for this kind of read, but not evicted by any of these transitions, so a read can be up to 5 seconds stale. A ticket moving through `PAYING` is not `AVAILABLE`, so it's correctly excluded from this count for the whole duration of a purchase attempt, not just once it reaches `SOLD`. This replaced an earlier design where `POST /api/v1/events/{id}/tickets/{reserve,release}` mutated an aggregate `ticketsAvailable` counter on `Event` directly — that counter and both endpoints are gone.

### Payment service integration (gRPC client)

This application is a gRPC **client** to an external payment service (unrelated to, and the
opposite direction of, this app's own [gRPC API server](#grpc-api-server) above) — see
docs/payment-service-integration.md for the full design.

**Codegen:** `src/main/proto/payment_service.proto` defines `PaymentService` (`PurchaseTicket`,
`GetPaymentStatus`) in proto package `eventmanager.payment.v1`, generating into Java package
`com.eventmanager.grpc.payment`. The generated `com.eventmanager.grpc.payment.PurchaseTicketRequest`
is a different class from `com.eventmanager.dto.PurchaseTicketRequest` (the REST API's request
DTO) — nothing outside `com.eventmanager.payment` imports the generated classes directly;
`PaymentServiceClient` is the one seam that translates between them.

**Classes** (`com.eventmanager.payment`):
- `PaymentServiceProperties` — `payment-service.host`/`port`/`deadline` (120s default, the
  per-call gRPC deadline)/`connect-timeout` (5s default, used for the lighter-weight
  `GetPaymentStatus` call).
- `PaymentGrpcConfig` (`com.eventmanager.config`) — builds the `ManagedChannel` (plaintext, no TLS)
  and the `PaymentServiceGrpc.PaymentServiceBlockingStub` bean. Channel creation is lazy — it does
  not connect eagerly, so this config loads safely even with no payment service running (there
  isn't one in docker-compose today) as long as no purchase is actually attempted.
- `PaymentServiceClient` — wraps the blocking stub. `purchaseTicket(paymentRequestId, userId,
  paymentToken, ticketId, purchasePrice)` applies the configured `deadline`; both this and
  `getPaymentStatus` catch `StatusRuntimeException` and return a `PaymentOutcome` with
  `PaymentOutcomeStatus.ERROR` rather than letting the exception propagate — `TicketService` never
  needs a separate catch block for "business decline" vs. "the call itself failed."
- `PaymentOutcome`/`PaymentOutcomeStatus` — this application's own plain mirror of the proto
  `PaymentResponse`/`PaymentStatus`, plus `ERROR` (no proto equivalent).
- `PaymentCacheService` — see docs/payment-service-integration.md, "Redis caching", for the cache
  keys/TTLs involved in reconciling a stuck `PAYING` ticket.
- `TicketPaymentInProgressException` (409) / `PaymentServiceUnavailableException` (502) — see
  **Exception handling** above.

**Why two transactions instead of one:** see **Ticket status state machine** above and
[I/O model](#io-model) — the saga pattern exists specifically so the (up to 120-second) gRPC call
never holds a Postgres connection or row lock.

### Authentication flows

**Custom JWT** (`POST /api/v1/auth/login` → `Authorization: Bearer <token>`)
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
Controllers accept/return DTOs, never entities. `EventRequest` carries `venueId` and `Set<Long> performerIds` for write operations, plus `ticketsTotal` — read only at creation; `EventService.updateEvent` never applies it, since `Event.ticketsTotal` has no setter. `EventResponse` carries embedded `VenueDto` and `Set<PerformerDto>` for reads, plus `ticketsTotal` mirroring the entity's fixed capacity — it is not a live "available" count (see `GET /api/v1/events/{id}/tickets/available/count` for that). `VideoRequest` carries a single `url` field (`@NotBlank @URL`) used by the performer video endpoints. `PerformerDto` includes `Set<String> videoUrls` as output (populated from the join); video management is done through dedicated endpoints, not through the create/update payload. `PurchaseTicketRequest` carries `paymentToken` (`@NotBlank` — a tokenized payment-method reference, never raw card data) and an optional `paymentRequestId` (a client-supplied idempotency key for the payment-service call; `TicketService` generates a UUID if omitted), used by the purchase endpoint — the only ticket write request DTO, since `reserveTicket`/`releaseTicket`/`cancelTicket` take no body (the ticket id path variable and the caller's JWT are enough) and tickets have no external create/delete request bodies. See **Payment service integration (gRPC client)**. `TicketResponse` embeds a lightweight `PerformerSummary` (id + name) record rather than the full `PerformerDto`, since ticket listings don't need genre/bio/video data, and also exposes `userId`, `paymentRequestId`, and `datePurchased` (all output-only, like `EventResponse.status` — there's no request DTO field for `userId` since it's always derived from the JWT, never client-supplied; `paymentRequestId` is output-only on reads but is the one field settable via the *purchase* request, not echoed back as a generic writable property; `datePurchased` is `null` until a purchase is approved and is never settable by any request DTO at all). `EventResponse.status` (`EventStatus`) is output-only, like `createdAt`/`updatedAt` — there's no corresponding field on `EventRequest`. `PagedResponse<T>` is a generic wrapper (`content`, `page`, `size`, `totalElements`, `totalPages`, `last`) built from a Spring Data `Page<S>` via the static `PagedResponse.of(page, mapper)` helper — used instead of returning `Page<T>` directly from controllers, keeping the paginated JSON shape explicit and documented in the OpenAPI schema. Mapping is done in service `toResponse()` / `toDto()` methods, not via a separate mapper library.

### Dependency injection
Three DI forms are used, each for a different reason:

**Constructor injection via `@RequiredArgsConstructor` (Lombok) — primary pattern**
Every service, controller, and security class declares dependencies as `private final` fields. Lombok generates the constructor; Spring injects the beans at startup. Dependencies are immutable and the class is testable without a Spring context (just call `new` with mocks).

| Class | Injected dependencies |
|---|---|
| `AuthController` | `AuthService` |
| `EventController` | `EventService`, `TicketService` |
| `VenueController` | `VenueService` |
| `PerformerController` | `PerformerService` |
| `TicketController` | `TicketService` |
| `AuthService` | `AuthenticationManager`, `UserRepository`, `PasswordEncoder`, `JwtTokenProvider` |
| `EventService` | `EventRepository`, `VenueRepository`, `PerformerRepository`, `VenueService`, `PerformerService`, `CassandraAsyncWriter`, `TicketService` |
| `VenueService` | `VenueRepository` |
| `PerformerService` | `PerformerRepository`, `VideoRepository`, `CassandraAsyncWriter`, `PerformerVideoEventPublisher` |
| `TicketService` | `TicketRepository`, `EventRepository`, `UserRepository`, `TicketOperationRepository`, `CassandraAsyncWriter`, `PaymentServiceClient`, `PaymentCacheService`, `PlatformTransactionManager`, `CacheManager` |
| `UserDetailsServiceImpl` | `UserRepository` |
| `JwtAuthenticationFilter` | `JwtTokenProvider`, `UserDetailsServiceImpl` |
| `SecurityConfig` | `UserDetailsServiceImpl`, `JwtAuthenticationFilter` |

`TicketService`'s `PlatformTransactionManager` and `CacheManager` are framework-provided beans (Spring Boot auto-configures both — the former for JPA, the latter as the `RedisCacheManager` from `RedisConfig`), injected here rather than relied upon implicitly: `purchaseTicket` builds its own `TransactionTemplate` from the injected `PlatformTransactionManager` to run two short transactions with a slow gRPC call in between (see [I/O model](#io-model) and **Payment service integration (gRPC client)**), and calls `cacheManager.getCache("tickets").evict(id)` directly instead of relying on `@CachePut`, since the method that would need the annotation doesn't go through the Spring AOP proxy for its inner steps (self-invocation).

**Field injection via `@Autowired(required = false)` — optional infrastructure beans**
`CassandraAsyncWriter` holds three optional repositories (`PerformerCassandraRepository`, `EventCassandraRepository`, `TicketOperationCassandraRepository`), all field-injected with `required = false`. `PerformerVideoEventPublisher` holds an optional `KafkaTemplate<String, VideoEvent>`, also field-injected with `required = false`. `TicketService` holds an optional `TicketCassandraRepository` the same way — field-injected directly rather than routed through `CassandraAsyncWriter`, since the ticket backup write has to be synchronous (see **Cassandra dual-write ... and ticket backup**). Spring skips injection when the beans are absent (test profile excludes Cassandra and Kafka autoconfiguration). Each method short-circuits with a null check. Constructor injection cannot express optionality without an `Optional<>` wrapper.

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

**One deliberate exception to "every service method is `@Transactional`-annotated"**: `TicketService.purchaseTicket` is a saga spanning a slow external call (a gRPC call to the payment service, with a 120-second deadline) and is *not* `@Transactional` itself — wrapping the whole thing in one transaction would pin a HikariCP connection and a row lock on the ticket for the call's full duration. Instead it builds a `TransactionTemplate` from an injected `PlatformTransactionManager` and runs two short transactions programmatically (`beginPayment`/`completePayment`), with no transaction open at all while the gRPC call is in flight. Those two methods are plain, unannotated private methods invoked via self-invocation from inside the `TransactionTemplate` lambda — `@Transactional` would silently do nothing there anyway, since self-invocation bypasses the Spring AOP proxy the annotation relies on, the same reason `@Async` requires a different bean (see above). See **Payment service integration (gRPC client)** and docs/payment-service-integration.md, "Why two transactions instead of one".

### Logging
Every controller (`com.eventmanager.controller` and `com.eventmanager.web`) and every service uses `@Slf4j`
(Lombok) with structured parameterized log statements. The two layers log at different levels by design, so a
`DEBUG`-level log captures the full HTTP-to-business-logic trace of a request while an `INFO`-level production
log only shows meaningful business events, not one entry per controller method call:

| Layer | Level | When |
|---|---|---|
| Controllers (`*Controller`, `web.Web*Controller`) | `DEBUG` | Every request handler logs on entry with its path variables/query params/key body fields (e.g. `"Received request to get event id={}"`) — a thin HTTP-boundary trace, distinct from the service layer's business-event logging below. Never logs request bodies that carry credentials (`WebTicketController.purchase` logs the ticket id, never `paymentToken`; `AuthController`/`AuthService` never log passwords). |
| Services | `INFO` | Mutation entry and success (`createEvent`/`updateEvent`/`deleteEvent`, `createVenue`/`updateVenue`/`deleteVenue`, `createPerformer`/`updatePerformer`/`deletePerformer`, `reserveTicket`/`releaseTicket`/`purchaseTicket`/`cancelTicket`, `AuthService.register`/`login`) |
| Services | `WARN` | Not-found paths before throwing `ResourceNotFoundException`, and `EventService.createEvent`'s ticketsTotal-exceeds-capacity rejection before throwing `IllegalArgumentException`. Ticket transition validation failures (wrong status, wrong owner) inside `TicketService` are *not* logged at this level — they're expected, frequent, user-facing 400/403s, not exceptional conditions; `WebTicketController` logs a `WARN` when one of these is caught and turned into a flash `errorMessage`, since that's the point closest to the actual user-visible outcome. |
| Services | `DEBUG` | Read operations (query params, result counts) |

`CassandraAsyncWriter` also uses `@Slf4j`: `DEBUG` on successful async save/delete, `ERROR` on exception (with stack trace).

Root log level is `DEBUG` for `com.eventmanager` (set in `application.yml`), so both layers' logs are visible by
default in local/dev runs; a production deployment would typically raise the root level to `INFO` to keep only
the service layer's business events.

### Monitoring
`spring-boot-starter-actuator` + `micrometer-registry-prometheus` add observability with no instrumentation code:

| Endpoint | Auth | What it shows |
|---|---|---|
| `GET /actuator/health` | public | Composite status: Postgres, Redis, Cassandra, disk |
| `GET /actuator/prometheus` | public | All Micrometer metrics in Prometheus text format |
| `GET /actuator/metrics` | authenticated | Individual metric lookup |
| `GET /actuator/info` | authenticated | App name/version/description + build artifact/time |

`show-details: always` and `show-components: always` in `application.yml` expose per-component health (each datastore reported individually). All metrics carry an `application=event-manager` tag.

`/actuator/health` and `/actuator/prometheus` are explicitly permitted in `SecurityConfig` so Prometheus can scrape without a token. Other actuator endpoints require authentication.

**`/actuator/info` response** — two contributors are active:

- **Env contributor** (`management.info.env.enabled: true`): surfaces `info.*` properties from `application.yml` — `info.app.name`, `info.app.version`, `info.app.description`. The `@project.name@`/`@project.version@`/`@project.description@` placeholders are resolved by Maven resource filtering (from `spring-boot-starter-parent`) at compile time.
- **Build contributor** (`management.info.build.enabled: true`): reads `META-INF/build-info.properties` generated by the `build-info` goal on `spring-boot-maven-plugin`. Includes artifact, group, version, and build timestamp. Requires a `mvn package` or `mvn spring-boot:run` pass to generate the file — not present during IDE runs that skip Maven.

Spring Boot 2.6+ disabled both contributors by default; they are explicitly re-enabled in `application.yml`.

Scrape config is in `monitoring/prometheus.yml`. Grafana dashboard **4701** (JVM Micrometer) covers HTTP request rate/latency, JVM heap, GC, and Hikari pool usage.

`management.endpoint.health.probes.enabled: true` exposes `/actuator/health/liveness` and `/actuator/health/readiness` as dedicated Kubernetes probe endpoints. Liveness checks only that the JVM/application context is alive; readiness checks datastores. Using `/actuator/health` for liveness would restart pods whenever a database is unreachable, which is wrong.

**Edge metrics toggle (`edge-metrics.enabled`):** `EdgeMetricsConfig` (`com.eventmanager.metrics`) registers a
Micrometer `MeterFilter` — the same extension point Spring Boot's own `management.metrics.enable.*` property
uses internally — that, when `edge-metrics.enabled` (`EdgeMetricsProperties`, env `EDGE_METRICS_ENABLED`,
default `true`) is `false`, denies four meters outright so they're never registered at all and disappear
entirely from `/actuator/prometheus`:

| Meter | Covers |
|---|---|
| `http.server.requests` | request rate (its count), latency percentiles (its distribution — `management.metrics.distribution.percentiles-histogram`/`percentiles` in application.yml configure 0.5/0.95/0.99 for this meter unconditionally, since that config is a no-op once the meter itself is denied), and status-code breakdown per route (its `uri`/`status`/`method`/`outcome` tags, added automatically by Spring's `WebMvcTagsProvider`) |
| `http.server.requests.active` | in-flight request concurrency — a separate `LongTaskTimer`, distinct from the completed-request timer above, discovered during manual verification (denying just `http.server.requests` left this one still present in `/actuator/prometheus` output) |
| `process.uptime` / `process.start.time` | uptime |

This exists because these four are exactly the metrics an API gateway sitting in front of this app would
naturally also emit at the edge — edge traffic metrics can move to a gateway, JVM/process-internal metrics
can't. Turning this off once a gateway owns edge metrics avoids two sources of truth for the same numbers.
**Deliberately excluded** from the deny list: `jvm.*` (heap, GC, threads), `process.cpu.usage`,
`process.files.*`, and the `hikaricp.*` connection-pool metrics — those describe this process's own internal
resource usage, have no gateway equivalent, and must stay regardless of whether a gateway is also reporting
edge traffic. Verified live: with `EDGE_METRICS_ENABLED=false`, `http_server_requests_seconds*`,
`http_server_requests_active_seconds*`, `process_uptime_seconds`, and `process_start_time_seconds` are all
absent from `/actuator/prometheus` while `jvm_memory_used_bytes`, `process_cpu_usage`, and
`hikaricp_connections_*` remain; with it unset (default `true`), all four are present and the percentile
quantiles/route tags are populated as expected.

### Kubernetes
`k8s/deployment.yml` contains four resources applied with `kubectl apply -f k8s/deployment.yml`:

| Resource | Name | Notes |
|---|---|---|
| `Secret` | `event-manager-secrets` | `DB_PASSWORD`, `REDIS_PASSWORD`, `JWT_SECRET` — replace before applying |
| `ConfigMap` | `event-manager-config` | Non-sensitive config; host names are in-cluster service names |
| `Deployment` | `event-manager` | 3 replicas; rolling update with `maxUnavailable: 0`; container exposes both `http` (8080) and `grpc` (9091) ports |
| `Service` | `event-manager-service` | `LoadBalancer` with two named ports: `http` (80 → pod 8080, REST API + web UI) and `grpc` (9091 → pod 9091, this app's own gRPC API server). Named ports are required once a `Service` exposes more than one. |

Resource sizing: 250m CPU / 512Mi memory requests; 1 CPU / 1Gi limits. Rolling update strategy keeps all 3 replicas live during a deploy (`maxUnavailable: 0`, `maxSurge: 1`).

Probes use the dedicated Spring Boot Kubernetes endpoints, both over HTTP on port 8080 (there is no separate gRPC health check — a pod failing its HTTP readiness probe is removed from load balancing for both the `http` and `grpc` Service ports together, since both come from the same container):
- **Readiness**: `/actuator/health/readiness` — checks datastores; pod removed from load balancing if unhealthy
- **Liveness**: `/actuator/health/liveness` — checks JVM only; does not restart pods on DB outage

The gRPC server binds `grpc-api.port` (default `9091`); it is not independently configured here — the `ConfigMap`/`Secret` only override the datastore/JWT settings that differ per environment, and `grpc-api.enabled`/`grpc-api.port` are left at their `application.yml` defaults. Set `GRPC_API_ENABLED`/`GRPC_API_PORT` (and, for TLS, `GRPC_API_TLS_*`/`SERVER_SSL_*`) in the `ConfigMap`/`Secret` if any need to be overridden per environment.

A plain `LoadBalancer` Service balances at the connection level (L4), which is enough for the REST API/web UI but not for gRPC's per-RPC load balancing across replicas, since HTTP/2 connection multiplexing pins a client to one backend for the life of its one long-lived connection — see docs/load-balancer-options.md for the gRPC-aware internal (headless Service + client-side `round_robin`) and external (gRPC-aware Ingress) alternatives, and its background section on `ClusterIP` vs. `LoadBalancer`.

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

**Infrastructure:** `apache/kafka:3.9.0` (official Apache image) in KRaft mode (no ZooKeeper). Dual listeners: `EXTERNAL://localhost:9092` for host-to-container access, `INTERNAL://kafka:29092` for container-to-container. The `app` service connects via `KAFKA_BOOTSTRAP_SERVERS: kafka:29092`. Healthcheck uses `/opt/kafka/bin/kafka-topics.sh --list` with `start_period: 30s` — the full path is required because `/opt/kafka/bin` is not on `$PATH` in the Apache image. A fixed `CLUSTER_ID` env var is required by the Apache image for KRaft storage formatting (Bitnami generated this automatically). Env vars use `KAFKA_` prefix (e.g. `KAFKA_NODE_ID`) rather than the `KAFKA_CFG_` prefix Bitnami used.

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

**Why `RetryTemplate` not `@Retryable`:** Stacking `@Async` and `@Retryable` on the same method is broken — `@Async` submits to a thread pool and returns a proxy immediately, so the `@Retryable` proxy on the calling thread has nothing to retry. Using `RetryTemplate` programmatically inside the already-dispatched `@Async` method avoids the AOP proxy ordering conflict. No `@EnableRetry` annotation is needed.

### Kafka consumer skeleton

**`PerformerVideoEventConsumer`** — `kafka/PerformerVideoEventConsumer.java` — skeleton consumer for the same topic:
- `@KafkaListener` on `performer-video-events`; `groupId` from `spring.kafka.consumer.group-id` (default `event-manager-consumer`)
- Receives `ConsumerRecord<String, VideoEvent>` for access to partition/offset alongside the typed payload
- Manual ack mode (`Acknowledgment ack`): `ack.acknowledge()` is called only after successful processing; on exception the offset is not committed so the broker redelivers the message
- Dispatches on `event.operation()` to `handleAdd` / `handleDelete` stubs — fill these in with the downstream integration logic (search index, CDN, notification, etc.)
- Unrecognised `operation` values are logged as `WARN` and acknowledged (treat as safe-to-skip)
- Dead-letter topic strategy is not yet implemented; a comment marks the exception path as the extension point

**Consumer config** (`application.yml` `spring.kafka.consumer`):
- `JsonDeserializer` with `spring.json.value.default.type: com.eventmanager.kafka.VideoEvent` — deserializes directly to the record type without requiring a type header in the message (the producer does not write one)
- `spring.json.trusted.packages: com.eventmanager.kafka` — required by `JsonDeserializer` to prevent arbitrary class instantiation
- `auto-offset-reset: earliest` — new consumer group starts from the beginning of the topic
- `listener.ack-mode: manual` — matches the `Acknowledgment` parameter in the listener method; Spring will not auto-commit offsets

See `docs/kafka-consumer-guide.md` for a step-by-step walkthrough of how to implement the stubs and extend this consumer.

### Cassandra dual-write (events, performers, and ticket operations) and ticket backup
`EventService` and `PerformerService` write to Postgres first (synchronous, within the JPA transaction), then fire an **async**, fire-and-forget Cassandra write via `CassandraAsyncWriter`. `TicketService.recordOperation` uses this same async pattern for `TicketOperation` rows. Tickets themselves are the exception: `TicketService` writes ticket *backups* to Cassandra **synchronously**, and only as a step during event deletion — see **Tickets** below. Postgres is the source of truth; Cassandra is a secondary store with no read path yet.

**Performers**
- Entity: `cassandra/model/CassandraPerformer.java` — `@Table("performers")` with `id` as partition key; fields: `name`, `genre`, `bio`, `videoUrls` (`Set<String>` → Cassandra `SET<text>`)
- Repository: `cassandra/repository/PerformerCassandraRepository.java` — `CassandraRepository<CassandraPerformer, Long>`
- `PerformerService` calls `cassandraAsyncWriter.savePerformer()` / `deletePerformer()` after the Postgres write; `addVideo` and `deleteVideo` also call `savePerformer()` so the Cassandra `video_urls` set stays in sync

**Events**
- Entity: `cassandra/model/CassandraEvent.java` — `@Table("events")` with `id` as partition key; fields: `name`, `description`, `eventDate`, `ticketPrice`, `ticketsTotal`, `venueId`, `createdAt`, `updatedAt`. The `ManyToMany` performers relationship is not stored — it maps poorly to a Cassandra column and `CassandraPerformer` does not store event IDs either. There is no `status` column — see **Tickets** below for why the Cassandra copy is deliberately never updated to `DELETING`.
- Repository: `cassandra/repository/EventCassandraRepository.java` — `CassandraRepository<CassandraEvent, Long>`
- `EventService` calls `cassandraAsyncWriter.saveEvent()` after the Postgres write in `createEvent` and `updateEvent`; `toCassandraEntity(EventResponse)` maps the response DTO to `CassandraEvent`, deriving `venueId` from `response.getVenue().getId()`. `deleteEvent` deliberately does **not** call `cassandraAsyncWriter.deleteEvent()` — see **Tickets** below. `getNumAvailableTickets` is a read query and never writes to Cassandra.

**Tickets**
- Entity: `cassandra/model/CassandraTicket.java` — `@Table("tickets")` with `id` as partition key; fields: `eventId`, `description`, `status` (stored as plain `TEXT`, i.e. `ticket.getStatus().name()` — no Cassandra enum mapping is configured, unlike the JPA side's `@Enumerated(EnumType.STRING)`), `userId`, `datePurchased`
- Repository: `cassandra/repository/TicketCassandraRepository.java` — `CassandraRepository<CassandraTicket, Long>`
- Tickets are **never** written to Cassandra at creation — only `TicketService.backupAndDeleteAllForEvent(eventId)` writes them, as an archival step run synchronously during `EventService.deleteEvent`, *before* the same tickets are deleted from Postgres. This is why it bypasses `CassandraAsyncWriter`'s fire-and-forget `@Async` pattern entirely: the delete-from-Postgres step must not run until the backup is confirmed written, which an async, exception-swallowing write can't guarantee. `TicketCassandraRepository` is field-injected into `TicketService` directly (`@Autowired(required = false)`), and any exception during the backup propagates up through `deleteEvent`'s `@Transactional` boundary, rolling back the whole delete (status change, ticket removal, event removal — nothing commits) rather than risk deleting a sold ticket with no archived copy.
- **The backup sweep only archives `SOLD` tickets** — `backupAndDeleteAllForEvent` filters the event's tickets to `status == SOLD` before calling `ticketCassandraRepository.saveAll(...)`; `AVAILABLE`/`RESERVED`/`PAYING` tickets never had a completed sale worth preserving and are skipped by the archive entirely (no Cassandra row at all, not even a stub). This filtering affects only the Cassandra write — **every** ticket for the event, regardless of status, is still deleted from Postgres in the same `ticketRepository.deleteAll(tickets)` call, since all of them must be gone before the event row itself can be removed (FK constraint).
- `Ticket.datePurchased` is carried onto `CassandraTicket` when a sold ticket is archived — see **Ticket status state machine** for when it's stamped.
- Because `deleteEvent` never calls `cassandraAsyncWriter.deleteEvent()`, the event's last Cassandra copy (synced by the most recent `createEvent`/`updateEvent` call) is left in place after the Postgres row is gone — an intentional archive, mirroring what `backupAndDeleteAllForEvent` does for its tickets.

**Ticket operations**
- Entity: `model/TicketOperation.java` — table `ticket_operations`; a flat audit-log row (`eventId`/`ticketId`/`userId` are plain `Long` foreign-key columns, not JPA associations — nothing navigates from an operation back to its ticket/event/user). Fields: `eventId`, `ticketId`, `userId`, `operation` (`TicketOperationType`: `PURCHASE`, `PAYMENT_DECLINED`, or `CANCEL`, `@Enumerated(EnumType.STRING)`), `purchasePrice` (always the ticket's event's current `ticketPrice` at the moment of the operation), `paymentService` (fixed default `"Square"` for every row today — there's no multi-processor support, just the one payment service called over gRPC), `paymentConfirmationDetails` (the payment service's real `confirmationId` for a `PURCHASE` row, the literal string `"Payment declined"` for a `PAYMENT_DECLINED` row, or the fixed placeholder `"payment confirmation 1234"` for a `CANCEL` row, which has no payment-service interaction), `paymentRequestId` (nullable — the payment attempt's idempotency key; always set for `PURCHASE`/`PAYMENT_DECLINED`, always `null` for `CANCEL`), `idempotencyToken` (nullable — the payment service's own idempotency token, set for `PURCHASE`/`PAYMENT_DECLINED`, `null` for `CANCEL`), `operationDate` (`LocalDateTime.now()` at write time). See **Payment service integration (gRPC client)**.
- Cassandra entity: `cassandra/model/CassandraTicketOperation.java` — `@Table("ticket_operations")` with `id` as partition key (same generated id as the Postgres row), mirroring every field including `paymentRequestId`/`idempotencyToken`; `operation` stored as plain `TEXT` (like `CassandraTicket.status`, no Cassandra enum mapping).
- Repositories: `repository/TicketOperationRepository.java` (`JpaRepository<TicketOperation, Long>`, plus a derived `deleteByEventId(Long)`) and `cassandra/repository/TicketOperationCassandraRepository.java` (`CassandraRepository<CassandraTicketOperation, Long>`).
- `TicketService.recordOperation` has three overloads: a 3-arg one (`ticket, type, userId`) used only by `cancelTicket`, which fills in the fixed `paymentService`/`paymentConfirmationDetails` defaults and leaves `paymentRequestId`/`idempotencyToken` `null`; a 5-arg one (`ticket, type, userId, paymentRequestId, PaymentOutcome`) used by `completePayment` for `PURCHASE`/`PAYMENT_DECLINED`, which derives `paymentConfirmationDetails` from the outcome and carries the real `paymentRequestId`/`idempotencyToken`; both delegate to a private 7-arg overload that actually builds and saves the row. Either way: saves the Postgres row synchronously, then calls `cassandraAsyncWriter.saveTicketOperation()` — async, fire-and-forget, the same as events/performers, *not* the synchronous pattern ticket backups use, since there's no delete-ordering constraint to protect here. No row is written at all when a purchase attempt's gRPC call itself fails (timeout/connection error) — nothing was resolved to record; the ticket is simply left `PAYING`.
- `TicketService.backupAndDeleteAllForEvent(eventId)` also calls `ticketOperationRepository.deleteByEventId(eventId)`, removing the event's operations from Postgres — unconditionally, even when the event has no tickets left. Their Cassandra copies are **never** deleted (there's no `CassandraAsyncWriter.deleteTicketOperation` method), the same archival choice `deleteEvent` makes for the event's own Cassandra copy.
- There is no read API for ticket operations today — they exist purely as an audit trail written by `purchaseTicket`/`cancelTicket`.

**`CassandraAsyncWriter`**
Holds the performer, event, and ticket-operation repositories with `@Autowired(required = false)` field injection. Each method null-checks its repository before writing, so the bean operates safely when Cassandra is excluded (test profile). Methods: `savePerformer`, `deletePerformer`, `saveEvent`, `deleteEvent`, `saveTicketOperation` — all `@Async("cassandraExecutor")`. (`deleteEvent` still exists on the writer for API completeness but `EventService` no longer calls it; there is no `deleteTicketOperation` method at all — ticket operation archives are never deleted from Cassandra.)

`spring.cassandra.schema-action: create_if_not_exists` auto-creates all four tables (`events`, `performers`, `tickets`, `ticket_operations`) on startup. The `event_manager` keyspace is created by the `cassandra-init` container in docker-compose on first `docker compose up -d`. Cassandra takes ~60 s to start; the healthcheck has `start_period: 60s`.

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
| `register(RegisterRequest)` | POST | `/api/v1/auth/register` | public |
| `login(username, password)` | POST | `/api/v1/auth/login` | public |
| `getEvents()` | GET | `/api/v1/events` | public |
| `getEventsByVenue(venueId)` | GET | `/api/v1/events?venueId=` | public |
| `getEventsBetween(start, end)` | GET | `/api/v1/events?start=&end=` | public |
| `getEvent(id)` | GET | `/api/v1/events/{id}` | public |
| `createEvent(EventRequest)` | POST | `/api/v1/events` | ADMIN |
| `updateEvent(id, EventRequest)` | PUT | `/api/v1/events/{id}` | ADMIN |
| `getNumAvailableTickets(id)` | GET | `/api/v1/events/{id}/tickets/available/count` | public |
| `deleteEvent(id)` | DELETE | `/api/v1/events/{id}` | ADMIN |
| `getVenues()` | GET | `/api/v1/venues` | public |
| `getVenuesByCity(city)` | GET | `/api/v1/venues?city=` | public |
| `getVenue(id)` | GET | `/api/v1/venues/{id}` | public |
| `createVenue(VenueDto)` | POST | `/api/v1/venues` | ADMIN |
| `updateVenue(id, VenueDto)` | PUT | `/api/v1/venues/{id}` | ADMIN |
| `deleteVenue(id)` | DELETE | `/api/v1/venues/{id}` | ADMIN |
| `getPerformers()` | GET | `/api/v1/performers` | public |
| `searchPerformersByName(name)` | GET | `/api/v1/performers?name=` | public |
| `getPerformersByGenre(genre)` | GET | `/api/v1/performers?genre=` | public |
| `getPerformer(id)` | GET | `/api/v1/performers/{id}` | public |
| `createPerformer(PerformerDto)` | POST | `/api/v1/performers` | ADMIN |
| `updatePerformer(id, PerformerDto)` | PUT | `/api/v1/performers/{id}` | ADMIN |
| `addVideo(performerId, url)` | POST | `/api/v1/performers/{id}/videos` | ADMIN |
| `deleteVideo(performerId, url)` | DELETE | `/api/v1/performers/{id}/videos` | ADMIN |
| `deletePerformer(id)` | DELETE | `/api/v1/performers/{id}` | ADMIN |
| `getTicket(id)` | GET | `/api/v1/tickets/{id}` | public |
| `getAvailableTickets(eventId, page, size)` | GET | `/api/v1/events/{id}/tickets/available?page=&size=` | public |
| `getMyTickets(page, size)` | GET | `/api/v1/tickets/me?page=&size=` | authenticated |
| `reserveTicket(id)` | POST | `/api/v1/tickets/{id}/reserve` | authenticated |
| `releaseTicket(id)` | POST | `/api/v1/tickets/{id}/release` | authenticated |
| `purchaseTicket(id, paymentToken, paymentRequestId)` | POST | `/api/v1/tickets/{id}/purchase` | authenticated |
| `cancelTicket(id)` | POST | `/api/v1/tickets/{id}/cancel` | authenticated |

There is no `createTicket`/`deleteTicket` method — tickets have no external create/delete API; `createEvent`/`deleteEvent` create/remove them as a side effect. There is also no `updateTicketStatus` method — each valid transition has its own dedicated client method instead of a generic status setter. There is also no `reserveTickets(id, count)`/`releaseTickets(id, count)` method — those event-level, aggregate-counter operations were removed along with `Event.ticketsAvailable` itself; `getNumAvailableTickets(id)` is the read-only replacement, always computed live from Postgres rather than returning a stored value. List responses use `ParameterizedTypeReference` to preserve generic type information at runtime. `deleteEvent`, `deleteVenue`, and `deletePerformer` return `void` — a 204/200 with no body is a success. `getAvailableTickets` returns `PagedResponse<TicketResponse>` (also via `ParameterizedTypeReference`, since it's a generic wrapper).

### Test profile
`src/main/resources/application-test.yml` (activated by `@ActiveProfiles("test")`) swaps Postgres for H2 in-memory, sets `spring.cache.type: none` so Redis is not required, and excludes `CassandraAutoConfiguration`, `CassandraRepositoriesAutoConfiguration`, and `KafkaAutoConfiguration` so neither Cassandra nor Kafka is required during tests. It also disables the rate limiter (`rate-limit.enabled: false`) since no Redis broker runs there and `@AutoConfigureMockMvc` would otherwise route every `MockMvc` request through that filter, and disables this app's own gRPC server (`grpc-api.enabled: false`) so `mvn test` never binds a real socket — `EventManagerApiGrpcTest` overrides it back to `true` (with `grpc-api.port=0` for an OS-assigned port) for its own real-socket coverage.

### Test classes

| Class | Style | Tests | Notes |
|---|---|---|---|
| `EventManagerApplicationTests` | `@SpringBootTest` | 1 | Context load smoke test |
| `VenueServiceTest` | Mockito (`@ExtendWith(MockitoExtension.class)`) | 11 | Pure unit tests, no Spring context |
| `EventServiceTest` | Mockito (`@ExtendWith(MockitoExtension.class)`) | 18 | Pure unit tests, no Spring context |
| `PerformerServiceTest` | Mockito (`@ExtendWith(MockitoExtension.class)`) | 13 | Pure unit tests, no Spring context |
| `TicketServiceTest` | Mockito (`@ExtendWith(MockitoExtension.class)`) | 35 | Pure unit tests, no Spring context — see **Ticket status state machine** |
| `PerformerControllerTest` | `@SpringBootTest + @AutoConfigureMockMvc` | 16 | Full context with H2; mocks `PerformerService` |
| `RateLimiterTest` | Mockito (`@ExtendWith(MockitoExtension.class)`) | 3 | Pure unit tests, no Spring context, no Redis |
| `EdgeMetricsConfigTest` | Plain JUnit | 3 | Pure unit tests, no Spring context, no real `MeterRegistry` |
| `EventManagerApiGrpcTest` | `@SpringBootTest` (real socket, `grpc-api.enabled=true`/`port=0`) | 15 | Mocks `EventService`/`TicketService`/`VenueService`/`PerformerService`; real `JwtTokenProvider`-minted JWTs (`ROLE_USER` and `ROLE_ADMIN`) over a real gRPC channel — see **gRPC API server** |

**`VenueServiceTest`** covers: `getAllVenues` (list, empty), `getVenueById` (found/not found), `getVenuesByCity` (match, no match), `createVenue` (saves and returns DTO), `updateVenue` (updates all fields, throws when not found), `deleteVenue` (deletes when exists, throws when not found). Single dependency `VenueRepository`; constructor: `new VenueService(venueRepository)`.

**`EventServiceTest`** covers: `getAllEvents` (list, empty), `getEventById` (found/not found), `getEventsByVenue`, `getEventsBetween`, `createEvent` (verifies Cassandra write, `status=AVAILABLE` on the response, and `ticketService.createAvailableTickets(event, request.ticketsTotal)` — note this is the event's own `ticketsTotal` (100 in the fixture), not venue capacity (20000); throws `IllegalArgumentException` when `ticketsTotal` exceeds venue capacity — no save/ticket/Cassandra interaction; throws when venue missing — no ticket/Cassandra interaction; throws when performer ID not in DB — no ticket interaction), `updateEvent` (updates fields and Cassandra write; asserts `ticketsTotal` on the response is unchanged regardless of the request, since `Event.ticketsTotal` has no setter; throws when event missing; throws when new venue missing), `getNumAvailableTickets` (delegates to and returns `ticketService.getNumAvailableTickets(id)`; propagates `ResourceNotFoundException` when the ticket service throws it), `deleteEvent` (sets `status=DELETING` and saves, calls `ticketService.backupAndDeleteAllForEvent`, deletes from Postgres, and verifies **no** Cassandra interaction — the event's Cassandra copy is deliberately left in place; throws when not found — no ticket/Cassandra interaction). Constructor: `new EventService(eventRepository, venueRepository, performerRepository, venueService, performerService, cassandraAsyncWriter, ticketService)`. `venueService`, `performerService`, and `ticketService` are mocked — `toDto` is stubbed wherever `toResponse` is exercised. Note: `resolvePerformers` short-circuits on an empty `Set` without calling the repository, so stubbing `findAllById(Set.of())` triggers Mockito strict-mode UnnecessaryStubbing; omit that stub when `performerIds` is empty. There are no `reserveTickets`/`releaseTickets` tests — those methods and the event-level `ticketsAvailable` counter they mutated were removed.

**`PerformerServiceTest`** covers: `getAllPerformers`, `getPerformerById` (found/not found), `searchPerformers`, `getPerformersByGenre`, `createPerformer` (verifies `cassandraAsyncWriter.savePerformer` is called), `updatePerformer` (found/not found), `deletePerformer` (found/not found). Mocks `CassandraAsyncWriter`, `VideoRepository`, and `PerformerVideoEventPublisher` directly via constructor — no `ReflectionTestUtils` needed. Constructor: `new PerformerService(performerRepository, videoRepository, cassandraAsyncWriter, videoEventPublisher)`. The "absent Cassandra/Kafka" scenarios are handled by null-check guards in `CassandraAsyncWriter` and `PerformerVideoEventPublisher` and are not tested at the service level. Repository stubs use the new video-aware method names (`findAllWithVideos`, `findByIdWithVideos`, etc.).

**`TicketServiceTest`** covers: `getTicketById` (found/not found), `getMyTickets` (returns a `PagedResponse` scoped to the authenticated caller's user id via `ticketRepository.findByUserId`; clamps an oversized `size` request to 100; throws `ResourceNotFoundException` when the caller's username has no matching `User` row, with no repository query issued), `getNumAvailableTickets` (returns the count from `ticketRepository.countByEventIdAndStatus`; throws `ResourceNotFoundException` when the event is missing, with no repository count query issued), `getAvailableTickets` (returns a `PagedResponse` built from the mocked `Page<Ticket>`; throws when event missing; clamps an oversized `size` request to 100), `reserveTicket` (succeeds and stamps the caller's user id when currently AVAILABLE; throws `IllegalArgumentException` otherwise; not-found; caller's username having no matching `User` row), `releaseTicket` (succeeds and clears `userId` back to `null` when currently RESERVED by the same caller; throws `IllegalArgumentException` when not currently RESERVED — no `userRepository` stub or `authenticateAs` call, since `release()`'s status check short-circuits before any JWT lookup; throws `AccessDeniedException` when RESERVED by a different user; not-found), `cancelTicket` (succeeds when currently SOLD by the same caller, leaves `userId` in place afterward, and records a `CANCEL` `TicketOperation` the same way as before; throws `IllegalArgumentException` when not currently SOLD — no operation recorded; throws `AccessDeniedException` when SOLD to a different user — no operation recorded; not-found — no operation recorded), `createAvailableTickets` (creates one AVAILABLE ticket with `userId = null` per unit of capacity, captured via `ArgumentCaptor` on `ticketRepository.saveAll`). Constructor: `new TicketService(ticketRepository, eventRepository, userRepository, ticketOperationRepository, cassandraAsyncWriter, paymentServiceClient, paymentCacheService, transactionManager, cacheManager)` — `PaymentServiceClient`, `PaymentCacheService`, `PlatformTransactionManager`, and `CacheManager` are added as plain Mockito `@Mock`s; none need stubbing for the saga's transactional plumbing to work, since an unstubbed `PlatformTransactionManager` mock returns `null` from `getTransaction`, which `TransactionTemplate` just passes through to `beginPayment`/`completePayment` and then to `commit`/`rollback`, none of which dereference it. The reserve/release/cancel success and ownership-mismatch tests populate `SecurityContextHolder` directly with a plain `UsernamePasswordAuthenticationToken(username, null, authorities)` in a small `authenticateAs(username)` helper — no Spring context or `@WithMockUser` needed; an `@AfterEach` calls `SecurityContextHolder.clearContext()` so no state leaks between tests.

**`purchaseTicket`** (the saga — see **Ticket status state machine**): `purchaseTicket_succeedsWhenReservedBySameUserAndPaymentApproved` (mocks `PaymentServiceClient.purchaseTicket(...)` to return an `APPROVED` `PaymentOutcome`; asserts two `ticketRepository.save` calls — one from each saga transaction — that `datePurchased` is stamped on the result, and that the recorded `TicketOperation` carries the real confirmation id and idempotency token), `purchaseTicket_usesClientSuppliedPaymentRequestId`, `purchaseTicket_movesBackToAvailableWhenPaymentDeclined` (`DECLINED` outcome → `AVAILABLE`, records `PAYMENT_DECLINED`), `purchaseTicket_throwsPaymentServiceUnavailableAndLeavesTicketPayingWhenCallFails` (`ERROR` outcome → only one `save` call total, ticket stays `PAYING`, no `TicketOperation` recorded), `purchaseTicket_throws409WhenAlreadyPaying` (ticket pre-seeded as `PAYING` → `TicketPaymentInProgressException`, no repository/payment-client interaction at all), and `purchaseTicket_throwsWhenNotCurrentlyReserved`/`throwsAccessDeniedWhenReservedByDifferentUser`/`throwsWhenNotFound`. A Mockito pitfall avoided here: `beginPayment`/`completePayment` mutate and re-save the *same* mocked `Ticket` instance across both saga transactions, so an `ArgumentCaptor` on `ticketRepository.save` can't distinguish the two calls' intermediate states — only call count and the final returned `TicketResponse` are asserted.

**`backupAndDeleteAllForEvent`** is covered by `backupAndDeleteAllForEvent_backsUpOnlySoldTicketsThenDeletesAllFromPostgres` (a mixed-status fixture — one `AVAILABLE`, one `SOLD` with a stamped `datePurchased` — asserting only the `SOLD` ticket reaches `ticketCassandraRepository.saveAll(...)`, with `datePurchased` carried onto the `CassandraTicket`, while `ticketRepository.deleteAll(...)` still receives both), `backupAndDeleteAllForEvent_skipsCassandraEntirelyWhenNoTicketsAreSold` (an all-`AVAILABLE` fixture → `verifyNoInteractions(ticketCassandraRepository)`, but Postgres deletion still happens), `backupAndDeleteAllForEvent_skipsCassandraWhenRepositoryAbsent` (repository left `null`, as in the test profile, injected via `ReflectionTestUtils.setField` when present — the one place in this test suite that needs it, since `TicketCassandraRepository` is field-injected directly into `TicketService` rather than wrapped by a mockable collaborator like `CassandraAsyncWriter`), and a no-tickets case that still verifies `ticketOperationRepository.deleteByEventId(eventId)` is called regardless.

**`EventManagerApiGrpcTest`** covers, over real generated `BlockingStub`s against a real socket: a public RPC with no token; `UNAUTHENTICATED` on an authenticated RPC with no token and with a garbage token; a real `ROLE_USER` JWT succeeding and resolving the correct caller inside a mocked `TicketService.reserveTicket` call (proving the gRPC `Context`→`SecurityContextHolder` bridge survives the real async dispatch); a `ROLE_ADMIN` JWT succeeding on an admin-only RPC (`CreateVenue`, `DeletePerformer`) while the same call with a `ROLE_USER` token gets `PERMISSION_DENIED` and with no token gets `UNAUTHENTICATED`; a deliberately-blank required field on a `CreateVenueRequest` mapping to `INVALID_ARGUMENT`; and a thrown `TicketPaymentInProgressException` arriving at the client as `FAILED_PRECONDITION`. See **gRPC API server**.

**`PerformerControllerTest`** covers: happy-path responses, query param routing (`?name=`, `?genre=`), 400 on validation failure, 403 for `ROLE_USER` on admin endpoints, **401** for unauthenticated requests (changed from 403 when `oauth2ResourceServer` installed `BearerTokenAuthenticationEntryPoint`), 404 with error body.

**`RateLimiterTest`** covers: `isAllowed` returns `true` when the Lua script returns `1` (under limit), `false` when it returns `0` (limit exceeded), and `true` (fail-open) when `StringRedisTemplate.execute` throws — no real Redis involved; the script's own return value is stubbed directly via a mocked `StringRedisTemplate`, so this tests `RateLimiter`'s Java-side contract (result interpretation, fail-open behavior) rather than the Lua script's correctness, which was instead verified live against a real Redis during development (a low `max-requests`/`window-duration` override reproducibly triggered `429`s that recovered once the window rolled).

**`EdgeMetricsConfigTest`** covers: `EdgeMetricsConfig.edgeMetricsFilter()`'s `accept(Meter.Id)` returns `DENY` for all four edge meter names (`http.server.requests`, `http.server.requests.active`, `process.uptime`, `process.start.time`) when `EdgeMetricsProperties.enabled = false`, `NEUTRAL` for the same four when `enabled = true`, and `NEUTRAL` for unrelated meters (`jvm.memory.used`, `process.cpu.usage`, `hikaricp.connections.active`) even when disabled — guarding against the filter's deny-list ever accidentally widening to catch metrics that must stay regardless of an API gateway's presence. No Spring context or real `MeterRegistry` needed — `Meter.Id` instances are constructed directly and passed to the filter.

**`@WebMvcTest` caveat:** `@EnableJpaRepositories` on `EventManagerApplication` causes `@WebMvcTest` slices to fail (JPA is forced into the context but `entityManagerFactory` isn't auto-configured by the slice). Controller tests use `@SpringBootTest + @AutoConfigureMockMvc + @ActiveProfiles("test")` instead.

**Async Cassandra/Kafka in service tests:** `@Async` is an AOP proxy feature — in a plain Mockito test with no Spring context, `CassandraAsyncWriter` and `PerformerVideoEventPublisher` methods run synchronously. This is fine: tests verify that the collaborators are called, not that they ran on a background thread. All four dependencies are constructor-injected into `PerformerService`, so `new PerformerService(performerRepository, videoRepository, cassandraAsyncWriter, videoEventPublisher)` with Mockito mocks is all that is needed — no `ReflectionTestUtils`.

**`AccessDeniedException` handling:** `GlobalExceptionHandler` has an explicit `@ExceptionHandler(AccessDeniedException.class)` returning 403. Without it, the catch-all `Exception` handler intercepts `@PreAuthorize` rejections and returns 500 instead of 403. Unauthenticated requests return **401** (not 403) because `oauth2ResourceServer` registers a `BearerTokenAuthenticationEntryPoint`; prior to adding OAuth2 support, no entry point was configured and Spring Security's default `Http403ForbiddenEntryPoint` applied.
