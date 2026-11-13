package com.eventmanager.cassandra.schema;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * {@code cassandra-schema-check.*}: {@code enabled} (default true) and {@code fail-on-mismatch}
 * ({@code CASSANDRA_SCHEMA_CHECK_FAIL_ON_MISMATCH}, default false: log loudly and keep running, since
 * Cassandra is a secondary archive; set true to refuse to start on drift).
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "cassandra-schema-check")
public class CassandraSchemaCheckProperties {
    private boolean enabled = true;
    private boolean failOnMismatch = false;
}
