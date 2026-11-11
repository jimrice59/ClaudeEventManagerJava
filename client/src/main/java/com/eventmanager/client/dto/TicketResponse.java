package com.eventmanager.client.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TicketResponse {
    private Long id;
    private Long eventId;
    private String eventName;
    private LocalDateTime eventDate;
    private String description;
    private Long venueId;
    private String venueName;
    private Set<PerformerSummary> performers;
    private TicketStatus status;
    /** Owner of a RESERVED/PAYING/SOLD ticket; null while AVAILABLE. */
    private Long userId;
    /** The most recent purchase attempt's id; null if the ticket has never been through a purchase. */
    private String paymentRequestId;
    /** When the most recent approved sale happened; null if never sold. Not cleared on cancellation. */
    private LocalDateTime datePurchased;
}
