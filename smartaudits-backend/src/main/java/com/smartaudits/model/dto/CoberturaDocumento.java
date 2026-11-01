package com.smartaudits.model.dto;

import com.smartaudits.service.html.CategoriaDocumentoLegal;
import java.util.List;

/**
 * Evidence of what a supplied HTML document is, kept inside its existing result JSON: the
 * signals that identified its legal category and the legal links it contains. Never raw HTML.
 * Audits stored before this existed have no such metadata (null), and none is reconstructed.
 */
public record CoberturaDocumento(List<Senal> senales, DeteccionEnlacesResponse enlaces) {

    /** @param origen where the signal came from (HTML title, H1, reference URL, opening, declared category) */
    public record Senal(CategoriaDocumentoLegal categoria, String origen, String evidencia) {}

    /** Copy keeping only the first {@code n} links, flagged as limited. */
    public CoberturaDocumento conEnlaces(int n) {
        return new CoberturaDocumento(senales, new DeteccionEnlacesResponse(
                List.copyOf(enlaces.detectados().subList(0, Math.min(n, enlaces.detectados().size()))),
                enlaces.noDetectados(), enlaces.enlacesAnalizados(), enlaces.enlacesDescartados(), true,
                enlaces.referenciaAplicada()));
    }
}
