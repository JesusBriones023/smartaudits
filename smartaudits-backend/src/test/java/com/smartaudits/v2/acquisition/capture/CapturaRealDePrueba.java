package com.smartaudits.v2.acquisition.capture;

import com.smartaudits.v2.acquisition.destination.DestinationUrl;

import java.time.Instant;

/**
 * Synthetic persistence fixture only: the authorized isolated-test path to a real capture. It lives
 * in the capture package (test sources, never packaged) and calls the package-private
 * {@link CapturaHtml#real} factory; no sockets or DNS. It proves neither real acquisition nor
 * D1/D2/D9/R-EGR safety and must never be installed as an application transport.
 */
public final class CapturaRealDePrueba {
    public static final String TRANSPORTE = "fixture-pv13-sin-red";
    public static final String REVISION = "fixture-catalogo";

    private CapturaRealDePrueba() {}

    public static CapturaHtml crear(DestinationUrl solicitada, DestinationUrl finalUrl, String html) {
        return crear(solicitada, finalUrl, 200, "text/html; charset=utf-8", html);
    }

    /** A real fixture with any status/type, so ingestion's own response validation can be exercised. */
    public static CapturaHtml crear(DestinationUrl solicitada, DestinationUrl finalUrl, int estado, String tipo, String html) {
        return CapturaHtml.real(solicitada, finalUrl, estado, tipo, html, Instant.parse("2026-11-02T12:00:00Z"),
                TRANSPORTE, REVISION);
    }
}
