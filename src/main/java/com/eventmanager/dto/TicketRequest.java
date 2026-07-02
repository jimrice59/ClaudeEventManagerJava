package com.eventmanager.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "Number of tickets to reserve or release")
public class TicketRequest {

    @NotNull
    @Min(1)
    @Schema(description = "Ticket count (minimum 1)", example = "2")
    private Integer count;
}
