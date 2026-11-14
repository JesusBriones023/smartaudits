package com.smartaudits.v2.acquisition.capture;

import com.smartaudits.service.html.ExtractorTextoHtml;
import com.smartaudits.v2.acquisition.destination.DestinationUrl;

import java.time.Instant;
import java.util.Objects;

/**
 * One HTML page handed over by a {@link TransporteHtml}: the internal contract between a transport
 * and the audit services. It is untrusted data: the ingestion service validates it again (status,
 * content type, requested URL) before extracting text. The HTML is bounded here, so no transport can
 * hand over more than {@link #MAX_HTML_LENGTH} characters. {@link #toString()} reveals no URL.
 *
 * <p>Provenance (ACQ-PROVENANCE-01): eligibility for real-source persistence is a property of this
 * object, decided when it is created, not of a label a caller supplies. The public constructor only
 * builds SIMULATED captures and refuses a real {@link ProcedenciaCaptura}, so a real label copied from
 * another capture, or rebuilt, cannot be attached to arbitrary HTML. A real capture is created only by
 * the package-private {@link #real} factory, which mints its provenance itself; in production sources
 * only {@link TransporteHttpReal} calls it, and only when every connection's peer was observed on the
 * socket. The class is final and immutable, so a real capture cannot be subclassed or altered; the
 * same instance can be handed over again, which is why ingestion still checks it against the URL
 * requested. Java access control is not a defense against code already running in the same JVM.
 */
public final class CapturaHtml {

    /** Same limit as pasted HTML. */
    public static final int MAX_HTML_LENGTH = ExtractorTextoHtml.MAX_HTML_LENGTH;
    public static final int MAX_TIPO_CONTENIDO_LENGTH = 200;

    private final DestinationUrl urlSolicitada;
    private final DestinationUrl urlFinal;
    private final int estadoHttp;
    private final String tipoContenido;
    private final String html;
    private final Instant fechaCaptura;
    private final ProcedenciaCaptura procedencia;

    /**
     * A SIMULATED capture: test adapters, offline processing and transports whose peer was not observed.
     *
     * @param urlSolicitada the destination the caller asked for
     * @param urlFinal      the destination finally read (differs after redirects; each one must have
     *                      been evaluated by the destination policy inside the transport)
     * @param tipoContenido raw Content-Type header value
     * @param procedencia   a simulated provenance; a real one is refused (only {@link #real} creates it)
     * @throws IllegalArgumentException if {@code procedencia} is real
     */
    public CapturaHtml(DestinationUrl urlSolicitada, DestinationUrl urlFinal, int estadoHttp, String tipoContenido,
                       String html, Instant fechaCaptura, ProcedenciaCaptura procedencia) {
        this(soloSimulada(procedencia), urlSolicitada, urlFinal, estadoHttp, tipoContenido, html, fechaCaptura);
    }

    /**
     * A REAL capture, with a provenance minted here and bound to this content. Package-private: in
     * production sources it is called only by {@link TransporteHttpReal}, after every hop's peer was
     * observed on the socket and matched the approved evaluation.
     *
     * @param transporte       identifier of the transport implementation
     * @param revisionCatalogo revision of the address catalog of the approving evaluation
     */
    static CapturaHtml real(DestinationUrl urlSolicitada, DestinationUrl urlFinal, int estadoHttp, String tipoContenido,
                            String html, Instant fechaCaptura, String transporte, String revisionCatalogo) {
        return new CapturaHtml(ProcedenciaCaptura.real(transporte, revisionCatalogo),
                urlSolicitada, urlFinal, estadoHttp, tipoContenido, html, fechaCaptura);
    }

    private static ProcedenciaCaptura soloSimulada(ProcedenciaCaptura procedencia) {
        if (Objects.requireNonNull(procedencia, "procedencia").esReal()) {
            throw new IllegalArgumentException("una procedencia real no se adjunta a una captura: solo la crea "
                    + "el transporte validado junto con su contenido");
        }
        return procedencia;
    }

    /** The only constructor that stores a provenance; reached only through the two paths above. */
    private CapturaHtml(ProcedenciaCaptura procedencia, DestinationUrl urlSolicitada, DestinationUrl urlFinal,
                        int estadoHttp, String tipoContenido, String html, Instant fechaCaptura) {
        this.urlSolicitada = Objects.requireNonNull(urlSolicitada, "urlSolicitada");
        this.urlFinal = Objects.requireNonNull(urlFinal, "urlFinal");
        if (estadoHttp < 100 || estadoHttp > 599) {
            throw new IllegalArgumentException("estado HTTP fuera de rango");
        }
        this.estadoHttp = estadoHttp;
        this.tipoContenido = Objects.requireNonNull(tipoContenido, "tipoContenido");
        if (tipoContenido.length() > MAX_TIPO_CONTENIDO_LENGTH) {
            throw new IllegalArgumentException("tipo de contenido demasiado largo");
        }
        this.html = Objects.requireNonNull(html, "html");
        if (html.length() > MAX_HTML_LENGTH) {
            throw new IllegalArgumentException("el HTML capturado supera los " + MAX_HTML_LENGTH + " caracteres");
        }
        this.fechaCaptura = Objects.requireNonNull(fechaCaptura, "fechaCaptura");
        this.procedencia = Objects.requireNonNull(procedencia, "procedencia");
    }

    public DestinationUrl urlSolicitada() {
        return urlSolicitada;
    }

    public DestinationUrl urlFinal() {
        return urlFinal;
    }

    public int estadoHttp() {
        return estadoHttp;
    }

    public String tipoContenido() {
        return tipoContenido;
    }

    public String html() {
        return html;
    }

    public Instant fechaCaptura() {
        return fechaCaptura;
    }

    /** Reporting metadata; it grants nothing by itself. */
    public ProcedenciaCaptura procedencia() {
        return procedencia;
    }

    /**
     * Persistence eligibility as a real-source audit: true only for a capture created by {@link #real}.
     * Sound because the public constructor refuses every real provenance and this class is final.
     */
    public boolean esRealVerificada() {
        return procedencia.esReal();
    }

    @Override
    public String toString() {
        return "CapturaHtml[" + procedencia.origen() + ", estado " + estadoHttp + ", " + html.length() + " caracteres]";
    }
}
