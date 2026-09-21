package com.eventmanager.dto;

import com.eventmanager.model.EventStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Event details returned by the API")
public class EventResponse implements Serializable {

    @Schema(example = "1")
    private Long id;
    @Schema(example = "Summer Rock Festival")
    private String name;
    @Schema(example = "Three nights of live rock music at MSG")
    private String description;
    @Schema(description = "Event date and time (ISO 8601)", example = "2025-08-15T19:00:00")
    private LocalDateTime eventDate;
    @Schema(example = "99.99")
    private BigDecimal ticketPrice;
    @Schema(description = "Total number of tickets for the event, fixed at creation", example = "500")
    private Integer ticketsTotal;
    @Schema(description = "Event lifecycle status; AVAILABLE normally, DELETING while a delete is in progress")
    private EventStatus status;
    @Schema(description = "Venue where the event is held")
    private VenueDto venue;
    @Schema(description = "Performers appearing at the event")
    private Set<PerformerDto> performers;
    @Schema(description = "Record creation timestamp (ISO 8601)")
    private LocalDateTime createdAt;
    @Schema(description = "Record last-updated timestamp (ISO 8601)")
    private LocalDateTime updatedAt;
}
