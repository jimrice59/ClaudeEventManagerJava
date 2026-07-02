package com.eventmanager.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

@Data
@Schema(description = "Payload for creating or updating an event")
public class EventRequest {

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
    @Min(0)
    @Schema(description = "Number of tickets initially available (≥ 0)", example = "500")
    private Integer ticketsAvailable;

    @NotNull
    @Schema(description = "ID of the venue where the event is held", example = "1")
    private Long venueId;

    @Schema(description = "IDs of performers appearing at the event (omit or empty for none)",
            example = "[1, 2]")
    private Set<Long> performerIds;
}
