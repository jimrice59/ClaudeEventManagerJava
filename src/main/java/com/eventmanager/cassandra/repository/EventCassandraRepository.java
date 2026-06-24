package com.eventmanager.cassandra.repository;

import com.eventmanager.cassandra.model.CassandraEvent;
import org.springframework.data.cassandra.repository.CassandraRepository;

public interface EventCassandraRepository extends CassandraRepository<CassandraEvent, Long> {
}
