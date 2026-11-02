package com.smartaudits.service.motor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartaudits.model.dto.ResultadoAuditoria;
import com.smartaudits.model.dto.ResultadoAuditoria.CoberturaEvaluacion;
import com.smartaudits.model.dto.ResultadoAuditoria.ControlPuntuado;
import com.smartaudits.model.dto.ResultadoAuditoria.EstadoEvidencia;
import com.smartaudits.model.dto.ResultadoAuditoria.MetodoControl;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static com.smartaudits.model.dto.ResultadoAuditoria.EstadoEvidencia.INSUFICIENTE;
import static com.smartaudits.model.dto.ResultadoAuditoria.EstadoEvidencia.NO_APLICABLE;
import static com.smartaudits.model.dto.ResultadoAuditoria.EstadoEvidencia.NO_VERIFICABLE;
import static com.smartaudits.model.dto.ResultadoAuditoria.EstadoEvidencia.POSIBLE_INCIDENCIA;
import static com.smartaudits.model.dto.ResultadoAuditoria.EstadoEvidencia.SUFICIENTE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The evaluation coverage ("cobertura-1"). Expected values are computed by hand from the rule weights
 * (R01 8, R02 6, R03 4, R04 8, R05 8, R06 8, R07 5, R08 5, R09 4, R10 7, R11 4, R12 6, R13 3, R14 3, R15 4, R16 7,
 * R17 4, R18 3, R19 3) or, for the corpus, from the checked-in JSON only.
 */
class CoberturaEvaluacionTest {

    private static final int[] PESOS = {8, 6, 4, 8, 8, 8, 5, 5, 4, 7, 4, 6, 3, 3, 4, 7, 4, 3, 3};
    private final MotorAnalisisLegal motor = new MotorAnalisisLegal();

    private static List<ControlPuntuado> todos(EstadoEvidencia estado) {
        return IntStream.range(0, 19).mapToObj(i -> new ControlPuntuado(String.format("R%02d", i + 1), PESOS[i],
                estado == SUFICIENTE ? PESOS[i] : 0, MetodoControl.CONTEXTUAL, estado)).toList();
    }

    private CoberturaEvaluacion cobertura(String texto, String tipo) {
        var c = motor.analyze(new EntradaAnalisis(texto, tipo)).getCoberturaEvaluacion();
        assertThat(c).isNotNull();
        assertThat(c.version()).isEqualTo("cobertura-1");
        assertThat(c.coberturaMinima()).isEqualTo(50);
        return c;
    }

    // ---- Zero denominators and extremes, with synthetic controls -------------------------------------

    @Test
    void everythingNotVerifiableGivesNoCoverageAndNoResult() {
        var c = MotorAnalisisLegal.cobertura(0, todos(NO_VERIFICABLE), Set.of(), 0);
        assertThat(c.pesoAplicable()).isEqualTo(100);
        assertThat(c.pesoEvaluado()).isZero();
        assertThat(c.porcentajeCobertura()).isZero();
        assertThat(c.resultadoControlesEvaluados()).isNull();
        assertThat(c.motivoSinResultado()).isEqualTo("El texto no permite evaluar ninguno de los controles aplicables.");
        assertThat(c.controlesNoVerificables()).hasSize(19);
    }

    @Test
    void everythingNotApplicableHasNoDenominatorAtAll() {
        var c = MotorAnalisisLegal.cobertura(0, todos(NO_APLICABLE), Set.of(), 0);
        assertThat(c.pesoAplicable()).isZero();
        assertThat(c.porcentajeCobertura()).isNull();
        assertThat(c.resultadoControlesEvaluados()).isNull();
        assertThat(c.motivoSinResultado()).isEqualTo("Ningún control es aplicable a este tipo de documento.");
        assertThat(c.noAplicables()).isEqualTo(19);
    }

    @Test
    void theResultNeedsHalfOfTheApplicableWeight() {
        // R01-R08 SUFICIENTE (8+6+4+8+8+8+5+5 = 52) and the rest NO_VERIFICABLE (48): 52 % coverage, result 100.
        List<ControlPuntuado> controles = new ArrayList<>(todos(NO_VERIFICABLE));
        for (int i = 0; i < 8; i++) controles.set(i, new ControlPuntuado(controles.get(i).ruleId(), PESOS[i], PESOS[i],
                MetodoControl.CONTEXTUAL, SUFICIENTE));
        var c = MotorAnalisisLegal.cobertura(52, controles, Set.of(), 0);
        assertThat(c.porcentajeCobertura()).isEqualTo(52);
        assertThat(c.resultadoControlesEvaluados()).isEqualTo(100);
        // Without R08 (5): 47 of 100, below the minimum, so a perfect-looking 100 is not given.
        controles.set(7, new ControlPuntuado("R08", 5, 0, MetodoControl.CONTEXTUAL, NO_VERIFICABLE));
        var baja = MotorAnalisisLegal.cobertura(47, controles, Set.of(), 0);
        assertThat(baja.porcentajeCobertura()).isEqualTo(47);
        assertThat(baja.resultadoControlesEvaluados()).isNull();
        assertThat(baja.motivoSinResultado()).isEqualTo("Se ha evaluado menos del 50 % del peso de los controles "
                + "aplicables: el texto no permite valorar el documento en su conjunto.");
    }

    @Test
    void roundingIsToTheNearestIntegerWithHalvesUp() {
        // 1 point over 8 evaluated = 12.5 → 13 (the 11.54 → 12 case is the problematic clause below).
        List<ControlPuntuado> controles = new ArrayList<>(todos(NO_APLICABLE));
        controles.set(0, new ControlPuntuado("R01", 8, 1, MetodoControl.CONTEXTUAL, INSUFICIENTE));
        var c = MotorAnalisisLegal.cobertura(1, controles, Set.of(), 0);
        assertThat(c.pesoAplicable()).isEqualTo(8);
        assertThat(c.resultadoControlesEvaluados()).isEqualTo(13);
    }

    // ---- Real analyses -------------------------------------------------------------------------------

    @Test
    void aVeryShortPrivacyPolicyIsMostlyMissingInformationNotFailedControls() {
        var c = cobertura("Bienvenido.", "Política de Privacidad");
        // R16 (7) does not apply; R03 4, R09 4, R10 7, R12 6, R13 3, R14 3, R15 4, R17 4, R18 3, R19 3 = 41 not verifiable.
        assertThat(c.pesoAplicable()).isEqualTo(93);
        assertThat(c.pesoEvaluado()).isEqualTo(52);
        assertThat(c.porcentajeCobertura()).isEqualTo(56); // 52 / 93 = 55.9
        assertThat(c.resultadoControlesEvaluados()).isZero();
        assertThat(c.incidenciasAusencia()).isEqualTo(8); // R01, R02, R04, R05, R06, R07, R08, R11
        assertThat(c.incidenciasRedaccion()).isZero();
        assertThat(c.controlesNoVerificables()).containsExactly("R03", "R09", "R10", "R12", "R13", "R14", "R15", "R17",
                "R18", "R19");
    }

    @Test
    void aCompleteDocumentIsFullyCoveredAndMatchesItsScore() {
        var c = cobertura(MotorAnalisisLegalTest.COMPLETE_TEXT, "Documento Completo");
        assertThat(c.porcentajeCobertura()).isEqualTo(100);
        assertThat(c.suficientes()).isEqualTo(19);
        assertThat(c.resultadoControlesEvaluados()).isEqualTo(100);
        assertThat(c.controlesNoVerificables()).isEmpty();
    }

    @Test
    void aProblematicClauseAndItsGlobalRiskAreKeptApartFromMissingInformation() {
        var r = motor.analyze(new EntradaAnalisis("Responsable del tratamiento: Ejemplo S.L. Vendemos sus datos a terceros.",
                "Política de Privacidad"));
        var c = r.getCoberturaEvaluacion();
        // R01 earns 8, R08 (sale of data) is a problematic clause, G01 takes 2: score 6 over 52 evaluated = 11.5 → 12.
        assertThat(r.getPuntuacionRiesgo()).isEqualTo(6);
        assertThat(c.suficientes()).isEqualTo(1);
        assertThat(c.incidenciasRedaccion()).isEqualTo(1);
        assertThat(c.incidenciasAusencia()).isEqualTo(6);
        assertThat(c.riesgosGlobales()).isEqualTo(1);
        assertThat(c.resultadoControlesEvaluados()).isEqualTo(12);
    }

    @Test
    void documentTypesOnlyCountWhatApplies() {
        String aviso = "Aviso legal. En cumplimiento del artículo 10 de la Ley 34/2002 (LSSI-CE): Ejemplo S.L., NIF B12345678, "
                + "domicilio social en calle Mayor 1, 28013 Madrid, inscrita en el Registro Mercantil de Madrid, tomo 1, "
                + "folio 2. Contacto: info@example.invalid, teléfono 912 345 678.";
        var c = cobertura(aviso, "Aviso Legal");
        // Only R02 (6) and R16 (7) apply: the legacy 13/100 is 100 % of what a legal notice must carry.
        assertThat(c.pesoAplicable()).isEqualTo(13);
        assertThat(c.porcentajeCobertura()).isEqualTo(100);
        assertThat(c.resultadoControlesEvaluados()).isEqualTo(100);
        assertThat(c.noAplicables()).isEqualTo(17);
        var terminos = cobertura("Términos y condiciones. Los precios incluyen IVA.", "Términos y Condiciones");
        assertThat(terminos.porcentajeCobertura()).isNull();
        assertThat(terminos.resultadoControlesEvaluados()).isNull();
        // An untyped document is read like a complete one.
        assertThat(cobertura("Bienvenido.", null).pesoAplicable()).isEqualTo(100);
    }

    @Test
    void theLegacyScoreAndItsBreakdownAreUntouched() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/motor/current-engine-v6.json")) {
            JsonNode corpus = new ObjectMapper().readTree(input);
            for (JsonNode caso : corpus.path("cases")) {
                String texto = caso.path("texto").asText();
                ResultadoAuditoria r = motor.analyze(new EntradaAnalisis(texto, caso.path("tipoDocumento").asText()));
                String id = caso.path("id").asText();
                if (texto.isBlank()) {
                    assertThat(r.getCoberturaEvaluacion()).as(id).isNull();
                    continue;
                }
                int score = caso.path("expectedScore").asInt();
                assertThat(r.getPuntuacionRiesgo()).as(id).isEqualTo(score);
                // Independent oracle: weights from the corpus rule table, states and incidents as checked in.
                int aplicable = 0, evaluado = 0;
                List<String> noVerificables = new ArrayList<>();
                var evaluaciones = caso.path("expectedEvaluations");
                for (var it = evaluaciones.fields(); it.hasNext(); ) {
                    var e = it.next();
                    int peso = corpus.path("rules").path(e.getKey()).path("peso").asInt();
                    String estado = e.getValue().asText();
                    if (estado.equals("NO_APLICABLE")) continue;
                    aplicable += peso;
                    if (estado.equals("NO_VERIFICABLE")) noVerificables.add(e.getKey());
                    else evaluado += peso;
                }
                List<String> incidencias = new ArrayList<>();
                caso.path("expectedIncidents").forEach(i -> incidencias.add(i.asText()));
                long redaccion = incidencias.stream().filter(i -> i.endsWith(":RISK")).count();
                long globales = incidencias.stream().filter(i -> i.startsWith("G")).count();
                long posibles = 0;
                for (var it = evaluaciones.elements(); it.hasNext(); ) if (it.next().asText().equals("POSIBLE_INCIDENCIA")) posibles++;
                Integer cobertura = aplicable == 0 ? null : (int) Math.floor(100.0 * evaluado / aplicable + 0.5);
                Integer resultado = cobertura == null || evaluado == 0 || cobertura < 50 ? null
                        : (int) Math.floor(100.0 * score / evaluado + 0.5);
                var c = r.getCoberturaEvaluacion();
                assertThat(c.pesoAplicable()).as(id).isEqualTo(aplicable);
                assertThat(c.pesoEvaluado()).as(id).isEqualTo(evaluado);
                assertThat(c.porcentajeCobertura()).as(id).isEqualTo(cobertura);
                assertThat(c.resultadoControlesEvaluados()).as(id).isEqualTo(resultado);
                assertThat(c.controlesNoVerificables()).as(id).containsExactlyElementsOf(noVerificables);
                assertThat(c.incidenciasRedaccion()).as(id).isEqualTo((int) redaccion);
                assertThat(c.incidenciasAusencia()).as(id).isEqualTo((int) (posibles - redaccion));
                assertThat(c.riesgosGlobales()).as(id).isEqualTo((int) globales);
                // The breakdown keeps its meaning: the evaluated weight is its contextual evaluated weight.
                assertThat(c.pesoEvaluado()).as(id).isEqualTo(r.getDesglosePuntuacion().pesoContextualEvaluado());
            }
        }
    }

    @Test
    void storedResultsWithoutCoverageStayAsTheyWereAndNewOnesRoundTrip() throws Exception {
        var mapper = new ObjectMapper();
        String historico = "{\"resumen\":\"Histórico\",\"puntuacionRiesgo\":37,\"errores\":[]}";
        var leido = mapper.readValue(historico, ResultadoAuditoria.class);
        assertThat(leido.getCoberturaEvaluacion()).isNull();
        assertThat(mapper.writeValueAsString(leido)).doesNotContain("coberturaEvaluacion");
        assertThat(motor.analyze(new EntradaAnalisis("  ", null)).getCoberturaEvaluacion()).isNull();
        var nuevo = motor.analyze(new EntradaAnalisis("Bienvenido.", "Política de Privacidad"));
        var json = mapper.readTree(mapper.writeValueAsString(nuevo)).path("coberturaEvaluacion");
        assertThat(json.path("version").asText()).isEqualTo("cobertura-1");
        assertThat(json.path("porcentajeCobertura").asInt()).isEqualTo(56);
        assertThat(mapper.readValue(mapper.writeValueAsString(nuevo), ResultadoAuditoria.class)).isEqualTo(nuevo);
        // The rules keep version 6; the output contract moves to 4 with this field.
        assertThat(motor.version()).isEqualTo(new VersionAnalizador("4", "6"));
    }
}
