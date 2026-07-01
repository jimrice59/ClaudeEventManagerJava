# Kafka Message Reference

## Overview

The app publishes to one topic: `performer-video-events`. A message is produced every time a video URL is added to or removed from a performer. Messages are JSON-serialized `VideoEvent` records:

```json
{"operation":"ADD","performerId":1,"videoId":3}
{"operation":"DELETE","performerId":1,"videoId":3}
```

The performer ID is used as the Kafka message key so all events for a given performer land on the same partition, preserving delivery order per performer.

The Kafka CLI scripts are located at `/opt/kafka/bin/` inside the container — they are **not** on `$PATH`, so the full path is required for every command.

---

## Listing topics

```bash
docker exec -it eventmanager-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --list
```

## Topic details

```bash
docker exec -it eventmanager-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --topic performer-video-events \
  --describe
```

Shows partition count, replication factor, and leader assignment.

---

## Consuming messages

**All messages from the beginning:**

```bash
docker exec -it eventmanager-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic performer-video-events \
  --from-beginning
```

**Live tail (waits for new messages only):**

```bash
docker exec -it eventmanager-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic performer-video-events
```

**With message keys** (performer ID | JSON body):

```bash
docker exec -it eventmanager-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic performer-video-events \
  --from-beginning \
  --property print.key=true \
  --property key.separator=" | "
```

Example output:
```
1 | {"operation":"ADD","performerId":1,"videoId":3}
1 | {"operation":"DELETE","performerId":1,"videoId":3}
```

**With full metadata** (partition, offset, timestamp, key, value):

```bash
docker exec -it eventmanager-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic performer-video-events \
  --from-beginning \
  --property print.key=true \
  --property print.offset=true \
  --property print.partition=true \
  --property print.timestamp=true \
  --property key.separator=" | "
```

Example output:
```
CreateTime:1782843857110	Partition:0	Offset:0	1 | {"operation":"ADD","performerId":1,"videoId":3}
```

Press `Ctrl+C` to stop the consumer.

---

## Consumer groups

The `kafka-console-consumer.sh` commands above use a temporary consumer group that is discarded on exit. To track offset progress across sessions, assign a named group:

```bash
docker exec -it eventmanager-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic performer-video-events \
  --group my-inspector \
  --from-beginning
```

**Check consumer group lag:**

```bash
docker exec -it eventmanager-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --group my-inspector \
  --describe
```

Shows current offset, log-end offset, and lag per partition.

**List all consumer groups:**

```bash
docker exec -it eventmanager-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --list
```

---

## Producing a test message manually

Useful for verifying a downstream consumer without going through the app:

```bash
docker exec -it eventmanager-kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 \
  --topic performer-video-events \
  --property parse.key=true \
  --property key.separator="|"
```

Then type a message and press Enter:
```
1|{"operation":"ADD","performerId":1,"videoId":99}
```

Press `Ctrl+C` to exit the producer.

---

## How messages are produced by the app

`PerformerVideoEventPublisher` (`kafka/PerformerVideoEventPublisher.java`) publishes after every `addVideo` and `deleteVideo` call in `PerformerService`. Key behaviours:

- The publish is `@Async("kafkaExecutor")` — the HTTP response returns before any Kafka I/O starts
- A `RetryTemplate` with exponential backoff (10 ms initial, 2× multiplier) retries up to 10 times on broker failure
- If all 10 attempts fail, the error is logged and silently swallowed — no exception reaches the caller
- If `KafkaTemplate` is absent (test profile), the method returns immediately without publishing

If messages are missing after a video add/remove, check the app log for `WARN` (retry attempts) or `ERROR` (all retries exhausted) entries from `PerformerVideoEventPublisher`.
