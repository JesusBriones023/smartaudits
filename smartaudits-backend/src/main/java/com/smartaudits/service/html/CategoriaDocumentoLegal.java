package com.smartaudits.service.html;

import java.util.List;

/**
 * Documentos legales que pueden aparecer enlazados en una página. Las expresiones se
 * comparan sin tildes ni mayúsculas y por palabras completas; las de ruta, contra los
 * segmentos de la URL con guiones, barras y puntos convertidos en espacios.
 */
public enum CategoriaDocumentoLegal {
    POLITICA_PRIVACIDAD(
            List.of("politica de privacidad", "privacidad", "proteccion de datos", "aviso de privacidad",
                    "privacy policy", "privacy notice", "privacy", "data protection"),
            List.of("privacidad", "privacy", "proteccion de datos", "proteccion datos", "rgpd", "gdpr"),
            List.of("privacy-policy")),
    POLITICA_COOKIES(
            List.of("politica de cookies", "uso de cookies", "cookies", "cookie policy", "cookie notice",
                    "cookie settings", "cookie"),
            List.of("cookies", "cookie"),
            List.of()),
    AVISO_LEGAL(
            List.of("aviso legal", "nota legal", "informacion legal", "legal notice", "imprint", "impressum"),
            List.of("aviso legal", "nota legal", "legal notice", "imprint", "impressum"),
            List.of()),
    TERMINOS_CONDICIONES(
            List.of("terminos y condiciones", "terminos y condiciones generales", "terminos", "terms and conditions",
                    "terms of service", "terms"),
            List.of("terminos y condiciones", "terminos", "terms and conditions", "terms of service", "terms", "tos"),
            List.of("terms-of-service")),
    CONDICIONES_USO(
            List.of("condiciones de uso", "condiciones generales de uso", "terminos de uso", "terms of use",
                    "conditions of use"),
            List.of("condiciones de uso", "condiciones uso", "terminos de uso", "terms of use", "conditions of use"),
            List.of()),
    CONDICIONES_COMPRA(
            List.of("condiciones de compra", "condiciones generales de compra", "condiciones de venta",
                    "condiciones generales de contratacion", "condiciones de contratacion",
                    "terms of sale", "conditions of sale", "terms of purchase", "purchase conditions"),
            List.of("condiciones de compra", "condiciones compra", "condiciones de venta", "condiciones de contratacion",
                    "condiciones contratacion", "terms of sale", "conditions of sale", "terms of purchase"),
            List.of()),
    DECLARACION_ACCESIBILIDAD(
            List.of("declaracion de accesibilidad", "accesibilidad", "accessibility statement", "accessibility"),
            List.of("accesibilidad", "accessibility"),
            List.of());

    private final List<String> expresiones;
    private final List<String> expresionesRuta;
    private final List<String> rel;

    CategoriaDocumentoLegal(List<String> expresiones, List<String> expresionesRuta, List<String> rel) {
        this.expresiones = expresiones;
        this.expresionesRuta = expresionesRuta;
        this.rel = rel;
    }

    List<String> expresiones() {
        return expresiones;
    }

    List<String> expresionesRuta() {
        return expresionesRuta;
    }

    /** Tipos de enlace estándar (atributo rel) que identifican la categoría por sí solos. */
    List<String> rel() {
        return rel;
    }
}
