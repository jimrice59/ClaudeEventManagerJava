package com.eventmanager.service;

import com.eventmanager.cassandra.model.CassandraEvent;
import com.eventmanager.cassandra.model.CassandraPerformer;
import com.eventmanager.cassandra.repository.EventCassandraRepository;
import com.eventmanager.cassandra.repository.PerformerCassandraRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class CassandraAsyncWriter {

    @Autowired(required = false)
    private PerformerCassandraRepository performerCassandraRepository;

    @Autowired(required = false)
    private EventCassandraRepository eventCassandraRepository;

    @Async("cassandraExecutor")
    public void savePerformer(CassandraPerformer performer) {
        if (performerCassandraRepository == null) return;
        try {
            performerCassandraRepository.save(performer);
            log.debug("Async Cassandra save completed for performer id={}", performer.getId());
        } catch (Exception e) {
            log.error("Async Cassandra save failed for performer id={}", performer.getId(), e);
        }
    }

    @Async("cassandraExecutor")
    public void deletePerformer(Long id) {
        if (performerCassandraRepository == null) return;
        try {
            performerCassandraRepository.deleteById(id);
            log.debug("Async Cassandra delete completed for performer id={}", id);
        } catch (Exception e) {
            log.error("Async Cassandra delete failed for performer id={}", id, e);
        }
    }

    @Async("cassandraExecutor")
    public void saveEvent(CassandraEvent event) {
        if (eventCassandraRepository == null) return;
        try {
            eventCassandraRepository.save(event);
            log.debug("Async Cassandra save completed for event id={}", event.getId());
        } catch (Exception e) {
            log.error("Async Cassandra save failed for event id={}", event.getId(), e);
        }
    }

    @Async("cassandraExecutor")
    public void deleteEvent(Long id) {
        if (eventCassandraRepository == null) return;
        try {
            eventCassandraRepository.deleteById(id);
            log.debug("Async Cassandra delete completed for event id={}", id);
        } catch (Exception e) {
            log.error("Async Cassandra delete failed for event id={}", id, e);
        }
    }
}
