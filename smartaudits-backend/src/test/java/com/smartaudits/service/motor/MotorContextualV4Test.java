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
 * Rules v4 (R01, R04, R09, R11, R16), one small text per behaviour, expectations written by hand.
 * "v3:" records what the keyword rules did with the same text.
 */
class MotorContextualV4Test {

    private static final String PRIVACIDAD = "Política de Privacidad";
    private static final String AVISO = "Aviso Legal";
    private static final String COMPLETO = "Documento Completo";
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

    /** Points the rule itself earned, read from the score breakdown. */
    private int puntos(String texto, String tipo, String ruleId) {
        return analizar(texto, tipo).getDesglosePuntuacion().controles().stream().filter(c -> c.ruleId().equals(ruleId))
                .findFirst().orElseThrow().puntos();
    }

    // ---- R16 Aviso legal (Art. 10 LSSI-CE) -------------------------------------------------

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            Aplicamos cifrado de extremo a extremo y control de acceso a los datos.                   | Documento Completo | NO_VERIFICABLE
            Los datos se guardan cifrados.                                                             | Documento Completo | NO_VERIFICABLE
            Aplicamos cifrado de extremo a extremo y control de acceso a los datos.                   | Política de Privacidad | NO_APLICABLE
            Aviso legal. Usamos cifrado en todas las comunicaciones.                                   | Aviso Legal | POSIBLE_INCIDENCIA
            """)
    void cifradoIsNeverATaxIdentifier(String texto, String tipo, String estado) {
        // v3: «cif» inside «cifrado» accredited the 7 points of R16.
        assertThat(evaluacion(texto, tipo, "R16").estado().name()).isEqualTo(estado);
        assertThat(puntos(texto, tipo, "R16")).isZero();
    }

    @Test
    void aCompleteLegalNoticeIsSufficientWithoutClaimingTheDataAreTrue() {
        String texto = "Aviso legal. En cumplimiento del artículo 10 de la Ley 34/2002 (LSSI-CE): Ejemplo S.L., NIF B12345678, "
                + "domicilio social en calle Mayor 1, 28013 Madrid, inscrita en el Registro Mercantil de Madrid, tomo 1, folio 2. "
                + "Contacto: info@example.invalid.";
        var e = evaluacion(texto, AVISO, "R16");
        assertThat(e.estado()).isEqualTo(SUFICIENTE);
        assertThat(e.evidencia()).startsWith("«En cumplimiento del artículo 10");
        assertThat(e.detalle()).isEqualTo("Datos del prestador indicados: identidad, NIF, domicilio, datos registrales, "
                + "correo electrónico. El texto no permite comprobar que sean correctos ni si el titular presta servicios de "
                + "la sociedad de la información.");
        assertThat(puntos(texto, AVISO, "R16")).isEqualTo(7);
    }

    @Test
    void aSoleTraderNeedsNoRegistryData() {
        String texto = "Aviso legal. Titular: Ana Pérez García, NIF 12345678Z, con domicilio en calle Mayor 1, 28013 Madrid. "
                + "Correo: ana@example.invalid.";
        var e = evaluacion(texto, AVISO, "R16");
        assertThat(e.estado()).isEqualTo(SUFICIENTE);
        assertThat(e.detalle()).startsWith("Datos del prestador indicados: identidad, NIF, domicilio, correo electrónico.");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            Aviso legal. Titular del sitio web: Ejemplo S.L., NIF B12345678.                 | identidad, NIF; falta: domicilio, datos registrales, correo electrónico.
            Aviso legal. Ejemplo S.L.                                                         | identidad; falta: NIF, domicilio, datos registrales, correo electrónico.
            Aviso legal. NIF: B12345678.                                                      | NIF; falta: identidad, domicilio, correo electrónico.
            """)
    void aNameOrATaxIdentifierAloneIsNoCompleteLegalNotice(String texto, String detalle) {
        // v3: «nif», «razón social» or a single keyword accredited all 7 points.
        var r = analizar(texto, AVISO);
        assertThat(errores(r, "R16")).singleElement().satisfies(e -> {
            assertThat(e.getEstado()).isEqualTo(INSUFICIENTE);
            assertThat(e.getTitulo()).isEqualTo("Información del prestador (Art. 10 LSSI-CE) incompleta");
            assertThat(e.getSeveridad()).isEqualTo("MEDIA");
            assertThat(e.getDescripcion()).endsWith("Datos del prestador indicados: " + detalle);
        });
        assertThat(puntos(texto, AVISO, "R16")).isEqualTo(3);
    }

    @Test
    void lssiApplicabilityIsAssessedCautiously() {
        // A legal notice is where Art. 10 LSSI-CE information belongs: its absence is a possible incident.
        var aviso = analizar("Aviso legal. Las condiciones de este sitio se rigen por la ley española.", AVISO);
        assertThat(errores(aviso, "R16")).singleElement().satisfies(e -> {
            assertThat(e.getEstado()).isEqualTo(POSIBLE_INCIDENCIA);
            assertThat(e.getTitulo()).isEqualTo("Aviso legal con información LSSI-CE ausente o incompleto");
            assertThat(e.getSeveridad()).isEqualTo("ALTA");
        });
        // Elsewhere the text cannot tell whether the site is an information society service, or where its notice is.
        var completo = analizar("Bienvenido a nuestra tienda.", COMPLETO);
        assertThat(errores(completo, "R16")).isEmpty();
        assertThat(evaluacion("Bienvenido a nuestra tienda.", COMPLETO, "R16").detalle())
                .isEqualTo("El texto no permite saber si el titular presta servicios de la sociedad de la información "
                        + "(Art. 10 LSSI-CE) ni si esa información figura en otra página.");
        assertThat(completo.getFaltantes()).anyMatch(f -> f.startsWith("Aviso legal con datos identificativos completos"));
        assertThat(evaluacion("Ejemplo S.L. gestiona esta web.", COMPLETO, "R16")).satisfies(e -> {
            assertThat(e.estado()).isEqualTo(NO_VERIFICABLE);
            assertThat(e.detalle()).startsWith("Aparecen datos del titular, pero el documento no se presenta como aviso legal.");
        });
        for (String tipo : List.of(PRIVACIDAD, COOKIES, "Términos y Condiciones", "Condiciones de Uso")) {
            assertThat(evaluacion("Responsable del tratamiento: Ejemplo S.L.", tipo, "R16").estado()).as(tipo).isEqualTo(NO_APLICABLE);
        }
    }

    // ---- R01 Identidad del responsable -------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"Responsable del tratamiento: Ejemplo S.L.", "El responsable del tratamiento es Ejemplo Servicios.",
            "Responsable: Ana Pérez García, NIF 12345678Z.", "Ejemplo S.L. trata sus datos como responsable.",
            "Responsable de sus datos: Comercial Ejemplo S.A."})
    void anActualControllerIsSufficient(String texto) {
        var e = evaluacion(texto, PRIVACIDAD, "R01");
        assertThat(e.estado()).isEqualTo(SUFICIENTE);
        assertThat(e.evidencia()).isEqualTo("«" + texto + "»");
        assertThat(puntos(texto, PRIVACIDAD, "R01")).isEqualTo(8);
    }

    @Test
    void aControllerHeadingFollowedByTheEntityIsSufficient() {
        String texto = """
                Responsable del tratamiento

                Ejemplo S.L., con domicilio en Madrid.""";
        var e = evaluacion(texto, PRIVACIDAD, "R01");
        assertThat(e.estado()).isEqualTo(SUFICIENTE);
        assertThat(e.evidencia()).isEqualTo("«Responsable del tratamiento Ejemplo S.L., con domicilio en Madrid.»");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            El responsable del tratamiento es la empresa titular de este sitio web.          | Se alude al responsable del tratamiento sin indicar su nombre o razón social.
            Sus datos serán tratados por el responsable del tratamiento.                      | Se alude al responsable del tratamiento sin indicar su nombre o razón social.
            Responsable del tratamiento: Nuestra empresa.                                     | Se alude al responsable del tratamiento sin indicar su nombre o razón social.
            Ejemplo S.L. es la titular de este sitio web.                                     | Se nombra una entidad, pero no se indica que sea la responsable del tratamiento.
            Ejemplo S.L. no es responsable del tratamiento de los datos que publiquen los usuarios. | Se nombra una entidad, pero no se indica que sea la responsable del tratamiento.
            """)
    void aGenericControllerOrAnEntityWithoutTheRoleIsInsufficient(String texto, String detalle) {
        // v3: «responsable del tratamiento» or «S.L.» alone accredited the 8 points.
        var r = analizar(texto, PRIVACIDAD);
        assertThat(errores(r, "R01")).singleElement().satisfies(e -> {
            assertThat(e.getEstado()).isEqualTo(INSUFICIENTE);
            assertThat(e.getTitulo()).isEqualTo("Responsable del tratamiento mencionado sin identificarlo");
            assertThat(e.getSeveridad()).isEqualTo("MEDIA");
            assertThat(e.getEvidencia()).isEqualTo("«" + texto + "»");
            assertThat(e.getDescripcion()).endsWith(detalle);
        });
        assertThat(puntos(texto, PRIVACIDAD, "R01")).isEqualTo(4);
    }

    @Test
    void aMissingControllerIsAPossibleIncidentOnlyWhereItApplies() {
        assertThat(errores(analizar("Bienvenido a nuestra página.", PRIVACIDAD), "R01")).singleElement().satisfies(e -> {
            assertThat(e.getEstado()).isEqualTo(POSIBLE_INCIDENCIA);
            assertThat(e.getTitulo()).isEqualTo("Identidad del responsable del tratamiento no especificada");
            assertThat(e.getSeveridad()).isEqualTo("ALTA");
        });
        // A legal notice identifies the provider (R16); the controller belongs to the privacy policy.
        assertThat(evaluacion("Aviso legal. Titular: Ejemplo S.L.", AVISO, "R01").estado()).isEqualTo(NO_APLICABLE);
        assertThat(evaluacion("Usamos cookies técnicas.", COOKIES, "R01").estado()).isEqualTo(NO_APLICABLE);
    }

    // ---- R04 Finalidades ---------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            La finalidad es la prestación del servicio y la gestión de clientes.      | prestación del servicio, gestión de clientes o del contrato
            Tratamos sus datos para gestionar sus pedidos y enviarle las facturas.     | pedidos, pagos y facturación
            Utilizamos sus datos para gestionar su cuenta de usuario.                  | cuentas de usuario
            Sus datos se tratarán con la finalidad de atender su solicitud.            | atención de consultas y solicitudes
            """)
    void concretePurposesAreSufficient(String texto, String fines) {
        var e = evaluacion(texto, PRIVACIDAD, "R04");
        assertThat(e.estado()).isEqualTo(SUFICIENTE);
        assertThat(e.evidencia()).isEqualTo("«" + texto + "»");
        assertThat(e.detalle()).isEqualTo("Finalidades indicadas: " + fines + ".");
    }

    @Test
    void aPurposesHeadingLetsTheListItemsStateThem() {
        String texto = """
                Finalidades:
                - Atender sus consultas.
                - Enviarle la newsletter si la solicita.""";
        var e = evaluacion(texto, PRIVACIDAD, "R04");
        assertThat(e.estado()).isEqualTo(SUFICIENTE);
        assertThat(e.evidencia()).isEqualTo("«Atender sus consultas.»");
        assertThat(e.detalle()).isEqualTo("Finalidades indicadas: atención de consultas y solicitudes, comunicaciones comerciales.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Tratamos sus datos para las finalidades indicadas en cada formulario.",
            "Sus datos se tratarán con fines legítimos.", "La finalidad del tratamiento se informa en el momento de la recogida.",
            "No utilizaremos sus datos para cualquier otra finalidad distinta de la indicada."})
    void anIsolatedOrVaguePurposeIsInsufficient(String texto) {
        // v3: «finalidad» or «finalidades» alone accredited the 8 points.
        var r = analizar(texto, PRIVACIDAD);
        assertThat(errores(r, "R04")).singleElement().satisfies(e -> {
            assertThat(e.getEstado()).isEqualTo(INSUFICIENTE);
            assertThat(e.getTitulo()).isEqualTo("Finalidades del tratamiento mencionadas sin concretarlas");
            assertThat(e.getSeveridad()).isEqualTo("MEDIA");
        });
        assertThat(puntos(texto, PRIVACIDAD, "R04")).isEqualTo(4);
    }

    @Test
    void anOpenEndedPurposeIsAPossibleIncident() {
        var r = analizar("Podremos utilizar sus datos para cualquier finalidad.", PRIVACIDAD);
        assertThat(errores(r, "R04")).singleElement().satisfies(e -> {
            assertThat(e.getEstado()).isEqualTo(POSIBLE_INCIDENCIA);
            assertThat(e.getTitulo()).startsWith(RIESGO);
            assertThat(e.getSeveridad()).isEqualTo("ALTA");
            assertThat(e.getEvidencia()).isEqualTo("«Podremos utilizar sus datos para cualquier finalidad.»");
        });
        assertThat(puntos("Podremos utilizar sus datos para cualquier finalidad.", PRIVACIDAD, "R04")).isZero();
    }

    @Test
    void negatedOrCookiePurposesAreNotTheProcessingPurposes() {
        // v3: «finalidad» in the cookie clause accredited the 8 points.
        assertThat(evaluacion("Usamos cookies analíticas con la finalidad de medir las visitas.", COMPLETO, "R04").estado())
                .isEqualTo(POSIBLE_INCIDENCIA);
        assertThat(evaluacion("No tratamos sus datos para enviar publicidad.", PRIVACIDAD, "R04").estado())
                .isEqualTo(POSIBLE_INCIDENCIA);
        assertThat(evaluacion("Aviso legal. Titular: Ejemplo S.L.", AVISO, "R04").estado()).isEqualTo(NO_APLICABLE);
    }

    // ---- R09 Transferencias internacionales --------------------------------------------------

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            Las transferencias internacionales a Estados Unidos se amparan en cláusulas contractuales tipo aprobadas por la Comisión Europea. | cláusulas contractuales tipo
            Sus datos se transfieren a Estados Unidos a empresas adheridas al Marco de Privacidad de Datos.                                  | Marco de Privacidad de Datos UE-EE. UU.
            No realizamos transferencias internacionales, salvo a Estados Unidos con cláusulas contractuales tipo.                          | cláusulas contractuales tipo
            """)
    void aDisclosedTransferWithANamedSafeguardIsSufficient(String texto, String garantia) {
        var e = evaluacion(texto, PRIVACIDAD, "R09");
        assertThat(e.estado()).isEqualTo(SUFICIENTE);
        assertThat(e.detalle()).isEqualTo("Se informa de transferencias internacionales con estas garantías: " + garantia
                + ". El texto no permite comprobar que se apliquen.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"No realizamos transferencias internacionales de datos.", "Sus datos no se transfieren fuera del EEE.",
            "Sus datos no salen del Espacio Económico Europeo.", "Los datos permanecen en la Unión Europea."})
    void aStatementOfNoTransfersIsSufficientButNotProven(String texto) {
        var e = evaluacion(texto, PRIVACIDAD, "R09");
        assertThat(e.estado()).isEqualTo(SUFICIENTE);
        assertThat(e.evidencia()).isEqualTo("«" + texto + "»");
        assertThat(e.detalle()).isEqualTo(
                "El documento declara que no realiza transferencias internacionales; el texto no permite comprobarlo.");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            Sus datos podrán transferirse a otros países.                                              | Se mencionan transferencias internacionales sin indicar el mecanismo de garantía (Arts. 45 a 49 RGPD).
            Las transferencias internacionales se realizan con las garantías adecuadas.                | Se mencionan transferencias internacionales sin indicar el mecanismo de garantía (Arts. 45 a 49 RGPD). Se alude a garantías adecuadas sin concretarlas.
            Las transferencias internacionales a Estados Unidos se amparan en el Privacy Shield.       | Se mencionan transferencias internacionales sin indicar el mecanismo de garantía (Arts. 45 a 49 RGPD). El Escudo de Privacidad UE-EE. UU. fue anulado por el TJUE en 2020 (asunto C-311/18).
            No realizamos transferencias internacionales, salvo a proveedores de Estados Unidos.       | Se mencionan transferencias internacionales sin indicar el mecanismo de garantía (Arts. 45 a 49 RGPD).
            Nuestros servidores están ubicados en la Unión Europea.                                    | Se indica dónde se alojan los datos, pero no si se transfieren fuera del Espacio Económico Europeo.
            """)
    void anIncompleteTransferExplanationIsInsufficient(String texto, String detalle) {
        var r = analizar(texto, PRIVACIDAD);
        assertThat(errores(r, "R09")).singleElement().satisfies(e -> {
            assertThat(e.getEstado()).isEqualTo(INSUFICIENTE);
            assertThat(e.getTitulo()).isEqualTo("Transferencias internacionales mencionadas sin garantías concretas");
            assertThat(e.getSeveridad()).isEqualTo("BAJA");
            assertThat(e.getDescripcion()).endsWith(detalle);
        });
        assertThat(puntos(texto, PRIVACIDAD, "R09")).isEqualTo(2);
    }

    @Test
    void transfersWithoutSafeguardsAreAPossibleIncident() {
        var r = analizar("Podremos transferir sus datos a cualquier país sin garantías.", PRIVACIDAD);
        assertThat(errores(r, "R09")).singleElement().satisfies(e -> {
            assertThat(e.getEstado()).isEqualTo(POSIBLE_INCIDENCIA);
            assertThat(e.getTitulo()).startsWith(RIESGO);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"Responsable del tratamiento: Ejemplo S.L.", "Comunicamos sus datos a terceros proveedores.",
            "Realizamos envíos a Reino Unido y Suiza."})
    void transfersAreNeverAssumed(String texto) {
        // v3: a MISSING finding for R09 in every one of them.
        var r = analizar(texto, PRIVACIDAD);
        assertThat(errores(r, "R09")).isEmpty();
        assertThat(evaluacion(texto, PRIVACIDAD, "R09")).satisfies(e -> {
            assertThat(e.estado()).isEqualTo(NO_VERIFICABLE);
            assertThat(e.detalle()).isEqualTo(
                    "El documento no menciona transferencias internacionales; el texto no permite saber si se realizan.");
        });
        assertThat(r.getFaltantes()).anyMatch(f -> f.startsWith("Transferencias internacionales de datos y garantías aplicables: "));
        assertThat(evaluacion("Aviso legal. Titular: Ejemplo S.L.", AVISO, "R09").estado()).isEqualTo(NO_APLICABLE);
    }

    // ---- R11 Reclamación ante la autoridad de control ----------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"Puede presentar una reclamación ante la AEPD.",
            "Tiene derecho a reclamar ante la Agencia Española de Protección de Datos (www.aepd.es).",
            "Si no obtiene respuesta, puede reclamar ante la autoridad de control."})
    void theRightToComplainToANamedAuthorityIsSufficient(String texto) {
        var e = evaluacion(texto, PRIVACIDAD, "R11");
        assertThat(e.estado()).isEqualTo(SUFICIENTE);
        assertThat(e.evidencia()).isEqualTo("«" + texto + "»");
        assertThat(puntos(texto, PRIVACIDAD, "R11")).isEqualTo(4);
    }

    @Test
    void theRightAndTheAuthorityMaySitInConsecutiveSentences() {
        var e = evaluacion("Si considera que el tratamiento de sus datos no es adecuado, puede presentar una reclamación. "
                + "La autoridad de control competente es la AEPD.", PRIVACIDAD, "R11");
        assertThat(e.estado()).isEqualTo(SUFICIENTE);
        assertThat(e.evidencia()).isEqualTo("«Si considera que el tratamiento de sus datos no es adecuado, puede presentar "
                + "una reclamación. La autoridad de control competente es la AEPD.»");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            Seguimos las recomendaciones de la AEPD.                                  | Se menciona la autoridad de control sin informar del derecho a presentar una reclamación ante ella.
            Notificaremos a la AEPD cualquier brecha de seguridad.                    | Se menciona la autoridad de control sin informar del derecho a presentar una reclamación ante ella.
            Puede presentar una reclamación si considera vulnerados sus derechos.     | Se menciona una reclamación sin identificar la autoridad de control ante la que presentarla.
            """)
    void anIsolatedAuthorityOrComplaintIsInsufficient(String texto, String detalle) {
        // v3: «aepd» or «presentar una reclamación» alone accredited the 4 points.
        var r = analizar(texto, PRIVACIDAD);
        assertThat(errores(r, "R11")).singleElement().satisfies(e -> {
            assertThat(e.getEstado()).isEqualTo(INSUFICIENTE);
            assertThat(e.getTitulo()).isEqualTo("Derecho a reclamar ante la autoridad de control informado de forma incompleta");
            assertThat(e.getSeveridad()).isEqualTo("BAJA");
            assertThat(e.getDescripcion()).endsWith(detalle);
        });
        assertThat(puntos(texto, PRIVACIDAD, "R11")).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"No podrá presentar reclamaciones ante la AEPD sin dirigirse antes a nosotros.",
            "El usuario renuncia a presentar cualquier reclamación ante la autoridad de control."})
    void denyingOrWaivingTheRightIsAPossibleIncident(String texto) {
        var r = analizar(texto, PRIVACIDAD);
        assertThat(errores(r, "R11")).singleElement().satisfies(e -> {
            assertThat(e.getEstado()).isEqualTo(POSIBLE_INCIDENCIA);
            assertThat(e.getTitulo()).startsWith(RIESGO);
            assertThat(e.getEvidencia()).isEqualTo("«" + texto + "»");
        });
        assertThat(puntos(texto, PRIVACIDAD, "R11")).isZero();
    }

    @Test
    void aConsumerComplaintFormIsNoDataProtectionComplaint() {
        assertThat(evaluacion("Puede solicitar la hoja de reclamaciones en nuestra tienda.", PRIVACIDAD, "R11").estado())
                .isEqualTo(POSIBLE_INCIDENCIA);
        assertThat(evaluacion("Puede solicitar la hoja de reclamaciones en nuestra tienda.", "Términos y Condiciones", "R11")
                .estado()).isEqualTo(NO_APLICABLE);
    }

    @Test
    void anUnknownDocumentTypeIsEvaluatedLikeACompleteDocument() {
        var r = analizar("Bienvenido.", null);
        assertThat(r.getEvaluaciones()).filteredOn(e -> List.of("R01", "R04", "R09", "R11", "R16").contains(e.ruleId()))
                .extracting(EvaluacionRegla::estado)
                .containsExactly(POSIBLE_INCIDENCIA, POSIBLE_INCIDENCIA, NO_VERIFICABLE, POSIBLE_INCIDENCIA, NO_VERIFICABLE);
        assertThat(evaluacion("Aviso legal. Titular: Ejemplo S.L.", "  Aviso Legal  ", "R16").estado()).isEqualTo(INSUFICIENTE);
    }

    // ---- Score breakdown and history ----------------------------------------------------------

    @Test
    void theBatchRulesAreContextualControlsInTheBreakdown() {
        var d = analizar("Responsable del tratamiento: Ejemplo S.L.", PRIVACIDAD).getDesglosePuntuacion();
        assertThat(d.controles()).filteredOn(c -> List.of("R01", "R04", "R09", "R11", "R16").contains(c.ruleId()))
                .hasSize(5).allSatisfy(c -> {
                    assertThat(c.metodo()).isEqualTo(MetodoControl.CONTEXTUAL);
                    assertThat(c.estado()).isNotNull();
                });
        // Rules v5 moved the last keyword controls (R13, R14, R17, R18 and R19) to the contextual evaluation.
        assertThat(d.controles()).noneMatch(c -> c.metodo() == MetodoControl.PALABRAS_CLAVE);
        assertThat(d.pesoPalabrasClave()).isZero();
    }

    @Test
    void storedRulesV3BreakdownsKeepTheirOriginalSplit() throws Exception {
        String v3 = """
                {"resumen":"Reglas v3","puntuacionRiesgo":21,"errores":[],
                "desglosePuntuacion":{"version":"desglose-1","puntuacion":21,"pesoTotal":100,"puntosAcreditados":21,
                "penalizacionRiesgos":0,"pesoNoAplicable":47,"pesoNoVerificable":0,"pesoContextualEvaluado":6,
                "puntosContextuales":6,"pesoPalabrasClave":47,"puntosPalabrasClave":15,"porcentajeContextual":100,
                "motivoSinPorcentaje":null,"controles":[{"ruleId":"R01","peso":8,"puntos":8,"metodo":"PALABRAS_CLAVE","estado":null},
                {"ruleId":"R16","peso":7,"puntos":7,"metodo":"PALABRAS_CLAVE","estado":null}]}}""";
        var mapper = new ObjectMapper();
        var leido = mapper.readValue(v3, ResultadoAuditoria.class);
        assertThat(leido.getPuntuacionRiesgo()).isEqualTo(21);
        assertThat(leido.getDesglosePuntuacion().pesoPalabrasClave()).isEqualTo(47);
        assertThat(leido.getDesglosePuntuacion().controles()).extracting(c -> c.metodo())
                .containsOnly(MetodoControl.PALABRAS_CLAVE);
        assertThat(mapper.readTree(mapper.writeValueAsString(leido)).path("desglosePuntuacion"))
                .isEqualTo(mapper.readTree(v3).path("desglosePuntuacion"));
        assertThat(motor.version()).isEqualTo(new VersionAnalizador("3", "5"));
    }
}
