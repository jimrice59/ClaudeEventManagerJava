package com.eventmanager.payment;

import com.eventmanager.grpc.payment.GetPaymentStatusRequest;
import com.eventmanager.grpc.payment.PaymentResponse;
import com.eventmanager.grpc.payment.PaymentServiceGrpc;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper around the generated {@link PaymentServiceGrpc.PaymentServiceBlockingStub}. Every
 * method here maps gRPC/proto types to this application's own {@link PaymentOutcome} — nothing
 * outside this class (and {@link com.eventmanager.config.PaymentGrpcConfig}) touches the
 * generated code, the same way {@code CassandraAsyncWriter} is the only place that touches
 * Cassandra repository types directly.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentServiceClient {

    private final PaymentServiceGrpc.PaymentServiceBlockingStub blockingStub;
    private final PaymentServiceProperties properties;

    /**
     * Calls {@code PurchaseTicket} with the configured deadline (120 seconds by default — see
     * {@link PaymentServiceProperties#getDeadline()}). Never throws for a business decline or for
     * a call-level failure (timeout, connection error) — both come back as a {@link PaymentOutcome}
     * so {@code TicketService} can always run its second (completion) transaction instead of
     * needing a separate catch block for every failure mode.
     */
    public PaymentOutcome purchaseTicket(String paymentRequestId, Long userId, String paymentToken,
                                          Long ticketId, BigDecimal purchasePrice) {
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
