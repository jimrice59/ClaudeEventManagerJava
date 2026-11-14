# Monitoring Event Manager with Prometheus and Grafana

This guide covers how to watch the Event Manager backend's performance and health with the
Prometheus and Grafana containers that ship in `docker-compose.yml`: what's collected, how to set
up Grafana, which queries answer the common questions, and how to set alerts.

Everything here was checked against the running Docker Compose stack on 2026-11-13 (Grafana 13.1).

## Contents

1. [How it fits together](#how-it-fits-together)
2. [Start the stack](#start-the-stack)
3. [Check that Prometheus is collecting](#check-that-prometheus-is-collecting)
4. [Known issue: every metric is collected twice](#known-issue-every-metric-is-collected-twice)
5. [Set up Grafana](#set-up-grafana)
6. [Useful queries](#useful-queries)
7. [Build a dashboard for this app](#build-a-dashboard-for-this-app)
8. [Alerts](#alerts)
9. [Generating load to see something](#generating-load-to-see-something)
10. [Metric reference](#metric-reference)
11. [Troubleshooting](#troubleshooting)

## How it fits together

```
Event Manager app ──(exposes)──► /actuator/prometheus   (Spring Boot Actuator + Micrometer)
                                        ▲
                                        │ scrapes every 15 s
                                 Prometheus :9090        (stores the time series)
                                        ▲
                                        │ queries (PromQL)
                                  Grafana :3000          (dashboards and alerts)
```

- **The app** publishes its metrics as plain text at `/actuator/prometheus`. Spring Boot provides
  the standard ones (HTTP requests, JVM, connection pools, Redis, caches); the app adds its own
  Cassandra metrics. Every metric carries the tag `application="event-manager"`.
- **Prometheus** fetches ("scrapes") that page every 15 seconds and keeps the history. Its config
  is [monitoring/prometheus.yml](../monitoring/prometheus.yml).
- **Grafana** draws dashboards from Prometheus and can send alerts.

| Service | URL | Login |
|---|---|---|
| App metrics (raw) | http://localhost:8080/actuator/prometheus | none (public) |
| App health | http://localhost:8080/actuator/health | none (public) |
| Prometheus | http://localhost:9090 | none |
| Grafana | http://localhost:3000 | `admin` / `admin` |

## Start the stack

Prometheus and Grafana start with everything else:

```bash
docker compose up -d
docker compose ps prometheus grafana     # both should be "Up"
```

Grafana keeps its settings and dashboards in the `grafana_data` volume, so the setup below is
needed once per machine (or after `docker compose down -v`, which deletes volumes).

## Check that Prometheus is collecting

Open http://localhost:9090/targets. The `event-manager` job lists two targets:

| Target | Meant for |
|---|---|
| `app:8080` | the app running in Docker Compose |
| `host.docker.internal:8080` | the app running on your machine (`mvn spring-boot:run`) |

The one that isn't in use normally shows **DOWN**, which is harmless. To look at the raw data
directly:

```bash
curl -s localhost:8080/actuator/prometheus | grep http_server_requests_seconds_count | head
```

## Known issue: every metric is collected twice

When the app runs in Docker, **both** targets show UP. `host.docker.internal:8080` reaches the same
container through its published port 8080, so Prometheus collects every metric twice, under two
`instance` labels. Any query that adds series together without picking one instance comes out
doubled. (Measured: `/api/v1/events` at 3.8 requests/s per instance, 8.2 requests/s summed.)

Until the config is changed, pick one of:

- **Filter every query** to one instance with `{instance="app:8080"}`. All queries in this guide do.
- **Remove the unused target** from `monitoring/prometheus.yml`, then restart Prometheus:
  `docker compose restart prometheus`. Remove `host.docker.internal:8080` while you use Docker, or
  `app:8080` while you use `mvn spring-boot:run`.

## Set up Grafana

### 1. Connect Grafana to Prometheus (once)

1. Open http://localhost:3000 and log in as `admin` / `admin`. Grafana asks for a new password;
   you can skip it for local use.
2. Go to **Connections → Data sources → Add data source → Prometheus**.
3. Set **Prometheus server URL** to `http://prometheus:9090`.

   Use the container name, not `localhost`: Grafana runs in its own container, where `localhost`
   is Grafana itself.
4. Click **Save & test**. It should report that the data source is working.

### 2. Import ready-made dashboards

Go to **Dashboards → New → Import**, enter an ID, click **Load**, pick the Prometheus data source,
and click **Import**.

| ID | Dashboard | Shows |
|---|---|---|
| **4701** | JVM (Micrometer) | request rate and latency, heap and non-heap memory, garbage collection, threads, CPU, Tomcat, log events |
| **11378** | Spring Boot statistics | a one-page summary: uptime, CPU, memory, request rate and errors, connection pool |

Both dashboards have an **instance** selector at the top; choose `app:8080` (see the
[known issue](#known-issue-every-metric-is-collected-twice)).

## Useful queries

You can run these in Prometheus (http://localhost:9090, **Graph** tab) or use them in Grafana panels.
`rate(...[1m])` means "per second, averaged over the last minute"; use `[5m]` for smoother lines.

### Traffic and latency

| Question | PromQL |
|---|---|
| Requests per second, per endpoint | `sum by (uri) (rate(http_server_requests_seconds_count{instance="app:8080"}[1m]))` |
| 95th-percentile latency per endpoint (seconds) | `histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket{instance="app:8080"}[5m])))` |
| Average latency per endpoint (seconds) | `sum by (uri) (rate(http_server_requests_seconds_sum{instance="app:8080"}[5m])) / sum by (uri) (rate(http_server_requests_seconds_count{instance="app:8080"}[5m]))` |
| Slowest single request recently | `max by (uri) (http_server_requests_seconds_max{instance="app:8080"})` |
| Server errors (5xx) per second | `sum by (uri) (rate(http_server_requests_seconds_count{instance="app:8080", status=~"5.."}[5m]))` |
| Rate-limited requests (429) per second | `sum(rate(http_server_requests_seconds_count{instance="app:8080", status="429"}[5m]))` |
| Requests per status code | `sum by (status) (rate(http_server_requests_seconds_count{instance="app:8080"}[5m]))` |

The `uri` label is the route pattern, not the literal URL, e.g. `/api/v1/tickets/{id}/purchase`,
so every ticket's purchases are grouped together. Purchases are worth watching separately: their
time is mostly the payment-service call, which can take up to 120 s before it times out.

`histogram_quantile` needs a few minutes of traffic on an endpoint; with none it returns `NaN`.

### Ticket flow

| Question | PromQL |
|---|---|
| Purchases per second | `sum(rate(http_server_requests_seconds_count{instance="app:8080", uri="/api/v1/tickets/{id}/purchase"}[5m]))` |
| Payment-service failures (purchase returned 502) | `sum(rate(http_server_requests_seconds_count{instance="app:8080", uri="/api/v1/tickets/{id}/purchase", status="502"}[5m]))` |
| Reservations per second (both reserve routes) | `sum(rate(http_server_requests_seconds_count{instance="app:8080", uri=~".*/reserve"}[5m]))` |
| Reservations failing because Redis is down (503) | `sum(rate(http_server_requests_seconds_count{instance="app:8080", uri=~".*/reserve", status="503"}[5m]))` |

### Databases and caches

| Question | PromQL |
|---|---|
| Postgres connections in use / waiting | `hikaricp_connections_active{instance="app:8080"}` and `hikaricp_connections_pending{instance="app:8080"}` |
| Postgres connection pool size | `hikaricp_connections_max{instance="app:8080"}` |
| Time spent waiting for a Postgres connection | `rate(hikaricp_connections_acquire_seconds_sum{instance="app:8080"}[5m]) / rate(hikaricp_connections_acquire_seconds_count{instance="app:8080"}[5m])` |
| Redis commands per second | `sum(rate(lettuce_command_completion_seconds_count{instance="app:8080"}[5m]))` |
| Cassandra writes by type and outcome | `sum by (entity, operation, outcome) (increase(cassandra_writes_total{instance="app:8080"}[1h]))` |
| Cassandra write failures | `sum(increase(cassandra_writes_total{instance="app:8080", outcome="failure"}[15m]))` |
| Cassandra columns missing from tables (schema drift) | `cassandra_schema_missing_columns{instance="app:8080"}` |

`pending` above 0 for more than a moment means requests are queueing for a Postgres connection.

**Cache hit/miss counters read 0.** `cache_gets_total` exists for every cache (`events`,
`allEvents`, and so on), but the Redis cache manager only counts hits and misses when statistics
are turned on, and `RedisConfig` doesn't turn them on. Adding `.enableStatistics()` to the
`RedisCacheManager.builder(...)` call would make these real.

### JVM and process

| Question | PromQL |
|---|---|
| Memory in use (heap and non-heap) | `sum by (area) (jvm_memory_used_bytes{instance="app:8080"})` |
| Heap use as a share of the maximum | `sum(jvm_memory_used_bytes{instance="app:8080", area="heap"}) / sum(jvm_memory_max_bytes{instance="app:8080", area="heap"})` |
| Share of time spent in garbage collection | `sum(rate(jvm_gc_pause_seconds_sum{instance="app:8080"}[5m]))` |
| Process CPU (0 to 1) | `process_cpu_usage{instance="app:8080"}` |
| Live threads | `jvm_threads_live_threads{instance="app:8080"}` |
| Errors logged per second | `rate(logback_events_total{instance="app:8080", level="error"}[5m])` |
| Uptime (seconds) | `process_uptime_seconds{instance="app:8080"}` |

The app handles each request on a Java virtual thread, which don't appear in
`jvm_threads_live_threads` (it counts platform threads), so a steady thread count under load is
expected.

## Build a dashboard for this app

The imported dashboards cover the JVM in general. For this app's own concerns, build one:

1. **Dashboards → New → New dashboard → Add visualization**, and pick the Prometheus data source.
2. Paste a query from above into the **Metrics browser** box (switch the editor to **Code** if it
   shows the builder).
3. Set the panel title and, under **Standard options → Unit**, pick a unit, e.g. *seconds* for
   latency, *requests/sec* for rates, *bytes(IEC)* for memory.
4. Under **Options → Legend**, set the legend to `{{uri}}` (or `{{entity}} {{outcome}}`) so lines
   are named by label rather than by the full series.
5. **Save dashboard**. Repeat for more panels.

A good starting set:

| Panel | Query | Visualization |
|---|---|---|
| Requests/s by endpoint | requests per second, per endpoint | Time series |
| p95 latency by endpoint | 95th-percentile latency | Time series, unit seconds |
| 5xx and 429 per second | the two status queries | Time series |
| Purchase outcomes | purchase rate, and the 502 rate | Time series |
| Postgres pool | active, pending, max | Time series |
| Cassandra write failures (last 15 min) | write failures | Stat, red above 0 |
| Schema drift | missing columns | Stat, red above 0 |
| Heap use | heap share of maximum | Gauge, unit percent (0.0–1.0) |

## Alerts

Grafana can evaluate a query on a schedule and notify you. In a panel, choose **Edit → Alert →
New alert rule** (or **Alerting → Alert rules → New alert rule**), enter the query, a condition,
and how long it must hold before firing. Contact points (email, Slack, etc.) are set under
**Alerting → Contact points**.

Suggested rules:

| Alert | Query | Fire when |
|---|---|---|
| Cassandra writes failing | `sum(increase(cassandra_writes_total{instance="app:8080", outcome="failure"}[15m]))` | above 0 |
| Cassandra schema drift | `cassandra_schema_missing_columns{instance="app:8080"}` | above 0 |
| Server errors | `sum(rate(http_server_requests_seconds_count{instance="app:8080", status=~"5.."}[5m]))` | above 0.1 for 5 min |
| Payment service failing | purchase 502 rate (above) | above 0 for 5 min |
| Slow API | `histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{instance="app:8080", uri!="/api/v1/tickets/{id}/purchase"}[5m])))` | above 1 (second) for 10 min |
| Postgres pool exhausted | `hikaricp_connections_pending{instance="app:8080"}` | above 0 for 2 min |
| Heap nearly full | heap share of maximum | above 0.9 for 10 min |
| App down | `up{job="event-manager", instance="app:8080"}` | equal to 0 for 1 min |

The two Cassandra alerts exist because of a real incident: Cassandra tables silently fell behind
the app's entities, and every write to them failed for weeks with only log lines to show for it.
See the schema drift notes in [design-document.md](design-document.md).

The slow-API rule leaves purchases out, since their time depends on the external payment service.

## Generating load to see something

Graphs stay flat on an idle app. A simple burst:

```bash
for i in $(seq 1 300); do curl -s -o /dev/null localhost:8080/api/v1/events; done
```

Wait 15–30 seconds for the next scrape. For realistic, sustained load, use the k6 scripts in
[loadtest/k6](../loadtest/k6/README.md): browsing traffic, and users reserving (optionally buying)
tickets, with smoke, load, stress, and spike profiles. They run in Docker, no install needed:

```bash
PROFILE=load ./loadtest/k6/run.sh browse.js
PROFILE=spike EVENT_ID=7 ./loadtest/k6/run.sh reservations.js
```

The app's rate limiter allows 1000 requests per second per client IP (`RATE_LIMIT_MAX_REQUESTS`);
beyond that you'll see 429 responses in the status breakdown. `RATE_LIMIT_ENABLED=false docker compose up -d app`
turns it off for a load test. A purchase needs a logged-in user
with a reservation, so it's easier to exercise through the React UI.

## Metric reference

The metric families most useful for this app. Names are as Prometheus shows them.

| Metric | Type | Labels worth knowing | What it measures |
|---|---|---|---|
| `http_server_requests_seconds_count` / `_sum` / `_bucket` / `_max` | histogram | `uri`, `method`, `status`, `outcome`, `exception` | every HTTP request: count, total time, latency buckets, slowest |
| `http_server_requests_seconds` | summary | `uri`, `quantile` (0.5, 0.95, 0.99) | latency percentiles computed inside the app (can't be combined across instances) |
| `http_server_requests_active_seconds_active_count` | gauge | `uri` | requests in progress right now |
| `cassandra_writes_total` | counter | `entity` (event, performer, ticket, ticket_operation), `operation` (save, delete), `outcome` (success, failure) | every Cassandra write the app makes; app-specific |
| `cassandra_schema_missing_columns` | gauge | | columns the app expects that a Cassandra table lacks, checked at startup; app-specific |
| `hikaricp_connections_active` / `_idle` / `_pending` / `_max` | gauge | `pool` | Postgres connection pool |
| `hikaricp_connections_acquire_seconds_*` | timer | | time to get a Postgres connection |
| `lettuce_command_completion_seconds_*` | timer | `command` | Redis commands (reservation holds, rate limiter, caches) |
| `cache_gets_total` / `cache_puts_total` | counter | `name`, `result` | Redis caches; hits/misses read 0 (statistics off) |
| `jvm_memory_used_bytes` / `jvm_memory_max_bytes` | gauge | `area` (heap, nonheap), `id` | JVM memory |
| `jvm_gc_pause_seconds_*` | timer | `action`, `cause` | garbage collection pauses |
| `jvm_threads_live_threads` | gauge | | platform threads (not virtual threads) |
| `process_cpu_usage`, `system_cpu_usage` | gauge | | CPU, 0 to 1 |
| `logback_events_total` | counter | `level` | log lines by level |
| `tomcat_sessions_*` | various | | web UI (Thymeleaf) sessions |
| `process_uptime_seconds`, `application_ready_time_seconds` | gauge | | uptime and startup time |

**Edge metrics can be switched off.** With `EDGE_METRICS_ENABLED=false`, the app stops publishing
`http_server_requests_*`, `http_server_requests_active_*`, `process_uptime_seconds`, and
`process_start_time_seconds`. That's meant for deployments where a gateway in front of the app
measures traffic instead. If the request panels are empty, check this setting first.

The app's own gRPC server (port 9091) isn't covered by `http_server_requests_*`; gRPC calls have no
metrics today.

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Grafana "Save & test" fails | URL set to `localhost:9090` | use `http://prometheus:9090` |
| A target shows DOWN | the app isn't running that way (normal for one of the two targets), or it's still starting | check http://localhost:8080/actuator/health |
| Numbers look twice as high as expected | both targets reach the same app | [filter by instance or remove a target](#known-issue-every-metric-is-collected-twice) |
| Request panels empty | no traffic yet, edge metrics disabled, or wrong instance selected | generate load; check `EDGE_METRICS_ENABLED`; choose `app:8080` |
| Latency panel shows `NaN` | no requests to that endpoint in the time window | widen the window or generate traffic |
| Cache hit rate is always 0 | Redis cache statistics are off | see [Databases and caches](#databases-and-caches) |
| Dashboard 4701 panels say "No data" | its *application* or *instance* selector doesn't match | choose `event-manager` and `app:8080` at the top |
| Grafana lost its setup | volumes were deleted (`docker compose down -v`) | repeat [Set up Grafana](#set-up-grafana) |

Related: [cassandra-queries.md](cassandra-queries.md) for inspecting Cassandra directly, and the
monitoring section of [design-document.md](design-document.md).
