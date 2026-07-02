package com.eventmanager.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "New user registration payload")
public class RegisterRequest {

    @NotBlank
    @Size(min = 3, max = 50)
    @Schema(description = "Unique username, 3–50 characters", example = "johndoe")
    private String username;

    @Email
    @NotBlank
    @Schema(description = "Unique email address", example = "john@example.com")
    private String email;

    @NotBlank
    @Size(min = 8, max = 100)
    @Schema(description = "Password, 8–100 characters", example = "secret123")
    private String password;
}
