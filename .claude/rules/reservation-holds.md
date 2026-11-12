---
paths:
  - "src/main/java/com/eventmanager/service/TicketHoldService.java"
  - "src/main/java/com/eventmanager/service/TicketService.java"
  - "src/main/java/com/eventmanager/service/TicketReservationProperties.java"
  - "src/main/java/com/eventmanager/repository/TicketRepository.java"
  - "src/test/java/com/eventmanager/service/TicketServiceTest.java"
  - "src/test/java/com/eventmanager/service/TicketHoldServiceTest.java"
---

# Reservation Holds (Redis)

A reservation is a **hold**: a Redis key that expires on its own after
`ticket-reservation.hold-ttl` (`TICKET_RESERVATION_HOLD_TTL`, default `10m`). Implemented
2026-11-12 from docs/redis-reservation-holds.md, with the differences recorded at the top of that doc.

**The model:** a held ticket stays `AVAILABLE` in Postgres, with no `userId`. "Available to reserve"
means `AVAILABLE` in Postgres **and** not held in Redis. `RESERVED` is never stored; it's reported
by reads while a hold exists. So an expired hold needs no cleanup anywhere: the ticket simply stops
being excluded. There is no keyspace-notification listener, no sweep job, and no separate pool of
available ids to keep in sync with Postgres. Only a purchase writes Postgres.

**Keys** (`TicketHoldService`):

| Key | Type | Contents |
|---|---|---|
| `hold:ticket:{ticketId}` | string, `PX` TTL | `"<userId>:<expiresAtMillis>"`. Its existence is the hold |
| `holds:event:{eventId}` | sorted set | held ticket ids scored by expiry, to exclude them from Postgres queries |
| `holds:user:{userId}` | sorted set | held ticket ids scored by expiry, for My Tickets |

Every multi-key change is a Lua script, so it's atomic. `HOLD_SCRIPT` does `SET NX PX`, then `ZADD`
to both indexes, using Redis's own clock (`TIME`) for the expiry, so the hold's TTL and its index
score always agree. `RELEASE_SCRIPT` deletes only if the stored user matches. `LIVE_MEMBERS_SCRIPT`
prunes index members past their expiry (`ZREMRANGEBYSCORE -inf now`) before returning the rest. Each
index also gets `PEXPIRE` = hold TTL + 60s on every hold, so an idle index disappears on its own.

**Operations** (`TicketService`):
- `reserveTicket(id)`: 404/400 if the ticket is missing or not `AVAILABLE`; `tryHold` (`SET NX`)
  decides, else 400 "current status is RESERVED". Then it re-reads the committed status
  (`findStatusById`); if a purchase just moved it to `PAYING`, it gives the hold back.
- `reserveAvailableTicket(eventId)`: takes the 20 lowest-id tickets that are `AVAILABLE` and not
  held (`findAvailableExcluding`), and tries `tryHold` on each in turn. If no such ticket exists → 409
  immediately. If all were grabbed concurrently → back off and retry (`ticket-reservation.max-retries`).
- `releaseTicket(id)`: `RELEASE_SCRIPT`. Not held / expired → 400; held by someone else → 403.
- `purchaseTicket`: in `beginPayment` (row-locked), the ticket must be `AVAILABLE` and the caller
  must hold it (no hold → 400 "it isn't reserved…", another user's → 403). It moves to `PAYING`
  with `userId` = caller, and **after** that commits the hold is deleted, so nobody can grab the
  ticket in between. A declined payment returns it to `AVAILABLE` with no hold.
- Reads: `getTicketById` checks Redis **first** (`getHold`), then Postgres, and reports `RESERVED` +
  holder + `holdExpiresAt` while held. It is no longer cached: the old `"tickets"` cache was removed,
  since a cached "reserved" answer would outlive the hold. The available list and count use
  `findAvailableExcluding`/`countAvailableExcluding` with the event's held ids. `getMyTickets` uses
  `findOwnedOrHeld` (`userId = caller OR id IN caller's held ids`) and overlays holds via one `MGET`.
  The `IN`/`NOT IN` lists use a `{-1}` sentinel when empty.

**Failure mode — fail closed:** every Redis call goes through `TicketHoldService.redis(...)`, which
turns any `DataAccessException` into `ReservationsUnavailableException` (503 REST,
`UNAVAILABLE` gRPC). It's a dedicated exception rather than a global mapping of Spring's Redis
exceptions, because e.g. `QueryTimeoutException` also comes from Postgres. Note that an outage
during an open connection surfaces as a *command timeout*, not a connection failure.
`spring.data.redis.timeout`/`connect-timeout` are set to `2s` (`REDIS_TIMEOUT`,
`REDIS_CONNECT_TIMEOUT`); Lettuce's 60s default stalled every request for a minute during an outage.

**Known gap:** if a hold expires during the milliseconds of a purchase's first transaction, another
user could win a new hold on a ticket that's about to become `PAYING`; their later purchase gets a
409/400. The re-read in `reserveTicket` narrows this, but doesn't close it.

**Verified live** (Postgres + Redis, 20s TTL):
- a reserve leaves the Postgres row unchanged;
- `GET /tickets/{id}` reports `RESERVED` with the holder and expiry;
- the ticket is excluded from the available list and count;
- other users get 400 (reserve) and 403 (release);
- after 20s, with no other activity, the ticket was available again;
- 5 concurrent reserve-next calls on a 3-ticket event → 3 distinct tickets, 2× 409;
- reserve → purchase → `SOLD` with the hold consumed;
- Redis stopped → 503 in ~4s (the rate limiter, which fails open, and then the hold check, each wait out the 2s timeout).

**Tests:** `TicketServiceTest` mocks `TicketHoldService`. `TicketHoldServiceTest` covers value
parsing and key names. The Lua scripts need a real Redis (the test profile has none), so they're
covered only by the live checks above.
