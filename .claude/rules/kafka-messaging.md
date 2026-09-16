---
paths:
  - "src/main/java/com/eventmanager/kafka/**"
  - "docs/kafka-*.md"
---

# Kafka Messaging (Performer Video Events)

`spring-kafka` + `spring-retry` are on the classpath. Kafka is optional — `KafkaTemplate` is `@Autowired(required = false)` and `KafkaAutoConfiguration` is excluded in the test profile, so the app starts and tests pass without a broker.

**Infrastructure:** `apache/kafka:3.9.0` (official Apache image) in KRaft mode (no ZooKeeper). Dual listeners: `EXTERNAL://localhost:9092` for host-to-container access, `INTERNAL://kafka:29092` for container-to-container. The `app` service connects via `KAFKA_BOOTSTRAP_SERVERS: kafka:29092`. Healthcheck uses `/opt/kafka/bin/kafka-topics.sh --list` with `start_period: 30s` — the full path is required because `/opt/kafka/bin` is not on `$PATH` in the Apache image. A fixed `CLUSTER_ID` env var is required by the Apache image for KRaft storage formatting (Bitnami generated this automatically). Env vars use `KAFKA_` prefix (e.g. `KAFKA_NODE_ID`) rather than the `KAFKA_CFG_` prefix Bitnami used.

**`VideoEvent`** — `kafka/VideoEvent.java` — Java record: `(String operation, Long performerId, Long videoId)`. `operation` is `"ADD"` or `"DELETE"`. Serialized to JSON by `JsonSerializer`.

**`PerformerVideoEventPublisher`** — `kafka/PerformerVideoEventPublisher.java`:
- Topic: `performer-video-events` (constant `TOPIC`)
- Performer ID is used as the Kafka message key so all events for a given performer land on the same partition (ordered delivery)
- `publish(VideoEvent)` is `@Async("kafkaExecutor")` — dispatched to the `kafkaExecutor` thread pool, HTTP response returns before any Kafka I/O
- Inside the background thread, a `RetryTemplate` with `ExponentialBackOffPolicy(initialInterval=10ms, multiplier=2.0)` and `SimpleRetryPolicy(maxAttempts=10)` wraps `kafkaTemplate.send(TOPIC, key, event).get()`
- `.get()` blocks the background thread until the broker ACKs or fails — this is required to make send failures visible to the retry mechanism
- On each attempt failure: `WARN` logged with attempt number, performer ID, video ID, and error message
- After all 10 attempts exhausted: recovery callback logs `ERROR` with final exception; no exception propagates
- If `KafkaTemplate` is null (test profile): returns immediately

**Retry schedule** (10ms start, 2× doubling, no cap configured):

| Attempt | Delay before attempt |
|---|---|
| 1 | — |
| 2 | 10 ms |
| 3 | 20 ms |
| 4 | 40 ms |
| 5 | 80 ms |
| 6 | 160 ms |
| 7 | 320 ms |
| 8 | 640 ms |
| 9 | 1280 ms |
| 10 | 2560 ms |

**Why `RetryTemplate` not `@Retryable`:** Stacking `@Async` and `@Retryable` on the same method is broken — `@Async` submits to a thread pool and returns a proxy immediately, so the `@Retryable` proxy on the calling thread has nothing to retry. Using `RetryTemplate` programmatically inside the already-dispatched `@Async` method avoids the AOP proxy ordering conflict. No `@EnableRetry` annotation is needed.

## Kafka consumer skeleton

**`PerformerVideoEventConsumer`** — `kafka/PerformerVideoEventConsumer.java` — skeleton consumer for the same topic:
- `@KafkaListener` on `performer-video-events`; `groupId` from `spring.kafka.consumer.group-id` (default `event-manager-consumer`)
- Receives `ConsumerRecord<String, VideoEvent>` for access to partition/offset alongside the typed payload
- Manual ack mode (`Acknowledgment ack`): `ack.acknowledge()` is called only after successful processing; on exception the offset is not committed so the broker redelivers the message
- Dispatches on `event.operation()` to `handleAdd` / `handleDelete` stubs — fill these in with the downstream integration logic (search index, CDN, notification, etc.)
- Unrecognised `operation` values are logged as `WARN` and acknowledged (treat as safe-to-skip)
- Dead-letter topic strategy is not yet implemented; a comment marks the exception path as the extension point

**Consumer config** (`application.yml` `spring.kafka.consumer`):
- `JsonDeserializer` with `spring.json.value.default.type: com.eventmanager.kafka.VideoEvent` — deserializes directly to the record type without requiring a type header in the message (the producer does not write one)
- `spring.json.trusted.packages: com.eventmanager.kafka` — required by `JsonDeserializer` to prevent arbitrary class instantiation
- `auto-offset-reset: earliest` — new consumer group starts from the beginning of the topic
- `listener.ack-mode: manual` — matches the `Acknowledgment` parameter in the listener method; Spring will not auto-commit offsets

See `docs/kafka-consumer-guide.md` for a step-by-step walkthrough of how to implement the stubs and extend this consumer.
