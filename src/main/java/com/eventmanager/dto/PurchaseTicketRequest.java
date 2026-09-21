package com.eventmanager.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "Credentials presented to purchase a reserved ticket")
public class PurchaseTicketRequest {

    @NotBlank
    @Schema(example = "confirmation-token-abc123")
    private String userCredentials;
}
