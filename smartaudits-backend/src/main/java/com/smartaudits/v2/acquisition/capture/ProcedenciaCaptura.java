package com.smartaudits.v2.acquisition.capture;

import java.util.Objects;

/**
 * Where a capture comes from: reporting metadata, never an authorization on its own (ACQ-PROVENANCE-01).
 * Persistence eligibility belongs to the {@link CapturaHtml} that carries this value, and only
 * {@link CapturaHtml#real} can attach a real origin; the public {@link CapturaHtml} constructor
 * refuses one. A simulated capture (a local test adapter, no network) is never a real capture and
 * is never persisted as an audit.
 *
 * <p>A final class with a private constructor, not a record: a public record always has a public
 * canonical constructor, so any caller could build {@code HTTP_REAL}. Here the only public factory
 * is {@link #simulada}; {@link #real} is package-private and called only by {@link CapturaHtml#real}.
 * Java access control does not protect against reflection or other code already running in the same
 * JVM, nor against a class added to this package from another artifact.
 */
public final class ProcedenciaCaptura {

    public enum Origen {
        /** Produced by a local adapter without sockets: test or demonstration data, never a real page. */
        SIMULADA_LOCAL,
        /** Obtained by the validated real HTTP transport (D1, D2, D9 and R-EGR closed). Not available. */
        HTTP_REAL
    }

    private final Origen origen;
    private final String transporte;
    private final String revisionCatalogo;

    /**
     * @param transporte       identifier of the transport implementation that produced the capture
     * @param revisionCatalogo revision of the address catalog of the approving evaluation (real only)
     */
    private ProcedenciaCaptura(Origen origen, String transporte, String revisionCatalogo) {
        this.origen = Objects.requireNonNull(origen, "origen");
        if (transporte == null || transporte.isBlank() || transporte.length() > 100) {
            throw new IllegalArgumentException("transporte");
        }
        if (origen == Origen.HTTP_REAL && (revisionCatalogo == null || revisionCatalogo.isBlank())) {
            throw new IllegalArgumentException("una captura real documenta la revisión del catálogo que la aprobó");
        }
        this.transporte = transporte;
        this.revisionCatalogo = revisionCatalogo;
    }

    /** Provenance of a capture produced by a local adapter without network. */
    public static ProcedenciaCaptura simulada(String transporte) {
        return new ProcedenciaCaptura(Origen.SIMULADA_LOCAL, transporte, null);
    }

    /**
     * Provenance of a real capture. Package-private and reserved for {@link CapturaHtml#real}, which
     * binds it to the capture's own content; a value obtained here cannot be attached to other HTML.
     */
    static ProcedenciaCaptura real(String transporte, String revisionCatalogo) {
        return new ProcedenciaCaptura(Origen.HTTP_REAL, transporte, revisionCatalogo);
    }

    public Origen origen() {
        return origen;
    }

    public String transporte() {
        return transporte;
    }

    public String revisionCatalogo() {
        return revisionCatalogo;
    }

    /** Reporting only: whether the label says real. Persistence asks {@link CapturaHtml#esRealVerificada()}. */
    public boolean esReal() {
        return origen == Origen.HTTP_REAL;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ProcedenciaCaptura p && origen == p.origen && transporte.equals(p.transporte)
                && Objects.equals(revisionCatalogo, p.revisionCatalogo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(origen, transporte, revisionCatalogo);
    }

    /** Same text as the former record. */
    @Override
    public String toString() {
        return "ProcedenciaCaptura[origen=" + origen + ", transporte=" + transporte
                + ", revisionCatalogo=" + revisionCatalogo + "]";
    }
}
