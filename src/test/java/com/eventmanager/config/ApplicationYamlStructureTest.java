package com.eventmanager.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards application.yml's nesting. A top-level block inserted in the middle of the {@code spring:}
 * section once silently re-parented {@code spring.cache} and {@code spring.kafka} under it, so the
 * Kafka clients fell back to localhost:9092 and default serializers. The test profile excludes
 * Kafka auto-configuration, so nothing else catches this; this reads the real file as Spring does.
 */
class ApplicationYamlStructureTest {

    @Test
    void springSectionsAreNestedUnderSpring() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        PropertySource<?> yml = sources.get(0);

        for (String key : List.of(
                "spring.datasource.url",
                "spring.data.redis.host",
                "spring.cassandra.schema-action",
                "spring.cache.type",
                "spring.kafka.bootstrap-servers",
                "spring.kafka.producer.value-serializer",
                "spring.kafka.consumer.value-deserializer",
                "spring.kafka.listener.ack-mode",
                "cassandra-schema-check.enabled")) {
            assertThat(yml.getProperty(key)).as(key).isNotNull();
        }
        assertThat(yml.getProperty("cassandra-schema-check.kafka.bootstrap-servers")).isNull();
    }
}
