package com.smartaudits.service.motor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartaudits.model.dto.ResultadoAuditoria;
import com.smartaudits.model.dto.ResultadoAuditoria.ControlPuntuado;
import com.smartaudits.model.dto.ResultadoAuditoria.DesglosePuntuacion;
import com.smartaudits.model.dto.ResultadoAuditoria.MetodoControl;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The score breakdown ("desglose-1"). Expected weights are computed by hand from the rule table (rules v5):
 * R01 8, R02 6, R03 4, R04 8, R05 8, R06 8, R07 5, R08 5, R09 4, R10 7, R11 4, R12 6, R13 3, R14 3, R15 4, R16 7,
 * R17 4, R18 3, R19 3, all contextual (100 points, none keyword-based). Rules v4 split them 84 / 16, rules v3 53 / 47.
 */
class DesglosePuntuacionTest {

    private final MotorAnalisisLegal motor = new MotorAnalisisLegal();

    static final String AVISO = "Aviso legal. En cumplimiento del artículo 10 de la Ley 34/2002 (LSSI-CE): Ejemplo S.L., "
            + "NIF B12345678, domicilio social en calle Mayor 1, 28013 Madrid, inscrita en el Registro Mercantil de Madrid, "
            + "tomo 1, folio 2. Contacto: info@example.invalid, teléfono 912 345 678.";
    static final String COOKIES = "Política de cookies. Usamos cookies técnicas, necesarias para el funcionamiento, y cookies "
            + "analíticas de terceros para medir las visitas. Las técnicas son de sesión; las analíticas duran un año. Puede "
            + "aceptar, rechazar o configurar las cookies desde el panel, y retirar el consentimiento en cualquier momento. "
            + "El consentimiento se presta pulsando el botón Aceptar.";
    static final String TERMINOS = "Términos y condiciones. El usuario se compromete a usar el servicio conforme a la ley. "
            + "Los precios incluyen IVA. El contrato se rige por la ley española.";

    private DesglosePuntuacion desglose(String texto, String tipo) {
        ResultadoAuditoria r = motor.analyze(new EntradaAnalisis(texto, tipo));
        DesglosePuntuacion d = r.getDesglosePuntuacion();
        assertThat(d).isNotNull();
        assertThat(d.puntuacion()).isEqualTo(r.getPuntuacionRiesgo());
        return d;
    }

    @Test
    void theBreakdownAlwaysAddsUpToTheUnchangedScore() throws Exception {
        List<String[]> textos = new ArrayList<>(List.of(new String[]{AVISO, "Aviso Legal"},
                new String[]{COOKIES, "Política de Cookies"}, new String[]{TERMINOS, "Términos y Condiciones"}));
        try (InputStream input = getClass().getResourceAsStream("/motor/current-engine-v6.json")) {
            for (JsonNode c : new ObjectMapper().readTree(input).path("cases")) {
                if (!c.path("texto").asText().isBlank()) {
                    textos.add(new String[]{c.path("texto").asText(), c.path("tipoDocumento").asText()});
                    // The breakdown reports the very score the corpus pins for rules v6.
                    assertThat(motor.analyze(new EntradaAnalisis(c.path("texto").asText(), c.path("tipoDocumento").asText()))
                            .getPuntuacionRiesgo()).as(c.path("id").asText()).isEqualTo(c.path("expectedScore").asInt());
                }
            }
        }
        for (String[] t : textos) {
            var d = desglose(t[0], t[1]);
            assertThat(d.version()).isEqualTo("desglose-1");
            assertThat(d.controles()).extracting(ControlPuntuado::ruleId)
                    .containsExactlyElementsOf(IntStream.rangeClosed(1, 19).mapToObj(i -> String.format("R%02d", i)).toList());
            assertThat(d.controles()).allSatisfy(c -> {
                assertThat(c.metodo()).isEqualTo(MetodoControl.CONTEXTUAL);
                assertThat(c.estado()).isNotNull();
                assertThat(c.puntos()).isBetween(0, c.peso());
            });
            assertThat(d.controles().stream().mapToInt(ControlPuntuado::peso).sum()).isEqualTo(100).isEqualTo(d.pesoTotal());
            assertThat(d.pesoNoAplicable() + d.pesoNoVerificable() + d.pesoContextualEvaluado() + d.pesoPalabrasClave())
                    .isEqualTo(100);
            assertThat(d.pesoPalabrasClave()).isZero();
            assertThat(d.puntosPalabrasClave()).isZero();
            assertThat(d.puntosContextuales()).isEqualTo(d.puntosAcreditados());
            assertThat(d.puntosAcreditados() - d.penalizacionRiesgos()).isEqualTo(d.puntuacion());
        }
    }

    @Test
    void aCorrectLegalNoticeShowsWhyItsScoreIsLowInsteadOfReadingAsCritical() {
        var d = desglose(AVISO, "Aviso Legal");
        // Only R02 and R16 apply to a legal notice; R01 8, R03 4, R04 8, R05 8, R06 8, R07 5, R08 5, R09 4,
        // R10 7, R11 4, R12 6, R13 3, R14 3, R15 4, R17 4, R18 3 and R19 3 do not (rules v4: 71, the last five
        // were keyword controls). Rules v3 gave 21: R01 earned 8 from «S.L.» as a keyword.
        assertThat(d.pesoNoAplicable()).isEqualTo(87);
        assertThat(d.pesoContextualEvaluado()).isEqualTo(13);
        assertThat(d.puntosContextuales()).isEqualTo(13);
        assertThat(d.porcentajeContextual()).isEqualTo(100);
        assertThat(d.puntosPalabrasClave()).isZero();
        assertThat(d.puntuacion()).isEqualTo(13);
        String resumen = motor.analyze(new EntradaAnalisis(AVISO, "Aviso Legal")).getResumen();
        assertThat(resumen).contains("87 de los 100 puntos corresponden a controles no aplicables")
                .contains("no es comparable con la de otros tipos de documento")
                .contains("no certifica el cumplimiento normativo ni prueba el comportamiento real del sitio web")
                .doesNotContain("incumplimientos críticos", "sanciones");
    }

    @Test
    void aCookiePolicyAndTermsAreSplitTheSameWay() {
        var cookies = desglose(COOKIES, "Política de Cookies");
        // R01, R02, R03, R04, R05, R06, R07, R08, R09, R11, R15 and R16: 8+6+4+8+8+8+5+5+4+4+4+7 = 71, plus
        // R13, R14, R17, R18 and R19: 3+3+4+3+3 = 16.
        assertThat(cookies.pesoNoAplicable()).isEqualTo(87);
        assertThat(cookies.pesoContextualEvaluado()).isEqualTo(13); // R10 + R12
        assertThat(cookies.porcentajeContextual()).isEqualTo(100);
        // Nothing contextual applies to terms: the share is unavailable, not an invented 0 % or 100 %.
        var terminos = desglose(TERMINOS, "Términos y Condiciones");
        assertThat(terminos.pesoNoAplicable()).isEqualTo(100);
        assertThat(terminos.pesoContextualEvaluado()).isZero();
        assertThat(terminos.porcentajeContextual()).isNull();
        assertThat(terminos.motivoSinPorcentaje()).isEqualTo(
                "Ningún control evaluado por contexto es aplicable y verificable en este documento.");
    }

    @Test
    void notVerifiablePartialAndMissingControlsAreKeptApart() {
        var d = desglose("Responsable del tratamiento: Ejemplo S.L. Contacto: privacidad@example.invalid.", "Política de Privacidad");
        // R03 4 + R09 4 + R10 7 + R12 6 + R15 4 + R13 3 + R14 3 + R17 4 + R18 3 + R19 3
        assertThat(d.pesoNoVerificable()).isEqualTo(41);
        assertThat(d.pesoNoAplicable()).isEqualTo(7); // R16: the provider's data belong to the legal notice
        // R01 8 + R02 6 + R04 8 + R05 8 + R06 8 + R07 5 + R08 5 + R11 4
        assertThat(d.pesoContextualEvaluado()).isEqualTo(52);
        assertThat(d.puntosContextuales()).isEqualTo(14); // R01 8 + R02 6
        assertThat(d.porcentajeContextual()).isEqualTo(27); // 14 / 52
        var parcial = desglose("Legitimación: la indicada en cada caso.", "Política de Privacidad");
        assertThat(parcial.controles()).filteredOn(c -> c.ruleId().equals("R05")).singleElement()
                .satisfies(c -> assertThat(c.puntos()).isEqualTo(4));
        // A complete privacy policy has nothing outside the 100 points to explain.
        String completo = motor.analyze(new EntradaAnalisis(MotorAnalisisLegalTest.COMPLETE_TEXT, "Documento Completo")).getResumen();
        assertThat(completo).doesNotContain("no es comparable").contains("no certifica el cumplimiento normativo");
    }

    @Test
    void globalRiskPenaltiesAreShownSeparately() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/motor/current-engine-v6.json")) {
            for (JsonNode c : new ObjectMapper().readTree(input).path("cases")) {
                if (c.path("id").asText().equals("M13")) {
                    var d = desglose(c.path("texto").asText(), c.path("tipoDocumento").asText());
                    // Five global risks at 2 points each on top of 83 accredited points (rules v5: 81, when R17 read
                    // human intervention as missing logic; rules v4: 85; rules v3: 89, before R16 stopped accepting
                    // «NIF de prueba»).
                    assertThat(d.penalizacionRiesgos()).isEqualTo(10);
                    assertThat(d.puntosAcreditados()).isEqualTo(83);
                    assertThat(d.puntuacion()).isEqualTo(73);
                }
            }
        }
    }

    @Test
    void historicalAndEmptyResultsHaveNoBreakdownAndNothingIsReconstructed() throws Exception {
        var mapper = new ObjectMapper();
        String historico = "{\"resumen\":\"Histórico\",\"puntuacionRiesgo\":37,\"errores\":[]}";
        var leido = mapper.readValue(historico, ResultadoAuditoria.class);
        assertThat(leido.getDesglosePuntuacion()).isNull();
        assertThat(mapper.writeValueAsString(leido)).doesNotContain("desglosePuntuacion");
        assertThat(motor.analyze(new EntradaAnalisis("  ", null)).getDesglosePuntuacion()).isNull();
        var nuevo = motor.analyze(new EntradaAnalisis(AVISO, "Aviso Legal"));
        assertThat(mapper.readValue(mapper.writeValueAsString(nuevo), ResultadoAuditoria.class)).isEqualTo(nuevo);
        assertThat(motor.version()).isEqualTo(new VersionAnalizador("3", "6"));
    }
}
