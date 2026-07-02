package com.eventmanager.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "Login credentials")
public class LoginRequest {

    @NotBlank
    @Schema(example = "johndoe")
    private String username;

    @NotBlank
    @Schema(example = "secret123")
    private String password;
}
