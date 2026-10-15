package com.eventmanager.grpcserver;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bound from {@code grpc-api.*} in application.yml (overridable via {@code GRPC_API_ENABLED}/
 * {@code GRPC_API_PORT} env vars). Distinct from this application's own HTTP port (8080) and from
 * the payment service it calls out to as a client (default 9095, see
 * {@code PaymentServiceProperties}) — this is the port *this* application's own gRPC server
 * listens on. {@code enabled} defaults to {@code false} under the test profile (see
 * application-test.yml) so {@code mvn test} never binds a real socket.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "grpc-api")
public class GrpcServerProperties {

    private boolean enabled = true;
    private int port = 9091;
}
