package com.eventmanager.controller;

import com.eventmanager.dto.PerformerDto;
import com.eventmanager.dto.VideoRequest;
import com.eventmanager.service.PerformerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/performers")
@RequiredArgsConstructor
public class PerformerController {

    private final PerformerService performerService;

    @GetMapping
    public ResponseEntity<List<PerformerDto>> getAllPerformers(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String genre) {
        if (name != null) {
            return ResponseEntity.ok(performerService.searchPerformers(name));
        }
        if (genre != null) {
            return ResponseEntity.ok(performerService.getPerformersByGenre(genre));
        }
        return ResponseEntity.ok(performerService.getAllPerformers());
    }

    @GetMapping("/{id}")
    public ResponseEntity<PerformerDto> getPerformerById(@PathVariable Long id) {
        return ResponseEntity.ok(performerService.getPerformerById(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PerformerDto> createPerformer(@Valid @RequestBody PerformerDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(performerService.createPerformer(dto));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PerformerDto> updatePerformer(@PathVariable Long id,
                                                        @Valid @RequestBody PerformerDto dto) {
        return ResponseEntity.ok(performerService.updatePerformer(id, dto));
    }

    @PostMapping("/{id}/videos")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PerformerDto> addVideo(@PathVariable Long id,
                                                 @Valid @RequestBody VideoRequest request) {
        return ResponseEntity.ok(performerService.addVideo(id, request.getUrl()));
    }

    @DeleteMapping("/{id}/videos")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PerformerDto> deleteVideo(@PathVariable Long id,
                                                    @Valid @RequestBody VideoRequest request) {
        return ResponseEntity.ok(performerService.deleteVideo(id, request.getUrl()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deletePerformer(@PathVariable Long id) {
        performerService.deletePerformer(id);
        return ResponseEntity.noContent().build();
    }
}
