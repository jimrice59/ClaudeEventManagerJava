# Redis Reservation Holds

**Status:** Proposal — discussion only, no code changes
**Scope:** `TicketService.reserveTicket` / `releaseTicket`
**Store:** Redis (existing instance, new keyspace)
**Date:** 2026-09-15

A design memo on moving the `AVAILABLE → RESERVED` transition out of row-level Postgres updates
and into a short-lived, TTL-backed key in Redis — so a ticket rush contends for one atomic
operation instead of racing on individual rows.

## §00 Why this, why now

Today, `reserveTicket(id)` is one conditional `UPDATE` against one specific ticket row. That's
fine at low volume, but the client-facing flow is browse-then-pick: a user pages through
`GET /events/{id}/tickets/available`, then reserves a specific ID from what they saw. Under a
genuine rush — a popular event dropping at once — many clients converge on the same overlapping
page of IDs and race Postgres row locks for them. Most requests lose that race, come back as a
400, and the client retries, which only adds more load to the exact table under contention.

The fix isn't a bigger database — it's moving the contended step to a store built for single-key
atomic operations under load, and only touching Postgres once a hold has already been won.

## §01 The available pool

Each event gets a Redis `SET` — `available:event:{eventId}` — holding the ID of every ticket
still up for grabs. It's seeded once, at the same moment
`TicketService.createAvailableTickets` writes the AVAILABLE rows to Postgres, so the two stores
start in agreement.

> **Term:** box offices have long called a temporary, unpaid reservation a **hold** — distinct
> from a firm **sale**. That vocabulary maps directly here: a Redis key is a hold, a Postgres row
> flipped to `SOLD` is a sale. Nothing in between is durable.

## §02 Claiming a hold

A single Lua script — the same primitive the sliding-window rate limiter already uses via
`RedisScript` + `EVAL` — pops one ID out of the pool and stamps a hold key with a TTL,
atomically. Redis runs the whole script single-threaded, so there's no window for two clients to
pop the same ID.

```lua
-- KEYS[1] = available:event:{eventId}
-- ARGV[1] = hold TTL in millis
-- ARGV[2] = caller's userId
local ticketId = redis.call('SPOP', KEYS[1])
if not ticketId then
    return nil  -- sold out
end
local holdKey = 'hold:ticket:' .. ticketId
redis.call('SET', holdKey, ARGV[2], 'PX', ARGV[1])
return ticketId
```

*(Representative script — the actual reserve path would resolve `userId` the same way
`currentUserId()` does today.)*

This returns instantly. `Ticket.status` in Postgres is untouched and still reads `AVAILABLE` —
the hold exists only in Redis until someone actually pays.

## §03 Letting go of a hold

Three ways a hold ends, and only one of them needs new plumbing:

| Path | What happens |
|---|---|
| `releaseTicket` | Delete the hold key, `SADD` the ID back into the pool. Same shape as today's endpoint, one extra Redis round trip. |
| `purchaseTicket` | Delete the hold key permanently — see §04. The ID never returns to the pool. |
| TTL lapses, untouched | Redis expires the key on its own. Getting the ID *back into the pool* on that silent expiry is the one genuinely new piece — Redis doesn't run a script when a key dies, so it needs a listener on keyspace notifications (`notify-keyspace-events Ex`) matching `hold:ticket:*`, or failing that, a periodic sweep. |

## §04 The one Postgres write

`purchaseTicket` checks the hold key still exists and its stored `userId` matches the caller,
then does exactly what it does today: the real `UPDATE ... SET status = 'SOLD'`, plus the
`PURCHASE` `TicketOperation` audit row. Only after that commits does it delete the Redis hold key
for good. This is the one point in the whole flow that still touches Postgres — and it happens at
purchase volume, not reserve-and-abandon volume, so the table that used to see every browsing
race now only sees confirmed sales.

## §05 Before / after

| Step | Today | Proposed |
|---|---|---|
| Reserve | Conditional `UPDATE` on one row; N clients lock-race the same rows under a rush. | `SPOP` + `SET` in one Lua script; O(1), no row contention. |
| Release | One `UPDATE`, immediate. | Explicit `SADD` back to the pool, *or* nothing — the TTL does it for you. |
| Read consistency | `GET /tickets/{id}` always reflects the true state. | Postgres still says `AVAILABLE` mid-hold — the read path needs to check Redis too, or accept the gap. |
| Redis outage | Irrelevant — reservation never touches Redis. | Must fail **closed**. Unlike the rate limiter's deliberate fail-open, letting reservation succeed with no pool to check risks overselling. |

## §06 Open risks

- **Two sources of truth.** A crash between the Redis `SET` and the eventual Postgres write
  leaves the pool and the ledger disagreeing until something reconciles them — this needs a real
  answer before it ships, not just a TTL and a shrug.
- **Fail-closed is a new failure mode.** Every other Redis dependency in this app (caching, the
  rate limiter) is designed to degrade gracefully. Reservation can't — a Redis outage has to
  block new holds rather than risk selling the same seat twice.
- **Stale reads.** `GET /api/v1/tickets/{id}` reads Postgres only. Either it learns to check the
  Redis hold too, or callers accept that a held-but-unpaid ticket still reports `AVAILABLE`.
- **Expiry needs a listener.** Keyspace notifications add an always-on background consumer that
  doesn't exist in this app today — its own crash-recovery story (a missed expiry event orphans a
  ticket) needs designing, not assuming.

## §07 A more robust reclaim: the pending-holds index

Pub/Sub keyspace notifications (§03) are fire-and-forget — at-most-once delivery, no
persistence, no replay. If the listener is disconnected or mid-restart when a hold's TTL lapses,
Redis deletes the key silently and no trace remains to reconcile from later. For something as
consequential as "does this ticket become available again," that's not enough on its own.

A more durable complement: alongside each hold key, add its ticket ID to a ZSET —
`holds:pending` — scored by the hold's expiry timestamp (epoch millis), set at the moment it's
claimed in §02's Lua script. A small periodic job then polls that index directly:

```
ZRANGEBYSCORE holds:pending -inf <now>
```

Anything returned has an expiry timestamp in the past — whether or not a Pub/Sub notification
about it ever fired. For each ID found, the job runs a small Lua script that atomically checks
the hold key is really gone (i.e. it wasn't purchased or explicitly released in the meantime),
removes the entry from `holds:pending`, and `SADD`s the ticket back into the event's available
pool. Using Pub/Sub as the fast path and this sweep as the guaranteed backstop means nothing gets
permanently stranded even if a notification is lost.

## §08 Where the sweep runs

The sweep doesn't need to be a separate service. It can be a scheduled job inside the
application itself — reusing the app's existing Redis connection, logging, and metrics — as
long as the reclaim step is written so that concurrent execution across replicas is safe: a Lua
script that conditionally `ZREM`s a specific member and only `SADD`s it back if that `ZREM`
actually removed something. Under that scheme, N application instances all running the same
sweep on their own schedules simply means N-1 of them see a no-op for any given entry, not a
correctness problem — just some redundant, cheap range queries.

Redis Cluster doesn't change this: slot routing for `holds:pending` (or a per-event key,
`holds:pending:{eventId}`) is a client-side concern that a cluster-aware client already handles
transparently, whether the caller is in-process or an external process. The only reason to pull
the sweep out into a standalone job is if the redundant per-replica scanning becomes measurable
load at some scale — at which point a lightweight Redis-native mutex
(`SET sweep:lock NX PX <ttl>`) is a smaller change than standing up a new deployable.

---

No implementation yet — this captures the design discussion for review before any code is written.
