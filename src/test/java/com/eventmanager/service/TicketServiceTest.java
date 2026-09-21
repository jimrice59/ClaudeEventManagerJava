package com.eventmanager.service;

import com.eventmanager.cassandra.model.CassandraTicket;
import com.eventmanager.cassandra.model.CassandraTicketOperation;
import com.eventmanager.cassandra.repository.TicketCassandraRepository;
import com.eventmanager.dto.PagedResponse;
import com.eventmanager.dto.TicketResponse;
import com.eventmanager.exception.ResourceNotFoundException;
import com.eventmanager.model.Event;
import com.eventmanager.model.Performer;
import com.eventmanager.model.Ticket;
import com.eventmanager.model.TicketOperation;
import com.eventmanager.model.TicketOperationType;
import com.eventmanager.model.TicketStatus;
import com.eventmanager.model.User;
import com.eventmanager.model.Venue;
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

    private TicketService ticketService;

    private Venue venue;
    private Performer performer;
    private Event event;
    private Ticket ticket;
    private User alice;

    @BeforeEach
    void setUp() {
        ticketService = new TicketService(ticketRepository, eventRepository, userRepository,
                ticketOperationRepository, cassandraAsyncWriter);
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
        when(ticketRepository.countByEventIdAndStatus(1L, TicketStatus.AVAILABLE)).thenReturn(37L);

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
        when(ticketRepository.findByEventIdAndStatus(eq(1L), eq(TicketStatus.AVAILABLE), any(Pageable.class)))
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
        when(ticketRepository.findByEventIdAndStatus(eq(1L), eq(TicketStatus.AVAILABLE), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        ticketService.getAvailableTickets(1L, 0, 5000);

        verify(ticketRepository).findByEventIdAndStatus(eq(1L), eq(TicketStatus.AVAILABLE),
                argThat(pageable -> pageable.getPageSize() == 100));
    }

    // --- getMyTickets ---

    @Test
    void getMyTickets_returnsPagedResponseForCurrentUser() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        authenticateAs("alice");
        Page<Ticket> page = new PageImpl<>(List.of(ticket));
        when(ticketRepository.findByUserId(eq(42L), any(Pageable.class))).thenReturn(page);

        PagedResponse<TicketResponse> result = ticketService.getMyTickets(0, 20);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getEventName()).isEqualTo("Summer Rock Festival");
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    void getMyTickets_clampsOversizedPageSize() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        authenticateAs("alice");
        when(ticketRepository.findByUserId(eq(42L), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        ticketService.getMyTickets(0, 5000);

        verify(ticketRepository).findByUserId(eq(42L), argThat(pageable -> pageable.getPageSize() == 100));
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

    // --- reserveTicket ---

    @Test
    void reserveTicket_succeedsAndStampsCallersUserId() {
        when(ticketRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(ticket));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));
        authenticateAs("alice");

        TicketResponse result = ticketService.reserveTicket(1L);

        assertThat(result.getStatus()).isEqualTo(TicketStatus.RESERVED);
        assertThat(result.getUserId()).isEqualTo(42L);
    }

    @Test
    void reserveTicket_throwsWhenNotCurrentlyAvailable() {
        ticket.setStatus(TicketStatus.SOLD);
        when(ticketRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(ticket));
        authenticateAs("alice");

        assertThatThrownBy(() -> ticketService.reserveTicket(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AVAILABLE");
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void reserveTicket_throwsWhenNotFound() {
        when(ticketRepository.findByIdWithDetails(99L)).thenReturn(Optional.empty());
        authenticateAs("alice");

        assertThatThrownBy(() -> ticketService.reserveTicket(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Ticket");
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void reserveTicket_throwsWhenAuthenticatedUsernameHasNoMatchingUserRow() {
        when(ticketRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(ticket));
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());
        authenticateAs("ghost");

        assertThatThrownBy(() -> ticketService.reserveTicket(1L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("User");
        verify(ticketRepository, never()).save(any());
    }

    // --- releaseTicket ---

    @Test
    void releaseTicket_succeedsAndClearsUserIdWhenReservedBySameUser() {
        ticket.setStatus(TicketStatus.RESERVED);
        ticket.setUserId(42L);
        when(ticketRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(ticket));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));
        authenticateAs("alice");

        TicketResponse result = ticketService.releaseTicket(1L);

        assertThat(result.getStatus()).isEqualTo(TicketStatus.AVAILABLE);
        assertThat(result.getUserId()).isNull();
    }

    @Test
    void releaseTicket_throwsWhenNotCurrentlyReserved() {
        // ticket is AVAILABLE (never reserved); the status check happens before any JWT lookup
        when(ticketRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> ticketService.releaseTicket(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RESERVED");
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void releaseTicket_throwsAccessDeniedWhenReservedByDifferentUser() {
        ticket.setStatus(TicketStatus.RESERVED);
        ticket.setUserId(42L); // reserved by alice
        when(ticketRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(ticket));
        User bob = User.builder().id(99L).username("bob").build();
        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(bob));
        authenticateAs("bob");

        assertThatThrownBy(() -> ticketService.releaseTicket(1L))
                .isInstanceOf(AccessDeniedException.class);
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void releaseTicket_throwsWhenNotFound() {
        when(ticketRepository.findByIdWithDetails(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ticketService.releaseTicket(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Ticket");
        verify(ticketRepository, never()).save(any());
    }

    // --- purchaseTicket ---

    @Test
    void purchaseTicket_succeedsWhenReservedBySameUser() {
        ticket.setStatus(TicketStatus.RESERVED);
        ticket.setUserId(42L);
        when(ticketRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(ticket));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ticketOperationRepository.save(any(TicketOperation.class))).thenAnswer(inv -> inv.getArgument(0));
        authenticateAs("alice");

        TicketResponse result = ticketService.purchaseTicket(1L, "confirmation-token-abc123");

        assertThat(result.getStatus()).isEqualTo(TicketStatus.SOLD);
        assertThat(result.getUserId()).isEqualTo(42L);

        ArgumentCaptor<TicketOperation> captor = ArgumentCaptor.forClass(TicketOperation.class);
        verify(ticketOperationRepository).save(captor.capture());
        TicketOperation recorded = captor.getValue();
        assertThat(recorded.getEventId()).isEqualTo(1L);
        assertThat(recorded.getTicketId()).isEqualTo(1L);
        assertThat(recorded.getUserId()).isEqualTo(42L);
        assertThat(recorded.getOperation()).isEqualTo(TicketOperationType.PURCHASE);
        assertThat(recorded.getPurchasePrice()).isEqualByComparingTo("99.99");
        assertThat(recorded.getPaymentService()).isEqualTo("Square");
        assertThat(recorded.getPaymentConfirmationDetails()).isEqualTo("payment confirmation 1234");
        assertThat(recorded.getOperationDate()).isNotNull();
        verify(cassandraAsyncWriter).saveTicketOperation(any(CassandraTicketOperation.class));
    }

    @Test
    void purchaseTicket_throwsWhenNotCurrentlyReserved() {
        // ticket is AVAILABLE (never reserved)
        when(ticketRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(ticket));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        authenticateAs("alice");

        assertThatThrownBy(() -> ticketService.purchaseTicket(1L, "confirmation-token-abc123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RESERVED");
        verify(ticketRepository, never()).save(any());
        verify(ticketOperationRepository, never()).save(any());
    }

    @Test
    void purchaseTicket_throwsAccessDeniedWhenReservedByDifferentUser() {
        ticket.setStatus(TicketStatus.RESERVED);
        ticket.setUserId(42L); // reserved by alice
        when(ticketRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(ticket));
        User bob = User.builder().id(99L).username("bob").build();
        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(bob));
        authenticateAs("bob");

        assertThatThrownBy(() -> ticketService.purchaseTicket(1L, "confirmation-token-abc123"))
                .isInstanceOf(AccessDeniedException.class);
        verify(ticketRepository, never()).save(any());
        verify(ticketOperationRepository, never()).save(any());
    }

    @Test
    void purchaseTicket_throwsWhenNotFound() {
        when(ticketRepository.findByIdWithDetails(99L)).thenReturn(Optional.empty());
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        authenticateAs("alice");

        assertThatThrownBy(() -> ticketService.purchaseTicket(99L, "confirmation-token-abc123"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Ticket");
        verify(ticketRepository, never()).save(any());
        verify(ticketOperationRepository, never()).save(any());
    }

    // --- cancelTicket ---

    @Test
    void cancelTicket_succeedsWhenSoldToSameUser() {
        ticket.setStatus(TicketStatus.SOLD);
        ticket.setUserId(42L);
        when(ticketRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(ticket));
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
    }

    @Test
    void cancelTicket_throwsWhenNotCurrentlySold() {
        // ticket is AVAILABLE (never purchased); the status check happens before any JWT lookup...
        // except cancelTicket now resolves currentUserId() up front (to pass to cancel()), so the
        // JWT lookup does happen before the status check — unlike the pre-refactor version.
        when(ticketRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(ticket));
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
        when(ticketRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(ticket));
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
        when(ticketRepository.findByIdWithDetails(99L)).thenReturn(Optional.empty());

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

        ticketService.createAvailableTickets(event, 3);

        verify(ticketRepository).saveAll(captor.capture());
        List<Ticket> created = captor.getValue();
        assertThat(created).hasSize(3);
        assertThat(created).allSatisfy(t -> {
            assertThat(t.getEvent()).isEqualTo(event);
            assertThat(t.getStatus()).isEqualTo(TicketStatus.AVAILABLE);
            assertThat(t.getUserId()).isNull();
        });
    }

    // --- backupAndDeleteAllForEvent ---

    @Test
    void backupAndDeleteAllForEvent_backsUpToCassandraThenDeletesFromPostgres() {
        ReflectionTestUtils.setField(ticketService, "ticketCassandraRepository", ticketCassandraRepository);
        when(ticketRepository.findByEventId(1L)).thenReturn(List.of(ticket));

        ticketService.backupAndDeleteAllForEvent(1L);

        ArgumentCaptor<List<CassandraTicket>> captor = ArgumentCaptor.forClass(List.class);
        verify(ticketCassandraRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        CassandraTicket backedUp = captor.getValue().get(0);
        assertThat(backedUp.getId()).isEqualTo(1L);
        assertThat(backedUp.getEventId()).isEqualTo(1L);
        assertThat(backedUp.getStatus()).isEqualTo("AVAILABLE");
        verify(ticketRepository).deleteAll(List.of(ticket));
        // Ticket operations are removed from Postgres too, but never from Cassandra — see
        // TicketService#backupAndDeleteAllForEvent.
        verify(ticketOperationRepository).deleteByEventId(1L);
    }

    @Test
    void backupAndDeleteAllForEvent_skipsCassandraWhenRepositoryAbsent() {
        // ticketCassandraRepository left null, as it is in production when Cassandra
        // autoconfiguration is excluded (test profile)
        when(ticketRepository.findByEventId(1L)).thenReturn(List.of(ticket));

        ticketService.backupAndDeleteAllForEvent(1L);

        verify(ticketRepository).deleteAll(List.of(ticket));
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
