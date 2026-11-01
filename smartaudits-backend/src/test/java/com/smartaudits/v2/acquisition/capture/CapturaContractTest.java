package com.smartaudits.v2.acquisition.capture;

import com.smartaudits.v2.acquisition.destination.DestinationUrl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static com.smartaudits.v2.acquisition.capture.TransporteLocalDePrueba.destino;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapturaContractTest {

    @Test
    void theRealAdapterAlwaysRefusesWithoutTouchingTheNetwork() {
        var real = new TransporteHttpNoDisponible();
        for (String url : List.of("https://www.ejemplo.es/privacidad", "http://example.com/")) {
            assertThatThrownBy(() -> real.capturar(destino(url)))
                    .isInstanceOf(CapturaException.class)
                    .hasMessage(TransporteHttpNoDisponible.MENSAJE)
                    .satisfies(e -> assertThat(((CapturaException) e).motivo()).isEqualTo(CapturaException.Motivo.NO_AUTORIZADA));
        }
    }

    @Test
    void wiringIsFailClosedByDefaultAndRefusesToEnableTheRealTransport() {
        var runner = new ApplicationContextRunner().withUserConfiguration(CapturaUrlConfig.class);
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(TransporteHtml.class)).isInstanceOf(TransporteHttpNoDisponible.class);
        });
        runner.withPropertyValues(CapturaUrlConfig.PROPIEDAD + "=false")
                .run(context -> assertThat(context.getBean(TransporteHtml.class)).isInstanceOf(TransporteHttpNoDisponible.class));
        runner.withPropertyValues(CapturaUrlConfig.PROPIEDAD + "=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("no existe un transporte HTTP validado");
        });
    }

    @Test
    void aRealProvenanceCannotBeCreatedOutsideThisPackage() throws Exception {
        var real = ProcedenciaCaptura.class.getDeclaredMethod("real", String.class, String.class);
        assertThat(Modifier.isPublic(real.getModifiers())).isFalse();
        assertThat(ProcedenciaCaptura.simulada(TransporteLocalDePrueba.ID).esReal()).isFalse();
        // The canonical constructor is public (records), but a real origin must document an approval.
        assertThatThrownBy(() -> new ProcedenciaCaptura(ProcedenciaCaptura.Origen.HTTP_REAL, "x", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theContractBoundsTheHtmlAndValidatesEveryField() {
        DestinationUrl url = destino("https://www.ejemplo.es/privacidad");
        var procedencia = ProcedenciaCaptura.simulada(TransporteLocalDePrueba.ID);
        String limite = "a".repeat(CapturaHtml.MAX_HTML_LENGTH);
        assertThat(new CapturaHtml(url, url, 200, "text/html", limite, Instant.now(), procedencia).html()).hasSize(CapturaHtml.MAX_HTML_LENGTH);
        assertThatThrownBy(() -> new CapturaHtml(url, url, 200, "text/html", limite + "a", Instant.now(), procedencia))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("500000");
        assertThatThrownBy(() -> new CapturaHtml(url, url, 99, "text/html", "", Instant.now(), procedencia))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CapturaHtml(url, url, 200, "x".repeat(201), "", Instant.now(), procedencia))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CapturaHtml(url, null, 200, "text/html", "", Instant.now(), procedencia))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CapturaHtml(url, url, 200, "text/html", "", null, procedencia))
                .isInstanceOf(NullPointerException.class);
        assertThat(new CapturaHtml(url, url, 200, "text/html", "<p>x</p>", Instant.now(), procedencia).toString())
                .doesNotContain("ejemplo").contains("SIMULADA_LOCAL");
    }

    @Test
    void captureAndIngestionSourcesUseNoNetworkApi() throws IOException {
        List<String> forbidden = List.of("java.net.URL", "URLConnection", "InetAddress", "Socket", "HttpClient",
                "java.net.http", "SocketChannel", "DatagramChannel", "HttpURLConnection", "RestTemplate", "WebClient",
                "ProxySelector", "openConnection", "getByName");
        for (Path dir : List.of(Paths.get("src/main/java/com/smartaudits/v2/acquisition/capture"),
                Paths.get("src/main/java/com/smartaudits/service/url"))) {
            List<Path> files;
            try (Stream<Path> stream = Files.list(dir)) {
                files = stream.filter(f -> f.toString().endsWith(".java")).toList();
            }
            assertThat(files).isNotEmpty();
            for (Path file : files) {
                String code = Files.readString(file);
                for (String token : forbidden) {
                    assertThat(code).as(file + " uses " + token).doesNotContain(token);
                }
            }
        }
    }
}
