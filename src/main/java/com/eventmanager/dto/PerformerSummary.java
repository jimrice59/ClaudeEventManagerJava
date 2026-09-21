package com.eventmanager.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serializable;

@Schema(description = "Lightweight performer identifier (id + name) embedded in ticket responses")
public record PerformerSummary(

        @Schema(example = "1")
        Long id,

        @Schema(example = "The Beatles")
        String name

) implements Serializable {
}
