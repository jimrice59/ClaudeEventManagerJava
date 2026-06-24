package com.eventmanager.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

@Data
public class EventRequest {

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
}
