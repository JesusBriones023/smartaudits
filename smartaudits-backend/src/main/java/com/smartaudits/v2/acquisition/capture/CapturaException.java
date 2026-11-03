package com.smartaudits.v2.acquisition.capture;

import java.util.Objects;

/** A capture that cannot be obtained or used. The message never contains the URL. */
public class CapturaException extends RuntimeException {

    public enum Motivo {
        /** The real transport is not available or not authorized. */
        NO_AUTORIZADA,
        /** The URL fails the destination rules (ADR-0002 rules 1-25); no transport was called. */
        URL_RECHAZADA,
        /** The transport failed. */
        TRANSPORTE,
        /** The capture is not a usable HTML page (status, content type, requested URL). */
        RESPUESTA_NO_VALIDA,
        /** A simulated capture cannot become a persisted audit. */
        CAPTURA_SIMULADA,
        /** No execution slot was available; no waiting or transport invocation. */
        CAPACIDAD_AGOTADA,
        /** Interruption observed by the synchronous capture owner. */
        CANCELADA
    }

    private final Motivo motivo;

    public CapturaException(Motivo motivo, String message) {
        this(motivo, message, null);
    }

    public CapturaException(Motivo motivo, String message, Throwable cause) {
        super(message, cause);
        this.motivo = Objects.requireNonNull(motivo, "motivo");
    }

    public Motivo motivo() {
        return motivo;
    }
}
