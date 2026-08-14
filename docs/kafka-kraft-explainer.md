# Why Kafka Doesn't Need ZooKeeper

The Kafka instance started by `docker-compose.yml` runs in **KRaft mode**
(Kafka Raft Metadata mode), which replaces ZooKeeper with a metadata
management system built directly into Kafka itself.

## What ZooKeeper was for

Kafka originally depended on Apache ZooKeeper as an external coordination
service. ZooKeeper stored everything Kafka needed to manage cluster state:

- Which broker is the active **controller** (the one that manages partition
  leadership and cluster membership)
- The list of brokers currently in the cluster
- Which broker holds the leader replica for each partition
- Topic and partition configurations
- Consumer group membership and committed offsets (in early Kafka versions)

Every Kafka deployment therefore required running a separate ZooKeeper
ensemble — typically three or five nodes for production — alongside the Kafka
brokers themselves. That meant two distributed systems to operate, monitor,
and keep in sync.

## What KRaft does instead

KRaft moves all metadata management inside Kafka using the
[Raft consensus algorithm](https://raft.github.io/). Instead of delegating
coordination to ZooKeeper, a set of Kafka brokers takes on a **controller**
role and maintains a replicated **metadata log** — an internal Kafka topic
(`__cluster_metadata`) that records every change to the cluster's state.

The controller quorum uses Raft to elect a leader and replicate the metadata
log across controller nodes. The active controller leader processes all
metadata changes; followers replicate them. Any broker can read its own
assignments directly from the local copy of this log, without round-tripping
to an external system.

## How the docker-compose config maps to KRaft concepts

Every env var in the `kafka:` service block is a KRaft configuration. Here
is what each one does:

```yaml
KAFKA_NODE_ID: "1"
```
A unique integer ID for this broker within the cluster. In ZooKeeper mode
the ID was also required; in KRaft it additionally serves as the controller
node ID in the quorum.

```yaml
KAFKA_PROCESS_ROLES: broker,controller
```
This is the key KRaft-specific setting. A KRaft node can be a `broker`
(serves producers and consumers), a `controller` (manages cluster metadata),
or both. Setting both on a single node is normal for development — production
deployments often run dedicated controller nodes. There is no equivalent in
ZooKeeper mode; the controller role was elected dynamically through
ZooKeeper.

```yaml
KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
```
The complete list of controller quorum members in the format
`nodeId@host:port`. Here there is exactly one controller — node 1 at
`kafka:9093`. This is the Raft voters list; Raft requires knowing all
participants upfront. In a three-controller production cluster this would be
`1@kafka1:9093,2@kafka2:9093,3@kafka3:9093`.

```yaml
KAFKA_LISTENERS: EXTERNAL://:9092,INTERNAL://:29092,CONTROLLER://:9093
KAFKA_ADVERTISED_LISTENERS: EXTERNAL://localhost:9092,INTERNAL://kafka:29092
KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: EXTERNAL:PLAINTEXT,INTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT
KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
KAFKA_INTER_BROKER_LISTENER_NAME: INTERNAL
```
Three listener names are defined:

| Listener | Port | Used by |
|---|---|---|
| `EXTERNAL` | 9092 | Clients on the host machine (`localhost:9092`) |
| `INTERNAL` | 29092 | Other containers in the compose network (`kafka:29092`) |
| `CONTROLLER` | 9093 | KRaft controller-to-controller and broker-to-controller Raft traffic |

The `CONTROLLER` listener is a KRaft concept — ZooKeeper-mode Kafka had no
equivalent because inter-broker coordination happened through ZooKeeper, not
a dedicated Kafka listener.

```yaml
CLUSTER_ID: MkU3OEVBNTcwNTJENDM2Qk
```
A Base64-encoded UUID that uniquely identifies this KRaft cluster. On first
boot, Kafka formats its local storage with this ID. In ZooKeeper mode,
ZooKeeper assigned the cluster ID automatically; in KRaft mode you must
supply it explicitly because there is no external system to generate it.
The value here is a fixed constant — on a fresh `docker compose up` the same
ID is used each time, so the volume (if any) stays consistent.

```yaml
KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: "1"
KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS: "0"
```
These are standard single-node development settings, not KRaft-specific.
Replication factor 1 is safe when there is only one broker. Rebalance delay
0 makes consumer groups rebalance immediately, avoiding a wait on startup.

## The startup sequence without ZooKeeper

In ZooKeeper mode the startup order was:

1. ZooKeeper ensemble reaches quorum
2. Kafka brokers connect to ZooKeeper
3. ZooKeeper elects a Kafka controller broker
4. Brokers register themselves and learn partition assignments

In KRaft mode:

1. Kafka brokers start and load the local metadata log
2. The controller quorum runs a Raft election among the configured voters
3. The elected leader controller processes any pending metadata changes
4. Brokers register with the active controller via the `CONTROLLER` listener

All of this happens within a single process (or single container here),
which is why the `start_period: 30s` healthcheck delay is much shorter than
it would need to be if a separate ZooKeeper container also had to start.

## Why ZooKeeper was removed

ZooKeeper coupling created several practical problems:

- **Operational complexity** — two distributed systems to deploy, version,
  and monitor
- **Scaling ceiling** — ZooKeeper became a bottleneck for clusters with
  millions of partitions because all partition metadata was loaded into
  ZooKeeper and then fetched by every broker on startup
- **Consistency gap** — brokers cached ZooKeeper state locally; edge cases
  could leave broker caches and ZooKeeper temporarily inconsistent
- **Blast radius** — a ZooKeeper outage made the Kafka cluster read-only or
  inaccessible even if all brokers were healthy

KRaft addresses all of these: the metadata log is a first-class Kafka topic,
scales with Kafka's own replication model, and the controller quorum is
co-located with the brokers it manages.

ZooKeeper support was deprecated in Kafka 3.5 and **removed entirely in
Kafka 4.0**. The `apache/kafka:3.9.0` image used here supports only KRaft —
there is no ZooKeeper option available even if you wanted one.
