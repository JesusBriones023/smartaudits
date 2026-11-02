package com.smartaudits.service.motor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartaudits.model.dto.ResultadoAuditoria;
import com.smartaudits.model.dto.ResultadoAuditoria.ErrorAuditoria;
import com.smartaudits.model.dto.ResultadoAuditoria.EvaluacionRegla;
import com.smartaudits.service.html.ExtractorTextoHtml;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static com.smartaudits.model.dto.ResultadoAuditoria.EstadoEvidencia.INSUFICIENTE;
import static com.smartaudits.model.dto.ResultadoAuditoria.EstadoEvidencia.NO_APLICABLE;
import static com.smartaudits.model.dto.ResultadoAuditoria.EstadoEvidencia.NO_VERIFICABLE;
import static com.smartaudits.model.dto.ResultadoAuditoria.EstadoEvidencia.POSIBLE_INCIDENCIA;
import static com.smartaudits.model.dto.ResultadoAuditoria.EstadoEvidencia.SUFICIENTE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rules v3 (R02, R10, R12, R15, G03), one small text per behaviour, expectations written by hand.
 * "v2:" records what the keyword rules still did with the same text.
 */
class MotorContextualV3Test {

    private static final String PRIVACIDAD = "Política de Privacidad";
    private static final String COOKIES = "Política de Cookies";
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

    private int puntos(String texto) {
        return analizar(texto, PRIVACIDAD).getPuntuacionRiesgo();
    }

    // ---- R02 Contacto ----------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            Contacto: privacidad@example.invalid.                                      | correo electrónico
            Escríbanos a soporte@example.invalid para hablar con nuestro equipo.       | correo electrónico
            Teléfono de atención al cliente: 912 345 678.                              | teléfono
            Responsable: Ejemplo S.L., calle Mayor 1, 28013 Madrid.                    | dirección postal
            Puede contactarnos mediante el formulario de contacto de la web.           | formulario de contacto
            """)
    void aConcreteChannelPresentedAsContactIsSufficient(String texto, String medio) {
        // v2: the second one (M11) was a MISSING finding; «Escríbanos» was not in the vocabulary.
        var e = evaluacion(texto, PRIVACIDAD, "R02");
        assertThat(e.estado()).isEqualTo(SUFICIENTE);
        assertThat(e.evidencia()).isEqualTo("«" + texto + "»");
        assertThat(e.detalle()).isEqualTo("Medios de contacto indicados: " + medio + ".");
    }

    @Test
    void aGenericInvitationOrAStrayAddressIsInsufficient() {
        // v2: «consulta» or «contacto» alone accredited the 6 points.
        String invitacion = "Para cualquier consulta, contacte con nosotros.";
        assertThat(errores(analizar(invitacion, PRIVACIDAD), "R02")).singleElement().satisfies(e -> {
            assertThat(e.getEstado()).isEqualTo(INSUFICIENTE);
            assertThat(e.getTitulo()).isEqualTo("Medios de contacto del responsable mencionados sin concretarlos");
            assertThat(e.getSeveridad()).isEqualTo("MEDIA");
            assertThat(e.getEvidencia()).isEqualTo("«" + invitacion + "»");
        });
        assertThat(evaluacion("El catálogo lo gestiona proveedor@example.invalid.", PRIVACIDAD, "R02").estado())
                .isEqualTo(INSUFICIENTE);
        // R02 weighs 6: an invitation earns 3, a channel 6.
        assertThat(puntos("Contacto: privacidad@example.invalid.") - puntos(invitacion)).isEqualTo(3);
    }

    @Test
    void exampleAddressesTheDposAddressAndTaxIdsAreNotTheControllersContact() {
        assertThat(evaluacion("Indique su correo, por ejemplo nombre@dominio.es, al registrarse.", PRIVACIDAD, "R02").estado())
                .isEqualTo(POSIBLE_INCIDENCIA);
        var dpo = analizar("Delegado de protección de datos: dpo@example.invalid.", PRIVACIDAD);
        assertThat(dpo.getEvaluaciones()).filteredOn(e -> e.ruleId().equals("R02")).singleElement()
                .satisfies(e -> assertThat(e.estado()).isEqualTo(POSIBLE_INCIDENCIA));
        assertThat(dpo.getEvaluaciones()).filteredOn(e -> e.ruleId().equals("R03")).singleElement()
                .satisfies(e -> assertThat(e.estado()).isEqualTo(SUFICIENTE));
        assertThat(evaluacion("Titular: Ejemplo S.L., NIF B12345678.", PRIVACIDAD, "R02").estado()).isEqualTo(POSIBLE_INCIDENCIA);
    }

    @Test
    void negatedContactIsNoContactButAChannelInTheNextClauseStillCounts() {
        assertThat(evaluacion("No disponemos de atención al cliente.", PRIVACIDAD, "R02").estado()).isEqualTo(POSIBLE_INCIDENCIA);
        var e = evaluacion("No atendemos consultas por teléfono; escríbanos a ayuda@example.invalid.", PRIVACIDAD, "R02");
        assertThat(e.estado()).isEqualTo(SUFICIENTE);
        assertThat(e.evidencia()).isEqualTo("«escríbanos a ayuda@example.invalid.»");
    }

    // ---- R10 Cookies -----------------------------------------------------------------------

    @Test
    void theWordCookiesIsAMentionAndAPartialDisclosureIsStillInsufficient() {
        // v2: M05 «Este sitio utiliza cookies.» accredited the full 7 points.
        var mencion = analizar("Este sitio utiliza cookies.", "Documento Completo");
        assertThat(errores(mencion, "R10")).singleElement().satisfies(e -> {
            assertThat(e.getEstado()).isEqualTo(INSUFICIENTE);
            assertThat(e.getSeveridad()).isEqualTo("MEDIA");
            assertThat(e.getDescripcion()).contains("Solo se mencionan las cookies.");
        });
        var parcial = evaluacion("Usamos cookies analíticas para medir las visitas.", PRIVACIDAD, "R10");
        assertThat(parcial.estado()).isEqualTo(INSUFICIENTE);
        assertThat(parcial.detalle()).isEqualTo("Información parcial. Se informa de: tipos, finalidades; falta: duración, gestión.");
    }

    @Test
    void aSubstantiveDisclosureIsSufficientWithoutClaimingTheBannerWorks() {
        String texto = "Usamos cookies técnicas y analíticas para medir las visitas, que duran un año, y puede rechazarlas desde el panel.";
        var e = evaluacion(texto, PRIVACIDAD, "R10");
        assertThat(e.estado()).isEqualTo(SUFICIENTE);
        assertThat(e.evidencia()).isEqualTo("«" + texto + "»");
        assertThat(e.detalle()).isEqualTo("Se informa de: tipos, finalidades, duración, gestión. "
                + "El funcionamiento real del aviso o panel de cookies no se ha verificado.");
        // In a cookie policy the following sentences need not repeat the word.
        var politica = evaluacion("Usamos cookies técnicas y analíticas. Sirven para medir las visitas. Duran un año.",
                COOKIES, "R10");
        assertThat(politica.estado()).isEqualTo(SUFICIENTE);
        assertThat(puntos(texto) - puntos("Este sitio utiliza cookies.")).isEqualTo(4);
    }

    @Test
    void problematicCookieStatementsArePossibleIncidentsUnlessNegatedQuotedOrAboutExemptCookies() {
        for (String texto : List.of("Si continúa navegando, consideramos que acepta el uso de cookies.",
                "No es posible rechazar las cookies publicitarias.")) {
            assertThat(errores(analizar(texto, COOKIES), "R10")).as(texto).singleElement().satisfies(e -> {
                assertThat(e.getEstado()).isEqualTo(POSIBLE_INCIDENCIA);
                assertThat(e.getTitulo()).isEqualTo(RIESGO + "Política de cookies ausente o incompleta");
                assertThat(e.getSeveridad()).isEqualTo("ALTA");
            });
        }
        assertThat(evaluacion("Las cookies técnicas no se pueden rechazar porque son necesarias.", COOKIES, "R10").estado())
                .isNotEqualTo(POSIBLE_INCIDENCIA);
        assertThat(evaluacion("Nunca instalamos cookies sin su consentimiento.", COOKIES, "R10").estado())
                .isNotEqualTo(POSIBLE_INCIDENCIA);
        assertThat(evaluacion("La AEPD no admite fórmulas como «al continuar navegando acepta las cookies».", COOKIES, "R10")
                .estado()).isNotEqualTo(POSIBLE_INCIDENCIA);
    }

    @Test
    void cookieApplicabilityDependsOnTheDocumentType() {
        // A cookie policy without cookie information is a possible incident.
        assertThat(errores(analizar("Bienvenido a nuestra web.", COOKIES), "R10")).singleElement()
                .satisfies(e -> assertThat(e.getEstado()).isEqualTo(POSIBLE_INCIDENCIA));
        // Elsewhere silence is not verifiable: the text does not tell whether the site uses cookies.
        var silencio = analizar("Bienvenido a nuestra web.", "Documento Completo");
        assertThat(errores(silencio, "R10")).isEmpty();
        assertThat(silencio.getFaltantes()).anyMatch(f -> f.contains("no permite saber si el sitio las utiliza"));
        var remision = evaluacion("Puede consultar la política de cookies para más información.", PRIVACIDAD, "R10");
        assertThat(remision.estado()).isEqualTo(NO_VERIFICABLE);
        assertThat(remision.evidencia()).isEqualTo("«Puede consultar la política de cookies para más información.»");
        assertThat(evaluacion("Este sitio utiliza cookies.", "Aviso Legal", "R10").estado()).isEqualTo(NO_APLICABLE);
    }

    // ---- R12 Consentimiento y G03 -----------------------------------------------------------

    @Test
    void consentIsSufficientOnlyWhenHowItIsGivenAndWithdrawnAreDescribed() {
        var e = evaluacion("El consentimiento se presta marcando una casilla y puede retirarlo en cualquier momento.",
                PRIVACIDAD, "R12");
        assertThat(e.estado()).isEqualTo(SUFICIENTE);
        assertThat(e.detalle()).contains("no se ha verificado");
        assertThat(evaluacion("Le pediremos su consentimiento mediante una casilla. Podrá retirar su consentimiento "
                + "escribiendo a privacidad@example.invalid.", PRIVACIDAD, "R12").estado()).isEqualTo(SUFICIENTE);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            Tratamos sus datos con su consentimiento.                         | No se explica cómo se presta ni cómo se retira.
            Su consentimiento será libre, específico e informado.             | No se explica cómo se presta ni cómo se retira.
            Puede retirar su consentimiento en cualquier momento.             | Se explica cómo retirarlo, pero no cómo se presta.
            """)
    void aGenericOrIncompleteConsentDescriptionIsInsufficient(String texto, String falta) {
        // v2: «consentimiento», «libre» or «informado» alone accredited the 6 points.
        assertThat(errores(analizar(texto, PRIVACIDAD), "R12")).singleElement().satisfies(e -> {
            assertThat(e.getEstado()).isEqualTo(INSUFICIENTE);
            assertThat(e.getSeveridad()).isEqualTo("MEDIA");
            assertThat(e.getDescripcion()).endsWith(falta);
            assertThat(e.getEvidencia()).isEqualTo("«" + texto + "»");
        });
    }

    @Test
    void tacitOrIrrevocableConsentIsAPossibleIncident() {
        for (String texto : List.of("Se entenderá prestado su consentimiento si no marca la casilla.",
                "Una vez prestado, el consentimiento no podrá retirarse.", "Utilizamos casillas premarcadas para el consentimiento.")) {
            assertThat(errores(analizar(texto, PRIVACIDAD), "R12")).as(texto).singleElement().satisfies(e -> {
                assertThat(e.getEstado()).isEqualTo(POSIBLE_INCIDENCIA);
                assertThat(e.getTitulo()).startsWith(RIESGO);
                assertThat(e.getSeveridad()).isEqualTo("ALTA");
            });
        }
        // Contradictory text: a proper description plus tacit consent keeps half of the 6 points.
        String correcto = "El consentimiento se presta marcando una casilla y puede retirarlo en cualquier momento.";
        assertThat(puntos(correcto + " Se entenderá prestado su consentimiento si no responde.")).isEqualTo(puntos(correcto) - 3);
    }

    @Test
    void g03IgnoresNegatedOrQuotedImpliedConsentButKeepsTheRealOne() {
        // v2: G03 fired on these (and R12 risk on the second one).
        for (String texto : List.of("Continuar navegando no implica consentimiento.",
                "En ningún caso entenderemos que continuar navegando implica aceptación.",
                "Expresiones como «continuar navegando implica consentimiento» no son válidas.")) {
            var r = analizar(texto, PRIVACIDAD);
            assertThat(errores(r, "G03")).as(texto).isEmpty();
            assertThat(errores(r, "R12")).as(texto).noneMatch(e -> e.getEstado() == POSIBLE_INCIDENCIA);
        }
        var real = analizar("Continuar navegando implica consentimiento.", PRIVACIDAD);
        assertThat(errores(real, "G03")).singleElement()
                .satisfies(e -> assertThat(e.getEvidencia()).isEqualTo("«Continuar navegando implica consentimiento.»"));
        assertThat(errores(real, "R12")).singleElement().satisfies(e -> assertThat(e.getTitulo()).startsWith(RIESGO));
    }

    @Test
    void consentSilenceIsNotVerifiableAndNotDemandedFromTerms() {
        var r = analizar("Responsable del tratamiento: Ejemplo S.L.", PRIVACIDAD);
        assertThat(errores(r, "R12")).isEmpty();
        assertThat(r.getEvaluaciones()).filteredOn(e -> e.ruleId().equals("R12")).singleElement()
                .satisfies(e -> assertThat(e.estado()).isEqualTo(NO_VERIFICABLE));
        assertThat(evaluacion("Responsable del tratamiento: Ejemplo S.L.", COOKIES, "R12").estado()).isEqualTo(NO_VERIFICABLE);
        assertThat(evaluacion("El precio incluye IVA.", "Términos y Condiciones", "R12").estado()).isEqualTo(NO_APLICABLE);
    }

    // ---- R15 Seguridad ---------------------------------------------------------------------

    @Test
    void concreteMeasuresAreSufficientButNeverProveImplementation() {
        var e = evaluacion("Aplicamos medidas de seguridad como el cifrado de los datos y el control de acceso.", PRIVACIDAD, "R15");
        assertThat(e.estado()).isEqualTo(SUFICIENTE);
        assertThat(e.detalle()).isEqualTo("Medidas declaradas: cifrado, control de acceso. El texto no acredita que estén implantadas.");
        var generica = analizar("Aplicamos las medidas de seguridad adecuadas.", PRIVACIDAD);
        assertThat(errores(generica, "R15")).singleElement().satisfies(x -> {
            assertThat(x.getEstado()).isEqualTo(INSUFICIENTE);
            assertThat(x.getSeveridad()).isEqualTo("BAJA");
        });
        // R15 weighs 4: generic 2, concrete 4. (No «cifrado» here: under rules v3, R16 matched «cif» inside it.)
        assertThat(puntos("Aplicamos medidas de seguridad como copias de seguridad diarias y control de acceso a los datos.")
                - puntos("Aplicamos las medidas de seguridad adecuadas.")).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"El enlace del catálogo es https://example.invalid.", "La web usa https.",
            "Puede pedir una copia de seguridad del contrato firmado.", "Los datos no están cifrados."})
    void incidentalVocabularyIsNoSecurityStatementAndSilenceIsNotVerifiable(String texto) {
        // v2: M06 and «https» accredited the 4 points.
        var r = analizar(texto, PRIVACIDAD);
        assertThat(errores(r, "R15")).isEmpty();
        assertThat(r.getEvaluaciones()).filteredOn(e -> e.ruleId().equals("R15")).singleElement()
                .satisfies(e -> assertThat(e.estado()).isEqualTo(NO_VERIFICABLE));
    }

    @Test
    void aSecurityDisclaimerIsAPossibleIncidentAndOtherTypesAreNotAsked() {
        assertThat(errores(analizar("No podemos garantizar la seguridad de sus datos.", PRIVACIDAD), "R15")).singleElement()
                .satisfies(e -> {
                    assertThat(e.getEstado()).isEqualTo(POSIBLE_INCIDENCIA);
                    assertThat(e.getTitulo()).startsWith(RIESGO);
                });
        var aviso = analizar("Titular: Ejemplo S.L.", "Aviso Legal");
        assertThat(aviso.getEvaluaciones()).filteredOn(e -> List.of("R02", "R10", "R12", "R15").contains(e.ruleId()))
                .extracting(EvaluacionRegla::estado).containsOnly(NO_APLICABLE);
    }

    // ---- Hostile HTML, history ---------------------------------------------------------------

    @Test
    void hiddenOrScriptedTextIsNeverEvidence() {
        String html = """
                <h1>Política de privacidad</h1>
                <script>document.write('Contacto: falso@example.invalid')</script>
                <p hidden>Usamos cookies técnicas para recordar preferencias; caducan en un año y puede rechazarlas.</p>
                <p>Continuar navegando implica consentimiento <img src=x onerror="alert(1)">.</p>""";
        var r = analizar(new ExtractorTextoHtml().extraer(html), PRIVACIDAD);
        assertThat(r.getEvaluaciones()).filteredOn(e -> e.ruleId().equals("R02")).singleElement()
                .satisfies(e -> assertThat(e.estado()).isEqualTo(POSIBLE_INCIDENCIA));
        assertThat(r.getEvaluaciones()).filteredOn(e -> e.ruleId().equals("R10")).singleElement()
                .satisfies(e -> assertThat(e.estado()).isEqualTo(NO_VERIFICABLE));
        assertThat(errores(r, "G03")).singleElement().satisfies(e -> assertThat(e.getEvidencia()).doesNotContain("<", "alert"));
    }

    @Test
    void storedRulesV2ResultsAreReadAsTheyWere() throws Exception {
        String v2 = """
                {"resumen":"Reglas v2","puntuacionRiesgo":70,"errores":[{"ruleId":"R05","motor":"LEGAL_TEXT","version":"2",
                "titulo":"Base legal del tratamiento mencionada sin identificarla","descripcion":"d","severidad":"MEDIA",
                "evidencia":"«Legitimación: la indicada.»","impacto":"i","accion":"a","estado":"INSUFICIENTE"}],
                "evaluaciones":[{"ruleId":"R05","estado":"INSUFICIENTE","evidencia":"«Legitimación: la indicada.»","detalle":"x"}]}""";
        var mapper = new ObjectMapper();
        var leido = mapper.readValue(v2, ResultadoAuditoria.class);
        assertThat(leido.getPuntuacionRiesgo()).isEqualTo(70);
        assertThat(leido.getEvaluaciones()).extracting(EvaluacionRegla::ruleId).containsExactly("R05");
        assertThat(leido.getErrores()).singleElement().satisfies(e -> assertThat(e.getVersion()).isEqualTo("2"));
        var escrito = mapper.readTree(mapper.writeValueAsString(leido));
        for (String campo : List.of("puntuacionRiesgo", "errores", "evaluaciones")) {
            assertThat(escrito.path(campo)).as(campo).isEqualTo(mapper.readTree(v2).path(campo));
        }
        assertThat(motor.version()).isEqualTo(new VersionAnalizador("3", "5"));
    }
}
