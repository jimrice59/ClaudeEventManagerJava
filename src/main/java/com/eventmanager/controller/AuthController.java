package com.eventmanager.controller;

import com.eventmanager.dto.AuthResponse;
import com.eventmanager.dto.LoginRequest;
import com.eventmanager.dto.RegisterRequest;
import com.eventmanager.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Register and obtain JWT tokens")
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "Register a new user",
               description = "Creates a ROLE_USER account. To obtain ROLE_ADMIN, update the role column in the database after registration.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Registration successful; returns JWT",
                    content = @Content(schema = @Schema(implementation = AuthResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation error, or username/email already taken",
                    content = @Content)
    })
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        log.debug("Received registration request for username='{}'", request.getUsername());
        return ResponseEntity.ok(authService.register(request));
    }

    @Operation(summary = "Login and obtain JWT",
               description = "Authenticates with username and password; returns a signed JWT valid for 24 hours.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Login successful; returns JWT",
                    content = @Content(schema = @Schema(implementation = AuthResponse.class))),
            @ApiResponse(responseCode = "401", description = "Invalid username or password",
                    content = @Content)
    })
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        log.debug("Received login request for username='{}'", request.getUsername());
        return ResponseEntity.ok(authService.login(request));
    }
}
