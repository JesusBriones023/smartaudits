package com.smartaudits.v2.acquisition.capture;

import com.smartaudits.v2.acquisition.destination.DestinationUrl;

/**
 * Place of the real HTTP adapter. It has no network code and always refuses: the HTTP client
 * (D1), the resolver (D2) and the TLS profile (D9) are undecided and the egress criterion (R-EGR)
 * is blocking, so no request to a user-supplied domain is authorized.
 */
public final class TransporteHttpNoDisponible implements TransporteHtml {

    public static final String MENSAJE = "La captura real por URL no está disponible: el transporte HTTP no está "
            + "validado (D1, D2 y D9 abiertas; R-EGR bloqueante).";

    @Override
    public CapturaHtml capturar(DestinationUrl url) {
        throw new CapturaException(CapturaException.Motivo.NO_AUTORIZADA, MENSAJE);
    }
}
