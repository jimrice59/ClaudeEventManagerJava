# Extending This Project to Support Elasticsearch Search

What code could be added to write data to Elasticsearch to support performer or event searches?
Following this project's existing dual-write pattern (`CassandraAsyncWriter`) is the natural fit
here — Elasticsearch would slot in as a second secondary store, written to asynchronously after
Postgres commits, but unlike Cassandra it would actually be *read from* — that's the whole point,
since it's what gives search relevance ranking and fuzzy/typo-tolerant matching that a Postgres
`LIKE '%name%'` query can't.

**1. Dependency:** `spring-boot-starter-data-elasticsearch` (wraps the official Java client + Spring
Data repository support).

**2. Document models** — new classes alongside `cassandra/model/`, e.g.
`search/model/PerformerDocument.java`:
```java
@Document(indexName = "performers")
public class PerformerDocument {
    @Id
    private Long id;
    @Field(type = FieldType.Text, analyzer = "standard")
    private String name;
    @Field(type = FieldType.Keyword)
    private String genre;
    @Field(type = FieldType.Text)
    private String bio;
}
```
An `EventDocument` would go further than the Postgres/Cassandra copies already do — it could
**denormalize** venue name and performer names directly into the document (`venueName`,
`performerNames: List<String>`), something the JPA side deliberately avoids (see `TicketResponse`
deriving venue/performer info at read time rather than duplicating it) — but for a search index,
denormalizing is the correct trade-off, since Elasticsearch can't do the `JOIN FETCH`s
`EventRepository` relies on.

**3. Repository:**
```java
public interface PerformerSearchRepository extends ElasticsearchRepository<PerformerDocument, Long> {
    // derived queries work, but a custom multi-field/fuzzy query is usually better:
}
```
For real search (not just exact lookups), you'd typically bypass the derived-query interface and
use `ElasticsearchOperations` directly with a `multiMatchQuery` across `name`/`bio` with
`Fuzziness.AUTO`, plus a `genre` keyword filter — this is the part that actually beats
`searchPerformers(name)`'s current case-insensitive substring match.

**4. Async writer** — a new `ElasticsearchAsyncWriter`, structured exactly like
`CassandraAsyncWriter`: `@Autowired(required = false)` on the repository/operations bean so it's
safely absent in the test profile, one `@Async("searchExecutor")` method per save/delete, each
null-checking before writing. `PerformerService.createPerformer`/`updatePerformer`/`deletePerformer`
and `EventService.createEvent`/`updateEvent`/`deleteEvent` would each gain one more fire-and-forget
call after their existing `cassandraAsyncWriter` call — same shape, new destination.

**5. New executor:** `AsyncConfig` gets a third named virtual-thread executor (`searchExecutor`),
kept separate from `cassandraExecutor`/`kafkaExecutor` for the same reason those two are separate —
so a slow Elasticsearch cluster never contends with Cassandra or Kafka I/O.

**6. The actual search endpoint** — this is the new capability, not just a mirror of the Cassandra
pattern: `GET /api/v1/performers/search?q=` (or reworking the existing `?name=`/`?genre=` params)
would call the new repository/operations instead of `PerformerRepository`, returning ranked results.
Same for events — a single query box searching name/description/venue/performers at once, instead
of the current separate `?venueId=` and `?start=&end=` filters.

**7. Test profile:** exclude Elasticsearch autoconfiguration the same way `application-test.yml`
already excludes Cassandra/Kafka, so `mvn test` never needs a real cluster.

**One gap worth naming up front:** like the Cassandra copies, this index would have no consistency
guarantee against Postgres — a reindex job (bulk-reading all events/performers and re-writing the
index) becomes necessary for initial backfill and for healing any drift, since fire-and-forget
writes can silently fail. That's the same trade-off this project already accepted for Cassandra,
just now visible on a path users actually hit (search), not just an inert archive.

---

No implementation yet — this captures the design discussion for review before any code is written.
