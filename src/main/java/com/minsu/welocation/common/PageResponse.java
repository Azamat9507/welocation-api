package com.minsu.welocation.common;

import org.springframework.data.domain.Page;

import java.util.List;

// a stable JSON shape instead of serializing Spring's Page
public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
