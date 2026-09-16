# Patterns & Lessons

Four generalizable engineering patterns pulled out of CLAUDE.md's feature-specific documentation.
CLAUDE.md describes *what this app does*; this doc captures *what's reusable elsewhere* —
each entry is written to survive independent of whether the feature it came from is kept,
rewritten, or removed.

## 1. Gating a metric means gating every meter that covers it, not just the obvious one

**Pattern:** a single user-facing "metric" can be backed by more than one underlying instrument.
Micrometer's `http.server.requests` (a completed-request `Timer`) and
`http.server.requests.active` (a separate `LongTaskTimer` tracking in-flight concurrency) are two
independent meters. Denying the first via a `MeterFilter` does nothing to the second — it's a
different meter with a different name, not a facet of the same one.

**Why it matters:** it's easy to assume "one metric concept = one meter" and verify only the name
you already had in mind. A hand-built `Meter.Id` in a unit test will happily confirm you gated
what you asked it to gate — it can't tell you about the sibling meter you didn't know existed.
This one only surfaced by grepping real `/actuator/prometheus` output line by line.

**Apply elsewhere:** after adding any allow/deny filter over auto-instrumented telemetry
(Micrometer, OpenTelemetry auto-instrumentation, structured logging frameworks with their own
"enable this category" toggles), verify against the actual exporter output for the whole family
prefix, not just the one meter/field name you set out to gate. Auto-instrumentation frameworks
routinely register more under a feature than its name suggests.

## 2. Sliding-window rate limiting beats fixed-window, and the reason generalizes

**Pattern:** a fixed-window counter (`INCR` + `EXPIRE` on a clock-aligned key) lets a client burst
up to 2x the nominal limit by timing requests around the reset boundary. A sliding-window log
(a Redis ZSET scored by request timestamp, pruned with `ZREMRANGEBYSCORE` before counting) evaluates
a true rolling window instead, closing that gap.

**Why it matters:** the fixed-window version is the version most people reach for first, and it
looks correct under the testing anyone naturally does — one client, a steady request rate. It only
fails under the adversarial case a rate limiter exists to stop: a client deliberately clustering
requests around the window edge.

**Two corollaries worth keeping too:**
- The whole check — prune, count, conditionally record — has to be one atomic operation (a single
  Lua `EVAL`), or concurrent requests on the same key race between the count and the write.
- Fail-open vs. fail-closed is a deliberate call, not a default, and the *same* failure (the
  backing store becomes unreachable) can demand opposite answers depending on what's actually at
  stake: a rate limiter should fail open (an outage in the limiter shouldn't cause an outage in
  the API it's protecting), while an inventory/reservation check over the same kind of store
  should fail closed (skipping the check risks overselling, which is worse than temporarily
  refusing new holds).

## 3. Never accept an ownership field from the client — derive it from the authenticated principal

**Pattern:** every ownership-gated transition here resolves the caller's identity by reading the
authenticated principal off the security context and looking up the corresponding user row — never
by trusting an id in the request body. There is no field on any request DTO where a client could
supply "this is my user id."

**Why it matters:** an ownership field on an incoming request is a standing invitation for IDOR
(insecure direct object reference) — nothing stops a client from putting someone else's id in the
JSON unless every single handler remembers to check it. Deriving identity server-side from the
verified principal removes the entire vulnerability class up front, instead of relying on
discipline at each new endpoint to re-add the check correctly.

**Apply elsewhere:** for any endpoint that mutates a "this belongs to me" resource, there should be
no code path where the id used for the ownership check can flow from parsed request JSON — only
from the authenticated context. If you find yourself validating `request.userId == token.userId`,
that's the smell; the request shouldn't have a `userId` field to compare against in the first place.

## 4. Don't let an irreversible operation proceed on the strength of an unconfirmed async write

**Pattern:** this app's default is fire-and-forget async for secondary-store writes — exceptions
are caught, logged, and never propagated to the caller. One write deliberately breaks that
convention: archiving records to a secondary store immediately before permanently deleting them
from the primary store runs *synchronously*, and any exception there rolls back the whole delete.

**Why it matters:** fire-and-forget is the right default when a failed write just means a stale or
missing secondary copy — an acceptable, recoverable gap. It stops being acceptable the moment a
*destructive* step is waiting on that write having actually succeeded. Keeping the async default
there would mean data could be permanently deleted from the system of record while its only backup
silently failed to be written, with nothing left afterward to prove it.

**Apply elsewhere:** audit each async, exception-swallowing write in a system for whether anything
irreversible is gated on it succeeding. Where the answer is yes, that one path needs to be
synchronous (or otherwise positively confirmed) even when every sibling write in the same system is
intentionally fire-and-forget — consistency of style is not a reason to make a delete unsafe.
