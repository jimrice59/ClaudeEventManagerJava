package com.eventmanager.controller;

import com.eventmanager.dto.VenueDto;
import com.eventmanager.service.VenueService;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/venues")
@RequiredArgsConstructor
@Tag(name = "Venues", description = "Create and manage event venues")
public class VenueController {

    private final VenueService venueService;

    @Operation(summary = "List venues",
               description = "Returns all venues, or filters by city (case-insensitive).")
    @ApiResponse(responseCode = "200", description = "Venue list (may be empty)")
    @GetMapping
    public ResponseEntity<List<VenueDto>> getAllVenues(
            @Parameter(description = "Filter by city name (case-insensitive)")
                @RequestParam(required = false) String city) {
        log.debug("Received request to list venues: city='{}'", city);
        if (city != null) {
            return ResponseEntity.ok(venueService.getVenuesByCity(city));
        }
        return ResponseEntity.ok(venueService.getAllVenues());
    }

    @Operation(summary = "Get venue by ID")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Venue found",
                    content = @Content(schema = @Schema(implementation = VenueDto.class))),
            @ApiResponse(responseCode = "404", description = "Venue not found", content = @Content)
    })
    @GetMapping("/{id}")
    public ResponseEntity<VenueDto> getVenueById(
            @Parameter(description = "Venue ID") @PathVariable Long id) {
        log.debug("Received request to get venue id={}", id);
        return ResponseEntity.ok(venueService.getVenueById(id));
    }

    @Operation(summary = "Create venue", security = @SecurityRequirement(name = "bearerAuth"),
               description = "Requires ROLE_ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Venue created",
                    content = @Content(schema = @Schema(implementation = VenueDto.class))),
            @ApiResponse(responseCode = "400", description = "Validation error", content = @Content),
            @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content),
            @ApiResponse(responseCode = "403", description = "Requires ROLE_ADMIN", content = @Content)
    })
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<VenueDto> createVenue(@Valid @RequestBody VenueDto dto) {
        log.debug("Received request to create venue name='{}'", dto.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(venueService.createVenue(dto));
    }

    @Operation(summary = "Update venue", security = @SecurityRequirement(name = "bearerAuth"),
               description = "Requires ROLE_ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Venue updated",
                    content = @Content(schema = @Schema(implementation = VenueDto.class))),
            @ApiResponse(responseCode = "400", description = "Validation error", content = @Content),
            @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content),
            @ApiResponse(responseCode = "403", description = "Requires ROLE_ADMIN", content = @Content),
            @ApiResponse(responseCode = "404", description = "Venue not found", content = @Content)
    })
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<VenueDto> updateVenue(
            @Parameter(description = "Venue ID") @PathVariable Long id,
            @Valid @RequestBody VenueDto dto) {
        log.debug("Received request to update venue id={}", id);
        return ResponseEntity.ok(venueService.updateVenue(id, dto));
    }

    @Operation(summary = "Delete venue", security = @SecurityRequirement(name = "bearerAuth"),
               description = "Requires ROLE_ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Venue deleted", content = @Content),
            @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content),
            @ApiResponse(responseCode = "403", description = "Requires ROLE_ADMIN", content = @Content),
            @ApiResponse(responseCode = "404", description = "Venue not found", content = @Content)
    })
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteVenue(
            @Parameter(description = "Venue ID") @PathVariable Long id) {
        log.debug("Received request to delete venue id={}", id);
        venueService.deleteVenue(id);
        return ResponseEntity.noContent().build();
    }
}
