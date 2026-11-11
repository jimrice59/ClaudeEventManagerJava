package com.eventmanager.client.dto;

/** Mirrors the server's {@code TicketStatus}. A ticket moves AVAILABLE → RESERVED → PAYING → SOLD. */
public enum TicketStatus {
    AVAILABLE,
    RESERVED,
    /** A purchase is in flight against the payment service. */
    PAYING,
    SOLD
}
