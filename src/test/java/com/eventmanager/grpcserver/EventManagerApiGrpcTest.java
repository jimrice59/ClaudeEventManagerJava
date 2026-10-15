package com.eventmanager.grpcserver;

import com.eventmanager.dto.EventResponse;
import com.eventmanager.dto.PerformerDto;
import com.eventmanager.dto.TicketResponse;
import com.eventmanager.dto.VenueDto;
import com.eventmanager.exception.ResourceNotFoundException;
import com.eventmanager.exception.TicketPaymentInProgressException;
import com.eventmanager.grpc.api.CreateEventRequest;
import com.eventmanager.grpc.api.CreateVenueRequest;
import com.eventmanager.grpc.api.EventGrpcServiceGrpc;
import com.eventmanager.grpc.api.EventMessage;
import com.eventmanager.grpc.api.GetEventRequest;
import com.eventmanager.grpc.api.PerformerGrpcServiceGrpc;
import com.eventmanager.grpc.api.PerformerIdRequest;
import com.eventmanager.grpc.api.PerformerMessage;
import com.eventmanager.grpc.api.PurchaseTicketGrpcRequest;
import com.eventmanager.grpc.api.TicketGrpcServiceGrpc;
import com.eventmanager.grpc.api.TicketIdRequest;
import com.eventmanager.grpc.api.TicketMessage;
import com.eventmanager.grpc.api.VenueGrpcServiceGrpc;
import com.eventmanager.grpc.api.VenueIdRequest;
import com.eventmanager.grpc.api.VenueMessage;
import com.eventmanager.model.Role;
import com.eventmanager.model.TicketStatus;
import com.eventmanager.model.User;
import com.eventmanager.repository.UserRepository;
import com.eventmanager.security.JwtTokenProvider;
import com.eventmanager.security.UserDetailsServiceImpl;
import com.eventmanager.service.EventService;
import com.eventmanager.service.PerformerService;
import com.eventmanager.service.TicketService;
import com.eventmanager.service.VenueService;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.AbstractStub;
import io.grpc.stub.MetadataUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * In-process-style integration test (real sockets on an OS-assigned port, not grpc-java's
 * InProcessTransport, since the thing under test is the real {@link GrpcServerLifecycle}/
 * {@link JwtServerInterceptor} wiring) proving three things no unit test on the individual
 * classes can: (1) a public RPC works with no token, (2) an authenticated-only RPC is rejected
 * with UNAUTHENTICATED with no token and actually resolves the right caller with a real JWT —
 * verifying the gRPC {@code Context}→{@code SecurityContextHolder} bridge in
 * {@link GrpcCallSupport} works across the real async dispatch (not just the same thread
 * {@link JwtServerInterceptor} ran on), and (3) a thrown business exception maps to the right
 * {@link Status} code over the wire. {@link EventService}/{@link TicketService} are
 * {@code @MockBean}s — this proves the transport/auth plumbing, not business logic (already
 * covered by {@code EventServiceTest}/{@code TicketServiceTest}).
 */
@SpringBootTest(properties = {"grpc-api.enabled=true", "grpc-api.port=0"})
@ActiveProfiles("test")
class EventManagerApiGrpcTest {

    private static final String TEST_USERNAME = "grpc-test-user";
    private static final String TEST_ADMIN_USERNAME = "grpc-test-admin";

    @Autowired
    private GrpcServerLifecycle grpcServerLifecycle;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private UserDetailsServiceImpl userDetailsService;

    @Autowired
    private UserRepository userRepository;

    @MockBean
    private EventService eventService;

    @MockBean
    private TicketService ticketService;

    @MockBean
    private VenueService venueService;

    @MockBean
    private PerformerService performerService;

    private ManagedChannel channel;
    private EventGrpcServiceGrpc.EventGrpcServiceBlockingStub eventStub;
    private TicketGrpcServiceGrpc.TicketGrpcServiceBlockingStub ticketStub;
    private VenueGrpcServiceGrpc.VenueGrpcServiceBlockingStub venueStub;
    private PerformerGrpcServiceGrpc.PerformerGrpcServiceBlockingStub performerStub;
    private String validToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        channel = ManagedChannelBuilder.forAddress("localhost", grpcServerLifecycle.getBoundPort())
                .usePlaintext()
                .build();
        eventStub = EventGrpcServiceGrpc.newBlockingStub(channel);
        ticketStub = TicketGrpcServiceGrpc.newBlockingStub(channel);
        venueStub = VenueGrpcServiceGrpc.newBlockingStub(channel);
        performerStub = PerformerGrpcServiceGrpc.newBlockingStub(channel);

        validToken = tokenFor(TEST_USERNAME, Role.ROLE_USER);
        adminToken = tokenFor(TEST_ADMIN_USERNAME, Role.ROLE_ADMIN);
    }

    private String tokenFor(String username, Role role) {
        if (userRepository.findByUsername(username).isEmpty()) {
            userRepository.save(User.builder()
                    .username(username)
                    .email(username + "@example.com")
                    .password("irrelevant-not-used-by-jwt-login")
                    .role(role)
                    .build());
        }
        UserDetails userDetails = userDetailsService.loadUserByUsername(username);
        Authentication authentication =
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        return jwtTokenProvider.generateToken(authentication);
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        SecurityContextHolder.clearContext();
    }

    private <S extends AbstractStub<S>> S withBearerToken(S stub, String token) {
        Metadata metadata = new Metadata();
        metadata.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
        return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    }

    @Test
    void getEvent_worksWithNoTokenAtAll() {
        when(eventService.getEventById(1L)).thenReturn(
                EventResponse.builder().id(1L).name("Summer Rock Festival").build());

        EventMessage response = eventStub.getEvent(GetEventRequest.newBuilder().setId(1L).build());

        assertThat(response.getId()).isEqualTo(1L);
        assertThat(response.getName()).isEqualTo("Summer Rock Festival");
    }

    @Test
    void getEvent_mapsResourceNotFoundExceptionToNotFoundStatus() {
        when(eventService.getEventById(99L)).thenThrow(new ResourceNotFoundException("Event", "id", 99L));

        assertThatThrownBy(() -> eventStub.getEvent(GetEventRequest.newBuilder().setId(99L).build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isEqualTo(Status.Code.NOT_FOUND));
    }

    @Test
    void reserveTicket_rejectedWithUnauthenticatedWhenNoTokenPresented() {
        assertThatThrownBy(() -> ticketStub.reserveTicket(TicketIdRequest.newBuilder().setId(1L).build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isEqualTo(Status.Code.UNAUTHENTICATED));
    }

    @Test
    void reserveTicket_rejectedWithUnauthenticatedWhenTokenIsGarbage() {
        var stubWithBadToken = withBearerToken(ticketStub, "not-a-real-jwt");

        assertThatThrownBy(() -> stubWithBadToken.reserveTicket(TicketIdRequest.newBuilder().setId(1L).build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isEqualTo(Status.Code.UNAUTHENTICATED));
    }

    /**
     * The key correctness test: proves the gRPC {@code Context}-carried {@code SecurityContext}
     * set up by {@link JwtServerInterceptor} is actually in {@link SecurityContextHolder} by the
     * time {@link TicketGrpcServiceImpl#reserveTicket} runs — not just at the point
     * {@code interceptCall} itself executed, which is a different point in the dispatch (see
     * {@link GrpcCallSupport} javadoc).
     */
    @Test
    void reserveTicket_succeedsWithValidTokenAndCallerIsResolvedCorrectly() {
        when(ticketService.reserveTicket(1L)).thenAnswer(invocation -> {
            Authentication current = SecurityContextHolder.getContext().getAuthentication();
            assertThat(current).isNotNull();
            assertThat(current.getName()).isEqualTo(TEST_USERNAME);
            return TicketResponse.builder().id(1L).status(TicketStatus.RESERVED).userId(42L).build();
        });
        var authedStub = withBearerToken(ticketStub, validToken);

        TicketMessage response = authedStub.reserveTicket(TicketIdRequest.newBuilder().setId(1L).build());

        assertThat(response.getStatus()).isEqualTo("RESERVED");
        assertThat(response.getUserId()).isEqualTo(42L);
    }

    @Test
    void purchaseTicket_mapsTicketPaymentInProgressExceptionToFailedPrecondition() {
        when(ticketService.purchaseTicket(anyLong(), any(), any()))
                .thenThrow(new TicketPaymentInProgressException(1L));
        var authedStub = withBearerToken(ticketStub, validToken);

        assertThatThrownBy(() -> authedStub.purchaseTicket(PurchaseTicketGrpcRequest.newBuilder()
                        .setId(1L).setPaymentToken("pm_tok_abc123").build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isEqualTo(Status.Code.FAILED_PRECONDITION));
    }

    // --- Venue/Performer/admin-event-write RPCs: ROLE_ADMIN gating and validation ---

    @Test
    void getVenue_worksWithNoTokenAtAll() {
        when(venueService.getVenueById(1L)).thenReturn(
                VenueDto.builder().id(1L).name("Madison Square Garden").address("4 Pennsylvania Plaza")
                        .city("New York").state("NY").capacity(20000).build());

        VenueMessage response = venueStub.getVenue(VenueIdRequest.newBuilder().setId(1L).build());

        assertThat(response.getName()).isEqualTo("Madison Square Garden");
        assertThat(response.hasZipCode()).isFalse(); // not set above — proves optional presence isn't defaulted to ""
    }

    @Test
    void createVenue_rejectedWithPermissionDeniedForNonAdminCaller() {
        var authedStub = withBearerToken(venueStub, validToken); // ROLE_USER, not ROLE_ADMIN

        assertThatThrownBy(() -> authedStub.createVenue(CreateVenueRequest.newBuilder()
                        .setName("The Forum").setAddress("3900 W Manchester Blvd").setCity("Inglewood")
                        .setState("CA").setCapacity(17500).build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isEqualTo(Status.Code.PERMISSION_DENIED));
        verifyNoVenueServiceWrite();
    }

    @Test
    void createVenue_rejectedWithUnauthenticatedWhenNoToken() {
        assertThatThrownBy(() -> venueStub.createVenue(CreateVenueRequest.newBuilder()
                        .setName("The Forum").setAddress("3900 W Manchester Blvd").setCity("Inglewood")
                        .setState("CA").setCapacity(17500).build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isEqualTo(Status.Code.UNAUTHENTICATED));
        verifyNoVenueServiceWrite();
    }

    @Test
    void createVenue_succeedsForAdminCaller() {
        when(venueService.createVenue(any())).thenAnswer(invocation -> {
            VenueDto dto = invocation.getArgument(0);
            dto.setId(5L);
            return dto;
        });
        var adminStub = withBearerToken(venueStub, adminToken);

        VenueMessage response = adminStub.createVenue(CreateVenueRequest.newBuilder()
                .setName("The Forum").setAddress("3900 W Manchester Blvd").setCity("Inglewood")
                .setState("CA").setCapacity(17500).build());

        assertThat(response.getId()).isEqualTo(5L);
        assertThat(response.getName()).isEqualTo("The Forum");
    }

    /**
     * Proves {@link GrpcValidationSupport} actually runs — {@code VenueDto.name} is
     * {@code @NotBlank}; an empty name reaching {@code VenueService} unvalidated would be a
     * regression, since proto3 has no equivalent of {@code @Valid} to catch this for free.
     */
    @Test
    void createVenue_mapsConstraintViolationToInvalidArgument() {
        var adminStub = withBearerToken(venueStub, adminToken);

        assertThatThrownBy(() -> adminStub.createVenue(CreateVenueRequest.newBuilder()
                        .setName("") // blank — violates @NotBlank on VenueDto.name
                        .setAddress("3900 W Manchester Blvd").setCity("Inglewood").setState("CA")
                        .setCapacity(17500).build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isEqualTo(Status.Code.INVALID_ARGUMENT));
        verifyNoVenueServiceWrite();
    }

    private void verifyNoVenueServiceWrite() {
        verify(venueService, never()).createVenue(any());
        verify(venueService, never()).updateVenue(anyLong(), any());
        verify(venueService, never()).deleteVenue(anyLong());
    }

    @Test
    void getPerformer_worksWithNoTokenAtAll() {
        when(performerService.getPerformerById(1L)).thenReturn(
                PerformerDto.builder().id(1L).name("The Beatles").genre("Rock").build());

        PerformerMessage response = performerStub.getPerformer(PerformerIdRequest.newBuilder().setId(1L).build());

        assertThat(response.getName()).isEqualTo("The Beatles");
        assertThat(response.hasBio()).isFalse();
    }

    @Test
    void deletePerformer_rejectedWithPermissionDeniedForNonAdminCaller() {
        var authedStub = withBearerToken(performerStub, validToken);

        assertThatThrownBy(() -> authedStub.deletePerformer(PerformerIdRequest.newBuilder().setId(1L).build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isEqualTo(Status.Code.PERMISSION_DENIED));
        verify(performerService, never()).deletePerformer(anyLong());
    }

    @Test
    void deletePerformer_succeedsForAdminCaller() {
        var adminStub = withBearerToken(performerStub, adminToken);

        adminStub.deletePerformer(PerformerIdRequest.newBuilder().setId(1L).build());

        verify(performerService).deletePerformer(1L);
    }

    @Test
    void createEvent_rejectedWithPermissionDeniedForNonAdminCaller() {
        var authedStub = withBearerToken(eventStub, validToken);

        assertThatThrownBy(() -> authedStub.createEvent(CreateEventRequest.newBuilder()
                        .setName("Jazz Night").setEventDate("2025-09-01T20:00:00").setTicketPrice("45.00")
                        .setTicketsTotal(100).setVenueId(1L).build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isEqualTo(Status.Code.PERMISSION_DENIED));
        verify(eventService, never()).createEvent(any());
    }
}
