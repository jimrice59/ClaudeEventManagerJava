package com.eventmanager.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.hibernate.validator.constraints.URL;

@Data
@Schema(description = "Video URL to add to or remove from a performer")
public class VideoRequest {

    @NotBlank
    @URL
    @Schema(description = "Publicly accessible video URL", example = "https://example.com/concert.mp4")
    private String url;
}
