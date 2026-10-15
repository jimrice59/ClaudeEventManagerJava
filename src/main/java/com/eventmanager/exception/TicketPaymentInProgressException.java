package com.eventmanager.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a purchase is attempted on a ticket that is already {@code PAYING} — a previous
 * purchase attempt for the same ticket is still in flight. Distinct from the general
 * "not RESERVED" {@link IllegalArgumentException} (400): this is a conflict with a concurrent
 * operation on the same resource, not a precondition failure, so it maps to 409 rather than 400.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class TicketPaymentInProgressException extends RuntimeException {

    public TicketPaymentInProgressException(Long ticketId) {
        super("Ticket " + ticketId + " already has a purchase in progress (status is PAYING)");
    }
}
