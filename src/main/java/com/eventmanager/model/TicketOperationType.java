package com.eventmanager.model;

/** The kind of {@link TicketOperation} recorded against a ticket. */
public enum TicketOperationType {
    PURCHASE,
    CANCEL,
    /** The payment service declined the charge; the ticket returned to AVAILABLE. */
    PAYMENT_DECLINED,
    /** The payment service call itself failed (timeout, connection error); the ticket remains PAYING. */
    PAYMENT_ERROR
}
