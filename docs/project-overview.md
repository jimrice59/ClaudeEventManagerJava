# Event Manager — Project Overview

## Project Summary

**What it is:** A full-stack event ticketing platform — browse events/venues/performers, reserve
and purchase tickets, manage everything through both a REST API and a server-rendered web UI. Built
as a demonstration/learning project touching a broad slice of a modern Java backend stack.

### Tech Stack

| Layer | Technology |
|---|---|
| Backend | Spring Boot 3.2.5, Java 21 (virtual threads) |
| Primary datastore | PostgreSQL (via Spring Data JPA) |
| Cache | Redis (response caching + rate limiting) |
| Secondary store | Cassandra (async dual-write archive) |
| Messaging | Kafka (performer video events) |
| Auth | Custom JWT (HS256) **and** a self-hosted OAuth 2.0 Authorization Server (RS256) |
| Web UI | Thymeleaf + Bootstrap 5 (session-based) |
| SPA | React 19 + TypeScript + Vite (JWT-based, separate from the Thymeleaf UI) |
| Observability | Micrometer + Prometheus + Grafana |
| Docs | springdoc-openapi / Swagger UI |
| Deployment | Docker Compose (dev), Kubernetes manifests, optional Traefik reverse proxy |

### Core Domain

- **Events** belong to a **Venue** and have many **Performers** (with videos). Tickets are created
  automatically at event-creation time — one row per unit of capacity — and archived to Cassandra
  before being deleted with the event.
- **Tickets** move through a strict state machine: `AVAILABLE → RESERVED → SOLD`, with cancellation
  returning a sold ticket to `AVAILABLE` rather than a terminal "cancelled" state. Ownership is
  always derived from the caller's JWT, never accepted from the client.
- Two parallel frontends (React SPA and Thymeleaf) hit the same backend through different auth
  models (JWT vs. session), sharing no code.

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

### Notable Engineering Decisions

- **Virtual threads** instead of a reactive stack for Postgres concurrency; **`@Async` +
  virtual-thread executors** for the fire-and-forget Cassandra/Kafka side-writes.
- **Redis sliding-window rate limiting** via an atomic Lua script (not a fixed-window counter, which
  would allow bursting) — fails *open* on Redis outages so the limiter itself is never a single
  point of failure.
- **Edge-metrics toggle**: HTTP-level Micrometer meters (request rate, latency percentiles, status
  breakdown, uptime) can be switched off via a `MeterFilter`, anticipating a future API gateway
  taking over that responsibility, while JVM/process-internal metrics always stay.
- **Cassandra dual-write is deliberately asymmetric** — most writes are async/fire-and-forget, but
  ticket *backups* before an event delete are synchronous, since an unconfirmed async write can't be
  trusted to gate an irreversible deletion.
- Four Spring Security filter chains ordered by specificity (OAuth2 AS → form login → session-based
  web UI → stateless API), with a rate-limit filter running ahead of all of them.

### Documentation Structure

- **`.claude/rules/`** — 20 topic files (per the Claude Code rules spec), most path-scoped so only
  relevant context loads per task.
- **`docs/design-document.md`** — the same material as one consolidated, human-readable reference
  with a table of contents.
- **`docs/`** — supplementary docs (Kafka guides, Cassandra queries, load-balancer options, UX
  review findings, the Redis-reservation-holds design memo, and cross-project patterns/lessons).

### Current State

95/95 backend tests passing. Recent work added the full ticket reservation lifecycle, Redis rate
limiting, the edge-metrics toggle, structured logging across all controllers/services, and matching
updates to both the standalone Java client library and the React frontend.
