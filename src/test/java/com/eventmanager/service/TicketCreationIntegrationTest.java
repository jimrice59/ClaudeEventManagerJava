package com.eventmanager.service;

import com.eventmanager.model.Event;
import com.eventmanager.model.Ticket;
import com.eventmanager.model.Venue;
import com.eventmanager.repository.EventRepository;
import com.eventmanager.repository.TicketRepository;
import com.eventmanager.repository.VenueRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs createAvailableTickets against H2, so the native bulk UPDATE that writes
 * "<padded id> Open Seating" actually executes (the unit tests mock the repository). Rolled back.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TicketCreationIntegrationTest {

    @Autowired private TicketService ticketService;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private VenueRepository venueRepository;
    @Autowired private EntityManager entityManager;

    @Test
    void newTicketsGetTheirIdPaddedToTheEventsWidthFollowedByOpenSeating() {
        Venue venue = venueRepository.save(Venue.builder().name("Hall").address("1 Main St")
                .city("Springfield").state("IL").zipCode("62701").capacity(500).build());
        Event event = eventRepository.save(Event.builder().name("Width Test")
                .eventDate(LocalDateTime.of(2027, 1, 1, 20, 0)).ticketPrice(new BigDecimal("10.00"))
                .ticketsTotal(12).venue(venue).build());

        ticketService.createAvailableTickets(event, 12);
        entityManager.flush();
        entityManager.clear(); // reload from the database, not the stale in-memory tickets

        List<Ticket> tickets = ticketRepository.findByEventId(event.getId());
        long maxId = tickets.stream().mapToLong(Ticket::getId).max().orElseThrow();
        int width = String.valueOf(maxId).length();
        assertThat(tickets).hasSize(12).allSatisfy(t -> {
            assertThat(t.getDescription()).isEqualTo(String.format("%0" + width + "d Open Seating", t.getId()));
            assertThat(t.getDescription()).matches("\\d{" + width + "} Open Seating");
        });
    }
}
