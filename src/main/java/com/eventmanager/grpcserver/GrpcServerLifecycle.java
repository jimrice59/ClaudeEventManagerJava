package com.eventmanager.grpcserver;

import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Starts and stops this application's own gRPC server (distinct from the gRPC *client* this
 * application runs to the payment service — see payment-service-grpc.md) alongside the Spring
 * context, using {@link SmartLifecycle} rather than a Spring Boot starter (the same hand-built
 * approach {@code PaymentGrpcConfig} uses on the client side — no third-party gRPC/Spring-Boot
 * integration dependency, just plain grpc-java).
 * <p>
 * Registers both {@link EventGrpcServiceImpl} and {@link TicketGrpcServiceImpl}, each wrapped
 * with {@link JwtServerInterceptor} via {@link ServerInterceptors#intercept}. Disabled entirely
 * under the test profile ({@code grpc-api.enabled: false} in application-test.yml) so
 * {@code mvn test} never binds a real socket — the same reason {@code rate-limit.enabled} is
 * false there too.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GrpcServerLifecycle implements SmartLifecycle {

    private final GrpcServerProperties properties;
    private final EventGrpcServiceImpl eventGrpcService;
    private final TicketGrpcServiceImpl ticketGrpcService;
    private final VenueGrpcServiceImpl venueGrpcService;
    private final PerformerGrpcServiceImpl performerGrpcService;
    private final JwtServerInterceptor jwtServerInterceptor;

    private Server server;
    private volatile boolean running;

    @Override
    public void start() {
        if (!properties.isEnabled()) {
            log.info("gRPC API server disabled (grpc-api.enabled=false)");
            return;
        }
        try {
            server = NettyServerBuilder.forPort(properties.getPort())
                    .addService(ServerInterceptors.intercept(eventGrpcService, jwtServerInterceptor))
                    .addService(ServerInterceptors.intercept(ticketGrpcService, jwtServerInterceptor))
                    .addService(ServerInterceptors.intercept(venueGrpcService, jwtServerInterceptor))
                    .addService(ServerInterceptors.intercept(performerGrpcService, jwtServerInterceptor))
                    .build()
                    .start();
            running = true;
            log.info("gRPC API server started on port {}", properties.getPort());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start gRPC API server on port " + properties.getPort(), e);
        }
    }

    @Override
    public void stop() {
        if (server == null) {
            return;
        }
        server.shutdown();
        try {
            if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
                server.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            server.shutdownNow();
        } finally {
            running = false;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** The actual bound port — useful when {@code grpc-api.port=0} (OS-assigned), e.g. in tests. */
    public int getBoundPort() {
        return server == null ? -1 : server.getPort();
    }
}
