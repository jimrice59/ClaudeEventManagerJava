package com.eventmanager.dto;

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
public class PerformerDto implements Serializable {

    private Long id;

    @NotBlank
    private String name;

    private String genre;

    private String bio;

    private Set<String> videoUrls;
}
