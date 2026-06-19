package com.eventmanager.cassandra.repository;

import com.eventmanager.cassandra.model.CassandraPerformer;
import org.springframework.data.cassandra.repository.CassandraRepository;

public interface PerformerCassandraRepository extends CassandraRepository<CassandraPerformer, Long> {
}
