package com.eventmanager.grpcserver;

import com.eventmanager.security.JwtTokenProvider;
import com.eventmanager.security.UserDetailsServiceImpl;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

/**
 * Validates an {@code authorization: Bearer <jwt>} gRPC metadata header using the same
 * {@link JwtTokenProvider}/{@link UserDetailsServiceImpl} {@code JwtAuthenticationFilter} uses for
 * the REST API — same custom HS256 JWT, same user lookup, just read from gRPC
 * {@link Metadata} instead of an HTTP header, since none of the servlet filter chain
 * (RateLimitFilter, JwtAuthenticationFilter, the {@code @Order} chains — see
 * architecture-overview.md) sees gRPC traffic at all; this interceptor is the entire auth layer
 * for the gRPC server.
 * <p>
 * No token at all is <b>not</b> rejected here — public RPCs (GetEvent, ListEvents, GetTicket,
 * GetAvailableTicketCount) must still work anonymously. A present-but-invalid/expired token is
 * rejected immediately with {@code UNAUTHENTICATED}, matching the REST API's behavior for a bad
 * bearer token. Each RPC that actually requires authentication enforces that itself via
 * {@link GrpcCallSupport#runAuthenticated} — there is no gRPC equivalent of
 * {@code @PreAuthorize}, so this is deliberately permissive at the transport layer and strict at
 * the call layer, the inverse of the REST filter chain's "deny by default, allow specific public
 * paths" shape.
 */
@Component
@RequiredArgsConstructor
public class JwtServerInterceptor implements ServerInterceptor {

    private static final Metadata.Key<String> AUTHORIZATION_KEY =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final JwtTokenProvider tokenProvider;
    private final UserDetailsServiceImpl userDetailsService;

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        String bearer = headers.get(AUTHORIZATION_KEY);
        String jwt = (bearer != null && bearer.startsWith("Bearer ")) ? bearer.substring(7) : null;

        if (jwt == null) {
            return Contexts.interceptCall(Context.current(), call, headers, next);
        }
        if (!tokenProvider.validateToken(jwt)) {
            call.close(Status.UNAUTHENTICATED.withDescription("Invalid or expired token"), new Metadata());
            return new ServerCall.Listener<>() {
            };
        }

        String username = tokenProvider.getUsernameFromToken(jwt);
        UserDetails userDetails = userDetailsService.loadUserByUsername(username);
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());

        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(authentication);

        Context context = Context.current().withValue(GrpcCallSupport.SECURITY_CONTEXT_KEY, securityContext);
        return Contexts.interceptCall(context, call, headers, next);
    }
}
