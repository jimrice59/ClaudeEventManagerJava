package com.eventmanager.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Page;

import java.io.Serializable;
import java.util.List;
import java.util.function.Function;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A single page of results")
public class PagedResponse<T> implements Serializable {

    @Schema(description = "Page content")
    private List<T> content;

    @Schema(description = "Current page number (0-based)", example = "0")
    private int page;

    @Schema(description = "Number of items requested per page", example = "20")
    private int size;

    @Schema(description = "Total number of matching items across all pages", example = "137")
    private long totalElements;

    @Schema(description = "Total number of pages", example = "7")
    private int totalPages;

    @Schema(description = "Whether this is the last page", example = "false")
    private boolean last;

    /** Maps a Spring Data {@link Page} of entities into a page of DTOs using the given mapper. */
    public static <S, T> PagedResponse<T> of(Page<S> source, Function<S, T> mapper) {
        return PagedResponse.<T>builder()
                .content(source.getContent().stream().map(mapper).toList())
                .page(source.getNumber())
                .size(source.getSize())
                .totalElements(source.getTotalElements())
                .totalPages(source.getTotalPages())
                .last(source.isLast())
                .build();
    }
}
