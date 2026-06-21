package com.eventmanager.service;

import com.eventmanager.cassandra.model.CassandraPerformer;
import com.eventmanager.cassandra.repository.PerformerCassandraRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class CassandraAsyncWriter {

    @Autowired(required = false)
    private PerformerCassandraRepository cassandraRepository;

    @Async("cassandraExecutor")
    public void savePerformer(CassandraPerformer performer) {
        if (cassandraRepository == null) return;
        try {
            cassandraRepository.save(performer);
            log.debug("Async Cassandra save completed for performer id={}", performer.getId());
        } catch (Exception e) {
            log.error("Async Cassandra save failed for performer id={}", performer.getId(), e);
        }
    }

    @Async("cassandraExecutor")
    public void deletePerformer(Long id) {
        if (cassandraRepository == null) return;
        try {
            cassandraRepository.deleteById(id);
            log.debug("Async Cassandra delete completed for performer id={}", id);
        } catch (Exception e) {
            log.error("Async Cassandra delete failed for performer id={}", id, e);
        }
    }
}
