package com.eventmanager.payment;

/** This application's own mirror of the payment service's {@code PaymentStatus} proto enum, plus {@link #ERROR}. */
public enum PaymentOutcomeStatus {
    APPROVED,
    DECLINED,
    PENDING,
    /** The call itself failed — deadline exceeded, connection failure, or an unexpected status — not a business decline. */
    ERROR
}
