package com.smartaudits.service.motor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartaudits.model.dto.ResultadoAuditoria;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** The frontend consumes the same fixed examples; no production configuration is shared. */
class RiskClassificationContractTest {
    private final AnalizadorLegal motor = new MotorAnalisisLegal();

    static Stream<Boundary> boundaries() throws IOException {
        try (InputStream input = RiskClassificationContractTest.class
                .getResourceAsStream("/motor/risk-boundaries.json")) {
            assertThat(input).isNotNull();
            Boundary[] cases = new ObjectMapper().readValue(input, Boundary[].class);
            assertThat(cases).extracting(Boundary::score).containsExactly(0, 39, 40, 64, 65, 84, 85, 100);
            return Arrays.stream(cases);
        }
    }

    @ParameterizedTest(name = "score {0}")
    @MethodSource("boundaries")
    void realAnalysisPreservesScoreAndRiskLabelAtEveryBoundary(Boundary example) {
        ResultadoAuditoria result = motor.analyze(new EntradaAnalisis(example.text(), "Documento Completo"));

        assertThat(result.getPuntuacionRiesgo()).isEqualTo(example.score());
        assertThat(result.getResumen()).contains(
                "Nivel de riesgo: " + example.riskLabel() + " (puntuación: " + example.score() + "/100).");
        assertThat(motor.version()).isEqualTo(new VersionAnalizador("2", "1"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\r\n"})
    void absentTextRetainsItsSpecialResultInsteadOfAClassification(String text) {
        ResultadoAuditoria result = motor.analyze(new EntradaAnalisis(text, null));

        assertThat(result.getPuntuacionRiesgo()).isZero();
        assertThat(result.getResumen()).isEqualTo(
                "No se ha podido analizar el documento: el texto proporcionado está vacío.");
        assertThat(result.getErrores()).isEmpty();
    }

    record Boundary(int score, String text, String riskLabel) {
        @Override public String toString() { return Integer.toString(score); }
    }
}
