package com.eventmanager.cassandra.schema;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The pure comparison behind CassandraSchemaCheck; the live check was verified against docker-compose Cassandra. */
class CassandraSchemaCheckTest {

    private static Map<String, String> cols(String... nameTypePairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < nameTypePairs.length; i += 2) m.put(nameTypePairs[i], nameTypePairs[i + 1]);
        return m;
    }

    @Test
    void reportsMissingColumnsAsOneAlterPerTable() {
        // The real drift found on 2026-11-12: ticket_operations lacked two payment columns.
        var report = CassandraSchemaCheck.compare("event_manager",
                Map.of("ticket_operations", cols("id", "bigint", "operation", "text",
                        "payment_request_id", "text", "idempotency_token", "text")),
                Map.of("ticket_operations", Set.of("id", "operation")));

        assertThat(report.isClean()).isFalse();
        assertThat(report.missingColumnCount()).isEqualTo(2);
        assertThat(report.alterStatements()).containsExactly(
                "ALTER TABLE event_manager.ticket_operations ADD (payment_request_id text, idempotency_token text);");
    }

    @Test
    void extraColumnsInTheTableAreFine() {
        // e.g. events still has the old tickets_available column after the field was renamed.
        var report = CassandraSchemaCheck.compare("event_manager",
                Map.of("events", cols("id", "bigint", "tickets_total", "int")),
                Map.of("events", Set.of("id", "tickets_total", "tickets_available")));

        assertThat(report.isClean()).isTrue();
        assertThat(report.alterStatements()).isEmpty();
    }

    @Test
    void reportsATableThatDoesNotExist() {
        var report = CassandraSchemaCheck.compare("event_manager",
                Map.of("tickets", cols("id", "bigint")), Map.of());

        assertThat(report.isClean()).isFalse();
        assertThat(report.missingTables()).containsExactly("tickets");
    }
}
