package com.smartaudits.service.motor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartaudits.model.dto.ResultadoAuditoria;
import com.smartaudits.model.dto.ResultadoAuditoria.ErrorAuditoria;
import com.smartaudits.model.dto.ResultadoAuditoria.EvaluacionRegla;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static com.smartaudits.model.dto.ResultadoAuditoria.EstadoEvidencia.INSUFICIENTE;
import static com.smartaudits.model.dto.ResultadoAuditoria.EstadoEvidencia.NO_APLICABLE;
import static com.smartaudits.model.dto.ResultadoAuditoria.EstadoEvidencia.NO_VERIFICABLE;
import static com.smartaudits.model.dto.ResultadoAuditoria.EstadoEvidencia.POSIBLE_INCIDENCIA;
import static com.smartaudits.model.dto.ResultadoAuditoria.EstadoEvidencia.SUFICIENTE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rules v6: legal calibration of R13, R14, R17, R18 and R19 and review of the legal wording, one small text per
 * decision, expectations written by hand from the BOE texts (RGPD, LOPDGDD, LSSI-CE, Ley 10/2025).
 * "v5:" records what the rules v5 engine did with the same text.
 */
class MotorContextualV6Test {

    private static final String PRIVACIDAD = "Política de Privacidad";
    private static final String RIESGO = "Cláusula presente pero con redacción problemática: ";
    private final MotorAnalisisLegal motor = new MotorAnalisisLegal();

    private ResultadoAuditoria analizar(String texto, String tipo) {
        return motor.analyze(new EntradaAnalisis(texto, tipo));
    }

    private EvaluacionRegla evaluacion(String texto, String tipo, String ruleId) {
        return analizar(texto, tipo).getEvaluaciones().stream().filter(e -> e.ruleId().equals(ruleId)).findFirst().orElseThrow();
    }

    private List<ErrorAuditoria> errores(ResultadoAuditoria r, String ruleId) {
        return r.getErrores().stream().filter(e -> e.getRuleId().equals(ruleId)).toList();
    }

    private int puntos(String texto, String ruleId) {
        return analizar(texto, PRIVACIDAD).getDesglosePuntuacion().controles().stream().filter(c -> c.ruleId().equals(ruleId))
                .findFirst().orElseThrow().puntos();
    }

    private void estado(String texto, String ruleId, Object estado, int puntos, String detalle) {
        var e = evaluacion(texto, PRIVACIDAD, ruleId);
        assertThat(e.estado()).as(texto).isEqualTo(estado);
        assertThat(e.detalle()).as(texto).contains(detalle);
        assertThat(puntos(texto, ruleId)).as(texto).isEqualTo(puntos);
    }

    // ---- R13: Art. 7 LOPDGDD governs consent-based processing only ----------------------------------

    @Test
    void minorsDataWithoutAConsentBasisIsNotVerifiable() {
        // v5: INSUFICIENTE with a finding, only because minors were mentioned.
        String texto = "También tratamos datos de menores.";
        var r = analizar(texto, PRIVACIDAD);
        var e = evaluacion(texto, PRIVACIDAD, "R13");
        assertThat(e.estado()).isEqualTo(NO_VERIFICABLE);
        assertThat(e.evidencia()).isEqualTo("«" + texto + "»");
        assertThat(e.detalle()).isEqualTo("Se mencionan datos de menores, pero el texto no indica si su tratamiento se basa en "
                + "su consentimiento: la edad del Art. 7 LOPDGDD solo se aplica a ese caso, y otras bases jurídicas no "
                + "dependen de ella.");
        assertThat(errores(r, "R13")).isEmpty();
        estado("Tratamos datos de menores con su consentimiento.", "R13", INSUFICIENTE, 1,
                "El texto basa en el consentimiento el tratamiento de datos de menores");
    }

    @Test
    void notCheckingAgesIsNoBreachByItself() {
        // v5: possible incident of ALTA severity.
        estado("Menores: no verificamos la edad de los usuarios.", "R13", INSUFICIENTE, 1,
                "No comprobar la edad no es por sí solo un incumplimiento.");
        estado("Cualquier persona, sin restricción de edad, puede registrarse en la plataforma.", "R13", INSUFICIENTE, 1,
                "No comprobar la edad no es por sí solo un incumplimiento.");
        // With conditions stated, it is a note on Art. 8.2 RGPD, not a finding.
        String conCondiciones = "Los menores de 14 años necesitan el consentimiento de sus padres. No verificamos la edad de "
                + "los usuarios.";
        estado(conCondiciones, "R13", SUFICIENTE, 3, "el Art. 8.2 RGPD exige esfuerzos razonables para verificar el "
                + "consentimiento de sus padres o tutores.");
        assertThat(errores(analizar(conCondiciones, PRIVACIDAD), "R13")).isEmpty();
        assertThat(evaluacion("Menores: no verificamos la edad de los usuarios.", "Aviso Legal", "R13").estado())
                .isEqualTo(NO_APLICABLE);
    }

    @Test
    void anAgeBelowFourteenIsAnIssueOnlyForConsent() {
        // An age to register may rest on another legal basis: insufficient information, not a possible incident.
        estado("Para crear una cuenta debe tener al menos 13 años.", "R13", INSUFICIENTE, 1,
                "El texto fija en 13 años la edad para registrarse sin indicar si el registro se basa en el consentimiento");
        estado("Los mayores de 13 años pueden prestar su consentimiento para registrarse.", "R13", POSIBLE_INCIDENCIA, 0,
                "El texto admite el consentimiento propio de menores desde los 13 años; el Art. 7 LOPDGDD solo lo admite "
                        + "cuando el menor es mayor de 14 años.");
        estado("Los menores de 13 años necesitan el consentimiento de sus padres.", "R13", POSIBLE_INCIDENCIA, 0,
                "desde los 13 años");
        estado("Cualquier persona, sin restricción de edad, puede prestar su consentimiento.", "R13", POSIBLE_INCIDENCIA, 0,
                "El texto admite el consentimiento de usuarios de cualquier edad");
        estado("Los menores de 14 años pueden registrarse sin el consentimiento de sus padres.", "R13", POSIBLE_INCIDENCIA, 0,
                "si el tratamiento se basa en su consentimiento");
    }

    // ---- R14: one exception per processing activity ------------------------------------------------

    @Test
    void anExceptionForOneProcessingDoesNotCoverAnother() {
        // v5: SUFICIENTE, the health exception validated the fingerprints too.
        String texto = "Tratamos datos de salud con su consentimiento explícito para gestionar sus citas. También recogemos "
                + "sus huellas dactilares para el control de acceso.";
        var r = analizar(texto, PRIVACIDAD);
        assertThat(errores(r, "R14")).singleElement().satisfies(e -> {
            assertThat(e.getEstado()).isEqualTo(INSUFICIENTE);
            assertThat(e.getSeveridad()).isEqualTo("BAJA");
            assertThat(e.getEvidencia()).isEqualTo("«También recogemos sus huellas dactilares para el control de acceso.»");
            assertThat(e.getDescripcion()).endsWith("Se menciona el tratamiento de categorías especiales (datos biométricos) "
                    + "sin indicar la excepción del Art. 9.2 RGPD que lo permite. Se indica una excepción del Art. 9.2 RGPD "
                    + "(consentimiento explícito) para el tratamiento de salud, pero cada tratamiento necesita la suya.");
        });
        assertThat(puntos(texto, "R14")).isEqualTo(1);
        estado("Tratamos datos de salud con su consentimiento explícito. Tratamos datos biométricos con su consentimiento "
                + "explícito para el control de acceso.", "R14", SUFICIENTE, 3,
                "Categorías especiales: salud, datos biométricos. Excepción del Art. 9.2 RGPD indicada: consentimiento explícito.");
    }

    @Test
    void consentAloneIsQualifiedForBeliefsAndOrigin() {
        estado("Tratamos datos sobre su religión con su consentimiento explícito para adaptar los menús.", "R14", SUFICIENTE, 3,
                "el Art. 9.1 LOPDGDD indica que el solo consentimiento no basta cuando la finalidad principal del tratamiento "
                        + "es identificar esos datos.");
        assertThat(evaluacion("Tratamos datos de salud con su consentimiento explícito.", PRIVACIDAD, "R14").detalle())
                .doesNotContain("Art. 9.1 LOPDGDD");
    }

    @Test
    void definitionsAndDeclaredSafeguardsAreToldApart() {
        assertThat(evaluacion("Según el artículo 9 del RGPD, son categorías especiales los datos de salud o los datos "
                + "biométricos.", PRIVACIDAD, "R14").estado()).isEqualTo(NO_VERIFICABLE);
        estado("Los datos biométricos se protegen con cifrado y acceso restringido.", "R14", INSUFICIENTE, 1,
                "Declararlas no acredita que estén implantadas.");
    }

    // ---- R17: Art. 22 decisions versus ordinary profiling --------------------------------------------

    @Test
    void ordinaryProfilingNeedsItsPurposeNotArticle22Information() {
        // v5: INSUFICIENTE, logic and consequences were asked of any profiling.
        estado("Elaboramos perfiles de uso para mejorar la aplicación.", "R17", SUFICIENTE, 4,
                "Se informa de la elaboración de perfiles y de su finalidad. El texto no indica que den lugar a decisiones "
                        + "con efectos jurídicos o significativos, por lo que no se exige la información del Art. 22 RGPD.");
        estado("Elaboramos perfiles de consumo con sus compras.", "R17", INSUFICIENTE, 2,
                "Se alude a la elaboración de perfiles sin indicar su finalidad.");
    }

    @Test
    void marketingProfilingNeedsTheRightToObject() {
        String sin = "Elaboramos perfiles con su historial de navegación para mostrarle publicidad personalizada.";
        estado(sin, "R17", INSUFICIENTE, 2, "sin mencionar el derecho a oponerse a ellos, que debe presentarse de forma "
                + "explícita (Arts. 21.2 y 21.4 RGPD).");
        String con = sin + " Puede oponerse a la elaboración de perfiles en cualquier momento.";
        estado(con, "R17", SUFICIENTE, 4, "Se informa de la elaboración de perfiles y de su finalidad.");
        estado("No podrá oponerse a la elaboración de perfiles con fines comerciales.", "R17", POSIBLE_INCIDENCIA, 0,
                "El texto impide oponerse a la elaboración de perfiles");
    }

    @Test
    void significantAutomatedDecisionsNeedTheirLogicAndConsequences() {
        String conGarantias = "Un sistema automático decide si se concede el préstamo según su puntuación crediticia, lo "
                + "que puede suponer la denegación. Puede solicitar intervención humana, expresar su punto de vista e "
                + "impugnar la decisión.";
        var e = evaluacion(conGarantias, PRIVACIDAD, "R17");
        assertThat(e.estado()).isEqualTo(SUFICIENTE);
        assertThat(e.detalle()).isEqualTo("Se informa de decisiones automatizadas con efectos significativos, de su lógica y "
                + "de sus consecuencias (Arts. 13.2.f y 22 RGPD). El texto no permite comprobar cómo se adoptan las decisiones.");
        // Without the safeguards the information duty is met, and the missing safeguards are pointed out.
        estado("Un sistema automático decide, sin intervención humana, si se concede el préstamo según su puntuación "
                + "crediticia, lo que puede suponer la denegación.", "R17", SUFICIENTE, 4,
                "No se describen las garantías del Art. 22.3 RGPD (intervención humana, expresar su punto de vista e "
                        + "impugnar la decisión), exigibles cuando la decisión se basa en un contrato o en el consentimiento "
                        + "explícito.");
        estado("Un sistema automático decide si se concede el préstamo.", "R17", INSUFICIENTE, 2,
                "Se describen decisiones automatizadas con efectos significativos sin indicar la lógica aplicada ni sus "
                        + "consecuencias (Art. 13.2.f RGPD).");
        estado("Las solicitudes de financiación se rechazan automáticamente y no podrá impugnar la decisión.", "R17",
                POSIBLE_INCIDENCIA, 0, "con efectos significativos, lo que puede ser contrario al Art. 22.3 RGPD.");
    }

    @Test
    void humanInvolvementOrUnknownEffectsAreNotTreatedAsArticle22() {
        // v5: INSUFICIENTE for missing logic.
        estado("Las decisiones automatizadas están sujetas a intervención humana.", "R17", SUFICIENTE, 4,
                "El texto indica que las decisiones cuentan con intervención humana, por lo que no las presenta como "
                        + "basadas únicamente en un tratamiento automatizado (Art. 22 RGPD)");
        estado("Utilizamos decisiones automatizadas para evaluar las solicitudes.", "R17", INSUFICIENTE, 2,
                "sin indicar si producen efectos jurídicos o significativos (Art. 22 RGPD)");
    }

    // ---- R18: authority notification versus communication to the people affected ---------------------

    @Test
    void theAuthorityAndTheAffectedPeopleAreToldApart() {
        estado("Si se produce una brecha de seguridad, la notificaremos a la AEPD en 72 horas.", "R18", SUFICIENTE, 3,
                "Se informa de la notificación a la autoridad de control en 72 horas.");
        estado("Si se produce una brecha de seguridad, informaremos a los usuarios afectados en 72 horas.", "R18", SUFICIENTE,
                3, "El plazo de 72 horas del Art. 33 RGPD se refiere a la notificación a la autoridad de control; la "
                        + "comunicación a los afectados (Art. 34) procede sin dilación indebida cuando la brecha entraña un "
                        + "alto riesgo.");
        estado("Si se produce una brecha de seguridad, la notificaremos a la AEPD y, si entraña un alto riesgo, a los "
                + "afectados.", "R18", SUFICIENTE, 3, "Se informa de la notificación a la autoridad de control y la "
                + "comunicación a las personas afectadas cuando entrañe un alto riesgo.");
    }

    @Test
    void theHighRiskThresholdBelongsToTheAffectedPeople() {
        // v5: SUFICIENTE. Information is given, with the wrong threshold: half of the weight and a finding.
        String texto = "Solo notificaremos a la AEPD las brechas de seguridad que supongan un alto riesgo.";
        var r = analizar(texto, PRIVACIDAD);
        assertThat(errores(r, "R18")).singleElement().satisfies(e -> {
            assertThat(e.getEstado()).isEqualTo(POSIBLE_INCIDENCIA);
            assertThat(e.getTitulo()).startsWith(RIESGO);
            assertThat(e.getEvidencia()).isEqualTo("«" + texto + "»");
            assertThat(e.getDescripcion()).endsWith("el Art. 33.1 RGPD exige notificarla salvo que sea improbable que entrañe "
                    + "un riesgo. El umbral del alto riesgo corresponde a la comunicación a los afectados (Art. 34).");
        });
        assertThat(puntos(texto, "R18")).isEqualTo(1);
        estado("No comunicaremos a la AEPD las brechas de seguridad.", "R18", POSIBLE_INCIDENCIA, 0,
                "el Art. 33 RGPD obliga a hacerlo en 72 horas salvo que sea improbable que entrañen un riesgo.");
    }

    // ---- R19: Art. 21 LSSI-CE is not replaced by a GDPR legal basis ----------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"Le enviaremos ofertas por correo electrónico sobre la base de nuestro interés legítimo, y puede "
            + "darse de baja en cualquier momento.", "Le enviaremos ofertas sobre la base de nuestro interés legítimo, y puede "
            + "darse de baja en cualquier momento."})
    void legitimateInterestAloneDoesNotAuthoriseElectronicMarketing(String texto) {
        // v5: SUFICIENTE with a note.
        estado(texto, "R19", INSUFICIENTE, 1, "Es una base jurídica del RGPD que no sustituye el Art. 21 LSSI-CE");
    }

    @Test
    void postIsOutsideArticle21() {
        estado("Le enviaremos ofertas por correo postal sobre la base de nuestro interés legítimo, y puede oponerse en "
                + "cualquier momento.", "R19", SUFICIENTE, 3, "Por correo postal no se aplica el Art. 21 LSSI-CE; el interés "
                + "legítimo exige respetar el derecho de oposición (Art. 21.2 RGPD).");
    }

    @Test
    void theCustomerExceptionNeedsSimilarProductsOfTheCompanyItself() {
        estado("A nuestros clientes les enviaremos por correo electrónico ofertas de productos similares a los que "
                + "contrataron, y podrán oponerse en cualquier momento.", "R19", SUFICIENTE, 3,
                "Se basan en: relación contractual previa (Art. 21.2 LSSI-CE).");
        for (String texto : List.of("A nuestros clientes les enviaremos por correo electrónico ofertas de productos de "
                        + "terceros, y podrán darse de baja en cualquier momento.",
                "A nuestros clientes les enviaremos por correo electrónico ofertas de productos similares de nuestros socios, "
                        + "y podrán oponerse en cualquier momento.")) {
            estado(texto, "R19", INSUFICIENTE, 1, "condición de la excepción del Art. 21.2 LSSI-CE.");
        }
        assertThat(evaluacion("Le enviaremos un correo electrónico para confirmar su pedido.", PRIVACIDAD, "R19").estado())
                .isEqualTo(NO_VERIFICABLE);
    }

    // ---- Legal wording, references and compatibility ----------------------------------------------

    @Test
    void impactsDoNotAssertAutomaticBreachesOrSanctions() {
        var r = analizar("Bienvenido.", "Documento Completo");
        var aviso = analizar("Aviso legal. Las condiciones de este sitio se rigen por la ley española.", "Aviso Legal");
        var riesgos = analizar("Vendemos sus datos. Conservaremos sus datos indefinidamente. No podemos garantizar la "
                + "seguridad. Usamos fingerprinting.", "Documento Completo");
        for (var resultado : List.of(r, aviso, riesgos)) {
            assertThat(resultado.getErrores()).isNotEmpty().allSatisfy(e -> {
                assertThat(e.getImpacto() + " " + e.getDescripcion()).as(e.getRuleId())
                        .doesNotContain("Incumplimiento", "Sanciones", "millones", "150.000", "infracción grave",
                                "ilegal o abusiva", "contradiciendo", "directamente ilícito");
            });
        }
        assertThat(aviso.getErrores()).filteredOn(e -> e.getRuleId().equals("R16")).singleElement()
                .satisfies(e -> assertThat(e.getImpacto()).contains("El texto no permite saber si el titular presta esos servicios."));
    }

    @Test
    void law10Of2025IsOnlyCitedForArticle23Lopdgdd() {
        var r = analizar("Bienvenido.", "Documento Completo");
        assertThat(r.getReferenciasLegales()).noneMatch(ref -> ref.contains("10/2025") || ref.contains("consolidada"))
                .contains("LOPDGDD - Ley Orgánica 3/2018, de 5 de diciembre", "Guía sobre el uso de las cookies - AEPD");
        assertThat(r.getResumen()).contains("Normativa de referencia: RGPD (UE 2016/679), LOPDGDD (LO 3/2018), LSSI-CE "
                + "(Ley 34/2002) y Directiva ePrivacy 2002/58/CE.").doesNotContain("10/2025", "consolidada");
        var comercial = analizar("Le enviaremos nuestra newsletter.", PRIVACIDAD);
        assertThat(comercial.getReferenciasLegales()).contains("LSSI-CE Arts. 21 y 22 — Comunicaciones comerciales / RGPD "
                + "Art. 21 / LOPDGDD Art. 23 (apartado 1 modificado por la Ley 10/2025, de servicios de atención a la clientela)");
    }

    @Test
    void hostileMarkupAndNullTypesAreHandledAsText() {
        String texto = "<img src=x onerror=\"alert(1)\">Le enviaremos ofertas por correo electrónico sobre la base de nuestro "
                + "interés legítimo.";
        var e = evaluacion(texto, null, "R19");
        assertThat(e.estado()).isEqualTo(INSUFICIENTE);
        assertThat(e.evidencia()).isEqualTo("«" + texto + "»");
        assertThat(evaluacion("Elaboramos perfiles de uso para mejorar la aplicación.", null, "R17").estado())
                .isEqualTo(SUFICIENTE);
    }

    @Test
    void storedRulesV5ResultsAreNotReinterpreted() throws Exception {
        String v5 = """
                {"resumen":"Reglas v5","puntuacionRiesgo":92,"errores":[{"ruleId":"R17","motor":"LEGAL_TEXT","version":"5",
                "titulo":"Decisiones automatizadas o perfilado informados de forma incompleta","descripcion":"d","severidad":"BAJA",
                "evidencia":"«Las decisiones automatizadas están sujetas a intervención humana.»","impacto":"i","accion":"a",
                "estado":"INSUFICIENTE"}],"evaluaciones":[{"ruleId":"R17","estado":"INSUFICIENTE","evidencia":"e","detalle":"d"}]}""";
        var mapper = new ObjectMapper();
        var leido = mapper.readValue(v5, ResultadoAuditoria.class);
        assertThat(leido.getPuntuacionRiesgo()).isEqualTo(92);
        assertThat(leido.getErrores()).singleElement().satisfies(e -> {
            assertThat(e.getVersion()).isEqualTo("5");
            assertThat(e.getEstado()).isEqualTo(INSUFICIENTE);
        });
        var reescrito = mapper.readTree(mapper.writeValueAsString(leido));
        assertThat(reescrito.path("errores")).isEqualTo(mapper.readTree(v5).path("errores"));
        assertThat(reescrito.path("evaluaciones")).isEqualTo(mapper.readTree(v5).path("evaluaciones"));
        assertThat(reescrito.path("puntuacionRiesgo").asInt()).isEqualTo(92);
        assertThat(motor.version()).isEqualTo(new VersionAnalizador("3", "6"));
    }
}
