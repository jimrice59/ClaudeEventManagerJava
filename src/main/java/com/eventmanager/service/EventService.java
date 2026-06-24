package com.eventmanager.service;

import com.eventmanager.cassandra.model.CassandraEvent;
import com.eventmanager.dto.EventRequest;
import com.eventmanager.dto.EventResponse;
import com.eventmanager.exception.ResourceNotFoundException;
import com.eventmanager.model.Event;
import com.eventmanager.model.Performer;
import com.eventmanager.model.Venue;
import com.eventmanager.repository.EventRepository;
import com.eventmanager.repository.PerformerRepository;
import com.eventmanager.repository.VenueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;
    private final VenueRepository venueRepository;
    private final PerformerRepository performerRepository;
    private final VenueService venueService;
    private final PerformerService performerService;
    private final CassandraAsyncWriter cassandraAsyncWriter;

    @Transactional(readOnly = true)
    public List<EventResponse> getAllEvents() {
        return eventRepository.findAllWithDetails().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Cacheable(value = "events", key = "#id")
    @Transactional(readOnly = true)
    public EventResponse getEventById(Long id) {
        Event event = eventRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Event", "id", id));
        return toResponse(event);
    }

    @Transactional(readOnly = true)
    public List<EventResponse> getEventsByVenue(Long venueId) {
        return eventRepository.findByVenueId(venueId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<EventResponse> getEventsBetween(LocalDateTime start, LocalDateTime end) {
        return eventRepository.findByEventDateBetween(start, end).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @CachePut(value = "events", key = "#result.id")
    @Transactional
    public EventResponse createEvent(EventRequest request) {
        Venue venue = venueRepository.findById(request.getVenueId())
                .orElseThrow(() -> new ResourceNotFoundException("Venue", "id", request.getVenueId()));

        Set<Performer> performers = resolvePerformers(request.getPerformerIds());

        Event event = Event.builder()
                .name(request.getName())
                .description(request.getDescription())
                .eventDate(request.getEventDate())
                .ticketPrice(request.getTicketPrice())
                .ticketsAvailable(request.getTicketsAvailable())
                .venue(venue)
                .performers(performers)
                .build();

        EventResponse saved = toResponse(eventRepository.save(event));
        cassandraAsyncWriter.saveEvent(toCassandraEntity(saved));
        return saved;
    }

    @CachePut(value = "events", key = "#id")
    @Transactional
    public EventResponse updateEvent(Long id, EventRequest request) {
        Event event = eventRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Event", "id", id));

        Venue venue = venueRepository.findById(request.getVenueId())
                .orElseThrow(() -> new ResourceNotFoundException("Venue", "id", request.getVenueId()));

        event.setName(request.getName());
        event.setDescription(request.getDescription());
        event.setEventDate(request.getEventDate());
        event.setTicketPrice(request.getTicketPrice());
        event.setTicketsAvailable(request.getTicketsAvailable());
        event.setVenue(venue);
        event.setPerformers(resolvePerformers(request.getPerformerIds()));

        EventResponse updated = toResponse(eventRepository.save(event));
        cassandraAsyncWriter.saveEvent(toCassandraEntity(updated));
        return updated;
    }

    @CachePut(value = "events", key = "#id")
    @Transactional
    public EventResponse reserveTickets(Long id, int count) {
        Event event = eventRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Event", "id", id));
        int available = event.getTicketsAvailable();
        if (available - count < 0) {
            throw new IllegalArgumentException(
                    "Cannot reserve " + count + " tickets; only " + available + " available");
        }
        event.setTicketsAvailable(available - count);
        EventResponse updated = toResponse(eventRepository.save(event));
        cassandraAsyncWriter.saveEvent(toCassandraEntity(updated));
        return updated;
    }

    @CachePut(value = "events", key = "#id")
    @Transactional
    public EventResponse releaseTickets(Long id, int count) {
        Event event = eventRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Event", "id", id));
        int capacity = event.getVenue().getCapacity();
        if (event.getTicketsAvailable() + count > capacity) {
            throw new IllegalArgumentException(
                    "Cannot release " + count + " tickets; would exceed venue capacity of " + capacity);
        }
        event.setTicketsAvailable(event.getTicketsAvailable() + count);
        EventResponse updated = toResponse(eventRepository.save(event));
        cassandraAsyncWriter.saveEvent(toCassandraEntity(updated));
        return updated;
    }

    @CacheEvict(value = "events", key = "#id")
    @Transactional
    public void deleteEvent(Long id) {
        if (!eventRepository.existsById(id)) {
            throw new ResourceNotFoundException("Event", "id", id);
        }
        eventRepository.deleteById(id);
        cassandraAsyncWriter.deleteEvent(id);
    }

    private CassandraEvent toCassandraEntity(EventResponse response) {
        return CassandraEvent.builder()
                .id(response.getId())
                .name(response.getName())
                .description(response.getDescription())
                .eventDate(response.getEventDate())
                .ticketPrice(response.getTicketPrice())
                .ticketsAvailable(response.getTicketsAvailable())
                .venueId(response.getVenue().getId())
                .createdAt(response.getCreatedAt())
                .updatedAt(response.getUpdatedAt())
                .build();
    }

    private Set<Performer> resolvePerformers(Set<Long> performerIds) {
        if (performerIds == null || performerIds.isEmpty()) {
            return new HashSet<>();
        }
        Set<Performer> performers = new HashSet<>(performerRepository.findAllById(performerIds));
        if (performers.size() != performerIds.size()) {
            throw new IllegalArgumentException("One or more performer IDs are invalid");
        }
        return performers;
    }

    private EventResponse toResponse(Event event) {
        return EventResponse.builder()
                .id(event.getId())
                .name(event.getName())
                .description(event.getDescription())
                .eventDate(event.getEventDate())
                .ticketPrice(event.getTicketPrice())
                .ticketsAvailable(event.getTicketsAvailable())
                .venue(venueService.toDto(event.getVenue()))
                .performers(event.getPerformers().stream()
                        .map(performerService::toDto)
                        .collect(Collectors.toSet()))
                .createdAt(event.getCreatedAt())
                .updatedAt(event.getUpdatedAt())
                .build();
    }
}
