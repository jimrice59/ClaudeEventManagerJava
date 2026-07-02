package com.eventmanager.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Venue details")
public class VenueDto implements Serializable {

    @Schema(description = "Venue ID (null on create request)", example = "1")
    private Long id;

    @NotBlank
    @Schema(example = "Madison Square Garden")
    private String name;

    @NotBlank
    @Schema(example = "4 Pennsylvania Plaza")
    private String address;

    @NotBlank
    @Schema(example = "New York")
    private String city;

    @NotBlank
    @Schema(description = "State or province", example = "NY")
    private String state;

    @Schema(example = "10001")
    private String zipCode;

    @Positive
    @Schema(description = "Maximum occupancy (must be > 0)", example = "20000")
    private Integer capacity;
}
