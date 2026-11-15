package com.smartaudits.v2.acquisition.capture;

import com.smartaudits.v2.acquisition.destination.DestinationUrl;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.smartaudits.v2.acquisition.capture.TransporteLocalDePrueba.destino;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ACQ-PROVENANCE-01 contract: real-persistence eligibility belongs to a capture object created by
 * the trusted factory, never to a label. No network: every capture is built in memory. The ingestion
 * decision itself is exercised in IngestaUrlProcedenciaIntegrationTest.
 */
class CapturaProcedenciaTest {

    static final DestinationUrl URL = destino("https://www.ejemplo.es/privacidad");
    static final String HTML = "<p>Responsable del tratamiento: Ejemplo S.L.</p>";
    static final String HTML_AJENO = "<p>Contenido arbitrario que nunca obtuvo el transporte validado</p>";
    static final String RECHAZO = "procedencia real no se adjunta";

    /** The strongest caller-made label: HTTP_REAL built by reflection, past Java access control. */
    static ProcedenciaCaptura etiquetaRealForjada() throws Exception {
        Constructor<ProcedenciaCaptura> c = ProcedenciaCaptura.class.getDeclaredConstructor(
                ProcedenciaCaptura.Origen.class, String.class, String.class);
        c.setAccessible(true);
        return c.newInstance(ProcedenciaCaptura.Origen.HTTP_REAL, "forjado", "revision-forjada");
    }

    @Test
    void aSimulatedCaptureRemainsUsableOfflineButIsNeverVerifiedReal() {
        CapturaHtml simulada = TransporteLocalDePrueba.captura(URL, URL, 200, "text/html", HTML);
        assertThat(simulada.html()).isEqualTo(HTML);
        assertThat(simulada.procedencia().origen()).isEqualTo(ProcedenciaCaptura.Origen.SIMULADA_LOCAL);
        assertThat(simulada.procedencia().esReal()).isFalse();
        assertThat(simulada.esRealVerificada()).isFalse();
        assertThat(simulada.toString()).contains("SIMULADA_LOCAL").doesNotContain("ejemplo");
    }

    @Test
    void theTrustedFactoryBindsARealProvenanceToItsOwnContent() {
        CapturaHtml real = CapturaRealDePrueba.crear(URL, URL, HTML);
        assertThat(real.esRealVerificada()).isTrue();
        assertThat(real.procedencia().esReal()).isTrue();
        assertThat(real.procedencia().transporte()).isEqualTo(CapturaRealDePrueba.TRANSPORTE);
        assertThat(real.procedencia().revisionCatalogo()).isEqualTo(CapturaRealDePrueba.REVISION);
        assertThat(real.html()).isEqualTo(HTML);
        // Value semantics and the former record's text are kept for reporting.
        assertThat(real.procedencia()).isEqualTo(CapturaRealDePrueba.crear(URL, URL, "<p>otro</p>").procedencia())
                .hasSameHashCodeAs(CapturaRealDePrueba.crear(URL, URL, "<p>otro</p>").procedencia());
        assertThat(real.procedencia().toString()).isEqualTo("ProcedenciaCaptura[origen=HTTP_REAL, transporte="
                + CapturaRealDePrueba.TRANSPORTE + ", revisionCatalogo=" + CapturaRealDePrueba.REVISION + "]");
    }

    @Test
    void aRealProvenanceCopiedFromAnotherCaptureCannotMakeOtherHtmlTrusted() {
        ProcedenciaCaptura copiada = CapturaRealDePrueba.crear(URL, URL, HTML).procedencia();
        assertThatThrownBy(() -> new CapturaHtml(URL, URL, 200, "text/html", HTML_AJENO, Instant.now(), copiada))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(RECHAZO);
    }

    @Test
    void aGenuineRealCaptureRebuiltFieldByFieldIsRejected() {
        CapturaHtml g = CapturaRealDePrueba.crear(URL, URL, HTML);
        assertThatThrownBy(() -> new CapturaHtml(g.urlSolicitada(), g.urlFinal(), g.estadoHttp(), g.tipoContenido(),
                g.html(), g.fechaCaptura(), g.procedencia()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(RECHAZO);
        assertThat(Modifier.isFinal(CapturaHtml.class.getModifiers())).as("no subclass can override eligibility").isTrue();
    }

    @Test
    void aSimulatedCaptureCannotBeRelabelledReal() {
        CapturaHtml s = TransporteLocalDePrueba.captura(URL, URL, 200, "text/html", HTML);
        ProcedenciaCaptura real = CapturaRealDePrueba.crear(URL, URL, HTML).procedencia();
        assertThatThrownBy(() -> new CapturaHtml(s.urlSolicitada(), s.urlFinal(), s.estadoHttp(), s.tipoContenido(),
                s.html(), s.fechaCaptura(), real))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(RECHAZO);
    }

    @Test
    void aCallerForgedHttpRealLabelGrantsNothing() throws Exception {
        ProcedenciaCaptura forjada = etiquetaRealForjada();
        assertThat(forjada.esReal()).as("the label itself says real").isTrue();
        // ...but the only public way to attach a provenance refuses it: the label alone grants no eligibility.
        assertThatThrownBy(() -> new CapturaHtml(URL, URL, 200, "text/html", HTML_AJENO, Instant.now(), forjada))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(RECHAZO);
    }

    @Test
    void noPublicApiCanMintOrAttachARealProvenance() {
        for (Class<?> tipo : List.of(ProcedenciaCaptura.class, CapturaHtml.class)) {
            assertThat(Modifier.isFinal(tipo.getModifiers())).as(tipo + " final").isTrue();
            assertThat(tipo.isRecord()).as(tipo + " has no public canonical constructor").isFalse();
            assertThat(Arrays.stream(tipo.getDeclaredFields()).filter(f -> !f.isSynthetic())
                    .filter(f -> !Modifier.isStatic(f.getModifiers()))
                    .allMatch(f -> Modifier.isPrivate(f.getModifiers()) && Modifier.isFinal(f.getModifiers())))
                    .as(tipo + " immutable").isTrue();
            List<Executable> visibles = new ArrayList<>();
            visibles.addAll(Arrays.asList(tipo.getDeclaredConstructors()));
            visibles.addAll(Arrays.asList(tipo.getDeclaredMethods()));
            for (Executable e : visibles) {
                boolean expuesto = Modifier.isPublic(e.getModifiers()) || Modifier.isProtected(e.getModifiers());
                if (expuesto) {
                    assertThat(e.getParameterTypes()).as(e + " accepts an origin").doesNotContain(ProcedenciaCaptura.Origen.class);
                    assertThat(e.getName()).as(e + " mints real provenance").isNotEqualTo("real");
                }
            }
        }
        assertThat(ProcedenciaCaptura.class.getConstructors()).isEmpty();
        assertThat(CapturaHtml.class.getConstructors()).singleElement()
                .satisfies(c -> assertThat(c.getParameterTypes()).endsWith(ProcedenciaCaptura.class));
    }

    /**
     * Structural guard, not a proof: in production sources only TransporteHttpReal calls the real
     * capture factory, and only CapturaHtml calls the real provenance factory.
     */
    @Test
    void onlyTheValidatedTransportMintsRealCapturesInProductionSources() throws IOException {
        Pattern capturaReal = Pattern.compile("CapturaHtml\\s*(\\.|::)\\s*real\\b");
        Pattern procedenciaReal = Pattern.compile("ProcedenciaCaptura\\s*(\\.|::)\\s*real\\b");
        List<String> usanCaptura = new ArrayList<>();
        List<String> usanProcedencia = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Paths.get("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String code = Files.readString(f);
                if (capturaReal.matcher(code).find()) usanCaptura.add(f.getFileName().toString());
                if (procedenciaReal.matcher(code).find()) usanProcedencia.add(f.getFileName().toString());
            }
        }
        assertThat(usanCaptura).containsExactly("TransporteHttpReal.java");
        assertThat(usanProcedencia).containsExactly("CapturaHtml.java");
    }
}
