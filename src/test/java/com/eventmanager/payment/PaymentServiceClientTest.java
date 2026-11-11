package com.eventmanager.payment;

import com.eventmanager.grpc.payment.PaymentResponse;
import com.eventmanager.grpc.payment.PaymentServiceGrpc;
import com.eventmanager.grpc.payment.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Covers the payment-service.enabled toggle: disabled never touches the gRPC stub. */
@ExtendWith(MockitoExtension.class)
class PaymentServiceClientTest {

    @Mock
    private PaymentServiceGrpc.PaymentServiceBlockingStub blockingStub;

    private PaymentServiceProperties properties;
    private PaymentServiceClient client;

    @BeforeEach
    void setUp() {
        properties = new PaymentServiceProperties();
        client = new PaymentServiceClient(blockingStub, properties);
    }

    @Test
    void purchaseTicket_whenDisabled_approvesWithoutCallingPaymentService() {
        properties.setEnabled(false);

        PaymentOutcome outcome = client.purchaseTicket("req-1", 7L, "tok", 4L, new BigDecimal("50.00"));

        assertThat(outcome.status()).isEqualTo(PaymentOutcomeStatus.APPROVED);
        assertThat(outcome.confirmationId()).startsWith(PaymentServiceClient.SIMULATED_CONFIRMATION_PREFIX);
        assertThat(outcome.idempotencyToken()).isEqualTo("req-1");
        verifyNoInteractions(blockingStub);
    }

    @Test
    void getPaymentStatus_whenDisabled_approvesWithoutCallingPaymentService() {
        properties.setEnabled(false);

        PaymentOutcome outcome = client.getPaymentStatus("req-1");

        assertThat(outcome.status()).isEqualTo(PaymentOutcomeStatus.APPROVED);
        verifyNoInteractions(blockingStub);
    }

    @Test
    void purchaseTicket_whenEnabled_callsPaymentServiceAndMapsResponse() {
        assertThat(properties.isEnabled()).as("enabled by default").isTrue();
        when(blockingStub.withDeadlineAfter(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(blockingStub);
        when(blockingStub.purchaseTicket(any())).thenReturn(PaymentResponse.newBuilder()
                .setStatus(PaymentStatus.DECLINED)
                .setIdempotencyToken("idem-9")
                .build());

        PaymentOutcome outcome = client.purchaseTicket("req-1", 7L, "tok", 4L, new BigDecimal("50.00"));

        assertThat(outcome.status()).isEqualTo(PaymentOutcomeStatus.DECLINED);
        assertThat(outcome.idempotencyToken()).isEqualTo("idem-9");
    }
}
