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
 * Before/after of rules v3 (R02, R10, R12, R15, G03) on the same texts as v1 and v2. The
 * expectations below were written by hand from the rule design before the v3 corpus existed;
 * MotorAnalisisLegalRegressionTest then pins the engine to that corpus.
 */
class ReglasV3DeltaTest {

    private static final Set<String> LOTE = Set.of("R02", "R10", "R12", "R15", "G03");

    /** v2 score → v3 score; every other case keeps its score. */
    private static final Map<String, List<Integer>> PUNTUACIONES = Map.of(
            "M05", List.of(7, 3),   // «Este sitio utiliza cookies.» is a mere mention: half of 7
            "M06", List.of(4, 0),   // an https URL is no security information
            "M07", List.of(1, 0),   // implied consent without any consent description earns nothing
            "M11", List.of(0, 6));  // «Escríbanos a soporte@…» is a real contact channel

    /**
     * In a complete document, a missing cookie, consent or security text cannot be judged without
     * knowing whether the site uses cookies, relies on consent or what it implements: NO_VERIFICABLE.
     */
    private static final Map<String, String> AUSENCIA_NO_VERIFICABLE = Map.of(
            "R10:MISSING", "R10", "R12:MISSING", "R12", "R15:MISSING", "R15");
    private static final Map<String, List<String>> ANADIDAS = Map.of("M05", List.of("R10:INSUFFICIENT"));
    private static final Map<String, List<String>> RETIRADAS = Map.of("M11", List.of("R02:MISSING"));

    private static final Set<String> RESUELTAS = Set.of("M05", "M06", "M11");

    @Test
    void onlyTheSecondBatchChangesAndOnlyAsStated() throws IOException {
        Map<String, JsonNode> v2 = casos("/motor/current-engine-v2.json");
        Map<String, JsonNode> v3 = casos("/motor/current-engine-v3.json");
        assertThat(v3.keySet()).containsExactlyElementsOf(v2.keySet());

        for (String id : v2.keySet()) {
            JsonNode antes = v2.get(id);
            JsonNode despues = v3.get(id);
            assertThat(despues.path("texto").asText()).as(id).isEqualTo(antes.path("texto").asText());
            assertThat(despues.path("tipoDocumento").asText()).as(id).isEqualTo(antes.path("tipoDocumento").asText());

            int puntos = antes.path("expectedScore").asInt();
            List<Integer> esperado = PUNTUACIONES.getOrDefault(id, List.of(puntos, puntos));
            assertThat(puntos).as(id + " v2").isEqualTo(esperado.get(0));
            assertThat(despues.path("expectedScore").asInt()).as(id + " v3").isEqualTo(esperado.get(1));

            List<String> esperadas = new ArrayList<>(textos(antes.path("expectedIncidents")));
            AUSENCIA_NO_VERIFICABLE.forEach((incidencia, regla) -> {
                if (esperadas.remove(incidencia)) {
                    assertThat(despues.path("expectedEvaluations").path(regla).asText()).as(id + " " + regla)
                            .isEqualTo("NO_VERIFICABLE");
                }
            });
            RETIRADAS.getOrDefault(id, List.of()).forEach(i -> assertThat(esperadas.remove(i)).as(id + " " + i).isTrue());
            esperadas.addAll(ANADIDAS.getOrDefault(id, List.of()));
            assertThat(textos(despues.path("expectedIncidents"))).as(id).containsExactlyInAnyOrderElementsOf(esperadas);

            List<String> cambiadas = new ArrayList<>(textos(antes.path("expectedIncidents")));
            cambiadas.removeAll(textos(despues.path("expectedIncidents")));
            cambiadas.addAll(ANADIDAS.getOrDefault(id, List.of()));
            assertThat(cambiadas).as(id).allSatisfy(i -> assertThat(LOTE).contains(i.split(":")[0]));

            // Rules of the first batch keep their v2 states.
            JsonNode evaluaciones = antes.path("expectedEvaluations");
            evaluaciones.fieldNames().forEachRemaining(regla -> assertThat(despues.path("expectedEvaluations").path(regla).asText())
                    .as(id + " " + regla).isEqualTo(evaluaciones.path(regla).asText()));

            assertThat(antes.path("knownLimitation").asBoolean()).as(id).isEqualTo(RESUELTAS.contains(id));
            assertThat(despues.path("knownLimitation").asBoolean()).as(id).isFalse();
        }
    }

    private static List<String> textos(JsonNode array) {
        List<String> valores = new ArrayList<>();
        array.forEach(v -> valores.add(v.asText()));
        return valores;
    }

    private static Map<String, JsonNode> casos(String recurso) throws IOException {
        try (InputStream input = ReglasV3DeltaTest.class.getResourceAsStream(recurso)) {
            assertThat(input).as(recurso).isNotNull();
            Map<String, JsonNode> casos = new LinkedHashMap<>();
            new ObjectMapper().readTree(input).path("cases").forEach(c -> casos.put(c.path("id").asText(), c));
            return casos;
        }
    }
}
