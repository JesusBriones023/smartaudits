package com.smartaudits.v2.acquisition.capture;

import java.util.Objects;

/**
 * Where a capture comes from. A simulated capture (a local test adapter, no network) is never a
 * real capture and is never persisted as an audit. A real capture can only be created inside this
 * package, by the validated HTTP transport, which does not exist yet: today nothing can produce one.
 *
 * @param origen           kind of transport
 * @param transporte       identifier of the transport implementation that produced the capture
 * @param revisionCatalogo revision of the address catalog of the approving evaluation (real only)
 */
public record ProcedenciaCaptura(Origen origen, String transporte, String revisionCatalogo) {

    public enum Origen {
        /** Produced by a local adapter without sockets: test or demonstration data, never a real page. */
        SIMULADA_LOCAL,
        /** Obtained by the validated real HTTP transport (D1, D2, D9 and R-EGR closed). Not available. */
        HTTP_REAL
    }

    public ProcedenciaCaptura {
        Objects.requireNonNull(origen, "origen");
        if (transporte == null || transporte.isBlank() || transporte.length() > 100) {
            throw new IllegalArgumentException("transporte");
        }
        if (origen == Origen.HTTP_REAL && (revisionCatalogo == null || revisionCatalogo.isBlank())) {
            throw new IllegalArgumentException("una captura real documenta la revisión del catálogo que la aprobó");
        }
    }

    /** Provenance of a capture produced by a local adapter without network. */
    public static ProcedenciaCaptura simulada(String transporte) {
        return new ProcedenciaCaptura(Origen.SIMULADA_LOCAL, transporte, null);
    }

    /**
     * Provenance of a real capture. Package-private on purpose: only the future validated transport,
     * in this package, may state that a page was really obtained.
     */
    static ProcedenciaCaptura real(String transporte, String revisionCatalogo) {
        return new ProcedenciaCaptura(Origen.HTTP_REAL, transporte, revisionCatalogo);
    }

    public boolean esReal() {
        return origen == Origen.HTTP_REAL;
    }
}
