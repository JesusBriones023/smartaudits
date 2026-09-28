package com.smartaudits.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class AuditoriaResponse {
    private Long id;
    private String titulo;
    private String tipoDocumento;
    private LocalDateTime fechaCreacion;
    private Integer puntuacionRiesgo;
    private String urlOpcional;
    private String textoOriginal;
    private ResultadoAuditoria resultado;

    // Información del usuario que creó la auditoría
    private Long usuarioId;
    private String usuarioNombre;
    private String usuarioEmail;
}
