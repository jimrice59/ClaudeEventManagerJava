package com.eventmanager.dto;

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
public class EventResponse implements Serializable {

    private Long id;
    private String name;
    private String description;
    private LocalDateTime eventDate;
    private BigDecimal ticketPrice;
    private Integer ticketsAvailable;
    private VenueDto venue;
    private Set<PerformerDto> performers;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
