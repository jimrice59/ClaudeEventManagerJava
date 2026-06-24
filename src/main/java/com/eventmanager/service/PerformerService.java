package com.eventmanager.service;

import com.eventmanager.cassandra.model.CassandraPerformer;
import com.eventmanager.dto.PerformerDto;
import com.eventmanager.exception.ResourceNotFoundException;
import com.eventmanager.kafka.PerformerVideoEventPublisher;
import com.eventmanager.kafka.VideoEvent;
import com.eventmanager.model.Performer;
import com.eventmanager.model.Video;
import com.eventmanager.repository.PerformerRepository;
import com.eventmanager.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final VideoRepository videoRepository;
    private final CassandraAsyncWriter cassandraAsyncWriter;
    private final PerformerVideoEventPublisher videoEventPublisher;

    @Transactional(readOnly = true)
    public List<PerformerDto> getAllPerformers() {
        log.debug("Fetching all performers");
        List<PerformerDto> performers = performerRepository.findAllWithVideos().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
        log.debug("Found {} performers", performers.size());
        return performers;
    }

    @Cacheable(value = "performers", key = "#id")
    @Transactional(readOnly = true)
    public PerformerDto getPerformerById(Long id) {
        log.debug("Fetching performer with id={}", id);
        return performerRepository.findByIdWithVideos(id)
                .map(this::toDto)
                .orElseThrow(() -> {
                    log.warn("Performer not found with id={}", id);
                    return new ResourceNotFoundException("Performer", "id", id);
                });
    }

    @Transactional(readOnly = true)
    public List<PerformerDto> searchPerformers(String name) {
        log.debug("Searching performers by name='{}'", name);
        List<PerformerDto> results = performerRepository.findByNameContainingIgnoreCaseWithVideos(name).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
        log.debug("Found {} performers matching name='{}'", results.size(), name);
        return results;
    }

    @Transactional(readOnly = true)
    public List<PerformerDto> getPerformersByGenre(String genre) {
        log.debug("Fetching performers by genre='{}'", genre);
        List<PerformerDto> results = performerRepository.findByGenreIgnoreCaseWithVideos(genre).stream()
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
        cassandraAsyncWriter.savePerformer(toCassandraEntity(saved));
        return saved;
    }

    @CachePut(value = "performers", key = "#id")
    @Transactional
    public PerformerDto updatePerformer(Long id, PerformerDto dto) {
        log.info("Updating performer id={}", id);
        Performer performer = performerRepository.findByIdWithVideos(id)
                .orElseThrow(() -> {
                    log.warn("Performer not found with id={}", id);
                    return new ResourceNotFoundException("Performer", "id", id);
                });
        performer.setName(dto.getName());
        performer.setGenre(dto.getGenre());
        performer.setBio(dto.getBio());
        PerformerDto saved = toDto(performerRepository.save(performer));
        log.info("Updated performer id={} name='{}'", saved.getId(), saved.getName());
        cassandraAsyncWriter.savePerformer(toCassandraEntity(saved));
        return saved;
    }

    @CachePut(value = "performers", key = "#performerId")
    @Transactional
    public PerformerDto addVideo(Long performerId, String url) {
        log.info("Adding video to performer id={}", performerId);
        Performer performer = performerRepository.findByIdWithVideos(performerId)
                .orElseThrow(() -> new ResourceNotFoundException("Performer", "id", performerId));
        Video video = videoRepository.findByUrl(url)
                .orElseGet(() -> videoRepository.save(Video.builder().url(url).build()));
        performer.getVideos().add(video);
        PerformerDto saved = toDto(performerRepository.save(performer));
        cassandraAsyncWriter.savePerformer(toCassandraEntity(saved));
        videoEventPublisher.publish(new VideoEvent("ADD", performerId, video.getId()));
        return saved;
    }

    @CachePut(value = "performers", key = "#performerId")
    @Transactional
    public PerformerDto deleteVideo(Long performerId, String url) {
        log.info("Removing video from performer id={}", performerId);
        Performer performer = performerRepository.findByIdWithVideos(performerId)
                .orElseThrow(() -> new ResourceNotFoundException("Performer", "id", performerId));
        Video toRemove = performer.getVideos().stream()
                .filter(v -> v.getUrl().equals(url))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Video", "url", url));
        Long videoId = toRemove.getId();
        performer.getVideos().remove(toRemove);
        PerformerDto saved = toDto(performerRepository.save(performer));
        cassandraAsyncWriter.savePerformer(toCassandraEntity(saved));
        videoEventPublisher.publish(new VideoEvent("DELETE", performerId, videoId));
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
        cassandraAsyncWriter.deletePerformer(id);
        log.info("Deleted performer id={}", id);
    }

    public PerformerDto toDto(Performer performer) {
        return PerformerDto.builder()
                .id(performer.getId())
                .name(performer.getName())
                .genre(performer.getGenre())
                .bio(performer.getBio())
                .videoUrls(performer.getVideos().stream()
                        .map(Video::getUrl)
                        .collect(Collectors.toSet()))
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
                .videoUrls(dto.getVideoUrls())
                .build();
    }
}
