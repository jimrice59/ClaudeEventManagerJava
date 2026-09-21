package com.eventmanager.cassandra.repository;

import com.eventmanager.cassandra.model.CassandraTicketOperation;
import org.springframework.data.cassandra.repository.CassandraRepository;

public interface TicketOperationCassandraRepository extends CassandraRepository<CassandraTicketOperation, Long> {
}
