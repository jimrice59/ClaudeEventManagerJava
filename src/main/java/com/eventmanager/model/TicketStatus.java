package com.eventmanager.model;

public enum TicketStatus {
    AVAILABLE,
    RESERVED,
    /** A purchase attempt is in flight against the payment service; see TicketService#purchaseTicket. */
    PAYING,
    SOLD
}
