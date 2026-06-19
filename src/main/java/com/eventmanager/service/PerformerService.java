package com.eventmanager.service;

import com.eventmanager.cassandra.model.CassandraPerformer;
import com.eventmanager.cassandra.repository.PerformerCassandraRepository;
import com.eventmanager.dto.PerformerDto;
import com.eventmanager.exception.ResourceNotFoundException;
import com.eventmanager.model.Performer;
import com.eventmanager.repository.PerformerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PerformerService {

    private final PerformerRepository performerRepository;

    @Autowired(required = false)
    private PerformerCassandraRepository cassandraRepository;

    @Transactional(readOnly = true)
    public List<PerformerDto> getAllPerformers() {
        log.debug("Fetching all performers");
        List<PerformerDto> performers = performerRepository.findAll().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
        log.debug("Found {} performers", performers.size());
        return performers;
    }

    @Cacheable(value = "performers", key = "#id")
    @Transactional(readOnly = true)
    public PerformerDto getPerformerById(Long id) {
        log.debug("Fetching performer with id={}", id);
        return performerRepository.findById(id)
                .map(this::toDto)
                .orElseThrow(() -> {
                    log.warn("Performer not found with id={}", id);
                    return new ResourceNotFoundException("Performer", "id", id);
                });
    }

    @Transactional(readOnly = true)
    public List<PerformerDto> searchPerformers(String name) {
        log.debug("Searching performers by name='{}'", name);
        List<PerformerDto> results = performerRepository.findByNameContainingIgnoreCase(name).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
        log.debug("Found {} performers matching name='{}'", results.size(), name);
        return results;
    }

    @Transactional(readOnly = true)
    public List<PerformerDto> getPerformersByGenre(String genre) {
        log.debug("Fetching performers by genre='{}'", genre);
        List<PerformerDto> results = performerRepository.findByGenreIgnoreCase(genre).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
        log.debug("Found {} performers with genre='{}'", results.size(), genre);
        return results;
    }

    @CachePut(value = "performers", key = "#result.id")
    @Transactional
    public PerformerDto createPerformer(PerformerDto dto) {
        log.info("Creating performer name='{}'", dto.getName());
        Performer performer = toEntity(dto);
        PerformerDto saved = toDto(performerRepository.save(performer));
        log.info("Created performer id={} name='{}'", saved.getId(), saved.getName());
        if (cassandraRepository != null) {
            cassandraRepository.save(toCassandraEntity(saved));
            log.debug("Synced performer id={} to Cassandra", saved.getId());
        }
        return saved;
    }

    @CachePut(value = "performers", key = "#id")
    @Transactional
    public PerformerDto updatePerformer(Long id, PerformerDto dto) {
        log.info("Updating performer id={}", id);
        Performer performer = performerRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("Performer not found with id={}", id);
                    return new ResourceNotFoundException("Performer", "id", id);
                });
        performer.setName(dto.getName());
        performer.setGenre(dto.getGenre());
        performer.setBio(dto.getBio());
        PerformerDto saved = toDto(performerRepository.save(performer));
        log.info("Updated performer id={} name='{}'", saved.getId(), saved.getName());
        if (cassandraRepository != null) {
            cassandraRepository.save(toCassandraEntity(saved));
            log.debug("Synced performer id={} to Cassandra", saved.getId());
        }
        return saved;
    }

    @CacheEvict(value = "performers", key = "#id")
    @Transactional
    public void deletePerformer(Long id) {
        log.info("Deleting performer id={}", id);
        if (!performerRepository.existsById(id)) {
            log.warn("Performer not found with id={}", id);
            throw new ResourceNotFoundException("Performer", "id", id);
        }
        performerRepository.deleteById(id);
        if (cassandraRepository != null) {
            cassandraRepository.deleteById(id);
            log.debug("Deleted performer id={} from Cassandra", id);
        }
        log.info("Deleted performer id={}", id);
    }

    public PerformerDto toDto(Performer performer) {
        return PerformerDto.builder()
                .id(performer.getId())
                .name(performer.getName())
                .genre(performer.getGenre())
                .bio(performer.getBio())
                .build();
    }

    private Performer toEntity(PerformerDto dto) {
        return Performer.builder()
                .name(dto.getName())
                .genre(dto.getGenre())
                .bio(dto.getBio())
                .build();
    }

    private CassandraPerformer toCassandraEntity(PerformerDto dto) {
        return CassandraPerformer.builder()
                .id(dto.getId())
                .name(dto.getName())
                .genre(dto.getGenre())
                .bio(dto.getBio())
                .build();
    }
}
