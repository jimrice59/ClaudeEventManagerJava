# Setting Up an ELK/EFK Stack for Distributed Logging

How much operational work is it to set up an ELK/EFK stack to get distributed logging, and what
else would be available with one?

## Operational work — more than it looks at first glance, for three separate reasons

### 1. Getting useful data into it is the actual bottleneck, not standing up the stack

This project's logging convention right now is plain parameterized SLF4J text (`"Received request
to get event id={}"`), going straight to stdout. ELK's value comes from structured, queryable
fields — so step one is switching to JSON logging (e.g. `logstash-logback-encoder`) so each log
line becomes `{timestamp, level, logger, message, eventId, ...}` rather than a text blob. Skip that
and you're stuck writing Grok patterns in Logstash to regex-parse plain text back into fields —
brittle, and it breaks every time a log message's wording changes. This is genuinely the
highest-effort part, not optional infrastructure glue.

### 2. The stack itself is three more services to run and keep healthy, not one

- **Elasticsearch** — needs heap sized correctly (roughly 50% of node RAM, capped well under 32GB
  due to a JVM compressed-pointers cliff), an Index Lifecycle Management (ILM) policy so old log
  indices roll over and get deleted automatically (skip this and disk fills up — logs grow forever,
  unlike your app data), and for anything beyond a single dev node, a real multi-node cluster for
  HA, which means cluster discovery, shard/replica planning, and TLS/auth between nodes.
- **Logstash or Fluentd/Fluent Bit** (the "L"/"F" — the shipping agent) — one instance per log
  source. In this project's Docker Compose setup that's one per container; in Kubernetes it's
  typically a Fluent Bit DaemonSet (one per node, tailing every pod's stdout) — less config than
  Logstash, which is why "EFK" has become more common than "ELK" for container workloads.
- **Kibana** — needs index patterns and dashboards built by hand before it's actually useful; the
  default install is just an empty query UI over raw JSON documents.

### 3. Security and retention are not defaults — they're setup work

Auth between components, TLS, and an ILM policy for retention all have to be deliberately
configured or you end up with an open, unbounded log store.

### Ways to lower that cost, given this project's actual setup

On Kubernetes, the ECK operator (Elastic Cloud on Kubernetes) automates most of cluster
lifecycle/TLS/scaling — genuinely worth using over hand-rolling manifests. A managed option
(Elastic Cloud, AWS OpenSearch Service) removes the cluster-ops burden entirely at the cost of a
hosting bill. And since this project *already* runs Grafana for Prometheus metrics, **Grafana
Loki** (with Promtail/Fluent Bit as the agent) is a genuinely lighter alternative worth considering
here specifically: it indexes only log labels rather than full text (cheaper to run), and it puts
logs in the same Grafana UI your metrics dashboards already live in, instead of standing up a
separate Kibana.

## What else comes with ELK/EFK beyond "search your logs"

- **Full-text + structured search** across every service's logs at once — "all `WARN` logs from
  `TicketService` where `eventId=42`" instead of grepping container output.
- **Kibana dashboards** — error-rate trends, log volume by service/level over time, top exceptions
  by frequency — once someone builds them; nothing comes pre-built for your app's specific fields.
- **Alerting** (Kibana Alerting/Watcher, or the open-source ElastAlert) — fire on patterns like
  "ERROR count spiked 5x in 5 minutes" or a specific exception recurring.
- **Request correlation**, if paired with a correlation/request ID stamped via MDC on each request
  (this project doesn't have one today) — pivot from a specific slow or failed request straight to
  every log line it touched across services, which becomes far more valuable once combined with the
  distributed tracing (OpenTelemetry/Jaeger) mentioned in `other-java-backend-technologies.md` —
  **Elastic APM**/"Elastic Observability" is Elastic's own answer that unifies logs, metrics, and
  traces in one stack, as an alternative to running Prometheus+Grafana and ELK and Jaeger as three
  separate systems.
- **Security/audit angle** — Elastic Security (SIEM-style) can flag things like repeated failed
  logins against `/api/v1/auth/login`, though that's a heavier, more specialized use of the same
  underlying data.
