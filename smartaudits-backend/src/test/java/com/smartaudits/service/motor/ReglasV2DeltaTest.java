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
 * Before/after of rules v2 on the very same texts: v1 is the frozen characterization of the old
 * rules, v2 the current one (checked against the engine by MotorAnalisisLegalRegressionTest).
 * Every difference between them is listed here by hand; anything else is a regression.
 */
class ReglasV2DeltaTest {

    private static final Set<String> CONTEXTUALES = Set.of("R03", "R05", "R06", "R07", "R08", "G01", "G02");

    /** v1 score → v2 score; every other case keeps its score. */
    private static final Map<String, List<Integer>> PUNTUACIONES = Map.of(
            "M03", List.of(8, 0),   // «barco» no longer contains the right «arco»
            "M04", List.of(0, 5),   // negated «indefinidamente» is not a risk; «cinco años» accredits R07
            "M12", List.of(8, 0));  // «No puede ejercer sus derechos» denies the rights

    private static final Map<String, List<String>> ANADIDAS = Map.of(
            "M03", List.of("R06:MISSING"),
            "M12", List.of("R06:RISK"));
    private static final Map<String, List<String>> RETIRADAS = Map.of(
            "M04", List.of("R07:RISK", "G02"));

    /** Known limitations of v1 that rules v2 resolve, and the ones left for the next batch. */
    private static final Set<String> RESUELTAS = Set.of("M03", "M04", "M12");
    private static final Set<String> PENDIENTES = Set.of("M05", "M06", "M11"); // R10, R15, R02: not in this batch

    @Test
    void onlyTheSelectedRulesChangeAndOnlyAsStated() throws IOException {
        Map<String, JsonNode> v1 = casos("/motor/current-engine-v1.json");
        Map<String, JsonNode> v2 = casos("/motor/current-engine-v2.json");
        assertThat(v2.keySet()).containsExactlyElementsOf(v1.keySet());

        for (String id : v1.keySet()) {
            JsonNode antes = v1.get(id);
            JsonNode despues = v2.get(id);
            assertThat(despues.path("texto").asText()).as(id).isEqualTo(antes.path("texto").asText());
            assertThat(despues.path("tipoDocumento").asText()).as(id).isEqualTo(antes.path("tipoDocumento").asText());

            List<Integer> puntos = PUNTUACIONES.getOrDefault(id,
                    List.of(antes.path("expectedScore").asInt(), antes.path("expectedScore").asInt()));
            assertThat(antes.path("expectedScore").asInt()).as(id + " v1").isEqualTo(puntos.get(0));
            assertThat(despues.path("expectedScore").asInt()).as(id + " v2").isEqualTo(puntos.get(1));

            List<String> esperadas = new ArrayList<>(textos(antes.path("expectedIncidents")));
            // A missing DPO mention is no longer a finding: R03 is not verifiable from the text.
            if (esperadas.remove("R03:MISSING")) {
                assertThat(despues.path("expectedEvaluations").path("R03").asText()).as(id).isEqualTo("NO_VERIFICABLE");
            }
            RETIRADAS.getOrDefault(id, List.of()).forEach(i -> assertThat(esperadas.remove(i)).as(id + " " + i).isTrue());
            esperadas.addAll(ANADIDAS.getOrDefault(id, List.of()));
            assertThat(textos(despues.path("expectedIncidents"))).as(id).containsExactlyInAnyOrderElementsOf(esperadas);

            List<String> cambiadas = new ArrayList<>(textos(antes.path("expectedIncidents")));
            cambiadas.removeAll(textos(despues.path("expectedIncidents")));
            cambiadas.addAll(ANADIDAS.getOrDefault(id, List.of()));
            assertThat(cambiadas).as(id).allSatisfy(i -> assertThat(CONTEXTUALES).contains(i.split(":")[0]));

            boolean resuelta = RESUELTAS.contains(id);
            assertThat(antes.path("knownLimitation").asBoolean()).as(id).isEqualTo(resuelta || PENDIENTES.contains(id));
            assertThat(despues.path("knownLimitation").asBoolean()).as(id).isEqualTo(PENDIENTES.contains(id));
        }
    }

    private static List<String> textos(JsonNode array) {
        List<String> valores = new ArrayList<>();
        array.forEach(v -> valores.add(v.asText()));
        return valores;
    }

    private static Map<String, JsonNode> casos(String recurso) throws IOException {
        try (InputStream input = ReglasV2DeltaTest.class.getResourceAsStream(recurso)) {
            assertThat(input).as(recurso).isNotNull();
            Map<String, JsonNode> casos = new LinkedHashMap<>();
            new ObjectMapper().readTree(input).path("cases").forEach(c -> casos.put(c.path("id").asText(), c));
            return casos;
        }
    }
}
