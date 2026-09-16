---
paths:
  - "src/main/java/com/eventmanager/ratelimit/**"
  - "src/main/java/com/eventmanager/config/RateLimitConfig.java"
  - "src/test/java/com/eventmanager/ratelimit/**"
---

# Rate Limiting

`RateLimitFilter` (`com.eventmanager.ratelimit`) enforces a global, Redis-backed rolling-window rate limit
across the whole app — both `/api/v1/**` and `/ui/**` — keyed by client IP (`X-Forwarded-For` if present,
falling back to the raw socket address), regardless of authentication state. It is registered by
`RateLimitConfig` as a plain `FilterRegistrationBean` (not a `@Component` on the filter class itself, which
would cause Spring Boot to auto-register it a second time) with `Ordered.HIGHEST_PRECEDENCE`, so it runs before
every Spring Security filter chain — a rejected request never reaches authentication, session, or CSRF handling.
`/actuator/**` is exempted unconditionally so Prometheus scraping and the Kubernetes liveness/readiness probes
are never throttled.

**Algorithm — sliding window, not a fixed bucket:** `RateLimiter` records each accepted request as a member of a
Redis sorted set (ZSET) scored by its timestamp in epoch millis, under key `rate-limit:ip:<address>`. A single
Lua script (`EVAL`, so the prune → count → conditionally-add sequence is atomic and race-free across concurrent
requests on the same key) removes members older than the current window before counting what's left:

```lua
redis.call('ZREMRANGEBYSCORE', key, '-inf', now - window)
local count = redis.call('ZCARD', key)
if count < limit then
    redis.call('ZADD', key, now, member)
    redis.call('PEXPIRE', key, window)
    return 1
end
redis.call('PEXPIRE', key, window)
return 0
```

This is a true rolling window: a fixed-window counter (`INCR` + `EXPIRE` on a clock-aligned bucket) would let a
client burst up to 2x the limit by timing requests around the reset boundary, which is exactly the burst
behavior a rate limiter is meant to prevent. The key's TTL is (re)set to the window length on every call — "TTL
per time window" — so a key that goes idle for a full window expires and cleans itself up with no separate
eviction job.

**Configuration** (`RateLimitProperties`, bound from `rate-limit.*`): `enabled` (`RATE_LIMIT_ENABLED`, default
`true`), `window-duration` (`RATE_LIMIT_WINDOW_DURATION`, a Spring `Duration` in simple format like `1s`/`500ms`,
default `1s`), `max-requests` (`RATE_LIMIT_MAX_REQUESTS`, default `1000`) — i.e. 1000 requests per rolling
1-second window out of the box, generous enough to absorb legitimate bursts while still bounding runaway/abusive
traffic. Disabled entirely in the `test` profile (`application-test.yml`) since no Redis broker runs there and
`@AutoConfigureMockMvc` would otherwise route every `MockMvc` request through this filter.

**Failure mode:** `RateLimiter.isAllowed` catches any exception from the Redis call and fails open (returns
`true`), logging a `WARN`. A rate limiter's own datastore being unreachable should never itself take the whole
API down — that would turn a transient Redis blip into a hard outage for every client, which is a worse outcome
than temporarily not rate-limiting at all.

**Response on rejection:** `429 Too Many Requests` with a `Retry-After` header (the window length, in seconds)
and a JSON body matching `GlobalExceptionHandler.ErrorResponse`'s shape (`{"status":429,"message":"..."}`,
constructed by hand here since this filter runs ahead of Spring MVC/`GlobalExceptionHandler` entirely).

**`RateLimiterTest`** covers: `isAllowed` returns `true` when the Lua script returns `1` (under limit), `false`
when it returns `0` (limit exceeded), and `true` (fail-open) when `StringRedisTemplate.execute` throws — no real
Redis involved; the script's own return value is stubbed directly via a mocked `StringRedisTemplate`, so this
tests `RateLimiter`'s Java-side contract (result interpretation, fail-open behavior) rather than the Lua script's
correctness, which was instead verified live against a real Redis during development (a low
`max-requests`/`window-duration` override reproducibly triggered `429`s that recovered once the window rolled).
