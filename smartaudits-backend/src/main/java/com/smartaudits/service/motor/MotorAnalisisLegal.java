package com.smartaudits.service.motor;

import com.smartaudits.model.dto.ResultadoAuditoria;
import com.smartaudits.model.dto.ResultadoAuditoria.ControlPuntuado;
import com.smartaudits.model.dto.ResultadoAuditoria.DesglosePuntuacion;
import com.smartaudits.model.dto.ResultadoAuditoria.ErrorAuditoria;
import com.smartaudits.model.dto.ResultadoAuditoria.EstadoEvidencia;
import com.smartaudits.model.dto.ResultadoAuditoria.EvaluacionRegla;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Motor de análisis legal propio de SmartAudits.
 *
 * Analiza textos legales (políticas de privacidad, avisos legales, términos de uso,
 * cookies, etc.) aplicando un conjunto de reglas basadas en:
 *
 *   - RGPD        — Reglamento (UE) 2016/679
 *   - LOPDGDD     — Ley Orgánica 3/2018 (versión consolidada diciembre 2025)
 *   - LSSI-CE     — Ley 34/2002 de Servicios de la Sociedad de la Información
 *   - Directiva ePrivacy 2002/58/CE
 *   - Ley 10/2025 — Modificaciones en privacidad y telecomunicaciones
 *   - Guía de cookies AEPD 2023/2025
 *
 * Sistema de puntuación porcentual (Opción B):
 *   - Cada regla tiene un PESO proporcional a su gravedad.
 *   - El total de pesos de todas las reglas suma exactamente 100.
 *   - Cumplir una regla suma su peso completo.
 *   - Cumplirla con lenguaje de riesgo suma la mitad del peso.
 *   - No cumplirla no suma nada.
 *   - Resultado final = suma de puntos obtenidos (0-100). Nunca negativo.
 *
 * Reglas versión 5: las 19 reglas (y los riesgos G01, G02 y G03) se evalúan por cláusulas, con palabras
 * completas y negaciones ({@link ReglasContextuales}); la versión 4 dejaba R13, R14, R17, R18 y R19 (16 puntos)
 * como palabras clave por subcadena, la versión 3 cubría R02, R03, R05, R06, R07, R08, R10, R12, R15, G01, G02 y
 * G03, y la versión 2 solo R03, R05, R06, R07, R08, G01 y G02. Que todas se evalúen por contexto no hace exacta la
 * evaluación: sigue siendo una lectura automática del texto. Cada regla termina en un estado
 * de evidencia y suma: su peso si es SUFICIENTE; la mitad si es INSUFICIENTE o si la cláusula
 * suficiente tiene redacción problemática; nada en otro caso. NO_APLICABLE y NO_VERIFICABLE no
 * suman ni generan incidencia: la puntuación sigue siendo la suma de pesos acreditados sobre 100,
 * sin redistribuir pesos (eso exigiría recalibrar todas las reglas).
 *
 * Cada resultado incluye un desglose ("desglose-1") que separa los puntos acreditados, las penalizaciones
 * y los pesos no aplicables, no verificables y contextuales; el peso por palabras clave es 0 desde las reglas 5
 * y solo los desgloses guardados antes lo conservan. El algoritmo no cambia.
 * La versión del motor 3 corresponde a esa salida y a un resumen que no presenta la cifra como cumplimiento.
 *
 * Limitación conocida, pendiente de rediseñar la puntuación: como NO_APLICABLE y NO_VERIFICABLE suman
 * cero, la cifra no es comparable entre tipos de documento (un aviso legal correcto nunca se acerca a 100)
 * ni entre documentos con distinta información no verificable (un texto sin cookies ni consentimiento
 * pierde esos pesos aunque no los necesite).
 *
 * No utiliza APIs externas. Todo el análisis se realiza mediante reglas en Java.
 */
@Service
public class MotorAnalisisLegal implements AnalizadorLegal {

    private static final String MOTOR = "LEGAL_TEXT";
    private static final VersionAnalizador VERSION = new VersionAnalizador("3", "5");

    @Override
    public VersionAnalizador version() {
        return VERSION;
    }

    // -----------------------------------------------------------------------
    // Clase interna: Regla de análisis
    // -----------------------------------------------------------------------

    private static class Regla {
        final String id;
        final String titulo;
        final String descripcion;
        final String severidad;           // ALTA, MEDIA, BAJA
        final int peso;                   // Puntos que aporta si se cumple (suma total = 100)
        final String impacto;
        final String accion;
        final String referenciaLegal;
        final String textoCumplimiento;
        final String campoFaltante;

        Regla(String id, String titulo, String descripcion, String severidad, int peso,
              String impacto, String accion, String referenciaLegal,
              String textoCumplimiento, String campoFaltante) {
            this.id = id;
            this.titulo = titulo;
            this.descripcion = descripcion;
            this.severidad = severidad;
            this.peso = peso;
            this.impacto = impacto;
            this.accion = accion;
            this.referenciaLegal = referenciaLegal;
            this.textoCumplimiento = textoCumplimiento;
            this.campoFaltante = campoFaltante;
        }
    }

    // -----------------------------------------------------------------------
    // Catálogo de reglas (cómo se evalúa cada una: ReglasContextuales)
    // Pesos diseñados para sumar exactamente 100 en total.
    // ALTA=6-8pts, MEDIA=3-5pts, BAJA=2-3pts
    // -----------------------------------------------------------------------

    private static final List<Regla> REGLAS = new ArrayList<>();

    static {

        // R01 — Identidad del responsable del tratamiento (ALTA, peso 8)
        REGLAS.add(new Regla(
            "R01",
            "Identidad del responsable del tratamiento no especificada",
            "No se identifica el nombre, razón social o datos de contacto del responsable del tratamiento de datos personales.",
            "ALTA", 8,
            "Incumplimiento del Art. 13.1.a y 14.1.a RGPD. El interesado no puede identificar quién trata sus datos. Sanciones de hasta 20 millones € o 4% del volumen de negocio anual.",
            "Incluir el nombre completo o razón social del responsable, NIF/CIF y dirección postal completa al inicio del documento.",
            "RGPD Art. 13.1.a — Identidad y datos de contacto del responsable",
            "\"Responsable del tratamiento: [Nombre o Razón Social], con NIF/CIF [XXX], domicilio en [Dirección completa, CP, Ciudad].\"",
            "Identidad y datos de contacto del responsable del tratamiento"
        ));

        // R02 — Datos de contacto del responsable (ALTA, peso 6)
        REGLAS.add(new Regla(
            "R02",
            "Datos de contacto del responsable ausentes o incompletos",
            "No se proporcionan medios de contacto suficientes para que el interesado pueda comunicarse con el responsable.",
            "ALTA", 6,
            "Posible incumplimiento del Art. 13.1.a RGPD: sin un medio de contacto concreto el interesado no puede dirigirse al responsable ni ejercer sus derechos.",
            "Proporcionar al menos una dirección de correo electrónico de contacto y, preferiblemente, dirección postal.",
            "RGPD Art. 13.1.a — Datos de contacto del responsable",
            "\"Para cualquier consulta sobre el tratamiento de sus datos puede contactar con nosotros en: [email] o en [dirección postal].\"",
            "Datos de contacto del responsable del tratamiento"
        ));

        // R03 — Delegado de Protección de Datos / DPO (MEDIA, peso 4)
        REGLAS.add(new Regla(
            "R03",
            "Información sobre el Delegado de Protección de Datos (DPO) ausente",
            "No se menciona si se ha designado Delegado de Protección de Datos ni sus datos de contacto.",
            "MEDIA", 4,
            "Incumplimiento del Art. 13.1.b RGPD. Si el responsable está obligado a designar DPO (Art. 37 RGPD), su ausencia es una infracción grave.",
            "Indicar si se ha designado DPO y sus datos de contacto. Si no aplica, indicarlo expresamente.",
            "RGPD Art. 13.1.b — Datos de contacto del DPO",
            "\"Delegado de Protección de Datos: [nombre o departamento]. Contacto: [dpo@empresa.com]. / El responsable no está obligado a designar DPO conforme al Art. 37 RGPD.\"",
            "Datos de contacto del Delegado de Protección de Datos (si procede)"
        ));

        // R04 — Finalidad del tratamiento (ALTA, peso 8)
        REGLAS.add(new Regla(
            "R04",
            "Finalidad del tratamiento de datos no especificada",
            "No se describen de forma clara las finalidades para las que se tratan los datos personales.",
            "ALTA", 8,
            "Incumplimiento del Art. 13.1.c RGPD. Sin finalidad declarada, el tratamiento carece de transparencia y puede considerarse ilícito.",
            "Describir todas las finalidades de tratamiento de forma clara, concreta y diferenciada.",
            "RGPD Art. 13.1.c — Fines del tratamiento",
            "\"Sus datos personales serán tratados para: (1) Gestión de la relación contractual. (2) Envío de comunicaciones comerciales si ha prestado consentimiento. (3) Cumplimiento de obligaciones legales.\"",
            "Finalidades específicas del tratamiento de datos"
        ));

        // R05 — Base legal del tratamiento (ALTA, peso 8)
        REGLAS.add(new Regla(
            "R05",
            "Base legal del tratamiento no indicada",
            "No se especifica la base jurídica que legitima el tratamiento de datos personales.",
            "ALTA", 8,
            "Posible incumplimiento del deber de información del Art. 13.1.c RGPD: el interesado no puede saber qué base del Art. 6.1 RGPD legitima cada tratamiento. El texto por sí solo no permite valorar si el tratamiento es lícito.",
            "Identificar y declarar explícitamente la base legal aplicable a cada tratamiento.",
            "RGPD Art. 6 — Licitud del tratamiento / Art. 13.1.c",
            "\"La base legal del tratamiento es: (a) el consentimiento del interesado (Art. 6.1.a RGPD); (b) la ejecución del contrato (Art. 6.1.b RGPD); (c) el cumplimiento de una obligación legal (Art. 6.1.c RGPD).\"",
            "Base legal de cada finalidad de tratamiento"
        ));

        // R06 — Derechos ARSULIPO (ALTA, peso 8)
        REGLAS.add(new Regla(
            "R06",
            "Derechos del interesado (ARSULIPO) no informados",
            "No se informa al usuario sobre sus derechos de Acceso, Rectificación, Supresión, Limitación, Portabilidad y Oposición.",
            "ALTA", 8,
            "Incumplimiento de los Arts. 15-22 y 13.2.b RGPD. El usuario desconoce sus derechos fundamentales sobre sus propios datos.",
            "Incluir sección dedicada a los derechos ARSULIPO con descripción de cada derecho y procedimiento claro para ejercerlos.",
            "RGPD Arts. 15-22 — Derechos del interesado / Art. 13.2.b",
            "\"Tiene derecho a: Acceder, Rectificar, Suprimir, Limitar, Oponerse y Portar sus datos. Para ejercerlos, dirígase a [email/dirección]. Responderemos en el plazo máximo de un mes.\"",
            "Derechos ARSULIPO del interesado y procedimiento para ejercerlos"
        ));

        // R07 — Plazo de conservación (MEDIA, peso 5)
        REGLAS.add(new Regla(
            "R07",
            "Plazo de conservación de datos no especificado",
            "No se indica durante cuánto tiempo se conservarán los datos ni los criterios para determinarlo.",
            "MEDIA", 5,
            "Incumplimiento del principio de limitación del plazo de conservación (Art. 5.1.e RGPD) y del deber de información (Art. 13.2.a RGPD).",
            "Especificar plazos concretos de conservación para cada finalidad o indicar los criterios legales que los determinan.",
            "RGPD Art. 5.1.e — Limitación del plazo de conservación / Art. 13.2.a",
            "\"Sus datos se conservarán durante [X años] y, transcurrido ese plazo, serán bloqueados y posteriormente suprimidos conforme a los plazos legales aplicables.\"",
            "Plazo de conservación de los datos o criterios para determinarlo"
        ));

        // R08 — Destinatarios / cesión de datos (MEDIA, peso 5)
        REGLAS.add(new Regla(
            "R08",
            "Destinatarios o cesiones de datos no informadas",
            "No se informa sobre si los datos serán comunicados a terceros ni quiénes son esos destinatarios.",
            "MEDIA", 5,
            "Incumplimiento del Art. 13.1.e RGPD. El usuario tiene derecho a saber si sus datos llegarán a terceros.",
            "Listar los destinatarios o categorías de destinatarios. Si no se ceden datos, indicarlo expresamente.",
            "RGPD Art. 13.1.e — Destinatarios o categorías de destinatarios",
            "\"Sus datos no serán cedidos a terceros, salvo obligación legal. Podrán acceder a ellos como encargados del tratamiento: [proveedor de hosting], [plataforma de pago], bajo las garantías del Art. 28 RGPD.\"",
            "Destinatarios o categorías de destinatarios de los datos"
        ));

        // R09 — Transferencias internacionales (MEDIA, peso 4)
        REGLAS.add(new Regla(
            "R09",
            "Transferencias internacionales de datos no declaradas",
            "No se informa si se realizan transferencias fuera del Espacio Económico Europeo (EEE) ni las garantías aplicables.",
            "MEDIA", 4,
            "Incumplimiento del Art. 13.1.f y Arts. 44-46 RGPD. Si existen transferencias sin garantías, el tratamiento puede ser ilícito.",
            "Indicar si se realizan transferencias internacionales, los países de destino y el mecanismo de garantía aplicable.",
            "RGPD Arts. 44-46 — Transferencias internacionales / Art. 13.1.f",
            "\"Sus datos no se transferirán fuera del EEE. / Sus datos podrán ser transferidos a [país] mediante cláusulas contractuales tipo aprobadas por la Comisión Europea.\"",
            "Transferencias internacionales de datos y garantías aplicables"
        ));

        // R10 — Política de cookies actualizada AEPD 2025 (ALTA, peso 7)
        REGLAS.add(new Regla(
            "R10",
            "Política de cookies ausente o incompleta",
            "No se proporciona información completa sobre cookies: tipos, finalidades, duración y gestión del consentimiento con botones de aceptar/rechazar al mismo nivel (AEPD 2025).",
            "ALTA", 7,
            "Posible incumplimiento del Art. 22.2 LSSI-CE: el usuario debe recibir información clara y completa sobre las cookies y sus finalidades. La Guía sobre el uso de las cookies de la AEPD indica que rechazar debe ser tan sencillo como aceptar.",
            "Implementar política de cookies completa con listado, mecanismo de consentimiento previo y panel de preferencias con botón de rechazo igual de visible que el de aceptación.",
            "LSSI-CE Art. 22.2 — Cookies / Directiva ePrivacy / Guía AEPD 2025",
            "\"Utilizamos cookies propias y de terceros. Cookies técnicas (exentas): [listado]. Cookies analíticas y publicitarias: requieren consentimiento previo. [Botón Aceptar] [Botón Rechazar] [Configurar].\"",
            "Política de cookies completa con tipología, finalidades y botones de gestión equivalentes"
        ));

        // R11 — Derecho a reclamar ante la AEPD (MEDIA, peso 4)
        REGLAS.add(new Regla(
            "R11",
            "Derecho a reclamar ante la autoridad de control (AEPD) no informado",
            "No se informa al interesado sobre su derecho a presentar reclamación ante la Agencia Española de Protección de Datos.",
            "MEDIA", 4,
            "Incumplimiento del Art. 13.2.d RGPD. El usuario tiene derecho a conocer que puede recurrir a la autoridad supervisora.",
            "Añadir información sobre el derecho a presentar reclamación ante la AEPD (www.aepd.es).",
            "RGPD Art. 13.2.d — Derecho a reclamar ante la autoridad de control",
            "\"Tiene derecho a presentar una reclamación ante la Agencia Española de Protección de Datos (www.aepd.es) si considera que el tratamiento de sus datos no es adecuado.\"",
            "Derecho a reclamar ante la Agencia Española de Protección de Datos (AEPD)"
        ));

        // R12 — Consentimiento informado y libre (ALTA, peso 6)
        REGLAS.add(new Regla(
            "R12",
            "Mecanismo de consentimiento no descrito adecuadamente",
            "No se describe cómo se obtiene el consentimiento ni cómo puede retirarse.",
            "ALTA", 6,
            "Posible incumplimiento del deber de información sobre el consentimiento (Arts. 4.11 y 7 RGPD): debe ser libre, específico, informado e inequívoco, y poder retirarse en cualquier momento.",
            "Describir el mecanismo de consentimiento: qué acción lo constituye, para qué tratamientos se pide y cómo puede retirarse en cualquier momento.",
            "RGPD Art. 7 — Condiciones para el consentimiento / Art. 4.11",
            "\"Su consentimiento se solicita mediante casilla de verificación desmarcada por defecto. Puede retirarlo en cualquier momento sin que ello afecte a la licitud del tratamiento anterior.\"",
            "Mecanismo claro de obtención y retirada del consentimiento"
        ));

        // R13 — Datos de menores actualizado LOPDGDD 2025 (BAJA, peso 3)
        REGLAS.add(new Regla(
            "R13",
            "Tratamiento de datos de menores no regulado",
            "No se indica si se tratan datos de menores ni, en su caso, la edad mínima y el consentimiento de quien ejerce la patria potestad o tutela.",
            "BAJA", 3,
            "Si el tratamiento de datos de un menor se basa en su consentimiento, el Art. 7 LOPDGDD solo admite el del propio menor a partir de los 14 años; por debajo se requiere el de quien ejerce la patria potestad o tutela (Art. 8 RGPD). El texto no permite saber si se tratan datos de menores.",
            "Si el servicio puede tratar datos de menores, indicar la edad mínima (14 años en España para consentir por sí mismos) y cómo se recaba y comprueba el consentimiento de sus representantes legales; si no se dirige a menores, indicarlo.",
            "RGPD Art. 8 — Consentimiento de menores / LOPDGDD Art. 7",
            "\"Nuestros servicios están dirigidos a mayores de 14 años. Si tiene menos de 14 años, necesita el consentimiento de sus padres o tutores para utilizar este servicio.\"",
            "Tratamiento de datos de menores de 14 años y consentimiento parental"
        ));

        // R14 — Datos especialmente sensibles (BAJA, peso 3)
        REGLAS.add(new Regla(
            "R14",
            "Tratamiento de categorías especiales de datos no regulado",
            "No se indica si se tratan categorías especiales de datos (salud, ideología, origen étnico, etc.) ni, en su caso, la excepción del Art. 9.2 RGPD que lo permite.",
            "BAJA", 3,
            "Las categorías especiales de datos solo pueden tratarse si se aplica alguna excepción del Art. 9.2 RGPD. El texto no permite saber si se tratan ni comprobar las garantías que declare.",
            "Si se tratan categorías especiales, indicar cuáles, para qué y qué excepción del Art. 9.2 RGPD se aplica, además de las garantías adoptadas; si no se tratan, indicarlo.",
            "RGPD Art. 9 — Tratamiento de categorías especiales de datos",
            "\"Tratamos datos de salud únicamente con su consentimiento explícito (Art. 9.2.a RGPD), con medidas de seguridad reforzadas. / No tratamos categorías especiales de datos.\"",
            "Tratamiento de categorías especiales de datos y garantías adicionales"
        ));

        // R15 — Medidas de seguridad (MEDIA, peso 4)
        REGLAS.add(new Regla(
            "R15",
            "Medidas de seguridad técnicas no mencionadas",
            "No se hace referencia a las medidas de seguridad técnicas y organizativas implementadas para proteger los datos.",
            "MEDIA", 4,
            "El documento no concreta las medidas aplicadas. El Art. 32 RGPD exige aplicar medidas técnicas y organizativas apropiadas; su implantación real no puede verificarse con el texto.",
            "Mencionar las principales medidas de seguridad: cifrado en tránsito (HTTPS/TLS), control de accesos, copias de seguridad y procedimientos ante brechas.",
            "RGPD Art. 32 — Seguridad del tratamiento",
            "\"Aplicamos medidas técnicas y organizativas apropiadas: cifrado SSL/TLS, control de acceso basado en roles, copias de seguridad periódicas y procedimientos de notificación de brechas (Art. 33 RGPD).\"",
            "Descripción de medidas de seguridad técnicas y organizativas aplicadas"
        ));

        // R16 — Aviso legal LSSI-CE (ALTA, peso 7) — NUEVA
        REGLAS.add(new Regla(
            "R16",
            "Aviso legal con información LSSI-CE ausente o incompleto",
            "No se incluye la información obligatoria del Art. 10 LSSI-CE: razón social, NIF, domicilio, datos de inscripción registral y datos de contacto.",
            "ALTA", 7,
            "Incumplimiento del Art. 10 LSSI-CE. Obligatorio para toda web que preste servicios de la sociedad de la información. Infracción leve-grave: hasta 150.000€.",
            "Incluir sección de Aviso Legal con: razón social, NIF/CIF, domicilio social, datos de inscripción registral, email de contacto y número de teléfono.",
            "LSSI-CE Art. 10 — Información general obligatoria del prestador de servicios",
            "\"En cumplimiento del Art. 10 de la Ley 34/2002, LSSI-CE: Razón social: [Nombre S.L.]. NIF: [BXXXXXXXX]. Domicilio: [dirección]. Inscrita en el Registro Mercantil de [ciudad], Tomo [X], Folio [X]. Email: [contacto@empresa.com].\"",
            "Aviso legal con datos identificativos completos del prestador (LSSI-CE Art. 10)"
        ));

        // R17 — Decisiones automatizadas y perfilado (MEDIA, peso 4) — NUEVA
        REGLAS.add(new Regla(
            "R17",
            "Información sobre decisiones automatizadas o perfilado ausente",
            "No se informa de si existen decisiones basadas únicamente en un tratamiento automatizado, incluida la elaboración de perfiles, con efectos jurídicos o significativos para el interesado.",
            "MEDIA", 4,
            "Si existen decisiones del Art. 22 RGPD, el Art. 13.2.f RGPD exige informar de su existencia, de la lógica aplicada y de sus consecuencias previstas. El texto no permite saber si existen.",
            "Indicar si se aplican decisiones automatizadas o perfilado. Si no aplica, declararlo expresamente. Si aplica, informar de la lógica aplicada y sus consecuencias.",
            "RGPD Art. 22 — Decisiones automatizadas y perfilado / Art. 13.2.f",
            "\"No adoptamos decisiones basadas únicamente en tratamiento automatizado que produzcan efectos jurídicos o significativos sobre usted. / Utilizamos perfilado para [finalidad]. Tiene derecho a obtener intervención humana y expresar su punto de vista.\"",
            "Información sobre decisiones automatizadas y/o perfilado (Art. 22 RGPD)"
        ));

        // R18 — Notificación de brechas de seguridad (MEDIA, peso 3) — NUEVA
        REGLAS.add(new Regla(
            "R18",
            "Procedimiento ante brechas de seguridad no mencionado",
            "No se informa de cómo se notifican las brechas de seguridad de los datos personales a la autoridad de control y a las personas afectadas.",
            "MEDIA", 3,
            "Publicar el procedimiento interno no es una obligación de información. Si se produce una brecha, los Arts. 33 y 34 RGPD obligan a notificarla a la autoridad de control y, cuando suponga un alto riesgo, a comunicarla a los afectados; el texto no permite comprobarlo.",
            "Si se quiere informar de ello, indicar que las brechas se notifican a la AEPD en 72 horas y a los afectados cuando supongan un alto riesgo (Arts. 33 y 34 RGPD).",
            "RGPD Arts. 33-34 — Notificación de brechas de seguridad",
            "\"Disponemos de procedimientos para detectar, analizar y notificar posibles brechas de seguridad a la AEPD en el plazo de 72 horas y a los interesados afectados cuando exista alto riesgo (Art. 34 RGPD).\"",
            "Procedimiento de notificación de brechas de seguridad (Arts. 33-34 RGPD)"
        ));

        // R19 — Exclusión publicitaria / comunicaciones comerciales (MEDIA, peso 3) — NUEVA
        REGLAS.add(new Regla(
            "R19",
            "Información sobre comunicaciones comerciales y derecho de exclusión publicitaria ausente",
            "No se informa de si se envían comunicaciones comerciales ni, en su caso, de en qué se basan y cómo darse de baja.",
            "MEDIA", 3,
            "Si se envían comunicaciones comerciales por medios electrónicos, el Art. 21 LSSI-CE exige consentimiento previo, salvo relación contractual previa y productos o servicios similares, y una forma sencilla y gratuita de oponerse (Art. 22 LSSI-CE y Art. 21 RGPD). El texto no permite saber si se envían.",
            "Informar sobre el envío de comunicaciones comerciales, la base que lo ampara y el mecanismo sencillo para oponerse o darse de baja.",
            "LSSI-CE Arts. 21 y 22 — Comunicaciones comerciales / RGPD Art. 21 / LOPDGDD Art. 23",
            "\"Podemos enviarte comunicaciones comerciales si has dado tu consentimiento. Puedes darte de baja en cualquier momento haciendo clic en el enlace 'Cancelar suscripción' de cualquier email o escribiendo a [email].\"",
            "Información sobre comunicaciones comerciales y derecho de oposición/baja"
        ));

        // SUMA TOTAL DE PESOS:
        // R01(8)+R02(6)+R03(4)+R04(8)+R05(8)+R06(8)+R07(5)+R08(5)+R09(4)
        // +R10(7)+R11(4)+R12(6)+R13(3)+R14(3)+R15(4)+R16(7)+R17(4)+R18(3)+R19(3)
        // = 100 ✓
    }

    // -----------------------------------------------------------------------
    // Patrones de riesgo globales
    // Detectan lenguaje claramente ilegal independientemente de las reglas.
    // Cada patrón detectado resta 2 puntos del total (máx -10 adicionales).
    // -----------------------------------------------------------------------

    private record PatronRiesgo(String id, String titulo, String descripcion, String severidad,
                                List<String> patrones) {}

    private static final List<PatronRiesgo> PATRONES_RIESGO_GLOBALES = List.of(
        new PatronRiesgo("G01",
            "Cláusula abusiva de cesión de datos",
            "Se detecta lenguaje que indica cesión o venta de datos sin base legal adecuada.",
            "ALTA",
            List.of("cederemos sus datos", "venderemos sus datos", "vendemos sus datos", "transferimos sus datos a socios publicitarios sin restriccion")),
        new PatronRiesgo("G02",
            "Conservación de datos de forma indefinida",
            "Se detecta referencia a conservación de datos sin límite temporal, contraria al principio de limitación del plazo de conservación (Art. 5.1.e RGPD).",
            "ALTA",
            List.of("indefinidamente", "sin limite de tiempo", "de forma indefinida", "almacenamos sus datos permanentemente")),
        new PatronRiesgo("G03",
            "Consentimiento tácito o por defecto",
            "Se detectan prácticas de consentimiento que no cumplen con los requisitos del Art. 7 RGPD (libre, específico, informado e inequívoco).",
            "ALTA",
            List.of("continuar navegando implica", "al usar este sitio acepta", "se entiende prestado el consentimiento", "el silencio implica aceptacion")),
        new PatronRiesgo("G04",
            "Exclusión de responsabilidad en seguridad",
            "El documento declina responsabilidad sobre la seguridad, contradiciendo el Art. 32 RGPD.",
            "MEDIA",
            List.of("no podemos garantizar la seguridad", "no somos responsables de brechas", "transmision no es segura", "no garantizamos la confidencialidad")),
        new PatronRiesgo("G05",
            "Tecnologías de seguimiento invasivo sin información",
            "Se detectan tecnologías de seguimiento (tracking, fingerprinting, beacons) que requieren información y consentimiento explícito.",
            "MEDIA",
            List.of("fingerprinting", "web beacon", "pixel de seguimiento", "pixel de rastreo", "supercookie", "local shared object"))
    );

    // -----------------------------------------------------------------------
    // Análisis legal principal
    // -----------------------------------------------------------------------

    @Override
    public ResultadoAuditoria analyze(EntradaAnalisis entrada) {
        String texto = entrada.texto();

        if (texto == null || texto.isBlank()) {
            return generarResultadoTextoVacio();
        }

        String textoNorm = normalizar(texto);
        List<ReglasContextuales.Clausula> clausulas = ReglasContextuales.segmentar(texto);
        List<EvaluacionRegla> evaluaciones = new ArrayList<>();

        List<ErrorAuditoria> errores = new ArrayList<>();
        List<String> riesgos = new ArrayList<>();
        List<String> recomendaciones = new ArrayList<>();
        List<String> textosSugeridos = new ArrayList<>();
        List<String> referenciasLegales = new ArrayList<>(Arrays.asList(
            "RGPD - Reglamento (UE) 2016/679",
            "LOPDGDD - Ley Orgánica 3/2018 (consolidada diciembre 2025)",
            "LSSI-CE - Ley 34/2002",
            "Directiva ePrivacy 2002/58/CE",
            "Ley 10/2025 - Modificaciones privacidad y telecomunicaciones",
            "Guía sobre uso de cookies - AEPD (2025)"
        ));
        List<String> faltantes = new ArrayList<>();

        // Sistema porcentual: empieza en 0, suma el peso de cada regla cumplida
        int puntuacion = 0;
        int incumplimientosAlta = 0;
        int incumplimientosMedia = 0;
        int incumplimientosBaja = 0;

        // ---- Evaluar cada regla ----
        List<ControlPuntuado> controles = new ArrayList<>();
        for (Regla regla : REGLAS) {
            int antes = puntuacion;
            var e = ReglasContextuales.evaluar(regla.id, clausulas, entrada.tipoDocumento());
            evaluaciones.add(new EvaluacionRegla(regla.id, e.estado(), e.evidencia(), e.detalle()));
            String severidad = null;
            switch (e.estado()) {
                case SUFICIENTE -> puntuacion += regla.peso;
                case INSUFICIENTE -> {
                    var textos = ReglasContextuales.TEXTOS.get(regla.id);
                    severidad = rebajar(regla.severidad);
                    errores.add(errorContextual(regla.id, textos.tituloInsuficiente(),
                            textos.descripcionInsuficiente() + " " + e.detalle(), severidad, e.evidencia(),
                            regla.impacto, regla.accion, e.estado()));
                    riesgos.add(textos.tituloInsuficiente());
                    anotarCarencia(regla, recomendaciones, textosSugeridos, faltantes, referenciasLegales);
                    puntuacion += regla.peso / 2;
                }
                case POSIBLE_INCIDENCIA -> {
                    if (e.problematica()) {
                        severidad = "ALTA";
                        errores.add(errorContextual(regla.id, PREFIJO_RIESGO + regla.titulo,
                                "La cláusula contiene una redacción que puede ser contraria a la normativa. " + e.detalle(),
                                severidad, e.evidencia(),
                                "Esta redacción puede ser considerada ilegal o abusiva según la normativa vigente.",
                                "Revisar y reformular la cláusula citada como evidencia.", e.estado()));
                        riesgos.add("Redacción problemática en: " + regla.titulo);
                        if (e.suficiente()) puntuacion += regla.peso / 2;
                    } else {
                        severidad = regla.severidad;
                        errores.add(errorContextual(regla.id, regla.titulo, regla.descripcion + " " + e.detalle(),
                                severidad, "No se ha encontrado en el texto analizado ninguna cláusula sobre: "
                                        + regla.campoFaltante + ".", regla.impacto, regla.accion, e.estado()));
                        riesgos.add(regla.titulo);
                    }
                    if (!e.suficiente()) {
                        anotarCarencia(regla, recomendaciones, textosSugeridos, faltantes, referenciasLegales);
                    }
                }
                case NO_VERIFICABLE -> {
                    // Not a finding: the text cannot tell whether the information is required.
                    faltantes.add(regla.campoFaltante + ": " + e.detalle());
                    recomendaciones.add(regla.accion);
                    textosSugeridos.add(regla.textoCumplimiento);
                }
                case NO_APLICABLE -> { /* Not demanded from this type of document. */ }
            }
            if (severidad != null) {
                switch (severidad) {
                    case "ALTA" -> incumplimientosAlta++;
                    case "MEDIA" -> incumplimientosMedia++;
                    default -> incumplimientosBaja++;
                }
            }
            controles.add(new ControlPuntuado(regla.id, regla.peso, puntuacion - antes,
                    ResultadoAuditoria.MetodoControl.CONTEXTUAL, e.estado()));
        }
        int puntosAcreditados = puntuacion;

        // ---- Evaluar patrones de riesgo globales (restan hasta 2 pts cada uno) ----
        for (PatronRiesgo patron : PATRONES_RIESGO_GLOBALES) {
            String nombreRiesgo = patron.titulo();
            String descripRiesgo = patron.descripcion();
            String severidadRiesgo = patron.severidad();

            if (patron.id().equals("G01") || patron.id().equals("G02") || patron.id().equals("G03")) {
                // Negation-aware: "no vendemos sus datos" or a quoted example of implied consent is not a risk.
                var clausula = switch (patron.id()) {
                    case "G01" -> ReglasContextuales.g01(clausulas);
                    case "G02" -> ReglasContextuales.g02(clausulas);
                    default -> ReglasContextuales.g03(clausulas);
                };
                if (clausula != null) {
                    ErrorAuditoria error = nuevoError(patron.id());
                    error.setTitulo(nombreRiesgo);
                    error.setDescripcion(descripRiesgo);
                    error.setSeveridad(severidadRiesgo);
                    error.setEvidencia(ReglasContextuales.cita(clausula));
                    error.setImpacto("Posible incumplimiento de la normativa de protección de datos.");
                    error.setAccion("Revisar y reformular el texto citado como evidencia.");
                    error.setEstado(EstadoEvidencia.POSIBLE_INCIDENCIA);
                    errores.add(error);
                    riesgos.add(nombreRiesgo);
                    puntuacion = Math.max(0, puntuacion - 2);
                    incumplimientosAlta++;
                }
                continue;
            }
            for (String expresion : patron.patrones()) {
                if (textoNorm.contains(normalizar(expresion))) {
                    ErrorAuditoria error = nuevoError(patron.id());
                    error.setTitulo(nombreRiesgo);
                    error.setDescripcion(descripRiesgo);
                    error.setSeveridad(severidadRiesgo);
                    error.setEvidencia("Patrón detectado: \"" + expresion + "\"");
                    error.setImpacto("Posible incumplimiento de la normativa de protección de datos.");
                    error.setAccion("Revisar y reformular el texto que contiene este patrón.");
                    errores.add(error);
                    riesgos.add(nombreRiesgo);
                    puntuacion = Math.max(0, puntuacion - 2);
                    if ("ALTA".equals(severidadRiesgo)) incumplimientosAlta++;
                    else incumplimientosMedia++;
                    break;
                }
            }
        }

        // Garantizar rango 0-100
        puntuacion = Math.max(0, Math.min(100, puntuacion));
        DesglosePuntuacion desglose = desglose(puntuacion, puntosAcreditados, controles);

        // ---- Recomendaciones generales siempre presentes ----
        recomendaciones.add("Revisar y actualizar el documento al menos una vez al año o ante cambios normativos.");
        recomendaciones.add("Asegurar que todos los formularios de recogida de datos incluyen información en dos capas (básica + enlace a política completa).");
        recomendaciones.add("Formar al personal en protección de datos y establecer un procedimiento interno de gestión de brechas de seguridad (Arts. 33-34 RGPD).");

        // ---- Generar resumen ----
        String resumen = generarResumen(puntuacion, incumplimientosAlta, incumplimientosMedia, incumplimientosBaja, errores.size())
                + resumenContextual(evaluaciones) + resumenPuntuacion(desglose);

        ResultadoAuditoria resultado = new ResultadoAuditoria();
        resultado.setResumen(resumen);
        resultado.setPuntuacionRiesgo(puntuacion);
        resultado.setRiesgos(riesgos);
        resultado.setErrores(errores);
        resultado.setRecomendaciones(recomendaciones);
        resultado.setTextosSugeridos(textosSugeridos);
        resultado.setReferenciasLegales(referenciasLegales);
        resultado.setFaltantes(faltantes);
        resultado.setEvaluaciones(evaluaciones);
        resultado.setDesglosePuntuacion(desglose);

        return resultado;
    }

    private static final String PREFIJO_RIESGO = "Cláusula presente pero con redacción problemática: ";

    private ErrorAuditoria errorContextual(String ruleId, String titulo, String descripcion, String severidad,
                                           String evidencia, String impacto, String accion, EstadoEvidencia estado) {
        ErrorAuditoria error = nuevoError(ruleId);
        error.setTitulo(titulo);
        error.setDescripcion(descripcion);
        error.setSeveridad(severidad);
        error.setEvidencia(evidencia);
        error.setImpacto(impacto);
        error.setAccion(accion);
        error.setEstado(estado);
        return error;
    }

    /** Same advice the keyword rules give for a missing clause. */
    private static void anotarCarencia(Regla regla, List<String> recomendaciones, List<String> textosSugeridos,
                                       List<String> faltantes, List<String> referenciasLegales) {
        recomendaciones.add(regla.accion);
        textosSugeridos.add(regla.textoCumplimiento);
        faltantes.add(regla.campoFaltante);
        if (!referenciasLegales.contains(regla.referenciaLegal)) referenciasLegales.add(regla.referenciaLegal);
    }

    /** Some information is given, so one level below the rule's own severity. */
    private static String rebajar(String severidad) {
        return switch (severidad) {
            case "ALTA" -> "MEDIA";
            default -> "BAJA";
        };
    }

    static final String VERSION_DESGLOSE = "desglose-1";

    /** Splits the 100 points by what the text could and could not accredit; the score itself is unchanged. */
    private static DesglosePuntuacion desglose(int puntuacion, int puntosAcreditados, List<ControlPuntuado> controles) {
        int noAplicable = 0, noVerificable = 0, contextual = 0, puntosContextuales = 0, claves = 0, puntosClaves = 0;
        for (ControlPuntuado c : controles) {
            if (c.metodo() == ResultadoAuditoria.MetodoControl.PALABRAS_CLAVE) {
                claves += c.peso();
                puntosClaves += c.puntos();
            } else if (c.estado() == EstadoEvidencia.NO_APLICABLE) {
                noAplicable += c.peso();
            } else if (c.estado() == EstadoEvidencia.NO_VERIFICABLE) {
                noVerificable += c.peso();
            } else {
                contextual += c.peso();
                puntosContextuales += c.puntos();
            }
        }
        // Only contextual controls know whether they apply. Since rules 5 every control is contextual, so the keyword
        // weight is 0; the share stays an evidence measure, never a compliance percentage.
        Integer porcentaje = contextual > 0 ? Math.round(100f * puntosContextuales / contextual) : null;
        String motivo = contextual > 0 ? null
                : "Ningún control evaluado por contexto es aplicable y verificable en este documento.";
        return new DesglosePuntuacion(VERSION_DESGLOSE, puntuacion, 100, puntosAcreditados, puntosAcreditados - puntuacion,
                noAplicable, noVerificable, contextual, puntosContextuales, claves, puntosClaves, porcentaje, motivo,
                List.copyOf(controles));
    }

    private static String resumenPuntuacion(DesglosePuntuacion d) {
        String fuera = d.pesoNoAplicable() + d.pesoNoVerificable() > 0
                ? String.format(" %d de los 100 puntos corresponden a controles no aplicables a este tipo de documento o no "
                        + "verificables con el texto, y no se suman: la puntuación no es comparable con la de otros tipos de documento.",
                        d.pesoNoAplicable() + d.pesoNoVerificable())
                : "";
        return fuera + " La puntuación resume la evidencia encontrada en el texto; no certifica el cumplimiento normativo "
                + "ni prueba el comportamiento real del sitio web.";
    }

    private static String resumenContextual(List<EvaluacionRegla> evaluaciones) {
        Map<EstadoEvidencia, Long> cuenta = evaluaciones.stream()
                .collect(Collectors.groupingBy(EvaluacionRegla::estado, () -> new EnumMap<>(EstadoEvidencia.class),
                        Collectors.counting()));
        return String.format(" Información evaluada por contexto (R01 a R19): "
                        + "%d con evidencia suficiente, %d con evidencia insuficiente, %d con posible incidencia, "
                        + "%d no verificables con el texto y %d no aplicables a este tipo de documento. "
                        + "La evaluación es automática y puede equivocarse aunque todos los controles se lean por contexto.",
                cuenta.getOrDefault(EstadoEvidencia.SUFICIENTE, 0L), cuenta.getOrDefault(EstadoEvidencia.INSUFICIENTE, 0L),
                cuenta.getOrDefault(EstadoEvidencia.POSIBLE_INCIDENCIA, 0L),
                cuenta.getOrDefault(EstadoEvidencia.NO_VERIFICABLE, 0L), cuenta.getOrDefault(EstadoEvidencia.NO_APLICABLE, 0L));
    }

    // -----------------------------------------------------------------------
    // Métodos auxiliares
    // -----------------------------------------------------------------------

    private ErrorAuditoria nuevoError(String ruleId) {
        ErrorAuditoria error = new ErrorAuditoria();
        error.setRuleId(ruleId);
        error.setMotor(MOTOR);
        error.setVersion(version().versionReglas());
        return error;
    }

    /**
     * Normaliza el texto: minúsculas, elimina acentos/diacríticos y colapsa espacios.
     * Eliminar acentos evita fallos de encoding (p.ej. "finalidad" vs "finalidad" con tilde).
     */
    private String normalizar(String texto) {
        if (texto == null) return "";
        String sinAcentos = Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}", "");
        return sinAcentos
                .toLowerCase(java.util.Locale.forLanguageTag("es"))
                .replaceAll("[^a-z0-9\\s.,:;@/\\-]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Genera un resumen textual en función de los resultados del análisis.
     */
    private String generarResumen(int puntuacion, int alta, int media, int baja, int totalErrores) {
        String nivel;
        String descripcionNivel;

        if (puntuacion >= 85) {
            nivel = "BAJO RIESGO";
            descripcionNivel = "El texto aporta evidencia para la mayoría de los controles evaluados; conviene revisar los aspectos señalados.";
        } else if (puntuacion >= 65) {
            nivel = "RIESGO MODERADO";
            descripcionNivel = "El texto aporta evidencia para parte de los controles evaluados; hay carencias concretas que revisar.";
        } else if (puntuacion >= 40) {
            nivel = "RIESGO ALTO";
            descripcionNivel = "El texto no aporta evidencia para varios controles relevantes; conviene una revisión detallada.";
        } else {
            nivel = "RIESGO MUY ALTO";
            descripcionNivel = "El texto aporta poca evidencia para los controles evaluados.";
        }

        return String.format(
            "Análisis SmartAudits completado. Nivel de riesgo: %s (puntuación: %d/100). " +
            "%s Se han detectado %d incidencia(s) en total: %d de severidad ALTA, " +
            "%d de severidad MEDIA y %d de severidad BAJA. " +
            "Normativa evaluada: RGPD (UE 2016/679), LOPDGDD (LO 3/2018, consolidada dic. 2025), " +
            "LSSI-CE (Ley 34/2002), Ley 10/2025 y Directiva ePrivacy 2002/58/CE.",
            nivel, puntuacion, descripcionNivel, totalErrores, alta, media, baja
        );
    }

    /**
     * Resultado para textos vacíos o nulos.
     */
    private ResultadoAuditoria generarResultadoTextoVacio() {
        ResultadoAuditoria resultado = new ResultadoAuditoria();
        resultado.setResumen("No se ha podido analizar el documento: el texto proporcionado está vacío.");
        resultado.setPuntuacionRiesgo(0);
        resultado.setRiesgos(Arrays.asList("Texto de entrada vacío o no proporcionado."));
        resultado.setErrores(Collections.emptyList());
        resultado.setRecomendaciones(Arrays.asList("Proporcione el texto del documento legal que desea auditar."));
        resultado.setTextosSugeridos(Collections.emptyList());
        resultado.setReferenciasLegales(Collections.emptyList());
        resultado.setFaltantes(Collections.emptyList());
        return resultado;
    }
}
