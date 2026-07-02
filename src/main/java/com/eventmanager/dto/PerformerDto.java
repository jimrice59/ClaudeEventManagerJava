package com.eventmanager.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Performer details")
public class PerformerDto implements Serializable {

    @Schema(description = "Performer ID (null on create request)", example = "1")
    private Long id;

    @NotBlank
    @Schema(example = "The Beatles")
    private String name;

    @Schema(example = "Rock")
    private String genre;

    @Schema(example = "Legendary British band formed in Liverpool in 1960")
    private String bio;

    @Schema(description = "URLs of associated video recordings (output only; manage via /videos endpoints)",
            example = "[\"https://example.com/video1.mp4\"]")
    private Set<String> videoUrls;
}
