package com.eventmanager.service;

import com.eventmanager.dto.EventResponse;
import com.eventmanager.dto.PerformerDto;
import com.eventmanager.model.Event;
import com.eventmanager.model.Performer;
import com.eventmanager.model.Venue;
import com.eventmanager.repository.EventRepository;
import com.eventmanager.repository.PerformerRepository;
import com.eventmanager.repository.VenueRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs EventService#searchEvents' real JPA Specification query against H2 (the unit tests mock
 * the repository, so they can't show that the predicates combine correctly). Each test rolls
 * back. The persistence context is cleared after seeding, so results come from the database,
 * not from the in-memory entities built here — which is what makes the "performers collection
 * not truncated by the performer filter" assertion meaningful.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EventSearchIntegrationTest {

    @Autowired private EventService eventService;
    @Autowired private EventRepository eventRepository;
    @Autowired private VenueRepository venueRepository;
    @Autowired private PerformerRepository performerRepository;
    @Autowired private EntityManager entityManager;

    private Venue arena;
    private Venue club;
    private Performer rockBand;
    private Performer jazzTrio;
    private Event arenaRockJune;   // arena, rock + jazz, June 10
    private Event arenaJazzJuly;   // arena, jazz,        July 5
    private Event clubRockJuly;    // club,  rock,        July 20

    @BeforeEach
    void seed() {
        arena = venueRepository.save(Venue.builder().name("Arena").address("1 Main St")
                .city("Springfield").state("IL").zipCode("62701").capacity(10000).build());
        club = venueRepository.save(Venue.builder().name("Club").address("2 Side St")
                .city("Springfield").state("IL").zipCode("62702").capacity(300).build());
        rockBand = performerRepository.save(Performer.builder().name("Rock Band").genre("Rock").build());
        jazzTrio = performerRepository.save(Performer.builder().name("Jazz Trio").genre("Jazz").build());

        arenaRockJune = event("Arena Rock June", arena, LocalDateTime.of(2026, 6, 10, 20, 0), rockBand, jazzTrio);
        arenaJazzJuly = event("Arena Jazz July", arena, LocalDateTime.of(2026, 7, 5, 20, 0), jazzTrio);
        clubRockJuly = event("Club Rock July", club, LocalDateTime.of(2026, 7, 20, 21, 0), rockBand);

        entityManager.flush();
        entityManager.clear();
    }

    private Event event(String name, Venue venue, LocalDateTime date, Performer... performers) {
        return eventRepository.save(Event.builder().name(name).venue(venue).eventDate(date)
                .ticketPrice(new BigDecimal("25.00")).ticketsTotal(100)
                .performers(new java.util.HashSet<>(Set.of(performers))).build());
    }

    private List<String> search(Long venueId, Long performerId, LocalDateTime start, LocalDateTime end) {
        return eventService.searchEvents(new EventSearchCriteria(venueId, performerId, start, end))
                .stream().map(EventResponse::getName).toList();
    }

    @Test
    void filtersByPerformerAlone() {
        assertThat(search(null, rockBand.getId(), null, null))
                .containsExactly("Arena Rock June", "Club Rock July");
    }

    @Test
    void performerFilterDoesNotTruncateEachEventsPerformerList() {
        EventResponse arenaRock = eventService
                .searchEvents(new EventSearchCriteria(null, rockBand.getId(), null, null)).get(0);

        assertThat(arenaRock.getName()).isEqualTo("Arena Rock June");
        assertThat(arenaRock.getPerformers()).extracting(PerformerDto::getName)
                .containsExactlyInAnyOrder("Rock Band", "Jazz Trio");
    }

    @Test
    void combinesVenueAndPerformer() {
        assertThat(search(arena.getId(), rockBand.getId(), null, null)).containsExactly("Arena Rock June");
        assertThat(search(club.getId(), jazzTrio.getId(), null, null)).isEmpty();
    }

    @Test
    void combinesVenueWithDateRange() {
        // Previously venueId silently won and the dates were ignored.
        assertThat(search(arena.getId(), null, LocalDateTime.of(2026, 7, 1, 0, 0), LocalDateTime.of(2026, 7, 31, 23, 59)))
                .containsExactly("Arena Jazz July");
    }

    @Test
    void combinesAllFour() {
        assertThat(search(arena.getId(), jazzTrio.getId(), LocalDateTime.of(2026, 6, 1, 0, 0), LocalDateTime.of(2026, 6, 30, 23, 59)))
                .containsExactly("Arena Rock June");
    }

    @Test
    void oneSidedBoundsEachFilterOnTheirOwn() {
        // Previously a lone start or end was silently ignored (all events returned).
        assertThat(search(null, null, LocalDateTime.of(2026, 7, 1, 0, 0), null))
                .containsExactly("Arena Jazz July", "Club Rock July");
        assertThat(search(null, null, null, LocalDateTime.of(2026, 7, 1, 0, 0)))
                .containsExactly("Arena Rock June");
    }

    @Test
    void boundsAreInclusive() {
        LocalDateTime exactly = arenaJazzJuly.getEventDate();
        assertThat(search(null, null, exactly, exactly)).containsExactly("Arena Jazz July");
    }

    @Test
    void resultsAreOrderedByEventDate() {
        assertThat(search(null, null, LocalDateTime.of(2026, 1, 1, 0, 0), null))
                .containsExactly("Arena Rock June", "Arena Jazz July", "Club Rock July");
    }

    @Test
    void unknownIdsMatchNothingRatherThanFailing() {
        assertThat(search(999_999L, null, null, null)).isEmpty();
        assertThat(search(null, 999_999L, null, null)).isEmpty();
    }
}
