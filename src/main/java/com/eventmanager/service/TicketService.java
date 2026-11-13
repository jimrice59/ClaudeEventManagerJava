package com.eventmanager.service;

import com.eventmanager.cassandra.model.CassandraTicket;
import com.eventmanager.cassandra.model.CassandraTicketOperation;
import com.eventmanager.cassandra.repository.TicketCassandraRepository;
import com.eventmanager.dto.PagedResponse;
import com.eventmanager.dto.PerformerSummary;
import com.eventmanager.dto.TicketResponse;
import com.eventmanager.exception.NoTicketsAvailableException;
import com.eventmanager.exception.PaymentServiceUnavailableException;
import com.eventmanager.exception.ResourceNotFoundException;
import com.eventmanager.exception.TicketPaymentInProgressException;
import com.eventmanager.model.Event;
import com.eventmanager.model.Ticket;
import com.eventmanager.model.TicketOperation;
import com.eventmanager.model.TicketOperationType;
import com.eventmanager.model.TicketStatus;
import com.eventmanager.model.User;
import com.eventmanager.payment.PaymentCacheService;
import com.eventmanager.payment.PaymentOutcome;
import com.eventmanager.payment.PaymentOutcomeStatus;
import com.eventmanager.payment.PaymentServiceClient;
import com.eventmanager.repository.EventRepository;
import com.eventmanager.repository.TicketOperationRepository;
import com.eventmanager.repository.TicketRepository;
import com.eventmanager.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import com.eventmanager.dto.TicketTransactionResponse;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Page;

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
    private final PaymentServiceClient paymentServiceClient;
    private final PaymentCacheService paymentCacheService;
    private final PlatformTransactionManager transactionManager;
    private final TicketReservationProperties reservationProperties;
    private final TicketHoldService holdService;

    /**
     * Seating text in every new ticket's description, after the ticket's zero-padded id:
     * "00007 Open Seating" (see createAvailableTickets). There are no seat assignments yet.
     */
    public static final String OPEN_SEATING = "Open Seating";

    /** Linear backoff unit between reserveAvailableTicket attempts: 50ms, 100ms, 150ms, ... */
    static final long RESERVE_RETRY_BACKOFF_MS = 50;

    // Optional: absent in the test profile (Cassandra autoconfiguration excluded). Field-injected
    // with required = false for the same reason CassandraAsyncWriter's repositories are — see
    // CLAUDE.md "Dependency injection". Backups here are synchronous and NOT routed through
    // CassandraAsyncWriter: the delete-event flow must know the backup succeeded before it is
    // safe to remove the tickets from Postgres, which a fire-and-forget @Async write can't guarantee.
    @Autowired(required = false)
    private TicketCassandraRepository ticketCassandraRepository;

    /**
     * Checks Redis for a live hold first, then reads the ticket from Postgres: while held, the
     * ticket reports RESERVED with the holder and holdExpiresAt, though Postgres still has it
     * AVAILABLE. Not cached: a cached "reserved" answer would outlive the hold's expiry.
     */
    @Transactional(readOnly = true)
    public TicketResponse getTicketById(Long id) {
        log.debug("Fetching ticket with id={}", id);
        Optional<TicketHoldService.Hold> hold = holdService.getHold(id);
        return toResponse(findTicketOrThrow(id), hold.orElse(null));
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
        // Available to reserve = AVAILABLE in Postgres and not currently held in Redis.
        return PagedResponse.of(
                ticketRepository.findAvailableExcluding(eventId, nonEmpty(holdService.heldTicketIds(eventId)), pageable),
                this::toResponse);
    }

    /**
     * Paginated list of every ticket owned by the authenticated caller, across all events,
     * ordered by ticket id. Resolves the caller's user id from the JWT and delegates to the
     * internal {@link #getTicketsByUserId(Long, int, int)} — the same public/private split
     * {@link #purchaseTicket(Long, String, String)} uses, so an admin-facing "tickets for an
     * arbitrary user id" variant could be added later without disturbing this one.
     */
    @Transactional(readOnly = true)
    public PagedResponse<TicketResponse> getMyTickets(int page, int size) {
        log.debug("Fetching tickets for current user, page={}, size={}", page, size);
        return getTicketsByUserId(currentUserId(), page, size);
    }

    /**
     * Paginated list of the caller's ticket transactions (their {@code TicketOperation} rows:
     * PURCHASE, PAYMENT_DECLINED, CANCEL), newest first. Scoped to the caller's JWT user id, like
     * {@link #getMyTickets}. Event names are looked up in one query per page; every remaining
     * operation's event still exists, since deleting an event deletes its operations from Postgres.
     * <p>
     * {@code operationDate} is stored as a zone-less {@code LocalDateTime} stamped by
     * {@code LocalDateTime.now()} in this JVM's default zone (UTC in the Docker image), so it is
     * converted with that same zone into an {@code OffsetDateTime}. Clients then get an
     * unambiguous instant and can show it in the viewer's own zone; sent zone-less, a browser would
     * read UTC wall-clock time as its local time (8 hours off in PST).
     */
    @Transactional(readOnly = true)
    public PagedResponse<TicketTransactionResponse> getMyTransactions(int page, int size) {
        Long userId = currentUserId();
        log.debug("Fetching transactions for user id={}, page={}, size={}", userId, page, size);
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size),
                Sort.by(Sort.Order.desc("operationDate"), Sort.Order.desc("id")));
        Page<TicketOperation> operations = ticketOperationRepository.findByUserId(userId, pageable);
        Set<Long> eventIds = operations.getContent().stream()
                .map(TicketOperation::getEventId)
                .collect(Collectors.toSet());
        Map<Long, String> eventNames = eventRepository.findAllById(eventIds).stream()
                .collect(Collectors.toMap(Event::getId, Event::getName));
        // Ticket descriptions (nullable, so not via toMap, which rejects null values).
        Map<Long, String> ticketDescriptions = new java.util.HashMap<>();
        ticketRepository.findAllById(operations.getContent().stream().map(TicketOperation::getTicketId).collect(Collectors.toSet()))
                .forEach(t -> ticketDescriptions.put(t.getId(), t.getDescription()));
        ZoneId serverZone = ZoneId.systemDefault();
        return PagedResponse.of(operations, op -> TicketTransactionResponse.builder()
                .id(op.getId())
                .ticketId(op.getTicketId())
                .ticketDescription(ticketDescriptions.get(op.getTicketId()))
                .eventId(op.getEventId())
                .eventName(eventNames.get(op.getEventId()))
                .operation(op.getOperation())
                .purchasePrice(op.getPurchasePrice())
                .paymentService(op.getPaymentService())
                .paymentConfirmationDetails(op.getPaymentConfirmationDetails())
                .paymentRequestId(op.getPaymentRequestId())
                .operationDate(op.getOperationDate().atZone(serverZone).toOffsetDateTime())
                .build());
    }

    /** Internal: paginated list of tickets for a given user id, regardless of status. */
    private PagedResponse<TicketResponse> getTicketsByUserId(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size), Sort.by("id").ascending());
        // Owned in Postgres (PAYING/SOLD) plus held in Redis (still AVAILABLE, ownerless, in Postgres).
        Page<Ticket> tickets = ticketRepository.findOwnedOrHeld(userId, nonEmpty(holdService.heldTicketIdsForUser(userId)), pageable);
        Map<Long, TicketHoldService.Hold> holds = holdService.getHolds(tickets.getContent().stream().map(Ticket::getId).toList());
        return PagedResponse.of(tickets, t -> toResponse(t, holds.get(t.getId())));
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
        return ticketRepository.countAvailableExcluding(eventId, nonEmpty(holdService.heldTicketIds(eventId)));
    }

    /** NOT IN / IN with an empty list is unsafe in JPQL; a sentinel that matches no ticket is not. */
    private static Collection<Long> nonEmpty(Set<Long> ids) {
        return ids.isEmpty() ? List.of(-1L) : ids;
    }

    /**
     * Reserves a ticket for the authenticated caller. Requires the ticket to currently be
     * AVAILABLE (else {@link IllegalArgumentException}); stamps it with the caller's user id
     * (from the JWT, never client-supplied) and moves it to RESERVED. Same validation this
     * transition has always had.
     */
    @Transactional(readOnly = true)
    public TicketResponse reserveTicket(Long id) {
        log.info("Reserving ticket id={}", id);
        Ticket ticket = findTicketOrThrow(id);
        Long userId = currentUserId();
        requireAvailable(ticket, "reserve");
        Instant expiresAt = holdService.tryHold(ticket.getEvent().getId(), id, userId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Cannot reserve ticket " + id + ": current status is RESERVED, not AVAILABLE"));
        confirmStillAvailableOrRelease(ticket, userId);
        log.info("Reserved ticket id={} for user id={} until {}", id, userId, expiresAt);
        return toResponse(ticket, new TicketHoldService.Hold(userId, expiresAt));
    }

    /**
     * After a hold is won, re-reads the ticket's committed status: a purchase that had consumed an
     * expiring hold may have just moved it to PAYING. If it's no longer AVAILABLE, gives the hold back.
     */
    private void confirmStillAvailableOrRelease(Ticket ticket, Long userId) {
        TicketStatus now = ticketRepository.findStatusById(ticket.getId()).orElse(null);
        if (now != TicketStatus.AVAILABLE) {
            holdService.release(ticket.getEvent().getId(), ticket.getId(), userId);
            throw new IllegalArgumentException(
                    "Cannot reserve ticket " + ticket.getId() + ": current status is " + now + ", not AVAILABLE");
        }
    }

    /** "10 minutes", "1 minute", or "20 seconds" for holds shorter than a minute. */
    static String describe(java.time.Duration d) {
        long minutes = d.toMinutes();
        if (minutes >= 1) {
            return minutes + (minutes == 1 ? " minute" : " minutes");
        }
        long seconds = Math.max(1, d.toSeconds());
        return seconds + (seconds == 1 ? " second" : " seconds");
    }

    private static void requireAvailable(Ticket ticket, String action) {
        if (ticket.getStatus() != TicketStatus.AVAILABLE) {
            throw new IllegalArgumentException("Cannot " + action + " ticket " + ticket.getId()
                    + ": current status is " + ticket.getStatus() + ", not AVAILABLE");
        }
    }

    /**
     * Reserves the next available ticket for an event on behalf of the authenticated caller, for
     * callers who want "a ticket to this event" rather than a specific one. Same end state as
     * {@link #reserveTicket}: a Redis hold for the caller that expires on its own after
     * {@code ticket-reservation.hold-ttl}.
     * <p>
     * Each attempt takes the lowest-id tickets that are AVAILABLE in Postgres and not already held,
     * and tries to hold them in turn; Redis's SET NX decides each one, so concurrent callers never
     * win the same ticket. If every candidate was grabbed by someone else in the meantime, it backs
     * off ({@link #RESERVE_RETRY_BACKOFF_MS} × attempt) and retries with a fresh candidate list, up to
     * {@code ticket-reservation.max-retries} times. When there's no unheld AVAILABLE ticket at all
     * (sold out, or everything left is held), it fails immediately with 409
     * ({@link NoTicketsAvailableException}); holds expire within minutes, so retrying for that isn't
     * worth it. Each attempt runs in its own short read-only transaction, so no connection is held
     * while backing off.
     */
    public TicketResponse reserveAvailableTicket(Long eventId) {
        log.info("Reserving next available ticket for event id={}", eventId);
        if (!eventRepository.existsById(eventId)) {
            throw new ResourceNotFoundException("Event", "id", eventId);
        }
        Long userId = currentUserId();
        int maxRetries = reservationProperties.getMaxRetries();
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setReadOnly(true);
        for (int attempt = 0; ; attempt++) {
            Attempt result = txTemplate.execute(status -> tryHoldNextAvailable(eventId, userId));
            if (result != null && result.reserved() != null) {
                log.info("Reserved ticket id={} (next available for event id={}, attempt {}) for user id={}",
                        result.reserved().getId(), eventId, attempt + 1, userId);
                return result.reserved();
            }
            if (result == null || !result.hadCandidates() || attempt >= maxRetries) {
                // No unheld AVAILABLE ticket at all (sold out, or everything left is held), or retries exhausted.
                throw new NoTicketsAvailableException(eventId);
            }
            long backoffMs = RESERVE_RETRY_BACKOFF_MS * (attempt + 1);
            log.info("Every candidate ticket for event id={} was just held by someone else; "
                    + "retrying in {}ms (retry {}/{})", eventId, backoffMs, attempt + 1, maxRetries);
            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NoTicketsAvailableException(eventId);
            }
        }
    }

    /** How many unheld AVAILABLE tickets one reserveAvailableTicket attempt tries to hold, lowest id first. */
    static final int RESERVE_CANDIDATES = 20;

    private record Attempt(TicketResponse reserved, boolean hadCandidates) {
    }

    /**
     * One attempt: fetch the lowest-id AVAILABLE tickets that aren't held, then try to hold each in
     * turn (SET NX in Redis is the arbiter, so concurrent callers can't both win one). Losing all of
     * them means other callers grabbed them in the meantime, which is worth a retry.
     */
    private Attempt tryHoldNextAvailable(Long eventId, Long userId) {
        List<Ticket> candidates = ticketRepository.findAvailableExcluding(eventId,
                nonEmpty(holdService.heldTicketIds(eventId)), PageRequest.of(0, RESERVE_CANDIDATES, Sort.by("id"))).getContent();
        for (Ticket ticket : candidates) {
            Optional<Instant> expiresAt = holdService.tryHold(eventId, ticket.getId(), userId);
            if (expiresAt.isPresent()) {
                try {
                    confirmStillAvailableOrRelease(ticket, userId);
                } catch (IllegalArgumentException stale) {
                    continue;
                }
                return new Attempt(toResponse(ticket, new TicketHoldService.Hold(userId, expiresAt.get())), true);
            }
        }
        return new Attempt(null, !candidates.isEmpty());
    }

    /**
     * Purchases a ticket on behalf of the authenticated caller, charging {@code paymentToken} (a
     * tokenized payment-method reference, never raw card data) via the payment service over gRPC.
     * Resolves the caller's user id from the JWT and delegates to the internal
     * {@link #purchaseTicket(Long, Long, String, String)}.
     * <p>
     * This is a saga, not one transaction: {@link #beginPayment} commits RESERVED→PAYING in its
     * own short transaction, then the (up to 120-second) gRPC call happens with no transaction
     * open at all, then {@link #completePayment} commits PAYING→SOLD/AVAILABLE in a second short
     * transaction. A single long-held {@code @Transactional} method spanning the whole call would
     * pin a HikariCP connection (and a row lock on this ticket) for the call's full duration —
     * see docs/payment-service-integration.md, "Why two transactions instead of one".
     * <p>
     * Returns 409 ({@link TicketPaymentInProgressException}) if the ticket is already {@code
     * PAYING} — a previous attempt is still in flight — rather than starting a second concurrent
     * payment call for the same ticket. Returns 502 ({@link PaymentServiceUnavailableException})
     * if the payment service call itself fails (timeout, connection error): the ticket is
     * deliberately left {@code PAYING} in that case rather than auto-released, since the payment
     * may have actually succeeded on the payment service's side even though this application
     * never learned the outcome — releasing it risks selling the same ticket twice.
     */
    public TicketResponse purchaseTicket(Long id, String paymentToken, String clientPaymentRequestId) {
        return purchaseTicket(id, currentUserId(), paymentToken, clientPaymentRequestId);
    }

    /** Internal purchase function: ticket id, resolved caller user id, payment token, and optional client-supplied paymentRequestId. */
    private TicketResponse purchaseTicket(Long id, Long userId, String paymentToken, String clientPaymentRequestId) {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        PendingPayment pending = txTemplate.execute(status -> beginPayment(id, userId, clientPaymentRequestId));
        // The ticket is PAYING in Postgres now, so the hold has done its job. Deleted only after
        // that commit, so nobody else could grab the ticket in between.
        holdService.release(pending.eventId(), id, userId);

        // Cached before the call goes out (not after) so the mapping is resolvable even if this
        // application crashes mid-call — see PaymentCacheService#cachePaymentRequest.
        paymentCacheService.cachePaymentRequest(pending.paymentRequestId(), id);
        log.info("Calling payment service for ticket id={}, user id={}, paymentRequestId={}",
                id, userId, pending.paymentRequestId());
        PaymentOutcome outcome = paymentServiceClient.purchaseTicket(
                pending.paymentRequestId(), userId, paymentToken, id, pending.purchasePrice());
        paymentCacheService.cacheIdempotencyToken(pending.paymentRequestId(), outcome.idempotencyToken());

        return txTemplate.execute(status -> completePayment(id, userId, pending.paymentRequestId(), outcome));
    }

    /**
     * Txn 1 of the purchase saga: validates (409 if already PAYING, else the same "RESERVED +
     * ownership" check {@link #sell} has always enforced), stamps a paymentRequestId (the
     * caller's if supplied, else a generated UUID — see {@code PurchaseTicketRequest}), and
     * commits the RESERVED→PAYING transition. Returns the data the payment-service call needs,
     * computed once here so {@code purchaseTicket} doesn't re-fetch the ticket before Txn 2.
     * <p>
     * Not {@code @Transactional}: it's invoked directly (self-invocation) from within the
     * {@link TransactionTemplate#execute} lambda in {@code purchaseTicket}, which bypasses the
     * Spring AOP proxy that {@code @Transactional} relies on — the transaction boundary here
     * comes entirely from that explicit {@code TransactionTemplate}, not from an annotation.
     */
    private PendingPayment beginPayment(Long id, Long userId, String clientPaymentRequestId) {
        Ticket ticket = lockTicketOrThrow(id);
        if (ticket.getStatus() == TicketStatus.PAYING) {
            throw new TicketPaymentInProgressException(id);
        }
        requireAvailable(ticket, "sell");
        TicketHoldService.Hold hold = holdService.getHold(id).orElseThrow(() -> new IllegalArgumentException(
                "Cannot sell ticket " + id + ": it isn't reserved (reservations expire after "
                        + describe(reservationProperties.getHoldTtl()) + "; reserve it again)"));
        if (!userId.equals(hold.userId())) {
            throw new AccessDeniedException("Ticket " + id + " is reserved by a different user");
        }
        ticket.setUserId(userId);
        String paymentRequestId = (clientPaymentRequestId != null && !clientPaymentRequestId.isBlank())
                ? clientPaymentRequestId
                : UUID.randomUUID().toString();
        ticket.setStatus(TicketStatus.PAYING);
        ticket.setPaymentRequestId(paymentRequestId);
        Ticket saved = ticketRepository.save(ticket);
        log.info("Ticket id={} moved to PAYING for user id={}, paymentRequestId={}", id, userId, paymentRequestId);
        return new PendingPayment(paymentRequestId, saved.getEvent().getTicketPrice(), saved.getEvent().getId());
    }

    /**
     * Txn 2 of the purchase saga: resolves the outcome of the payment-service call.
     * <ul>
     *   <li>APPROVED → SOLD; records a PURCHASE {@link TicketOperation} with the real
     *       confirmation id and idempotency token.</li>
     *   <li>DECLINED → AVAILABLE, owner cleared; records a PAYMENT_DECLINED {@link
     *       TicketOperation} (still with the idempotency token the payment service returned,
     *       since it did process the request).</li>
     *   <li>Anything else (the call itself failed — timeout, connection error) → the ticket is
     *       left PAYING as-is and this throws {@link PaymentServiceUnavailableException}; no
     *       {@link TicketOperation} is recorded since nothing was actually resolved. See
     *       docs/payment-service-integration.md, "Reconciling a stuck PAYING ticket".</li>
     * </ul>
     * Not {@code @Transactional} for the same self-invocation reason as {@link #beginPayment}.
     */
    private TicketResponse completePayment(Long id, Long userId, String paymentRequestId, PaymentOutcome outcome) {
        Ticket ticket = lockTicketOrThrow(id);
        if (outcome.status() == PaymentOutcomeStatus.APPROVED) {
            ticket.setStatus(TicketStatus.SOLD);
            ticket.setDatePurchased(LocalDateTime.now());
            Ticket saved = ticketRepository.save(ticket);
            recordOperation(saved, TicketOperationType.PURCHASE, userId, paymentRequestId, outcome);
            writeSoldTicketToCassandra(saved);
            log.info("Purchased ticket id={} for user id={}, confirmationId={}", saved.getId(), userId, outcome.confirmationId());
            return toResponse(saved);
        }
        if (outcome.status() == PaymentOutcomeStatus.DECLINED) {
            ticket.setStatus(TicketStatus.AVAILABLE);
            ticket.setUserId(null);
            Ticket saved = ticketRepository.save(ticket);
            recordOperation(saved, TicketOperationType.PAYMENT_DECLINED, userId, paymentRequestId, outcome);
            log.info("Payment declined for ticket id={}, user id={}, paymentRequestId={}", id, userId, paymentRequestId);
            return toResponse(saved);
        }
        log.error("Payment outcome unresolved for ticket id={}, user id={}, paymentRequestId={}, status={}; leaving ticket PAYING",
                id, userId, paymentRequestId, outcome.status());
        throw new PaymentServiceUnavailableException(id, paymentRequestId);
    }

    /** Data carried from {@link #beginPayment} to the payment-service call and on to {@link #completePayment}. */
    private record PendingPayment(String paymentRequestId, BigDecimal purchasePrice, Long eventId) {
    }

    /**
     * Cancels a purchased ticket. Requires the ticket to currently be SOLD (else
     * {@link IllegalArgumentException}) and the caller's JWT-derived user id to match the
     * ticket's existing user id (else {@link AccessDeniedException}) — only the buyer may
     * cancel their own purchase. Moves the ticket back to AVAILABLE and clears its owner
     * (the same as {@link #releaseTicket}), and records a CANCEL {@link TicketOperation}.
     */
    @Transactional
    public TicketResponse cancelTicket(Long id) {
        log.info("Cancelling ticket id={}", id);
        Ticket ticket = lockTicketOrThrow(id);
        Long userId = currentUserId();
        cancel(ticket, userId);
        ticket.setStatus(TicketStatus.AVAILABLE);
        ticket.setUserId(null);
        Ticket saved = ticketRepository.save(ticket);
        recordOperation(saved, TicketOperationType.CANCEL, userId);
        removeCancelledTicketFromCassandra(saved.getId());
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
    @Transactional(readOnly = true)
    public TicketResponse releaseTicket(Long id) {
        log.info("Releasing ticket id={}", id);
        Ticket ticket = findTicketOrThrow(id);
        Long userId = currentUserId();
        switch (holdService.release(ticket.getEvent().getId(), id, userId)) {
            case RELEASED -> log.info("Released ticket id={}", id);
            case HELD_BY_OTHER -> throw new AccessDeniedException("Ticket " + id + " was reserved by a different user");
            case NOT_HELD -> throw new IllegalArgumentException("Cannot release ticket " + id + ": current status is "
                    + ticket.getStatus() + ", not RESERVED (it isn't reserved, or the reservation expired)");
        }
        return toResponse(ticket, null);
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

    /**
     * Records a CANCEL against a ticket (the only caller left using this overload — purchase
     * outcomes go through {@link #recordOperation(Ticket, TicketOperationType, Long, String,
     * PaymentOutcome)} below, since those have real payment info to record). One row
     * synchronously in Postgres (the source of truth), then a best-effort async copy in
     * Cassandra via CassandraAsyncWriter — the same dual-write pattern events/performers use,
     * unlike the ticket-backup-on-delete path which is synchronous. {@code purchasePrice} is
     * always the ticket's event's current {@code ticketPrice}; {@code paymentService}/
     * {@code paymentConfirmationDetails} are fixed defaults since cancelling doesn't call the
     * payment service (no refund flow exists yet).
     */
    private void recordOperation(Ticket ticket, TicketOperationType type, Long userId) {
        recordOperation(ticket, type, userId, DEFAULT_PAYMENT_SERVICE, DEFAULT_PAYMENT_CONFIRMATION_DETAILS, null, null);
    }

    /**
     * Records a PURCHASE or PAYMENT_DECLINED outcome against a ticket, with the real payment
     * info the payment service returned: {@code confirmationId} (PURCHASE only) and
     * {@code idempotencyToken} both go into the audit row alongside {@code paymentRequestId}, so
     * a ticket's full payment history — approved or declined — is recoverable from
     * {@code TicketOperation} even after the {@code Ticket} row itself moves on.
     */
    private void recordOperation(Ticket ticket, TicketOperationType type, Long userId,
                                  String paymentRequestId, PaymentOutcome outcome) {
        String confirmationDetails = outcome.status() == PaymentOutcomeStatus.APPROVED
                ? outcome.confirmationId()
                : "Payment declined";
        recordOperation(ticket, type, userId, DEFAULT_PAYMENT_SERVICE, confirmationDetails,
                paymentRequestId, outcome.idempotencyToken());
    }

    private void recordOperation(Ticket ticket, TicketOperationType type, Long userId, String paymentService,
                                  String paymentConfirmationDetails, String paymentRequestId, String idempotencyToken) {
        TicketOperation operation = TicketOperation.builder()
                .eventId(ticket.getEvent().getId())
                .ticketId(ticket.getId())
                .userId(userId)
                .operation(type)
                .purchasePrice(ticket.getEvent().getTicketPrice())
                .paymentService(paymentService)
                .paymentConfirmationDetails(paymentConfirmationDetails)
                .paymentRequestId(paymentRequestId)
                .idempotencyToken(idempotencyToken)
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
                .paymentRequestId(operation.getPaymentRequestId())
                .idempotencyToken(operation.getIdempotencyToken())
                .operationDate(operation.getOperationDate())
                .build();
    }

    private Ticket findTicketOrThrow(Long id) {
        return ticketRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket", "id", id));
    }

    /**
     * Row-locked lookup (SELECT ... FOR UPDATE, held until the current transaction commits) for
     * every read-check-write status transition. Without it, two concurrent calls on the same
     * ticket both read the old status, both pass the precondition check, and the last write
     * silently wins — e.g. two concurrent purchases of one RESERVED ticket would both pass the
     * PAYING→409 guard in beginPayment and both charge the card. With it, the second caller blocks,
     * then reads the first caller's committed status and fails the check normally. Only used
     * inside a transaction (@Transactional, or the purchase saga's TransactionTemplate steps —
     * never across the payment-service call itself). See TicketRepository#findByIdForUpdate.
     */
    private Ticket lockTicketOrThrow(Long id) {
        return ticketRepository.findByIdForUpdate(id)
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
        // Ids are database-generated (IDENTITY), so they only exist after the insert; the
        // descriptions, which embed the padded id, are then written by one bulk UPDATE.
        List<Ticket> saved = ticketRepository.saveAll(tickets);
        long maxId = saved.stream().mapToLong(t -> t.getId() == null ? 0 : t.getId()).max().orElse(0);
        int width = ticketNumberWidth(maxId, count);
        ticketRepository.writeTicketDescriptions(event.getId(), width, " " + OPEN_SEATING);
        log.info("Created {} AVAILABLE tickets for event id={} (ticket numbers padded to {} digits)",
                count, event.getId(), width);
    }

    /**
     * Digits to pad an event's ticket numbers to: enough for its largest ticket id, so every ticket
     * of the event gets the same width. Ticket ids are global, not 1..N per event, so the largest
     * id (not the count) decides; e.g. 9000 tickets numbered 1-9000 → 4, 60000 tickets → 5, and a
     * 12-ticket event with ids 60004-60015 → 5. The count is a floor for when ids are unknown.
     */
    static int ticketNumberWidth(long maxTicketId, int count) {
        return String.valueOf(Math.max(1, Math.max(maxTicketId, count))).length();
    }

    /**
     * Called synchronously from EventService.deleteEvent, inside the same transaction, before the
     * event row itself is removed. Backs up only the event's SOLD (purchased) tickets to
     * Cassandra — AVAILABLE/RESERVED/PAYING tickets never had a completed sale worth archiving and
     * are ignored by the sweep — then deletes *all* of the event's tickets from Postgres
     * regardless of status, since every ticket row must be gone before the event row itself can be
     * removed (FK constraint). The backup write is synchronous and un-caught on purpose: if it
     * throws, the whole deleteEvent transaction rolls back rather than risk deleting a sold ticket
     * with no archived copy. Also deletes the event's {@link TicketOperation} rows from Postgres —
     * unlike tickets, these are never backed up here (they were already dual-written to Cassandra
     * when each was recorded, the same as events/performers) and their Cassandra copies are
     * deliberately left in place as an archive, exactly like a deleted event's own last-synced
     * Cassandra copy.
     */
    @Transactional
    public void backupAndDeleteAllForEvent(Long eventId) {
        List<Ticket> tickets = ticketRepository.findByEventId(eventId);
        if (!tickets.isEmpty()) {
            List<Ticket> soldTickets = tickets.stream().filter(t -> t.getStatus() == TicketStatus.SOLD).toList();
            backupToCassandra(soldTickets);
            ticketRepository.deleteAll(tickets);
            log.info("Backed up {} sold ticket(s) and deleted {} total ticket(s) for event id={}",
                    soldTickets.size(), tickets.size(), eventId);
        }
        ticketOperationRepository.deleteByEventId(eventId);
    }

    private void backupToCassandra(List<Ticket> soldTickets) {
        if (ticketCassandraRepository == null || soldTickets.isEmpty()) {
            return;
        }
        List<CassandraTicket> backups = soldTickets.stream().map(this::toCassandraEntity).toList();
        ticketCassandraRepository.saveAll(backups);
    }

    /**
     * The Cassandra {@code tickets} table holds tickets that are currently sold: a sale inserts the
     * ticket's row (SOLD, its buyer, datePurchased) and a cancel deletes it. Async and
     * fire-and-forget like the other dual-writes. The time of the change becomes the Cassandra
     * write timestamp (see CassandraAsyncWriter#saveTicket), so out-of-order arrival can't leave a
     * stale state. Declined payments, reservations, and ticket creation write nothing.
     */
    private void writeSoldTicketToCassandra(Ticket ticket) {
        CassandraTicket row = toCassandraEntity(ticket);
        Instant changedAt = Instant.now();
        afterCommit(() -> cassandraAsyncWriter.saveTicket(row, changedAt));
    }

    /** See {@link #writeSoldTicketToCassandra}. */
    private void removeCancelledTicketFromCassandra(Long ticketId) {
        Instant changedAt = Instant.now();
        afterCommit(() -> cassandraAsyncWriter.deleteTicket(ticketId, changedAt));
    }

    /**
     * Runs the action once the surrounding transaction commits, so Cassandra never gets a change
     * that Postgres rolled back; straight away when there's no transaction (unit tests).
     */
    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private CassandraTicket toCassandraEntity(Ticket ticket) {
        return CassandraTicket.builder()
                .id(ticket.getId())
                .eventId(ticket.getEvent().getId())
                .description(ticket.getDescription())
                .status(ticket.getStatus().name())
                .userId(ticket.getUserId())
                .datePurchased(ticket.getDatePurchased())
                .build();
    }

    private int clampSize(int size) {
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }

    private TicketResponse toResponse(Ticket ticket) {
        return toResponse(ticket, null);
    }

    /**
     * A held ticket is still AVAILABLE in Postgres; while the hold lasts it's reported as RESERVED,
     * owned by the holder, with holdExpiresAt.
     */
    private TicketResponse toResponse(Ticket ticket, TicketHoldService.Hold hold) {
        boolean held = hold != null && ticket.getStatus() == TicketStatus.AVAILABLE;
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
                .status(held ? TicketStatus.RESERVED : ticket.getStatus())
                .userId(held ? hold.userId() : ticket.getUserId())
                .paymentRequestId(ticket.getPaymentRequestId())
                .datePurchased(ticket.getDatePurchased())
                .holdExpiresAt(held ? hold.expiresAt().atOffset(java.time.ZoneOffset.UTC) : null)
                .build();
    }
}
