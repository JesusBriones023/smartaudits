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
 * Before/after of rules v4 (R01, R04, R09, R11, R16) on the same texts as v1, v2 and v3, plus the batch's own
 * cases. The expectations below were written by hand from the rule design before the v4 corpus existed;
 * MotorAnalisisLegalRegressionTest then pins the engine to that corpus.
 */
class ReglasV4DeltaTest {

    private static final Set<String> LOTE = Set.of("R01", "R04", "R09", "R11", "R16");

    /**
     * v3 score → v4 score; every other shared case keeps its score. M01, M08, M13 and M14 give «NIF de prueba»,
     * which is no tax identifier: their legal notice is INSUFICIENTE, half of 7.
     */
    private static final Map<String, List<Integer>> PUNTUACIONES = Map.of(
            "M01", List.of(100, 96), "M08", List.of(92, 88), "M13", List.of(79, 75), "M14", List.of(98, 94));

    /**
     * In a complete document, absent transfers cannot be judged without knowing whether any happen, and absent
     * Art. 10 LSSI-CE data without knowing whether the site is an information society service: NO_VERIFICABLE.
     */
    private static final Map<String, String> AUSENCIA_NO_VERIFICABLE = Map.of("R09:MISSING", "R09", "R16:MISSING", "R16");
    private static final Set<String> NIF_DE_PRUEBA = Set.of("M01", "M08", "M13", "M14");

    /** Cases of the batch: score observed with the rules v3 engine (95c3019) → v4 expectation. */
    private static final Map<String, List<Integer>> NUEVOS = Map.of(
            "M15", List.of(11, 4),   // «cifrado» matched «cif»: R16 +7 vanishes
            "M16", List.of(21, 13),  // a complete legal notice: R01 no longer earns 8 from «S.L.» (not applicable)
            "M17", List.of(15, 3),   // name and NIF only: R16 INSUFICIENTE, R01 not applicable
            "M18", List.of(20, 12),  // generic R01, R04, R11 earned 8+8+4; now half each, R09 half
            "M19", List.of(31, 24),  // R16 earned 7 from «NIF» in a privacy policy; not applicable now
            "M20", List.of(8, 0));   // «cualquier finalidad» earned R04 8; now a possible incident

    @Test
    void onlyTheThirdBatchChangesAndOnlyAsStated() throws IOException {
        Map<String, JsonNode> v3 = casos("/motor/current-engine-v3.json");
        Map<String, JsonNode> v4 = casos("/motor/current-engine-v4.json");
        List<String> esperados = new ArrayList<>(v3.keySet());
        esperados.addAll(NUEVOS.keySet().stream().sorted().toList());
        assertThat(v4.keySet()).containsExactlyElementsOf(esperados);

        for (String id : v3.keySet()) {
            JsonNode antes = v3.get(id);
            JsonNode despues = v4.get(id);
            assertThat(despues.path("texto").asText()).as(id).isEqualTo(antes.path("texto").asText());
            assertThat(despues.path("tipoDocumento").asText()).as(id).isEqualTo(antes.path("tipoDocumento").asText());

            int puntos = antes.path("expectedScore").asInt();
            List<Integer> esperado = PUNTUACIONES.getOrDefault(id, List.of(puntos, puntos));
            assertThat(puntos).as(id + " v3").isEqualTo(esperado.get(0));
            assertThat(despues.path("expectedScore").asInt()).as(id + " v4").isEqualTo(esperado.get(1));

            List<String> incidencias = new ArrayList<>(textos(antes.path("expectedIncidents")));
            AUSENCIA_NO_VERIFICABLE.forEach((incidencia, regla) -> {
                if (incidencias.remove(incidencia)) {
                    assertThat(despues.path("expectedEvaluations").path(regla).asText()).as(id + " " + regla)
                            .isEqualTo("NO_VERIFICABLE");
                }
            });
            if (NIF_DE_PRUEBA.contains(id)) incidencias.add("R16:INSUFFICIENT");
            assertThat(textos(despues.path("expectedIncidents"))).as(id).containsExactlyInAnyOrderElementsOf(incidencias);

            List<String> cambiadas = new ArrayList<>(textos(antes.path("expectedIncidents")));
            cambiadas.removeAll(textos(despues.path("expectedIncidents")));
            if (NIF_DE_PRUEBA.contains(id)) cambiadas.add("R16:INSUFFICIENT");
            assertThat(cambiadas).as(id).allSatisfy(i -> assertThat(LOTE).contains(i.split(":")[0]));

            // Rules of the earlier batches keep their v3 states; the batch rules are now evaluated too.
            JsonNode evaluaciones = antes.path("expectedEvaluations");
            evaluaciones.fieldNames().forEachRemaining(regla -> assertThat(despues.path("expectedEvaluations").path(regla).asText())
                    .as(id + " " + regla).isEqualTo(evaluaciones.path(regla).asText()));
            if (!antes.path("texto").asText().isBlank()) {
                LOTE.forEach(regla -> assertThat(despues.path("expectedEvaluations").has(regla)).as(id + " " + regla).isTrue());
            }
            if (NIF_DE_PRUEBA.contains(id)) {
                assertThat(despues.path("expectedEvaluations").path("R16").asText()).as(id).isEqualTo("INSUFICIENTE");
            }

            assertThat(antes.path("knownLimitation").asBoolean()).as(id).isFalse();
            assertThat(despues.path("knownLimitation").asBoolean()).as(id).isFalse();
        }

        NUEVOS.forEach((id, puntos) -> {
            JsonNode caso = v4.get(id);
            assertThat(caso.path("expectedScore").asInt()).as(id + " v4").isEqualTo(puntos.get(1));
            // The v3 score observed for the same text is recorded in the case notes.
            assertThat(caso.path("notas").asText()).as(id).contains("Reglas v3: ").contains(String.valueOf(puntos.get(0)));
            assertThat(caso.path("knownLimitation").asBoolean()).as(id).isFalse();
        });
    }

    private static List<String> textos(JsonNode array) {
        List<String> valores = new ArrayList<>();
        array.forEach(v -> valores.add(v.asText()));
        return valores;
    }

    private static Map<String, JsonNode> casos(String recurso) throws IOException {
        try (InputStream input = ReglasV4DeltaTest.class.getResourceAsStream(recurso)) {
            assertThat(input).as(recurso).isNotNull();
            Map<String, JsonNode> casos = new LinkedHashMap<>();
            new ObjectMapper().readTree(input).path("cases").forEach(c -> casos.put(c.path("id").asText(), c));
            return casos;
        }
    }
}
