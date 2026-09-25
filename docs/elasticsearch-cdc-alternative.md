# CDC as an Alternative to App-Level Elasticsearch Writes

As an alternative to populating Elasticsearch directly from this application (see
`elasticsearch-search-extension.md`), could you use CDC in Postgres to populate an Elasticsearch
datastore? Yes — and it's actually the more common production pattern for this exact problem,
because it fixes the biggest weakness the app-level dual-write approach has.

**How it would work:** Postgres supports logical replication off its Write-Ahead Log (WAL).
**Debezium** (a Kafka Connect source connector) reads that WAL via a replication slot and emits one
Kafka topic per table (e.g., `eventmanager.public.events`, `eventmanager.public.performers`)
carrying before/after row images for every insert/update/delete. From there, either a Kafka Connect
**Elasticsearch sink connector** writes documents with almost no custom code, or — since this
project already has Kafka wired up with a consumer pattern (`PerformerVideoEventConsumer`) — a
custom `@KafkaListener` consumer transforms each CDC event into a `PerformerDocument`/
`EventDocument` and writes it via `ElasticsearchOperations`. Either way, `EventService`/
`PerformerService` go back to knowing nothing about Elasticsearch at all — the
`ElasticsearchAsyncWriter` calls from the earlier design disappear entirely.

**What this actually solves:** the earlier app-level design had one real gap — "no consistency
guarantee against Postgres... fire-and-forget writes can silently fail." CDC removes that gap
structurally: it reads from the transaction log *after* commit, so it catches every committed
change regardless of which code path made it (the app, a raw SQL fix, a bulk import — anything the
hand-written writer would silently miss), and it's at-least-once by construction rather than
"best-effort unless the executor throws." It also fully decouples the request path from
Elasticsearch — the app never even calls out to it, so a slow or down ES cluster can't add latency
or backpressure to `createEvent`/`updatePerformer` at all, which a fire-and-forget `@Async` call
still theoretically could under sustained load.

**What it costs:** meaningfully more infrastructure — Debezium + Kafka Connect as new deployed
components, plus enabling `wal_level = logical` on Postgres and managing a replication slot (an
unconsumed slot causes WAL to accumulate and can fill disk — an operational risk the app-level
approach doesn't have). Denormalization also gets harder: the earlier design wanted
`EventDocument` to embed venue name and performer names, which in-process is just
`event.getVenue().getName()` on an already-loaded JPA entity graph; with CDC, joining `events` +
`venues` + `event_performers` into one document means either stream-processing the joins (Kafka
Streams/ksqlDB) or doing lookups in the consumer — more moving parts than the app just doing it
inline. And CDC doesn't buy synchronous consistency, just more *reliable* eventual consistency —
it's still async under the hood.

One genuine upside worth flagging: this project's `deleteEvent` currently leaves the Cassandra copy
in place as an intentional archive (documented as such). CDC would capture the real Postgres
`DELETE` and could propagate an actual deletion to the search index — arguably more correct
behavior for search than the archival choice made for Cassandra, since a deleted event showing up
in search results would be a real bug, not a deliberate trade-off.

**Recommendation:** given Kafka is already running here, CDC via Debezium is a very natural fit and
the architecturally sounder long-term choice — but it's a bigger lift than the simpler async-writer
sketch for a project this size. Reach for it if search correctness genuinely matters (stale/missing
search results are a real problem) or if keeping app code decoupled from search infrastructure is a
priority; otherwise the simpler in-app writer is less to stand up and operate.

---

No implementation yet — this captures the design discussion for review before any code is written.
