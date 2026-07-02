package com.eventmanager.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
@Schema(description = "Authentication response containing the JWT access token")
public class AuthResponse {
    @Schema(description = "Signed JWT; include as 'Authorization: Bearer <token>' on subsequent requests")
    private String token;
    @Builder.Default
    @Schema(description = "Token type", example = "Bearer")
    private String type = "Bearer";
    @Schema(example = "johndoe")
    private String username;
    @Schema(example = "john@example.com")
    private String email;
    @Schema(description = "Granted role", example = "ROLE_USER")
    private String role;
}
