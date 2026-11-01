package com.smartaudits.v2.acquisition.capture;

import com.smartaudits.v2.acquisition.capture.CapturaException.Motivo;
import com.smartaudits.v2.acquisition.capture.http.ClienteHttpFijado;
import com.smartaudits.v2.acquisition.capture.http.ObservacionConexion;
import com.smartaudits.v2.acquisition.capture.http.RespuestaHttp;
import com.smartaudits.v2.acquisition.destination.DestinationDecision;
import com.smartaudits.v2.acquisition.destination.DestinationPolicy;
import com.smartaudits.v2.acquisition.destination.DestinationUrl;
import com.smartaudits.v2.acquisition.destination.HostResolver;
import com.smartaudits.v2.acquisition.destination.IpAddress;
import com.smartaudits.v2.acquisition.destination.UrlParseResult;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Real HTTP/HTTPS transport (ADR-0002 section 17). NOT wired into the application: CapturaUrlConfig
 * keeps the fail-closed TransporteHttpNoDisponible until D1, D2, D9 and R-EGR are closed.
 *
 * Every hop, the first one and each redirect: a new evaluation of {@link DestinationPolicy} (a new
 * DNS answer, every address classified, one forbidden address rejects all), then one connection to
 * the first approved address only (another address would need a new evaluation, so there is no
 * fallback), then the connection's observations are checked against that evaluation. Redirects:
 * at most {@code maxRedirecciones}, no repeated URL, no https to http. A capture is REAL only when
 * every connection's peer was observed on the socket and matched the approval; through a test
 * connector that declares its peer the capture is SIMULATED.
 */
public final class TransporteHttpReal implements TransporteHtml {

    public static final String ID = "http-fijado-jdk/1";
    static final Set<Integer> REDIRECCIONES = Set.of(301, 302, 303, 307, 308);
    static final Map<String, Charset> CODIFICACIONES = Map.of(
            "utf-8", StandardCharsets.UTF_8, "iso-8859-1", StandardCharsets.ISO_8859_1,
            "us-ascii", StandardCharsets.US_ASCII, "windows-1252", Charset.forName("windows-1252"));

    private final DestinationPolicy politica;
    private final ClienteHttpFijado cliente;
    private final Clock reloj;

    public TransporteHttpReal(HostResolver resolver, ClienteHttpFijado cliente, Clock reloj) {
        this.politica = DestinationPolicy.using(Objects.requireNonNull(resolver, "resolver"));
        this.cliente = Objects.requireNonNull(cliente, "cliente");
        this.reloj = Objects.requireNonNull(reloj, "reloj");
    }

    @Override
    public CapturaHtml capturar(DestinationUrl solicitada) {
        Set<String> visitadas = new HashSet<>();
        boolean todoObservado = true;
        String revision = null;
        DestinationUrl actual = solicitada;
        for (int saltos = 0; ; saltos++) {
            if (!visitadas.add(actual.canonicalForm())) {
                throw new CapturaException(Motivo.RESPUESTA_NO_VALIDA, "Bucle de redirecciones");
            }
            DestinationDecision decision = politica.evaluate(actual); // never an earlier approval
            if (decision instanceof DestinationDecision.Rejected rechazo) {
                throw new CapturaException(Motivo.URL_RECHAZADA, "Destino rechazado por la política (" + rechazo.code() + ")");
            }
            DestinationDecision.Approved aprobada = (DestinationDecision.Approved) decision;
            IpAddress ip = aprobada.addresses().get(0);
            RespuestaHttp respuesta = cliente.obtener(actual, ip);
            comprobar(aprobada, actual, ip, respuesta.observacion());
            todoObservado &= respuesta.observacion().peerObservado();
            revision = aprobada.catalogRevision();

            if (REDIRECCIONES.contains(respuesta.estado())) {
                if (saltos >= cliente.limites().maxRedirecciones()) {
                    throw new CapturaException(Motivo.RESPUESTA_NO_VALIDA, "Demasiadas redirecciones");
                }
                if (respuesta.numeroDe("location") != 1) {
                    throw new CapturaException(Motivo.RESPUESTA_NO_VALIDA, "Redirección sin una única cabecera Location");
                }
                actual = siguiente(actual, respuesta.cabecera("location"));
                continue;
            }
            if (respuesta.estado() != 200) {
                throw new CapturaException(Motivo.RESPUESTA_NO_VALIDA, "La página respondió con el estado HTTP " + respuesta.estado());
            }
            String tipo = respuesta.numeroDe("content-type") == 1 ? respuesta.cabecera("content-type") : "";
            String media = tipo.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
            if (!media.equals("text/html") && !media.equals("application/xhtml+xml")) {
                throw new CapturaException(Motivo.RESPUESTA_NO_VALIDA, "El contenido capturado no es HTML");
            }
            String html = decodificar(respuesta.cuerpo(), tipo);
            ProcedenciaCaptura procedencia = todoObservado ? ProcedenciaCaptura.real(ID, revision)
                    : ProcedenciaCaptura.simulada(ID + " con conector de prueba (peer declarado, no observado)");
            return new CapturaHtml(solicitada, actual, 200, tipo, html, reloj.instant(), procedencia);
        }
    }

    /** The connection really is the evaluated one: same URL, an approved address, its port, its TLS identity. */
    static void comprobar(DestinationDecision.Approved aprobada, DestinationUrl url, IpAddress elegida, ObservacionConexion o) {
        String host = url.host().name();
        boolean ok = o != null && aprobada.url().equals(url) && o.url().equals(url)
                && o.peer().equals(elegida) && aprobada.addresses().contains(o.peer()) && o.puerto() == url.port()
                && host.equals(o.host());
        if (ok && url.scheme().equals("https")) {
            ok = o.tls() && host.equals(o.sni()) && host.equals(o.identidadTls()) && o.protocoloTls() != null;
        } else if (ok) {
            ok = !o.tls();
        }
        if (!ok) {
            throw new CapturaException(Motivo.TRANSPORTE, "La conexión observada no corresponde a la evaluación aprobada");
        }
    }

    /** Location resolved against the current URL; it must pass the destination rules again. */
    static DestinationUrl siguiente(DestinationUrl actual, String location) {
        if (location == null || location.isBlank() || location.length() > 2048) {
            throw new CapturaException(Motivo.RESPUESTA_NO_VALIDA, "Redirección sin destino válido");
        }
        String resuelta;
        try {
            resuelta = new URI(actual.canonicalForm()).resolve(new URI(location)).toString();
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new CapturaException(Motivo.URL_RECHAZADA, "La redirección no es una URL válida");
        }
        UrlParseResult analizada = DestinationUrl.parse(resuelta);
        if (analizada instanceof UrlParseResult.Rejected rechazo) {
            throw new CapturaException(Motivo.URL_RECHAZADA, "La redirección no es un destino válido (" + rechazo.code() + ")");
        }
        DestinationUrl destino = ((UrlParseResult.Parsed) analizada).url();
        if (actual.scheme().equals("https") && destino.scheme().equals("http")) {
            throw new CapturaException(Motivo.URL_RECHAZADA, "Redirección de HTTPS a HTTP no permitida");
        }
        return destino;
    }

    static String decodificar(byte[] cuerpo, String tipoContenido) {
        Charset charset = StandardCharsets.UTF_8;
        for (String parametro : tipoContenido.split(";")) {
            String[] partes = parametro.strip().split("=", 2);
            if (partes.length == 2 && partes[0].strip().equalsIgnoreCase("charset")) {
                charset = CODIFICACIONES.get(partes[1].strip().replace("\"", "").toLowerCase(Locale.ROOT));
                if (charset == null) {
                    throw new CapturaException(Motivo.RESPUESTA_NO_VALIDA, "Codificación de caracteres no admitida");
                }
            }
        }
        String html;
        try {
            html = charset.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE).decode(ByteBuffer.wrap(cuerpo)).toString();
        } catch (CharacterCodingException e) {
            throw new CapturaException(Motivo.RESPUESTA_NO_VALIDA, "Contenido no decodificable");
        }
        if (html.length() > CapturaHtml.MAX_HTML_LENGTH) {
            throw new CapturaException(Motivo.RESPUESTA_NO_VALIDA, "El HTML capturado supera los " + CapturaHtml.MAX_HTML_LENGTH + " caracteres");
        }
        return html;
    }
}
