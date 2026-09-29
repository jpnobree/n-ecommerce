package com.atelier.shared.web;

import org.springframework.data.domain.Page;

import java.util.List;

/** Formato de página da API (PRD 17.1); evita serializar o PageImpl do Spring. */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
