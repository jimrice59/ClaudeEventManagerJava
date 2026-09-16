---
paths:
  - "src/main/java/com/eventmanager/controller/**"
  - "src/main/java/com/eventmanager/web/**"
  - "src/main/java/com/eventmanager/service/**"
---

# Logging

Every controller (`com.eventmanager.controller` and `com.eventmanager.web`) and every service uses `@Slf4j`
(Lombok) with structured parameterized log statements. The two layers log at different levels by design, so a
`DEBUG`-level log captures the full HTTP-to-business-logic trace of a request while an `INFO`-level production
log only shows meaningful business events, not one entry per controller method call:

| Layer | Level | When |
|---|---|---|
| Controllers (`*Controller`, `web.Web*Controller`) | `DEBUG` | Every request handler logs on entry with its path variables/query params/key body fields (e.g. `"Received request to get event id={}"`) — a thin HTTP-boundary trace, distinct from the service layer's business-event logging below. Never logs request bodies that carry credentials (`WebTicketController.purchase` logs the ticket id, never `userCredentials`; `AuthController`/`AuthService` never log passwords). |
| Services | `INFO` | Mutation entry and success (`createEvent`/`updateEvent`/`deleteEvent`, `createVenue`/`updateVenue`/`deleteVenue`, `createPerformer`/`updatePerformer`/`deletePerformer`, `reserveTicket`/`releaseTicket`/`purchaseTicket`/`cancelTicket`, `AuthService.register`/`login`) |
| Services | `WARN` | Not-found paths before throwing `ResourceNotFoundException`, and `EventService.createEvent`'s ticketsTotal-exceeds-capacity rejection before throwing `IllegalArgumentException`. Ticket transition validation failures (wrong status, wrong owner) inside `TicketService` are *not* logged at this level — they're expected, frequent, user-facing 400/403s, not exceptional conditions; `WebTicketController` logs a `WARN` when one of these is caught and turned into a flash `errorMessage`, since that's the point closest to the actual user-visible outcome. |
| Services | `DEBUG` | Read operations (query params, result counts) |

`CassandraAsyncWriter` also uses `@Slf4j`: `DEBUG` on successful async save/delete, `ERROR` on exception (with stack trace).

Root log level is `DEBUG` for `com.eventmanager` (set in `application.yml`), so both layers' logs are visible by
default in local/dev runs; a production deployment would typically raise the root level to `INFO` to keep only
the service layer's business events.
