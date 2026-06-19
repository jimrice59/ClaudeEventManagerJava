package com.eventmanager.service;

import com.eventmanager.dto.VenueDto;
import com.eventmanager.exception.ResourceNotFoundException;
import com.eventmanager.model.Venue;
import com.eventmanager.repository.VenueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class VenueService {

    private final VenueRepository venueRepository;

    @Transactional(readOnly = true)
    public List<VenueDto> getAllVenues() {
        return venueRepository.findAll().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Cacheable(value = "venues", key = "#id")
    @Transactional(readOnly = true)
    public VenueDto getVenueById(Long id) {
        return venueRepository.findById(id)
                .map(this::toDto)
                .orElseThrow(() -> new ResourceNotFoundException("Venue", "id", id));
    }

    @Transactional(readOnly = true)
    public List<VenueDto> getVenuesByCity(String city) {
        return venueRepository.findByCityIgnoreCase(city).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @CachePut(value = "venues", key = "#result.id")
    @Transactional
    public VenueDto createVenue(VenueDto dto) {
        Venue venue = toEntity(dto);
        return toDto(venueRepository.save(venue));
    }

    @CachePut(value = "venues", key = "#id")
    @Transactional
    public VenueDto updateVenue(Long id, VenueDto dto) {
        Venue venue = venueRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Venue", "id", id));
        venue.setName(dto.getName());
        venue.setAddress(dto.getAddress());
        venue.setCity(dto.getCity());
        venue.setState(dto.getState());
        venue.setZipCode(dto.getZipCode());
        venue.setCapacity(dto.getCapacity());
        return toDto(venueRepository.save(venue));
    }

    @CacheEvict(value = "venues", key = "#id")
    @Transactional
    public void deleteVenue(Long id) {
        if (!venueRepository.existsById(id)) {
            throw new ResourceNotFoundException("Venue", "id", id);
        }
        venueRepository.deleteById(id);
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
