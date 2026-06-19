package com.eventmanager.controller;

import com.eventmanager.dto.VenueDto;
import com.eventmanager.service.VenueService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/venues")
@RequiredArgsConstructor
public class VenueController {

    private final VenueService venueService;

    @GetMapping
    public ResponseEntity<List<VenueDto>> getAllVenues(
            @RequestParam(required = false) String city) {
        if (city != null) {
            return ResponseEntity.ok(venueService.getVenuesByCity(city));
        }
        return ResponseEntity.ok(venueService.getAllVenues());
    }

    @GetMapping("/{id}")
    public ResponseEntity<VenueDto> getVenueById(@PathVariable Long id) {
        return ResponseEntity.ok(venueService.getVenueById(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<VenueDto> createVenue(@Valid @RequestBody VenueDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(venueService.createVenue(dto));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<VenueDto> updateVenue(@PathVariable Long id,
                                                @Valid @RequestBody VenueDto dto) {
        return ResponseEntity.ok(venueService.updateVenue(id, dto));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteVenue(@PathVariable Long id) {
        venueService.deleteVenue(id);
        return ResponseEntity.noContent().build();
    }
}
