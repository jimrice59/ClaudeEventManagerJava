package com.eventmanager.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * The hold store (Redis) couldn't be reached, so a reservation can't be checked or made. Thrown by
 * TicketHoldService for any Redis error, connection failures and command timeouts alike.
 * Reservations fail closed (503) rather than risk two people holding the same ticket; unlike the
 * rate limiter, which fails open.
 */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class ReservationsUnavailableException extends RuntimeException {

    public ReservationsUnavailableException(Throwable cause) {
        super("Ticket reservations are temporarily unavailable; please try again shortly", cause);
    }
}
