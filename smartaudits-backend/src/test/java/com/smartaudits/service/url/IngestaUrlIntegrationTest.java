package com.smartaudits.service.url;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartaudits.model.Role;
import com.smartaudits.model.Usuario;
import com.smartaudits.model.dto.AuditoriaConjuntaRequest;
import com.smartaudits.model.dto.AuditoriaHtmlRequest;
import com.smartaudits.repository.AuditoriaRepository;
import com.smartaudits.repository.UsuarioRepository;
import com.smartaudits.service.AuditQuotaService;
import com.smartaudits.service.AuditoriaConjuntaService;
import com.smartaudits.service.AuditoriaService;
import com.smartaudits.service.HistorialService;
import com.smartaudits.service.html.CategoriaDocumentoLegal;
import com.smartaudits.service.html.ContenidoHtmlInvalidoException;
import com.smartaudits.service.html.DetectorEnlacesLegales;
import com.smartaudits.service.html.ExtractorTextoHtml;
import com.smartaudits.service.motor.AnalizadorLegal;
import com.smartaudits.service.motor.EntradaAnalisis;
import com.smartaudits.service.motor.MotorAnalisisLegal;
import com.smartaudits.v2.acquisition.capture.CapturaException;
import com.smartaudits.v2.acquisition.capture.CapturaException.Motivo;
import com.smartaudits.v2.acquisition.capture.ProcedenciaCaptura;
import com.smartaudits.v2.acquisition.capture.TransporteHttpNoDisponible;
import com.smartaudits.v2.acquisition.capture.TransporteLocalDePrueba;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.util.List;

import static com.smartaudits.v2.acquisition.capture.TransporteLocalDePrueba.captura;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * URL → SIMULATED local capture → existing extractor, engine, link discovery and audit services.
 * Every capture here is test data from {@link TransporteLocalDePrueba}; none is a real page.
 */
@DataJpaTest(showSql = false, properties = {
        "spring.config.location=optional:classpath:/phase21-no-config.properties",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
        "spring.sql.init.mode=never",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "logging.level.root=WARN", "logging.level.org.springframework=WARN"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Import({AuditoriaService.class, HistorialService.class, AuditQuotaService.class, MotorAnalisisLegal.class,
        ObjectMapper.class, AuditoriaConjuntaService.class, IngestaUrlService.class, IngestaUrlIntegrationTest.Local.class})
class IngestaUrlIntegrationTest {
    static final String URL = "https://www.ejemplo.es/legal/privacidad";
    static final String HTML = """
            <html><head><base href="https://evil.example.invalid/"><script>fetch('/x')</script></head><body>
            <h1>Política de Privacidad</h1>
            <p>Responsable del tratamiento: Ejemplo S.L. Contacto: privacidad@example.invalid.</p>
            <ul><li>Derecho de acceso</li><li>Derecho de supresión</li></ul>
            <footer><a href="../cookies">Política de cookies</a><a href="javascript:alert(1)">Aviso legal</a>
            <a href="https://otra.example.invalid/terminos">Términos y condiciones</a></footer>
            </body></html>""";

    @TestConfiguration
    static class Local {
        @Bean
        TransporteLocalDePrueba transporte() {
            return new TransporteLocalDePrueba();
        }
    }

    @Autowired IngestaUrlService ingesta;
    @Autowired TransporteLocalDePrueba transporte;
    @Autowired AuditoriaService auditorias;
    @Autowired AuditoriaConjuntaService conjuntas;
    @Autowired AnalizadorLegal analizador;
    @Autowired AuditoriaRepository audits;
    @Autowired UsuarioRepository users;

    @BeforeEach
    void pages() {
        transporte.reiniciar().pagina(URL, HTML);
    }

    Usuario user(String email) {
        Usuario u = new Usuario();
        u.setNombre("Cliente " + email);
        u.setEmail(email);
        u.setPassword("unused-test-fixture");
        u.setRole(Role.CLIENTE);
        return users.saveAndFlush(u);
    }

    @Test
    void urlToSimulatedCaptureToHtml() {
        var captura = ingesta.capturar(URL);

        assertThat(captura.urlSolicitada().canonicalForm()).isEqualTo(URL);
        assertThat(captura.urlFinal().canonicalForm()).isEqualTo(URL);
        assertThat(captura.estadoHttp()).isEqualTo(200);
        assertThat(captura.tipoContenido()).isEqualTo("text/html; charset=utf-8");
        assertThat(captura.html()).isEqualTo(HTML);
        assertThat(captura.fechaCaptura()).isNotNull();
        assertThat(captura.procedencia()).isEqualTo(ProcedenciaCaptura.simulada(TransporteLocalDePrueba.ID));
        assertThat(captura.procedencia().esReal()).isFalse();
        assertThat(transporte.llamadas()).extracting(d -> d.canonicalForm()).containsExactly(URL);
    }

    @Test
    void htmlToExtractionToAnalysisToLinksWithTheExistingServices() {
        var analisis = ingesta.analizar(URL, "Política de Privacidad");

        String texto = new ExtractorTextoHtml().extraer(HTML);
        assertThat(analisis.texto()).isEqualTo(texto).doesNotContain("<", "fetch");
        assertThat(analisis.resultado()).isEqualTo(analizador.analyze(new EntradaAnalisis(texto, "Política de Privacidad")));
        assertThat(analisis.enlaces()).isEqualTo(new DetectorEnlacesLegales().detectar(HTML, URL));
        // Relative links resolve against the captured URL, never against <base>; javascript: is discarded.
        assertThat(analisis.enlaces().detectados()).extracting(d -> d.categoria(), d -> d.url(), d -> d.externo())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(CategoriaDocumentoLegal.POLITICA_COOKIES, "https://www.ejemplo.es/cookies", false),
                        org.assertj.core.groups.Tuple.tuple(CategoriaDocumentoLegal.TERMINOS_CONDICIONES, "https://otra.example.invalid/terminos", true));
        assertThat(analisis.enlaces().enlacesDescartados()).isEqualTo(1);
        assertThat(audits.count()).isZero();
    }

    @Test
    void afterARedirectLinksResolveAgainstTheFinalUrl() {
        String movida = "https://www.ejemplo.es/privacidad-antigua";
        transporte.responder(movida, d -> captura(d, TransporteLocalDePrueba.destino("https://legal.ejemplo.es/v2/privacidad"),
                200, "application/xhtml+xml", "<p>Privacidad</p><a href=\"cookies\">Cookies</a>"));

        var analisis = ingesta.analizar(movida, "Política de Privacidad");

        assertThat(analisis.captura().urlFinal().canonicalForm()).isEqualTo("https://legal.ejemplo.es/v2/privacidad");
        assertThat(analisis.enlaces().detectados()).extracting(d -> d.url()).containsExactly("https://legal.ejemplo.es/v2/cookies");
    }

    @Test
    void invalidUrlsAreRejectedBeforeAnyTransportCall() {
        for (String url : List.of("javascript:alert(1)", "file:///etc/passwd", "https://127.0.0.1/", "https://[::1]/",
                "https://localhost/", "https://user@ejemplo.es/", "ftp://ejemplo.es/", "https://ejemplo.es:8443/", "")) {
            assertThatThrownBy(() -> ingesta.analizar(url, "Aviso Legal"))
                    .as(url).isInstanceOf(CapturaException.class)
                    .satisfies(e -> assertThat(((CapturaException) e).motivo()).isEqualTo(Motivo.URL_RECHAZADA));
        }
        assertThatThrownBy(() -> ingesta.capturar(null)).isInstanceOf(CapturaException.class);
        assertThat(transporte.llamadas()).isEmpty();
    }

    @Test
    void transportAndResponseErrorsPropagateWithTheirReason() {
        transporte.responder("https://www.ejemplo.es/404", d -> captura(d, d, 404, "text/html", "<p>No encontrada</p>"))
                .responder("https://www.ejemplo.es/pdf", d -> captura(d, d, 200, "application/pdf", "%PDF-1.7"))
                .responder("https://www.ejemplo.es/otra", d -> captura(TransporteLocalDePrueba.destino(URL), d, 200, "text/html", "<p>x</p>"))
                .responder("https://www.ejemplo.es/fallo", d -> { throw new IllegalStateException("conexión reiniciada"); })
                .responder("https://www.ejemplo.es/nula", d -> null)
                .responder("https://www.ejemplo.es/vacia", d -> captura(d, d, 200, "text/html", "<script>x()</script>"));

        expectReason("https://www.ejemplo.es/404", Motivo.RESPUESTA_NO_VALIDA, "estado HTTP 404");
        expectReason("https://www.ejemplo.es/pdf", Motivo.RESPUESTA_NO_VALIDA, "no es HTML");
        expectReason("https://www.ejemplo.es/otra", Motivo.RESPUESTA_NO_VALIDA, "no corresponde a la URL solicitada");
        expectReason("https://www.ejemplo.es/fallo", Motivo.TRANSPORTE, "no pudo obtener la página");
        expectReason("https://www.ejemplo.es/nula", Motivo.TRANSPORTE, "no devolvió ninguna captura");
        expectReason("https://www.ejemplo.es/sin-respuesta", Motivo.TRANSPORTE, "Sin respuesta local");
        assertThatThrownBy(() -> ingesta.analizar("https://www.ejemplo.es/vacia", "Aviso Legal"))
                .isInstanceOf(ContenidoHtmlInvalidoException.class).hasMessageContaining("no contiene texto legible");
        assertThatThrownBy(() -> ingesta.analizar(URL, "Documento inventado")).isInstanceOf(IllegalArgumentException.class);
    }

    void expectReason(String url, Motivo motivo, String mensaje) {
        assertThatThrownBy(() -> ingesta.analizar(url, "Aviso Legal"))
                .as(url).isInstanceOf(CapturaException.class).hasMessageContaining(mensaje)
                .satisfies(e -> assertThat(((CapturaException) e).motivo()).isEqualTo(motivo));
    }

    @Test
    void sizeLimitsOfTheExistingFlowApply() {
        transporte.responder("https://www.ejemplo.es/larga",
                d -> captura(d, d, 200, "text/html", "<p>" + "a".repeat(15_001) + "</p>"));
        assertThatThrownBy(() -> ingesta.analizar("https://www.ejemplo.es/larga", "Aviso Legal"))
                .isInstanceOf(ContenidoHtmlInvalidoException.class).hasMessageContaining("15000");
        transporte.responder("https://www.ejemplo.es/enorme",
                d -> captura(d, d, 200, "text/html", "x".repeat(ExtractorTextoHtml.MAX_HTML_LENGTH + 1)));
        // The contract itself refuses oversized HTML, so the transport failure is reported as such.
        expectReason("https://www.ejemplo.es/enorme", Motivo.TRANSPORTE, "no pudo obtener la página");
    }

    @Test
    void aSimulatedCaptureIsNeverPersistedAsAnAudit() {
        Usuario owner = user("ingesta@example.invalid");
        assertThatThrownBy(() -> ingesta.auditar(URL, "Privacidad", "Política de Privacidad", owner, "192.0.2.61"))
                .isInstanceOf(CapturaException.class)
                .satisfies(e -> assertThat(((CapturaException) e).motivo()).isEqualTo(Motivo.CAPTURA_SIMULADA));
        assertThat(audits.count()).isZero();
    }

    @Test
    void theDefaultRealAdapterProducesNoCaptureAndNoAudit() {
        Usuario owner = user("bloqueado@example.invalid");
        var bloqueado = new IngestaUrlService(new TransporteHttpNoDisponible(), auditorias, analizador);
        for (var accion : List.<Runnable>of(() -> bloqueado.capturar(URL), () -> bloqueado.analizar(URL, "Aviso Legal"),
                () -> bloqueado.auditar(URL, "t", "Aviso Legal", owner, "192.0.2.62"))) {
            assertThatThrownBy(accion::run).isInstanceOf(CapturaException.class)
                    .hasMessage(TransporteHttpNoDisponible.MENSAJE)
                    .satisfies(e -> assertThat(((CapturaException) e).motivo()).isEqualTo(Motivo.NO_AUTORIZADA));
        }
        assertThat(audits.count()).isZero();
    }

    @Test
    void theCapturedHtmlGivesTheSameResultAsTheIntegratedFeatures() {
        Usuario owner = user("compat@example.invalid");
        var analisis = ingesta.analizar(URL, "Política de Privacidad");

        // Pasted HTML audit (PRODUCT-VERTICAL-01) of the same HTML.
        var pegado = new AuditoriaHtmlRequest();
        pegado.setTitulo("Pegado");
        pegado.setTipoDocumento("Política de Privacidad");
        pegado.setHtml(HTML);
        var html = auditorias.crearAuditoriaDesdeHtml(pegado, owner, "192.0.2.63");
        assertThat(html.getResultado()).isEqualTo(analisis.resultado());
        assertThat(html.getTextoOriginal()).isEqualTo(analisis.texto());

        // Joint audit (PRODUCT-VERTICAL-03) of the same HTML: same per-document analysis.
        var documento = new AuditoriaConjuntaRequest.Documento();
        documento.setTitulo("Privacidad");
        documento.setTipoDocumento("Política de Privacidad");
        documento.setHtml(HTML);
        var conjunta = new AuditoriaConjuntaRequest();
        conjunta.setTitulo("Conjunta");
        conjunta.setDocumentos(List.of(documento));
        var informe = conjuntas.crear(conjunta, owner, "192.0.2.63");
        assertThat(informe.documentos().get(0).puntuacionRiesgo()).isEqualTo(analisis.resultado().getPuntuacionRiesgo());
        assertThat(informe.documentos().get(0).incidencias()).isEqualTo(analisis.resultado().getErrores());

        // Link discovery (PRODUCT-VERTICAL-02) with the captured URL as reference.
        assertThat(analisis.enlaces()).isEqualTo(new DetectorEnlacesLegales().detectar(HTML, URL));
    }
}
