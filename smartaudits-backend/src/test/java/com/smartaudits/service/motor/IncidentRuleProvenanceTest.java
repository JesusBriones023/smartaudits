package com.smartaudits.service.motor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartaudits.model.dto.ResultadoAuditoria;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentRuleProvenanceTest {
    private final MotorAnalisisLegal motor = new MotorAnalisisLegal();

    @Test
    void everyMissingRuleRetainsItsOriginalIdAndTitle() throws Exception {
        var expected = new LinkedHashMap<String, String>();
        try (var input = getClass().getResourceAsStream("/motor/current-engine-v1.json")) {
            new ObjectMapper().readTree(input).path("rules").fields()
                    .forEachRemaining(rule -> expected.put(rule.getKey(), rule.getValue().path("titulo").asText()));
        }
        // Rules v2/v3: missing DPO, cookie, consent and security texts are not verifiable (no finding),
        // and a legal notice is not asked for privacy information. Rules v4 add transfers (never assumed)
        // and the legal notice (LSSI-CE applicability unknown) to them, and rules v5 minors, special
        // categories, automated decisions, breaches and marketing (never presumed); a complete document shows
        // the other 8 with their v1 titles.
        List.of("R03", "R09", "R10", "R12", "R13", "R14", "R15", "R16", "R17", "R18", "R19").forEach(expected::remove);
        var result = motor.analyze(new EntradaAnalisis("zzz", "Documento Completo"));
        Map<String, String> actual = new LinkedHashMap<>();
        assertThat(result.getErrores()).hasSize(8).allSatisfy(error -> {
            assertThat(actual.put(error.getRuleId(), error.getTitulo())).isNull();
            assertThat(error.getMotor()).isEqualTo("LEGAL_TEXT");
            assertThat(error.getVersion()).isEqualTo(motor.version().versionReglas());
        });
        assertThat(actual).containsExactlyEntriesOf(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "G01, cederemos sus datos, Cláusula abusiva de cesión de datos",
            "G02, conservaremos sus datos indefinidamente, Conservación de datos de forma indefinida",
            "G03, continuar navegando implica, Consentimiento tácito o por defecto",
            "G04, no podemos garantizar la seguridad, Exclusión de responsabilidad en seguridad",
            "G05, fingerprinting, Tecnologías de seguimiento invasivo sin información"})
    void globalRiskHasStableIdentity(String id, String text, String title) {
        assertThat(motor.analyze(new EntradaAnalisis(text, "Aviso Legal")).getErrores())
                .filteredOn(error -> error.getRuleId().startsWith("G"))
                .singleElement().satisfies(error -> {
                    assertThat(error.getRuleId()).isEqualTo(id);
                    assertThat(error.getTitulo()).isEqualTo(title);
                    assertThat(error.getMotor()).isEqualTo("LEGAL_TEXT");
                    assertThat(error.getVersion()).isEqualTo(motor.version().versionReglas());
                });
    }

    @Test
    void problematicRetentionKeepsR07AndAlsoIdentifiesIndependentGlobalRisk() {
        var result = motor.analyze(new EntradaAnalisis("Plazo de conservación: indefinidamente", "Aviso Legal"));
        assertThat(result.getErrores()).filteredOn(error -> "R07".equals(error.getRuleId()))
                .singleElement().satisfies(error -> assertThat(error.getTitulo())
                        .startsWith("Cláusula presente pero con redacción problemática: "));
        assertThat(result.getErrores()).extracting(ResultadoAuditoria.ErrorAuditoria::getRuleId).contains("G02");
    }

    @Test
    void rulesVersionSixWithOutputContractFour() {
        // Output 3 added the score breakdown and output 4 the evaluation coverage; rules 4 and 5 moved the remaining
        // rules to the contextual evaluation and rules 6 recalibrated them. Stored audits keep their own versions.
        assertThat(motor.version()).isEqualTo(new VersionAnalizador("4", "6"));
    }

    @ParameterizedTest
    @CsvSource({
            "R10, instalamos cookies sin su consentimiento",
            // Rules v6: «no verificamos la edad» alone is no longer a possible incident.
            "R13, Los menores de 14 años pueden registrarse sin el consentimiento de sus padres",
            "R19, no puede darse de baja de nuestras comunicaciones"})
    void problematicClauseRetainsItsOriginalRuleId(String ruleId, String text) {
        assertThat(motor.analyze(new EntradaAnalisis(text, "Aviso Legal")).getErrores())
                .filteredOn(error -> ruleId.equals(error.getRuleId()))
                .singleElement().satisfies(error -> {
                    assertThat(error.getTitulo()).startsWith("Cláusula presente pero con redacción problemática: ");
                    assertThat(error.getMotor()).isEqualTo("LEGAL_TEXT");
                    assertThat(error.getVersion()).isEqualTo(motor.version().versionReglas());
                });
    }

    @Test
    void everyFindingUsesTheRulesVersionExposedByTheAnalyzer() {
        var alternativeVersion = new VersionAnalizador("test-engine", "test-rules");
        MotorAnalisisLegal versioned = new MotorAnalisisLegal() {
            @Override public VersionAnalizador version() { return alternativeVersion; }
        };
        var result = versioned.analyze(new EntradaAnalisis("Plazo de conservación: indefinidamente", "Aviso Legal"));
        assertThat(result.getErrores()).isNotEmpty().allSatisfy(error -> {
            assertThat(error.getVersion()).isEqualTo(alternativeVersion.versionReglas());
            assertThat(error.getMotor()).isEqualTo("LEGAL_TEXT");
        });
    }

    @Test
    void defensiveEmptyInputDoesNotInventAnIncidentOrRule() {
        assertThat(motor.analyze(new EntradaAnalisis(null, null)).getErrores()).isEmpty();
        assertThat(motor.analyze(new EntradaAnalisis("  ", null)).getErrores()).isEmpty();
    }

    @Test
    void historicalJsonWithoutProvenanceStillDeserializesWithoutInventingMetadata() throws Exception {
        String historical = """
                {"resumen":"Histórico","puntuacionRiesgo":37,"errores":[
                  {"titulo":"Categoría histórica","descripcion":"Descripción","severidad":"MEDIA",
                   "evidencia":"Evidencia","impacto":"Impacto","accion":"Acción"}]}
                """;
        var result = new ObjectMapper().readValue(historical, ResultadoAuditoria.class);
        assertThat(result.getPuntuacionRiesgo()).isEqualTo(37);
        assertThat(result.getErrores()).singleElement().satisfies(error -> {
            assertThat(error.getTitulo()).isEqualTo("Categoría histórica");
            assertThat(error.getRuleId()).isNull();
            assertThat(error.getMotor()).isNull();
            assertThat(error.getVersion()).isNull();
        });
    }
}
