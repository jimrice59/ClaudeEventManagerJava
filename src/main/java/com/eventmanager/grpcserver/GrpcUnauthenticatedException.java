package com.eventmanager.grpcserver;

/**
 * Thrown by {@link GrpcCallSupport#runAuthenticated} when an RPC that requires authentication is
 * called with no valid JWT — the gRPC equivalent of REST's {@code BearerTokenAuthenticationEntryPoint}
 * 401. There is no REST-side equivalent exception to reuse: on the servlet side, an unauthenticated
 * request is rejected by the Spring Security filter chain before it ever reaches a controller, but
 * gRPC has no filter-chain/{@code @PreAuthorize} layer — each RPC method checks for itself via
 * {@link GrpcCallSupport#runAuthenticated}.
 */
class GrpcUnauthenticatedException extends RuntimeException {

    GrpcUnauthenticatedException() {
        super("This RPC requires authentication — send a valid JWT as gRPC metadata \"authorization: Bearer <token>\"");
    }
}
