package com.smartaudits.model.dto;

import com.smartaudits.service.html.CategoriaDocumentoLegal;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Informe conjunto. No hay puntuación global: el motor solo define la puntuación de un
 * documento, así que cada uno conserva la suya.
 *
 * @param documentos            en el orden en que se aportaron
 * @param recomendaciones       cada recomendación una vez, con los documentos donde aparece
 * @param categoriasNoAportadas categorías legales sin ningún documento en esta auditoría
 * @param cobertura             matriz documental de las siete categorías legales
 */
public record AuditoriaConjuntaResponse(
        Long id,
        String titulo,
        LocalDateTime fechaCreacion,
        Long usuarioId,
        int documentosAnalizados,
        List<Documento> documentos,
        List<RecomendacionAgrupada> recomendaciones,
        List<String> categoriasNoAportadas,
        MatrizCobertura cobertura) {

    /**
     * DOCUMENTO_ANALIZADO: a supplied document whose content was analysed belongs to the
     * category. ENLACE_DETECTADO_NO_ANALIZADO: only links point to it; their content was never
     * fetched. NO_VERIFICADO: nothing supplied says anything, which does not prove absence.
     */
    public enum EstadoCobertura { DOCUMENTO_ANALIZADO, ENLACE_DETECTADO_NO_ANALIZADO, NO_VERIFICADO }

    /**
     * @param categorias       always the seven legal categories, in a fixed order
     * @param sinEvidencias    documents stored before evidence was kept: declared category only, links unknown
     * @param enlacesLimitados documents whose link list was cut by the processing or storage limits
     */
    public record MatrizCobertura(List<CoberturaCategoria> categorias, List<DocumentoRef> sinEvidencias,
            List<DocumentoRef> enlacesLimitados) {}

    /** @param contradicciones documents declared in this category whose own content identifies another one */
    public record CoberturaCategoria(CategoriaDocumentoLegal categoria, String etiqueta, EstadoCobertura estado,
            List<DocumentoCobertura> documentos, List<EnlaceCobertura> enlaces, List<Contradiccion> contradicciones) {}

    /** @param categoriaDeclarada the category the user chose for the document */
    public record DocumentoCobertura(Long auditoriaId, String titulo, String categoriaDeclarada,
            List<CoberturaDocumento.Senal> evidencias, boolean historico) {}

    public record Contradiccion(Long auditoriaId, String titulo, List<String> identificadas) {}

    /** @param relativa unresolved relative path: kept apart per document, it cannot be compared across sites */
    public record EnlaceCobertura(String url, boolean relativa, List<OrigenEnlace> origenes) {}

    public record OrigenEnlace(Long auditoriaId, String titulo, String texto, String contexto, String senal) {}

    public record DocumentoRef(Long auditoriaId, String titulo) {}

    /**
     * Resultado de un documento: es su auditoría individual, enlazable por auditoriaId.
     *
     * @param desglosePuntuacion cómo se compone su puntuación; null en auditorías anteriores al desglose
     */
    public record Documento(
            Long auditoriaId,
            String titulo,
            String tipoDocumento,
            String urlOpcional,
            Integer puntuacionRiesgo,
            String resumen,
            List<String> riesgos,
            String versionMotor,
            String versionReglas,
            List<ResultadoAuditoria.ErrorAuditoria> incidencias,
            ResultadoAuditoria.DesglosePuntuacion desglosePuntuacion) {}

    public record RecomendacionAgrupada(String texto, List<Long> auditoriaIds, List<String> documentos) {}

    /** Fila del historial. */
    public record Resumen(Long id, String titulo, LocalDateTime fechaCreacion, int documentosAnalizados) {}
}
