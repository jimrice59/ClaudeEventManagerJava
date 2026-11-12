package com.eventmanager.repository;

import com.eventmanager.model.Ticket;
import com.eventmanager.model.TicketStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    @Query("SELECT t FROM Ticket t JOIN FETCH t.event e JOIN FETCH e.venue WHERE t.id = :id")
    Optional<Ticket> findByIdWithDetails(@Param("id") Long id);

    /**
     * Loads a ticket with a row lock (SELECT ... FOR UPDATE) held until the caller's transaction
     * commits — for read-check-write transitions like TicketService#reserveTicket, where two
     * concurrent callers must not both see AVAILABLE. A second caller blocks on the lock, then
     * reads the first caller's committed status and fails the precondition check normally.
     * Deliberately no JOIN FETCH: the lock should cover only the ticket row, not its event/venue
     * (which would serialize every reservation for the same event). Event/venue load lazily
     * inside the same transaction when mapping to the response.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Ticket t WHERE t.id = :id")
    Optional<Ticket> findByIdForUpdate(@Param("id") Long id);

    /** All tickets for an event, regardless of status — used to back up + cascade-delete on event removal. */
    List<Ticket> findByEventId(Long eventId);

    /**
     * Picks and row-locks the lowest-id AVAILABLE ticket for an event, for
     * TicketService#reserveAvailableTicket. Must run inside a transaction: the lock is held until
     * commit. SKIP LOCKED makes concurrent callers each skip rows another transaction has already
     * locked, rather than all blocking on (and then fighting over) the same lowest-id ticket — so
     * N simultaneous requests reserve N different tickets. Empty when none are available.
     * Native because JPQL has no SKIP LOCKED; Postgres-specific (not exercised under H2).
     */
    @Query(value = "SELECT * FROM tickets WHERE event_id = :eventId AND status = 'AVAILABLE' " +
                   "ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED",
           nativeQuery = true)
    Optional<Ticket> lockNextAvailableTicket(@Param("eventId") Long eventId);

    /** Largest ticket id for an event, any status (null if it has none); see AvailableTicketsPage. */
    @Query("SELECT MAX(t.id) FROM Ticket t WHERE t.event.id = :eventId")
    Long findMaxIdByEventId(@Param("eventId") Long eventId);

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
