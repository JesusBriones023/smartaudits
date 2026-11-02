package com.smartaudits.service.motor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartaudits.model.dto.ResultadoAuditoria;
import com.smartaudits.model.dto.ResultadoAuditoria.ErrorAuditoria;
import com.smartaudits.model.dto.ResultadoAuditoria.EvaluacionRegla;
import com.smartaudits.service.html.ExtractorTextoHtml;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static com.smartaudits.model.dto.ResultadoAuditoria.EstadoEvidencia.INSUFICIENTE;
import static com.smartaudits.model.dto.ResultadoAuditoria.EstadoEvidencia.NO_APLICABLE;
import static com.smartaudits.model.dto.ResultadoAuditoria.EstadoEvidencia.NO_VERIFICABLE;
import static com.smartaudits.model.dto.ResultadoAuditoria.EstadoEvidencia.POSIBLE_INCIDENCIA;
import static com.smartaudits.model.dto.ResultadoAuditoria.EstadoEvidencia.SUFICIENTE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rules v2 (R03, R05, R06, R07, R08, G01, G02), one small text per behaviour. Each comment
 * "v1:" records what the keyword rules did with the same text.
 */
class MotorContextualTest {

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

    private int puntos(String texto) {
        return analizar(texto, PRIVACIDAD).getPuntuacionRiesgo();
    }

    // ---- R05 Base jurídica ---------------------------------------------------------------

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            La base jurídica del tratamiento es la ejecución del contrato (art. 6.1.b RGPD).        | ejecución de un contrato
            Legitimación: interés legítimo del responsable.                                          | interés legítimo
            Tratamos sus datos con arreglo al artículo 6.1.f RGPD.                                   | interés legítimo
            Sus datos se tratan con su consentimiento y para cumplir obligaciones legales.           | consentimiento, obligación legal
            """)
    void aNamedLegalBasisTiedToTheProcessingIsSufficient(String texto, String bases) {
        var e = evaluacion(texto, PRIVACIDAD, "R05");
        assertThat(e.estado()).isEqualTo(SUFICIENTE);
        assertThat(e.evidencia()).isEqualTo("«" + texto + "»");
        assertThat(e.detalle()).isEqualTo("Bases jurídicas identificadas: " + bases + ".");
    }

    @Test
    void aHeadingFollowedByTheBasisIsReadTogether() {
        var e = evaluacion("Base jurídica" + System.lineSeparator() + System.lineSeparator()
                + "El consentimiento que nos otorga al registrarse.", PRIVACIDAD, "R05");
        assertThat(e.estado()).isEqualTo(SUFICIENTE);
        assertThat(e.evidencia()).isEqualTo("«Base jurídica El consentimiento que nos otorga al registrarse.»");
    }

    @Test
    void consentOutOfContextOrGenericConnectorsDoNotInformTheLegalBasis() {
        // v1: "en virtud de" and "6.1" satisfied R05; "consentimiento" alone does not either now.
        for (String texto : List.of("Puede retirar su consentimiento a las cookies en cualquier momento.",
                "En virtud de este acuerdo, el apartado 6.1 regula los precios.")) {
            var r = analizar(texto, PRIVACIDAD);
            assertThat(r.getEvaluaciones()).filteredOn(e -> e.ruleId().equals("R05")).singleElement()
                    .satisfies(e -> assertThat(e.estado()).as(texto).isEqualTo(POSIBLE_INCIDENCIA));
            assertThat(errores(r, "R05")).singleElement().satisfies(e -> {
                assertThat(e.getTitulo()).isEqualTo("Base legal del tratamiento no indicada");
                assertThat(e.getSeveridad()).isEqualTo("ALTA");
                assertThat(e.getEvidencia()).startsWith("No se ha encontrado en el texto analizado ninguna cláusula");
            });
        }
    }

    @Test
    void mentioningOrNegatingTheBasisIsInsufficientWithLowerSeverityAndHalfTheWeight() {
        for (String texto : List.of("Legitimación: la indicada en cada caso.", "La base jurídica no es el interés legítimo.")) {
            var r = analizar(texto, PRIVACIDAD);
            assertThat(errores(r, "R05")).as(texto).singleElement().satisfies(e -> {
                assertThat(e.getEstado()).isEqualTo(INSUFICIENTE);
                assertThat(e.getTitulo()).isEqualTo("Base legal del tratamiento mencionada sin identificarla");
                assertThat(e.getSeveridad()).isEqualTo("MEDIA");
                assertThat(e.getEvidencia()).isEqualTo("«" + texto + "»");
            });
        }
        // R05 weighs 8: a mention earns 4, the named basis 8; the rest of the text is identical.
        assertThat(puntos("Legitimación: la indicada en cada caso.")).isEqualTo(4);
        assertThat(puntos("Legitimación: la ejecución del contrato.")).isEqualTo(8);
    }

    // ---- R06 Derechos --------------------------------------------------------------------

    @Test
    void rightsAreCountedByNameWithWholeWordsAndInAListUnderAHeading() {
        String lista = String.join(System.lineSeparator(), "Sus derechos:", "- Acceso a sus datos.", "- Rectificación.",
                "- Supresión.", "- Oposición.");
        var e = evaluacion(lista, PRIVACIDAD, "R06");
        assertThat(e.estado()).isEqualTo(SUFICIENTE);
        assertThat(e.detalle()).isEqualTo("Derechos informados: acceso, rectificación, supresión, oposición. No se mencionan: "
                + "limitación del tratamiento, portabilidad (pueden no aplicarse a todos los tratamientos).");
        // v1: «barco» contains «arco» and «control de acceso» is not a right.
        assertThat(evaluacion("El barco sale mañana.", PRIVACIDAD, "R06").estado()).isEqualTo(POSIBLE_INCIDENCIA);
        assertThat(evaluacion("Aplicamos control de acceso y cifrado.", PRIVACIDAD, "R06").estado()).isEqualTo(POSIBLE_INCIDENCIA);
    }

    @Test
    void aFewOrGenericRightsAreInsufficient() {
        var parcial = analizar("Puede ejercer el derecho de acceso escribiendo a privacidad@example.invalid.", PRIVACIDAD);
        assertThat(errores(parcial, "R06")).singleElement().satisfies(e -> {
            assertThat(e.getEstado()).isEqualTo(INSUFICIENTE);
            assertThat(e.getSeveridad()).isEqualTo("MEDIA");
            assertThat(e.getDescripcion()).contains("Derechos informados: acceso. No se mencionan: rectificación");
        });
        // v1: «derechos ARCO» satisfied R06 without naming any right.
        assertThat(evaluacion("Puede ejercer sus derechos ARCO.", PRIVACIDAD, "R06").estado()).isEqualTo(INSUFICIENTE);
    }

    @Test
    void aDenialIsAPossibleIncidentButALegitimateConditionIsNot() {
        // v1: «No puede ejercer sus derechos» satisfied R06 with its full weight.
        var negado = analizar("No atenderemos solicitudes de supresión de datos.", PRIVACIDAD);
        assertThat(errores(negado, "R06")).singleElement().satisfies(e -> {
            assertThat(e.getEstado()).isEqualTo(POSIBLE_INCIDENCIA);
            assertThat(e.getTitulo()).isEqualTo(RIESGO + "Derechos del interesado (ARSULIPO) no informados");
            assertThat(e.getSeveridad()).isEqualTo("ALTA");
            assertThat(e.getEvidencia()).isEqualTo("«No atenderemos solicitudes de supresión de datos.»");
        });
        String derechos = "Tiene derecho de acceso, rectificación, supresión, limitación, portabilidad y oposición. ";
        assertThat(evaluacion(derechos + "No atenderemos solicitudes de acceso sin acreditar su identidad.", PRIVACIDAD, "R06")
                .estado()).isEqualTo(SUFICIENTE);
        // Contradictory text: the rights are listed and then one is denied — half of the 8 points.
        String contradictorio = derechos + "No puede ejercer el derecho de oposición.";
        assertThat(evaluacion(contradictorio, PRIVACIDAD, "R06").estado()).isEqualTo(POSIBLE_INCIDENCIA);
        assertThat(puntos(contradictorio)).isEqualTo(puntos(derechos) - 4);
    }

    // ---- R07 Conservación ----------------------------------------------------------------

    @Test
    void aPeriodOrALegalCriterionIsSufficientAndVagueWordingIsNot() {
        assertThat(evaluacion("Conservaremos sus datos durante cinco años.", PRIVACIDAD, "R07").estado()).isEqualTo(SUFICIENTE);
        assertThat(evaluacion("Sus datos se conservarán mientras dure la relación contractual.", PRIVACIDAD, "R07").estado())
                .isEqualTo(SUFICIENTE);
        // A maximum stays a period even when negated.
        assertThat(evaluacion("No conservaremos sus datos más de dos años.", PRIVACIDAD, "R07").estado()).isEqualTo(SUFICIENTE);
        var vago = analizar("Conservaremos sus datos el tiempo necesario.", PRIVACIDAD);
        assertThat(errores(vago, "R07")).singleElement().satisfies(e -> {
            assertThat(e.getEstado()).isEqualTo(INSUFICIENTE);
            assertThat(e.getSeveridad()).isEqualTo("BAJA");
        });
        // R07 weighs 5: vague 2, concrete 5.
        assertThat(puntos("Conservaremos sus datos durante cinco años.") - puntos("Conservaremos sus datos el tiempo necesario."))
                .isEqualTo(3);
        // v1: «10 años» satisfied R07 although it is not about the data.
        assertThat(evaluacion("Tenemos 10 años de experiencia.", PRIVACIDAD, "R07").estado()).isEqualTo(POSIBLE_INCIDENCIA);
    }

    @Test
    void indefiniteRetentionIsAPossibleIncidentOnlyWhenAffirmedAboutTheData() {
        var indefinida = analizar("Conservaremos sus datos indefinidamente.", PRIVACIDAD);
        assertThat(errores(indefinida, "R07")).singleElement().satisfies(e -> {
            assertThat(e.getTitulo()).startsWith(RIESGO);
            assertThat(e.getEstado()).isEqualTo(POSIBLE_INCIDENCIA);
        });
        assertThat(errores(indefinida, "G02")).singleElement()
                .satisfies(e -> assertThat(e.getEvidencia()).isEqualTo("«Conservaremos sus datos indefinidamente.»"));
        // v1: both were flagged as R07 risk and G02.
        var negada = analizar("Plazo de conservación: cinco años. No conservamos los datos indefinidamente.", PRIVACIDAD);
        assertThat(errores(negada, "R07")).isEmpty();
        assertThat(errores(negada, "G02")).isEmpty();
        assertThat(errores(analizar("La oferta es válida indefinidamente.", PRIVACIDAD), "G02")).isEmpty();
        // A negation stops at a coordination or a comma: only the sharing is negated here.
        var coordinada = analizar("No cedemos datos y conservamos indefinidamente.", PRIVACIDAD);
        assertThat(errores(coordinada, "R07")).singleElement().satisfies(e -> assertThat(e.getTitulo()).startsWith(RIESGO));
        assertThat(errores(coordinada, "G02")).hasSize(1);
        assertThat(errores(analizar("No cedemos datos, conservamos indefinidamente.", PRIVACIDAD), "G02")).hasSize(1);
    }

    // ---- R08 Destinatarios y G01 ---------------------------------------------------------

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            No compartimos sus datos con terceros.                                   | El documento declara que no comunica los datos a terceros.
            No cederemos sus datos a terceros, salvo obligación legal.               | El documento declara que no comunica los datos a terceros.
            No vendemos sus datos.                                                   | El documento declara que no comunica los datos a terceros.
            Comunicamos sus datos a la Agencia Tributaria y a entidades bancarias.   | Destinatarios o categorías identificados: agencia tributaria, entidades bancarias.
            Cederemos sus datos a las autoridades cuando lo exija la ley.            | Destinatarios o categorías identificados: autoridades.
            """)
    void negatedOrQualifiedDisclosuresInformTheRecipientsWithoutRisk(String texto, String detalle) {
        var r = analizar(texto, PRIVACIDAD);
        assertThat(r.getEvaluaciones()).filteredOn(e -> e.ruleId().equals("R08")).singleElement().satisfies(e -> {
            assertThat(e.estado()).isEqualTo(SUFICIENTE);
            assertThat(e.detalle()).isEqualTo(detalle);
        });
        // v1: «cederemos sus datos» and «vendemos sus datos» were found inside the negations (R08 risk and G01).
        assertThat(errores(r, "R08")).isEmpty();
        assertThat(errores(r, "G01")).isEmpty();
    }

    @Test
    void unnamedThirdPartiesAreInsufficientAndASaleIsAPossibleIncident() {
        var terceros = analizar("Sus datos podrán comunicarse a terceros.", PRIVACIDAD);
        assertThat(errores(terceros, "R08")).singleElement().satisfies(e -> {
            assertThat(e.getEstado()).isEqualTo(INSUFICIENTE);
            assertThat(e.getTitulo()).isEqualTo("Destinatarios de los datos mencionados sin identificarlos");
            assertThat(e.getSeveridad()).isEqualTo("BAJA");
        });
        var venta = analizar("Vendemos sus datos a anunciantes.", PRIVACIDAD);
        assertThat(errores(venta, "R08")).singleElement().satisfies(e -> {
            assertThat(e.getTitulo()).isEqualTo(RIESGO + "Destinatarios o cesiones de datos no informadas");
            assertThat(e.getSeveridad()).isEqualTo("ALTA");
        });
        assertThat(errores(venta, "G01")).hasSize(1);
        // A mention of providers that is not about personal data is no recipient information.
        assertThat(evaluacion("Trabajamos con terceros proveedores de confianza.", PRIVACIDAD, "R08").estado())
                .isEqualTo(POSIBLE_INCIDENCIA);
    }

    // ---- R03 Delegado de Protección de Datos ---------------------------------------------

    @Test
    void theDpoIsSufficientWithAContactOrAStatementThatThereIsNone() {
        assertThat(evaluacion("Delegado de Protección de Datos: dpo@example.invalid.", PRIVACIDAD, "R03").estado())
                .isEqualTo(SUFICIENTE);
        var noDesignado = evaluacion("No estamos obligados a designar un delegado de protección de datos.", PRIVACIDAD, "R03");
        assertThat(noDesignado.estado()).isEqualTo(SUFICIENTE);
        assertThat(noDesignado.detalle()).contains("no permite comprobar si su designación es obligatoria");
        var sinContacto = analizar("Hemos designado un DPO.", PRIVACIDAD);
        assertThat(errores(sinContacto, "R03")).singleElement().satisfies(e -> {
            assertThat(e.getEstado()).isEqualTo(INSUFICIENTE);
            assertThat(e.getSeveridad()).isEqualTo("BAJA");
        });
    }

    @Test
    void aMissingDpoIsNotVerifiableRatherThanABreach() {
        // v1: a MEDIA finding "DPO ausente" although nobody knows whether one is mandatory.
        var r = analizar("Responsable del tratamiento: Ejemplo S.L.", PRIVACIDAD);
        assertThat(errores(r, "R03")).isEmpty();
        assertThat(r.getEvaluaciones()).filteredOn(e -> e.ruleId().equals("R03")).singleElement()
                .satisfies(e -> assertThat(e.estado()).isEqualTo(NO_VERIFICABLE));
        assertThat(r.getFaltantes()).anyMatch(f -> f.startsWith("Datos de contacto del Delegado de Protección de Datos (si procede): ")
                && f.contains("Art. 37 RGPD"));
        assertThat(r.getRecomendaciones()).contains("Indicar si se ha designado DPO y sus datos de contacto. Si no aplica, indicarlo expresamente.");
        assertThat(r.getRiesgos()).noneMatch(x -> x.contains("Delegado"));
    }

    // ---- Applicability, wrong category, score --------------------------------------------

    @Test
    void privacyInformationIsNotDemandedFromOtherDocumentTypesAndDoesNotInflateTheScore() {
        String aviso = "Titular: Ejemplo S.L., con NIF B12345678 y domicilio en Madrid.";
        var r = analizar(aviso, "Aviso Legal");
        // Rules v4: only R16 (the provider's own data) is asked of a legal notice; rules v5 add R13, R14, R17, R18
        // and R19 to the not applicable ones.
        assertThat(r.getEvaluaciones()).filteredOn(e -> !e.ruleId().equals("R16"))
                .extracting(EvaluacionRegla::estado).containsOnly(NO_APLICABLE);
        assertThat(r.getErrores()).extracting(ErrorAuditoria::getRuleId).doesNotContain("R01", "R03", "R04", "R05", "R06",
                "R07", "R08", "R09", "R11");
        assertThat(r.getResumen()).contains("18 no aplicables a este tipo de documento");
        // Not applicable earns nothing, exactly like the missing clause: no silent change of the score.
        assertThat(r.getDesglosePuntuacion().controles()).filteredOn(c -> c.estado() == NO_APLICABLE)
                .allSatisfy(c -> assertThat(c.puntos()).isZero());
        // Only R16, half of 7: identity, NIF and address, without registry data or e-mail.
        assertThat(r.getPuntuacionRiesgo()).isEqualTo(3);
        assertThat(evaluacion(aviso, null, "R05").estado()).isEqualTo(POSIBLE_INCIDENCIA);
    }

    @Test
    void aWronglyCategorisedDocumentIsStillReadForWhatItSays() {
        String politica = "La base legal del tratamiento es su consentimiento. Vendemos sus datos a anunciantes.";
        var r = analizar(politica, "Términos y Condiciones");
        assertThat(evaluacion(politica, "Términos y Condiciones", "R05").estado()).isEqualTo(SUFICIENTE);
        // Problematic content is reported in any document.
        assertThat(errores(r, "R08")).singleElement().satisfies(e -> assertThat(e.getTitulo()).startsWith(RIESGO));
        // And a privacy policy that only carries legal-notice content misses the privacy information.
        assertThat(evaluacion("Titular: Ejemplo S.L.", PRIVACIDAD, "R08").estado()).isEqualTo(POSIBLE_INCIDENCIA);
    }

    // ---- Extracted HTML, history -----------------------------------------------------------

    @Test
    void evidenceComesFromTheVisibleTextOfHostileHtmlOnly() {
        String html = """
                <h1>Política de privacidad</h1>
                <script>document.write('No vendemos sus datos.')</script>
                <p hidden>No cedemos sus datos a terceros.</p>
                <p>Vendemos sus datos <img src=x onerror="alert(1)">a anunciantes.</p>""";
        String texto = new ExtractorTextoHtml().extraer(html);
        var r = analizar(texto, PRIVACIDAD);
        assertThat(errores(r, "R08")).singleElement().satisfies(e ->
                assertThat(e.getEvidencia()).isEqualTo("«Vendemos sus datos a anunciantes.»"));
        assertThat(r.getEvaluaciones()).allSatisfy(e -> assertThat(String.valueOf(e.evidencia())).doesNotContain("<", "alert"));
    }

    @Test
    void historicalResultsWithoutStatesStayAsTheyWereAndNewOnesCarryThem() throws Exception {
        var mapper = new ObjectMapper();
        String historico = """
                {"resumen":"Histórico","puntuacionRiesgo":61,"errores":[{"ruleId":"R03","motor":"LEGAL_TEXT","version":"1",
                "titulo":"Información sobre el Delegado de Protección de Datos (DPO) ausente","descripcion":"d","severidad":"MEDIA",
                "evidencia":"Cláusula no detectada en el documento analizado.","impacto":"i","accion":"a"}]}""";
        var leido = mapper.readValue(historico, ResultadoAuditoria.class);
        assertThat(leido.getErrores()).singleElement().satisfies(e -> assertThat(e.getEstado()).isNull());
        assertThat(leido.getEvaluaciones()).isEmpty();
        String reescrito = mapper.writeValueAsString(leido);
        assertThat(reescrito).doesNotContain("estado", "evaluaciones");

        var nuevo = analizar("Hemos designado un DPO.", PRIVACIDAD);
        String json = mapper.writeValueAsString(nuevo);
        assertThat(json).contains("\"evaluaciones\"", "\"estado\":\"INSUFICIENTE\"");
        assertThat(mapper.readValue(json, ResultadoAuditoria.class)).isEqualTo(nuevo);
        assertThat(motor.version()).isEqualTo(new VersionAnalizador("3", "5"));
        assertThat(nuevo.getErrores()).allSatisfy(e -> assertThat(e.getVersion()).isEqualTo("5"));
    }
}
