---
paths:
  - "src/main/java/com/eventmanager/exception/**"
  - "src/main/java/com/eventmanager/controller/**"
  - "src/main/java/com/eventmanager/web/**"
---

# Exception Handling

`GlobalExceptionHandler` (`@RestControllerAdvice`) maps exceptions to HTTP responses:

| Exception | Status | Notes |
|---|---|---|
| `ResourceNotFoundException` | 404 | Message from exception |
| `AccessDeniedException` | 403 | Fixed message "Access denied"; must be declared before the `Exception` catch-all or `@PreAuthorize` rejections return 500 |
| `TicketPaymentInProgressException` | 409 | A purchase attempt is already in flight for this ticket (status is already `PAYING`) — see payment-service-grpc.md |
| `NoTicketsAvailableException` | 409 | `POST /api/v1/events/{id}/tickets/reserve` found no `AVAILABLE` ticket to reserve (sold out, or still all locked after the configured retries) — see ticket-status-state-machine.md |
| `PaymentServiceUnavailableException` | 502 | The payment-service gRPC call itself failed (timeout/connection error); the ticket is left `PAYING`, and nothing reconciles it automatically yet |
| `ReservationsUnavailableException` | 503 | Redis (the reservation hold store) couldn\'t be reached or timed out: reservations fail closed rather than risk two people holding one ticket. Thrown by `TicketHoldService` for any Redis error |
| `BadCredentialsException` | 401 | Fixed message "Invalid username or password" |
| `IllegalArgumentException` | 400 | Message from exception; used by `AuthService` for duplicate username/email and by `EventService` for invalid ticket counts, and by `EventSearchCriteria` when `start` is after `end` |
| `MethodArgumentTypeMismatchException` | 400 | A query/path parameter that can't be converted to its type (`?start=June-1st`, `?venueId=abc`, `/events/abc`): "Invalid value 'X' for parameter 'p': expected Long", plus an ISO-8601 hint for date-time params. Previously fell through to the catch-all as a 500 |
| `MethodArgumentNotValidException` | 400 | Returns `{ status, errors: { field: message }, timestamp }` — different shape from `ErrorResponse` |
| `Exception` (catch-all) | 500 | Generic message; logged server-side at `ERROR` (`log.error("Unhandled exception", ex)`) since this is the only place an unexpected failure is ever surfaced — the client only ever sees the generic message, never a stack trace |

`ErrorResponse` is a record `(int status, String message)` with a computed `timestamp()` method.

**`AccessDeniedException` handling:** `GlobalExceptionHandler` has an explicit `@ExceptionHandler(AccessDeniedException.class)` returning 403. Without it, the catch-all `Exception` handler intercepts `@PreAuthorize` rejections and returns 500 instead of 403. Unauthenticated requests return **401** (not 403) because `oauth2ResourceServer` registers a `BearerTokenAuthenticationEntryPoint`; prior to adding OAuth2 support, no entry point was configured and Spring Security's default `Http403ForbiddenEntryPoint` applied.
