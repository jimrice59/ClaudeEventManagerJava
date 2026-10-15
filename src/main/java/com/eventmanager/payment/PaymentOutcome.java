package com.eventmanager.payment;

/**
 * This application's own representation of a payment attempt's result — kept separate from the
 * generated {@code com.eventmanager.grpc.payment.PaymentResponse} proto class so nothing outside
 * {@link PaymentServiceClient} needs to depend on gRPC/protobuf types directly.
 *
 * @param status           outcome, or {@link PaymentOutcomeStatus#ERROR} if the call itself
 *                         failed (timeout, connection failure, unexpected gRPC status) rather
 *                         than returning a business decision
 * @param confirmationId   set only when {@code status == APPROVED}
 * @param idempotencyToken set only when {@code status == APPROVED} or {@code DECLINED} — i.e.
 *                         whenever the payment service actually processed the request and
 *                         returned a response, as opposed to the call failing outright
 */
public record PaymentOutcome(PaymentOutcomeStatus status, String confirmationId, String idempotencyToken) {
}
