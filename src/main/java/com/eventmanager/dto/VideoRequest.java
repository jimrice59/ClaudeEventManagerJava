package com.eventmanager.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.hibernate.validator.constraints.URL;

@Data
public class VideoRequest {

    @NotBlank
    @URL
    private String url;
}
