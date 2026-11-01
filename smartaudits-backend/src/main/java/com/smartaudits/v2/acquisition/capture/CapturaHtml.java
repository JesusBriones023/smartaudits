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
     * @param urlSolicitada the destination the caller asked for
     * @param urlFinal      the destination finally read (differs after redirects; each one must have
     *                      been evaluated by the destination policy inside the transport)
     * @param tipoContenido raw Content-Type header value
     */
    public CapturaHtml(DestinationUrl urlSolicitada, DestinationUrl urlFinal, int estadoHttp, String tipoContenido,
                       String html, Instant fechaCaptura, ProcedenciaCaptura procedencia) {
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

    public ProcedenciaCaptura procedencia() {
        return procedencia;
    }

    @Override
    public String toString() {
        return "CapturaHtml[" + procedencia.origen() + ", estado " + estadoHttp + ", " + html.length() + " caracteres]";
    }
}
