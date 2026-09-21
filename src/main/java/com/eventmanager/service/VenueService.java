package com.eventmanager.service;

import com.eventmanager.dto.VenueDto;
import com.eventmanager.exception.ResourceNotFoundException;
import com.eventmanager.model.Venue;
import com.eventmanager.repository.VenueRepository;
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
public class VenueService {

    private final VenueRepository venueRepository;

    @Transactional(readOnly = true)
    public List<VenueDto> getAllVenues() {
        log.debug("Fetching all venues");
        List<VenueDto> venues = venueRepository.findAll().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
        log.debug("Found {} venues", venues.size());
        return venues;
    }

    @Cacheable(value = "venues", key = "#id")
    @Transactional(readOnly = true)
    public VenueDto getVenueById(Long id) {
        log.debug("Fetching venue with id={}", id);
        return venueRepository.findById(id)
                .map(this::toDto)
                .orElseThrow(() -> {
                    log.warn("Venue not found with id={}", id);
                    return new ResourceNotFoundException("Venue", "id", id);
                });
    }

    @Cacheable(value = "venuesByCity", key = "#city")
    @Transactional(readOnly = true)
    public List<VenueDto> getVenuesByCity(String city) {
        log.debug("Fetching venues by city='{}'", city);
        List<VenueDto> results = venueRepository.findByCityIgnoreCase(city).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
        log.debug("Found {} venues in city='{}'", results.size(), city);
        return results;
    }

    @CachePut(value = "venues", key = "#result.id")
    @Transactional
    public VenueDto createVenue(VenueDto dto) {
        log.info("Creating venue name='{}'", dto.getName());
        Venue venue = toEntity(dto);
        VenueDto saved = toDto(venueRepository.save(venue));
        log.info("Created venue id={} name='{}'", saved.getId(), saved.getName());
        return saved;
    }

    @CachePut(value = "venues", key = "#id")
    @Transactional
    public VenueDto updateVenue(Long id, VenueDto dto) {
        log.info("Updating venue id={}", id);
        Venue venue = venueRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("Venue not found with id={}", id);
                    return new ResourceNotFoundException("Venue", "id", id);
                });
        venue.setName(dto.getName());
        venue.setAddress(dto.getAddress());
        venue.setCity(dto.getCity());
        venue.setState(dto.getState());
        venue.setZipCode(dto.getZipCode());
        venue.setCapacity(dto.getCapacity());
        VenueDto saved = toDto(venueRepository.save(venue));
        log.info("Updated venue id={} name='{}'", saved.getId(), saved.getName());
        return saved;
    }

    @CacheEvict(value = "venues", key = "#id")
    @Transactional
    public void deleteVenue(Long id) {
        log.info("Deleting venue id={}", id);
        if (!venueRepository.existsById(id)) {
            log.warn("Venue not found with id={}", id);
            throw new ResourceNotFoundException("Venue", "id", id);
        }
        venueRepository.deleteById(id);
        log.info("Deleted venue id={}", id);
    }

    public VenueDto toDto(Venue venue) {
        return VenueDto.builder()
                .id(venue.getId())
                .name(venue.getName())
                .address(venue.getAddress())
                .city(venue.getCity())
                .state(venue.getState())
                .zipCode(venue.getZipCode())
                .capacity(venue.getCapacity())
                .build();
    }

    private Venue toEntity(VenueDto dto) {
        return Venue.builder()
                .name(dto.getName())
                .address(dto.getAddress())
                .city(dto.getCity())
                .state(dto.getState())
                .zipCode(dto.getZipCode())
                .capacity(dto.getCapacity())
                .build();
    }
}
