package com.eventmanager.kafka;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class PerformerVideoEventPublisher {

    static final String TOPIC = "performer-video-events";

    @Autowired(required = false)
    private KafkaTemplate<String, VideoEvent> kafkaTemplate;

    public void publish(VideoEvent event) {
        if (kafkaTemplate == null) return;
        kafkaTemplate.send(TOPIC, String.valueOf(event.performerId()), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish VideoEvent operation={} performerId={} videoId={}",
                                event.operation(), event.performerId(), event.videoId(), ex);
                    } else {
                        log.debug("Published VideoEvent operation={} performerId={} videoId={}",
                                event.operation(), event.performerId(), event.videoId());
                    }
                });
    }
}
