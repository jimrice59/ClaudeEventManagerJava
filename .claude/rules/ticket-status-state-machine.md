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
| RESERVED → AVAILABLE | `releaseTicket(id)` | status is `RESERVED` | caller's id equals ticket's `userId` | `userId` = `null`, status = `AVAILABLE` |
| RESERVED → PAYING | `purchaseTicket(id, paymentToken, paymentRequestId)` — Txn 1 (`beginPayment`) | status is `RESERVED`, **else 409 if already `PAYING`** (a concurrent attempt is in flight — see below) | caller's id equals ticket's `userId` | status = `PAYING`; `paymentRequestId` stamped (caller-supplied or generated) |
| PAYING → SOLD / AVAILABLE | same call, Txn 2 (`completePayment`), after the gRPC call returns | n/a (status is already `PAYING` from Txn 1) | n/a | APPROVED → `SOLD`, records `PURCHASE`; DECLINED → `AVAILABLE` + `userId=null`, records `PAYMENT_DECLINED`; call failure (timeout/connection error) → **ticket stays `PAYING`**, no operation recorded, throws `PaymentServiceUnavailableException` (502) |
| SOLD → AVAILABLE | `cancelTicket(id)` | status is `SOLD` | caller's id equals ticket's `userId` | `userId` = `null`, status = `AVAILABLE`; records a `CANCEL` `TicketOperation` |

- **The PAYING→409 check is distinct from the generic "not RESERVED" 400**: `beginPayment` checks
  `status == PAYING` first and throws `TicketPaymentInProgressException` (409 — a conflict with an
  attempt already in flight) before falling through to `sell()`'s ordinary "not RESERVED" check
  (400 — a precondition failure), so a double-submitted purchase request never starts a second
  concurrent payment-service call for the same ticket.
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
- **`purchaseTicket`/`cancelTicket` write an audit row** (`TicketOperation` — `PURCHASE`,
  `PAYMENT_DECLINED`, or `CANCEL`) — the only durable record that a ticket was ever sold, declined,
  or cancelled, since the ticket row itself loses most of that history once `userId` is cleared.
  `reserveTicket`/`releaseTicket` don't log operations, and neither does a purchase attempt whose
  gRPC call itself fails (nothing was resolved to record).
- **Initial state**: tickets are created `AVAILABLE` with `userId = null` as a side effect of
  `POST /api/v1/events` (`TicketService.createAvailableTickets`, one per unit of `ticketsTotal`) —
  there is no direct "create ticket" entry point.
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
`releaseTicket`/`createAvailableTickets`/`backupAndDeleteAllForEvent` coverage unchanged from
before this feature. For `purchaseTicket`: `purchaseTicket_succeedsWhenReservedBySameUserAndPaymentApproved`
(mocks `PaymentServiceClient.purchaseTicket(...)` to return an `APPROVED` `PaymentOutcome`; asserts
two `ticketRepository.save` calls — one from each saga transaction — and that the recorded
`TicketOperation` carries the real confirmation id and idempotency token), `purchaseTicket_usesClientSuppliedPaymentRequestId`,
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
cacheManager)`) — none need stubbing for the saga's transactional plumbing to work: an unstubbed
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
