package com.smartaudits.service.motor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartaudits.model.dto.ResultadoAuditoria;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Characterization of the current engine, not assertions of legal correctness. */
class MotorAnalisisLegalRegressionTest {
    private static final String RISK_PREFIX = "Cláusula presente pero con redacción problemática: ";
    private static final Corpus CORPUS = loadCorpus();

    static Stream<CorpusCase> corpusCases() {
        return CORPUS.cases().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpusCases")
    void characterizesCurrentEngineIncludingDocumentedLimitations(CorpusCase example) {
        assertThat(example.id()).isNotBlank();
        assertThat(example.categoria()).isNotBlank();
        assertThat(example.descripcion()).isNotBlank();
        assertThat(example.tipoDocumento()).isNotBlank();
        assertThat(example.texto()).isNotNull();
        assertThat(example.notas()).isNotBlank();
        assertThat(example.knownLimitation()).isNotNull();
        if (example.knownLimitation()) {
            assertThat(example.limitation()).as("Explain the observed limitation and future review")
                    .isNotBlank();
        } else {
            assertThat(example.limitation()).isEmpty();
        }

        ResultadoAuditoria result = new MotorAnalisisLegal().analyze(example.texto(), example.tipoDocumento());
        String context = example.id() + " knownLimitation=" + example.knownLimitation()
                + " — " + example.notas();

        assertThat(result).as(context).isNotNull();
        assertThat(result.getPuntuacionRiesgo()).as(context).isBetween(0, 100)
                .isEqualTo(example.expectedScore());
        assertThat(result.getResumen()).as(context).isNotBlank().contains(example.expectedSummaryMarker());
        assertThat(result.getErrores()).as(context).isNotNull().allSatisfy(error -> {
            assertThat(error.getTitulo()).isNotBlank();
            assertThat(error.getDescripcion()).isNotBlank();
            assertThat(error.getSeveridad()).isIn("ALTA", "MEDIA", "BAJA");
            assertThat(error.getEvidencia()).isNotBlank();
            assertThat(error.getImpacto()).isNotBlank();
            assertThat(error.getAccion()).isNotBlank();
        });

        // Compare the entire incident multiset, including absences and severity.
        // Expectations come only from the checked-in JSON, never engine internals.
        List<Incident> expected = example.expectedIncidents().stream()
                .map(MotorAnalisisLegalRegressionTest::expectedIncident).toList();
        List<Incident> actual = result.getErrores().stream()
                .map(error -> new Incident(error.getTitulo(), error.getSeveridad())).toList();
        assertThat(actual).as(context).containsExactlyInAnyOrderElementsOf(expected);

        Structure structure = example.expectedStructure();
        assertStrings(result.getRiesgos(), structure.riesgos(), context);
        assertStrings(result.getRecomendaciones(), structure.recomendaciones(), context);
        assertStrings(result.getTextosSugeridos(), structure.textosSugeridos(), context);
        assertStrings(result.getReferenciasLegales(), structure.referenciasLegales(), context);
        assertStrings(result.getFaltantes(), structure.faltantes(), context);
        List<String> expectedMissingFields = example.expectedIncidents().stream()
                .filter(id -> id.endsWith(":MISSING"))
                .map(id -> CORPUS.rules().get(id.substring(0, 3)).campoFaltante()).toList();
        assertThat(result.getFaltantes()).as(context)
                .containsExactlyInAnyOrderElementsOf(expectedMissingFields);
    }

    private static void assertStrings(List<String> values, int size, String context) {
        assertThat(values).as(context).isNotNull().hasSize(size)
                .allSatisfy(value -> assertThat(value).isNotBlank());
    }

    private static Incident expectedIncident(String id) {
        if (id.startsWith("G")) {
            GlobalRisk risk = CORPUS.globalRisks().get(id);
            assertThat(risk).as("Unknown global risk %s", id).isNotNull();
            return new Incident(risk.titulo(), risk.severidad());
        }
        assertThat(id).matches("R(0[1-9]|1[0-9]):(MISSING|RISK)");
        Rule rule = CORPUS.rules().get(id.substring(0, 3));
        assertThat(rule).as("Unknown rule %s", id).isNotNull();
        return id.endsWith(":RISK")
                ? new Incident(RISK_PREFIX + rule.titulo(), "ALTA")
                : new Incident(rule.titulo(), rule.severidad());
    }

    private static Corpus loadCorpus() {
        try (InputStream input = MotorAnalisisLegalRegressionTest.class
                .getResourceAsStream("/motor/current-engine-v1.json")) {
            assertThat(input).as("Versioned motor corpus must exist").isNotNull();
            Corpus corpus = new ObjectMapper().readValue(input, Corpus.class);
            assertThat(corpus.schemaVersion()).isEqualTo(1);
            assertThat(corpus.baselineCommit()).isNotBlank();
            assertThat(corpus.purpose()).isNotBlank();
            assertThat(corpus.rules()).hasSize(19);
            assertThat(corpus.globalRisks()).hasSize(5);
            assertThat(corpus.cases()).hasSizeGreaterThanOrEqualTo(12);
            assertThat(corpus.cases()).extracting(CorpusCase::id).doesNotHaveDuplicates();
            assertThat(corpus.cases()).extracting(CorpusCase::categoria).contains(
                    "VALIDO", "INCOMPLETO", "AMBIGUO", "NEGACION", "FALSO_POSITIVO", "FALSO_NEGATIVO", "VACIO");
            return corpus;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read the versioned motor corpus", exception);
        }
    }

    record Corpus(int schemaVersion, String baselineCommit, String purpose,
                  Map<String, Rule> rules, Map<String, GlobalRisk> globalRisks, List<CorpusCase> cases) {}
    record Rule(String titulo, String severidad, int peso, String campoFaltante) {}
    record GlobalRisk(String titulo, String severidad, int penalizacion) {}
    record Structure(int riesgos, int recomendaciones, int textosSugeridos, int referenciasLegales, int faltantes) {}
    record Incident(String titulo, String severidad) {}
    record CorpusCase(String id, String categoria, String descripcion, String tipoDocumento, String texto,
                      Integer expectedScore, String expectedSummaryMarker, List<String> expectedIncidents,
                      Structure expectedStructure, String notas, Boolean knownLimitation, String limitation) {
        @Override public String toString() {
            return id + " [" + categoria + ", knownLimitation=" + knownLimitation + "]";
        }
    }
}
