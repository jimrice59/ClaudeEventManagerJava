package com.eventmanager.repository;

import com.eventmanager.model.Ticket;
import com.eventmanager.model.TicketStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
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
     * An event's AVAILABLE tickets excluding the given (currently held) ids, by ticket id. "Available
     * to reserve" = AVAILABLE in Postgres and not held in Redis (see TicketHoldService). Callers pass
     * a sentinel {-1} rather than an empty collection, so NOT IN never gets an empty list.
     */
    @Query(value = "SELECT t FROM Ticket t JOIN FETCH t.event e JOIN FETCH e.venue " +
                   "WHERE e.id = :eventId AND t.status = com.eventmanager.model.TicketStatus.AVAILABLE AND t.id NOT IN :heldIds",
           countQuery = "SELECT COUNT(t) FROM Ticket t WHERE t.event.id = :eventId " +
                        "AND t.status = com.eventmanager.model.TicketStatus.AVAILABLE AND t.id NOT IN :heldIds")
    Page<Ticket> findAvailableExcluding(@Param("eventId") Long eventId,
                                        @Param("heldIds") java.util.Collection<Long> heldIds,
                                        Pageable pageable);

    @Query("SELECT COUNT(t) FROM Ticket t WHERE t.event.id = :eventId " +
           "AND t.status = com.eventmanager.model.TicketStatus.AVAILABLE AND t.id NOT IN :heldIds")
    long countAvailableExcluding(@Param("eventId") Long eventId, @Param("heldIds") java.util.Collection<Long> heldIds);

    /**
     * A user's tickets: the ones Postgres records as theirs (PAYING/SOLD) plus the ones they hold in
     * Redis (which Postgres still has as AVAILABLE, with no owner). Same sentinel convention.
     */
    @Query(value = "SELECT t FROM Ticket t JOIN FETCH t.event e JOIN FETCH e.venue WHERE t.userId = :userId OR t.id IN :heldIds",
           countQuery = "SELECT COUNT(t) FROM Ticket t WHERE t.userId = :userId OR t.id IN :heldIds")
    Page<Ticket> findOwnedOrHeld(@Param("userId") Long userId,
                                 @Param("heldIds") java.util.Collection<Long> heldIds,
                                 Pageable pageable);

    /** Current committed status, read straight from the database (bypasses the persistence context). */
    @Query("SELECT t.status FROM Ticket t WHERE t.id = :id")
    Optional<TicketStatus> findStatusById(@Param("id") Long id);

    /**
     * Writes "<id zero-padded to width><suffix>" (e.g. "00007 Open Seating") into the description of
     * every ticket of an event, in one statement; used right after the event's tickets are inserted,
     * once their database-generated ids exist. Flushes pending inserts first. Deliberately does not
     * clear the persistence context (the caller still uses its managed Event afterwards); the
     * in-memory Ticket objects keep a stale null description, which is never written back because
     * dirty checking compares them against their own (also null) snapshot. LPAD/CAST/CONCAT work on
     * both Postgres and H2.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE tickets SET description = CONCAT(LPAD(CAST(id AS VARCHAR(20)), :width, '0'), :suffix) " +
                   "WHERE event_id = :eventId", nativeQuery = true)
    int writeTicketDescriptions(@Param("eventId") Long eventId, @Param("width") int width, @Param("suffix") String suffix);

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
