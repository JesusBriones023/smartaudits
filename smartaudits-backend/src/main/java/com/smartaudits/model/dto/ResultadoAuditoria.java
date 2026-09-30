package com.smartaudits.model.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ResultadoAuditoria {
    private String resumen;

    @JsonAlias("puntuacion")
    private Integer puntuacionRiesgo;

    private List<String> riesgos = new ArrayList<>();
    private List<ErrorAuditoria> errores = new ArrayList<>();
    private List<String> recomendaciones = new ArrayList<>();
    private List<String> textosSugeridos = new ArrayList<>();
    private List<String> referenciasLegales = new ArrayList<>();
    private List<String> faltantes = new ArrayList<>();

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ErrorAuditoria {
        private String ruleId;
        private String motor;
        private String version;
        private String titulo;
        private String descripcion;
        private String severidad;
        private String evidencia;
        private String impacto;
        private String accion;
    }
}
