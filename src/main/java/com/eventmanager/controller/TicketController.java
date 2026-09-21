package com.eventmanager.controller;

import com.eventmanager.dto.PagedResponse;
import com.eventmanager.dto.PurchaseTicketRequest;
import com.eventmanager.dto.TicketResponse;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Tickets are created and deleted only as a side effect of event creation/deletion
 * (see EventService.createEvent / deleteEvent) — there is no external create or delete
 * operation for individual tickets. There is also no generic "set status" operation:
 * each valid transition (reserve, release, purchase, cancel) has its own dedicated endpoint,
 * each enforcing the same ownership/prior-status validation the transition has always required.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/tickets")
@RequiredArgsConstructor
@Tag(name = "Tickets", description = "Read tickets and reserve/release/purchase/cancel them; tickets themselves are created and deleted only via their event")
public class TicketController {

    private final TicketService ticketService;

    @Operation(summary = "Get ticket by ID")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Ticket found",
                    content = @Content(schema = @Schema(implementation = TicketResponse.class))),
            @ApiResponse(responseCode = "404", description = "Ticket not found", content = @Content)
    })
    @GetMapping("/{id}")
    public ResponseEntity<TicketResponse> getTicketById(
            @Parameter(description = "Ticket ID") @PathVariable Long id) {
        log.debug("Received request to get ticket id={}", id);
        return ResponseEntity.ok(ticketService.getTicketById(id));
    }

    @Operation(summary = "List my tickets", security = @SecurityRequirement(name = "bearerAuth"),
               description = "Requires authentication. Returns a paginated page of every ticket owned by the " +
                             "caller (resolved from the JWT), across all events and statuses, ordered by ticket id. " +
                             "There is no way to list another user's tickets — only the caller's own JWT user id " +
                             "is ever used.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of the caller's tickets (may be empty)"),
            @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content)
    })
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PagedResponse<TicketResponse>> getMyTickets(
            @Parameter(description = "Page number, 0-based") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size (1-100)") @RequestParam(defaultValue = "20") int size) {
        log.debug("Received request for current user's tickets: page={}, size={}", page, size);
        return ResponseEntity.ok(ticketService.getMyTickets(page, size));
    }

    @Operation(summary = "Reserve a ticket", security = @SecurityRequirement(name = "bearerAuth"),
               description = "Requires authentication (any role). The ticket must currently be AVAILABLE; " +
                             "it is stamped with the caller's user id (from the JWT) and moved to RESERVED.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Ticket reserved",
                    content = @Content(schema = @Schema(implementation = TicketResponse.class))),
            @ApiResponse(responseCode = "400", description = "Ticket is not currently AVAILABLE", content = @Content),
            @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content),
            @ApiResponse(responseCode = "404", description = "Ticket not found", content = @Content)
    })
    @PostMapping("/{id}/reserve")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<TicketResponse> reserveTicket(
            @Parameter(description = "Ticket ID") @PathVariable Long id) {
        log.debug("Received request to reserve ticket id={}", id);
        return ResponseEntity.ok(ticketService.reserveTicket(id));
    }

    @Operation(summary = "Release a reserved ticket", security = @SecurityRequirement(name = "bearerAuth"),
               description = "Requires authentication (any role). The ticket must currently be RESERVED and " +
                             "the caller must be the same user who reserved it (from the JWT); moves it back to " +
                             "AVAILABLE and clears its owner.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Ticket released",
                    content = @Content(schema = @Schema(implementation = TicketResponse.class))),
            @ApiResponse(responseCode = "400", description = "Ticket is not currently RESERVED", content = @Content),
            @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content),
            @ApiResponse(responseCode = "403", description = "Ticket was reserved by a different user", content = @Content),
            @ApiResponse(responseCode = "404", description = "Ticket not found", content = @Content)
    })
    @PostMapping("/{id}/release")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<TicketResponse> releaseTicket(
            @Parameter(description = "Ticket ID") @PathVariable Long id) {
        log.debug("Received request to release ticket id={}", id);
        return ResponseEntity.ok(ticketService.releaseTicket(id));
    }

    @Operation(summary = "Purchase a reserved ticket", security = @SecurityRequirement(name = "bearerAuth"),
               description = "Requires authentication (any role). The ticket must currently be RESERVED and " +
                             "the caller must be the same user who reserved it (from the JWT); moves it to SOLD " +
                             "and records a PURCHASE ticket operation.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Ticket purchased",
                    content = @Content(schema = @Schema(implementation = TicketResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation error, or the ticket is not currently RESERVED",
                    content = @Content),
            @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content),
            @ApiResponse(responseCode = "403", description = "Ticket was reserved by a different user", content = @Content),
            @ApiResponse(responseCode = "404", description = "Ticket not found", content = @Content)
    })
    @PostMapping("/{id}/purchase")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<TicketResponse> purchaseTicket(
            @Parameter(description = "Ticket ID") @PathVariable Long id,
            @Valid @RequestBody PurchaseTicketRequest request) {
        log.debug("Received request to purchase ticket id={}", id);
        return ResponseEntity.ok(ticketService.purchaseTicket(id, request.getUserCredentials()));
    }

    @Operation(summary = "Cancel a purchased ticket", security = @SecurityRequirement(name = "bearerAuth"),
               description = "Requires authentication (any role). The ticket must currently be SOLD and the " +
                             "caller must be the same user who purchased it (from the JWT); moves it back to " +
                             "AVAILABLE, clears its owner, and records a CANCEL ticket operation.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Ticket cancelled",
                    content = @Content(schema = @Schema(implementation = TicketResponse.class))),
            @ApiResponse(responseCode = "400", description = "Ticket is not currently SOLD", content = @Content),
            @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content),
            @ApiResponse(responseCode = "403", description = "Ticket was purchased by a different user", content = @Content),
            @ApiResponse(responseCode = "404", description = "Ticket not found", content = @Content)
    })
    @PostMapping("/{id}/cancel")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<TicketResponse> cancelTicket(
            @Parameter(description = "Ticket ID") @PathVariable Long id) {
        log.debug("Received request to cancel ticket id={}", id);
        return ResponseEntity.ok(ticketService.cancelTicket(id));
    }
}
