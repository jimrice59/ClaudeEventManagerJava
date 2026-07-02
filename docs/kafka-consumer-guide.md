# Kafka Consumer Guide

`PerformerVideoEventConsumer` is a ready-to-run skeleton that listens on the
`performer-video-events` topic. This guide explains every part of the class and
walks through the steps to turn the stubs into a real integration.

## Background: how consumer groups coordinate

Each consumer in a group is assigned a subset of the topic's partitions — no two
consumers in the same group ever read the same partition at the same time.

**Partition assignment** is handled by a group coordinator, a Kafka broker elected
to manage that consumer group. When a consumer joins or leaves, the coordinator
triggers a rebalance: all consumers in the group temporarily stop consuming, the
coordinator picks a partition assignment strategy (round-robin, range, sticky,
etc.), and distributes partitions across the live consumers. Once rebalance
completes, each consumer resumes from its last committed offset on its assigned
partitions.

**Offset tracking** is how consumers know where they left off. Each consumer
periodically commits its current offset back to Kafka (to the `__consumer_offsets`
internal topic). On restart or after a rebalance, a consumer fetches that committed
offset and picks up from there. With `ack-mode: manual` as in this project, the
offset is only committed when your code explicitly calls `ack.acknowledge()` — so a
crash before the ack causes the message to be redelivered to whoever gets that
partition next.

**Heartbeats** keep the group membership alive. Each consumer sends periodic
heartbeats to the coordinator on a background thread (`heartbeat.interval.ms`,
default 3s). If the coordinator doesn't hear from a consumer within
`session.timeout.ms` (default 45s), it declares that consumer dead and triggers a
rebalance to redistribute its partitions.

**The practical implication for this project**: the `performer-video-events` topic
keys messages by performer ID, so all events for one performer always land on the
same partition. If you run multiple instances of the app (e.g.
`docker compose --profile traefik up -d --scale app=3`), each instance joins the
same consumer group (`event-manager-consumer`) and Kafka ensures only one instance
processes events for any given performer at a time — preserving ADD-before-DELETE
ordering per performer without any application-level coordination.

The limit is that you can't have more active consumers in a group than there are
partitions. Extra consumers sit idle until a rebalance assigns them a partition
(e.g. when another consumer dies). The Kafka topic in this project is created with
1 partition by default, so only one consumer is ever active — to parallelize you'd
increase the partition count first.

## Background: what the producer sends

`PerformerService` calls `PerformerVideoEventPublisher.publish(VideoEvent)` after
every `addVideo` and `deleteVideo` Postgres write. The publisher is `@Async`, so
the HTTP response returns before the Kafka send starts. The message looks like:

```json
{ "operation": "ADD",    "performerId": 42, "videoId": 7 }
{ "operation": "DELETE", "performerId": 42, "videoId": 7 }
```

The Kafka message **key** is the performer ID as a string. All events for the same
performer land on the same partition in arrival order, so a consumer processing
one partition sees ADD before DELETE for any given video.

## Walking through the skeleton

```java
@KafkaListener(
        topics = PerformerVideoEventPublisher.TOPIC,          // "performer-video-events"
        groupId = "${spring.kafka.consumer.group-id:event-manager-consumer}",
        containerFactory = "kafkaListenerContainerFactory"
)
public void consume(ConsumerRecord<String, VideoEvent> record, Acknowledgment ack) {
```

`ConsumerRecord<String, VideoEvent>` gives you:

| Field | How to access | Typical use |
|-------|--------------|-------------|
| Message key (performer ID) | `record.key()` | Routing, logging |
| Deserialized payload | `record.value()` | Business logic |
| Partition | `record.partition()` | Debugging |
| Offset | `record.offset()` | Idempotency checks, logging |
| Headers | `record.headers()` | Tracing IDs, schema version |

`Acknowledgment ack` is injected by Spring because `listener.ack-mode: manual` is
set in `application.yml`. **Never call `ack.acknowledge()` before your processing
succeeds** — doing so commits the offset even if your downstream write failed, and
the message is lost permanently.

### The ack / no-ack contract

```
success  → ack.acknowledge()   → offset committed, message not redelivered
exception → (no ack call)      → offset stays, message redelivered on restart
                                 or after partition rebalance
```

This gives you at-least-once delivery. Your `handleAdd` / `handleDelete` methods
must be idempotent (safe to call more than once with the same input).

### The switch dispatch

```java
switch (event.operation()) {
    case "ADD"    -> handleAdd(event);
    case "DELETE" -> handleDelete(event);
    default       -> log.warn("Unknown operation ...");
}
ack.acknowledge();
```

Unknown operations are acknowledged (logged and dropped). If you later add a new
operation type to the producer, the consumer will warn rather than crash or
loop. Update the switch when you add new operation types.

## Step 1 — implement `handleAdd`

Fill in the TODO with whatever downstream integration you need. Common examples:

### Update a search index (Elasticsearch)

```java
private void handleAdd(VideoEvent event) {
    VideoDocument doc = VideoDocument.builder()
            .videoId(event.videoId())
            .performerId(event.performerId())
            .build();
    searchIndexClient.index(doc);
}
```

### Send a notification

```java
private void handleAdd(VideoEvent event) {
    notificationService.send(
        "performer." + event.performerId() + ".video.added",
        Map.of("videoId", event.videoId())
    );
}
```

### Trigger a CDN pre-warm

```java
private void handleAdd(VideoEvent event) {
    String videoUrl = videoRepository.findUrlById(event.videoId())
            .orElseThrow(() -> new IllegalStateException("Video " + event.videoId() + " not found"));
    cdnClient.prewarm(videoUrl);
}
```

## Step 2 — implement `handleDelete`

Mirror your ADD logic in reverse. Idempotency matters more here: a delete request
for something already deleted should succeed silently rather than throw.

```java
private void handleDelete(VideoEvent event) {
    searchIndexClient.delete(event.videoId());  // no-op if already absent
}
```

## Step 3 — make your handlers idempotent

The consumer gives at-least-once delivery. Network failures or a crash between
processing and the ack call can deliver the same message twice. Guard against
this by:

- **Checking before writing**: `if (!index.exists(event.videoId())) index.add(...)`
- **Using upsert/merge** instead of insert
- **Storing processed offsets** in a side table and skipping already-seen ones

## Step 4 — decide on a dead-letter strategy

The skeleton catches all exceptions, logs `ERROR`, and leaves the offset
uncommitted. If the underlying problem is transient (network blip, downstream
outage), the message will be retried on the next restart or rebalance. If the
problem is permanent (malformed payload, logic bug), the consumer will be stuck
on the same message indefinitely.

Options:

### A. Spring Kafka `DeadLetterPublishingRecoverer` (recommended)

Add a `DefaultErrorHandler` bean with a `DeadLetterPublishingRecoverer`. After N
retries it forwards the message to `performer-video-events.DLT` and commits the
original offset:

```java
@Bean
public DefaultErrorHandler errorHandler(KafkaTemplate<String, VideoEvent> template) {
    DeadLetterPublishingRecoverer recoverer =
            new DeadLetterPublishingRecoverer(template,
                    (r, e) -> new TopicPartition(r.topic() + ".DLT", r.partition()));
    return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3));
}
```

Wire it into the container factory:

```java
@Bean
public ConcurrentKafkaListenerContainerFactory<String, VideoEvent> kafkaListenerContainerFactory(
        ConsumerFactory<String, VideoEvent> consumerFactory,
        DefaultErrorHandler errorHandler) {
    ConcurrentKafkaListenerContainerFactory<String, VideoEvent> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(consumerFactory);
    factory.setCommonErrorHandler(errorHandler);
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
    return factory;
}
```

### B. Manual dead-letter in the catch block

If you need custom routing logic, publish to a DLT manually:

```java
} catch (Exception e) {
    log.error("Processing failed, sending to DLT: {}", event, e);
    dltTemplate.send("performer-video-events.DLT",
            String.valueOf(event.performerId()), event);
    ack.acknowledge();   // commit original offset — message is in DLT now
}
```

## Step 5 — configure concurrency (optional)

By default, `@KafkaListener` uses a single-threaded container. To process
multiple partitions in parallel, add `concurrency` to the annotation or the
container factory:

```java
@KafkaListener(
        topics = PerformerVideoEventPublisher.TOPIC,
        groupId = "${spring.kafka.consumer.group-id:event-manager-consumer}",
        concurrency = "3"   // one thread per partition, up to the partition count
)
```

Keep in mind: ordering is guaranteed within a partition but not across partitions.
Since the producer keys by performer ID, all events for one performer are on the
same partition — concurrent threads will never interleave events for the same
performer.

## Running locally

Kafka is started by `docker compose up -d`. With the consumer class on the
classpath, it starts automatically when the app starts. To watch it process
events, add a video via the API and tail the logs:

```bash
# Add a video (triggers ADD event)
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"password123"}' | jq -r '.token')

curl -X POST http://localhost:8080/api/v1/performers/1/videos \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.com/video.mp4"}'

# Tail app logs to see the consumer fire
docker logs -f eventmanager-app
```

Expected log output:

```
INFO  PerformerVideoEventConsumer - Received VideoEvent operation=ADD performerId=1 videoId=3 partition=0 offset=4
DEBUG PerformerVideoEventConsumer - Handling ADD: performerId=1 videoId=3
```

## Testing the consumer

Use `@EmbeddedKafka` to test the consumer without a real broker:

```java
@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = "performer-video-events")
class PerformerVideoEventConsumerTest {

    @Autowired
    private KafkaTemplate<String, VideoEvent> kafkaTemplate;

    @MockBean
    private MyDownstreamService myDownstreamService;

    @Test
    void handleAdd_callsDownstreamService() throws Exception {
        VideoEvent event = new VideoEvent("ADD", 1L, 7L);
        kafkaTemplate.send("performer-video-events", "1", event);

        // Give the listener time to process
        verify(myDownstreamService, timeout(5000)).handleAdd(event);
    }
}
```

Add `spring.kafka.consumer.auto-offset-reset: earliest` to
`application-test.yml` so the embedded consumer sees messages sent before it
starts. Note: `KafkaAutoConfiguration` must **not** be excluded in the test
profile if you're testing the consumer — only exclude it in tests that don't
need Kafka at all (e.g. `PerformerServiceTest`).
