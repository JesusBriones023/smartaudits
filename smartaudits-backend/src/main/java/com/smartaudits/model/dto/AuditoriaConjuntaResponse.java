package com.smartaudits.model.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Informe conjunto. No hay puntuación global: el motor solo define la puntuación de un
 * documento, así que cada uno conserva la suya.
 *
 * @param documentos            en el orden en que se aportaron
 * @param recomendaciones       cada recomendación una vez, con los documentos donde aparece
 * @param categoriasNoAportadas categorías legales sin ningún documento en esta auditoría
 */
public record AuditoriaConjuntaResponse(
        Long id,
        String titulo,
        LocalDateTime fechaCreacion,
        Long usuarioId,
        int documentosAnalizados,
        List<Documento> documentos,
        List<RecomendacionAgrupada> recomendaciones,
        List<String> categoriasNoAportadas) {

    /** Resultado de un documento: es su auditoría individual, enlazable por auditoriaId. */
    public record Documento(
            Long auditoriaId,
            String titulo,
            String tipoDocumento,
            String urlOpcional,
            Integer puntuacionRiesgo,
            String resumen,
            String versionMotor,
            String versionReglas,
            List<ResultadoAuditoria.ErrorAuditoria> incidencias) {}

    public record RecomendacionAgrupada(String texto, List<Long> auditoriaIds, List<String> documentos) {}

    /** Fila del historial. */
    public record Resumen(Long id, String titulo, LocalDateTime fechaCreacion, int documentosAnalizados) {}
}
