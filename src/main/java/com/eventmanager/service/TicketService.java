package com.eventmanager.service;

import com.eventmanager.cassandra.model.CassandraTicket;
import com.eventmanager.cassandra.model.CassandraTicketOperation;
import com.eventmanager.cassandra.repository.TicketCassandraRepository;
import com.eventmanager.dto.PagedResponse;
import com.eventmanager.dto.PerformerSummary;
import com.eventmanager.dto.TicketResponse;
import com.eventmanager.exception.ResourceNotFoundException;
import com.eventmanager.model.Event;
import com.eventmanager.model.Ticket;
import com.eventmanager.model.TicketOperation;
import com.eventmanager.model.TicketOperationType;
import com.eventmanager.model.TicketStatus;
import com.eventmanager.model.User;
import com.eventmanager.repository.EventRepository;
import com.eventmanager.repository.TicketOperationRepository;
import com.eventmanager.repository.TicketRepository;
import com.eventmanager.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final String DEFAULT_PAYMENT_SERVICE = "Square";
    private static final String DEFAULT_PAYMENT_CONFIRMATION_DETAILS = "payment confirmation 1234";

    private final TicketRepository ticketRepository;
    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final TicketOperationRepository ticketOperationRepository;
    private final CassandraAsyncWriter cassandraAsyncWriter;

    // Optional: absent in the test profile (Cassandra autoconfiguration excluded). Field-injected
    // with required = false for the same reason CassandraAsyncWriter's repositories are — see
    // CLAUDE.md "Dependency injection". Backups here are synchronous and NOT routed through
    // CassandraAsyncWriter: the delete-event flow must know the backup succeeded before it is
    // safe to remove the tickets from Postgres, which a fire-and-forget @Async write can't guarantee.
    @Autowired(required = false)
    private TicketCassandraRepository ticketCassandraRepository;

    @Cacheable(value = "tickets", key = "#id")
    @Transactional(readOnly = true)
    public TicketResponse getTicketById(Long id) {
        log.debug("Fetching ticket with id={}", id);
        return toResponse(findTicketOrThrow(id));
    }

    /** Paginated list of AVAILABLE tickets for an event, ordered by ticket id. */
    @Transactional(readOnly = true)
    public PagedResponse<TicketResponse> getAvailableTickets(Long eventId, int page, int size) {
        log.debug("Fetching available tickets for event id={}, page={}, size={}", eventId, page, size);
        if (!eventRepository.existsById(eventId)) {
            log.warn("Event not found with id={}", eventId);
            throw new ResourceNotFoundException("Event", "id", eventId);
        }
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size), Sort.by("id").ascending());
        return PagedResponse.of(
                ticketRepository.findByEventIdAndStatus(eventId, TicketStatus.AVAILABLE, pageable),
                this::toResponse);
    }

    /**
     * Paginated list of every ticket owned by the authenticated caller, across all events,
     * ordered by ticket id. Resolves the caller's user id from the JWT and delegates to the
     * internal {@link #getTicketsByUserId(Long, int, int)} — the same public/private split
     * {@link #purchaseTicket(Long, String)} uses, so an admin-facing "tickets for an arbitrary
     * user id" variant could be added later without disturbing this one.
     */
    @Transactional(readOnly = true)
    public PagedResponse<TicketResponse> getMyTickets(int page, int size) {
        log.debug("Fetching tickets for current user, page={}, size={}", page, size);
        return getTicketsByUserId(currentUserId(), page, size);
    }

    /** Internal: paginated list of tickets for a given user id, regardless of status. */
    private PagedResponse<TicketResponse> getTicketsByUserId(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size), Sort.by("id").ascending());
        return PagedResponse.of(ticketRepository.findByUserId(userId, pageable), this::toResponse);
    }

    /**
     * Live count of AVAILABLE tickets for an event, computed by querying Postgres (the primary
     * datastore) directly — not a stored counter on {@code Event} (there is none; the event's own
     * {@code ticketsTotal} is a fixed capacity, not a live count). The result is cached in Redis
     * under {@code "availableTicketCounts"} with a short 5-second TTL (see {@code RedisConfig}) —
     * long enough to absorb a burst of reads without hitting Postgres each time, short enough that
     * the count self-corrects quickly with no explicit eviction wired to ticket writes. Backs
     * {@link com.eventmanager.service.EventService#getNumAvailableTickets(Long)}.
     */
    @Cacheable(value = "availableTicketCounts", key = "#eventId")
    @Transactional(readOnly = true)
    public long getNumAvailableTickets(Long eventId) {
        log.debug("Fetching available ticket count for event id={}", eventId);
        if (!eventRepository.existsById(eventId)) {
            log.warn("Event not found with id={}", eventId);
            throw new ResourceNotFoundException("Event", "id", eventId);
        }
        return ticketRepository.countByEventIdAndStatus(eventId, TicketStatus.AVAILABLE);
    }

    /**
     * Reserves a ticket for the authenticated caller. Requires the ticket to currently be
     * AVAILABLE (else {@link IllegalArgumentException}); stamps it with the caller's user id
     * (from the JWT, never client-supplied) and moves it to RESERVED. Same validation this
     * transition has always had.
     */
    @CachePut(value = "tickets", key = "#id")
    @Transactional
    public TicketResponse reserveTicket(Long id) {
        log.info("Reserving ticket id={}", id);
        Ticket ticket = findTicketOrThrow(id);
        reserve(ticket);
        ticket.setStatus(TicketStatus.RESERVED);
        TicketResponse saved = toResponse(ticketRepository.save(ticket));
        log.info("Reserved ticket id={} for user id={}", saved.getId(), saved.getUserId());
        return saved;
    }

    /**
     * Purchases a ticket on behalf of the authenticated caller. Resolves the caller's user id
     * from the JWT and delegates to the internal {@link #purchaseTicket(Long, Long, String)}.
     * {@code userCredentials} is accepted and threaded through for future use (e.g. payment/
     * identity confirmation) but is not itself validated against any store today — the
     * enforced rule is still the same one SOLD has always had: the ticket must currently be
     * RESERVED, and the caller must be the same user who reserved it. Records a PURCHASE
     * {@link TicketOperation}.
     */
    @CachePut(value = "tickets", key = "#id")
    @Transactional
    public TicketResponse purchaseTicket(Long id, String userCredentials) {
        return purchaseTicket(id, currentUserId(), userCredentials);
    }

    /** Internal purchase function: ticket id, resolved caller user id, and user credentials. */
    private TicketResponse purchaseTicket(Long id, Long userId, String userCredentials) {
        log.info("Purchasing ticket id={} for user id={}", id, userId);
        Ticket ticket = findTicketOrThrow(id);
        sell(ticket, userId);
        ticket.setStatus(TicketStatus.SOLD);
        Ticket saved = ticketRepository.save(ticket);
        recordOperation(saved, TicketOperationType.PURCHASE, userId);
        log.info("Purchased ticket id={} for user id={}", saved.getId(), userId);
        return toResponse(saved);
    }

    /**
     * Cancels a purchased ticket. Requires the ticket to currently be SOLD (else
     * {@link IllegalArgumentException}) and the caller's JWT-derived user id to match the
     * ticket's existing user id (else {@link AccessDeniedException}) — only the buyer may
     * cancel their own purchase. Moves the ticket back to AVAILABLE and clears its owner
     * (the same as {@link #releaseTicket}), and records a CANCEL {@link TicketOperation}.
     */
    @CachePut(value = "tickets", key = "#id")
    @Transactional
    public TicketResponse cancelTicket(Long id) {
        log.info("Cancelling ticket id={}", id);
        Ticket ticket = findTicketOrThrow(id);
        Long userId = currentUserId();
        cancel(ticket, userId);
        ticket.setStatus(TicketStatus.AVAILABLE);
        ticket.setUserId(null);
        Ticket saved = ticketRepository.save(ticket);
        recordOperation(saved, TicketOperationType.CANCEL, userId);
        log.info("Cancelled ticket id={} for user id={}", saved.getId(), userId);
        return toResponse(saved);
    }

    /**
     * Releases a reserved ticket back to AVAILABLE. Requires the ticket to currently be
     * RESERVED (else {@link IllegalArgumentException}) and the caller's JWT-derived user id to
     * match the ticket's existing user id (else {@link AccessDeniedException}) — only the user
     * who reserved it may release it. {@code userId} is cleared back to {@code null}, matching
     * the invariant that every AVAILABLE ticket has no owner.
     */
    @CachePut(value = "tickets", key = "#id")
    @Transactional
    public TicketResponse releaseTicket(Long id) {
        log.info("Releasing ticket id={}", id);
        Ticket ticket = findTicketOrThrow(id);
        release(ticket);
        ticket.setStatus(TicketStatus.AVAILABLE);
        ticket.setUserId(null);
        TicketResponse saved = toResponse(ticketRepository.save(ticket));
        log.info("Released ticket id={}", saved.getId());
        return saved;
    }

    private void reserve(Ticket ticket) {
        if (ticket.getStatus() != TicketStatus.AVAILABLE) {
            throw new IllegalArgumentException(
                    "Cannot reserve ticket " + ticket.getId() + ": current status is "
                            + ticket.getStatus() + ", not AVAILABLE");
        }
        ticket.setUserId(currentUserId());
    }

    private void sell(Ticket ticket, Long userId) {
        if (ticket.getStatus() != TicketStatus.RESERVED) {
            throw new IllegalArgumentException(
                    "Cannot sell ticket " + ticket.getId() + ": current status is "
                            + ticket.getStatus() + ", not RESERVED");
        }
        if (!userId.equals(ticket.getUserId())) {
            throw new AccessDeniedException("Ticket " + ticket.getId() + " was reserved by a different user");
        }
    }

    private void cancel(Ticket ticket, Long userId) {
        if (ticket.getStatus() != TicketStatus.SOLD) {
            throw new IllegalArgumentException(
                    "Cannot cancel ticket " + ticket.getId() + ": current status is "
                            + ticket.getStatus() + ", not SOLD");
        }
        if (!userId.equals(ticket.getUserId())) {
            throw new AccessDeniedException("Ticket " + ticket.getId() + " was purchased by a different user");
        }
    }

    private void release(Ticket ticket) {
        if (ticket.getStatus() != TicketStatus.RESERVED) {
            throw new IllegalArgumentException(
                    "Cannot release ticket " + ticket.getId() + ": current status is "
                            + ticket.getStatus() + ", not RESERVED");
        }
        if (!currentUserId().equals(ticket.getUserId())) {
            throw new AccessDeniedException("Ticket " + ticket.getId() + " was reserved by a different user");
        }
    }

    /**
     * Records a PURCHASE or CANCEL against a ticket: one row synchronously in Postgres (the
     * source of truth), then a best-effort async copy in Cassandra via CassandraAsyncWriter —
     * the same dual-write pattern events/performers use, unlike the ticket-backup-on-delete path
     * which is synchronous. {@code purchasePrice} is always the ticket's event's current
     * {@code ticketPrice}; {@code paymentService}/{@code paymentConfirmationDetails} are fixed
     * defaults today since there's no real payment integration yet.
     */
    private void recordOperation(Ticket ticket, TicketOperationType type, Long userId) {
        TicketOperation operation = TicketOperation.builder()
                .eventId(ticket.getEvent().getId())
                .ticketId(ticket.getId())
                .userId(userId)
                .operation(type)
                .purchasePrice(ticket.getEvent().getTicketPrice())
                .paymentService(DEFAULT_PAYMENT_SERVICE)
                .paymentConfirmationDetails(DEFAULT_PAYMENT_CONFIRMATION_DETAILS)
                .operationDate(LocalDateTime.now())
                .build();
        TicketOperation saved = ticketOperationRepository.save(operation);
        cassandraAsyncWriter.saveTicketOperation(toCassandraEntity(saved));
    }

    private CassandraTicketOperation toCassandraEntity(TicketOperation operation) {
        return CassandraTicketOperation.builder()
                .id(operation.getId())
                .eventId(operation.getEventId())
                .ticketId(operation.getTicketId())
                .userId(operation.getUserId())
                .operation(operation.getOperation().name())
                .purchasePrice(operation.getPurchasePrice())
                .paymentService(operation.getPaymentService())
                .paymentConfirmationDetails(operation.getPaymentConfirmationDetails())
                .operationDate(operation.getOperationDate())
                .build();
    }

    private Ticket findTicketOrThrow(Long id) {
        return ticketRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket", "id", id));
    }

    /** Resolves the authenticated caller's user id from the JWT-backed SecurityContext — never client-supplied. */
    private Long currentUserId() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username)
                .map(User::getId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "username", username));
    }

    /**
     * Creates {@code count} AVAILABLE tickets for a just-created event — one per unit of venue
     * capacity. Called synchronously from EventService.createEvent, inside the same transaction,
     * so ticket creation either commits with the event or rolls back with it. No Cassandra write:
     * tickets are only backed up to Cassandra when their event is deleted.
     */
    @Transactional
    public void createAvailableTickets(Event event, int count) {
        List<Ticket> tickets = IntStream.range(0, count)
                .mapToObj(i -> Ticket.builder()
                        .event(event)
                        .status(TicketStatus.AVAILABLE)
                        .userId(null)
                        .build())
                .toList();
        ticketRepository.saveAll(tickets);
        log.info("Created {} AVAILABLE tickets for event id={}", count, event.getId());
    }

    /**
     * Called synchronously from EventService.deleteEvent, inside the same transaction, before the
     * event row itself is removed. Backs up every ticket for the event to Cassandra, then deletes
     * them all from Postgres. The backup write is synchronous and un-caught on purpose: if it
     * throws, the whole deleteEvent transaction rolls back rather than risk deleting tickets with
     * no archived copy. Also deletes the event's {@link TicketOperation} rows from Postgres —
     * unlike tickets, these are never backed up here (they were already dual-written to Cassandra
     * when each was recorded, the same as events/performers) and their Cassandra copies are
     * deliberately left in place as an archive, exactly like a deleted event's own last-synced
     * Cassandra copy.
     */
    @CacheEvict(value = "tickets", allEntries = true)
    @Transactional
    public void backupAndDeleteAllForEvent(Long eventId) {
        List<Ticket> tickets = ticketRepository.findByEventId(eventId);
        if (!tickets.isEmpty()) {
            backupToCassandra(tickets);
            ticketRepository.deleteAll(tickets);
            log.info("Backed up and deleted {} tickets for event id={}", tickets.size(), eventId);
        }
        ticketOperationRepository.deleteByEventId(eventId);
    }

    private void backupToCassandra(List<Ticket> tickets) {
        if (ticketCassandraRepository == null) {
            return;
        }
        List<CassandraTicket> backups = tickets.stream().map(this::toCassandraEntity).toList();
        ticketCassandraRepository.saveAll(backups);
    }

    private CassandraTicket toCassandraEntity(Ticket ticket) {
        return CassandraTicket.builder()
                .id(ticket.getId())
                .eventId(ticket.getEvent().getId())
                .description(ticket.getDescription())
                .status(ticket.getStatus().name())
                .userId(ticket.getUserId())
                .build();
    }

    private int clampSize(int size) {
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }

    private TicketResponse toResponse(Ticket ticket) {
        Event event = ticket.getEvent();
        return TicketResponse.builder()
                .id(ticket.getId())
                .eventId(event.getId())
                .eventName(event.getName())
                .eventDate(event.getEventDate())
                .description(ticket.getDescription())
                .venueId(event.getVenue().getId())
                .venueName(event.getVenue().getName())
                .performers(event.getPerformers().stream()
                        .map(p -> new PerformerSummary(p.getId(), p.getName()))
                        .collect(Collectors.toSet()))
                .status(ticket.getStatus())
                .userId(ticket.getUserId())
                .build();
    }
}
