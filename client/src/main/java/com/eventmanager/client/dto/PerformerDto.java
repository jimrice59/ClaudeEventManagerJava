package com.eventmanager.client.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PerformerDto {

    private Long id;

    @NotBlank
    private String name;

    private String genre;

    private String bio;

    private Set<String> videoUrls;
}
