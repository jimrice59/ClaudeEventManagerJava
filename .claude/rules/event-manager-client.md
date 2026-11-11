---
paths:
  - "src/main/java/com/eventmanager/client/**"
---

# EventManagerClient

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
| `searchEvents(venueId, performerId, start, end)` | GET | `/api/v1/events?venueId=&performerId=&start=&end=` | public — any argument may be null; the non-null ones are ANDed (both client copies: `src/main/.../client` and the `client/` module) |
| `getEvent(id)` | GET | `/api/v1/events/{id}` | public |
| `createEvent(EventRequest)` | POST | `/api/v1/events` | ADMIN |
| `updateEvent(id, UpdateEventRequest)` | PUT | `/api/v1/events/{id}` | ADMIN |
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
| `getMyTransactions(page, size)` | GET | `/api/v1/tickets/me/transactions?page=&size=` | authenticated — `PagedResponse<TicketTransactionResponse>` (both copies; the `client/` module has its own `TicketTransactionResponse`/`TicketOperationType`, with `operationDate` as `OffsetDateTime`) |
| `reserveTicket(id)` | POST | `/api/v1/tickets/{id}/reserve` | authenticated |
| `reserveAvailableTicket(eventId)` | POST | `/api/v1/events/{id}/tickets/reserve` | authenticated — the server picks the ticket; 409 (`HttpClientErrorException.Conflict`) if none is available |
| `releaseTicket(id)` | POST | `/api/v1/tickets/{id}/release` | authenticated |
| `purchaseTicket(id, paymentToken, paymentRequestId)` | POST | `/api/v1/tickets/{id}/purchase` | authenticated |
| `cancelTicket(id)` | POST | `/api/v1/tickets/{id}/cancel` | authenticated |

There is no `createTicket`/`deleteTicket` method — tickets have no external create/delete API; `createEvent`/`deleteEvent` create/remove them as a side effect. There is also no `updateTicketStatus` method — each valid transition has its own dedicated client method instead of a generic status setter. There is also no `reserveTickets(id, count)`/`releaseTickets(id, count)` method — those event-level, aggregate-counter operations were removed along with `Event.ticketsAvailable` itself; `getNumAvailableTickets(id)` is the read-only replacement, always computed live from Postgres rather than returning a stored value. List responses use `ParameterizedTypeReference` to preserve generic type information at runtime. `deleteEvent`, `deleteVenue`, and `deletePerformer` return `void` — a 204/200 with no body is a success. `getAvailableTickets` returns `PagedResponse<TicketResponse>` (also via `ParameterizedTypeReference`, since it's a generic wrapper). `paymentToken` must be a tokenized payment-method reference from the payment service, never raw card data; `paymentRequestId` may be `null` to let the server generate one (returned on `TicketResponse.getPaymentRequestId()`) — see payment-service-grpc.md.

**The `client/` module copy** (`client/src/main/java/com/eventmanager/client/EventManagerClient.java`, its own Maven module with its own DTOs in `client/dto`) has the same method set as the in-app copy, tickets included (`TicketResponse`, `TicketStatus`, `PerformerSummary` record, `PagedResponse<T>`, `PurchaseTicketRequest`, `EventStatus`, `UpdateEventRequest`). Its `updateEvent` takes `UpdateEventRequest` (no `ticketsTotal`); a `@Deprecated updateEvent(id, EventRequest)` overload converts via `UpdateEventRequest.from(...)` so existing callers still compile.

**Its Jackson mapper is a tolerant reader — keep it that way.** Unlike the in-app copy (plain `new RestTemplate()`, whose Spring-built mapper already ignores unknown fields), the module builds its own `ObjectMapper` in package-private `defaultRestTemplate()`. That mapper was `new ObjectMapper().registerModule(new JavaTimeModule())` until 2026-11-11, and a bare `ObjectMapper` *fails on unknown properties*. So once the server added `EventResponse.status`, every module call returning events (`getEvents`, `getEvent`, `searchEvents`, `createEvent`, `updateEvent`) threw `RestClientException`, confirmed against the live app. It now sets:
- `FAIL_ON_UNKNOWN_PROPERTIES=false`, so new server fields are ignored;
- `READ_UNKNOWN_ENUM_VALUES_AS_NULL=true`, so a new server enum value reads as `null` rather than failing (`PAYING` was itself a later addition);
- `WRITE_DATES_AS_TIMESTAMPS=false`, so request dates go out as ISO strings rather than `[2026,6,1,19,0]` arrays (the server accepted both).

The module's DTOs still mirror the server's fields, so new server fields should be added to them, but forgetting no longer breaks callers.

**Tests:** `client/src/test/.../EventManagerClientTest` (7, run with `cd client && mvn test`; `spring-boot-starter-test` is a test-scoped dependency of the module) binds `MockRestServiceServer` to the *real* `defaultRestTemplate()`, so it covers the mapper settings as well as request shapes. `getEvents_readsStatusAndIgnoresFieldsTheClientDoesNotKnow` is the regression test for the failure above (mutation-checked: removing the `FAIL_ON_UNKNOWN_PROPERTIES` line fails it with the same error). The in-app copy is covered separately by `src/test/.../client/EventManagerClientTest` (4).
