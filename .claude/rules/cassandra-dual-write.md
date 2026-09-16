---
paths:
  - "src/main/java/com/eventmanager/cassandra/**"
  - "src/main/java/com/eventmanager/service/CassandraAsyncWriter.java"
  - "src/main/java/com/eventmanager/service/EventService.java"
  - "src/main/java/com/eventmanager/service/PerformerService.java"
  - "src/main/java/com/eventmanager/service/TicketService.java"
---

# Cassandra Dual-Write (Events, Performers, Ticket Operations) and Ticket Backup

`EventService` and `PerformerService` write to Postgres first (synchronous, within the JPA transaction), then fire an **async**, fire-and-forget Cassandra write via `CassandraAsyncWriter`. `TicketService.recordOperation` uses this same async pattern for `TicketOperation` rows. Tickets themselves are the exception: `TicketService` writes ticket *backups* to Cassandra **synchronously**, and only as a step during event deletion — see **Tickets** below. Postgres is the source of truth; Cassandra is a secondary store with no read path yet.

**Performers**
- Entity: `cassandra/model/CassandraPerformer.java` — `@Table("performers")` with `id` as partition key; fields: `name`, `genre`, `bio`, `videoUrls` (`Set<String>` → Cassandra `SET<text>`)
- Repository: `cassandra/repository/PerformerCassandraRepository.java` — `CassandraRepository<CassandraPerformer, Long>`
- `PerformerService` calls `cassandraAsyncWriter.savePerformer()` / `deletePerformer()` after the Postgres write; `addVideo` and `deleteVideo` also call `savePerformer()` so the Cassandra `video_urls` set stays in sync

**Events**
- Entity: `cassandra/model/CassandraEvent.java` — `@Table("events")` with `id` as partition key; fields: `name`, `description`, `eventDate`, `ticketPrice`, `ticketsTotal`, `venueId`, `createdAt`, `updatedAt`. The `ManyToMany` performers relationship is not stored — it maps poorly to a Cassandra column and `CassandraPerformer` does not store event IDs either. There is no `status` column — see **Tickets** below for why the Cassandra copy is deliberately never updated to `DELETING`.
- Repository: `cassandra/repository/EventCassandraRepository.java` — `CassandraRepository<CassandraEvent, Long>`
- `EventService` calls `cassandraAsyncWriter.saveEvent()` after the Postgres write in `createEvent` and `updateEvent`; `toCassandraEntity(EventResponse)` maps the response DTO to `CassandraEvent`, deriving `venueId` from `response.getVenue().getId()`. `deleteEvent` deliberately does **not** call `cassandraAsyncWriter.deleteEvent()` — see **Tickets** below. `getNumAvailableTickets` is a read query and never writes to Cassandra.

**Tickets**
- Entity: `cassandra/model/CassandraTicket.java` — `@Table("tickets")` with `id` as partition key; fields: `eventId`, `description`, `status` (stored as plain `TEXT`, i.e. `ticket.getStatus().name()` — no Cassandra enum mapping is configured, unlike the JPA side's `@Enumerated(EnumType.STRING)`), `userId`
- Repository: `cassandra/repository/TicketCassandraRepository.java` — `CassandraRepository<CassandraTicket, Long>`
- Tickets are **never** written to Cassandra at creation — only `TicketService.backupAndDeleteAllForEvent(eventId)` writes them, as an archival step run synchronously during `EventService.deleteEvent`, *before* the same tickets are deleted from Postgres. This is why it bypasses `CassandraAsyncWriter`'s fire-and-forget `@Async` pattern entirely: the delete-from-Postgres step must not run until the backup is confirmed written, which an async, exception-swallowing write can't guarantee. `TicketCassandraRepository` is field-injected into `TicketService` directly (`@Autowired(required = false)`), and any exception during the backup propagates up through `deleteEvent`'s `@Transactional` boundary, rolling back the whole delete (status change, ticket removal, event removal — nothing commits) rather than risk deleting tickets with no archived copy.
- Because `deleteEvent` never calls `cassandraAsyncWriter.deleteEvent()`, the event's last Cassandra copy (synced by the most recent `createEvent`/`updateEvent` call) is left in place after the Postgres row is gone — an intentional archive, mirroring what `backupAndDeleteAllForEvent` does for its tickets.

**Ticket operations**
- Entity: `model/TicketOperation.java` — table `ticket_operations`; a flat audit-log row (`eventId`/`ticketId`/`userId` are plain `Long` foreign-key columns, not JPA associations — nothing navigates from an operation back to its ticket/event/user). Fields: `eventId`, `ticketId`, `userId`, `operation` (`TicketOperationType`: `PURCHASE` or `CANCEL`, `@Enumerated(EnumType.STRING)`), `purchasePrice` (always the ticket's event's current `ticketPrice` at the moment of the operation, even for a `CANCEL` row), `paymentService` (fixed default `"Square"`), `paymentConfirmationDetails` (fixed default `"payment confirmation 1234"` — there's no real payment integration yet), `operationDate` (`LocalDateTime.now()` at write time).
- Cassandra entity: `cassandra/model/CassandraTicketOperation.java` — `@Table("ticket_operations")` with `id` as partition key (same generated id as the Postgres row), mirroring every field; `operation` stored as plain `TEXT` (like `CassandraTicket.status`, no Cassandra enum mapping).
- Repositories: `repository/TicketOperationRepository.java` (`JpaRepository<TicketOperation, Long>`, plus a derived `deleteByEventId(Long)`) and `cassandra/repository/TicketOperationCassandraRepository.java` (`CassandraRepository<CassandraTicketOperation, Long>`).
- `TicketService.recordOperation(ticket, type, userId)` is called from `purchaseTicket` (type `PURCHASE`) and `cancelTicket` (type `CANCEL`) after the ticket's own row is saved: it saves the Postgres row synchronously, then calls `cassandraAsyncWriter.saveTicketOperation()` — async, fire-and-forget, the same as events/performers, *not* the synchronous pattern ticket backups use, since there's no delete-ordering constraint to protect here.
- `TicketService.backupAndDeleteAllForEvent(eventId)` also calls `ticketOperationRepository.deleteByEventId(eventId)`, removing the event's operations from Postgres — unconditionally, even when the event has no tickets left. Their Cassandra copies are **never** deleted (there's no `CassandraAsyncWriter.deleteTicketOperation` method), the same archival choice `deleteEvent` makes for the event's own Cassandra copy.
- There is no read API for ticket operations today — they exist purely as an audit trail written by `purchaseTicket`/`cancelTicket`.

**`CassandraAsyncWriter`**
Holds the performer, event, and ticket-operation repositories with `@Autowired(required = false)` field injection. Each method null-checks its repository before writing, so the bean operates safely when Cassandra is excluded (test profile). Methods: `savePerformer`, `deletePerformer`, `saveEvent`, `deleteEvent`, `saveTicketOperation` — all `@Async("cassandraExecutor")`. (`deleteEvent` still exists on the writer for API completeness but `EventService` no longer calls it; there is no `deleteTicketOperation` method at all — ticket operation archives are never deleted from Cassandra.)

`spring.cassandra.schema-action: create_if_not_exists` auto-creates all four tables (`events`, `performers`, `tickets`, `ticket_operations`) on startup. The `event_manager` keyspace is created by the `cassandra-init` container in docker-compose on first `docker compose up -d`. Cassandra takes ~60 s to start; the healthcheck has `start_period: 60s`.

`@EnableJpaRepositories(basePackages = "com.eventmanager.repository")` on `EventManagerApplication` prevents Spring Data JPA from scanning the `cassandra.repository` package, avoiding multi-store conflicts.
