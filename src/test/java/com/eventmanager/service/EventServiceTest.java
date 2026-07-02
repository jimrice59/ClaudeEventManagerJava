package com.eventmanager.service;

import com.eventmanager.cassandra.model.CassandraEvent;
import com.eventmanager.dto.EventRequest;
import com.eventmanager.dto.EventResponse;
import com.eventmanager.dto.VenueDto;
import com.eventmanager.exception.ResourceNotFoundException;
import com.eventmanager.model.Event;
import com.eventmanager.model.Venue;
import com.eventmanager.repository.EventRepository;
import com.eventmanager.repository.PerformerRepository;
import com.eventmanager.repository.VenueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    @Mock private EventRepository eventRepository;
    @Mock private VenueRepository venueRepository;
    @Mock private PerformerRepository performerRepository;
    @Mock private VenueService venueService;
    @Mock private PerformerService performerService;
    @Mock private CassandraAsyncWriter cassandraAsyncWriter;

    private EventService eventService;

    private Venue venue;
    private VenueDto venueDto;
    private Event event;

    private static final LocalDateTime EVENT_DATE = LocalDateTime.of(2025, 6, 15, 20, 0);

    @BeforeEach
    void setUp() {
        eventService = new EventService(
                eventRepository, venueRepository, performerRepository,
                venueService, performerService, cassandraAsyncWriter);

        venue = Venue.builder()
                .id(1L).name("Madison Square Garden").address("4 Pennsylvania Plaza")
                .city("New York").state("NY").zipCode("10001").capacity(20000)
                .build();

        venueDto = VenueDto.builder()
                .id(1L).name("Madison Square Garden").address("4 Pennsylvania Plaza")
                .city("New York").state("NY").zipCode("10001").capacity(20000)
                .build();

        event = Event.builder()
                .id(1L).name("Rock Concert").description("Epic show")
                .eventDate(EVENT_DATE)
                .ticketPrice(new BigDecimal("99.99"))
                .ticketsAvailable(100)
                .venue(venue)
                .performers(new HashSet<>())
                .build();
    }

    private EventRequest buildRequest() {
        EventRequest r = new EventRequest();
        r.setName("Rock Concert");
        r.setDescription("Epic show");
        r.setEventDate(EVENT_DATE);
        r.setTicketPrice(new BigDecimal("99.99"));
        r.setTicketsAvailable(100);
        r.setVenueId(1L);
        r.setPerformerIds(Set.of());
        return r;
    }

    // --- getAllEvents ---

    @Test
    void getAllEvents_returnsAllEvents() {
        when(eventRepository.findAllWithDetails()).thenReturn(List.of(event));
        when(venueService.toDto(venue)).thenReturn(venueDto);

        List<EventResponse> result = eventService.getAllEvents();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("Rock Concert");
        assertThat(result.get(0).getVenue().getName()).isEqualTo("Madison Square Garden");
    }

    @Test
    void getAllEvents_returnsEmptyList() {
        when(eventRepository.findAllWithDetails()).thenReturn(List.of());

        assertThat(eventService.getAllEvents()).isEmpty();
    }

    // --- getEventById ---

    @Test
    void getEventById_returnsEvent() {
        when(eventRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(event));
        when(venueService.toDto(venue)).thenReturn(venueDto);

        EventResponse result = eventService.getEventById(1L);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getName()).isEqualTo("Rock Concert");
        assertThat(result.getTicketPrice()).isEqualByComparingTo("99.99");
        assertThat(result.getTicketsAvailable()).isEqualTo(100);
        assertThat(result.getVenue().getId()).isEqualTo(1L);
    }

    @Test
    void getEventById_throwsWhenNotFound() {
        when(eventRepository.findByIdWithDetails(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> eventService.getEventById(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Event")
                .hasMessageContaining("99");
    }

    // --- getEventsByVenue ---

    @Test
    void getEventsByVenue_returnsMatchingEvents() {
        when(eventRepository.findByVenueId(1L)).thenReturn(List.of(event));
        when(venueService.toDto(venue)).thenReturn(venueDto);

        List<EventResponse> result = eventService.getEventsByVenue(1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getVenue().getId()).isEqualTo(1L);
    }

    @Test
    void getEventsByVenue_returnsEmptyWhenNoMatch() {
        when(eventRepository.findByVenueId(99L)).thenReturn(List.of());

        assertThat(eventService.getEventsByVenue(99L)).isEmpty();
    }

    // --- getEventsBetween ---

    @Test
    void getEventsBetween_returnsEventsInRange() {
        LocalDateTime start = LocalDateTime.of(2025, 6, 1, 0, 0);
        LocalDateTime end   = LocalDateTime.of(2025, 6, 30, 23, 59);
        when(eventRepository.findByEventDateBetween(start, end)).thenReturn(List.of(event));
        when(venueService.toDto(venue)).thenReturn(venueDto);

        List<EventResponse> result = eventService.getEventsBetween(start, end);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getEventDate()).isEqualTo(EVENT_DATE);
    }

    // --- createEvent ---

    @Test
    void createEvent_savesEventAndSchedulesCassandraWrite() {
        when(venueRepository.findById(1L)).thenReturn(Optional.of(venue));
        when(eventRepository.save(any(Event.class))).thenReturn(event);
        when(venueService.toDto(venue)).thenReturn(venueDto);

        EventResponse result = eventService.createEvent(buildRequest());

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getName()).isEqualTo("Rock Concert");
        verify(eventRepository).save(any(Event.class));
        verify(cassandraAsyncWriter).saveEvent(any(CassandraEvent.class));
    }

    @Test
    void createEvent_throwsWhenVenueNotFound() {
        when(venueRepository.findById(99L)).thenReturn(Optional.empty());
        EventRequest request = buildRequest();
        request.setVenueId(99L);

        assertThatThrownBy(() -> eventService.createEvent(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Venue")
                .hasMessageContaining("99");
        verify(eventRepository, never()).save(any());
        verifyNoInteractions(cassandraAsyncWriter);
    }

    @Test
    void createEvent_throwsWhenPerformerIdInvalid() {
        when(venueRepository.findById(1L)).thenReturn(Optional.of(venue));
        // request asks for performer 42, but repository returns nothing
        when(performerRepository.findAllById(Set.of(42L))).thenReturn(List.of());
        EventRequest request = buildRequest();
        request.setPerformerIds(Set.of(42L));

        assertThatThrownBy(() -> eventService.createEvent(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("performer");
        verify(eventRepository, never()).save(any());
    }

    // --- updateEvent ---

    @Test
    void updateEvent_updatesFieldsAndSchedulesCassandraWrite() {
        Event updated = Event.builder()
                .id(1L).name("Rock Concert Deluxe").description("Even more epic")
                .eventDate(EVENT_DATE).ticketPrice(new BigDecimal("149.99"))
                .ticketsAvailable(50).venue(venue).performers(new HashSet<>()).build();
        EventRequest request = buildRequest();
        request.setName("Rock Concert Deluxe");
        request.setTicketPrice(new BigDecimal("149.99"));
        request.setTicketsAvailable(50);

        when(eventRepository.findById(1L)).thenReturn(Optional.of(event));
        when(venueRepository.findById(1L)).thenReturn(Optional.of(venue));
        when(eventRepository.save(any(Event.class))).thenReturn(updated);
        when(venueService.toDto(venue)).thenReturn(venueDto);

        EventResponse result = eventService.updateEvent(1L, request);

        assertThat(result.getName()).isEqualTo("Rock Concert Deluxe");
        assertThat(result.getTicketPrice()).isEqualByComparingTo("149.99");
        assertThat(result.getTicketsAvailable()).isEqualTo(50);
        verify(cassandraAsyncWriter).saveEvent(any(CassandraEvent.class));
    }

    @Test
    void updateEvent_throwsWhenEventNotFound() {
        when(eventRepository.findById(99L)).thenReturn(Optional.empty());
        EventRequest request = buildRequest();

        assertThatThrownBy(() -> eventService.updateEvent(99L, request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Event")
                .hasMessageContaining("99");
        verify(eventRepository, never()).save(any());
        verifyNoInteractions(cassandraAsyncWriter);
    }

    @Test
    void updateEvent_throwsWhenVenueNotFound() {
        when(eventRepository.findById(1L)).thenReturn(Optional.of(event));
        when(venueRepository.findById(99L)).thenReturn(Optional.empty());
        EventRequest request = buildRequest();
        request.setVenueId(99L);

        assertThatThrownBy(() -> eventService.updateEvent(1L, request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Venue")
                .hasMessageContaining("99");
        verify(eventRepository, never()).save(any());
    }

    // --- reserveTickets ---

    @Test
    void reserveTickets_decrementsTicketsAvailable() {
        when(eventRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(event));
        when(eventRepository.save(event)).thenReturn(event);
        when(venueService.toDto(venue)).thenReturn(venueDto);

        EventResponse result = eventService.reserveTickets(1L, 10);

        assertThat(result.getTicketsAvailable()).isEqualTo(90);
        verify(cassandraAsyncWriter).saveEvent(any(CassandraEvent.class));
    }

    @Test
    void reserveTickets_throwsWhenInsufficientTickets() {
        event.setTicketsAvailable(5);
        when(eventRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> eventService.reserveTickets(1L, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5 available");
        verify(eventRepository, never()).save(any());
        verifyNoInteractions(cassandraAsyncWriter);
    }

    @Test
    void reserveTickets_throwsWhenEventNotFound() {
        when(eventRepository.findByIdWithDetails(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> eventService.reserveTickets(99L, 1))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Event")
                .hasMessageContaining("99");
    }

    // --- releaseTickets ---

    @Test
    void releaseTickets_incrementsTicketsAvailable() {
        event.setTicketsAvailable(90);  // 90/20000 available; releasing 5 → 95
        when(eventRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(event));
        when(eventRepository.save(event)).thenReturn(event);
        when(venueService.toDto(venue)).thenReturn(venueDto);

        EventResponse result = eventService.releaseTickets(1L, 5);

        assertThat(result.getTicketsAvailable()).isEqualTo(95);
        verify(cassandraAsyncWriter).saveEvent(any(CassandraEvent.class));
    }

    @Test
    void releaseTickets_throwsWhenWouldExceedVenueCapacity() {
        // venue capacity is 20000; releasing 1 from 20000 would exceed it
        event.setTicketsAvailable(20000);
        when(eventRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> eventService.releaseTickets(1L, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("venue capacity");
        verify(eventRepository, never()).save(any());
        verifyNoInteractions(cassandraAsyncWriter);
    }

    @Test
    void releaseTickets_throwsWhenEventNotFound() {
        when(eventRepository.findByIdWithDetails(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> eventService.releaseTickets(99L, 1))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Event")
                .hasMessageContaining("99");
    }

    // --- deleteEvent ---

    @Test
    void deleteEvent_deletesFromPostgresAndSchedulesCassandraDelete() {
        when(eventRepository.existsById(1L)).thenReturn(true);

        eventService.deleteEvent(1L);

        verify(eventRepository).deleteById(1L);
        verify(cassandraAsyncWriter).deleteEvent(1L);
    }

    @Test
    void deleteEvent_throwsWhenNotFound() {
        when(eventRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> eventService.deleteEvent(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Event")
                .hasMessageContaining("99");
        verify(eventRepository, never()).deleteById(any());
        verifyNoInteractions(cassandraAsyncWriter);
    }
}
