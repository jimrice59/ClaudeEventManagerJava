package com.eventmanager.dto;

import com.eventmanager.model.TicketStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Ticket details returned by the API")
public class TicketResponse implements Serializable {

    @Schema(example = "1")
    private Long id;

    @Schema(description = "ID of the event this ticket is for", example = "1")
    private Long eventId;

    @Schema(description = "Name of the event this ticket is for (derived from the event)",
            example = "Summer Rock Festival")
    private String eventName;

    @Schema(description = "Date and time of the event this ticket is for (derived from the event)")
    private LocalDateTime eventDate;

    @Schema(description = "Ticket description, e.g. seating section", example = "Section 100, Row A, Seat 5")
    private String description;

    @Schema(description = "ID of the venue where the event is held (derived from the event)", example = "1")
    private Long venueId;

    @Schema(description = "Name of the venue where the event is held (derived from the event)",
            example = "Madison Square Garden")
    private String venueName;

    @Schema(description = "Performers appearing at the event (derived from the event)")
    private Set<PerformerSummary> performers;

    @Schema(description = "Ticket status")
    private TicketStatus status;

    @Schema(description = "Id of the user who reserved/bought this ticket; null while AVAILABLE", example = "42")
    private Long userId;
}
