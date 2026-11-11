package com.eventmanager.client.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * Body for {@code PUT /api/v1/events/{id}}. Same fields as {@link EventRequest} except
 * {@code ticketsTotal}, which the server fixes at creation and never changes — mirroring the
 * server's own {@code UpdateEventRequest}.
 */
@Data
public class UpdateEventRequest {

    @NotBlank
    private String name;

    private String description;

    @NotNull
    private LocalDateTime eventDate;

    @NotNull
    @DecimalMin("0.00")
    @DecimalMax("10000.00")
    @Digits(integer = 5, fraction = 2)
    private BigDecimal ticketPrice;

    @NotNull
    private Long venueId;

    private Set<Long> performerIds;

    /** Copies every field except {@code ticketsTotal}, which an update can't change. */
    public static UpdateEventRequest from(EventRequest request) {
        UpdateEventRequest update = new UpdateEventRequest();
        update.setName(request.getName());
        update.setDescription(request.getDescription());
        update.setEventDate(request.getEventDate());
        update.setTicketPrice(request.getTicketPrice());
        update.setVenueId(request.getVenueId());
        update.setPerformerIds(request.getPerformerIds());
        return update;
    }
}
