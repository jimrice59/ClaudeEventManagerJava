package com.eventmanager.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
 * Payload for {@code PUT /api/v1/events/{id}} — unlike {@link EventRequest} (create), this has no
 * {@code ticketsTotal} field. {@code EventService.updateEvent} already ignores that field even
 * when present (it's immutable after creation, fixed by the number of tickets synchronously
 * created alongside the event), so the update payload simply doesn't offer a field whose value
 * would be silently dropped — the same reasoning behind {@code UpdateEventRequest} on the gRPC API
 * (event_manager_api.proto) omitting it too.
 */
@Data
@Schema(description = "Payload for updating an event. ticketsTotal is fixed at creation and is not part of this payload.")
public class UpdateEventRequest {

    @NotBlank
    @Schema(example = "Summer Rock Festival")
    private String name;

    @Schema(example = "Three nights of live rock music at MSG")
    private String description;

    @NotNull
    @Schema(description = "Event date and time (ISO 8601)", example = "2025-08-15T19:00:00")
    private LocalDateTime eventDate;

    @NotNull
    @DecimalMin("0.00")
    @DecimalMax("10000.00")
    @Digits(integer = 5, fraction = 2)
    @Schema(description = "Ticket price (0.00–10000.00)", example = "99.99")
    private BigDecimal ticketPrice;

    @NotNull
    @Schema(description = "ID of the venue where the event is held", example = "1")
    private Long venueId;

    @Schema(description = "IDs of performers appearing at the event (omit or empty for none)",
            example = "[1, 2]")
    private Set<Long> performerIds;
}
