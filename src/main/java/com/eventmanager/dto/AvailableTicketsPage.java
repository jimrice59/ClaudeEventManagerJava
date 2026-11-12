package com.eventmanager.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * A page of an event's AVAILABLE tickets: the same fields as {@link PagedResponse}, plus
 * {@code maxTicketId}. Ticket ids are global (not 1..N per event), so a client that pads ticket
 * numbers to a uniform width needs the event's largest id. That is fixed once the event's tickets
 * are created (no tickets are ever added later), so the width stays constant across pages and as
 * tickets sell. Clients that only understand {@code PagedResponse} can ignore the extra field.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Page of an event's available tickets, plus the event's largest ticket id")
public class AvailableTicketsPage implements Serializable {

    private List<TicketResponse> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
    private boolean last;

    @Schema(description = "Largest ticket id among all of the event's tickets (any status); null if the "
            + "event has none. Use its digit count to pad ticket numbers to a uniform width.", example = "60003")
    private Long maxTicketId;

    public static AvailableTicketsPage of(PagedResponse<TicketResponse> page, Long maxTicketId) {
        return AvailableTicketsPage.builder()
                .content(page.getContent())
                .page(page.getPage())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .last(page.isLast())
                .maxTicketId(maxTicketId)
                .build();
    }
}
