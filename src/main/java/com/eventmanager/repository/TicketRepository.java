package com.eventmanager.repository;

import com.eventmanager.model.Ticket;
import com.eventmanager.model.TicketStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    @Query("SELECT t FROM Ticket t JOIN FETCH t.event e JOIN FETCH e.venue WHERE t.id = :id")
    Optional<Ticket> findByIdWithDetails(@Param("id") Long id);

    /** All tickets for an event, regardless of status — used to back up + cascade-delete on event removal. */
    List<Ticket> findByEventId(Long eventId);

    /** Live count of tickets in a given status for an event — backs EventService/TicketService#getNumAvailableTickets. */
    long countByEventIdAndStatus(Long eventId, TicketStatus status);

    // Both joins are to-one associations (ticket->event, event->venue), so this is safe to
    // paginate directly in the database — no "in-memory pagination" warning like a collection
    // fetch (e.g. event.performers) would trigger. Performers are lazy-loaded per distinct
    // event while mapping to the response DTO, inside the same read-only transaction.
    @Query(value = "SELECT t FROM Ticket t JOIN FETCH t.event e JOIN FETCH e.venue " +
                   "WHERE t.event.id = :eventId AND t.status = :status",
           countQuery = "SELECT COUNT(t) FROM Ticket t WHERE t.event.id = :eventId AND t.status = :status")
  
           Page<Ticket> findByEventIdAndStatus(@Param("eventId") Long eventId,
                                         @Param("status") TicketStatus status,
                                         Pageable pageable);

    // Same fetch-join shape as findByEventIdAndStatus above — ticket->event and event->venue are
    // both to-one, so pagination is safe to push into the database.
    @Query(value = "SELECT t FROM Ticket t JOIN FETCH t.event e JOIN FETCH e.venue WHERE t.userId = :userId",
           countQuery = "SELECT COUNT(t) FROM Ticket t WHERE t.userId = :userId")
    Page<Ticket> findByUserId(@Param("userId") Long userId, Pageable pageable);
}
