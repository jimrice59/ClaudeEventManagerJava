package com.eventmanager.service;

import com.eventmanager.cassandra.model.CassandraTicket;
import com.eventmanager.cassandra.model.CassandraTicketOperation;
import com.eventmanager.cassandra.repository.TicketOperationCassandraRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.cassandra.core.CassandraOperations;
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
    void saveTicketWritesNullsWithTheChangeTimeAsTheCassandraTimestamp() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CassandraAsyncWriter writer = new CassandraAsyncWriter(registry);
        CassandraOperations operations = mock(CassandraOperations.class);
        ReflectionTestUtils.setField(writer, "cassandraOperations", operations);
        CassandraTicket cancelled = CassandraTicket.builder().id(7L).eventId(3L).status("AVAILABLE").userId(null).build();
        java.time.Instant changedAt = java.time.Instant.parse("2026-11-13T10:15:30.123456Z");

        writer.saveTicket(cancelled, changedAt);

        ArgumentCaptor<InsertOptions> options = ArgumentCaptor.forClass(InsertOptions.class);
        verify(operations).insert(same(cancelled), options.capture());
        // Newest change wins even if writes arrive out of order; the cleared user_id is written.
        assertThat(options.getValue().getTimestamp()).isEqualTo(1794564930123456L);
        assertThat(options.getValue().isInsertNulls()).isTrue();
        assertThat(registry.counter("cassandra.writes", "entity", "ticket", "operation", "save",
                "outcome", "success").count()).isEqualTo(1);
    }
}
