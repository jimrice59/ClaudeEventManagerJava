# Architecture Overview

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

**Application endpoints** (handled by `@Order(3)` API filter chain):

| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/api/v1/auth/register` | public | returns custom JWT |
| POST | `/api/v1/auth/login` | public | returns custom JWT |
| GET | `/api/v1/events` | public | optional `?venueId=` or `?start=&end=` (ISO datetime) |
| GET | `/api/v1/events/{id}` | public | cached |
| POST | `/api/v1/events` | ADMIN | 400 if `ticketsTotal` exceeds venue capacity; sets `status=AVAILABLE`; synchronously creates one AVAILABLE ticket per unit of `ticketsTotal` (Postgres only, not Cassandra); Cassandra dual-write of the event itself |
| PUT | `/api/v1/events/{id}` | ADMIN | `ticketsTotal` in the body is ignored — fixed at creation, immutable; Cassandra dual-write |
| DELETE | `/api/v1/events/{id}` | ADMIN | sets `status=DELETING`; backs up all of the event's tickets to Cassandra then deletes them from Postgres; also deletes the event's `TicketOperation` rows from Postgres only (their Cassandra copies are left in place); deletes the event from Postgres only — its last-synced Cassandra copy is left in place |
| GET | `/api/v1/events/{id}/tickets/available` | public | paginated (`?page=&size=`, 0-based, size 1–100, default 0/20); tickets with status `AVAILABLE`, ordered by ticket id |
| GET | `/api/v1/events/{id}/tickets/available/count` | public | live count of tickets with status `AVAILABLE`, computed by querying Postgres directly (`TicketRepository.countByEventIdAndStatus`) — not a stored counter |
| GET | `/api/v1/tickets/{id}` | public | cached; tickets have no external create/delete operation — see `POST /api/v1/events` and `DELETE /api/v1/events/{id}` above |
| GET | `/api/v1/tickets/me` | authenticated | paginated (`?page=&size=`, 0-based, size 1–100, default 0/20); every ticket owned by the caller, across all events/statuses, ordered by ticket id; the caller's own JWT `user_id` is the only one that can ever be queried — no way to look up another user's tickets |
| POST | `/api/v1/tickets/{id}/reserve` | authenticated | requires current status AVAILABLE; stamps `user_id` from the caller's JWT; moves to RESERVED |
| POST | `/api/v1/tickets/{id}/release` | authenticated | requires current status RESERVED and the caller's JWT `user_id` to match the ticket's; moves back to AVAILABLE and clears `user_id` |
| POST | `/api/v1/tickets/{id}/purchase` | authenticated | body: `{ userCredentials }`; requires current status RESERVED and the caller's JWT `user_id` to match the ticket's; moves to SOLD (`userCredentials` is accepted and passed through but not yet verified against any store) |
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
