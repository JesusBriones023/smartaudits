package com.smartaudits.model.dto;

import com.smartaudits.model.TipoFuente;

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
    private String versionMotor;
    private String versionReglas;
    private LocalDateTime fechaAnalisis;
    private TipoFuente tipoFuente;
    private Integer puntuacionRiesgo;
    private String urlOpcional;
    private String textoOriginal;
    private ResultadoAuditoria resultado;

    // Información del usuario que creó la auditoría
    private Long usuarioId;
    private String usuarioNombre;
    private String usuarioEmail;
}
