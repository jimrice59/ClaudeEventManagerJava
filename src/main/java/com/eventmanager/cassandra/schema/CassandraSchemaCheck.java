package com.eventmanager.cassandra.schema;

import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.cassandra.core.convert.CassandraConverter;
import org.springframework.data.cassandra.core.mapping.CassandraPersistentEntity;
import org.springframework.data.cassandra.core.mapping.CassandraPersistentProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Startup check that every Cassandra entity's columns exist in its table.
 *
 * <p>Why: {@code spring.cassandra.schema-action: create_if_not_exists} creates missing tables but
 * never alters existing ones. When an entity gains or renames a field, every insert naming that
 * column fails with "Undefined column name ...". Those writes run async and their errors are only
 * logged, so this went unnoticed: events, ticket operations, and sold-ticket backups all stopped
 * reaching Cassandra (and the backup failure blocked deleting events with sold tickets).
 *
 * <p>On {@link ApplicationReadyEvent} (after Spring Data has created any missing tables), it compares
 * the mapping model (table, column, CQL type per property) with the live table metadata, logs one
 * ERROR per drifted table with a ready-to-run {@code ALTER TABLE ... ADD} statement, and publishes the
 * count of missing columns as the {@code cassandra.schema.missing.columns} gauge. With
 * {@code cassandra-schema-check.fail-on-mismatch=true} it refuses to start instead. Extra columns in a
 * table (e.g. a renamed field's old column) are harmless to inserts and aren't reported. Skips itself
 * when Cassandra isn't configured (the test profile).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CassandraSchemaCheck {

    private final ObjectProvider<CqlSession> sessionProvider;
    private final ObjectProvider<CassandraConverter> converterProvider;
    private final CassandraSchemaCheckProperties properties;
    private final MeterRegistry meterRegistry;

    private final AtomicInteger missingColumns = new AtomicInteger();

    @EventListener(ApplicationReadyEvent.class)
    public void checkOnStartup() {
        CqlSession session = sessionProvider.getIfAvailable();
        CassandraConverter converter = converterProvider.getIfAvailable();
        if (session == null || converter == null || !properties.isEnabled()) {
            log.debug("Cassandra schema check skipped (Cassandra not configured, or check disabled)");
            return;
        }
        Gauge.builder("cassandra.schema.missing.columns", missingColumns, AtomicInteger::get)
                .description("Columns that Cassandra entities map but their tables lack (inserts naming them fail)")
                .register(meterRegistry);

        String keyspace = session.getKeyspace().map(CqlIdentifier::asInternal).orElse("");
        Map<String, Map<String, String>> expected = expectedColumns(converter);
        Map<String, Set<String>> actual = new LinkedHashMap<>();
        for (String table : expected.keySet()) {
            actualColumns(session, table).ifPresent(cols -> actual.put(table, cols));
        }

        Report report = compare(keyspace, expected, actual);
        missingColumns.set(report.missingColumnCount());
        if (report.isClean()) {
            log.info("Cassandra schema check passed: {} tables match their entities", expected.size());
            return;
        }
        report.missingTables().forEach(t ->
                log.error("Cassandra schema drift: table {}.{} is missing entirely", keyspace, t));
        report.alterStatements().forEach(stmt ->
                log.error("Cassandra schema drift: writes to this table will fail until you run: {}", stmt));
        if (properties.isFailOnMismatch()) {
            throw new IllegalStateException("Cassandra schema drift: " + report.missingColumnCount()
                    + " missing column(s); see the ALTER statements logged above");
        }
    }

    /** table -> (column -> CQL type), from Spring Data's mapping model. */
    private static Map<String, Map<String, String>> expectedColumns(CassandraConverter converter) {
        Map<String, Map<String, String>> expected = new TreeMap<>();
        for (CassandraPersistentEntity<?> entity : converter.getMappingContext().getTableEntities()) {
            Map<String, String> columns = new LinkedHashMap<>();
            entity.doWithProperties((CassandraPersistentProperty p) -> {
                if (!p.isTransient()) {
                    columns.put(p.getRequiredColumnName().asInternal(),
                            converter.getColumnTypeResolver().resolve(p).getDataType().asCql(false, true));
                }
            });
            expected.put(entity.getTableName().asInternal(), columns);
        }
        return expected;
    }

    private static Optional<Set<String>> actualColumns(CqlSession session, String table) {
        return session.getKeyspace()
                .flatMap(ks -> session.getMetadata().getKeyspace(ks))
                .flatMap(ks -> ks.getTable(table))
                .map(TableMetadata::getColumns)
                .map(cols -> cols.keySet().stream().map(CqlIdentifier::asInternal).collect(Collectors.toSet()));
    }

    /** Result of comparing mapped columns with the live tables. */
    public record Report(List<String> missingTables, List<String> alterStatements, int missingColumnCount) {
        public boolean isClean() {
            return missingTables.isEmpty() && missingColumnCount == 0;
        }
    }

    /**
     * Pure comparison, kept separate so it can be unit-tested without a Cassandra cluster.
     * {@code expected}: table -> (column -> CQL type); {@code actual}: table -> its columns (a table
     * absent from {@code actual} doesn't exist). Produces one {@code ALTER TABLE ... ADD (...)} per
     * table with missing columns.
     */
    static Report compare(String keyspace, Map<String, Map<String, String>> expected, Map<String, Set<String>> actual) {
        List<String> missingTables = new ArrayList<>();
        List<String> alters = new ArrayList<>();
        int missing = 0;
        for (Map.Entry<String, Map<String, String>> table : expected.entrySet()) {
            Set<String> present = actual.get(table.getKey());
            if (present == null) {
                missingTables.add(table.getKey());
                continue;
            }
            List<String> adds = table.getValue().entrySet().stream()
                    .filter(col -> !present.contains(col.getKey()))
                    .map(col -> col.getKey() + " " + col.getValue())
                    .toList();
            if (!adds.isEmpty()) {
                missing += adds.size();
                alters.add("ALTER TABLE " + keyspace + "." + table.getKey() + " ADD (" + String.join(", ", adds) + ");");
            }
        }
        return new Report(missingTables, alters, missing);
    }
}
