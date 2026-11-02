package com.smartaudits.service.motor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Before/after of rules v6 (legal calibration of R13, R14, R17, R18 and R19 and of the legal wording) on the same texts
 * as v1 to v5, plus the batch's own cases. The expectations below were written by hand before the v6 corpus existed;
 * MotorAnalisisLegalRegressionTest then pins the engine to that corpus.
 */
class ReglasV6DeltaTest {

    private static final String[] ESTRUCTURA = {"riesgos", "recomendaciones", "textosSugeridos", "referenciasLegales",
            "faltantes"};

    /**
     * v5 score → v6 score; every other shared case keeps its score. The complete example (M01, M08, M13, M14) says
     * «Las decisiones automatizadas están sujetas a intervención humana»: human involvement, so no Art. 22 decision and
     * R17 is SUFICIENTE again (+2). M24 says «También tratamos datos de menores» without a consent basis: R13 is
     * NO_VERIFICABLE (-1).
     */
    private static final Map<String, List<Integer>> PUNTUACIONES = Map.of("M01", List.of(92, 94), "M08", List.of(84, 86),
            "M13", List.of(71, 73), "M14", List.of(90, 92), "M24", List.of(6, 5));
    private static final Map<String, String> CAMBIO_DE_ESTADO = Map.of("M01", "R17", "M08", "R17", "M13", "R17",
            "M14", "R17", "M24", "R13");
    /** M08 goes back above the 85 boundary. */
    private static final Map<String, String> MARCADORES = Map.of("M08", "BAJO RIESGO");

    /** Cases of the batch: score observed with the rules v5 engine (b5413ee) → v6 expectation. */
    private static final Map<String, List<Integer>> NUEVOS = Map.of(
            "M28", List.of(18, 16),  // one Art. 9.2 exception no longer covers a second special-category processing
            "M29", List.of(2, 4),    // ordinary profiling needs a purpose, not Art. 22 information
            "M30", List.of(4, 4),    // significant automated decision with logic, consequences and safeguards
            "M31", List.of(5, 3),    // high-risk threshold applied to the authority: possible incident with information
            "M32", List.of(3, 1),    // legitimate interest alone for e-mail marketing
            "M33", List.of(3, 3),    // existing customers, similar products (Art. 21.2 LSSI-CE)
            "M34", List.of(0, 1));   // not checking ages is no breach by itself

    @Test
    void onlyTheCalibratedDecisionsAndTheDefaultReferencesChange() throws IOException {
        Map<String, JsonNode> v5 = casos("/motor/current-engine-v5.json");
        Map<String, JsonNode> v6 = casos("/motor/current-engine-v6.json");
        List<String> esperados = new ArrayList<>(v5.keySet());
        esperados.addAll(NUEVOS.keySet().stream().sorted().toList());
        assertThat(v6.keySet()).containsExactlyElementsOf(esperados);

        for (String id : v5.keySet()) {
            JsonNode antes = v5.get(id);
            JsonNode despues = v6.get(id);
            assertThat(despues.path("texto").asText()).as(id).isEqualTo(antes.path("texto").asText());
            assertThat(despues.path("tipoDocumento").asText()).as(id).isEqualTo(antes.path("tipoDocumento").asText());
            int puntos = antes.path("expectedScore").asInt();
            List<Integer> esperado = PUNTUACIONES.getOrDefault(id, List.of(puntos, puntos));
            assertThat(puntos).as(id + " v5").isEqualTo(esperado.get(0));
            assertThat(despues.path("expectedScore").asInt()).as(id + " v6").isEqualTo(esperado.get(1));
            assertThat(despues.path("expectedSummaryMarker").asText()).as(id)
                    .isEqualTo(MARCADORES.getOrDefault(id, antes.path("expectedSummaryMarker").asText()));

            // The only state that changes is the one stated; every other rule keeps its v5 state.
            String regla = CAMBIO_DE_ESTADO.get(id);
            antes.path("expectedEvaluations").fieldNames().forEachRemaining(r -> {
                String estado = despues.path("expectedEvaluations").path(r).asText();
                if (r.equals(regla)) {
                    assertThat(estado).as(id + " " + r).isEqualTo(r.equals("R17") ? "SUFICIENTE" : "NO_VERIFICABLE");
                } else {
                    assertThat(estado).as(id + " " + r).isEqualTo(antes.path("expectedEvaluations").path(r).asText());
                }
            });
            List<String> incidencias = textos(antes.path("expectedIncidents"));
            if (regla != null) incidencias.remove(regla + ":INSUFFICIENT");
            assertThat(textos(despues.path("expectedIncidents"))).as(id).containsExactlyInAnyOrderElementsOf(incidencias);

            // Lists: the default reference to Law 10/2025 disappears from every analysed text; an INSUFICIENTE that
            // becomes SUFICIENTE also drops its riesgo, recomendación, texto, referencia and faltante, and one that
            // becomes NO_VERIFICABLE drops its riesgo and referencia.
            boolean vacio = antes.path("texto").asText().isBlank();
            for (String campo : ESTRUCTURA) {
                int delta = 0;
                if (!vacio && campo.equals("referenciasLegales")) delta--;
                if (regla != null && regla.equals("R17")) delta--;
                if (regla != null && regla.equals("R13") && (campo.equals("riesgos") || campo.equals("referenciasLegales"))) delta--;
                assertThat(despues.path("expectedStructure").path(campo).asInt()).as(id + " " + campo)
                        .isEqualTo(antes.path("expectedStructure").path(campo).asInt() + delta);
            }
            assertThat(despues.path("knownLimitation").asBoolean()).as(id).isFalse();
        }

        Set<String> categorias = new HashSet<>();
        NUEVOS.forEach((id, puntos) -> {
            JsonNode caso = v6.get(id);
            assertThat(caso.path("expectedScore").asInt()).as(id + " v6").isEqualTo(puntos.get(1));
            assertThat(caso.path("notas").asText()).as(id).startsWith("Reglas v5: " + puntos.get(0) + " ");
            assertThat(caso.path("knownLimitation").asBoolean()).as(id).isFalse();
            categorias.add(caso.path("categoria").asText());
        });
        assertThat(categorias).containsExactlyInAnyOrder("MULTIPLES_TRATAMIENTOS", "PERFILADO_ORDINARIO",
                "DECISION_SIGNIFICATIVA", "RIESGO_EXPLICITO", "INTERES_LEGITIMO", "CLIENTES_21_2", "MENORES");
    }

    @Test
    void theEarlierCorporaAreKeptAsTheyWere() throws IOException {
        int[] casos = {14, 14, 14, 20, 27, 34};
        for (int v = 1; v <= 6; v++) {
            try (InputStream input = getClass().getResourceAsStream("/motor/current-engine-v" + v + ".json")) {
                JsonNode corpus = new ObjectMapper().readTree(input);
                assertThat(corpus.path("schemaVersion").asInt()).as("v" + v).isEqualTo(v);
                assertThat(corpus.path("cases").size()).as("v" + v).isEqualTo(casos[v - 1]);
            }
        }
    }

    private static List<String> textos(JsonNode array) {
        List<String> valores = new ArrayList<>();
        array.forEach(v -> valores.add(v.asText()));
        return valores;
    }

    private static Map<String, JsonNode> casos(String recurso) throws IOException {
        try (InputStream input = ReglasV6DeltaTest.class.getResourceAsStream(recurso)) {
            assertThat(input).as(recurso).isNotNull();
            Map<String, JsonNode> casos = new LinkedHashMap<>();
            new ObjectMapper().readTree(input).path("cases").forEach(c -> casos.put(c.path("id").asText(), c));
            return casos;
        }
    }
}
