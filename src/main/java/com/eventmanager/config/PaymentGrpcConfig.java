package com.eventmanager.config;

import com.eventmanager.grpc.payment.PaymentServiceGrpc;
import com.eventmanager.payment.PaymentServiceProperties;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Builds the gRPC channel/stub used to call the external payment service (see
 * {@code com.eventmanager.payment.PaymentServiceClient} and docs/payment-service-integration.md).
 * A {@link ManagedChannel} is lazy — creating this bean does not itself open a connection, so
 * this config loads safely in the test profile (no payment service running) as long as no test
 * actually invokes an RPC through the stub. Uses plaintext (no TLS) to match this project's other
 * local/dev-profile infrastructure connections (Postgres, Redis, Cassandra, Kafka all run
 * unencrypted here too) — a real deployment would enable TLS and client auth here instead.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class PaymentGrpcConfig {

    private final PaymentServiceProperties properties;

    private ManagedChannel channel;

    @Bean
    public ManagedChannel paymentServiceChannel() {
        channel = NettyChannelBuilder.forAddress(properties.getHost(), properties.getPort())
                .usePlaintext()
                .build();
        return channel;
    }

    @Bean
    public PaymentServiceGrpc.PaymentServiceBlockingStub paymentServiceBlockingStub(ManagedChannel paymentServiceChannel) {
        return PaymentServiceGrpc.newBlockingStub(paymentServiceChannel);
    }

    @PreDestroy
    public void shutdown() {
        if (channel == null) {
            return;
        }
        channel.shutdown();
        try {
            if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                channel.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            channel.shutdownNow();
        }
    }
}
