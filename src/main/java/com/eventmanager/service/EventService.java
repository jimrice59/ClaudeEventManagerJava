package com.eventmanager.service;

import com.eventmanager.cassandra.model.CassandraEvent;
import com.eventmanager.dto.EventRequest;
import com.eventmanager.dto.EventResponse;
import com.eventmanager.exception.ResourceNotFoundException;
import com.eventmanager.model.Event;
import com.eventmanager.model.EventStatus;
import com.eventmanager.model.Performer;
import com.eventmanager.model.Venue;
import com.eventmanager.repository.EventRepository;
import com.eventmanager.repository.PerformerRepository;
import com.eventmanager.repository.VenueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;
    private final VenueRepository venueRepository;
    private final PerformerRepository performerRepository;
    private final VenueService venueService;
    private final PerformerService performerService;
    private final CassandraAsyncWriter cassandraAsyncWriter;
    private final TicketService ticketService;

    @Cacheable("allEvents")
    @Transactional(readOnly = true)
    public List<EventResponse> getAllEvents() {
        log.debug("Fetching all events");
        List<EventResponse> events = eventRepository.findAllWithDetails().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        log.debug("Found {} events", events.size());
        return events;
    }

    @Cacheable(value = "events", key = "#id")
    @Transactional(readOnly = true)
    public EventResponse getEventById(Long id) {
        log.debug("Fetching event with id={}", id);
        Event event = eventRepository.findByIdWithDetails(id)
                .orElseThrow(() -> {
                    log.warn("Event not found with id={}", id);
                    return new ResourceNotFoundException("Event", "id", id);
                });
        return toResponse(event);
    }

    @Cacheable(value = "eventsByVenue", key = "#venueId")
    @Transactional(readOnly = true)
    public List<EventResponse> getEventsByVenue(Long venueId) {
        log.debug("Fetching events for venue id={}", venueId);
        List<EventResponse> events = eventRepository.findByVenueId(venueId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        log.debug("Found {} events for venue id={}", events.size(), venueId);
        return events;
    }

    @Transactional(readOnly = true)
    public List<EventResponse> getEventsBetween(LocalDateTime start, LocalDateTime end) {
        log.debug("Fetching events between {} and {}", start, end);
        List<EventResponse> events = eventRepository.findByEventDateBetween(start, end).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        log.debug("Found {} events between {} and {}", events.size(), start, end);
        return events;
    }

    @CachePut(value = "events", key = "#result.id")
    @Transactional
    public EventResponse createEvent(EventRequest request) {
        log.info("Creating event name='{}' venueId={}", request.getName(), request.getVenueId());
        Venue venue = venueRepository.findById(request.getVenueId())
                .orElseThrow(() -> {
                    log.warn("Venue not found with id={}", request.getVenueId());
                    return new ResourceNotFoundException("Venue", "id", request.getVenueId());
                });

        if (request.getTicketsTotal() > venue.getCapacity()) {
            log.warn("Rejected event creation: ticketsTotal ({}) exceeds venue capacity ({}) for venue id={}",
                    request.getTicketsTotal(), venue.getCapacity(), venue.getId());
            throw new IllegalArgumentException(
                    "ticketsTotal (" + request.getTicketsTotal() + ") exceeds venue capacity ("
                            + venue.getCapacity() + ")");
        }

        Set<Performer> performers = resolvePerformers(request.getPerformerIds());

        Event event = Event.builder()
                .name(request.getName())
                .description(request.getDescription())
                .eventDate(request.getEventDate())
                .ticketPrice(request.getTicketPrice())
                .ticketsTotal(request.getTicketsTotal())
                .status(EventStatus.AVAILABLE)
                .venue(venue)
                .performers(performers)
                .build();

        Event savedEvent = eventRepository.save(event);

        // One AVAILABLE ticket per unit of the event's own ticketsTotal (validated above to be
        // <= venue capacity, so a partial house doesn't over-issue tickets), synchronously, in this
        // same transaction — not in Cassandra (tickets are only backed up there on event deletion).
        ticketService.createAvailableTickets(savedEvent, savedEvent.getTicketsTotal());

        EventResponse saved = toResponse(savedEvent);
        cassandraAsyncWriter.saveEvent(toCassandraEntity(saved));
        log.info("Created event id={} name='{}'", saved.getId(), saved.getName());
        return saved;
    }

    /** {@code ticketsTotal} is immutable (no setter on {@link Event}) — the request's value is ignored on update. */
    @CachePut(value = "events", key = "#id")
    @Transactional
    public EventResponse updateEvent(Long id, EventRequest request) {
        log.info("Updating event id={}", id);
        Event event = eventRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("Event not found with id={}", id);
                    return new ResourceNotFoundException("Event", "id", id);
                });

        Venue venue = venueRepository.findById(request.getVenueId())
                .orElseThrow(() -> {
                    log.warn("Venue not found with id={}", request.getVenueId());
                    return new ResourceNotFoundException("Venue", "id", request.getVenueId());
                });

        event.setName(request.getName());
        event.setDescription(request.getDescription());
        event.setEventDate(request.getEventDate());
        event.setTicketPrice(request.getTicketPrice());
        event.setVenue(venue);
        event.setPerformers(resolvePerformers(request.getPerformerIds()));

        EventResponse updated = toResponse(eventRepository.save(event));
        cassandraAsyncWriter.saveEvent(toCassandraEntity(updated));
        log.info("Updated event id={} name='{}'", updated.getId(), updated.getName());
        return updated;
    }

    /**
     * Live count of AVAILABLE tickets for the event — queries Postgres (via
     * {@link TicketService#getNumAvailableTickets}) rather than any stored counter, since
     * {@code ticketsTotal} is a fixed capacity, not a live count.
     */
    @Transactional(readOnly = true)
    public long getNumAvailableTickets(Long id) {
        log.debug("Fetching available ticket count for event id={}", id);
        return ticketService.getNumAvailableTickets(id);
    }

    /**
     * Marks the event DELETING, backs up and deletes all of its tickets (and deletes the Postgres
     * rows of any ticket operations logged against them — their Cassandra copies are left in
     * place), then removes the event itself from Postgres. All one transaction: if any step
     * fails, everything rolls back rather than leaving the event half-deleted. No
     * CassandraAsyncWriter.deleteEvent call — the event's last-synced copy is deliberately left
     * in Cassandra as an archive.
     */
    @CacheEvict(value = "events", key = "#id")
    @Transactional
    public void deleteEvent(Long id) {
        log.info("Deleting event id={}", id);
        Event event = eventRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("Event not found with id={}", id);
                    return new ResourceNotFoundException("Event", "id", id);
                });

        event.setStatus(EventStatus.DELETING);
        eventRepository.save(event);

        ticketService.backupAndDeleteAllForEvent(id);

        eventRepository.deleteById(id);
        log.info("Deleted event id={}", id);
    }

    private CassandraEvent toCassandraEntity(EventResponse response) {
        return CassandraEvent.builder()
                .id(response.getId())
                .name(response.getName())
                .description(response.getDescription())
                .eventDate(response.getEventDate())
                .ticketPrice(response.getTicketPrice())
                .ticketsTotal(response.getTicketsTotal())
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
                .ticketsTotal(event.getTicketsTotal())
                .status(event.getStatus())
                .venue(venueService.toDto(event.getVenue()))
                .performers(event.getPerformers().stream()
                        .map(performerService::toDto)
                        .collect(Collectors.toSet()))
                .createdAt(event.getCreatedAt())
                .updatedAt(event.getUpdatedAt())
                .build();
    }
}
