# Architecture Overview

## Three transports, one service layer

REST (`/api/v1/**`), the Thymeleaf web UI (`/ui/**`), and a gRPC server (port 9091 by default,
separate from the HTTP port 8080) all call the same `EventService`/`TicketService`/`VenueService`/
`PerformerService` beans — no business logic is duplicated across transports. The gRPC server now
covers the full CRUD surface for events/venues/performers plus every ticket transition, and has
its own auth layer (`JwtServerInterceptor`, validating the same custom JWT outside the servlet
filter chain below, since none of it sees gRPC traffic) with its own `ROLE_ADMIN` gate
(`GrpcCallSupport.runAdmin`, returning `PERMISSION_DENIED` — the gRPC equivalent of
`@PreAuthorize("hasRole('ADMIN')")`) for the same create/update/delete operations the REST API
restricts to admins — see grpc-api-server.md and docs/grpc-api-exposure.md. (Separately, this
application is also a gRPC *client* to an external payment service — see payment-service-grpc.md —
unrelated, opposite direction.)

## Request flow
```
HTTP Request
  → RateLimitFilter (highest precedence — runs before every filter chain below)
  │    Redis sliding-window check keyed by client IP; 429 short-circuits here,
  │    never reaching authentication/session/CSRF — see rate-limiting.md
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
  │    → WebEventController / WebVenueController / WebPerformerController / WebTicketController / WebAuthController
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
            ↓ (event, performer, and ticket-operation writes, fire-and-forget)
       → CassandraAsyncWriter (@Async → cassandraExecutor thread pool → EventCassandraRepository / PerformerCassandraRepository / TicketOperationCassandraRepository)
            ↓ (performer video add/delete, fire-and-forget with retry)
       → PerformerVideoEventPublisher (@Async → kafkaExecutor thread pool → KafkaTemplate → performer-video-events topic)
```

## API endpoints

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

**Application endpoints** (handled by the `@Order(4)` API filter chain):

| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/api/v1/auth/register` | public | returns custom JWT |
| POST | `/api/v1/auth/login` | public | returns custom JWT |
| GET | `/api/v1/events` | public | optional `?venueId=`, `?performerId=`, `?start=`, `?end=` (ISO datetime), **combined with AND**: any subset narrows the result, and `start`/`end` are inclusive and each works alone. Ordered by event date. 400 if `start` is after `end` or a value is malformed. Shared with gRPC `ListEvents` via `EventService.searchEvents(EventSearchCriteria)` |
| GET | `/api/v1/events/{id}` | public | cached |
| POST | `/api/v1/events` | ADMIN | 400 if `ticketsTotal` exceeds venue capacity; sets `status=AVAILABLE`; synchronously creates one AVAILABLE ticket per unit of `ticketsTotal` (Postgres only, not Cassandra); Cassandra dual-write of the event itself |
| PUT | `/api/v1/events/{id}` | ADMIN | body: `UpdateEventRequest` — unlike `POST`'s `EventRequest`, has no `ticketsTotal` field at all (fixed at creation, immutable); Cassandra dual-write |
| DELETE | `/api/v1/events/{id}` | ADMIN | sets `status=DELETING`; archives the event's **SOLD** tickets to Cassandra (AVAILABLE/RESERVED/PAYING tickets never had a completed sale and are skipped by the archive), then deletes **all** of the event's tickets from Postgres regardless of status (FK constraint); also deletes the event's `TicketOperation` rows from Postgres only (their Cassandra copies are left in place); deletes the event from Postgres only — its last-synced Cassandra copy is left in place |
| GET | `/api/v1/events/{id}/tickets/available` | public | paginated (`?page=&size=`, 0-based, size 1–100, default 0/20); tickets with status `AVAILABLE`, ordered by ticket id. Returns `AvailableTicketsPage`: the usual page fields plus `maxTicketId`, the event's largest ticket id (any status, fixed at creation), for padding ticket numbers to one width. Ticket ids are global, not 1..N per event |
| POST | `/api/v1/events/{id}/tickets/reserve` | authenticated | reserves the **next** AVAILABLE ticket for the event (lowest id) instead of a caller-chosen one — same end state as `POST /api/v1/tickets/{id}/reserve`; picked with `FOR UPDATE SKIP LOCKED` so concurrent calls get different tickets; 404 if the event doesn't exist, 409 if none are AVAILABLE |
| GET | `/api/v1/events/{id}/tickets/available/count` | public | live count of tickets with status `AVAILABLE`, computed by querying Postgres directly (`TicketRepository.countByEventIdAndStatus`) — not a stored counter |
| GET | `/api/v1/tickets/{id}` | public | cached; tickets have no external create/delete operation — see `POST /api/v1/events` and `DELETE /api/v1/events/{id}` above |
| GET | `/api/v1/tickets/me` | authenticated | paginated (`?page=&size=`, 0-based, size 1–100, default 0/20); every ticket owned by the caller, across all events/statuses, ordered by ticket id; returns `TicketListPage` (a `PagedResponse` subclass) with `maxTicketIdByEvent`, each listed event's largest ticket id, so clients can pad ticket numbers per event; the caller's own JWT `user_id` is the only one that can ever be queried — no way to look up another user's tickets |
| GET | `/api/v1/tickets/me/transactions` | authenticated | paginated (`?page=&size=`, default 0/20, size clamped to 100); the caller's ticket transactions (`TicketOperation` rows: PURCHASE, PAYMENT_DECLINED, CANCEL), newest first, as `TicketTransactionResponse` inside a `TicketListPage` (with `maxTicketIdByEvent`, as for `/tickets/me`). Includes the event name and the ticket's `ticketDescription`; **never includes the payment `idempotencyToken`** (or `userId`). `operationDate` is an `OffsetDateTime`: the stored zone-less `LocalDateTime` is converted with the server's zone (the zone `LocalDateTime.now()` wrote it in; UTC in Docker), so clients can show it in the viewer's zone. Caveat: a row written by a JVM in a different zone (e.g. a local `mvn spring-boot:run` in PST against the same database) gets the wrong offset; storing `timestamptz`/`Instant` would remove that |
| POST | `/api/v1/tickets/{id}/reserve` | authenticated | requires current status AVAILABLE; stamps `user_id` from the caller's JWT; moves to RESERVED |
| POST | `/api/v1/tickets/{id}/release` | authenticated | requires current status RESERVED and the caller's JWT `user_id` to match the ticket's; moves back to AVAILABLE and clears `user_id` |
| POST | `/api/v1/tickets/{id}/purchase` | authenticated | body: `{ paymentToken, paymentRequestId? }`; requires current status RESERVED (409 if already PAYING) and the caller's JWT `user_id` to match the ticket's; charges the ticket via an external payment service over gRPC — approved moves to SOLD and stamps `datePurchased`, declined moves back to AVAILABLE, a failed payment-service call (502) leaves the ticket PAYING — see payment-service-grpc.md and ticket-status-state-machine.md |
| POST | `/api/v1/tickets/{id}/cancel` | authenticated | requires current status SOLD and the caller's JWT `user_id` to match the ticket's; moves back to AVAILABLE, clears `user_id`, and records a CANCEL `TicketOperation` (see data-model.md) |
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
