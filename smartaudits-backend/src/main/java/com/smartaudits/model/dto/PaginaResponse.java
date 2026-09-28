package com.smartaudits.model.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Contrato estable de respuesta para endpoints paginados.
 *
 * Evita exponer directamente la representación interna de Spring Data
 * y mantiene una API sencilla para el frontend.
 */
public record PaginaResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {

    public static <T> PaginaResponse<T> from(Page<T> page) {
        return new PaginaResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast()
        );
    }
}