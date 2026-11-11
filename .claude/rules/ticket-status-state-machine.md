---
paths:
  - "src/main/java/com/eventmanager/service/TicketService.java"
  - "src/main/java/com/eventmanager/controller/TicketController.java"
  - "src/main/java/com/eventmanager/web/WebTicketController.java"
  - "src/main/java/com/eventmanager/model/Ticket.java"
  - "src/main/java/com/eventmanager/model/TicketStatus.java"
  - "src/test/java/com/eventmanager/service/TicketServiceTest.java"
---

# Ticket Status State Machine

`TicketStatus` has four values (`AVAILABLE`, `RESERVED`, `PAYING`, `SOLD` — still no `CANCELLED`)
and five transitions. `reserve`/`release`/`sell`/`cancel` are the original four private validation
helpers; `purchaseTicket` additionally calls out to the payment service via gRPC between its two
transitions — see payment-service-grpc.md and docs/payment-service-integration.md for the full
design (this file covers the state machine itself).

```
        reserveTicket(id)              purchaseTicket(id, token, reqId)   [payment service call]
   ┌─────────────────────►┌──────────►┌──────────────►┌─── APPROVED ────►┌──────
   │   sets userId=caller   RESERVED    userId must      PAYING             SOLD
   │                                    match ticket's        │
AVAILABLE◄─────────────────┘ releaseTicket(id)                │ DECLINED
   userId=null                clears userId                   ▼
        ▲                                                 AVAILABLE (userId cleared)
        └───────────────────────── cancelTicket(id) ──────────┘ (from SOLD)
                                    clears userId
```

| Transition | Method | Precondition (else 400, or 409 for PAYING) | Ownership check (else 403) | Result |
|---|---|---|---|---|
| → RESERVED | `reserveTicket(id)` | status is `AVAILABLE` | none — anyone can reserve an open ticket | `userId` = caller, status = `RESERVED` |
| → RESERVED | `reserveAvailableTicket(eventId)` — same transition, ticket chosen by the server | event exists (else 404); some ticket for it is `AVAILABLE` and not row-locked by a concurrent call (else **409**, `NoTicketsAvailableException`) | none | same as `reserveTicket` on the lowest-id such ticket |
| RESERVED → AVAILABLE | `releaseTicket(id)` | status is `RESERVED` | caller's id equals ticket's `userId` | `userId` = `null`, status = `AVAILABLE` |
| RESERVED → PAYING | `purchaseTicket(id, paymentToken, paymentRequestId)` — Txn 1 (`beginPayment`) | status is `RESERVED`, **else 409 if already `PAYING`** (a concurrent attempt is in flight — see below) | caller's id equals ticket's `userId` | status = `PAYING`; `paymentRequestId` stamped (caller-supplied or generated) |
| PAYING → SOLD / AVAILABLE | same call, Txn 2 (`completePayment`), after the gRPC call returns | n/a (status is already `PAYING` from Txn 1) | n/a | APPROVED → `SOLD`, `datePurchased` stamped to now, records `PURCHASE`; DECLINED → `AVAILABLE` + `userId=null` (`datePurchased` untouched), records `PAYMENT_DECLINED`; call failure (timeout/connection error) → **ticket stays `PAYING`**, no operation recorded, throws `PaymentServiceUnavailableException` (502) |
| SOLD → AVAILABLE | `cancelTicket(id)` | status is `SOLD` | caller's id equals ticket's `userId` | `userId` = `null`, status = `AVAILABLE`; records a `CANCEL` `TicketOperation` |

- **The PAYING→409 check is distinct from the generic "not RESERVED" 400**: `beginPayment` checks
  `status == PAYING` first and throws `TicketPaymentInProgressException` (409 — a conflict with an
  attempt already in flight) before falling through to `sell()`'s ordinary "not RESERVED" check
  (400 — a precondition failure), so a double-submitted purchase request never starts a second
  concurrent payment-service call for the same ticket. That guarantee depends on `beginPayment`
  reading the ticket under the row lock described below; the unlocked check alone didn't hold up
  under concurrency.
- **`purchaseTicket` is a saga, not one transaction** — `beginPayment` (RESERVED→PAYING) and
  `completePayment` (PAYING→SOLD/AVAILABLE) are each their own short transaction via
  `TransactionTemplate`, with the (up to 120-second) gRPC call to the payment service running with
  no transaction open at all in between. See io-and-transactions.md and
  docs/payment-service-integration.md, "Why two transactions instead of one".
- **No `CANCELLED` status** — cancelling a `SOLD` ticket returns it all the way to `AVAILABLE` (the
  same end state `releaseTicket` produces), not a terminal dead state, so a cancelled ticket can be
  reserved and sold again.
- **`userId` is the ownership token**: it's set on `reserveTicket` and cleared on both paths back
  to `AVAILABLE` (`releaseTicket`, `cancelTicket`, and a `DECLINED` purchase outcome), so every
  `AVAILABLE` ticket has `userId = null` as an invariant. It is never accepted from the client —
  see data-model.md for how `currentUserId()` resolves it from the JWT.
- **`paymentRequestId` is not an ownership token and is never cleared** — once a ticket has gone
  through `beginPayment` at least once, its most recent `paymentRequestId` stays on the row
  (visible in `TicketResponse`) regardless of how that attempt resolved, for support/reconciliation
  lookups. It has no bearing on which transitions are valid.
- **`datePurchased` is stamped only on an APPROVED outcome** (the RESERVED/PAYING→`SOLD` edge) and,
  like `paymentRequestId`, is never cleared afterward — including by `cancelTicket`, which returns
  the ticket to `AVAILABLE` but leaves `datePurchased` as a record of the most recent sale. A ticket
  that has never been sold has `datePurchased = null`.
- **`purchaseTicket`/`cancelTicket` write an audit row** (`TicketOperation` — `PURCHASE`,
  `PAYMENT_DECLINED`, or `CANCEL`) — the only durable record that a ticket was ever sold, declined,
  or cancelled, since the ticket row itself loses most of that history once `userId` is cleared.
  `reserveTicket`/`releaseTicket` don't log operations, and neither does a purchase attempt whose
  gRPC call itself fails (nothing was resolved to record).
- **Initial state**: tickets are created `AVAILABLE` with `userId = null` as a side effect of
  `POST /api/v1/events` (`TicketService.createAvailableTickets`, one per unit of `ticketsTotal`) —
  there is no direct "create ticket" entry point.
- **`reserveAvailableTicket` is a second entry point to the →RESERVED transition, not a sixth
  transition**: it runs the same private `reserve()` helper, just on a ticket it selects itself via
  `TicketRepository.lockNextAvailableTicket` (native `SELECT ... ORDER BY id LIMIT 1 FOR UPDATE
  SKIP LOCKED`). The lock is what makes it safe under contention: without it, concurrent callers
  would all read the same lowest-id row and both "reserve" it. With `SKIP LOCKED`, each skips rows
  other in-flight calls hold, so N simultaneous requests reserve N different tickets (verified
  against Postgres: 5 concurrent calls on an event with 2 AVAILABLE tickets → 2× 200 with distinct
  ids, 3× 409). It's `@CachePut(key = "#result.id")`, since the ticket id is only known from the result.
- **`reserveAvailableTicket` retries only one case.** An attempt can't lose its ticket to another
  client, because it holds the row lock until commit, so there's nothing to retry there. It *can*
  find nothing to lock while the event isn't sold out: every remaining AVAILABLE ticket may be
  row-locked by in-flight transactions, and if one rolls back, its ticket is still AVAILABLE. So
  when an attempt comes back empty, it checks `countByEventIdAndStatus(eventId, AVAILABLE)`:
  - Zero means truly sold out → 409 immediately, no retry.
  - Non-zero → back off `RESERVE_RETRY_BACKOFF_MS` (50ms) × attempt number and retry, up to
    `ticket-reservation.max-retries` (`TicketReservationProperties`,
    `TICKET_RESERVATION_MAX_RETRIES`, default 3, `@Min(0)`, 0 disables).

  Each attempt is its own `TransactionTemplate` transaction, so no connection is held during
  backoff; the method itself is therefore not `@Transactional`. Clients see no change (same
  endpoint, responses, and 404/409), only up to 300ms extra latency at the default in the
  contended case. Verified against Postgres by holding the event's AVAILABLE rows `FOR UPDATE`
  from psql:
  - locks released mid-request → 200 on attempt 3 (would have been a 409)
  - locks held throughout → 3 retries then 409 in ~0.4s
  - truly sold out → 409 in ~0.02s, no retry
- **Every status transition row-locks the ticket**, via `TicketService.lockTicketOrThrow` →
  `TicketRepository.findByIdForUpdate`. That covers `reserveTicket`, `releaseTicket`,
  `cancelTicket`, and both purchase-saga steps (`beginPayment`, `completePayment`).
  `reserveAvailableTicket` uses its own `SKIP LOCKED` query, and only read-only
  `getTicketById` stays unlocked. The lock is held only within each short transaction, never
  across the payment-service call between the two saga steps. Without it, `beginPayment`'s
  `PAYING`→409 guard was itself racy. Reproduced against Postgres: 5 concurrent purchases of one
  RESERVED ticket → **3× 200 and 3 `PURCHASE` operations** (a triple charge against a real payment
  service), 2× 409. Now: 1× 200, 4× 409, one `PURCHASE`. Two concurrent cancels of a SOLD ticket
  → one 200, one 400.
- **History — `reserveTicket(id)` was the first to get the lock**,
  (`@Lock(PESSIMISTIC_WRITE)` → `SELECT ... FOR UPDATE`). Before this, it read the ticket
  unlocked, and `Ticket` has no `@Version`, so concurrent reserves of the *same* id all read
  `AVAILABLE` and all returned 200 while the last write silently won. Reproduced against
  Postgres: 6 concurrent reserves of one ticket by two users → 6× 200, but only one owner in the
  row. Now: 1× 200, 5× 400 ("current status is RESERVED"). A pessimistic lock rather than
  `@Version`, because there's no migration tool and `ddl-auto: update` would add a `version`
  column that's NULL on every existing row. `findByIdForUpdate` deliberately has no `JOIN FETCH`,
  so the lock covers only the ticket row, and reserves of different tickets for the same event
  don't serialize (verified: two concurrent → both 200 in ~23ms).
- All five transitions require authentication but not `ROLE_ADMIN` — ownership (`userId` match),
  not role, is what gates `release`/`purchase`/`cancel`; see authorization-model.md.
- **No live "available" counter is stored anywhere** — `Event.ticketsTotal` is a fixed capacity set
  at creation (immutable; no setter), not a count these transitions decrement/increment. The number
  of tickets currently `AVAILABLE` is computed on demand:
  `TicketService.getNumAvailableTickets(eventId)` runs
  `TicketRepository.countByEventIdAndStatus(eventId, AVAILABLE)` against Postgres directly,
  `EventService.getNumAvailableTickets(id)` delegates to it, and
  `GET /api/v1/events/{id}/tickets/available/count` (public) exposes it over the API. The result is
  cached in Redis for 5 seconds (see redis-caching.md) — short enough that it's still effectively
  live for this kind of read, but not evicted by any of these transitions, so a read can be up to 5
  seconds stale. A ticket moving through `PAYING` is not `AVAILABLE`, so it's correctly excluded
  from this count for the whole duration of a purchase attempt, not just once it reaches `SOLD`.

**`TicketServiceTest`** covers everything the table above describes, plus the pre-existing
`getTicketById`/`getMyTickets`/`getNumAvailableTickets`/`getAvailableTickets`/`reserveTicket`/
`releaseTicket`/`createAvailableTickets` coverage unchanged from before this feature.
`backupAndDeleteAllForEvent` is covered by `backupAndDeleteAllForEvent_backsUpOnlySoldTicketsThenDeletesAllFromPostgres`
(a mixed-status fixture — one `AVAILABLE`, one `SOLD` with a stamped `datePurchased` — asserting only
the `SOLD` ticket reaches `ticketCassandraRepository.saveAll(...)`, with `datePurchased` carried onto
the `CassandraTicket`, while `ticketRepository.deleteAll(...)` still receives both),
`backupAndDeleteAllForEvent_skipsCassandraEntirelyWhenNoTicketsAreSold` (an all-`AVAILABLE` fixture →
`verifyNoInteractions(ticketCassandraRepository)`, but Postgres deletion still happens),
`backupAndDeleteAllForEvent_skipsCassandraWhenRepositoryAbsent` (repository left `null`, as in the
test profile), and `backupAndDeleteAllForEvent_doesNothingToTicketsWhenEventHasNoneButStillDeletesOperations`.
For `purchaseTicket`: `purchaseTicket_succeedsWhenReservedBySameUserAndPaymentApproved`
(mocks `PaymentServiceClient.purchaseTicket(...)` to return an `APPROVED` `PaymentOutcome`; asserts
two `ticketRepository.save` calls — one from each saga transaction — that `datePurchased` is stamped
on the result, and that the recorded `TicketOperation` carries the real confirmation id and
idempotency token), `purchaseTicket_usesClientSuppliedPaymentRequestId`,
`purchaseTicket_movesBackToAvailableWhenPaymentDeclined` (`DECLINED` outcome → `AVAILABLE`, records
`PAYMENT_DECLINED`), `purchaseTicket_throwsPaymentServiceUnavailableAndLeavesTicketPayingWhenCallFails`
(`ERROR` outcome → only one `save` call total, ticket stays `PAYING`, no `TicketOperation`
recorded), `purchaseTicket_throws409WhenAlreadyPaying` (ticket pre-seeded as `PAYING` →
`TicketPaymentInProgressException`, no repository/payment-client interaction at all), and the
pre-existing `purchaseTicket_throwsWhenNotCurrentlyReserved`/`throwsAccessDeniedWhenReservedByDifferentUser`/
`throwsWhenNotFound` cases, updated only for the new 3-arg `purchaseTicket(id, paymentToken,
paymentRequestId)` signature. `PaymentServiceClient`, `PaymentCacheService`, `PlatformTransactionManager`,
and `CacheManager` are added as plain Mockito `@Mock`s to the constructor
(`new TicketService(ticketRepository, eventRepository, userRepository, ticketOperationRepository,
cassandraAsyncWriter, paymentServiceClient, paymentCacheService, transactionManager,
cacheManager, reservationProperties)`, where `reservationProperties` is a real
`TicketReservationProperties` rather than a mock, so retry tests can call `setMaxRetries(...)`) — none need stubbing for the saga's transactional plumbing to work: an unstubbed
`PlatformTransactionManager` mock returns `null` from `getTransaction`, which `TransactionTemplate`
just passes through to `beginPayment`/`completePayment` and then to `commit`/`rollback`, none of
which dereference it — see docs/payment-service-integration.md. Two notable Mockito pitfalls
avoided here: (1) `beginPayment`/`completePayment` mutate and re-save the *same* mocked `Ticket`
instance across both saga transactions, so an `ArgumentCaptor` on `ticketRepository.save` can't
distinguish the two calls' intermediate states (both captured references alias the final mutated
object by the time assertions run) — only call count and the final returned `TicketResponse` are
asserted, not per-call captured state; (2) the `cancelTicket` test's existing assertions
(`paymentService == "Square"`, `paymentConfirmationDetails == "payment confirmation 1234"`) are
unaffected by this change, since `cancelTicket` still goes through the original 3-arg
`recordOperation(ticket, type, userId)` overload with the same hardcoded defaults — only the
purchase path's `recordOperation(ticket, type, userId, paymentRequestId, outcome)` overload uses
real payment data.
