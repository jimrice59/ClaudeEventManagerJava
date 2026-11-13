package com.eventmanager.service;

import com.eventmanager.cassandra.model.CassandraEvent;
import com.eventmanager.cassandra.model.CassandraPerformer;
import com.eventmanager.cassandra.model.CassandraTicket;
import com.eventmanager.cassandra.model.CassandraTicketOperation;
import com.eventmanager.cassandra.repository.EventCassandraRepository;
import com.eventmanager.cassandra.repository.PerformerCassandraRepository;
import com.eventmanager.cassandra.repository.TicketOperationCassandraRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.InsertOptions;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class CassandraAsyncWriter {

    /**
     * Counts every async write by entity, operation, and outcome ({@code cassandra.writes}, tags
     * entity/operation/outcome=success|failure). Failures used to be visible only as ERROR logs,
     * which is how a schema drift went unnoticed for weeks; alert on
     * {@code cassandra_writes_total{outcome="failure"}} in Prometheus.
     */
    private final MeterRegistry meterRegistry;

    private void count(String entity, String operation, boolean success) {
        meterRegistry.counter("cassandra.writes", "entity", entity, "operation", operation,
                "outcome", success ? "success" : "failure").increment();
    }

    @Autowired(required = false)
    private PerformerCassandraRepository performerCassandraRepository;

    @Autowired(required = false)
    private EventCassandraRepository eventCassandraRepository;

    @Autowired(required = false)
    private TicketOperationCassandraRepository ticketOperationCassandraRepository;

    /** For ticket writes, which need a per-write timestamp that the repository API can't set. */
    @Autowired(required = false)
    private CassandraOperations cassandraOperations;

    @Async("cassandraExecutor")
    public void savePerformer(CassandraPerformer performer) {
        if (performerCassandraRepository == null) return;
        try {
            performerCassandraRepository.save(performer);
            log.debug("Async Cassandra save completed for performer id={}", performer.getId());
            count("performer", "save", true);
        } catch (Exception e) {
            log.error("Async Cassandra save failed for performer id={}", performer.getId(), e);
            count("performer", "save", false);
        }
    }

    @Async("cassandraExecutor")
    public void deletePerformer(Long id) {
        if (performerCassandraRepository == null) return;
        try {
            performerCassandraRepository.deleteById(id);
            log.debug("Async Cassandra delete completed for performer id={}", id);
            count("performer", "delete", true);
        } catch (Exception e) {
            log.error("Async Cassandra delete failed for performer id={}", id, e);
            count("performer", "delete", false);
        }
    }

    @Async("cassandraExecutor")
    public void saveEvent(CassandraEvent event) {
        if (eventCassandraRepository == null) return;
        try {
            eventCassandraRepository.save(event);
            log.debug("Async Cassandra save completed for event id={}", event.getId());
            count("event", "save", true);
        } catch (Exception e) {
            log.error("Async Cassandra save failed for event id={}", event.getId(), e);
            count("event", "save", false);
        }
    }

    @Async("cassandraExecutor")
    public void deleteEvent(Long id) {
        if (eventCassandraRepository == null) return;
        try {
            eventCassandraRepository.deleteById(id);
            log.debug("Async Cassandra delete completed for event id={}", id);
            count("event", "delete", true);
        } catch (Exception e) {
            log.error("Async Cassandra delete failed for event id={}", id, e);
            count("event", "delete", false);
        }
    }

    // No deleteTicketOperation(s) method exists — unlike events/performers, ticket operation
    // archives are never deleted from Cassandra, even when their event is (see
    // TicketService#backupAndDeleteAllForEvent).
    @Async("cassandraExecutor")
    public void saveTicketOperation(CassandraTicketOperation operation) {
        if (ticketOperationCassandraRepository == null) return;
        try {
            ticketOperationCassandraRepository.save(operation);
            log.debug("Async Cassandra save completed for ticket operation id={}", operation.getId());
            count("ticket_operation", "save", true);
        } catch (Exception e) {
            log.error("Async Cassandra save failed for ticket operation id={}", operation.getId(), e);
            count("ticket_operation", "save", false);
        }
    }

    /**
     * Upserts a ticket's current state into the Cassandra {@code tickets} table, after a sale or a
     * cancellation (see TicketService#mirrorTicketToCassandra). Two things differ from the other
     * async saves:
     * <ul>
     *   <li>The write carries {@code changedAt} as its Cassandra write timestamp, so if a sale's
     *       and a later cancel's writes reach Cassandra out of order (each runs on its own
     *       virtual thread), the later change still wins: Cassandra keeps the cell with the
     *       newest timestamp, not the last to arrive.</li>
     *   <li>Nulls are written, so a cancel's cleared {@code user_id} replaces the buyer's id
     *       instead of being skipped.</li>
     * </ul>
     */
    @Async("cassandraExecutor")
    public void saveTicket(CassandraTicket ticket, Instant changedAt) {
        if (cassandraOperations == null) return;
        try {
            // In microseconds, Cassandra's own precision: InsertOptions.timestamp(Instant) truncates
            // to milliseconds, so two changes within one millisecond would tie.
            long micros = Math.addExact(Math.multiplyExact(changedAt.getEpochSecond(), 1_000_000L), changedAt.getNano() / 1_000);
            cassandraOperations.insert(ticket, InsertOptions.builder().timestamp(micros).withInsertNulls().build());
            log.debug("Async Cassandra save completed for ticket id={} status={}", ticket.getId(), ticket.getStatus());
            count("ticket", "save", true);
        } catch (Exception e) {
            log.error("Async Cassandra save failed for ticket id={}", ticket.getId(), e);
            count("ticket", "save", false);
        }
    }
}
