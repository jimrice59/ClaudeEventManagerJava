package com.eventmanager.service;

import com.eventmanager.cassandra.model.CassandraTicketOperation;
import com.eventmanager.cassandra.repository.TicketOperationCassandraRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
}
