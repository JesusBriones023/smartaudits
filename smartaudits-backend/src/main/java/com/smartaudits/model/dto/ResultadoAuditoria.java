package com.smartaudits.model.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
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

    /**
     * How each context-aware rule was settled, with the exact clause it relied on. Results
     * produced before rules version 2 have none, and none is reconstructed for them.
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<EvaluacionRegla> evaluaciones = new ArrayList<>();

    /** How puntuacionRiesgo was made up; null for results produced before it existed (never reconstructed). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private DesglosePuntuacion desglosePuntuacion;

    @JsonInclude(JsonInclude.Include.NON_NULL) // results stored before coverage existed stay byte-identical
    private CoberturaDocumento coberturaDocumento;

    /** Copy carrying this document's coverage; the analyser's own result is never modified. */
    public ResultadoAuditoria conCobertura(CoberturaDocumento cobertura) {
        return new ResultadoAuditoria(resumen, puntuacionRiesgo, riesgos, errores, recomendaciones, textosSugeridos,
                referenciasLegales, faltantes, evaluaciones, desglosePuntuacion, cobertura);
    }

    /** CONTEXTUAL: settled by evidence states; PALABRAS_CLAVE: legacy keyword match, applicability never determined. */
    public enum MetodoControl { CONTEXTUAL, PALABRAS_CLAVE }

    /** @param estado only for CONTEXTUAL controls */
    public record ControlPuntuado(String ruleId, int peso, int puntos, MetodoControl metodo, EstadoEvidencia estado) {}

    /**
     * Breakdown of the unchanged 100-point score (interpretation "desglose-1"):
     * puntuacion = puntosAcreditados - penalizacionRiesgos, and the 100 points split into weights
     * not applicable, not verifiable, contextual evaluated and keyword-based.
     *
     * @param porcentajeContextual share of the evaluated contextual weight that the text accredits;
     *                             null when no contextual control applies and is verifiable
     * @param motivoSinPorcentaje  why porcentajeContextual is null
     */
    public record DesglosePuntuacion(String version, int puntuacion, int pesoTotal, int puntosAcreditados,
                                     int penalizacionRiesgos, int pesoNoAplicable, int pesoNoVerificable,
                                     int pesoContextualEvaluado, int puntosContextuales, int pesoPalabrasClave,
                                     int puntosPalabrasClave, Integer porcentajeContextual, String motivoSinPorcentaje,
                                     List<ControlPuntuado> controles) {}

    /**
     * SUFICIENTE: the text substantiates the information. INSUFICIENTE: it is mentioned or only
     * partly given. POSIBLE_INCIDENCIA: it is missing where it applies, or the wording itself is
     * problematic. NO_VERIFICABLE: the text alone cannot tell whether it is required.
     * NO_APLICABLE: it does not belong to this type of document.
     */
    public enum EstadoEvidencia { SUFICIENTE, INSUFICIENTE, POSIBLE_INCIDENCIA, NO_VERIFICABLE, NO_APLICABLE }

    /** @param evidencia the analysed clause (whitespace collapsed), or why there is none */
    public record EvaluacionRegla(String ruleId, EstadoEvidencia estado, String evidencia, String detalle) {}

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

        /** Only set by context-aware rules; null for every other rule and for historical results. */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private EstadoEvidencia estado;

        public ErrorAuditoria(String ruleId, String motor, String version, String titulo, String descripcion,
                              String severidad, String evidencia, String impacto, String accion) {
            this(ruleId, motor, version, titulo, descripcion, severidad, evidencia, impacto, accion, null);
        }
    }
}
