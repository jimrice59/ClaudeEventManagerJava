package com.eventmanager.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown by {@code TicketService#reserveAvailableTicket} when the event exists but has no
 * {@code AVAILABLE} ticket left to reserve (sold out, or every remaining one is reserved/paying,
 * or locked by a concurrent reservation). A conflict with the event's current state rather than a
 * malformed request, so 409 — the caller may succeed later if a reservation is released.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class NoTicketsAvailableException extends RuntimeException {

    public NoTicketsAvailableException(Long eventId) {
        super("No tickets currently available for event " + eventId);
    }
}
