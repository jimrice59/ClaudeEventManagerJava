package com.eventmanager.service;

import com.eventmanager.cassandra.model.CassandraTicket;
import com.eventmanager.cassandra.model.CassandraTicketOperation;
import com.eventmanager.cassandra.repository.TicketCassandraRepository;
import com.eventmanager.dto.PagedResponse;
import com.eventmanager.dto.TicketTransactionResponse;
import com.eventmanager.dto.TicketResponse;
import com.eventmanager.exception.NoTicketsAvailableException;
import com.eventmanager.exception.PaymentServiceUnavailableException;
import com.eventmanager.exception.ResourceNotFoundException;
import com.eventmanager.exception.TicketPaymentInProgressException;
import com.eventmanager.model.Event;
import com.eventmanager.model.Performer;
import com.eventmanager.model.Ticket;
import com.eventmanager.model.TicketOperation;
import com.eventmanager.model.TicketOperationType;
import com.eventmanager.model.TicketStatus;
import com.eventmanager.model.User;
import com.eventmanager.model.Venue;
import com.eventmanager.payment.PaymentCacheService;
import com.eventmanager.payment.PaymentOutcome;
import com.eventmanager.payment.PaymentOutcomeStatus;
import com.eventmanager.payment.PaymentServiceClient;
import com.eventmanager.repository.EventRepository;
import com.eventmanager.repository.TicketOperationRepository;
import com.eventmanager.repository.TicketRepository;
import com.eventmanager.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketServiceTest {

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private EventRepository eventRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TicketOperationRepository ticketOperationRepository;

    @Mock
    private CassandraAsyncWriter cassandraAsyncWriter;

    @Mock
    private TicketCassandraRepository ticketCassandraRepository;

    @Mock
    private PaymentServiceClient paymentServiceClient;

    @Mock
    private PaymentCacheService paymentCacheService;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private TicketHoldService holdService;

    private TicketService ticketService;

    private Venue venue;
    private Performer performer;
    private Event event;
    private Ticket ticket;
    private User alice;

    private final TicketReservationProperties reservationProperties = new TicketReservationProperties();

    /** Expiry used for stubbed holds. */
    private static final java.time.Instant HOLD_EXPIRY = java.time.Instant.parse("2026-11-12T19:10:00Z");

    @BeforeEach
    void setUp() {
        ticketService = new TicketService(ticketRepository, eventRepository, userRepository,
                ticketOperationRepository, cassandraAsyncWriter, paymentServiceClient, paymentCacheService,
                transactionManager, reservationProperties, holdService);
        // TicketCassandraRepository is field-injected (@Autowired(required = false)), matching
        // the optional-infrastructure-bean pattern documented for CassandraAsyncWriter — tests
        // that exercise the "Cassandra present" path inject it explicitly via ReflectionTestUtils.

        venue = Venue.builder()
                .id(1L).name("Madison Square Garden").address("4 Pennsylvania Plaza")
                .city("New York").state("NY").zipCode("10001").capacity(20000)
                .build();

        performer = Performer.builder().id(1L).name("The Beatles").genre("Rock").build();

        event = Event.builder()
                .id(1L)
                .name("Summer Rock Festival")
                .eventDate(LocalDateTime.of(2025, 8, 15, 19, 0))
                .ticketPrice(new BigDecimal("99.99"))
                .ticketsTotal(500)
                .venue(venue)
                .performers(Set.of(performer))
                .build();

        ticket = Ticket.builder()
                .id(1L)
                .event(event)
                .description("Section 100, Row A, Seat 5")
                .status(TicketStatus.AVAILABLE)
                .build();

        alice = User.builder().id(42L).username("alice").build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** Puts an authenticated principal (by username) in the SecurityContext, as the JWT filter would. */
    private void authenticateAs(String username) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null, List.of()));
    }

    // --- getTicketById ---

    @Test
    void getTicketById_returnsTicket() {
        when(ticketRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(ticket));

        TicketResponse result = ticketService.getTicketById(1L);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getVenueName()).isEqualTo("Madison Square Garden");
        assertThat(result.getUserId()).isNull();
    }

    @Test
    void getTicketById_throwsWhenNotFound() {
        when(ticketRepository.findByIdWithDetails(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ticketService.getTicketById(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Ticket")
                .hasMessageContaining("99");
    }

    // --- getNumAvailableTickets ---

    @Test
    void getNumAvailableTickets_returnsCountFromRepository() {
        when(eventRepository.existsById(1L)).thenReturn(true);
        when(ticketRepository.countAvailableExcluding(eq(1L), any())).thenReturn(37L);

        long result = ticketService.getNumAvailableTickets(1L);

        assertThat(result).isEqualTo(37L);
    }

    @Test
    void getNumAvailableTickets_throwsWhenEventNotFound() {
        when(eventRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> ticketService.getNumAvailableTickets(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Event")
                .hasMessageContaining("99");
        verify(ticketRepository, never()).countByEventIdAndStatus(any(), any());
    }

    // --- getAvailableTickets ---

    @Test
    void getAvailableTickets_returnsPagedResponse() {
        when(eventRepository.existsById(1L)).thenReturn(true);
        Page<Ticket> page = new PageImpl<>(List.of(ticket));
        when(ticketRepository.findAvailableExcluding(eq(1L), any(), any(Pageable.class)))
                .thenReturn(page);

        PagedResponse<TicketResponse> result = ticketService.getAvailableTickets(1L, 0, 20);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getStatus()).isEqualTo(TicketStatus.AVAILABLE);
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.isLast()).isTrue();
    }

    @Test
    void getAvailableTickets_throwsWhenEventNotFound() {
        when(eventRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> ticketService.getAvailableTickets(99L, 0, 20))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Event")
                .hasMessageContaining("99");
        verify(ticketRepository, never()).findByEventIdAndStatus(any(), any(), any());
    }

    @Test
    void getAvailableTickets_clampsOversizedPageSize() {
        when(eventRepository.existsById(1L)).thenReturn(true);
        when(ticketRepository.findAvailableExcluding(eq(1L), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        ticketService.getAvailableTickets(1L, 0, 5000);

        verify(ticketRepository).findAvailableExcluding(eq(1L), any(),
                argThat(pageable -> pageable.getPageSize() == 100));
    }

    // --- getMyTickets ---

    @Test
    void getMyTickets_returnsPagedResponseForCurrentUser() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        authenticateAs("alice");
        Page<Ticket> page = new PageImpl<>(List.of(ticket));
        when(ticketRepository.findOwnedOrHeld(eq(42L), any(), any(Pageable.class))).thenReturn(page);
        PagedResponse<TicketResponse> result = ticketService.getMyTickets(0, 20);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getEventName()).isEqualTo("Summer Rock Festival");
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    void getMyTickets_clampsOversizedPageSize() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        authenticateAs("alice");
        when(ticketRepository.findOwnedOrHeld(eq(42L), any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        ticketService.getMyTickets(0, 5000);

        verify(ticketRepository).findOwnedOrHeld(eq(42L), any(), argThat(pageable -> pageable.getPageSize() == 100));
    }

    @Test
    void getMyTickets_throwsWhenCallersUsernameHasNoMatchingUser() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());
        authenticateAs("ghost");

        assertThatThrownBy(() -> ticketService.getMyTickets(0, 20))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("User")
                .hasMessageContaining("ghost");
        verify(ticketRepository, never()).findByUserId(any(), any());
    }

    @Test
    void getMyTransactions_returnsCallersOperationsNewestFirstWithEventNamesAndZonedDates() {
        LocalDateTime purchasedAt = LocalDateTime.of(2026, 11, 11, 22, 39, 51);
        TicketOperation purchase = TicketOperation.builder()
                .id(10L).eventId(1L).ticketId(1L).userId(42L).operation(TicketOperationType.PURCHASE)
                .purchasePrice(new BigDecimal("99.99")).paymentService("Square")
                .paymentConfirmationDetails("SIMULATED-abc").paymentRequestId("req-1")
                .idempotencyToken("secret-token").operationDate(purchasedAt).build();
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(ticketOperationRepository.findByUserId(eq(42L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(purchase)));
        when(eventRepository.findAllById(any())).thenReturn(List.of(event));
        ticket.setDescription("00001 Open Seating");
        when(ticketRepository.findAllById(Set.of(1L))).thenReturn(List.of(ticket));
        authenticateAs("alice");

        PagedResponse<TicketTransactionResponse> page = ticketService.getMyTransactions(0, 20);

        TicketTransactionResponse t = page.getContent().get(0);
        assertThat(t.getId()).isEqualTo(10L);
        assertThat(t.getTicketDescription()).isEqualTo("00001 Open Seating");
        assertThat(t.getEventName()).isEqualTo("Summer Rock Festival");
        assertThat(t.getOperation()).isEqualTo(TicketOperationType.PURCHASE);
        assertThat(t.getPaymentConfirmationDetails()).isEqualTo("SIMULATED-abc");
        // Zone-less stored time, converted with the server's zone (the zone LocalDateTime.now() used).
        assertThat(t.getOperationDate()).isEqualTo(purchasedAt.atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime());
        // Scoped to the caller and sorted newest first.
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(ticketOperationRepository).findByUserId(eq(42L), pageable.capture());
        assertThat(pageable.getValue().getSort().getOrderFor("operationDate").isDescending()).isTrue();
    }

    @Test
    void getMyTransactions_clampsOversizedPageSize() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(ticketOperationRepository.findByUserId(eq(42L), any(Pageable.class))).thenReturn(Page.empty());
        when(eventRepository.findAllById(any())).thenReturn(List.of());
        authenticateAs("alice");

        ticketService.getMyTransactions(0, 5000);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(ticketOperationRepository).findByUserId(eq(42L), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
    }

    // --- purchaseTicket ---

    @Test
    void purchaseTicket_succeedsWhenReservedBySameUserAndPaymentApproved() {
        when(holdService.getHold(1L)).thenReturn(Optional.of(new TicketHoldService.Hold(42L, HOLD_EXPIRY)));
        when(ticketRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(ticket));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ticketOperationRepository.save(any(TicketOperation.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentServiceClient.purchaseTicket(any(), eq(42L), eq("pm_tok_abc123"), eq(1L), eq(new BigDecimal("99.99"))))
                .thenReturn(new PaymentOutcome(PaymentOutcomeStatus.APPROVED, "conf-9000", "idem-9000"));
        authenticateAs("alice");

        TicketResponse result = ticketService.purchaseTicket(1L, "pm_tok_abc123", null);

        assertThat(result.getStatus()).isEqualTo(TicketStatus.SOLD);
        assertThat(result.getUserId()).isEqualTo(42L);
        assertThat(result.getPaymentRequestId()).isNotBlank();
        assertThat(result.getDatePurchased()).isNotNull();

        // beginPayment (RESERVED→PAYING) and completePayment (PAYING→SOLD) each save once. (Both
        // calls mutate and re-save the same mocked Ticket instance, so an ArgumentCaptor can't
        // distinguish the two calls' intermediate states — only the final result and call count.)
        verify(ticketRepository, org.mockito.Mockito.times(2)).save(any(Ticket.class));

        ArgumentCaptor<TicketOperation> captor = ArgumentCaptor.forClass(TicketOperation.class);
        verify(ticketOperationRepository).save(captor.capture());
        TicketOperation recorded = captor.getValue();
        assertThat(recorded.getEventId()).isEqualTo(1L);
        assertThat(recorded.getTicketId()).isEqualTo(1L);
        assertThat(recorded.getUserId()).isEqualTo(42L);
        assertThat(recorded.getOperation()).isEqualTo(TicketOperationType.PURCHASE);
        assertThat(recorded.getPurchasePrice()).isEqualByComparingTo("99.99");
        assertThat(recorded.getPaymentService()).isEqualTo("Square");
        assertThat(recorded.getPaymentConfirmationDetails()).isEqualTo("conf-9000");
        assertThat(recorded.getIdempotencyToken()).isEqualTo("idem-9000");
        assertThat(recorded.getPaymentRequestId()).isNotBlank();
        assertThat(recorded.getOperationDate()).isNotNull();
        verify(cassandraAsyncWriter).saveTicketOperation(any(CassandraTicketOperation.class));
        // The sold ticket is mirrored to the Cassandra tickets table, buyer and purchase date included.
        ArgumentCaptor<CassandraTicket> mirrored = ArgumentCaptor.forClass(CassandraTicket.class);
        verify(cassandraAsyncWriter).saveTicket(mirrored.capture(), any(java.time.Instant.class));
        assertThat(mirrored.getValue().getId()).isEqualTo(1L);
        assertThat(mirrored.getValue().getEventId()).isEqualTo(1L);
        assertThat(mirrored.getValue().getStatus()).isEqualTo("SOLD");
        assertThat(mirrored.getValue().getUserId()).isEqualTo(42L);
        assertThat(mirrored.getValue().getDatePurchased()).isNotNull();
        verify(paymentCacheService).cachePaymentRequest(any(), eq(1L));
        verify(paymentCacheService).cacheIdempotencyToken(any(), eq("idem-9000"));
        // The hold is deleted after RESERVED→PAYING commits.
        verify(holdService).release(1L, 1L, 42L);
    }

    @Test
    void purchaseTicket_usesClientSuppliedPaymentRequestId() {
        when(holdService.getHold(1L)).thenReturn(Optional.of(new TicketHoldService.Hold(42L, HOLD_EXPIRY)));
        when(ticketRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(ticket));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ticketOperationRepository.save(any(TicketOperation.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentServiceClient.purchaseTicket(eq("client-request-id-1"), eq(42L), any(), eq(1L), any()))
                .thenReturn(new PaymentOutcome(PaymentOutcomeStatus.APPROVED, "conf-1", "idem-1"));
        authenticateAs("alice");

        TicketResponse result = ticketService.purchaseTicket(1L, "pm_tok_abc123", "client-request-id-1");

        assertThat(result.getPaymentRequestId()).isEqualTo("client-request-id-1");
        verify(paymentServiceClient).purchaseTicket(eq("client-request-id-1"), eq(42L), any(), eq(1L), any());
    }

    @Test
    void purchaseTicket_movesBackToAvailableWhenPaymentDeclined() {
        when(holdService.getHold(1L)).thenReturn(Optional.of(new TicketHoldService.Hold(42L, HOLD_EXPIRY)));
        when(ticketRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(ticket));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ticketOperationRepository.save(any(TicketOperation.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentServiceClient.purchaseTicket(any(), eq(42L), any(), eq(1L), any()))
                .thenReturn(new PaymentOutcome(PaymentOutcomeStatus.DECLINED, null, "idem-declined"));
        authenticateAs("alice");

        TicketResponse result = ticketService.purchaseTicket(1L, "pm_tok_abc123", null);

        assertThat(result.getStatus()).isEqualTo(TicketStatus.AVAILABLE);
        assertThat(result.getUserId()).isNull();

        ArgumentCaptor<TicketOperation> captor = ArgumentCaptor.forClass(TicketOperation.class);
        verify(ticketOperationRepository).save(captor.capture());
        assertThat(captor.getValue().getOperation()).isEqualTo(TicketOperationType.PAYMENT_DECLINED);
        assertThat(captor.getValue().getIdempotencyToken()).isEqualTo("idem-declined");
        // A declined payment never sold the ticket: nothing to mirror.
        verify(cassandraAsyncWriter, never()).saveTicket(any(), any());
    }

    @Test
    void purchaseTicket_throwsPaymentServiceUnavailableAndLeavesTicketPayingWhenCallFails() {
        when(holdService.getHold(1L)).thenReturn(Optional.of(new TicketHoldService.Hold(42L, HOLD_EXPIRY)));
        when(ticketRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(ticket));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentServiceClient.purchaseTicket(any(), eq(42L), any(), eq(1L), any()))
                .thenReturn(new PaymentOutcome(PaymentOutcomeStatus.ERROR, null, null));
        authenticateAs("alice");

        assertThatThrownBy(() -> ticketService.purchaseTicket(1L, "pm_tok_abc123", null))
                .isInstanceOf(PaymentServiceUnavailableException.class);

        // Only beginPayment's RESERVED→PAYING save happens; completePayment never saves since
        // the ticket is left PAYING, not resolved to SOLD/AVAILABLE.
        ArgumentCaptor<Ticket> ticketCaptor = ArgumentCaptor.forClass(Ticket.class);
        verify(ticketRepository).save(ticketCaptor.capture());
        assertThat(ticketCaptor.getValue().getStatus()).isEqualTo(TicketStatus.PAYING);
        verify(ticketOperationRepository, never()).save(any());
    }

    @Test
    void purchaseTicket_throws409WhenAlreadyPaying() {
        ticket.setStatus(TicketStatus.PAYING);
        ticket.setUserId(42L);
        ticket.setPaymentRequestId("in-flight-request");
        when(ticketRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(ticket));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        authenticateAs("alice");

        assertThatThrownBy(() -> ticketService.purchaseTicket(1L, "pm_tok_abc123", null))
                .isInstanceOf(TicketPaymentInProgressException.class);
        verify(ticketRepository, never()).save(any());
        verifyNoInteractions(paymentServiceClient);
    }

    @Test
    void purchaseTicket_throwsWhenNotCurrentlyReserved() {
        // ticket is AVAILABLE with no hold (never reserved, or the hold expired)
        when(ticketRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(ticket));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        authenticateAs("alice");

        assertThatThrownBy(() -> ticketService.purchaseTicket(1L, "pm_tok_abc123", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("isn't reserved");
        verify(ticketRepository, never()).save(any());
        verify(ticketOperationRepository, never()).save(any());
    }

    @Test
    void purchaseTicket_throwsAccessDeniedWhenReservedByDifferentUser() {
        when(holdService.getHold(1L)).thenReturn(Optional.of(new TicketHoldService.Hold(42L, HOLD_EXPIRY))); // alice's hold
        when(ticketRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(ticket));
        User bob = User.builder().id(99L).username("bob").build();
        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(bob));
        authenticateAs("bob");

        assertThatThrownBy(() -> ticketService.purchaseTicket(1L, "pm_tok_abc123", null))
                .isInstanceOf(AccessDeniedException.class);
        verify(ticketRepository, never()).save(any());
        verify(ticketOperationRepository, never()).save(any());
    }

    @Test
    void purchaseTicket_throwsWhenNotFound() {
        when(ticketRepository.findByIdForUpdate(99L)).thenReturn(Optional.empty());
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        authenticateAs("alice");

        assertThatThrownBy(() -> ticketService.purchaseTicket(99L, "pm_tok_abc123", null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Ticket");
        verify(ticketRepository, never()).save(any());
        verify(ticketOperationRepository, never()).save(any());
    }

    // --- getTicketById with holds ---

    @Test
    void getTicketById_checksRedisFirstAndReportsReservedWhileHeld() {
        when(holdService.getHold(1L)).thenReturn(Optional.of(new TicketHoldService.Hold(42L, HOLD_EXPIRY)));
        when(ticketRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(ticket)); // AVAILABLE in Postgres

        TicketResponse result = ticketService.getTicketById(1L);

        assertThat(result.getStatus()).isEqualTo(TicketStatus.RESERVED);
        assertThat(result.getUserId()).isEqualTo(42L);
        assertThat(result.getHoldExpiresAt()).isEqualTo(HOLD_EXPIRY.atOffset(java.time.ZoneOffset.UTC));
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(holdService, ticketRepository);
        order.verify(holdService).getHold(1L);
        order.verify(ticketRepository).findByIdWithDetails(1L);
    }

    @Test
    void getTicketById_ignoresALeftoverHoldOnceTheTicketIsNoLongerAvailable() {
        ticket.setStatus(TicketStatus.PAYING);
        ticket.setUserId(42L);
        when(holdService.getHold(1L)).thenReturn(Optional.of(new TicketHoldService.Hold(7L, HOLD_EXPIRY)));
        when(ticketRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(ticket));

        TicketResponse result = ticketService.getTicketById(1L);

        assertThat(result.getStatus()).isEqualTo(TicketStatus.PAYING);
        assertThat(result.getUserId()).isEqualTo(42L);
        assertThat(result.getHoldExpiresAt()).isNull();
    }

    // --- reserveTicket (a Redis hold; Postgres untouched) ---

    @Test
    void reserveTicket_placesAHoldForTheCallerWithoutWritingPostgres() {
        when(ticketRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(ticket));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(holdService.tryHold(1L, 1L, 42L)).thenReturn(Optional.of(HOLD_EXPIRY));
        when(ticketRepository.findStatusById(1L)).thenReturn(Optional.of(TicketStatus.AVAILABLE));
        authenticateAs("alice");

        TicketResponse result = ticketService.reserveTicket(1L);

        assertThat(result.getStatus()).isEqualTo(TicketStatus.RESERVED);
        assertThat(result.getUserId()).isEqualTo(42L);
        assertThat(result.getHoldExpiresAt()).isEqualTo(HOLD_EXPIRY.atOffset(java.time.ZoneOffset.UTC));
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void reserveTicket_failsWhenSomeoneElseAlreadyHoldsIt() {
        when(ticketRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(ticket));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(holdService.tryHold(1L, 1L, 42L)).thenReturn(Optional.empty());
        authenticateAs("alice");

        assertThatThrownBy(() -> ticketService.reserveTicket(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RESERVED");
    }

    @Test
    void reserveTicket_throwsWhenNotCurrentlyAvailable() {
        ticket.setStatus(TicketStatus.SOLD);
        when(ticketRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(ticket));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        authenticateAs("alice");

        assertThatThrownBy(() -> ticketService.reserveTicket(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AVAILABLE");
        verifyNoInteractions(holdService);
    }

    @Test
    void reserveTicket_givesTheHoldBackIfThePurchaseOfAnExpiringHoldJustWon() {
        when(ticketRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(ticket));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(holdService.tryHold(1L, 1L, 42L)).thenReturn(Optional.of(HOLD_EXPIRY));
        when(ticketRepository.findStatusById(1L)).thenReturn(Optional.of(TicketStatus.PAYING));
        authenticateAs("alice");

        assertThatThrownBy(() -> ticketService.reserveTicket(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PAYING");
        verify(holdService).release(1L, 1L, 42L);
    }

    @Test
    void reserveTicket_throwsWhenNotFound() {
        when(ticketRepository.findByIdWithDetails(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ticketService.reserveTicket(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Ticket");
        verifyNoInteractions(holdService);
    }

    @Test
    void reserveTicket_throwsWhenAuthenticatedUsernameHasNoMatchingUserRow() {
        when(ticketRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(ticket));
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());
        authenticateAs("ghost");

        assertThatThrownBy(() -> ticketService.reserveTicket(1L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("User");
        verifyNoInteractions(holdService);
    }

    // --- reserveAvailableTicket (lowest-id unheld ticket; SET NX decides) ---

    private Ticket availableTicket(long id) {
        return Ticket.builder().id(id).event(event).status(TicketStatus.AVAILABLE).build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void reserveAvailableTicket_holdsTheLowestUnheldTicketForTheCaller() {
        when(eventRepository.existsById(1L)).thenReturn(true);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(holdService.heldTicketIds(1L)).thenReturn(Set.of(7L));
        when(ticketRepository.findAvailableExcluding(eq(1L), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(ticket)));
        when(holdService.tryHold(1L, 1L, 42L)).thenReturn(Optional.of(HOLD_EXPIRY));
        when(ticketRepository.findStatusById(1L)).thenReturn(Optional.of(TicketStatus.AVAILABLE));
        authenticateAs("alice");

        TicketResponse result = ticketService.reserveAvailableTicket(1L);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getStatus()).isEqualTo(TicketStatus.RESERVED);
        assertThat(result.getUserId()).isEqualTo(42L);
        // Tickets already held (7) are excluded from the candidates.
        ArgumentCaptor<java.util.Collection<Long>> excluded = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(ticketRepository).findAvailableExcluding(eq(1L), excluded.capture(), any(Pageable.class));
        assertThat(excluded.getValue()).containsExactly(7L);
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void reserveAvailableTicket_movesOnWhenSomeoneElseJustHeldACandidate() {
        when(eventRepository.existsById(1L)).thenReturn(true);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(ticketRepository.findAvailableExcluding(eq(1L), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(availableTicket(1L), availableTicket(2L))));
        when(holdService.tryHold(1L, 1L, 42L)).thenReturn(Optional.empty());
        when(holdService.tryHold(1L, 2L, 42L)).thenReturn(Optional.of(HOLD_EXPIRY));
        when(ticketRepository.findStatusById(2L)).thenReturn(Optional.of(TicketStatus.AVAILABLE));
        authenticateAs("alice");

        assertThat(ticketService.reserveAvailableTicket(1L).getId()).isEqualTo(2L);
    }

    @Test
    void reserveAvailableTicket_failsImmediatelyWhenNoUnheldTicketIsAvailable() {
        when(eventRepository.existsById(1L)).thenReturn(true);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(ticketRepository.findAvailableExcluding(eq(1L), any(), any(Pageable.class))).thenReturn(Page.empty());
        authenticateAs("alice");

        assertThatThrownBy(() -> ticketService.reserveAvailableTicket(1L))
                .isInstanceOf(NoTicketsAvailableException.class);
        // Sold out, or everything left is held: holds expire in minutes, so no point retrying now.
        verify(ticketRepository, times(1)).findAvailableExcluding(eq(1L), any(), any(Pageable.class));
        verify(holdService, never()).tryHold(any(), any(), any());
    }

    @Test
    void reserveAvailableTicket_retriesWhenEveryCandidateWasJustGrabbedThenSucceeds() {
        when(eventRepository.existsById(1L)).thenReturn(true);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(ticketRepository.findAvailableExcluding(eq(1L), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(ticket)));
        when(holdService.tryHold(1L, 1L, 42L)).thenReturn(Optional.empty(), Optional.of(HOLD_EXPIRY));
        when(ticketRepository.findStatusById(1L)).thenReturn(Optional.of(TicketStatus.AVAILABLE));
        authenticateAs("alice");

        assertThat(ticketService.reserveAvailableTicket(1L).getStatus()).isEqualTo(TicketStatus.RESERVED);
        verify(ticketRepository, times(2)).findAvailableExcluding(eq(1L), any(), any(Pageable.class));
    }

    @Test
    void reserveAvailableTicket_givesUpAfterMaxRetries() {
        reservationProperties.setMaxRetries(2);
        when(eventRepository.existsById(1L)).thenReturn(true);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(ticketRepository.findAvailableExcluding(eq(1L), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(ticket)));
        when(holdService.tryHold(1L, 1L, 42L)).thenReturn(Optional.empty());
        authenticateAs("alice");

        assertThatThrownBy(() -> ticketService.reserveAvailableTicket(1L))
                .isInstanceOf(NoTicketsAvailableException.class);
        verify(holdService, times(3)).tryHold(1L, 1L, 42L); // 1 attempt + 2 retries
    }

    @Test
    void reserveAvailableTicket_zeroMaxRetriesMakesExactlyOneAttempt() {
        reservationProperties.setMaxRetries(0);
        when(eventRepository.existsById(1L)).thenReturn(true);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(ticketRepository.findAvailableExcluding(eq(1L), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(ticket)));
        when(holdService.tryHold(1L, 1L, 42L)).thenReturn(Optional.empty());
        authenticateAs("alice");

        assertThatThrownBy(() -> ticketService.reserveAvailableTicket(1L))
                .isInstanceOf(NoTicketsAvailableException.class);
        verify(holdService, times(1)).tryHold(1L, 1L, 42L);
    }

    @Test
    void reserveAvailableTicket_throwsNotFoundWhenEventMissing() {
        when(eventRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> ticketService.reserveAvailableTicket(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Event");
        verifyNoInteractions(holdService);
    }

    // --- releaseTicket (deletes the caller's hold) ---

    @Test
    void releaseTicket_deletesTheCallersHold() {
        when(ticketRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(ticket));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(holdService.release(1L, 1L, 42L)).thenReturn(TicketHoldService.ReleaseResult.RELEASED);
        authenticateAs("alice");

        TicketResponse result = ticketService.releaseTicket(1L);

        assertThat(result.getStatus()).isEqualTo(TicketStatus.AVAILABLE);
        assertThat(result.getUserId()).isNull();
        assertThat(result.getHoldExpiresAt()).isNull();
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void releaseTicket_throwsAccessDeniedWhenHeldByAnotherUser() {
        when(ticketRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(ticket));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(holdService.release(1L, 1L, 42L)).thenReturn(TicketHoldService.ReleaseResult.HELD_BY_OTHER);
        authenticateAs("alice");

        assertThatThrownBy(() -> ticketService.releaseTicket(1L)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void releaseTicket_throwsWhenNotHeldOrTheHoldExpired() {
        when(ticketRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(ticket));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(holdService.release(1L, 1L, 42L)).thenReturn(TicketHoldService.ReleaseResult.NOT_HELD);
        authenticateAs("alice");

        assertThatThrownBy(() -> ticketService.releaseTicket(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not RESERVED");
    }

    @Test
    void releaseTicket_throwsWhenNotFound() {
        when(ticketRepository.findByIdWithDetails(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ticketService.releaseTicket(99L))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(holdService);
    }

    // --- cancelTicket ---

    @Test
    void cancelTicket_succeedsWhenSoldToSameUser() {
        ticket.setStatus(TicketStatus.SOLD);
        ticket.setUserId(42L);
        when(ticketRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(ticket));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ticketOperationRepository.save(any(TicketOperation.class))).thenAnswer(inv -> inv.getArgument(0));
        authenticateAs("alice");

        TicketResponse result = ticketService.cancelTicket(1L);

        // cancelling moves the ticket back to AVAILABLE and clears its owner (the CANCELLED
        // status has been removed — cancellation is now tracked via the TicketOperation log)
        assertThat(result.getStatus()).isEqualTo(TicketStatus.AVAILABLE);
        assertThat(result.getUserId()).isNull();

        ArgumentCaptor<TicketOperation> captor = ArgumentCaptor.forClass(TicketOperation.class);
        verify(ticketOperationRepository).save(captor.capture());
        TicketOperation recorded = captor.getValue();
        assertThat(recorded.getEventId()).isEqualTo(1L);
        assertThat(recorded.getTicketId()).isEqualTo(1L);
        assertThat(recorded.getUserId()).isEqualTo(42L);
        assertThat(recorded.getOperation()).isEqualTo(TicketOperationType.CANCEL);
        assertThat(recorded.getPurchasePrice()).isEqualByComparingTo("99.99");
        assertThat(recorded.getPaymentService()).isEqualTo("Square");
        assertThat(recorded.getPaymentConfirmationDetails()).isEqualTo("payment confirmation 1234");
        verify(cassandraAsyncWriter).saveTicketOperation(any(CassandraTicketOperation.class));
        // The Cassandra row is updated to the cancelled state: AVAILABLE, no owner.
        ArgumentCaptor<CassandraTicket> mirrored = ArgumentCaptor.forClass(CassandraTicket.class);
        verify(cassandraAsyncWriter).saveTicket(mirrored.capture(), any(java.time.Instant.class));
        assertThat(mirrored.getValue().getId()).isEqualTo(1L);
        assertThat(mirrored.getValue().getStatus()).isEqualTo("AVAILABLE");
        assertThat(mirrored.getValue().getUserId()).isNull();
    }

    @Test
    void cancelTicket_throwsWhenNotCurrentlySold() {
        // ticket is AVAILABLE (never purchased); the status check happens before any JWT lookup...
        // except cancelTicket now resolves currentUserId() up front (to pass to cancel()), so the
        // JWT lookup does happen before the status check — unlike the pre-refactor version.
        when(ticketRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(ticket));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        authenticateAs("alice");

        assertThatThrownBy(() -> ticketService.cancelTicket(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SOLD");
        verify(ticketRepository, never()).save(any());
        verify(ticketOperationRepository, never()).save(any());
    }

    @Test
    void cancelTicket_throwsAccessDeniedWhenPurchasedByDifferentUser() {
        ticket.setStatus(TicketStatus.SOLD);
        ticket.setUserId(42L); // purchased by alice
        when(ticketRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(ticket));
        User bob = User.builder().id(99L).username("bob").build();
        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(bob));
        authenticateAs("bob");

        assertThatThrownBy(() -> ticketService.cancelTicket(1L))
                .isInstanceOf(AccessDeniedException.class);
        verify(ticketRepository, never()).save(any());
        verify(ticketOperationRepository, never()).save(any());
    }

    @Test
    void cancelTicket_throwsWhenNotFound() {
        // findTicketOrThrow throws before currentUserId() is ever resolved — no userRepository
        // stub or authenticateAs call needed here (would trip UnnecessaryStubbingException).
        when(ticketRepository.findByIdForUpdate(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ticketService.cancelTicket(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Ticket");
        verify(ticketRepository, never()).save(any());
        verify(ticketOperationRepository, never()).save(any());
    }

    // --- createAvailableTickets ---

    @Test
    void createAvailableTickets_createsOneAvailableTicketPerUnitOfCapacity() {
        ArgumentCaptor<List<Ticket>> captor = ArgumentCaptor.forClass(List.class);
        // The database assigns ids on insert; simulate ids continuing from earlier events.
        when(ticketRepository.saveAll(any())).thenAnswer(inv -> {
            List<Ticket> in = inv.getArgument(0);
            for (int i = 0; i < in.size(); i++) in.get(i).setId(60004L + i);
            return in;
        });

        ticketService.createAvailableTickets(event, 3);

        verify(ticketRepository).saveAll(captor.capture());
        List<Ticket> created = captor.getValue();
        assertThat(created).hasSize(3);
        assertThat(created).allSatisfy(t -> {
            assertThat(t.getEvent()).isEqualTo(event);
            assertThat(t.getStatus()).isEqualTo(TicketStatus.AVAILABLE);
            assertThat(t.getUserId()).isNull();
        });
        // Descriptions are written after the insert, padded to the largest new id (60006 → 5 digits).
        verify(ticketRepository).writeTicketDescriptions(1L, 5, " Open Seating");
    }

    @Test
    void describe_showsSecondsForHoldsShorterThanAMinute() {
        assertThat(TicketService.describe(java.time.Duration.ofMinutes(10))).isEqualTo("10 minutes");
        assertThat(TicketService.describe(java.time.Duration.ofMinutes(1))).isEqualTo("1 minute");
        assertThat(TicketService.describe(java.time.Duration.ofSeconds(20))).isEqualTo("20 seconds");
    }

    @Test
    void ticketNumberWidth_isTheDigitCountOfTheEventsLargestTicketId() {
        assertThat(TicketService.ticketNumberWidth(9000, 9000)).isEqualTo(4);
        assertThat(TicketService.ticketNumberWidth(60000, 60000)).isEqualTo(5);
        // Ids are global: a 12-ticket event created after others still needs 5 digits.
        assertThat(TicketService.ticketNumberWidth(60015, 12)).isEqualTo(5);
        assertThat(TicketService.ticketNumberWidth(0, 3)).isEqualTo(1);
    }

    // --- backupAndDeleteAllForEvent ---

    @Test
    void backupAndDeleteAllForEvent_backsUpOnlySoldTicketsThenDeletesAllFromPostgres() {
        ReflectionTestUtils.setField(ticketService, "ticketCassandraRepository", ticketCassandraRepository);
        LocalDateTime purchasedAt = LocalDateTime.of(2025, 8, 1, 12, 0);
        Ticket soldTicket = Ticket.builder().id(2L).event(event).status(TicketStatus.SOLD)
                .userId(42L).datePurchased(purchasedAt).build();
        when(ticketRepository.findByEventId(1L)).thenReturn(List.of(ticket, soldTicket));

        ticketService.backupAndDeleteAllForEvent(1L);

        // Only the SOLD ticket is archived — AVAILABLE/RESERVED/PAYING tickets are ignored by the sweep.
        ArgumentCaptor<List<CassandraTicket>> captor = ArgumentCaptor.forClass(List.class);
        verify(ticketCassandraRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        CassandraTicket backedUp = captor.getValue().get(0);
        assertThat(backedUp.getId()).isEqualTo(2L);
        assertThat(backedUp.getEventId()).isEqualTo(1L);
        assertThat(backedUp.getStatus()).isEqualTo("SOLD");
        assertThat(backedUp.getDatePurchased()).isEqualTo(purchasedAt);
        // But every ticket, regardless of status, is still deleted from Postgres.
        verify(ticketRepository).deleteAll(List.of(ticket, soldTicket));
        // Ticket operations are removed from Postgres too, but never from Cassandra — see
        // TicketService#backupAndDeleteAllForEvent.
        verify(ticketOperationRepository).deleteByEventId(1L);
    }

    @Test
    void backupAndDeleteAllForEvent_skipsCassandraEntirelyWhenNoTicketsAreSold() {
        ReflectionTestUtils.setField(ticketService, "ticketCassandraRepository", ticketCassandraRepository);
        when(ticketRepository.findByEventId(1L)).thenReturn(List.of(ticket));

        ticketService.backupAndDeleteAllForEvent(1L);

        verifyNoInteractions(ticketCassandraRepository);
        verify(ticketRepository).deleteAll(List.of(ticket));
        verify(ticketOperationRepository).deleteByEventId(1L);
    }

    @Test
    void backupAndDeleteAllForEvent_skipsCassandraWhenRepositoryAbsent() {
        // ticketCassandraRepository left null, as it is in production when Cassandra
        // autoconfiguration is excluded (test profile)
        Ticket soldTicket = Ticket.builder().id(2L).event(event).status(TicketStatus.SOLD).userId(42L).build();
        when(ticketRepository.findByEventId(1L)).thenReturn(List.of(soldTicket));

        ticketService.backupAndDeleteAllForEvent(1L);

        verify(ticketRepository).deleteAll(List.of(soldTicket));
        verify(ticketOperationRepository).deleteByEventId(1L);
    }

    @Test
    void backupAndDeleteAllForEvent_doesNothingToTicketsWhenEventHasNoneButStillDeletesOperations() {
        ReflectionTestUtils.setField(ticketService, "ticketCassandraRepository", ticketCassandraRepository);
        when(ticketRepository.findByEventId(1L)).thenReturn(List.of());

        ticketService.backupAndDeleteAllForEvent(1L);

        verify(ticketRepository, never()).deleteAll(any());
        verifyNoInteractions(ticketCassandraRepository);
        // Ticket-operation cleanup is unconditional — not gated on whether any tickets remain.
        verify(ticketOperationRepository).deleteByEventId(1L);
    }
}
