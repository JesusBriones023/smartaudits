package com.smartaudits.v2.acquisition.capture;

import com.smartaudits.v2.acquisition.destination.DestinationUrl;
import com.smartaudits.v2.acquisition.destination.UrlParseResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Test-only transport: no sockets, no resolver, no network. It returns HTML written by the tests,
 * always with SIMULADA_LOCAL provenance. It exercises the integration, never the real transport.
 */
public final class TransporteLocalDePrueba implements TransporteHtml {

    public static final String ID = "transporte-local-de-prueba";

    private final Map<String, Function<DestinationUrl, CapturaHtml>> respuestas = new HashMap<>();
    private final List<DestinationUrl> llamadas = new ArrayList<>();

    public static DestinationUrl destino(String url) {
        return ((UrlParseResult.Parsed) DestinationUrl.parse(url)).url();
    }

    public static CapturaHtml captura(DestinationUrl solicitada, DestinationUrl urlFinal, int estado, String tipo, String html) {
        return new CapturaHtml(solicitada, urlFinal, estado, tipo, html, Instant.now(), ProcedenciaCaptura.simulada(ID));
    }

    /** A 200 text/html page at this URL. */
    public TransporteLocalDePrueba pagina(String url, String html) {
        return responder(url, d -> captura(d, d, 200, "text/html; charset=utf-8", html));
    }

    public TransporteLocalDePrueba responder(String url, Function<DestinationUrl, CapturaHtml> respuesta) {
        respuestas.put(destino(url).canonicalForm(), respuesta);
        return this;
    }

    /** Forgets every page and call (the test context shares one instance across tests). */
    public TransporteLocalDePrueba reiniciar() {
        respuestas.clear();
        llamadas.clear();
        return this;
    }

    public List<DestinationUrl> llamadas() {
        return List.copyOf(llamadas);
    }

    @Override
    public CapturaHtml capturar(DestinationUrl url) {
        llamadas.add(url);
        Function<DestinationUrl, CapturaHtml> respuesta = respuestas.get(url.canonicalForm());
        if (respuesta == null) {
            throw new CapturaException(CapturaException.Motivo.TRANSPORTE, "Sin respuesta local para la URL");
        }
        return respuesta.apply(url);
    }
}
