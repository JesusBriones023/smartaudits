package com.smartaudits.v2.acquisition.capture.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartaudits.model.Usuario;
import com.smartaudits.repository.AuditoriaRepository;
import com.smartaudits.service.AuditQuotaService;
import com.smartaudits.service.AuditoriaService;
import com.smartaudits.service.HistorialService;
import com.smartaudits.service.html.CategoriaDocumentoLegal;
import com.smartaudits.service.motor.MotorAnalisisLegal;
import com.smartaudits.service.url.IngestaUrlService;
import com.smartaudits.v2.acquisition.capture.CapturaException;
import com.smartaudits.v2.acquisition.capture.CapturaException.Motivo;
import com.smartaudits.v2.acquisition.capture.CapturaUrlConfig;
import com.smartaudits.v2.acquisition.capture.TransporteHtml;
import com.smartaudits.v2.acquisition.capture.TransporteHttpNoDisponible;
import com.smartaudits.v2.acquisition.capture.TransporteHttpReal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** The real transport behind the existing ingestion service, and the fail-closed wiring. */
class IngestaConTransporteRealTest {
    static final String HTML = """
            <html><head><base href="https://evil.example.invalid/"></head><body>
            <h1>Política de Privacidad</h1><p>Responsable del tratamiento: Ejemplo S.L.</p>
            <footer><a href="/cookies">Política de cookies</a></footer></body></html>""";

    static PkiDePrueba.Credencial ca;
    static SSLContext cliente;
    ServidorHttpDePrueba servidor;

    @BeforeAll
    static void pki() throws Exception {
        ca = PkiDePrueba.ca("CA de pruebas ingesta");
        cliente = PkiDePrueba.cliente(ca);
    }

    @AfterEach
    void cerrar() throws IOException {
        if (servidor != null) {
            servidor.close();
        }
    }

    @Test
    void ingestionThroughTheRealTransportAnalysesButNeverPersistsADeclaredPeerCapture() throws Exception {
        servidor = ServidorHttpDePrueba.tls(PkiDePrueba.servidor(PkiDePrueba.hoja(ca, "www.ejemplo.es"), ca), ServidorHttpDePrueba.html(HTML));
        var resolver = new TransporteHttpRealTest.ResolverFalso().con("www.ejemplo.es", TransporteHttpRealTest.PUBLICA);
        var conector = new ConectorDePrueba().ruta(TransporteHttpRealTest.PUBLICA, servidor.puerto());
        var transporte = new TransporteHttpReal(resolver, new ClienteHttpFijado(conector, cliente.getSocketFactory(),
                TransporteHttpRealTest.RAPIDOS), TransporteHttpRealTest.RELOJ);
        AuditoriaRepository auditorias = mock(AuditoriaRepository.class);
        HistorialService historial = mock(HistorialService.class);
        var auditoriaService = new AuditoriaService(auditorias, new MotorAnalisisLegal(), historial, new ObjectMapper(),
                mock(AuditQuotaService.class));
        var ingesta = new IngestaUrlService(transporte, auditoriaService, new MotorAnalisisLegal());

        var analisis = ingesta.analizar("https://www.ejemplo.es/legal", "Política de Privacidad");

        assertThat(analisis.texto()).contains("Responsable del tratamiento").doesNotContain("<");
        assertThat(analisis.resultado().getPuntuacionRiesgo()).isNotNull();
        assertThat(analisis.enlaces().detectados()).extracting(d -> d.categoria(), d -> d.url())
                .containsExactly(org.assertj.core.groups.Tuple.tuple(CategoriaDocumentoLegal.POLITICA_COOKIES, "https://www.ejemplo.es/cookies"));
        assertThat(analisis.captura().procedencia().esReal()).isFalse();

        assertThatThrownBy(() -> ingesta.auditar("https://www.ejemplo.es/legal", "t", "Política de Privacidad", new Usuario(), "192.0.2.70"))
                .isInstanceOf(CapturaException.class)
                .satisfies(e -> assertThat(((CapturaException) e).motivo()).isEqualTo(Motivo.CAPTURA_SIMULADA));
        verify(auditorias, never()).save(any());
        verify(historial, never()).registrarCreacion(any(), any(), any());
    }

    @Test
    void theNormalConfigurationNeverExposesTheRealTransport() {
        var runner = new ApplicationContextRunner().withUserConfiguration(CapturaUrlConfig.class);
        runner.run(context -> {
            assertThat(context.getBean(TransporteHtml.class)).isInstanceOf(TransporteHttpNoDisponible.class);
            assertThat(context.getBeansOfType(TransporteHttpReal.class)).isEmpty();
            assertThat(context.getBeansOfType(ClienteHttpFijado.class)).isEmpty();
        });
        runner.withPropertyValues(CapturaUrlConfig.PROPIEDAD + "=true").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void transportSourcesHaveNoTrustAllNoPermissiveVerifierAndNoSystemResolutionOrProxy() throws IOException {
        List<String> prohibidos = List.of("getByName", "getAllByName", "getLocalHost", "java.net.URL", "HttpURLConnection",
                "java.net.http", "ProxySelector", "X509TrustManager", "TrustManager", "HostnameVerifier", "setHostnameVerifier",
                "ALLOW_ALL", "trustAll", "InsecureTrust", "setEndpointIdentificationAlgorithm(null)", "SSLv3", "TLSv1.1",
                "followRedirects", "CookieHandler", "ResponseCache");
        for (Path dir : List.of(Paths.get("src/main/java/com/smartaudits/v2/acquisition/capture/http"),
                Paths.get("src/main/java/com/smartaudits/v2/acquisition/capture/dns"))) {
            try (Stream<Path> archivos = Files.list(dir)) {
                for (Path f : archivos.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String codigo = Files.readString(f);
                    for (String token : prohibidos) {
                        assertThat(codigo).as(f + " usa " + token).doesNotContain(token);
                    }
                }
            }
        }
        String cliente = Files.readString(Paths.get("src/main/java/com/smartaudits/v2/acquisition/capture/http/ClienteHttpFijado.java"));
        assertThat(cliente).contains("setEndpointIdentificationAlgorithm(\"HTTPS\")", "new SNIHostName(host)", "IdentidadTls.coincide");
        assertThat(Files.readString(Paths.get("src/main/java/com/smartaudits/v2/acquisition/capture/http/ConectorDirecto.java")))
                .contains("new Socket(Proxy.NO_PROXY)", "InetAddress.getByAddress(ip.bytes())");
        assertThat(Files.readString(Paths.get("src/main/java/com/smartaudits/v2/acquisition/capture/dns/ResolvedorDnsDirecto.java")))
                .contains("new Socket(Proxy.NO_PROXY)", "InetAddress.getByAddress(ipServidor.clone())");
    }
}
