# Other Technologies Commonly Used in Java Backend Stacks

This project already uses a broad slice of a modern Java backend stack (Spring Boot, PostgreSQL,
Redis, Cassandra, Kafka, OAuth2, Micrometer/Prometheus/Grafana, springdoc-openapi). Here's what else
shows up commonly elsewhere, organized by the same categories, so it's clear what's an alternative
to something already in this project vs. genuinely new territory.

## Frameworks (alternatives to Spring Boot)
- **Quarkus**, **Micronaut** — "cloud-native" Java frameworks built for fast startup and low memory
  via compile-time DI/bean processing (rather than Spring's runtime reflection), often paired with
  GraalVM native-image for near-instant cold starts — relevant for serverless/K8s at scale.
- **Vert.x** — event-loop/reactive toolkit, closer to Node.js's model than to Spring's
  servlet-per-thread model.

## Persistence
- **Flyway** / **Liquibase** — versioned SQL migrations. This project relies on Hibernate's
  `ddl-auto: update`, which most production teams replace with one of these for reviewable,
  repeatable schema changes.
- **jOOQ** — type-safe SQL query builder, an alternative to JPA/Hibernate when teams want to write
  real SQL but keep compile-time safety.
- **MapStruct** — generates DTO↔entity mapping code at compile time, instead of hand-written
  `toResponse()`/`toDto()` methods like this project uses.

## Other datastores
- **MongoDB** (document store), **Elasticsearch/OpenSearch** (full-text search — often bolted onto
  a system like this one specifically for performer/event search — see
  `elasticsearch-search-extension.md` for what that would look like here), **DynamoDB**/
  **CockroachDB** for other consistency/scale trade-offs than Postgres+Cassandra here.

## Messaging (alternatives/complements to Kafka)
- **RabbitMQ** / **ActiveMQ** — traditional message brokers (AMQP), better fit than Kafka for simple
  task queues rather than event streaming.
- **Schema Registry** (Confluent/Apicurio) + **Avro/Protobuf** — typed, versioned message schemas
  instead of this project's plain JSON Kafka payloads.

## Caching
- **Caffeine** — high-performance in-process cache, often layered *in front of* Redis (L1/L2
  caching) rather than replacing it.
- **Hazelcast** — distributed in-memory data grid, an alternative to Redis when you want the cache
  co-located with app instances.

## Resilience
- **Resilience4j** — circuit breakers, retries, bulkheads, and rate limiting as a library, an
  alternative to this project's hand-rolled Redis rate limiter and `RetryTemplate` usage.

## Testing
- **Testcontainers** — spins up real Postgres/Kafka/Redis in Docker for integration tests, instead
  of this project's H2/disabled-Cassandra-and-Kafka test-profile approach — the more common way to
  get true integration coverage.
- **ArchUnit** — enforces architectural rules (e.g., "controllers must not depend on repositories
  directly") as unit tests.

## Observability
- **OpenTelemetry** — vendor-neutral distributed tracing, usually paired with **Jaeger** or
  **Zipkin**; this project has metrics (Micrometer/Prometheus) and logs but no distributed tracing
  yet.
- **ELK/EFK stack** (Elasticsearch/Logstash/Kibana or Fluentd) — centralized log aggregation, since
  this project's logs currently just go to stdout.

## Security/Identity
- **Keycloak** / **Auth0** / **Okta** — external Identity Providers, an alternative to this
  project's self-hosted `spring-boot-starter-oauth2-authorization-server`.

## API styles beyond REST
- **GraphQL** (Spring GraphQL, Netflix DGS) and **gRPC** (Protobuf-based RPC) — common where REST's
  over/under-fetching or HTTP/JSON's overhead becomes a real cost.

## Reactive stack
- **WebFlux + R2DBC** — this project's own docs already note this as the non-blocking alternative to
  virtual threads, mentioned but not implemented here.
