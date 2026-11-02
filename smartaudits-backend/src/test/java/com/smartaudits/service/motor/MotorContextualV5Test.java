package com.smartaudits.service.motor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartaudits.model.dto.ResultadoAuditoria;
import com.smartaudits.model.dto.ResultadoAuditoria.ErrorAuditoria;
import com.smartaudits.model.dto.ResultadoAuditoria.EvaluacionRegla;
import com.smartaudits.model.dto.ResultadoAuditoria.MetodoControl;
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
 * Rules v5 (R13, R14, R17, R18, R19), one small text per behaviour, expectations written by hand.
 * "v4:" records what the keyword rules did with the same text.
 */
class MotorContextualV5Test {

    private static final String PRIVACIDAD = "Política de Privacidad";
    private static final String RIESGO = "Cláusula presente pero con redacción problemática: ";
    private static final List<String> LOTE = List.of("R13", "R14", "R17", "R18", "R19");
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

    /** Points the rule itself earned, read from the score breakdown. */
    private int puntos(String texto, String tipo, String ruleId) {
        return analizar(texto, tipo).getDesglosePuntuacion().controles().stream().filter(c -> c.ruleId().equals(ruleId))
                .findFirst().orElseThrow().puntos();
    }

    /** A sufficient clause earns the rule's full weight and is quoted as evidence, with no finding. */
    private void suficiente(String texto, String ruleId, int peso, String evidencia, String detalle) {
        var r = analizar(texto, PRIVACIDAD);
        var e = evaluacion(texto, PRIVACIDAD, ruleId);
        assertThat(e.estado()).isEqualTo(SUFICIENTE);
        assertThat(e.evidencia()).isEqualTo("«" + evidencia + "»");
        assertThat(e.detalle()).startsWith(detalle);
        assertThat(errores(r, ruleId)).isEmpty();
        assertThat(puntos(texto, PRIVACIDAD, ruleId)).isEqualTo(peso);
    }

    /** A mere mention earns half the weight and one finding, a level below the rule's severity. */
    private void insuficiente(String texto, String ruleId, int puntos, String titulo, String detalle) {
        var r = analizar(texto, PRIVACIDAD);
        assertThat(errores(r, ruleId)).singleElement().satisfies(e -> {
            assertThat(e.getEstado()).isEqualTo(INSUFICIENTE);
            assertThat(e.getTitulo()).isEqualTo(titulo);
            assertThat(e.getSeveridad()).isEqualTo("BAJA");
            assertThat(e.getDescripcion()).endsWith(detalle);
        });
        assertThat(puntos(texto, PRIVACIDAD, ruleId)).isEqualTo(puntos);
    }

    /** Problematic wording: a possible incident of ALTA severity that quotes the clause. */
    private void problematica(String texto, String ruleId, String detalle) {
        var r = analizar(texto, PRIVACIDAD);
        assertThat(errores(r, ruleId)).singleElement().satisfies(e -> {
            assertThat(e.getEstado()).isEqualTo(POSIBLE_INCIDENCIA);
            assertThat(e.getTitulo()).startsWith(RIESGO);
            assertThat(e.getSeveridad()).isEqualTo("ALTA");
            assertThat(e.getEvidencia()).isEqualTo("«" + texto + "»");
            assertThat(e.getDescripcion()).endsWith(detalle);
        });
        assertThat(puntos(texto, PRIVACIDAD, ruleId)).isZero();
    }

    /** Nothing in the text establishes the practice: no finding, no points, listed as not verifiable. */
    private void noVerificable(String texto, String ruleId) {
        var r = analizar(texto, PRIVACIDAD);
        assertThat(evaluacion(texto, PRIVACIDAD, ruleId).estado()).isEqualTo(NO_VERIFICABLE);
        assertThat(errores(r, ruleId)).isEmpty();
        assertThat(puntos(texto, PRIVACIDAD, ruleId)).isZero();
    }

    // ---- R13 Menores ---------------------------------------------------------------------------

    @Test
    void anAgeLimitOrParentalConsentIsSufficient() {
        suficiente("Los menores de 14 años solo pueden registrarse con el consentimiento de sus padres o tutores.", "R13", 3,
                "Los menores de 14 años solo pueden registrarse con el consentimiento de sus padres o tutores.",
                "Se indican condiciones para los datos de menores: edad mínima de 14 años y consentimiento de sus padres o tutores.");
        suficiente("Para crear una cuenta debe tener al menos 16 años.", "R13", 3,
                "Para crear una cuenta debe tener al menos 16 años.",
                "Se indican condiciones para los datos de menores: edad mínima de 16 años.");
        // «sin» is negated by the earlier «No»: parental consent is required.
        suficiente("No recogemos datos de menores de 14 años sin el consentimiento de sus padres.", "R13", 3,
                "No recogemos datos de menores de 14 años sin el consentimiento de sus padres.",
                "Se indican condiciones para los datos de menores: edad mínima de 14 años y consentimiento");
        suficiente("Nuestros servicios no están dirigidos a menores de edad y no recogemos datos de menores.", "R13", 3,
                "Nuestros servicios no están dirigidos a menores de edad y no recogemos datos de menores.",
                "El documento declara que no se dirige a menores o que no trata sus datos; el texto no permite comprobarlo.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Podremos introducir cambios menores en esta política de privacidad.",
            "Ofrecemos ropa y juguetes para niños de todas las edades.",
            "Los menores de 16 años deben ir acompañados de un adulto en la tienda.",
            "Conservaremos sus datos al menos 5 años.", "Menores."})
    void aMentionOfChildrenIsNoProcessingOfTheirData(String texto) {
        // v4: «menores» as a substring earned 3 points («cambios menores», «menores de 16 años … acompañados»).
        noVerificable(texto, "R13");
        assertThat(evaluacion(texto, PRIVACIDAD, "R13").detalle()).isEqualTo("El documento no indica si se tratan datos de "
                + "menores; el texto no permite saber si el servicio se dirige a ellos ni si algún tratamiento se basa en su "
                + "consentimiento.");
    }

    @Test
    void processingMinorsDataWithoutConditionsIsInsufficient() {
        insuficiente("También tratamos datos de menores.", "R13", 1,
                "Tratamiento de datos de menores descrito sin edad mínima ni consentimiento parental",
                "Se describe el tratamiento de datos de menores sin indicar la edad mínima ni el consentimiento de sus padres "
                        + "o tutores.");
    }

    @Test
    void describedProcessingOutweighsAContraryStatementWhichIsReported() {
        String texto = "Este servicio no está dirigido a menores. Los niños pueden crear una cuenta y facilitarnos sus datos.";
        var e = evaluacion(texto, PRIVACIDAD, "R13");
        assertThat(e.estado()).isEqualTo(INSUFICIENTE);
        assertThat(e.evidencia()).isEqualTo("«Los niños pueden crear una cuenta y facilitarnos sus datos.»");
        assertThat(e.detalle()).endsWith("El documento también declara lo contrario: «Este servicio no está dirigido a menores.».");
    }

    @Test
    void consentBelowFourteenOrWithoutParentsIsAPossibleIncident() {
        problematica("Los mayores de 13 años pueden registrarse y prestar su consentimiento.", "R13",
                "El texto fija en 13 años la edad a partir de la cual se admite el registro o el consentimiento del menor; "
                        + "en España el menor solo puede consentir por sí mismo desde los 14 años (Art. 7 LOPDGDD).");
        problematica("Los menores de 14 años pueden registrarse sin el consentimiento de sus padres.", "R13",
                "lo que puede ser contrario al Art. 8 RGPD y al Art. 7 LOPDGDD si el tratamiento se basa en su consentimiento.");
        // v4: the risk pattern earned half the weight; the clause offers no condition at all.
        problematica("Menores: no verificamos la edad de los usuarios.", "R13",
                "el Art. 8.2 RGPD exige esfuerzos razonables para verificar el consentimiento de sus padres o tutores.");
    }

    @Test
    void aMinorsHeadingLetsTheNextClauseSetTheLimit() {
        var e = evaluacion("Menores\n\nDebe tener 14 años o más para usar este servicio.", PRIVACIDAD, "R13");
        assertThat(e.estado()).isEqualTo(SUFICIENTE);
        assertThat(e.evidencia()).isEqualTo("«Debe tener 14 años o más para usar este servicio.»");
    }

    // ---- R14 Categorías especiales -----------------------------------------------------------------

    @Test
    void aStatedArticle9ExceptionOrAnExplicitAbsenceIsSufficient() {
        suficiente("Solo tratamos datos de salud con su consentimiento explícito, para gestionar su cita médica.", "R14", 3,
                "Solo tratamos datos de salud con su consentimiento explícito, para gestionar su cita médica.",
                "Categorías especiales: salud. Excepción del Art. 9.2 RGPD indicada: consentimiento explícito. El texto no "
                        + "acredita que la excepción se aplique ni que las garantías estén implantadas.");
        suficiente("Tratamos datos sobre su salud para prestarle asistencia. La base jurídica es la prestación de asistencia "
                        + "sanitaria (art. 9.2.h RGPD).", "R14", 3,
                "Tratamos datos sobre su salud para prestarle asistencia. La base jurídica es la prestación de asistencia "
                        + "sanitaria (art. 9.2.h RGPD).",
                "Categorías especiales: salud. Excepción del Art. 9.2 RGPD indicada: asistencia sanitaria, Art. 9.2 RGPD.");
        suficiente("No tratamos categorías especiales de datos.", "R14", 3, "No tratamos categorías especiales de datos.",
                "El documento declara que no trata categorías especiales de datos; el texto no permite comprobarlo.");
        suficiente("No recogemos datos de salud salvo con su consentimiento explícito.", "R14", 3,
                "No recogemos datos de salud salvo con su consentimiento explícito.",
                "Categorías especiales: salud. Excepción del Art. 9.2 RGPD indicada: consentimiento explícito.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Vendemos productos de salud y bienestar.",
            "Respetamos todas las creencias religiosas y no discriminamos por origen étnico.",
            "Según el artículo 9 del RGPD, son categorías especiales los datos de salud o los datos biométricos.",
            "Tratamos a todos los clientes por igual, sin importar su religión."})
    void sensitiveTopicsAndDefinitionsAreNoProcessing(String texto) {
        // v4: «creencias religiosas», «religion», «datos de salud» or «articulo 9» earned the 3 points.
        noVerificable(texto, "R14");
        assertThat(evaluacion(texto, PRIVACIDAD, "R14").detalle()).isEqualTo("El documento no indica si se tratan "
                + "categorías especiales de datos (Art. 9 RGPD); puede que no se traten, y el texto no permite saberlo.");
    }

    @Test
    void processingOrSafeguardsWithoutAnExceptionAreInsufficient() {
        String titulo = "Categorías especiales de datos mencionadas sin la excepción que permite tratarlas";
        // v4: «alergias» was no keyword, so a real health processing earned nothing and raised a finding.
        insuficiente("Recogemos información sobre alergias alimentarias de nuestros clientes.", "R14", 1, titulo,
                "Se menciona el tratamiento de categorías especiales (salud) sin indicar la excepción del Art. 9.2 RGPD que "
                        + "lo permite.");
        // Stated safeguards are reported as stated, never as implemented.
        insuficiente("Los datos biométricos se protegen con cifrado y acceso restringido.", "R14", 1, titulo,
                "Garantías declaradas: cifrado, acceso restringido. Declararlas no acredita que estén implantadas.");
    }

    @Test
    void sensitiveDataWithoutConsentOrForAnyPurposeIsAPossibleIncident() {
        problematica("Podremos tratar sus datos de salud sin su consentimiento.", "R14",
                "El texto declara tratar categorías especiales de datos sin consentimiento ni otra excepción del Art. 9.2 "
                        + "RGPD, para su venta o para cualquier finalidad.");
        problematica("Usaremos sus datos biométricos para cualquier finalidad.", "R14", "para su venta o para cualquier finalidad.");
    }

    @Test
    void aDisclosedProcessingOutweighsADenial() {
        var e = evaluacion("No tratamos datos de salud. Recogemos datos de salud de los pacientes para gestionar sus citas.",
                PRIVACIDAD, "R14");
        assertThat(e.estado()).isEqualTo(INSUFICIENTE);
        assertThat(e.detalle()).endsWith("El documento también declara lo contrario: «No tratamos datos de salud.».");
    }

    // ---- R17 Decisiones automatizadas -----------------------------------------------------------------

    @Test
    void meaningfulInformationOrAnExplicitAbsenceIsSufficient() {
        suficiente("No tomamos decisiones basadas únicamente en el tratamiento automatizado de sus datos.", "R17", 4,
                "No tomamos decisiones basadas únicamente en el tratamiento automatizado de sus datos.",
                "El documento declara que no adopta decisiones automatizadas o que no elabora perfiles");
        // v4: «perfiles comerciales» was no keyword; this real profiling earned nothing.
        suficiente("Elaboramos perfiles comerciales a partir de su historial de compras para enviarle ofertas personalizadas. "
                        + "Puede oponerse en cualquier momento.", "R17", 4,
                "Elaboramos perfiles comerciales a partir de su historial de compras para enviarle ofertas personalizadas. "
                        + "Puede oponerse en cualquier momento.",
                "Se informa de decisiones automatizadas o perfilado: lógica aplicada, consecuencias, garantías del interesado.");
        // «sin intervención humana» describes the decision; it is no safeguard.
        suficiente("Un sistema automático decide, sin intervención humana, si se concede el préstamo según su puntuación "
                        + "crediticia, lo que puede suponer la denegación.", "R17", 4,
                "Un sistema automático decide, sin intervención humana, si se concede el préstamo según su puntuación "
                        + "crediticia, lo que puede suponer la denegación.",
                "Se informa de decisiones automatizadas o perfilado: lógica aplicada, consecuencias; falta: garantías del "
                        + "interesado.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Nuestro buscador utiliza algoritmos e inteligencia artificial para ordenar los resultados.",
            "Sus datos se incorporarán a un fichero automatizado y serán objeto de tratamiento automatizado."})
    void algorithmsOrComputerisedProcessingAreNoAutomatedDecision(String texto) {
        // v4: «algoritmo», «inteligencia artificial» or «tratamiento automatizado» earned the 4 points.
        noVerificable(texto, "R17");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            Utilizamos decisiones automatizadas para evaluar las solicitudes.  | Se informa de: nada concreto; falta: lógica aplicada, consecuencias, garantías del interesado.
            Las decisiones automatizadas están sujetas a intervención humana.  | Se informa de: garantías del interesado; falta: lógica aplicada, consecuencias.
            """)
    void aPartialDisclosureIsInsufficient(String texto, String detalle) {
        insuficiente(texto, "R17", 2, "Decisiones automatizadas o perfilado informados de forma incompleta", detalle);
    }

    @Test
    void denyingTheRightToContestIsAPossibleIncident() {
        // v4: neither «automáticamente» nor «impugnar» was a keyword.
        problematica("Las solicitudes de financiación se rechazan automáticamente y no podrá impugnar la decisión.", "R17",
                "El texto impide oponerse, impugnar o pedir la revisión de decisiones automatizadas, lo que puede ser "
                        + "contrario al Art. 22.3 RGPD.");
    }

    // ---- R18 Brechas de seguridad -----------------------------------------------------------------

    @Test
    void notificationInformationIsSufficientWithoutProvingIt() {
        String texto = "Si se produce una brecha de seguridad que afecte a sus datos, la notificaremos a la AEPD en un plazo "
                + "de 72 horas y le informaremos cuando suponga un alto riesgo.";
        suficiente(texto, "R18", 3, texto, "Se informa de la notificación de brechas de seguridad (destinatarios: autoridad "
                + "de control, personas afectadas), con un plazo. El texto no acredita que exista un procedimiento ni que se "
                + "aplique.");
        // Not notifying breaches unlikely to be a risk is what Art. 33.1 RGPD allows.
        assertThat(evaluacion("No notificaremos las brechas de seguridad que sea improbable que supongan un riesgo para sus "
                + "derechos; las demás se comunicarán a la AEPD.", PRIVACIDAD, "R18").estado()).isEqualTo(SUFICIENTE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Nos tomamos muy en serio la seguridad y protegemos sus datos frente a accesos no autorizados.",
            "Nuestro proyecto ayuda a reducir la brecha digital en zonas rurales.", "Los pedidos se entregan en 72 horas."})
    void vagueSecurityOrUnrelatedWordsSayNothingAboutBreaches(String texto) {
        // v4: «72 horas» earned the 3 points.
        noVerificable(texto, "R18");
        assertThat(evaluacion(texto, PRIVACIDAD, "R18").detalle()).startsWith("El documento no informa sobre la "
                + "notificación de brechas de seguridad. Publicarla no es una obligación de información");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Ante una brecha de seguridad adoptaremos las medidas necesarias para minimizar sus efectos.",
            "En marzo de 2024 sufrimos un incidente de seguridad que afectó a direcciones de correo electrónico."})
    void aBreachMentionWithoutNotificationIsInsufficientAndInfersNothing(String texto) {
        insuficiente(texto, "R18", 1, "Brechas de seguridad mencionadas sin indicar su notificación",
                "Mencionarlas no implica que se haya producido ninguna.");
    }

    @Test
    void refusingToNotifyIsAPossibleIncident() {
        problematica("No notificaremos a los usuarios las brechas de seguridad.", "R18",
                "El texto declara que no se notificarán las brechas de seguridad, sin limitarlo a los casos en que los "
                        + "Arts. 33.1 y 34.3 RGPD lo permiten.");
    }

    // ---- R19 Comunicaciones comerciales ----------------------------------------------------------------

    @Test
    void aBasisAndAWayToUnsubscribeAreSufficient() {
        String consentimiento = "Solo le enviaremos comunicaciones comerciales por correo electrónico si nos da su "
                + "consentimiento, y podrá darse de baja en cualquier momento desde el enlace incluido en cada mensaje.";
        suficiente(consentimiento, "R19", 3, consentimiento,
                "Se informa de las comunicaciones comerciales y de cómo darse de baja. Se basan en: consentimiento.");
        // v4: none of these words was a keyword; existing customers are the Art. 21.2 LSSI-CE exception.
        suficiente("A nuestros clientes podemos enviarles por correo electrónico ofertas de productos similares a los que "
                        + "contrataron; pueden oponerse en cualquier momento.", "R19", 3,
                "A nuestros clientes podemos enviarles por correo electrónico ofertas de productos similares a los que "
                        + "contrataron;",
                "Se informa de las comunicaciones comerciales y de cómo darse de baja. Se basan en: relación contractual "
                        + "previa (Art. 21.2 LSSI-CE).");
        suficiente("No enviamos comunicaciones comerciales.", "R19", 3, "No enviamos comunicaciones comerciales.",
                "El documento declara que no envía comunicaciones comerciales; el texto no permite comprobarlo.");
    }

    @Test
    void legitimateInterestByEmailCarriesAnLssiNote() {
        var e = evaluacion("Le enviaremos ofertas por correo electrónico sobre la base de nuestro interés legítimo, y puede "
                + "darse de baja en cualquier momento.", PRIVACIDAD, "R19");
        assertThat(e.estado()).isEqualTo(SUFICIENTE);
        assertThat(e.detalle()).contains("Se basan en: interés legítimo. Por medios electrónicos, el Art. 21 LSSI-CE exige "
                + "consentimiento previo salvo relación contractual previa y productos o servicios similares; el texto no "
                + "permite saber cuál se aplica.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Le enviaremos un correo electrónico para confirmar su pedido y avisarle del envío.",
            "Puede oponerse al tratamiento de sus datos.", "Mostramos publicidad de terceros en nuestra web."})
    void transactionalMessagesObjectionsAndOnSiteAdsAreNoMarketingCommunications(String texto) {
        // v4: «puede oponerse» and «publicidad» earned the 3 points.
        noVerificable(texto, "R19");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            Le enviaremos nuestra newsletter con novedades y ofertas.  | Se informa de comunicaciones comerciales sin indicar en qué se basan ni cómo darse de baja.
            No le enviaremos publicidad sin su consentimiento.          | Se informa de comunicaciones comerciales sin indicar cómo darse de baja.
            """)
    void marketingWithoutBasisOrUnsubscribeIsInsufficient(String texto, String detalle) {
        // v4: the second one matched the risk pattern «enviaremos publicidad sin su consentimiento», ignoring the «No».
        insuficiente(texto, "R19", 1, "Comunicaciones comerciales informadas de forma incompleta", detalle);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Le enviaremos publicidad sin su consentimiento.",
            "No puede darse de baja de nuestras comunicaciones comerciales.",
            "Al registrarse acepta recibir nuestras comunicaciones comerciales."})
    void marketingWithoutConsentWithoutOptOutOrBundledIsAPossibleIncident(String texto) {
        problematica(texto, "R19", "lo que puede ser contrario a los Arts. 21 y 22 LSSI-CE y al Art. 21 RGPD.");
    }

    // ---- Shared: applicability, document types, hostile input and the score ----------------------------

    @ParameterizedTest
    @ValueSource(strings = {"Aviso Legal", "Política de Cookies", "Términos y Condiciones", "Condiciones de Uso",
            "Condiciones de Compra", "Accesibilidad"})
    void theFiveAreNotAskedOfDocumentsAboutSomethingElse(String tipo) {
        var r = analizar("Este sitio pertenece a Ejemplo.", tipo);
        assertThat(r.getEvaluaciones()).filteredOn(e -> LOTE.contains(e.ruleId())).hasSize(5)
                .allSatisfy(e -> assertThat(e.estado()).isEqualTo(NO_APLICABLE));
        assertThat(r.getErrores()).noneMatch(e -> LOTE.contains(e.getRuleId()));
        // A problematic statement is reported whatever the document type.
        assertThat(evaluacion("No puede darse de baja de nuestras comunicaciones comerciales.", tipo, "R19").estado())
                .isEqualTo(POSIBLE_INCIDENCIA);
    }

    @Test
    void anUnknownDocumentTypeIsEvaluatedLikeACompleteDocument() {
        for (String tipo : new String[]{null, "Documento Completo", PRIVACIDAD, "  Política de Privacidad  "}) {
            var r = analizar("Bienvenido.", tipo);
            assertThat(r.getEvaluaciones()).filteredOn(e -> LOTE.contains(e.ruleId())).hasSize(5)
                    .allSatisfy(e -> assertThat(e.estado()).as(String.valueOf(tipo)).isEqualTo(NO_VERIFICABLE));
            assertThat(r.getErrores()).noneMatch(e -> LOTE.contains(e.getRuleId()));
        }
        assertThat(evaluacion("No tratamos categorías especiales de datos.", null, "R14").estado()).isEqualTo(SUFICIENTE);
    }

    @Test
    void hostileMarkupIsAnalysedAsInertTextAndQuotedVerbatim() {
        String texto = "<p onclick=\"alert(1)\">No tratamos categorías especiales de datos.</p><script>enviar('publicidad')</script>";
        var r = analizar(texto, PRIVACIDAD);
        assertThat(evaluacion(texto, PRIVACIDAD, "R14").estado()).isEqualTo(SUFICIENTE);
        // The evidence is the clause exactly as received: escaping it is the presentation layer's job.
        assertThat(evaluacion(texto, PRIVACIDAD, "R14").evidencia()).isEqualTo("«" + texto + "»");
        assertThat(errores(r, "R19")).singleElement().satisfies(e -> assertThat(e.getEvidencia()).isEqualTo("«" + texto + "»"));
    }

    @Test
    void theWeightsAndTheAlgorithmAreUnchanged() {
        String texto = "Los menores de 14 años necesitan el consentimiento de sus padres o tutores para registrarse. "
                + "No tratamos categorías especiales de datos. No adoptamos decisiones basadas únicamente en un tratamiento "
                + "automatizado. Si se produce una brecha de seguridad, la notificaremos a la AEPD en 72 horas. Solo enviamos "
                + "la newsletter a quien se suscribe, y puede darse de baja en cualquier momento.";
        var d = analizar(texto, PRIVACIDAD).getDesglosePuntuacion();
        assertThat(d.controles()).filteredOn(c -> LOTE.contains(c.ruleId()))
                .extracting(c -> c.ruleId() + "=" + c.puntos() + "/" + c.peso())
                .containsExactly("R13=3/3", "R14=3/3", "R17=4/4", "R18=3/3", "R19=3/3");
        // R11 (AEPD without the right to complain) 2 and R12 (consent without how it is given or withdrawn) 3.
        assertThat(d.puntuacion()).isEqualTo(21);
        // Half of an odd weight is rounded down, as for every rule since rules v1.
        assertThat(puntos("También tratamos datos de menores.", PRIVACIDAD, "R13")).isEqualTo(1);
        assertThat(puntos("Utilizamos decisiones automatizadas para evaluar las solicitudes.", PRIVACIDAD, "R17")).isEqualTo(2);
    }

    @Test
    void everyControlIsContextualAndTheBreakdownSaysSo() {
        var r = analizar("Bienvenido.", PRIVACIDAD);
        var d = r.getDesglosePuntuacion();
        assertThat(d.controles()).hasSize(19).allSatisfy(c -> {
            assertThat(c.metodo()).isEqualTo(MetodoControl.CONTEXTUAL);
            assertThat(c.estado()).isNotNull();
        });
        assertThat(d.pesoPalabrasClave()).isZero();
        assertThat(d.puntosPalabrasClave()).isZero();
        // R03, R09, R10, R12, R15 and the five of the batch: not verifiable; R16: not applicable to a privacy policy.
        assertThat(d.pesoNoVerificable()).isEqualTo(4 + 4 + 7 + 6 + 4 + 3 + 3 + 4 + 3 + 3);
        assertThat(d.pesoNoAplicable()).isEqualTo(7);
        assertThat(d.pesoContextualEvaluado()).isEqualTo(100 - 41 - 7);
        // Full contextual coverage is not accuracy, and no adjusted or compliance score is produced.
        assertThat(r.getResumen()).contains("Información evaluada por contexto (R01 a R19)")
                .contains("La evaluación es automática y puede equivocarse aunque todos los controles se lean por contexto.")
                .doesNotContain("cumplimiento del", "% de cumplimiento");
    }

    @Test
    void storedRulesV4BreakdownsKeepTheirKeywordSplit() throws Exception {
        String v4 = """
                {"resumen":"Reglas v4","puntuacionRiesgo":13,"errores":[],
                "desglosePuntuacion":{"version":"desglose-1","puntuacion":13,"pesoTotal":100,"puntosAcreditados":13,
                "penalizacionRiesgos":0,"pesoNoAplicable":71,"pesoNoVerificable":0,"pesoContextualEvaluado":13,
                "puntosContextuales":13,"pesoPalabrasClave":16,"puntosPalabrasClave":0,"porcentajeContextual":100,
                "motivoSinPorcentaje":null,"controles":[{"ruleId":"R13","peso":3,"puntos":0,"metodo":"PALABRAS_CLAVE","estado":null},
                {"ruleId":"R16","peso":7,"puntos":7,"metodo":"CONTEXTUAL","estado":"SUFICIENTE"}]}}""";
        var mapper = new ObjectMapper();
        var leido = mapper.readValue(v4, ResultadoAuditoria.class);
        assertThat(leido.getPuntuacionRiesgo()).isEqualTo(13);
        assertThat(leido.getDesglosePuntuacion().pesoPalabrasClave()).isEqualTo(16);
        assertThat(leido.getDesglosePuntuacion().controles()).extracting(c -> c.metodo())
                .containsExactly(MetodoControl.PALABRAS_CLAVE, MetodoControl.CONTEXTUAL);
        assertThat(mapper.readTree(mapper.writeValueAsString(leido)).path("desglosePuntuacion"))
                .isEqualTo(mapper.readTree(v4).path("desglosePuntuacion"));
        assertThat(motor.version()).isEqualTo(new VersionAnalizador("3", "5"));
    }
}
