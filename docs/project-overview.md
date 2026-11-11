# Event Manager — Project Overview

**Start here.** This is the entry point for a new developer — a map of what the project is, how
it's put together, and where to go next. For implementation-level detail on any of the topics
below, see **`docs/design-document.md`**, a single consolidated reference with its own table of
contents; for Claude Code's own per-topic context, see `.claude/rules/*.md`.

## Project Summary

**What it is:** A full-stack event ticketing platform — browse events/venues/performers, reserve
and purchase tickets, manage everything through a REST API, a gRPC API, and a server-rendered web
UI. Built as a demonstration/learning project touching a broad slice of a modern Java backend
stack.

### Tech Stack

| Layer | Technology |
|---|---|
| Backend | Spring Boot 3.2.5, Java 21 (virtual threads) |
| Primary datastore | PostgreSQL (via Spring Data JPA) |
| Cache | Redis (response caching + rate limiting) |
| Secondary store | Cassandra (async dual-write archive, synchronous for sold-ticket backups) |
| Messaging | Kafka (performer video events) |
| Auth | Custom JWT (HS256) **and** a self-hosted OAuth 2.0 Authorization Server (RS256) |
| Web UI | Thymeleaf + Bootstrap 5 (session-based) |
| SPA | React 19 + TypeScript + Vite (JWT-based, separate from the Thymeleaf UI) |
| RPC — server | gRPC server (plain grpc-java, port 9091) — a second, binary-wire-format transport for the same domain API as REST |
| RPC — client | gRPC client to an external payment service (ticket purchase only) — unrelated to, and the opposite direction of, the RPC server above |
| Transport security | TLS for both HTTP and gRPC, off by default (`server.ssl.*` / `grpc-api.tls.*`); `certs/generate-dev-certs.sh` generates a matching local dev cert for both |
| Observability | Micrometer + Prometheus + Grafana |
| Docs | springdoc-openapi / Swagger UI |
| Deployment | Docker Compose (dev), Kubernetes manifests (REST + gRPC Service ports), optional Traefik reverse proxy |

### Core Domain

- **Events** belong to a **Venue** and have many **Performers** (with videos). Tickets are created
  automatically at event-creation time — one row per unit of capacity.
- **Tickets** move through a state machine: `AVAILABLE → RESERVED → PAYING → SOLD`, with
  cancellation returning a sold ticket to `AVAILABLE` rather than a terminal "cancelled" state.
  Ownership is always derived from the caller's JWT, never accepted from the client. A ticket can
  be reserved by id, or the caller can ask for "the next available ticket" for an event and let the
  server choose one.
- **Ticket changes are safe under concurrency.** Every status transition locks the ticket's row
  while it checks and updates it, so when several requests race for the same ticket, exactly one
  wins. Before this, simultaneous purchases of one ticket could all succeed, charging the buyer
  more than once. "Reserve the next available" uses `SKIP LOCKED`, so simultaneous callers get
  different tickets instead of colliding.
- **Event browsing** filters by venue, performer, and date range in any combination.
- **Purchasing a ticket is a saga, not a single write.** `purchaseTicket` moves the ticket to
  `PAYING`, calls an external payment service over gRPC (deadline 120s) with no database
  transaction open during that call, then resolves to `SOLD` (stamping `datePurchased`) on
  approval or back to `AVAILABLE` on decline. If the call itself fails, the ticket is deliberately
  left `PAYING` rather than guessed at; nothing reconciles it automatically yet. For local
  development, `PAYMENT_SERVICE_ENABLED=false` skips the call and approves every purchase — see
  payment-service-grpc.md and docs/payment-service-integration.md.
- **Deleting an event archives only its `SOLD` tickets to Cassandra**, synchronously, before every
  ticket for that event (regardless of status) is deleted from Postgres — a ticket is never
  permanently removed without a confirmed archive existing first, but `AVAILABLE`/`RESERVED`/
  `PAYING` tickets never had a sale worth preserving and are skipped.
- Two parallel frontends (React SPA and Thymeleaf) hit the same backend through different auth
  models (JWT vs. session), sharing no code.
- The backend itself is reachable over **three transports** — REST (`/api/v1/**`), the Thymeleaf
  web UI (`/ui/**`), and a **gRPC server** on its own port (9091) — all three calling the same
  `EventService`/`TicketService`/`VenueService`/`PerformerService` beans, so no business logic is
  duplicated per transport. gRPC covers the full CRUD surface for events/venues/performers plus
  every ticket transition, reusing the same custom JWT (sent as `authorization: Bearer <token>`
  metadata instead of an HTTP header) and the same `ROLE_ADMIN` gate as REST, just enforced by a
  hand-written interceptor/helper (`JwtServerInterceptor`, `GrpcCallSupport`) instead of Spring
  Security filters — see docs/grpc-api-support.md. This is unrelated to — and in the opposite
  direction from — this app's own use of gRPC as a *client* to the payment service above.

### What is a Spring Boot application?

Spring Boot is an opinionated layer on top of the core Spring Framework that makes it fast to build
a standalone, production-ready Java application without the heavy XML/manual-bean-wiring
configuration that plain Spring used to require. Three things define it:

- **Auto-configuration** — it inspects what's on the classpath and wires up sensible defaults
  automatically. Add `spring-boot-starter-data-jpa` and it configures a `DataSource`,
  `EntityManagerFactory`, and transaction manager for you; add `spring-boot-starter-web` and it sets
  up an embedded servlet container.
- **Embedded server** — the app is a runnable JAR with an embedded Tomcat (or Netty, Jetty) baked
  in, not a WAR you deploy into an external app server. `java -jar app.jar` and it's serving
  requests.
- **Starters** — curated dependency bundles (`spring-boot-starter-security`, `-data-redis`,
  `-actuator`, etc.) that pull in a coherent, tested set of libraries for one concern instead of you
  hand-picking versions.

This repo is a concrete example: `@SpringBootApplication` on `EventManagerApplication` is the entry
point; adding `spring-boot-starter-web`, `-data-jpa`, `-data-redis`, `-security`, `-actuator`, and
`-oauth2-authorization-server` to `pom.xml` is what gives it its embedded Tomcat, JPA/Postgres
wiring, Redis caching, security filter chains, and `/actuator/*` endpoints — all with a handful of
`application.yml` properties instead of hand-written configuration classes for each one.

### What is a Single Page Application (SPA)?

SPA stands for Single-Page Application — a web app (like the React frontend in this project) that
loads once and updates the page dynamically via JavaScript/API calls, rather than requesting a full
new HTML page from the server on every navigation (which is what the Thymeleaf UI does instead).

## Repository Layout

| Path | What's there |
|---|---|
| `src/main/java/com/eventmanager/` | The Spring Boot backend — controllers, services, repositories, `grpcserver/` (gRPC API server), `payment/`/`config/PaymentGrpcConfig.java` (payment-service gRPC client), `cassandra/` (secondary-store entities/repos) |
| `src/main/proto/` | Protobuf definitions — `event_manager_api.proto`/`venue_api.proto`/`performer_api.proto` (this app's own gRPC API) and `payment_service.proto` (the payment-service client contract) |
| `src/main/resources/` | `application.yml` (+ `-dev`/`-test` profiles), Thymeleaf templates, static assets |
| `frontend/` | Standalone Vite + React + TypeScript SPA, independent of the Thymeleaf UI |
| `k8s/deployment.yml` | Kubernetes `Secret`/`ConfigMap`/`Deployment`/`Service` manifests (REST + gRPC Service ports) |
| `certs/` | `generate-dev-certs.sh` — generates a matching self-signed TLS cert for local HTTPS + gRPC testing |
| `docker-compose.yml`, `Dockerfile` | Local infrastructure stack and the two-stage container build |
| `.claude/rules/` | 23 topic files of Claude Code context, most path-scoped so only relevant material loads per task |
| `docs/` | This overview, `design-document.md`, and 20 supplementary docs — see **Documentation Structure** below |

## Infrastructure (`docker-compose.yml`)

| Service | Purpose |
|---|---|
| `postgres` | Primary datastore |
| `redis` | Response caching + rate limiting |
| `cassandra` + `cassandra-init` | Secondary archive store; the init container creates the keyspace on first run |
| `kafka` | Performer video events (KRaft mode, no ZooKeeper) |
| `app` | This application — exposes `8080` (REST + web UI) and `9091` (gRPC) |
| `traefik` | Optional reverse proxy/load balancer, behind the `traefik` profile |
| `prometheus`, `grafana` | Metrics scraping + dashboards |

There is no payment-service container. `PaymentGrpcConfig`'s channel is lazy, so the app starts
fine without one, and `docker-compose.yml` sets `PAYMENT_SERVICE_ENABLED=false` by default so
purchases are simulated (always approved, nobody charged) instead of failing. After changing backend
code, run `docker compose up -d --build app`: plain `up -d` keeps running the previously built image.
See build-and-run.md for the full command reference.

### Notable Engineering Decisions

- **Virtual threads** instead of a reactive stack for Postgres concurrency; **`@Async` +
  virtual-thread executors** for the fire-and-forget Cassandra/Kafka side-writes.
- **The ticket-purchase saga runs as two short transactions, not one** — wrapping the whole
  purchase (including the up-to-120-second payment-service call) in a single `@Transactional`
  would pin a Postgres connection and row lock for that entire duration; a `TransactionTemplate`
  commits RESERVED→PAYING, makes the call with no transaction open, then commits the outcome.
- **Pessimistic row locks for ticket transitions, not optimistic `@Version` checks** — there's no
  migration tool, so adding a version column to the existing tickets table would leave it NULL on
  every row. Each lock lasts only as long as that step's transaction, and never spans the
  payment-service call.
- **Redis sliding-window rate limiting** via an atomic Lua script (not a fixed-window counter, which
  would allow bursting) — fails *open* on Redis outages so the limiter itself is never a single
  point of failure.
- **Edge-metrics toggle**: HTTP-level Micrometer meters (request rate, latency percentiles, status
  breakdown, uptime) can be switched off via a `MeterFilter`, anticipating a future API gateway
  taking over that responsibility, while JVM/process-internal metrics always stay.
- **Cassandra dual-write is deliberately asymmetric** — most writes are async/fire-and-forget, but
  ticket *backups* before an event delete are synchronous and sold-tickets-only, since an
  unconfirmed async write can't be trusted to gate an irreversible deletion, and a never-sold
  ticket has nothing worth archiving.
- **TLS is opt-in everywhere, not assumed** — both the HTTP and gRPC listeners default to
  plaintext; turning TLS on for either is a config change plus a cert, not a code change, and one
  script generates a self-signed cert in both shapes (PKCS12 for Tomcat, PEM for grpc-java) for
  local testing.
- Four Spring Security filter chains ordered by specificity (OAuth2 AS → form login → session-based
  web UI → stateless API), with a rate-limit filter running ahead of all of them — none of this is
  visible to gRPC traffic, which has its own, separate auth interceptor.

## Documentation Structure

- **`.claude/rules/`** — 23 topic files (per the Claude Code rules spec), most path-scoped so only
  relevant context loads per task.
- **`docs/design-document.md`** — the next stop after this overview: the same material as one
  consolidated, human-readable reference with a table of contents, covering every topic below in
  implementation-level detail.
- **`docs/`** — 20 supplementary docs, roughly grouped:
  - *What's actually built* — `grpc-api-support.md`/`grpc-api-exposure.md` (this app's own gRPC
    server), `payment-service-integration.md` (the payment-service gRPC client and purchase saga),
    `cassandra-queries.md`, `kafka-consumer-guide.md`/`kafka-messages.md`/`kafka-topic-creation.md`.
  - *Design memos and alternatives explored, not (or not fully) implemented* —
    `redis-reservation-holds.md` (Redis-backed ticket holds instead of Postgres row-locking),
    `redis-rate-limiting-algorithms.md` (survey of Redis rate-limiting algorithms against the one
    in use), `cassandra-cdc-alternative.md`/`elasticsearch-cdc-alternative.md` (CDC vs. app-level
    dual-write), `elasticsearch-search-extension.md`, `load-balancer-options.md` (Traefik/NGINX/
    Ingress and gRPC-aware load balancing for Kubernetes), `elk-efk-stack.md`, `kafka-kraft-explainer.md`,
    and `grpc-service-exposure.md` (the original gRPC proposal — superseded by the two "what's
    actually built" gRPC docs above once the feature shipped).
  - *Cross-cutting* — `other-java-backend-technologies.md`, `typescript-vs-javascript.md` (learning
    explainers), `ux-review-findings.md`, `patterns-and-lessons.md` (cross-project log).

### Current State

153/153 backend tests passing (as of 2026-11-11). The most recent work:
- **Combinable event filters:** venue, performer and date range, with events listed in date order.
- **"Reserve the next available ticket"** for an event, in REST, gRPC and the React UI.
- **Concurrency fixes:** row locks fixed races that let simultaneous reserves or purchases of one
  ticket all succeed.
- **A payment-service toggle** for local development.
- **Error handling:** 400s instead of 500s for malformed request parameters.
- **React checkout:** an inline checkout panel with clear outcomes for each payment result.

Earlier work added this app's own gRPC API server, the payment-service gRPC client and its
two-transaction purchase saga, a Cassandra sold-tickets-only archive sweep on event deletion, and
optional TLS for both the HTTP and gRPC listeners.
