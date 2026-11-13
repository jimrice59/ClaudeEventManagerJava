package com.eventmanager.service;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Bound from {@code ticket-reservation.*} in application.yml.
 *
 * <p>{@code maxRetries} ({@code TICKET_RESERVATION_MAX_RETRIES}, default 3): how many extra
 * attempts {@link TicketService#reserveAvailableTicket} makes when it finds no ticket to reserve
 * <em>only because</em> every remaining AVAILABLE ticket is row-locked by another in-flight
 * transaction — it never retries a truly sold-out event. {@code 0} disables retrying. Validated
 * at startup: a negative value fails fast rather than silently behaving like 0.
 */
@Getter
@Setter
@Component
@Validated
@ConfigurationProperties(prefix = "ticket-reservation")
public class TicketReservationProperties {

    @Min(0)
    private int maxRetries = 3;

    /**
     * How long a reservation (a Redis hold, see TicketHoldService) lasts before it expires on its
     * own and the ticket becomes available again ({@code TICKET_RESERVATION_HOLD_TTL}, default 2m;
     * docker-compose.yml sets 10m).
     */
    @NotNull
    private Duration holdTtl = Duration.ofMinutes(2);
}
