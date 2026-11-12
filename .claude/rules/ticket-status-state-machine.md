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

`TicketStatus` has four values (`AVAILABLE`, `RESERVED`, `PAYING`, `SOLD` — still no `CANCELLED`).
**`RESERVED` is not stored in Postgres**: a reservation is a Redis hold that expires on its own, and
`RESERVED` is what reads report while one exists (see reservation-holds.md). Postgres stores
`AVAILABLE`, `PAYING`, and `SOLD`. `purchaseTicket` calls out to the payment service via gRPC
between its two Postgres transitions — see payment-service-grpc.md and
docs/payment-service-integration.md.

```
   reserveTicket(id) /           purchaseTicket(id, token, reqId)   [payment service call]
   reserveAvailableTicket(event)
   ┌───────────────────►┌───────────►┌──────────────►┌─── APPROVED ────►┌──────
   │  Redis hold for     RESERVED     caller must      PAYING             SOLD
   │  the caller (TTL)   (Redis only)  hold it;              │
AVAILABLE◄────────────────┘            hold deleted          │ DECLINED
   userId=null   releaseTicket(id),    after commit          ▼
        ▲        or the hold expires                     AVAILABLE (userId cleared)
        └───────────────────────── cancelTicket(id) ──────────┘ (from SOLD)
                                    clears userId
```

| Transition | Method | Precondition (else 400, or 409 for PAYING) | Ownership check (else 403) | Result |
|---|---|---|---|---|
| → RESERVED | `reserveTicket(id)` | status is `AVAILABLE` and not already held | none — anyone can reserve an open ticket | a Redis hold for the caller, expiring after `hold-ttl`; **Postgres unchanged** |
| → RESERVED | `reserveAvailableTicket(eventId)` — same transition, ticket chosen by the server | event exists (else 404); some ticket is `AVAILABLE` and not held (else **409**, `NoTicketsAvailableException`) | none | a hold on the lowest-id such ticket the caller wins |
| RESERVED → AVAILABLE | `releaseTicket(id)`, **or the hold's TTL lapsing** | the caller holds it (not held / expired → 400) | the hold's user is the caller (else 403) | hold deleted; Postgres unchanged |
| RESERVED → PAYING | `purchaseTicket(id, paymentToken, paymentRequestId)` — Txn 1 (`beginPayment`) | status is `AVAILABLE` and the caller holds it (no hold → 400), **else 409 if already `PAYING`** (a concurrent attempt is in flight — see below) | the hold's user is the caller (else 403) | status = `PAYING`, `userId` = caller; `paymentRequestId` stamped (caller-supplied or generated); the hold is deleted after this commits |
| PAYING → SOLD / AVAILABLE | same call, Txn 2 (`completePayment`), after the gRPC call returns | n/a (status is already `PAYING` from Txn 1) | n/a | APPROVED → `SOLD`, `datePurchased` stamped to now, records `PURCHASE`; DECLINED → `AVAILABLE` + `userId=null` (`datePurchased` untouched), records `PAYMENT_DECLINED`; call failure (timeout/connection error) → **ticket stays `PAYING`**, no operation recorded, throws `PaymentServiceUnavailableException` (502) |
| SOLD → AVAILABLE | `cancelTicket(id)` | status is `SOLD` | caller's id equals ticket's `userId` | `userId` = `null`, status = `AVAILABLE`; records a `CANCEL` `TicketOperation` |

- **The PAYING→409 check is distinct from the generic "not RESERVED" 400**: `beginPayment` checks
  `status == PAYING` first and throws `TicketPaymentInProgressException` (409 — a conflict with an
  attempt already in flight) before the ordinary "not AVAILABLE / no hold" checks (400 — a
  precondition failure), so a double-submitted purchase request never starts a second
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
- **`userId` is the ownership token**: during a reservation the owner lives only in the Redis hold.
  The Postgres `userId` is set when a purchase moves the ticket to `PAYING`, and cleared on the paths
  back to `AVAILABLE` (`cancelTicket`, and a `DECLINED` purchase outcome), so every `AVAILABLE`
  ticket has `userId = null` in Postgres as an invariant. It is never accepted from the client —
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
- **Reservations are Redis holds** — see reservation-holds.md for the keys, scripts, expiry, and
  fail-closed behavior. Holds replaced two earlier designs: Postgres `RESERVED` rows with a
  `SELECT ... FOR UPDATE` row lock on reserve (which had fixed a race where concurrent reserves of
  one ticket all returned 200), and `FOR UPDATE SKIP LOCKED` for reserve-next. Under holds, Redis's
  `SET NX` is the arbiter: of concurrent reserves of one ticket, exactly one wins.
- **Postgres row locks still guard the purchase saga and cancel**: `beginPayment`,
  `completePayment`, and `cancelTicket` load the ticket via `lockTicketOrThrow` →
  `TicketRepository.findByIdForUpdate` (`SELECT ... FOR UPDATE`, held only within each short
  transaction, never across the payment-service call). Without it, `beginPayment`'s `PAYING`→409
  guard was racy. Reproduced against Postgres: 5 concurrent purchases of one ticket → **3× 200 and
  3 `PURCHASE` operations** (a triple charge against a real payment service). With the lock: 1×
  200, 4× 409. A pessimistic lock rather than `@Version`, because there's no migration tool and
  `ddl-auto: update` would add a `version` column that's NULL on every existing row.
- All transitions require authentication but not `ROLE_ADMIN` — ownership (`userId` match),
  not role, is what gates `release`/`purchase`/`cancel`; see authorization-model.md.
- **No live "available" counter is stored anywhere** — `Event.ticketsTotal` is a fixed capacity set
  at creation (immutable; no setter), not a count these transitions decrement/increment. The number
  of tickets currently `AVAILABLE` is computed on demand:
  `TicketService.getNumAvailableTickets(eventId)` runs
  `TicketRepository.countAvailableExcluding(eventId, heldIds)` against Postgres directly (`AVAILABLE`
  minus the event's live Redis holds),
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
and `TicketHoldService` are added as plain Mockito `@Mock`s to the constructor
(`new TicketService(ticketRepository, eventRepository, userRepository, ticketOperationRepository,
cassandraAsyncWriter, paymentServiceClient, paymentCacheService, transactionManager,
reservationProperties, holdService)`, where `reservationProperties` is a real
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
