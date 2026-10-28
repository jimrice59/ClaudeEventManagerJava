# Redis Rate-Limiting Algorithms

This app's `RateLimitFilter`/`RateLimiter` (see `.claude/rules/rate-limiting.md`) already implements
one of these — a **sliding window log** on a Redis ZSET. This doc surveys the other common
Redis-backed options, why the sliding window log was the right pick here, and when one of the
alternatives would be worth switching to.

## The options

| Algorithm | Redis structure | Behavior | Tradeoff |
|---|---|---|---|
| **Fixed window counter** | `INCR` + `EXPIRE` on a clock-aligned key (e.g. `rate:ip:<60s-epoch>`) | Simplest; O(1) per request | Allows ~2x burst at window boundaries — a client firing at 0:59 and again at 1:00 gets two full windows' worth of requests almost back-to-back |
| **Sliding window log** *(what this project uses)* | ZSET, one member per accepted request, scored by timestamp | Exact — no boundary-burst behavior | O(log n) per request, and memory scales with requests-per-window, since every accepted request is stored as a member until it ages out |
| **Sliding window counter** | Two fixed-window counters, weighted-averaged by how far into the current window the request falls | Approximates the sliding log cheaply — just two `INCR`s, no per-request storage | Approximate, not exact — small error near window boundaries in exchange for much lower memory/CPU cost than the log |
| **Token bucket** | A hash/string holding `tokens` + `last_refill_ts`, refilled lazily on each call via a Lua script | Allows controlled bursts up to the bucket size, then a steady refill rate afterward | Needs a Lua script to make the refill-then-check sequence atomic — the same reason this project's sliding window uses `EVAL` rather than separate round trips |
| **Leaky bucket** | Similar state shape to token bucket, modeled as a queue draining at a fixed rate | Smooths bursts into a constant output rate, rather than allowing a burst through | Rarely implemented directly in Redis for API rate limiting — more natural at a proxy/queueing layer that can actually delay/queue requests rather than just accept-or-reject them |
| **Redis `CL.THROTTLE`** (redis-cell module, GCRA) | Redis module command — no app-side Lua script at all | Token-bucket semantics in a single atomic command | Requires the `redis-cell` module loaded on the Redis server; not available on vanilla Redis or most managed Redis offerings — would need an infrastructure change, not just an app-side one, and isn't compatible with this project's plain `spring-boot-starter-data-redis` setup as-is |

## Why sliding window log fits this project

`RateLimiter.isAllowed` (see rate-limiting.md) needs to answer one question per request: "has this
IP made `max-requests` or more requests in the trailing `window-duration`?" — and needs to answer it
exactly, since the whole point of a rolling window (versus the cheaper fixed window) is closing the
boundary-burst gap. At this project's default limits (1000 requests per rolling 1-second window per
IP), the per-key ZSET never holds more than ~1000 members at once, so the "memory scales with
requests-per-window" cost of the log approach is negligible — the exactness is free at this scale.

The Lua script already in place (`ZREMRANGEBYSCORE` → `ZCARD` → conditional `ZADD` + `PEXPIRE`) is
also exactly the shape a token-bucket or leaky-bucket implementation would need anyway — Redis
doesn't offer compare-and-swap semantics for multi-step read-modify-write logic, so any of these
algorithms beyond the plain fixed-window counter requires a Lua `EVAL` to stay race-free across
concurrent requests on the same key. Choosing the log didn't cost extra scripting complexity
relative to the alternatives.

## When to reconsider

- **Sliding window counter** — worth it if `max-requests` grows large enough (e.g. rate-limiting by
  an authenticated user tier at 100k requests/window instead of by IP at 1000/window) that storing
  one ZSET member per request becomes real memory pressure. The error introduced near window
  boundaries is usually an acceptable trade for that.
- **Token bucket** — worth it if the desired behavior changes from "hard rolling cap" to "allow a
  burst, then settle into a steady rate" — e.g. letting a client fire off a quick batch of requests
  on page load, then throttling to a steady background rate, rather than rejecting the 1001st
  request in the same second a legitimate burst occurred.
- **`CL.THROTTLE`/redis-cell** — worth it only if the Redis deployment can load custom modules
  (ruled out for most managed Redis offerings) and the team wants to delete the Lua script entirely
  in favor of one atomic command — a maintenance simplification, not a behavioral one, since GCRA is
  itself a token-bucket variant.
- **Fixed window counter** — not a real contender here; it's cheaper than every other option but
  reintroduces exactly the boundary-burst problem the sliding window was built to close, so it would
  be a regression rather than a trade-up.
