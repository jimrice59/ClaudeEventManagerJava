package com.eventmanager.kafka;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class PerformerVideoEventConsumer {

    @KafkaListener(
            topics = PerformerVideoEventPublisher.TOPIC,
            groupId = "${spring.kafka.consumer.group-id:event-manager-consumer}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, VideoEvent> record, Acknowledgment ack) {
        VideoEvent event = record.value();
        log.info("Received VideoEvent operation={} performerId={} videoId={} partition={} offset={}",
                event.operation(), event.performerId(), event.videoId(),
                record.partition(), record.offset());

        try {
            switch (event.operation()) {
                case "ADD"    -> handleAdd(event);
                case "DELETE" -> handleDelete(event);
                default       -> log.warn("Unknown operation={} for performerId={} videoId={}",
                                        event.operation(), event.performerId(), event.videoId());
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process VideoEvent operation={} performerId={} videoId={}",
                    event.operation(), event.performerId(), event.videoId(), e);
            // Do not acknowledge — message will be redelivered based on consumer group offset.
            // Consider a dead-letter topic for poison messages after max retries.
        }
    }

    private void handleAdd(VideoEvent event) {
        // TODO: implement ADD logic (e.g. update search index, send notification, sync CDN)
        log.debug("Handling ADD: performerId={} videoId={}", event.performerId(), event.videoId());
    }

    private void handleDelete(VideoEvent event) {
        // TODO: implement DELETE logic (e.g. purge from cache, remove from search index)
        log.debug("Handling DELETE: performerId={} videoId={}", event.performerId(), event.videoId());
    }
}
