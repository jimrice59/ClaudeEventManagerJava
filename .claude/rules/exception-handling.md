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
| `BadCredentialsException` | 401 | Fixed message "Invalid username or password" |
| `IllegalArgumentException` | 400 | Message from exception; used by `AuthService` for duplicate username/email and by `EventService` for invalid ticket counts |
| `MethodArgumentNotValidException` | 400 | Returns `{ status, errors: { field: message }, timestamp }` — different shape from `ErrorResponse` |
| `Exception` (catch-all) | 500 | Generic message; logged server-side at `ERROR` (`log.error("Unhandled exception", ex)`) since this is the only place an unexpected failure is ever surfaced — the client only ever sees the generic message, never a stack trace |

`ErrorResponse` is a record `(int status, String message)` with a computed `timestamp()` method.

**`AccessDeniedException` handling:** `GlobalExceptionHandler` has an explicit `@ExceptionHandler(AccessDeniedException.class)` returning 403. Without it, the catch-all `Exception` handler intercepts `@PreAuthorize` rejections and returns 500 instead of 403. Unauthenticated requests return **401** (not 403) because `oauth2ResourceServer` registers a `BearerTokenAuthenticationEntryPoint`; prior to adding OAuth2 support, no entry point was configured and Spring Security's default `Http403ForbiddenEntryPoint` applied.
