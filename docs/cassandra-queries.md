# Cassandra Query Reference

## Connecting

**Interactive shell (via Docker):**

```bash
docker exec -it eventmanager-cassandra cqlsh
```

**One-liner (run a single statement without entering the shell):**

```bash
docker exec -it eventmanager-cassandra cqlsh -e "USE event_manager; SELECT * FROM events;"
```

---

## Keyspace setup

```sql
-- list all keyspaces
DESCRIBE KEYSPACES;

-- select the application keyspace (required before querying tables)
USE event_manager;
```

The `event_manager` keyspace is created automatically by the `cassandra-init` container on first `docker compose up -d`. Tables (`events`, `performers`) are created automatically by Spring Data Cassandra on app startup via `spring.cassandra.schema-action: create_if_not_exists`.

---

## Schema

```sql
-- list tables in the current keyspace
DESCRIBE TABLES;

-- show full table definition
DESCRIBE TABLE events;
DESCRIBE TABLE performers;
```

**events** table columns:

| Column | Type | Notes |
|---|---|---|
| `id` | bigint | partition key |
| `name` | text | |
| `description` | text | |
| `event_date` | timestamp | |
| `ticket_price` | decimal | |
| `tickets_available` | int | |
| `venue_id` | bigint | FK to Postgres venues — no join support in Cassandra |
| `created_at` | timestamp | |
| `updated_at` | timestamp | |

**performers** table columns:

| Column | Type | Notes |
|---|---|---|
| `id` | bigint | partition key |
| `name` | text | |
| `genre` | text | |
| `bio` | text | |
| `video_urls` | set\<text\> | synced on every video add/remove |

The `ManyToMany` event↔performer relationship is not stored in Cassandra — it maps poorly to a column and is only available via Postgres.

---

## Querying events

```sql
USE event_manager;

-- all events
SELECT * FROM events;

-- specific event by id (partition key — efficient)
SELECT * FROM events WHERE id = 1;

-- selected columns only
SELECT id, name, event_date, tickets_available FROM events;

-- count rows
SELECT COUNT(*) FROM events;
```

> Cassandra does not support arbitrary `WHERE` clauses on non-key columns without `ALLOW FILTERING`. Range queries and filtering on `name`, `event_date`, etc. require `ALLOW FILTERING` and perform a full table scan — use Postgres for those queries.

```sql
-- filtering on a non-key column (full scan — avoid on large tables)
SELECT * FROM events WHERE name = 'Rock Night' ALLOW FILTERING;
```

---

## Querying performers

```sql
USE event_manager;

-- all performers
SELECT * FROM performers;

-- specific performer by id
SELECT * FROM performers WHERE id = 1;

-- view video URLs for a performer (stored as a set)
SELECT id, name, video_urls FROM performers WHERE id = 1;

-- find performers with a specific video URL (full scan)
SELECT id, name FROM performers WHERE video_urls CONTAINS 'https://youtube.com/watch?v=abc' ALLOW FILTERING;
```

---

## Verifying dual-write consistency

Cassandra is a secondary store — Postgres is the source of truth. Use these queries to check that Cassandra is in sync after writes through the app.

```sql
-- event counts should match (performers relationship not stored in Cassandra)
SELECT COUNT(*) FROM events;       -- compare with: SELECT COUNT(*) FROM events in psql

SELECT COUNT(*) FROM performers;   -- compare with: SELECT COUNT(*) FROM performers in psql

-- check a specific record matches
SELECT * FROM events WHERE id = 1;
SELECT * FROM performers WHERE id = 1;
```

---

## Inspecting Cassandra metadata

```sql
-- keyspace replication settings
DESCRIBE KEYSPACE event_manager;

-- cluster info
SELECT cluster_name, data_center, rack FROM system.local;

-- all nodes
SELECT peer, data_center, rack, release_version FROM system.peers;
```

---

## Exiting

```
\q
```

or `Ctrl+D`.
