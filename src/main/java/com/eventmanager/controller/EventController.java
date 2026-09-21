package com.eventmanager.controller;

import com.eventmanager.dto.EventRequest;
import com.eventmanager.dto.EventResponse;
import com.eventmanager.dto.PagedResponse;
import com.eventmanager.dto.TicketResponse;
import com.eventmanager.service.EventService;
import com.eventmanager.service.TicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
@Tag(name = "Events", description = "Create and manage events; browse and count available tickets")
public class EventController {

    private final EventService eventService;
    private final TicketService ticketService;

    @Operation(summary = "List events",
               description = "Returns all events, or filters by venueId, or filters by date range (start + end both required). " +
                             "Provide venueId OR start+end, not both.")
    @ApiResponse(responseCode = "200", description = "Event list (may be empty)")
    @GetMapping
    public ResponseEntity<List<EventResponse>> getAllEvents(
            @Parameter(description = "Filter by venue ID") @RequestParam(required = false) Long venueId,
            @Parameter(description = "Range start (ISO 8601, e.g. 2025-06-01T00:00:00)")
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @Parameter(description = "Range end (ISO 8601, e.g. 2025-06-30T23:59:59)")
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        log.debug("Received request to list events: venueId={}, start={}, end={}", venueId, start, end);

        if (venueId != null) {
            return ResponseEntity.ok(eventService.getEventsByVenue(venueId));
        }
        if (start != null && end != null) {
            return ResponseEntity.ok(eventService.getEventsBetween(start, end));
        }
        return ResponseEntity.ok(eventService.getAllEvents());
    }

    @Operation(summary = "Get event by ID")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Event found",
                    content = @Content(schema = @Schema(implementation = EventResponse.class))),
            @ApiResponse(responseCode = "404", description = "Event not found", content = @Content)
    })
    @GetMapping("/{id}")
    public ResponseEntity<EventResponse> getEventById(
            @Parameter(description = "Event ID") @PathVariable Long id) {
        log.debug("Received request to get event id={}", id);
        return ResponseEntity.ok(eventService.getEventById(id));
    }

    @Operation(summary = "List available tickets for an event",
               description = "Returns a paginated page of tickets with status AVAILABLE for the given event, " +
                             "ordered by ticket id. Each ticket includes the venue and performers derived from the event.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of available tickets (may be empty)"),
            @ApiResponse(responseCode = "404", description = "Event not found", content = @Content)
    })
    @GetMapping("/{id}/tickets/available")
    public ResponseEntity<PagedResponse<TicketResponse>> getAvailableTickets(
            @Parameter(description = "Event ID") @PathVariable Long id,
            @Parameter(description = "Page number, 0-based") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size (1-100)") @RequestParam(defaultValue = "20") int size) {
        log.debug("Received request for available tickets of event id={}, page={}, size={}", id, page, size);
        return ResponseEntity.ok(ticketService.getAvailableTickets(id, page, size));
    }

    @Operation(summary = "Create event", security = @SecurityRequirement(name = "bearerAuth"),
               description = "Requires ROLE_ADMIN. ticketsTotal must not exceed the venue's capacity and is fixed " +
                             "for the life of the event — it cannot be changed by a later update. " +
                             "Synchronously creates one AVAILABLE ticket per unit of ticketsTotal.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Event created",
                    content = @Content(schema = @Schema(implementation = EventResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation error, invalid performer/venue IDs, " +
                    "or ticketsTotal exceeds venue capacity", content = @Content),
            @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content),
            @ApiResponse(responseCode = "403", description = "Requires ROLE_ADMIN", content = @Content)
    })
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<EventResponse> createEvent(@Valid @RequestBody EventRequest request) {
        log.debug("Received request to create event name='{}'", request.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(eventService.createEvent(request));
    }

    @Operation(summary = "Update event", security = @SecurityRequirement(name = "bearerAuth"),
               description = "Requires ROLE_ADMIN. ticketsTotal in the request body is ignored — it is fixed at " +
                             "creation and cannot be changed.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Event updated",
                    content = @Content(schema = @Schema(implementation = EventResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation error or invalid venue/performer IDs",
                    content = @Content),
            @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content),
            @ApiResponse(responseCode = "403", description = "Requires ROLE_ADMIN", content = @Content),
            @ApiResponse(responseCode = "404", description = "Event not found", content = @Content)
    })
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<EventResponse> updateEvent(
            @Parameter(description = "Event ID") @PathVariable Long id,
            @Valid @RequestBody EventRequest request) {
        log.debug("Received request to update event id={}", id);
        return ResponseEntity.ok(eventService.updateEvent(id, request));
    }

    @Operation(summary = "Get the live count of available tickets for an event",
               description = "Computes the number of tickets currently in AVAILABLE status by querying Postgres " +
                             "directly — not a stored counter. Cached in Redis for 5 seconds, so the value may lag " +
                             "up to 5 seconds behind concurrent reserve/release/purchase/cancel calls. Distinct " +
                             "from ticketsTotal, which is the event's fixed total capacity and never changes after " +
                             "creation.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Number of AVAILABLE tickets"),
            @ApiResponse(responseCode = "404", description = "Event not found", content = @Content)
    })
    @GetMapping("/{id}/tickets/available/count")
    public ResponseEntity<Long> getNumAvailableTickets(
            @Parameter(description = "Event ID") @PathVariable Long id) {
        log.debug("Received request for available ticket count of event id={}", id);
        return ResponseEntity.ok(eventService.getNumAvailableTickets(id));
    }

    @Operation(summary = "Delete event", security = @SecurityRequirement(name = "bearerAuth"),
               description = "Requires ROLE_ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Event deleted", content = @Content),
            @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content),
            @ApiResponse(responseCode = "403", description = "Requires ROLE_ADMIN", content = @Content),
            @ApiResponse(responseCode = "404", description = "Event not found", content = @Content)
    })
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteEvent(
            @Parameter(description = "Event ID") @PathVariable Long id) {
        log.debug("Received request to delete event id={}", id);
        eventService.deleteEvent(id);
        return ResponseEntity.noContent().build();
    }
}
