package com.eventmanager.cassandra.repository;

import com.eventmanager.cassandra.model.CassandraTicket;
import org.springframework.data.cassandra.repository.CassandraRepository;

public interface TicketCassandraRepository extends CassandraRepository<CassandraTicket, Long> {
}
