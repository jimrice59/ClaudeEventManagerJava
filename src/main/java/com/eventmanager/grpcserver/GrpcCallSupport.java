package com.eventmanager.grpcserver;

import io.grpc.Context;
import io.grpc.stub.StreamObserver;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Shared wrapper every {@code *GrpcServiceImpl} RPC method body runs through — see
 * {@code EventGrpcServiceImpl}/{@code TicketGrpcServiceImpl}. Does two things an
 * {@code @PreAuthorize}-guarded REST controller method gets for free and a gRPC service impl
 * does not:
 * <ol>
 *   <li>Populates {@link SecurityContextHolder} for the duration of this call from the
 *       {@link SecurityContext} {@link JwtServerInterceptor} attached to the gRPC {@link Context}
 *       — <b>not</b> directly from the interceptor itself, since {@code interceptCall} only sets
 *       up the call's {@link io.grpc.ServerCall.Listener} and returns immediately; the actual RPC
 *       method body runs later (via that listener's {@code onHalfClose}), on whatever thread
 *       grpc-java's executor assigns, which is not necessarily the thread {@code interceptCall}
 *       ran on. gRPC's {@link Context} (unlike a raw {@link ThreadLocal}) is propagated correctly
 *       across that hop, so reading it back out here — and only then copying it into
 *       {@code SecurityContextHolder}, right as this specific call's body starts executing — is
 *       what makes {@code TicketService.currentUserId()} (which reads
 *       {@code SecurityContextHolder} directly, unaware it's being called from gRPC rather than a
 *       servlet request) resolve the right caller. Always cleared in a {@code finally}, since
 *       grpc-java's executor threads are pooled and reused across unrelated calls, the same reason
 *       Spring's servlet filter chain clears it per-request too.</li>
 *   <li>Maps any exception the action throws to the right gRPC {@link io.grpc.Status} via
 *       {@link GrpcExceptionMapper}, instead of it surfacing to the caller as an opaque
 *       {@code UNKNOWN}.</li>
 * </ol>
 */
final class GrpcCallSupport {

    /** Set by {@link JwtServerInterceptor} when a valid JWT was presented; absent otherwise. */
    static final Context.Key<SecurityContext> SECURITY_CONTEXT_KEY = Context.key("eventmanager-security-context");

    private GrpcCallSupport() {
    }

    /** For RPCs that work with or without authentication (the public reads). */
    static void run(StreamObserver<?> responseObserver, Runnable action) {
        SecurityContext fromContext = SECURITY_CONTEXT_KEY.get();
        try {
            if (fromContext != null) {
                SecurityContextHolder.setContext(fromContext);
            }
            action.run();
        } catch (Exception e) {
            responseObserver.onError(GrpcExceptionMapper.toStatusRuntimeException(e));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** For RPCs that require authentication — the gRPC equivalent of {@code @PreAuthorize("isAuthenticated()")}. */
    static void runAuthenticated(StreamObserver<?> responseObserver, Runnable action) {
        run(responseObserver, () -> {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !authentication.isAuthenticated()) {
                throw new GrpcUnauthenticatedException();
            }
            action.run();
        });
    }
}
