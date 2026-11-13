package com.eventmanager.service;

import com.eventmanager.cassandra.model.CassandraTicket;
import com.eventmanager.cassandra.model.CassandraTicketOperation;
import com.eventmanager.cassandra.repository.TicketOperationCassandraRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.DeleteOptions;
import org.springframework.data.cassandra.core.InsertOptions;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** cassandra.writes counter: failures are counted, not just logged. */
class CassandraAsyncWriterTest {

    @Test
    void countsSuccessesAndFailuresByEntityOperationAndOutcome() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CassandraAsyncWriter writer = new CassandraAsyncWriter(registry);
        TicketOperationCassandraRepository repo = mock(TicketOperationCassandraRepository.class);
        ReflectionTestUtils.setField(writer, "ticketOperationCassandraRepository", repo);
        CassandraTicketOperation op = CassandraTicketOperation.builder().id(14L).build();

        writer.saveTicketOperation(op); // succeeds (mock save returns null)
        when(repo.save(any())).thenThrow(new RuntimeException("Undefined column name payment_request_id"));
        writer.saveTicketOperation(op); // fails: logged *and* counted

        assertThat(registry.counter("cassandra.writes", "entity", "ticket_operation", "operation", "save",
                "outcome", "success").count()).isEqualTo(1);
        assertThat(registry.counter("cassandra.writes", "entity", "ticket_operation", "operation", "save",
                "outcome", "failure").count()).isEqualTo(1);
    }

    @Test
    void ticketInsertAndDeleteCarryTheChangeTimeAsAMicrosecondCassandraTimestamp() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CassandraAsyncWriter writer = new CassandraAsyncWriter(registry);
        CassandraOperations operations = mock(CassandraOperations.class);
        ReflectionTestUtils.setField(writer, "cassandraOperations", operations);
        CassandraTicket sold = CassandraTicket.builder().id(7L).eventId(3L).status("SOLD").userId(42L).build();
        java.time.Instant soldAt = java.time.Instant.parse("2026-11-13T10:15:30.123456Z");
        java.time.Instant cancelledAt = java.time.Instant.parse("2026-11-13T10:15:30.123789Z");

        writer.saveTicket(sold, soldAt);
        writer.deleteTicket(7L, cancelledAt);

        ArgumentCaptor<InsertOptions> insert = ArgumentCaptor.forClass(InsertOptions.class);
        verify(operations).insert(same(sold), insert.capture());
        // Microseconds, not the builder's millisecond-truncated timestamp(Instant): the two
        // changes are 333µs apart and must not tie.
        assertThat(insert.getValue().getTimestamp()).isEqualTo(1794564930123456L);
        ArgumentCaptor<CassandraTicket> deleted = ArgumentCaptor.forClass(CassandraTicket.class);
        ArgumentCaptor<DeleteOptions> delete = ArgumentCaptor.forClass(DeleteOptions.class);
        verify(operations).delete(deleted.capture(), delete.capture());
        assertThat(deleted.getValue().getId()).isEqualTo(7L);
        assertThat(delete.getValue().getTimestamp()).isEqualTo(1794564930123789L);
        assertThat(registry.counter("cassandra.writes", "entity", "ticket", "operation", "save",
                "outcome", "success").count()).isEqualTo(1);
        assertThat(registry.counter("cassandra.writes", "entity", "ticket", "operation", "delete",
                "outcome", "success").count()).isEqualTo(1);
    }
}
