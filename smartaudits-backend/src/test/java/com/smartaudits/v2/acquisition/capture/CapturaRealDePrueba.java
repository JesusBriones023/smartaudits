package com.smartaudits.v2.acquisition.capture;

import com.smartaudits.v2.acquisition.destination.DestinationUrl;

import java.time.Instant;

/**
 * Synthetic persistence fixture only. Calls the existing package-private provenance factory;
 * no sockets or DNS, no production constructor changes. It proves neither real acquisition
 * nor D1/D2/D9/R-EGR safety and must never be installed as an application transport.
 */
public final class CapturaRealDePrueba {
    private CapturaRealDePrueba() {}

    public static CapturaHtml crear(DestinationUrl solicitada, DestinationUrl finalUrl, String html) {
        return new CapturaHtml(solicitada, finalUrl, 200, "text/html; charset=utf-8", html,
                Instant.parse("2026-11-02T12:00:00Z"), ProcedenciaCaptura.real("fixture-pv13-sin-red", "fixture-catalogo"));
    }
}
