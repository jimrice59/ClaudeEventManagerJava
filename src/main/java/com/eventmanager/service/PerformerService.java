package com.eventmanager.service;

import com.eventmanager.cassandra.model.CassandraPerformer;
import com.eventmanager.cassandra.repository.PerformerCassandraRepository;
import com.eventmanager.dto.PerformerDto;
import com.eventmanager.exception.ResourceNotFoundException;
import com.eventmanager.model.Performer;
import com.eventmanager.repository.PerformerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PerformerService {

    private final PerformerRepository performerRepository;

    @Autowired(required = false)
    private PerformerCassandraRepository cassandraRepository;

    @Transactional(readOnly = true)
    public List<PerformerDto> getAllPerformers() {
        return performerRepository.findAll().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Cacheable(value = "performers", key = "#id")
    @Transactional(readOnly = true)
    public PerformerDto getPerformerById(Long id) {
        return performerRepository.findById(id)
                .map(this::toDto)
                .orElseThrow(() -> new ResourceNotFoundException("Performer", "id", id));
    }

    @Transactional(readOnly = true)
    public List<PerformerDto> searchPerformers(String name) {
        return performerRepository.findByNameContainingIgnoreCase(name).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<PerformerDto> getPerformersByGenre(String genre) {
        return performerRepository.findByGenreIgnoreCase(genre).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @CachePut(value = "performers", key = "#result.id")
    @Transactional
    public PerformerDto createPerformer(PerformerDto dto) {
        Performer performer = toEntity(dto);
        PerformerDto saved = toDto(performerRepository.save(performer));
        if (cassandraRepository != null) {
            cassandraRepository.save(toCassandraEntity(saved));
        }
        return saved;
    }

    @CachePut(value = "performers", key = "#id")
    @Transactional
    public PerformerDto updatePerformer(Long id, PerformerDto dto) {
        Performer performer = performerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Performer", "id", id));
        performer.setName(dto.getName());
        performer.setGenre(dto.getGenre());
        performer.setBio(dto.getBio());
        PerformerDto saved = toDto(performerRepository.save(performer));
        if (cassandraRepository != null) {
            cassandraRepository.save(toCassandraEntity(saved));
        }
        return saved;
    }

    @CacheEvict(value = "performers", key = "#id")
    @Transactional
    public void deletePerformer(Long id) {
        if (!performerRepository.existsById(id)) {
            throw new ResourceNotFoundException("Performer", "id", id);
        }
        performerRepository.deleteById(id);
        if (cassandraRepository != null) {
            cassandraRepository.deleteById(id);
        }
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
