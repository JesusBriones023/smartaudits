package com.smartaudits.service.url;

import com.smartaudits.model.TipoFuente;
import com.smartaudits.model.Usuario;
import com.smartaudits.model.dto.AuditoriaResponse;
import com.smartaudits.model.dto.DeteccionEnlacesResponse;
import com.smartaudits.model.dto.ResultadoAuditoria;
import com.smartaudits.service.AuditoriaService;
import com.smartaudits.service.AuditQuotaService;
import com.smartaudits.service.html.DetectorEnlacesLegales;
import com.smartaudits.service.motor.AnalizadorLegal;
import com.smartaudits.service.motor.EntradaAnalisis;
import com.smartaudits.v2.acquisition.capture.CapturaException;
import com.smartaudits.v2.acquisition.capture.CapturaException.Motivo;
import com.smartaudits.v2.acquisition.capture.CapturaHtml;
import com.smartaudits.v2.acquisition.capture.ControlConcurrenciaCaptura;
import com.smartaudits.v2.acquisition.capture.TransporteHtml;
import com.smartaudits.v2.acquisition.destination.DestinationUrl;
import com.smartaudits.v2.acquisition.destination.UrlParseResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Locale;
import java.util.Set;

/**
 * URL → capture → the existing services. It holds no extraction, analysis or link rules of its own:
 * text comes from {@link AuditoriaService#textoAuditableDeHtml}, the analysis from {@link AnalizadorLegal},
 * links from {@link DetectorEnlacesLegales} and persistence from {@link AuditoriaService#crearAuditoriaAnalizada}.
 * The URL passes the destination rules before any transport is called, and the capture is validated
 * again here, independently of the transport. Only a real capture may become a persisted audit, as
 * {@link TipoFuente#CRAWLER}; with the current fail-closed transport no capture is ever obtained.
 */
@Service
@RequiredArgsConstructor
public class IngestaUrlService {

    static final Set<String> TIPOS_HTML = Set.of("text/html", "application/xhtml+xml");
    static final Set<String> TIPOS_DOCUMENTO = Set.of("Política de Privacidad", "Aviso Legal", "Política de Cookies",
            "Términos y Condiciones", "Condiciones de Uso", "Documento Completo");

    private final TransporteHtml transporte;
    private final AuditoriaService auditoriaService;
    private final AnalizadorLegal analizador;
    private final AuditQuotaService cuota;
    private final ControlConcurrenciaCaptura concurrencia;
    private final DetectorEnlacesLegales detector = new DetectorEnlacesLegales();

    /** Result of a capture analysed without persisting anything. */
    public record AnalisisCaptura(CapturaHtml captura, String texto, ResultadoAuditoria resultado,
                                  DeteccionEnlacesResponse enlaces) {}

    /** Validated capture of the URL; CapturaException otherwise. */
    public CapturaHtml capturar(String url) {
        exigirContextoSinRecursos();
        return capturar(destinoValido(url));
    }

    private static DestinationUrl destinoValido(String url) {
        if (url == null) {
            throw new CapturaException(Motivo.URL_RECHAZADA, "La URL es obligatoria");
        }
        UrlParseResult analizada = DestinationUrl.parse(url);
        if (analizada instanceof UrlParseResult.Rejected rejected) {
            throw new CapturaException(Motivo.URL_RECHAZADA,
                    "La URL no es válida para la adquisición (" + rejected.code() + ")");
        }
        return ((UrlParseResult.Parsed) analizada).url();
    }

    private CapturaHtml capturar(DestinationUrl destino) {
        exigirContextoSinRecursos();
        return concurrencia.ejecutar(() -> {
            CapturaHtml captura;
            try {
                captura = transporte.capturar(destino);
            } catch (CapturaException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new CapturaException(Motivo.TRANSPORTE, "El transporte no pudo obtener la página", e);
            }
            if (captura == null) {
                throw new CapturaException(Motivo.TRANSPORTE, "El transporte no devolvió ninguna captura");
            }
            validar(destino, captura);
            return captura;
        });
    }

    /** Captures the URL and analyses it with the existing extractor, engine and link discovery. */
    public AnalisisCaptura analizar(String url, String tipoDocumento) {
        exigirContextoSinRecursos();
        tipoValido(tipoDocumento);
        CapturaHtml captura = capturar(url);
        String texto = auditoriaService.textoAuditableDeHtml(captura.html());
        ResultadoAuditoria resultado = analizador.analyze(new EntradaAnalisis(texto, tipoDocumento));
        // Relative links resolve against the page actually read, never against <base>.
        DeteccionEnlacesResponse enlaces = detector.detectar(captura.html(), captura.urlFinal().canonicalForm());
        return new AnalisisCaptura(captura, texto, resultado, enlaces);
    }

    /** Captures the URL and stores it as an audit (CRAWLER) through the existing flow; real captures only. */
    public AuditoriaResponse auditar(String url, String titulo, String tipoDocumento, Usuario usuario, String ipAcceso) {
        exigirContextoSinRecursos();
        tipoValido(tipoDocumento);
        DestinationUrl destino = destinoValido(url);
        cuota.precomprobarPuedeCrear(usuario);
        // The advisory query must have released its resources before any transport call.
        CapturaHtml captura = capturar(destino);
        if (!captura.procedencia().esReal()) {
            throw new CapturaException(Motivo.CAPTURA_SIMULADA,
                    "Una captura simulada no se guarda como auditoría: no procede de una página real");
        }
        String texto = auditoriaService.textoAuditableDeHtml(captura.html());
        // Cross-bean call: the existing service proxy starts the persistence transaction here,
        // repeats the authoritative locked quota check and rolls back the complete write on failure.
        return auditoriaService.crearAuditoriaAnalizada(titulo, tipoDocumento, texto,
                captura.urlFinal().canonicalForm(), TipoFuente.CRAWLER, usuario, ipAcceso);
    }

    /**
     * Acquisition cannot join/suspend an outer transaction or borrow an OSIV persistence context.
     * An inactive transaction alone does not prove a JDBC connection has been released. Reject
     * bound JPA/JDBC holders too, even an OSIV holder that has not obtained a connection yet.
     * Explicit checks also protect self-invocation and instances used without a Spring proxy.
     */
    private static void exigirContextoSinRecursos() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("La adquisición debe invocarse fuera de una transacción");
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.getResourceMap().values().stream()
                        .anyMatch(holder -> holder instanceof EntityManagerHolder || holder instanceof ConnectionHolder)) {
            throw new IllegalStateException("La adquisición requiere un contexto sin recursos JPA/JDBC vinculados (incluido OSIV)");
        }
    }

    private static void validar(DestinationUrl destino, CapturaHtml captura) {
        if (!captura.urlSolicitada().equals(destino)) {
            throw new CapturaException(Motivo.RESPUESTA_NO_VALIDA, "La captura no corresponde a la URL solicitada");
        }
        if (captura.estadoHttp() != 200) {
            throw new CapturaException(Motivo.RESPUESTA_NO_VALIDA,
                    "La página respondió con el estado HTTP " + captura.estadoHttp());
        }
        String tipo = captura.tipoContenido().split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!TIPOS_HTML.contains(tipo)) {
            throw new CapturaException(Motivo.RESPUESTA_NO_VALIDA, "El contenido capturado no es HTML");
        }
    }

    private static void tipoValido(String tipoDocumento) {
        if (!TIPOS_DOCUMENTO.contains(tipoDocumento)) {
            throw new IllegalArgumentException("El tipo de documento no es válido");
        }
    }
}
