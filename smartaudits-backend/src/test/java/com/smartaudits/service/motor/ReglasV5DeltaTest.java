package com.smartaudits.service.motor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Before/after of rules v5 (R13, R14, R17, R18, R19) on the same texts as v1 to v4, plus the batch's own cases.
 * The expectations below were written by hand from the rule design before the v5 corpus existed;
 * MotorAnalisisLegalRegressionTest then pins the engine to that corpus.
 */
class ReglasV5DeltaTest {

    private static final List<String> LOTE = List.of("R13", "R14", "R17", "R18", "R19");

    /**
     * v4 score → v5 score; every other shared case keeps its score. The complete example (M01, M08, M13, M14) states
     * special categories with safeguards but no Art. 9.2 exception (R14 INSUFICIENTE, 1 of 3) and automated decisions
     * «sujetas a intervención humana» without their logic or consequences (R17 INSUFICIENTE, 2 of 4).
     */
    private static final Map<String, List<Integer>> PUNTUACIONES = Map.of(
            "M01", List.of(96, 92), "M08", List.of(88, 84), "M13", List.of(75, 71), "M14", List.of(94, 90));
    private static final Map<String, String> COMPLETO = Map.of("R13", "SUFICIENTE", "R14", "INSUFICIENTE",
            "R17", "INSUFICIENTE", "R18", "SUFICIENTE", "R19", "SUFICIENTE");
    /** M08 drops from 88 to 84, below the 85 boundary. */
    private static final Map<String, String> MARCADORES = Map.of("M08", "RIESGO MODERADO");

    /** Cases of the batch: score observed with the rules v4 engine (44f6fad) → v5 expectation. */
    private static final Map<String, List<Integer>> NUEVOS = Map.of(
            "M21", List.of(16, 0),   // misleading words earned 16 keyword points; nothing is processing
            "M22", List.of(21, 21),  // adequate statements: same points, now from evidence
            "M23", List.of(7, 3),    // five problematic statements: possible incidents without points
            "M24", List.of(13, 6),   // five mere mentions: half of each weight
            "M25", List.of(10, 12),  // negations: profiling denial now counts, the conditional is no risk
            "M26", List.of(7, 7),    // cookie policy: the five are not applicable instead of five findings
            "M27", List.of(6, 4));   // markup read as text: one negation and one mention
    private static final Set<String> CATEGORIAS_NUEVAS = Set.of("FALSO_POSITIVO", "VALIDO", "RIESGO_EXPLICITO", "AMBIGUO",
            "NEGACION", "NO_APLICABLE", "HTML_HOSTIL");

    @Test
    void onlyTheFourthBatchChangesAndOnlyAsStated() throws IOException {
        Map<String, JsonNode> v4 = casos("/motor/current-engine-v4.json");
        Map<String, JsonNode> v5 = casos("/motor/current-engine-v5.json");
        List<String> esperados = new ArrayList<>(v4.keySet());
        esperados.addAll(NUEVOS.keySet().stream().sorted().toList());
        assertThat(v5.keySet()).containsExactlyElementsOf(esperados);

        for (String id : v4.keySet()) {
            JsonNode antes = v4.get(id);
            JsonNode despues = v5.get(id);
            String tipo = antes.path("tipoDocumento").asText();
            assertThat(despues.path("texto").asText()).as(id).isEqualTo(antes.path("texto").asText());
            assertThat(despues.path("tipoDocumento").asText()).as(id).isEqualTo(tipo);

            int puntos = antes.path("expectedScore").asInt();
            List<Integer> esperado = PUNTUACIONES.getOrDefault(id, List.of(puntos, puntos));
            assertThat(puntos).as(id + " v4").isEqualTo(esperado.get(0));
            assertThat(despues.path("expectedScore").asInt()).as(id + " v5").isEqualTo(esperado.get(1));
            assertThat(despues.path("expectedSummaryMarker").asText()).as(id)
                    .isEqualTo(MARCADORES.getOrDefault(id, antes.path("expectedSummaryMarker").asText()));

            // The keyword findings of the batch disappear: absent subjects are never presumed.
            List<String> incidencias = new ArrayList<>(textos(antes.path("expectedIncidents")));
            List<String> retiradas = incidencias.stream().filter(i -> LOTE.contains(i.split(":")[0])).toList();
            assertThat(retiradas).as(id).allSatisfy(i -> assertThat(i).endsWith(":MISSING"));
            incidencias.removeAll(retiradas);
            String ausente = tipo.equals("Aviso Legal") ? "NO_APLICABLE" : "NO_VERIFICABLE";
            if (PUNTUACIONES.containsKey(id)) {
                incidencias.add("R14:INSUFFICIENT");
                incidencias.add("R17:INSUFFICIENT");
            } else {
                retiradas.forEach(i -> assertThat(despues.path("expectedEvaluations").path(i.substring(0, 3)).asText())
                        .as(id + " " + i).isEqualTo(ausente));
            }
            assertThat(textos(despues.path("expectedIncidents"))).as(id).containsExactlyInAnyOrderElementsOf(incidencias);

            // Rules of the earlier batches keep their v4 states; the batch rules are now evaluated too.
            JsonNode evaluaciones = antes.path("expectedEvaluations");
            evaluaciones.fieldNames().forEachRemaining(regla -> assertThat(despues.path("expectedEvaluations").path(regla).asText())
                    .as(id + " " + regla).isEqualTo(evaluaciones.path(regla).asText()));
            if (!antes.path("texto").asText().isBlank()) {
                Map<String, String> lote = new LinkedHashMap<>();
                LOTE.forEach(regla -> lote.put(regla, despues.path("expectedEvaluations").path(regla).asText()));
                Map<String, String> estados = new LinkedHashMap<>();
                LOTE.forEach(regla -> estados.put(regla, PUNTUACIONES.containsKey(id) ? COMPLETO.get(regla) : ausente));
                assertThat(lote).as(id).isEqualTo(estados);
            }

            assertThat(antes.path("knownLimitation").asBoolean()).as(id).isFalse();
            assertThat(despues.path("knownLimitation").asBoolean()).as(id).isFalse();
        }

        Set<String> categorias = new java.util.HashSet<>();
        NUEVOS.forEach((id, puntos) -> {
            JsonNode caso = v5.get(id);
            assertThat(caso.path("expectedScore").asInt()).as(id + " v5").isEqualTo(puntos.get(1));
            // The v4 score observed for the same text is recorded in the case notes.
            assertThat(caso.path("notas").asText()).as(id).startsWith("Reglas v4: " + puntos.get(0) + " ");
            assertThat(caso.path("knownLimitation").asBoolean()).as(id).isFalse();
            LOTE.forEach(regla -> assertThat(caso.path("expectedEvaluations").has(regla)).as(id + " " + regla).isTrue());
            categorias.add(caso.path("categoria").asText());
        });
        assertThat(categorias).isEqualTo(CATEGORIAS_NUEVAS);
    }

    @Test
    void theEarlierCorporaAreKeptAsTheyWere() throws IOException {
        // Each version keeps its own schema and cases; v5 adds M21-M27 on top of v4.
        int[] casos = {14, 14, 14, 20, 27};
        for (int v = 1; v <= 5; v++) {
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
        try (InputStream input = ReglasV5DeltaTest.class.getResourceAsStream(recurso)) {
            assertThat(input).as(recurso).isNotNull();
            Map<String, JsonNode> casos = new LinkedHashMap<>();
            new ObjectMapper().readTree(input).path("cases").forEach(c -> casos.put(c.path("id").asText(), c));
            return casos;
        }
    }
}
