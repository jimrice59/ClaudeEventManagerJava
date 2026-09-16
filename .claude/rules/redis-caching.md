---
paths:
  - "src/main/java/com/eventmanager/service/**"
  - "src/main/java/com/eventmanager/config/RedisConfig.java"
---

# Redis Caching

All four services cache individual records by ID with a 1-hour TTL:

| Cache name | Service | Cacheable | CachePut | CacheEvict |
|---|---|---|---|---|
| `"events"` | `EventService` | `getEventById` | `createEvent`, `updateEvent` | `deleteEvent` |
| `"venues"` | `VenueService` | `getVenueById` | `createVenue`, `updateVenue` | `deleteVenue` |
| `"performers"` | `PerformerService` | `getPerformerById` | `createPerformer`, `updatePerformer`, `addVideo`, `deleteVideo` | `deletePerformer` |
| `"tickets"` | `TicketService` | `getTicketById` | `reserveTicket`, `releaseTicket`, `purchaseTicket`, `cancelTicket` | — (see note) |

`TicketService.getNumAvailableTickets(eventId)` is cached separately in a `"availableTicketCounts"` cache with its own short **5-second TTL** (configured as a per-cache override in `RedisConfig`, distinct from the 1-hour default above), keyed by `#eventId`. Unlike the four caches above, nothing `@CacheEvict`s or `@CachePut`s it on `reserveTicket`/`purchaseTicket`/`releaseTicket`/`cancelTicket` — the short TTL is the only staleness control, a deliberate trade-off: it absorbs read bursts against a value that changes on nearly every ticket write, at the cost of the count being up to 5 seconds stale.

Five list/search endpoints are also cached, `@Cacheable` only (no `@CachePut`/`@CacheEvict` — list invalidation on write isn't implemented for these either, so each relies entirely on its TTL to bound staleness):

| Cache name | Service method | Key | TTL |
|---|---|---|---|
| `"allEvents"` | `EventService.getAllEvents()` | none (no-arg method — one entry for the whole list) | 30 seconds |
| `"eventsByVenue"` | `EventService.getEventsByVenue(venueId)` | `#venueId` | 30 minutes |
| `"venuesByCity"` | `VenueService.getVenuesByCity(city)` | `#city` | 30 minutes |
| `"performersByName"` | `PerformerService.searchPerformers(name)` | `#name` | 30 minutes |
| `"performersByGenre"` | `PerformerService.getPerformersByGenre(genre)` | `#genre` | 30 minutes |

`getAllEvents` gets a much shorter TTL than the other four because it has no filter — any event create/update/delete invalidates it, so a long TTL would mean stale results after almost any write; the filtered `venuesByCity`/`performersByName`/`performersByGenre`/`eventsByVenue` queries are hit less often and change less often relative to reads, so a 30-minute TTL was judged an acceptable staleness/hit-rate trade-off. `getEventsBetween` and `getAvailableTickets` remain uncached — an unbounded range of possible `start`/`end` or `page`/`size` argument combinations would fragment the cache into many near-unique, rarely-reused entries. `createAvailableTickets` (bulk-creates a whole event's worth of tickets) has no per-id cache entries to populate, so it isn't annotated. `backupAndDeleteAllForEvent` uses `@CacheEvict(value = "tickets", allEntries = true)` instead of the usual `key = "#id"` pattern, since it removes an unbounded, dynamically-sized set of ticket ids in one call — it does not evict `"availableTicketCounts"` either, again relying on the 5-second TTL. `TicketOperation` has no cache of its own — there's no `getById`-style read path for it, only writes (see cassandra-dual-write.md).

Cache is configured in `RedisConfig` with JSON serialization (`GenericJackson2JsonRedisSerializer`) and a 1-hour TTL by default. Every cache with a non-default TTL (`"availableTicketCounts"` at 5 seconds; `"allEvents"` at 30 seconds; `"eventsByVenue"`, `"venuesByCity"`, `"performersByName"`, `"performersByGenre"` at 30 minutes) is built from that same default config via `.entryTtl(Duration)` and registered individually with `.withCacheConfiguration(name, config)` on the `RedisCacheManager` builder — `cacheDefaults(...)` only applies to caches with no such override (`"events"`, `"venues"`, `"performers"`, `"tickets"`, at 1 hour). `EventResponse`, `VenueDto`, `PerformerDto`, and `TicketResponse` all implement `Serializable` for this reason (the cached `Long` from `getNumAvailableTickets` and the cached `List<...>` results from the list endpoints need no such treatment — `List` and the DTOs it holds are already covered).

**`GenericJackson2JsonRedisSerializer`'s own no-arg `ObjectMapper` cannot serialize `java.time.LocalDateTime`** — it has no `JavaTimeModule` registered, unlike the app-wide `ObjectMapper` Spring Boot auto-configures for the REST API (which gets JSR-310 support automatically via `spring-boot-starter-json`). Every DTO with a `LocalDateTime` field (`EventResponse.eventDate`/`createdAt`/`updatedAt`, `TicketResponse.eventDate`) would throw `SerializationException` on first cache write, surfacing as a 500 on `getEventById`/`getAllEvents`/etc. — and since caching is disabled in the test profile (`spring.cache.type: none`), this class of bug is invisible to `mvn test` and only reproduces against a real Redis. `RedisConfig.redisObjectMapper()` fixes this by building its own `ObjectMapper` that replicates what the no-arg serializer does internally (all-field visibility via `setVisibility(PropertyAccessor.ALL, ANY)`, plus `activateDefaultTyping(..., NON_FINAL, PROPERTY)` so cached JSON carries `@class` metadata and deserializes back to the right concrete DTO) and additionally registers `JavaTimeModule`, then passes that mapper to `new GenericJackson2JsonRedisSerializer(objectMapper)`.
