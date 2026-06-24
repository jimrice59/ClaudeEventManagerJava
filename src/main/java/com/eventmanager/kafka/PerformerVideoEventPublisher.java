package com.eventmanager.kafka;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutionException;

@Slf4j
@Service
public class PerformerVideoEventPublisher {

    static final String TOPIC = "performer-video-events";
    private static final int MAX_ATTEMPTS = 10;
    private static final long INITIAL_INTERVAL_MS = 10;

    @Autowired(required = false)
    private KafkaTemplate<String, VideoEvent> kafkaTemplate;

    private final RetryTemplate retryTemplate = buildRetryTemplate();

    @Async("kafkaExecutor")
    public void publish(VideoEvent event) {
        if (kafkaTemplate == null) return;
        retryTemplate.execute(ctx -> {
            try {
                kafkaTemplate.send(TOPIC, String.valueOf(event.performerId()), event).get();
                log.debug("Published VideoEvent operation={} performerId={} videoId={}",
                        event.operation(), event.performerId(), event.videoId());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Kafka publish interrupted attempt={} performerId={} videoId={}",
                        ctx.getRetryCount() + 1, event.performerId(), event.videoId());
                throw new RuntimeException(e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                log.warn("Kafka publish failed attempt={} performerId={} videoId={}: {}",
                        ctx.getRetryCount() + 1, event.performerId(), event.videoId(), cause.getMessage());
                throw new RuntimeException(cause);
            }
            return null;
        }, ctx -> {
            log.error("Kafka publish exhausted all {} attempts for operation={} performerId={} videoId={}",
                    ctx.getRetryCount(), event.operation(), event.performerId(), event.videoId(),
                    ctx.getLastThrowable());
            return null;
        });
    }

    private static RetryTemplate buildRetryTemplate() {
        ExponentialBackOffPolicy backOff = new ExponentialBackOffPolicy();
        backOff.setInitialInterval(INITIAL_INTERVAL_MS);
        backOff.setMultiplier(2.0);

        RetryTemplate template = new RetryTemplate();
        template.setBackOffPolicy(backOff);
        template.setRetryPolicy(new SimpleRetryPolicy(MAX_ATTEMPTS));
        return template;
    }
}
