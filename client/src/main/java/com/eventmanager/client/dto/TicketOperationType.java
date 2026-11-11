package com.eventmanager.client.dto;

/** Mirrors the server's {@code TicketOperationType}: what kind of ticket transaction a row records. */
public enum TicketOperationType {
    PURCHASE,
    PAYMENT_DECLINED,
    CANCEL,
    PAYMENT_ERROR
}
