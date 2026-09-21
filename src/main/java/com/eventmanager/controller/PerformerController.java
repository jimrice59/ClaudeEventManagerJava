package com.eventmanager.controller;

import com.eventmanager.dto.PerformerDto;
import com.eventmanager.dto.VideoRequest;
import com.eventmanager.service.PerformerService;
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
@RequestMapping("/api/v1/performers")
@RequiredArgsConstructor
@Tag(name = "Performers", description = "Create and manage performers; add and remove video URLs")
public class PerformerController {

    private final PerformerService performerService;

    @Operation(summary = "List performers",
               description = "Returns all performers. Filter by name (contains, case-insensitive) via ?name=, " +
                             "or by genre (exact, case-insensitive) via ?genre=. Provide at most one filter.")
    @ApiResponse(responseCode = "200", description = "Performer list (may be empty)")
    @GetMapping
    public ResponseEntity<List<PerformerDto>> getAllPerformers(
            @Parameter(description = "Name substring filter (case-insensitive)")
                @RequestParam(required = false) String name,
            @Parameter(description = "Genre filter (case-insensitive exact match)")
                @RequestParam(required = false) String genre) {
        log.debug("Received request to list performers: name='{}', genre='{}'", name, genre);
        if (name != null) {
            return ResponseEntity.ok(performerService.searchPerformers(name));
        }
        if (genre != null) {
            return ResponseEntity.ok(performerService.getPerformersByGenre(genre));
        }
        return ResponseEntity.ok(performerService.getAllPerformers());
    }

    @Operation(summary = "Get performer by ID")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Performer found",
                    content = @Content(schema = @Schema(implementation = PerformerDto.class))),
            @ApiResponse(responseCode = "404", description = "Performer not found", content = @Content)
    })
    @GetMapping("/{id}")
    public ResponseEntity<PerformerDto> getPerformerById(
            @Parameter(description = "Performer ID") @PathVariable Long id) {
        log.debug("Received request to get performer id={}", id);
        return ResponseEntity.ok(performerService.getPerformerById(id));
    }

    @Operation(summary = "Create performer", security = @SecurityRequirement(name = "bearerAuth"),
               description = "Requires ROLE_ADMIN. Videos are managed via the /videos sub-endpoints after creation.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Performer created",
                    content = @Content(schema = @Schema(implementation = PerformerDto.class))),
            @ApiResponse(responseCode = "400", description = "Validation error", content = @Content),
            @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content),
            @ApiResponse(responseCode = "403", description = "Requires ROLE_ADMIN", content = @Content)
    })
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PerformerDto> createPerformer(@Valid @RequestBody PerformerDto dto) {
        log.debug("Received request to create performer name='{}'", dto.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(performerService.createPerformer(dto));
    }

    @Operation(summary = "Update performer", security = @SecurityRequirement(name = "bearerAuth"),
               description = "Requires ROLE_ADMIN. Updates name, genre, and bio only; videos are unchanged.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Performer updated",
                    content = @Content(schema = @Schema(implementation = PerformerDto.class))),
            @ApiResponse(responseCode = "400", description = "Validation error", content = @Content),
            @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content),
            @ApiResponse(responseCode = "403", description = "Requires ROLE_ADMIN", content = @Content),
            @ApiResponse(responseCode = "404", description = "Performer not found", content = @Content)
    })
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PerformerDto> updatePerformer(
            @Parameter(description = "Performer ID") @PathVariable Long id,
            @Valid @RequestBody PerformerDto dto) {
        log.debug("Received request to update performer id={}", id);
        return ResponseEntity.ok(performerService.updatePerformer(id, dto));
    }

    @Operation(summary = "Add video URL", security = @SecurityRequirement(name = "bearerAuth"),
               description = "Requires ROLE_ADMIN. If the URL already exists in the database (shared with another performer) " +
                             "the existing Video row is reused. Publishes an ADD event to Kafka.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Video added; returns updated performer",
                    content = @Content(schema = @Schema(implementation = PerformerDto.class))),
            @ApiResponse(responseCode = "400", description = "Validation error (blank or invalid URL)",
                    content = @Content),
            @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content),
            @ApiResponse(responseCode = "403", description = "Requires ROLE_ADMIN", content = @Content),
            @ApiResponse(responseCode = "404", description = "Performer not found", content = @Content)
    })
    @PostMapping("/{id}/videos")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PerformerDto> addVideo(
            @Parameter(description = "Performer ID") @PathVariable Long id,
            @Valid @RequestBody VideoRequest request) {
        log.debug("Received request to add video to performer id={}", id);
        return ResponseEntity.ok(performerService.addVideo(id, request.getUrl()));
    }

    @Operation(summary = "Remove video URL", security = @SecurityRequirement(name = "bearerAuth"),
               description = "Requires ROLE_ADMIN. Removes the video association from this performer; does not delete " +
                             "the Video row (other performers may share it). Publishes a DELETE event to Kafka.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Video removed; returns updated performer",
                    content = @Content(schema = @Schema(implementation = PerformerDto.class))),
            @ApiResponse(responseCode = "400", description = "Validation error (blank or invalid URL)",
                    content = @Content),
            @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content),
            @ApiResponse(responseCode = "403", description = "Requires ROLE_ADMIN", content = @Content),
            @ApiResponse(responseCode = "404", description = "Performer not found", content = @Content)
    })
    @DeleteMapping("/{id}/videos")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PerformerDto> deleteVideo(
            @Parameter(description = "Performer ID") @PathVariable Long id,
            @Valid @RequestBody VideoRequest request) {
        log.debug("Received request to remove video from performer id={}", id);
        return ResponseEntity.ok(performerService.deleteVideo(id, request.getUrl()));
    }

    @Operation(summary = "Delete performer", security = @SecurityRequirement(name = "bearerAuth"),
               description = "Requires ROLE_ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Performer deleted", content = @Content),
            @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content),
            @ApiResponse(responseCode = "403", description = "Requires ROLE_ADMIN", content = @Content),
            @ApiResponse(responseCode = "404", description = "Performer not found", content = @Content)
    })
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deletePerformer(
            @Parameter(description = "Performer ID") @PathVariable Long id) {
        log.debug("Received request to delete performer id={}", id);
        performerService.deletePerformer(id);
        return ResponseEntity.noContent().build();
    }
}
