package com.eventmanager.payment;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Bound from {@code payment-service.*} in application.yml (overridable via
 * {@code PAYMENT_SERVICE_HOST}/{@code PAYMENT_SERVICE_PORT}/{@code PAYMENT_SERVICE_DEADLINE}
 * env vars). {@code deadline} is the per-call gRPC deadline on {@code PurchaseTicket} — the
 * "synchronous call with a timeout of 120 seconds" from the design discussion — kept separate
 * from {@code connectTimeout}, which only bounds how long establishing the TCP/TLS connection
 * itself may take; a connection failure should surface in seconds, not after waiting out the
 * whole 120-second call deadline.
 *
 * <p>{@code enabled} ({@code PAYMENT_SERVICE_ENABLED}, default {@code true}) is a toggle for the
 * outbound call itself: when {@code false}, {@link PaymentServiceClient} never contacts the
 * payment service and approves every purchase with a simulated confirmation — for local
 * development and demos, where docker-compose has no payment service to call. Never disable it
 * in a real deployment: every purchase would succeed without charging anyone.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "payment-service")
public class PaymentServiceProperties {

    private boolean enabled = true;
    private String host = "localhost";
    private int port = 9095;
    private Duration deadline = Duration.ofSeconds(120);
    private Duration connectTimeout = Duration.ofSeconds(5);
}
