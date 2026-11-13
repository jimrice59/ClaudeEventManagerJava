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
import org.springframework.data.cassandra.core.DeleteOptions;
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
     * Inserts a sold ticket into the Cassandra {@code tickets} table (see
     * TicketService#writeSoldTicketToCassandra). {@link #deleteTicket} removes it again on cancel.
     * <p>
     * Both carry the time of the change as their Cassandra write timestamp. Each runs on its own
     * virtual thread, so a sale's insert and a quick cancel's delete can reach Cassandra out of
     * order; Cassandra resolves them by timestamp, not arrival, so the later change still wins (a
     * delete that arrives first shadows the older insert, and a re-sale's newer insert brings the
     * row back).
     */
    @Async("cassandraExecutor")
    public void saveTicket(CassandraTicket ticket, Instant changedAt) {
        if (cassandraOperations == null) return;
        try {
            cassandraOperations.insert(ticket, InsertOptions.builder().timestamp(micros(changedAt)).build());
            log.debug("Async Cassandra save completed for ticket id={} status={}", ticket.getId(), ticket.getStatus());
            count("ticket", "save", true);
        } catch (Exception e) {
            log.error("Async Cassandra save failed for ticket id={}", ticket.getId(), e);
            count("ticket", "save", false);
        }
    }

    /** Deletes a cancelled ticket's row, timestamped like {@link #saveTicket}. */
    @Async("cassandraExecutor")
    public void deleteTicket(Long id, Instant changedAt) {
        if (cassandraOperations == null) return;
        try {
            cassandraOperations.delete(CassandraTicket.builder().id(id).build(),
                    DeleteOptions.builder().timestamp(micros(changedAt)).build());
            log.debug("Async Cassandra delete completed for ticket id={}", id);
            count("ticket", "delete", true);
        } catch (Exception e) {
            log.error("Async Cassandra delete failed for ticket id={}", id, e);
            count("ticket", "delete", false);
        }
    }

    /**
     * Microseconds since the epoch, Cassandra's write-timestamp precision. The options builders'
     * timestamp(Instant) truncates to milliseconds, so two changes within one millisecond would tie.
     */
    static long micros(Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000L), instant.getNano() / 1_000);
    }
}
