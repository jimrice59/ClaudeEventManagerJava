package com.eventmanager.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when the gRPC call to the payment service itself fails (deadline exceeded, connection
 * failure, unexpected status) rather than returning a business decision — see
 * {@code PaymentServiceClient}. The ticket is deliberately left in {@code PAYING} when this is
 * thrown: the payment may have actually succeeded on the payment service's side even though this
 * application never learned the outcome, so auto-releasing the ticket back to {@code AVAILABLE}
 * risks selling it twice. 502 signals a failure in an upstream dependency, not a problem with the
 * request itself. The response includes {@code paymentRequestId} so the caller (or an operator)
 * can poll {@code GetPaymentStatus} later — see docs/payment-service-integration.md.
 */
@ResponseStatus(HttpStatus.BAD_GATEWAY)
public class PaymentServiceUnavailableException extends RuntimeException {

    public PaymentServiceUnavailableException(Long ticketId, String paymentRequestId) {
        super("Payment service did not return a result for ticket " + ticketId
                + " (paymentRequestId=" + paymentRequestId + "); ticket remains PAYING pending reconciliation");
    }
}
