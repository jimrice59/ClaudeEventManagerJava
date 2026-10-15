package com.eventmanager.grpcserver;

import com.eventmanager.dto.EventResponse;
import com.eventmanager.dto.TicketResponse;
import com.eventmanager.exception.ResourceNotFoundException;
import com.eventmanager.exception.TicketPaymentInProgressException;
import com.eventmanager.grpc.api.EventGrpcServiceGrpc;
import com.eventmanager.grpc.api.EventMessage;
import com.eventmanager.grpc.api.GetEventRequest;
import com.eventmanager.grpc.api.PurchaseTicketGrpcRequest;
import com.eventmanager.grpc.api.TicketGrpcServiceGrpc;
import com.eventmanager.grpc.api.TicketIdRequest;
import com.eventmanager.grpc.api.TicketMessage;
import com.eventmanager.model.Role;
import com.eventmanager.model.TicketStatus;
import com.eventmanager.model.User;
import com.eventmanager.repository.UserRepository;
import com.eventmanager.security.JwtTokenProvider;
import com.eventmanager.security.UserDetailsServiceImpl;
import com.eventmanager.service.EventService;
import com.eventmanager.service.TicketService;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
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

    private ManagedChannel channel;
    private EventGrpcServiceGrpc.EventGrpcServiceBlockingStub eventStub;
    private TicketGrpcServiceGrpc.TicketGrpcServiceBlockingStub ticketStub;
    private String validToken;

    @BeforeEach
    void setUp() {
        channel = ManagedChannelBuilder.forAddress("localhost", grpcServerLifecycle.getBoundPort())
                .usePlaintext()
                .build();
        eventStub = EventGrpcServiceGrpc.newBlockingStub(channel);
        ticketStub = TicketGrpcServiceGrpc.newBlockingStub(channel);

        if (userRepository.findByUsername(TEST_USERNAME).isEmpty()) {
            userRepository.save(User.builder()
                    .username(TEST_USERNAME)
                    .email("grpc-test-user@example.com")
                    .password("irrelevant-not-used-by-jwt-login")
                    .role(Role.ROLE_USER)
                    .build());
        }
        UserDetails userDetails = userDetailsService.loadUserByUsername(TEST_USERNAME);
        Authentication authentication =
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        validToken = jwtTokenProvider.generateToken(authentication);
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        SecurityContextHolder.clearContext();
    }

    private TicketGrpcServiceGrpc.TicketGrpcServiceBlockingStub withBearerToken(
            TicketGrpcServiceGrpc.TicketGrpcServiceBlockingStub stub, String token) {
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
}
