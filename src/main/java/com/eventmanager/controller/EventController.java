package com.eventmanager.controller;

import com.eventmanager.dto.EventRequest;
import com.eventmanager.dto.EventResponse;
import com.eventmanager.dto.TicketRequest;
import com.eventmanager.service.EventService;
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
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
@Tag(name = "Events", description = "Create and manage events; reserve and release tickets")
public class EventController {

    private final EventService eventService;

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
        return ResponseEntity.ok(eventService.getEventById(id));
    }

    @Operation(summary = "Create event", security = @SecurityRequirement(name = "bearerAuth"),
               description = "Requires authentication (ROLE_USER or ROLE_ADMIN).")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Event created",
                    content = @Content(schema = @Schema(implementation = EventResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation error or invalid performer/venue IDs",
                    content = @Content),
            @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content)
    })
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<EventResponse> createEvent(@Valid @RequestBody EventRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(eventService.createEvent(request));
    }

    @Operation(summary = "Update event", security = @SecurityRequirement(name = "bearerAuth"),
               description = "Requires authentication (ROLE_USER or ROLE_ADMIN).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Event updated",
                    content = @Content(schema = @Schema(implementation = EventResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation error or invalid venue/performer IDs",
                    content = @Content),
            @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content),
            @ApiResponse(responseCode = "404", description = "Event not found", content = @Content)
    })
    @PutMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<EventResponse> updateEvent(
            @Parameter(description = "Event ID") @PathVariable Long id,
            @Valid @RequestBody EventRequest request) {
        return ResponseEntity.ok(eventService.updateEvent(id, request));
    }

    @Operation(summary = "Reserve tickets", security = @SecurityRequirement(name = "bearerAuth"),
               description = "Decrements ticketsAvailable by count. Returns 400 if count exceeds available tickets.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tickets reserved; returns updated event",
                    content = @Content(schema = @Schema(implementation = EventResponse.class))),
            @ApiResponse(responseCode = "400", description = "Insufficient tickets available", content = @Content),
            @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content),
            @ApiResponse(responseCode = "404", description = "Event not found", content = @Content)
    })
    @PostMapping("/{id}/tickets/reserve")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<EventResponse> reserveTickets(
            @Parameter(description = "Event ID") @PathVariable Long id,
            @Valid @RequestBody TicketRequest request) {
        return ResponseEntity.ok(eventService.reserveTickets(id, request.getCount()));
    }

    @Operation(summary = "Release tickets", security = @SecurityRequirement(name = "bearerAuth"),
               description = "Increments ticketsAvailable by count. Returns 400 if the result would exceed venue capacity.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tickets released; returns updated event",
                    content = @Content(schema = @Schema(implementation = EventResponse.class))),
            @ApiResponse(responseCode = "400", description = "Would exceed venue capacity", content = @Content),
            @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content),
            @ApiResponse(responseCode = "404", description = "Event not found", content = @Content)
    })
    @PostMapping("/{id}/tickets/release")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<EventResponse> releaseTickets(
            @Parameter(description = "Event ID") @PathVariable Long id,
            @Valid @RequestBody TicketRequest request) {
        return ResponseEntity.ok(eventService.releaseTickets(id, request.getCount()));
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
        eventService.deleteEvent(id);
        return ResponseEntity.noContent().build();
    }
}
