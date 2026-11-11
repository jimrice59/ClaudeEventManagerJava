package com.eventmanager.payment;

import com.eventmanager.grpc.payment.GetPaymentStatusRequest;
import com.eventmanager.grpc.payment.PaymentResponse;
import com.eventmanager.grpc.payment.PaymentServiceGrpc;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper around the generated {@link PaymentServiceGrpc.PaymentServiceBlockingStub}. Every
 * method here maps gRPC/proto types to this application's own {@link PaymentOutcome} — nothing
 * outside this class (and {@link com.eventmanager.config.PaymentGrpcConfig}) touches the
 * generated code, the same way {@code CassandraAsyncWriter} is the only place that touches
 * Cassandra repository types directly.
 *
 * <p>When {@code payment-service.enabled=false} (see {@link PaymentServiceProperties}), both
 * methods short-circuit before touching the stub and return a simulated {@code APPROVED}
 * outcome, so {@code TicketService}'s purchase saga runs unchanged (RESERVED → PAYING → SOLD,
 * PURCHASE operation recorded) without a payment service to call.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentServiceClient {

    private final PaymentServiceGrpc.PaymentServiceBlockingStub blockingStub;
    private final PaymentServiceProperties properties;

    /** Prefix on every simulated confirmation id, so simulated sales are easy to find in ticket_operations. */
    static final String SIMULATED_CONFIRMATION_PREFIX = "SIMULATED-";

    @PostConstruct
    void warnIfDisabled() {
        if (!properties.isEnabled()) {
            log.warn("Payment service calls are DISABLED (payment-service.enabled=false): every ticket "
                    + "purchase will be approved without charging anyone. Do not use this setting in production.");
        }
    }

    /**
     * Calls {@code PurchaseTicket} with the configured deadline (120 seconds by default — see
     * {@link PaymentServiceProperties#getDeadline()}). Never throws for a business decline or for
     * a call-level failure (timeout, connection error) — both come back as a {@link PaymentOutcome}
     * so {@code TicketService} can always run its second (completion) transaction instead of
     * needing a separate catch block for every failure mode.
     */
    public PaymentOutcome purchaseTicket(String paymentRequestId, Long userId, String paymentToken,
                                          Long ticketId, BigDecimal purchasePrice) {
        if (!properties.isEnabled()) {
            log.info("Payment service disabled — simulating APPROVED for paymentRequestId={}, ticketId={}, price={}",
                    paymentRequestId, ticketId, purchasePrice);
            return simulatedApproval(paymentRequestId);
        }
        com.eventmanager.grpc.payment.PurchaseTicketRequest request =
                com.eventmanager.grpc.payment.PurchaseTicketRequest.newBuilder()
                        .setPaymentRequestId(paymentRequestId)
                        .setUserId(userId)
                        .setPaymentToken(paymentToken)
                        .setTicketId(ticketId)
                        .setPurchasePrice(purchasePrice.toPlainString())
                        .build();
        try {
            PaymentResponse response = blockingStub
                    .withDeadlineAfter(properties.getDeadline().toMillis(), TimeUnit.MILLISECONDS)
                    .purchaseTicket(request);
            return toOutcome(response);
        } catch (StatusRuntimeException e) {
            log.error("Payment service call failed for paymentRequestId={}, ticketId={}: {}",
                    paymentRequestId, ticketId, e.getStatus(), e);
            return new PaymentOutcome(PaymentOutcomeStatus.ERROR, null, null);
        }
    }

    /**
     * Queries the outcome of a previous attempt by its {@code paymentRequestId} — the
     * reconciliation path for a ticket stuck in {@code PAYING} (the original call errored out, or
     * this application crashed before persisting the result). Not yet wired into an automatic
     * sweep job; see docs/payment-service-integration.md.
     */
    public PaymentOutcome getPaymentStatus(String paymentRequestId) {
        if (!properties.isEnabled()) {
            return simulatedApproval(paymentRequestId);
        }
        GetPaymentStatusRequest request = GetPaymentStatusRequest.newBuilder()
                .setPaymentRequestId(paymentRequestId)
                .build();
        try {
            PaymentResponse response = blockingStub
                    .withDeadlineAfter(properties.getConnectTimeout().toMillis(), TimeUnit.MILLISECONDS)
                    .getPaymentStatus(request);
            return toOutcome(response);
        } catch (StatusRuntimeException e) {
            log.error("Payment status query failed for paymentRequestId={}: {}", paymentRequestId, e.getStatus(), e);
            return new PaymentOutcome(PaymentOutcomeStatus.ERROR, null, null);
        }
    }

    /**
     * The idempotency token reuses the paymentRequestId, so a client retry with the same id gets
     * the same token back — mirroring what a real payment service does for a repeated request.
     */
    private PaymentOutcome simulatedApproval(String paymentRequestId) {
        return new PaymentOutcome(PaymentOutcomeStatus.APPROVED,
                SIMULATED_CONFIRMATION_PREFIX + UUID.randomUUID(), paymentRequestId);
    }

    private PaymentOutcome toOutcome(PaymentResponse response) {
        PaymentOutcomeStatus status = switch (response.getStatus()) {
            case APPROVED -> PaymentOutcomeStatus.APPROVED;
            case DECLINED -> PaymentOutcomeStatus.DECLINED;
            case PENDING -> PaymentOutcomeStatus.PENDING;
            default -> PaymentOutcomeStatus.ERROR;
        };
        return new PaymentOutcome(status, response.getConfirmationId(), response.getIdempotencyToken());
    }
}
