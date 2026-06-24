package com.eventmanager.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TicketRequest {

    @NotNull
    @Min(1)
    private Integer count;
}
