# Kafka Topic Creation

This document explains the three ways the `performer-video-events` topic can
come into existence: broker auto-creation, Spring `NewTopic` bean, and the
Kafka CLI tools.

## How the topic is used in this project

`PerformerVideoEventPublisher` writes to the topic every time a video URL is
added to or removed from a performer:

```java
static final String TOPIC = "performer-video-events";
// ...
kafkaTemplate.send(TOPIC, String.valueOf(event.performerId()), event).get();
```

`PerformerVideoEventConsumer` reads from the same topic:

```java
@KafkaListener(topics = PerformerVideoEventPublisher.TOPIC, ...)
public void consume(ConsumerRecord<String, VideoEvent> record, Acknowledgment ack) { ... }
```

Neither class creates the topic — they just reference its name. How the
topic actually gets created depends on which of the mechanisms below fires
first.

---

## 1. Broker auto-creation (what happens by default)

Kafka brokers have a setting called `auto.create.topics.enable` which
defaults to `true`. When it is on, the broker creates a topic automatically
the first time any client references a topic name that doesn't exist —
whether that is a producer sending a message, a consumer subscribing, or a
metadata request.

The `docker-compose.yml` for this project never sets
`KAFKA_AUTO_CREATE_TOPICS_ENABLE`, so the broker default of `true` applies.
The sequence on a fresh `docker compose up -d` is:

1. The app starts and the Spring Kafka producer connects to the broker.
2. `PerformerService.addVideo` or `deleteVideo` is called for the first time.
3. `PerformerVideoEventPublisher.publish` calls `kafkaTemplate.send(...)`.
4. The Kafka client sends a metadata request for `performer-video-events`.
5. The broker sees the topic does not exist, checks
   `auto.create.topics.enable = true`, and creates it with:
   - **Partitions:** `num.partitions` broker default → **1**
   - **Replication factor:** `default.replication.factor` broker default → **1**
6. The producer send completes normally.

The same auto-creation fires if the consumer subscribes before any producer
has sent — whichever client references the topic name first wins.

### The consequence of 1 partition

With one partition, only one consumer instance in the group is ever active.
If you run three app instances (`docker compose --profile traefik up -d
--scale app=3`), all three join the `event-manager-consumer` group, but
two of them sit idle waiting for a partition that will never be assigned to
them. Auto-creation with 1 partition is fine for development; it becomes a
bottleneck if you need to process events in parallel.

### Disabling auto-creation for production

Auto-creation is convenient but hides configuration mistakes — a typo in a
topic name silently creates a new topic instead of failing. Production
deployments typically set:

```yaml
KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false"
```

With this set, sending to a non-existent topic returns an
`UnknownTopicOrPartitionException` immediately, which is easier to diagnose
than a consumer that quietly reads from an empty topic.

---

## 2. Spring `NewTopic` bean (application-managed creation)

Spring Kafka's `KafkaAdmin` component runs at application startup and
creates any `NewTopic` beans it finds — using the `CreateTopics` admin API
if the topic does not yet exist, or leaving it alone if it does. This is the
recommended approach when you want the application to own its topic
configuration.

Add a `@Bean` method to any `@Configuration` class (e.g. a new
`KafkaTopicConfig`):

```java
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic performerVideoEventsTopic() {
        return TopicBuilder.name("performer-video-events")
                .partitions(6)
                .replicas(1)
                .build();
    }
}
```

On startup, `KafkaAdmin` connects to the broker and calls `CreateTopics`. If
`performer-video-events` already exists with different settings (e.g. it was
auto-created with 1 partition), `KafkaAdmin` **does not modify it** — the
`NewTopic` bean is a creation hint, not an enforcement mechanism. To change
an existing topic's partition count you must use the CLI (see below).

`KafkaAutoConfiguration` is excluded in the test profile
(`application-test.yml`), so `KafkaAdmin` does not run during tests and
the bean would have no effect there.

---

## 3. Kafka CLI tools

The Kafka image ships with a full set of administration scripts under
`/opt/kafka/bin/`. These are the authoritative tools for creating, inspecting,
and modifying topics.

All examples below connect via `localhost:9092` (the `EXTERNAL` listener
exposed by docker-compose). Run them from the host while the compose stack
is running.

### List existing topics

```bash
docker exec -it eventmanager-kafka \
  /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --list
```

### Describe a topic (partitions, replicas, leader)

```bash
docker exec -it eventmanager-kafka \
  /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --describe \
  --topic performer-video-events
```

Sample output:

```
Topic: performer-video-events   TopicId: abc123   PartitionCount: 1
  Partition: 0   Leader: 1   Replicas: 1   Isr: 1
```

### Create the topic explicitly (before the app starts)

```bash
docker exec -it eventmanager-kafka \
  /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --create \
  --topic performer-video-events \
  --partitions 6 \
  --replication-factor 1
```

Creating the topic before the app starts is the cleanest approach when
`auto.create.topics.enable` is disabled, or when you want more than 1
partition from the outset.

### Increase the partition count on an existing topic

Partitions can be increased but **never decreased** — decreasing would move
data to different partitions and break the ordering guarantee.

```bash
docker exec -it eventmanager-kafka \
  /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --alter \
  --topic performer-video-events \
  --partitions 6
```

After altering, existing messages stay in their current partitions. New
messages are distributed across all 6 partitions using the key (performer ID)
as before.

### Delete a topic

```bash
docker exec -it eventmanager-kafka \
  /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --delete \
  --topic performer-video-events
```

Deletion is asynchronous — the topic is marked for deletion and removed by
the broker in the background. With `auto.create.topics.enable = true`, the
next producer send will recreate it immediately, so delete only makes sense
when the app is stopped or when auto-creation is disabled.

### Read messages from the topic (debugging)

```bash
docker exec -it eventmanager-kafka \
  /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic performer-video-events \
  --from-beginning
```

This prints raw JSON to stdout. Each line is one `VideoEvent` message. Press
`Ctrl-C` to stop. Useful for verifying that `PerformerVideoEventPublisher`
is actually sending messages before implementing the consumer logic.

---

## Choosing the right approach

| Approach | When to use |
|---|---|
| Broker auto-creation (default) | Local development; topic name typos are acceptable |
| `NewTopic` bean | App owns its topic config; want partition count set at startup without manual steps |
| CLI `--create` before app start | `auto.create.topics.enable = false`; CI/CD pipeline pre-provisions topics; need exact control over partition count from day one |
| CLI `--alter` | Increasing partition count on an existing topic without recreating it |
