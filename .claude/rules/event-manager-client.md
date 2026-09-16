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
| `purchaseTicket(id, userCredentials)` | POST | `/api/v1/tickets/{id}/purchase` | authenticated |
| `cancelTicket(id)` | POST | `/api/v1/tickets/{id}/cancel` | authenticated |

There is no `createTicket`/`deleteTicket` method — tickets have no external create/delete API; `createEvent`/`deleteEvent` create/remove them as a side effect. There is also no `updateTicketStatus` method — each valid transition has its own dedicated client method instead of a generic status setter. There is also no `reserveTickets(id, count)`/`releaseTickets(id, count)` method — those event-level, aggregate-counter operations were removed along with `Event.ticketsAvailable` itself; `getNumAvailableTickets(id)` is the read-only replacement, always computed live from Postgres rather than returning a stored value. List responses use `ParameterizedTypeReference` to preserve generic type information at runtime. `deleteEvent`, `deleteVenue`, and `deletePerformer` return `void` — a 204/200 with no body is a success. `getAvailableTickets` returns `PagedResponse<TicketResponse>` (also via `ParameterizedTypeReference`, since it's a generic wrapper).
