# Payment Service Integration (PAYING Status)

This documents the design implemented for charging a real payment service when a ticket is
purchased, replacing the previous no-op purchase (`SOLD` with hardcoded payment details).

## The state machine gains a PAYING status

```
        reserveTicket(id)              purchaseTicket(id, token, reqId)        [payment service]
   ┌─────────────────────►┌──────────►┌──────────────►┌─────── APPROVED ─────►┌──────
   │  userId=caller         RESERVED    userId must      PAYING                  SOLD
   │                                    match             │
AVAILABLE◄──────────────────┘ releaseTicket(id)           │ DECLINED
   userId=null                clears userId               ▼
        ▲                                             AVAILABLE (userId cleared)
        └──────────────────────── cancelTicket(id) ───────┘ (from SOLD)
```

`TicketStatus` is now `AVAILABLE`, `RESERVED`, `PAYING`, `SOLD` (still no `CANCELLED`). A second
purchase attempt against a ticket already `PAYING` is rejected with 409
(`TicketPaymentInProgressException`), not the generic 400 used for every other invalid-status
case — it's a conflict with a concurrent operation on the same resource, not a precondition
failure.

## Why two transactions instead of one

A naive implementation would wrap `purchaseTicket` in one `@Transactional` method: validate, call
the payment service, then update the row — all inside one database transaction. With a
120-second gRPC deadline, that pins a HikariCP connection (and a row lock on the ticket) for up to
120 seconds per in-flight purchase; a handful of concurrent purchases would exhaust the connection
pool and stall unrelated requests.

Instead, `TicketService.purchaseTicket` runs a **saga**: two short transactions with the gRPC call
in between, with no ambient transaction open while the call is in flight:

1. **`beginPayment`** (its own transaction via `TransactionTemplate`): re-validates RESERVED +
   ownership (the same check `sell()` has always done), stamps a `paymentRequestId`, commits
   RESERVED→PAYING, and returns the ticket's purchase price so the gRPC call doesn't need a second
   read.
2. **The gRPC call itself** — no transaction open at all.
3. **`completePayment`** (its own transaction): re-reads the ticket, commits PAYING→SOLD (APPROVED)
   or PAYING→AVAILABLE (DECLINED), and records the outcome as a `TicketOperation`.

`beginPayment`/`completePayment` are plain private methods invoked directly (self-invocation) from
inside a `TransactionTemplate.execute(...)` lambda — not `@Transactional`-annotated methods called
through the Spring AOP proxy, since self-invocation bypasses that proxy entirely. The transaction
boundary comes from the explicit `TransactionTemplate`, built from the injected
`PlatformTransactionManager`, not from an annotation. This is the standard workaround for "a few
short transactions in one method with slow I/O in between," and it's also what makes the saga
testable with plain Mockito mocks — no real `PlatformTransactionManager` is needed, since the test
only needs `getTransaction`/`commit`/`rollback` to be callable, not to do anything.

## Payment-service contract (gRPC)

Defined in `src/main/proto/payment_service.proto`:

```protobuf
service PaymentService {
  rpc PurchaseTicket(PurchaseTicketRequest) returns (PaymentResponse);
  rpc GetPaymentStatus(GetPaymentStatusRequest) returns (PaymentResponse);
}

message PurchaseTicketRequest {
  string payment_request_id = 1;  // caller-generated idempotency key
  int64 user_id = 2;
  string payment_token = 3;       // tokenized payment-method reference, never raw card data
  int64 ticket_id = 4;
  string purchase_price = 5;      // decimal as a string
}

message PaymentResponse {
  PaymentStatus status = 1;       // APPROVED, DECLINED, or PENDING
  string confirmation_id = 2;     // set only when APPROVED
  string idempotency_token = 3;
}
```

`PurchaseTicketRequest.paymentToken` (the DTO field on the public REST API) is always a tokenized
reference to a previously-vaulted payment method — this application never sees or forwards raw
card data, so it stays out of PCI DSS scope for cardholder data. `payment_request_id` is the
caller's own idempotency key: `PurchaseTicketRequest.paymentRequestId` on the REST DTO lets an
external caller supply one (e.g. so a client-side retry after a dropped connection doesn't risk a
double charge), or `TicketService.beginPayment` generates a UUID if omitted. It's threaded through
every layer — the `Ticket` row, the `TicketOperation` audit row, and both Redis caches below — so
it's always the key to find out what happened to a given purchase attempt.

`GetPaymentStatus` exists for reconciliation (see below) but nothing calls it automatically yet.

## Redis caching

`PaymentCacheService` maintains two independent string caches, both keyed by `paymentRequestId`:

| Key prefix | TTL | Purpose |
|---|---|---|
| `payment:request:` | 24 hours | Maps `paymentRequestId → ticketId`, written **before** the gRPC call goes out. Lets a reconciliation job (or support tooling) resolve a `paymentRequestId` back to its ticket without a Postgres round-trip, and remains resolvable even in the narrow window after the call returns but before `completePayment` commits. |
| `payment:idempotency-token:` | 5 minutes | Caches the payment service's own idempotency token once the call returns. A short crash-recovery window: if this application dies between receiving an APPROVED/DECLINED response and committing `completePayment`, the token can be recovered from Redis instead of re-calling the payment service. Deliberately much shorter than the 24-hour entry — this is a crash-recovery aid, not a durable record; the durable copy is the `TicketOperation` row once `completePayment` commits. |

## What's stored where

`Ticket` gains a `paymentRequestId` column, set when `beginPayment` commits and never cleared
afterward — the most recent payment attempt (successful, declined, or stuck) stays visible on the
ticket itself, including in `TicketResponse`, so a client polling `GET /api/v1/tickets/{id}` during
a `PAYING` window has the id it needs to query the payment service directly if it wants to.

`TicketOperation` (and its Cassandra mirror `CassandraTicketOperation`) gains `paymentRequestId` and
`idempotencyToken` columns. `TicketOperationType` gains `PAYMENT_DECLINED` alongside the existing
`PURCHASE`/`CANCEL`. A `PURCHASE` row now carries the payment service's real `confirmationId`
(previously a hardcoded placeholder); a `PAYMENT_DECLINED` row carries the idempotency token the
payment service returned even though the charge was declined, since it did process the request.
No `TicketOperation` is written when the gRPC call itself fails (nothing was actually resolved) —
see below.

## Reconciling a stuck PAYING ticket

If the gRPC call fails outright — the 120-second deadline is exceeded, or a connection error —
`PaymentServiceClient` returns an `ERROR`-status outcome rather than throwing, and
`completePayment` deliberately **does not** touch the ticket: it stays `PAYING`, and
`TicketService.purchaseTicket` throws `PaymentServiceUnavailableException` (mapped to **502**).
Auto-releasing the ticket back to `AVAILABLE` here would be unsafe: the payment may have actually
succeeded on the payment service's side even though this application never learned the outcome,
and releasing the ticket risks selling it twice.

This is intentionally left as a known gap rather than solved here: `PaymentServiceClient` already
exposes `getPaymentStatus(paymentRequestId)` (calling the proto's `GetPaymentStatus` RPC) and
`PaymentCacheService`/the `Ticket.paymentRequestId` column already carry what's needed to find
every ticket stuck in `PAYING`, but nothing calls `getPaymentStatus` automatically yet. The
natural next step is a periodic sweep (`ticketRepository.findByStatus(PAYING)` filtered to rows
stale past some threshold) that calls `GetPaymentStatus` for each and resolves it exactly the way
`completePayment` does today — the same "durable backstop poll" shape as the `holds:pending` ZSET
sweep described in `redis-reservation-holds.md`, just reading from Postgres instead of a Redis
ZSET as the source of "what's outstanding."

## What's out of scope here

- **Automatic reconciliation sweep** — described above, not implemented.
- **Refunds on cancel** — `cancelTicket` (SOLD→AVAILABLE) still doesn't call the payment service;
  there's no refund RPC in the proto and no code path triggers one.
- **TLS between this application and the payment service** — the gRPC channel
  (`PaymentGrpcConfig`) uses plaintext, matching every other local/dev-profile infrastructure
  connection in this project (Postgres, Redis, Cassandra, Kafka are all unencrypted here too). A
  real deployment would add TLS and client authentication.
