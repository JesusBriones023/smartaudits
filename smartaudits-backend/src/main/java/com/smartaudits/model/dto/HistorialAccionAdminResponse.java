package com.smartaudits.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO de listado del historial de acciones administrativas.
 * Usa los snapshots de nombre/email para que el historial sea legible
 * aunque los usuarios involucrados hayan cambiado sus datos después.
 */
@Data
@NoArgsConstructor
public class HistorialAccionAdminResponse {
    private Long id;
    private Long adminId;
    private String adminNombre;
    private String adminEmail;
    private Long objetivoId;
    private String objetivoNombre;
    private String objetivoEmail;
    private String tipoAccion;       // "DESACTIVAR", "REACTIVAR", "PROMOVER", "DEGRADAR"
    private String tipoAccionEtiqueta; // "Desactivar usuario", etc.
    private String detalles;
    private LocalDateTime fecha;
}
