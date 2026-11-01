package com.smartaudits.model.dto;

import com.smartaudits.service.html.CategoriaDocumentoLegal;

import java.util.List;

/**
 * Enlaces del HTML aportado que parecen documentos legales. Son candidatos encontrados
 * por análisis estático: no se han descargado, seguido ni verificado.
 *
 * @param detectados            un elemento por categoría y URL, en orden de aparición
 * @param noDetectados          categorías sin ningún enlace candidato en el HTML
 * @param enlacesAnalizados     enlaces con href examinados (como máximo el límite)
 * @param enlacesDescartados    enlaces ignorados por esquema no http(s), credenciales,
 *                              solo fragmento o formato inválido
 * @param limiteAlcanzado       se alcanzó el máximo de enlaces procesados o de resultados
 * @param referenciaAplicada    las rutas relativas se resolvieron contra la URL de referencia
 */
public record DeteccionEnlacesResponse(
        List<EnlaceLegalDetectado> detectados,
        List<CategoriaDocumentoLegal> noDetectados,
        int enlacesAnalizados,
        int enlacesDescartados,
        boolean limiteAlcanzado,
        boolean referenciaAplicada) {

    /**
     * @param relativa  ruta relativa conservada tal cual (no había URL de referencia)
     * @param externo   el host difiere del de la URL de referencia; null si no se puede saber
     * @param contexto  footer, nav, header, aside o cuerpo
     * @param senal     texto, atributo o ruta: qué indicó la categoría
     */
    public record EnlaceLegalDetectado(
            CategoriaDocumentoLegal categoria,
            String texto,
            String url,
            boolean relativa,
            Boolean externo,
            String contexto,
            String senal) {}
}
