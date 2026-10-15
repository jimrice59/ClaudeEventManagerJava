# CDC as an Alternative to App-Level Cassandra Dual-Write

As an alternative to this application writing to Cassandra itself (see `cassandra-dual-write.md`),
could you use CDC on Postgres to populate Cassandra instead? Yes — the same Debezium-based pattern
discussed in `elasticsearch-cdc-alternative.md` for the search-index use case applies here too. But
this app's Cassandra writes aren't uniform: events/performers/ticket-operations are async
fire-and-forget, while the ticket-archive-on-delete path is deliberately synchronous and
transactional. That asymmetry matters a lot for whether CDC is actually a good fit.

**How it would work:** enable `wal_level = logical` on Postgres, and point Debezium (a Kafka
Connect source connector) at a replication slot reading the WAL. It emits one Kafka topic per
table (`eventmanager.public.events`, `.performers`, `.tickets`, `.ticket_operations`) carrying
before/after row images for every insert/update/delete. A Kafka Connect **Cassandra sink
connector**, or — since this project already has a `@KafkaListener` consumer pattern
(`PerformerVideoEventConsumer`) — a custom consumer, maps each CDC event to a
`CassandraEvent`/`CassandraPerformer`/`CassandraTicket`/`CassandraTicketOperation` and writes it.
`EventService`, `PerformerService`, and `TicketService` would no longer call `CassandraAsyncWriter`
or the Cassandra repositories at all — that code, and the manual `toCassandraEntity()` mapping
methods scattered across all three services, would disappear.

**What this solves for the async paths (events, performers, ticket operations):** the same gap
`elasticsearch-cdc-alternative.md` identifies — `CassandraAsyncWriter`'s fire-and-forget `@Async`
writes are caught and merely logged at `ERROR` on failure, with no retry and no way for a caller to
ever know a Cassandra write was dropped. CDC reads from the committed transaction log, so it
catches every committed change regardless of which code path produced it (the app, a bulk import,
a manual SQL fix) and is at-least-once by construction. It also fully removes Cassandra from the
request path for these writes — though since they're already `@Async`, that benefit is smaller
than it was for the Elasticsearch case, where every write was currently synchronous.

**Denormalization is a non-issue here**, unlike the Elasticsearch case: `CassandraEvent` stores a
plain `venueId` (not an embedded venue name) and `CassandraTicket` stores a plain `eventId` — both
already normalized, not denormalized documents built from a loaded JPA entity graph. A CDC consumer
doesn't need to join anything; it maps one row to one row.

**What this breaks for the ticket-archive-before-delete path:** this is the real objection, and
it's specific to Cassandra's role in this app, not a generic CDC trade-off. `TicketService
.backupAndDeleteAllForEvent` writes an event's SOLD tickets to Cassandra *synchronously, inside the
same transaction* as the Postgres delete, and lets any exception from that write roll back the
whole `deleteEvent` transaction — so a ticket can never be permanently deleted without a confirmed
archive existing first (see cassandra-dual-write.md and data-model.md). CDC cannot preserve this
guarantee: Debezium only observes a row's deletion *after* the Postgres transaction has already
committed. By the time a CDC consumer has archived the ticket's last state into Cassandra, the
"this data no longer exists anywhere else" moment has already passed — if the Cassandra write then
fails, there is no transaction left to roll back. You would be trading "archive confirmed before
delete is irreversible" for "archive eventually, probably, after the delete is already final" — a
strictly weaker guarantee for the one piece of this system that was built to avoid exactly that.

A second, smaller friction: the sold-tickets-only filter (`backupAndDeleteAllForEvent` only backs
up tickets with `status == SOLD`, ignoring AVAILABLE/RESERVED/PAYING — see
ticket-status-state-machine.md) is a one-line `Stream.filter()` in Java today. Under CDC it becomes
stream-processing logic in the consumer or sink connector instead — not hard, but another piece of
business logic that has to live outside the application and be kept in sync with
`TicketStatus`/`TicketService` if that rule ever changes.

**Other costs:** new infrastructure (Debezium, Kafka Connect, a managed replication slot — an
unconsumed slot accumulates WAL and can fill disk, an operational risk the current approach doesn't
have); a harder local-dev/test story, since this project's test profile currently excludes both
Cassandra and Kafka autoconfiguration entirely (`application-test.yml`) for simplicity, and a CDC
pipeline is much harder to fake in a unit/integration test than a mockable `@Autowired(required =
false)` repository field.

**Recommendation:** split by write path rather than deciding once for all of Cassandra. For
events/performers/ticket-operations — the already-async, fire-and-forget writes — CDC is a
reasonable upgrade for the same reasons as the Elasticsearch case: stronger delivery guarantees, no
risk of a silently-dropped write, and simpler application code. For the ticket-archive-before-delete
path, keep the current synchronous in-app write: it's the one place in this system that depends on
a guarantee CDC structurally cannot provide (archive-confirmed-before-irreversible-delete), and
weakening that guarantee to buy infrastructure simplicity would be the wrong trade for an audit
trail.

---

No implementation yet — this captures the design discussion for review before any code is written.
