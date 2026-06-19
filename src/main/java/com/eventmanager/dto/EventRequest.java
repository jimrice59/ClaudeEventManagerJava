package com.eventmanager.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

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
    private Long venueId;

    private Set<Long> performerIds;
}
