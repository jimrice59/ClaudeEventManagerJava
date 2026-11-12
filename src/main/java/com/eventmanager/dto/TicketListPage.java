package com.eventmanager.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.Map;

/**
 * A {@link PagedResponse} of ticket-related rows that can span several events (My Tickets, My
 * Transactions), plus {@code maxTicketIdByEvent}: for each event on the page, its largest ticket
 * id (any status). Ticket ids are global, not 1..N per event, so a client padding ticket numbers
 * to a per-event width (to match the event page's labels) needs each event's largest id. That is
 * fixed once an event's tickets are created. Extends {@code PagedResponse}, so callers typed to it
 * (gRPC mapping, Thymeleaf, Java clients) are unaffected; JSON keys of the map are event ids.
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Schema(description = "A page of tickets or transactions, plus each listed event's largest ticket id")
public class TicketListPage<T> extends PagedResponse<T> {

    @Schema(description = "Event id -> that event's largest ticket id, for every event on this page",
            example = "{\"4\": 60003}")
    private Map<Long, Long> maxTicketIdByEvent;

    public static <T> TicketListPage<T> of(PagedResponse<T> page, Map<Long, Long> maxTicketIdByEvent) {
        TicketListPage<T> result = new TicketListPage<>();
        result.setContent(page.getContent());
        result.setPage(page.getPage());
        result.setSize(page.getSize());
        result.setTotalElements(page.getTotalElements());
        result.setTotalPages(page.getTotalPages());
        result.setLast(page.isLast());
        result.setMaxTicketIdByEvent(maxTicketIdByEvent);
        return result;
    }
}
