package com.smartaudits.service.motor;

import com.smartaudits.model.dto.ResultadoAuditoria;
import com.smartaudits.model.dto.ResultadoAuditoria.ErrorAuditoria;
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
 * No utiliza APIs externas. Todo el análisis se realiza mediante reglas en Java.
 */
@Service
public class MotorAnalisisLegal {

    // -----------------------------------------------------------------------
    // Clase interna: Regla de análisis
    // -----------------------------------------------------------------------

    private static class Regla {
        final String id;
        final String titulo;
        final String descripcion;
        final String severidad;           // ALTA, MEDIA, BAJA
        final int peso;                   // Puntos que aporta si se cumple (suma total = 100)
        final List<String> palabrasClave; // Al menos UNA debe estar presente para cumplir
        final List<String> patronesRiesgo; // Si ALGUNO aparece en cláusula existente → cumplimiento parcial
        final String impacto;
        final String accion;
        final String referenciaLegal;
        final String textoCumplimiento;
        final String campoFaltante;

        Regla(String id, String titulo, String descripcion, String severidad,
              int peso, List<String> palabrasClave, List<String> patronesRiesgo,
              String impacto, String accion, String referenciaLegal,
              String textoCumplimiento, String campoFaltante) {
            this.id = id;
            this.titulo = titulo;
            this.descripcion = descripcion;
            this.severidad = severidad;
            this.peso = peso;
            this.palabrasClave = palabrasClave;
            this.patronesRiesgo = patronesRiesgo;
            this.impacto = impacto;
            this.accion = accion;
            this.referenciaLegal = referenciaLegal;
            this.textoCumplimiento = textoCumplimiento;
            this.campoFaltante = campoFaltante;
        }
    }

    // -----------------------------------------------------------------------
    // Catálogo de reglas
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
            Arrays.asList(
                "responsable del tratamiento", "responsable de tratamiento",
                "responsable es", "responsable:", "razon social", "razon social:",
                "denominacion social", "empresa responsable", "identidad del responsable",
                "titular del sitio", "titular de la web", "titular del portal",
                "sociedad limitada", "sociedad anonima", " s.l.", " s.a.", " s.l.u.",
                "con domicilio", "con domicilio social", "con nif", "con cif",
                "inscrita en el registro", "registro mercantil",
                "quien gestiona", "quien trata sus datos", "empresa que gestiona"
            ),
            Collections.emptyList(),
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
            Arrays.asList(
                "contacto", "email", "correo electronico", "correo-electronico",
                "telefono", "telefono de contacto", "numero de telefono",
                "direccion postal", "apartado de correos",
                "formulario de contacto", "atencion al cliente", "servicio al cliente",
                "puede contactar", "puede contactarnos", "puede dirigirse",
                "puede ponerse en contacto", "contactenos", "escribenos",
                "para cualquier consulta", "ante cualquier duda"
            ),
            Collections.emptyList(),
            "Incumplimiento del Art. 13.1.a RGPD. El interesado no puede ejercer sus derechos por falta de contacto claro.",
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
            Arrays.asList(
                "delegado de proteccion de datos", "delegado de privacidad",
                "dpo", "data protection officer", "dpd",
                "responsable de privacidad", "responsable de proteccion",
                "oficial de proteccion", "figura del delegado",
                "no es obligatorio designar", "no procede la designacion",
                "no estamos obligados a designar"
            ),
            Collections.emptyList(),
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
            Arrays.asList(
                "finalidad", "finalidades", "para que tratamos", "para que usamos",
                "con el fin de", "con la finalidad de", "con el objeto de",
                "objetivo del tratamiento", "proposito", "propositos",
                "tratamos sus datos para", "usamos sus datos para",
                "gestion de clientes", "gestion de usuarios", "prestacion del servicio",
                "gestion de pedidos", "gestion de compras", "envio de comunicaciones",
                "comunicaciones comerciales", "analitica web", "mejora del servicio",
                "cumplimiento de obligaciones legales", "cumplimiento legal",
                "para poder ofrecerle", "para gestionar su"
            ),
            Collections.emptyList(),
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
            Arrays.asList(
                "base legal", "base juridica", "legitimacion", "legitimacion del tratamiento",
                "consentimiento del interesado", "ejecucion del contrato", "cumplimiento del contrato",
                "obligacion legal", "obligaciones legales", "interes legitimo",
                "interes vital", "interes publico", "mision de interes publico",
                "art. 6", "articulo 6", "articulo 6 rgpd", "6.1", "6.1.a", "6.1.b", "6.1.c",
                "amparo legal", "amparo juridico", "nos ampara",
                "legitima el tratamiento", "que legitima", "que autoriza el tratamiento",
                "en virtud de", "en base a", "con fundamento en"
            ),
            Collections.emptyList(),
            "Incumplimiento del Art. 6 y 13.1.c RGPD. Sin base legal válida, todo tratamiento es ilícito. Máxima exposición a sanciones.",
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
            Arrays.asList(
                "derecho de acceso", "derecho de rectificacion", "derecho de supresion",
                "derecho al olvido", "derecho de oposicion", "derecho de portabilidad",
                "derecho de limitacion", "derecho a la limitacion",
                "arsulipo", "arco", "derechos arco",
                "puede ejercer sus derechos", "puede ejercitar sus derechos",
                "ejercicio de derechos", "sus derechos como interesado",
                "derechos que le asisten", "derechos reconocidos",
                "acceder a sus datos", "rectificar sus datos", "suprimir sus datos",
                "cancelar sus datos", "oponerse al tratamiento",
                "solicitar la portabilidad", "limitar el tratamiento",
                "art. 15", "art. 16", "art. 17", "art. 18", "art. 20", "art. 21",
                "articulo 15", "articulo 17", "articulo 21"
            ),
            Collections.emptyList(),
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
            Arrays.asList(
                "plazo de conservacion", "periodo de conservacion", "tiempo de conservacion",
                "durante cuanto tiempo", "conservaremos sus datos", "conservacion de datos",
                "suprimiremos", "bloqueados durante", "plazo legal", "plazo maximo",
                "mientras dure la relacion", "durante la vigencia del contrato",
                "una vez finalizada la relacion", "transcurrido el plazo",
                "anos desde", "meses desde", "anos a partir",
                "obligacion de conservacion", "plazos legales de conservacion",
                "cinco anos", "cuatro anos", "seis anos", "diez anos",
                "5 anos", "4 anos", "6 anos", "10 anos"
            ),
            Arrays.asList(
                "indefinidamente", "indefinido", "sin plazo determinado",
                "hasta que lo solicite", "de forma indefinida", "sin limite de tiempo"
            ),
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
            Arrays.asList(
                "destinatarios", "cesion de datos", "comunicacion de datos",
                "terceros", "encargados de tratamiento", "encargados del tratamiento",
                "proveedores", "no se cedera", "no cedemos", "no compartimos",
                "compartiremos", "compartimos sus datos", "acceso de terceros",
                "prestadores de servicios", "empresas colaboradoras",
                "socios comerciales", "entidades del grupo",
                "plataforma de pago", "pasarela de pago", "proveedor de hosting",
                "no se realizan cesiones", "no se producen cesiones"
            ),
            Arrays.asList(
                "cederemos sus datos a terceros sin su consentimiento",
                "vendemos sus datos", "compartimos sus datos con anunciantes sin restriccion"
            ),
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
            Arrays.asList(
                "transferencias internacionales", "transferencia internacional",
                "fuera del eee", "fuera de la union europea", "fuera de europa",
                "terceros paises", "paises terceros", "clausulas tipo",
                "clausulas contractuales tipo", "decision de adecuacion",
                "privacy shield", "marco de privacidad", "bcr", "normas corporativas vinculantes",
                "estados unidos", "reino unido", "servidores fuera",
                "no se realizan transferencias", "no se producen transferencias internacionales",
                "todos los datos se almacenan en la ue", "servidores en la union europea"
            ),
            Collections.emptyList(),
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
            Arrays.asList(
                "cookies", "cookie", "politica de cookies", "uso de cookies",
                "gestion de cookies", "configurar cookies", "aceptar cookies",
                "rechazar cookies", "cookies tecnicas", "cookies analiticas",
                "cookies publicitarias", "cookies de seguimiento", "cookies de terceros",
                "cookies propias", "cookies de sesion", "cookies persistentes",
                "panel de cookies", "configuracion de cookies", "preferencias de cookies",
                "consentimiento de cookies", "centro de preferencias",
                "puede rechazar", "puede desactivar", "puede configurar",
                "exentas de consentimiento", "no requieren consentimiento"
            ),
            Arrays.asList(
                "instalamos cookies sin su consentimiento",
                "no puede rechazar las cookies",
                "continuar navegando implica aceptar"
            ),
            "Incumplimiento del Art. 22.2 LSSI-CE y Directiva ePrivacy. Multas de hasta 300.000€. La AEPD exige en 2025 que los botones de aceptar y rechazar estén al mismo nivel de visibilidad.",
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
            Arrays.asList(
                "aepd", "agencia espanola de proteccion de datos",
                "autoridad de control", "autoridad de supervision",
                "presentar una reclamacion", "reclamar ante",
                "www.aepd.es", "aepd.es",
                "organismo supervisor", "autoridad supervisora",
                "derecho a reclamar", "puede reclamar ante",
                "supervisora competente", "autoridad competente en materia de proteccion"
            ),
            Collections.emptyList(),
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
            Arrays.asList(
                "consentimiento", "consent", "otorga su consentimiento",
                "acepta el tratamiento", "prestar su consentimiento",
                "retirar su consentimiento", "retirar el consentimiento",
                "revocar consentimiento", "retirada del consentimiento",
                "libre", "inequivoco", "especifico", "informado",
                "manifestacion de voluntad", "acto positivo",
                "casilla de verificacion", "casilla sin marcar", "checkbox",
                "puede retirar", "puede revocar", "sin efecto retroactivo",
                "sin perjuicio de la licitud"
            ),
            Arrays.asList(
                "su consentimiento se entendera prestado",
                "el silencio implica consentimiento",
                "casilla marcada por defecto",
                "continuar navegando implica consentimiento",
                "al usar este sitio acepta"
            ),
            "Incumplimiento del Art. 7 y 4.11 RGPD. El consentimiento debe ser libre, específico, informado e inequívoco.",
            "Describir el mecanismo de consentimiento: qué acción lo constituye, para qué tratamientos se pide y cómo puede retirarse en cualquier momento.",
            "RGPD Art. 7 — Condiciones para el consentimiento / Art. 4.11",
            "\"Su consentimiento se solicita mediante casilla de verificación desmarcada por defecto. Puede retirarlo en cualquier momento sin que ello afecte a la licitud del tratamiento anterior.\"",
            "Mecanismo claro de obtención y retirada del consentimiento"
        ));

        // R13 — Datos de menores actualizado LOPDGDD 2025 (BAJA, peso 3)
        REGLAS.add(new Regla(
            "R13",
            "Tratamiento de datos de menores no regulado",
            "No se especifican medidas relativas al tratamiento de datos de menores de 14 años (LOPDGDD consolidada 2025).",
            "BAJA", 3,
            Arrays.asList(
                "menores", "menor de edad", "menores de edad", "14 anos", "catorce anos",
                "menor de 14", "menores de 14", "16 anos", "dieciseis anos",
                "patria potestad", "autorizacion parental", "consentimiento parental",
                "tutores", "representantes legales", "edad minima", "mayores de edad",
                "nuestro servicio no esta dirigido a menores",
                "no recopilamos datos de menores", "verificacion de edad",
                "art. 8 rgpd", "articulo 8", "art. 7 lopdgdd"
            ),
            Arrays.asList(
                "no verificamos la edad", "cualquier persona puede registrarse",
                "no existe restriccion de edad"
            ),
            "Posible incumplimiento del Art. 8 RGPD y Art. 7 LOPDGDD. En España, los menores de 14 años requieren consentimiento parental. La LOPDGDD consolidada (dic. 2025) confirma este umbral.",
            "Indicar la edad mínima (14 años en España) y las medidas para verificar la edad o recabar el consentimiento de los representantes legales.",
            "RGPD Art. 8 — Consentimiento de menores / LOPDGDD Art. 7 (consolidada dic. 2025)",
            "\"Nuestros servicios están dirigidos a mayores de 14 años. Si tiene menos de 14 años, necesita el consentimiento de sus padres o tutores para utilizar este servicio.\"",
            "Tratamiento de datos de menores de 14 años y consentimiento parental"
        ));

        // R14 — Datos especialmente sensibles (BAJA, peso 3)
        REGLAS.add(new Regla(
            "R14",
            "Tratamiento de categorías especiales de datos no regulado",
            "No se especifican medidas adicionales si se tratan categorías especiales de datos (salud, ideología, origen étnico, etc.).",
            "BAJA", 3,
            Arrays.asList(
                "categorias especiales", "datos especialmente protegidos",
                "datos de salud", "datos medicos", "datos clinicos", "historial medico",
                "origen etnico", "raza", "ideologia politica", "opinion politica",
                "creencias religiosas", "religion", "orientacion sexual", "vida sexual",
                "datos biometricos", "huella dactilar", "reconocimiento facial",
                "datos geneticos", "antecedentes penales", "infracciones penales",
                "art. 9", "articulo 9", "articulo 9 rgpd",
                "no tratamos datos sensibles", "no recopilamos datos de salud",
                "no tratamos categorias especiales"
            ),
            Collections.emptyList(),
            "Si se tratan categorías especiales sin las garantías del Art. 9 RGPD, el tratamiento es directamente ilícito. Sanciones del nivel más alto.",
            "Si se tratan datos sensibles, indicar explícitamente la excepción del Art. 9.2 RGPD aplicable y las medidas de seguridad adicionales implementadas.",
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
            Arrays.asList(
                "medidas de seguridad", "seguridad de los datos", "cifrado",
                "encriptacion", "ssl", "tls", "https", "protocolo seguro",
                "acceso restringido", "control de acceso", "pseudonimizacion",
                "anonimizacion", "copia de seguridad", "backup",
                "proteccion de datos desde el diseno", "privacy by design",
                "medidas tecnicas y organizativas", "medidas tecnicas",
                "art. 32", "articulo 32",
                "seguridad informatica", "ciberseguridad",
                "acceso autorizado", "personal autorizado", "confidencialidad"
            ),
            Arrays.asList(
                "no podemos garantizar la seguridad",
                "no somos responsables de brechas de seguridad",
                "transmision de datos no es segura", "transmision no garantizada"
            ),
            "Incumplimiento del Art. 32 RGPD. El responsable debe implementar medidas técnicas y organizativas apropiadas.",
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
            Arrays.asList(
                "aviso legal", "informacion legal", "nota legal",
                "datos identificativos", "datos del titular", "datos de la empresa",
                "razon social", "nombre comercial", "denominacion social",
                "nif", "cif", "numero de identificacion fiscal",
                "domicilio social", "sede social", "direccion de la empresa",
                "registro mercantil", "inscrita en el registro", "tomo", "folio", "seccion",
                "numero de registro", "datos registrales",
                "en cumplimiento de la ley 34/2002", "en cumplimiento de la lssi",
                "art. 10 lssi", "articulo 10 de la ley 34"
            ),
            Collections.emptyList(),
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
            "No se informa si el responsable realiza decisiones automatizadas o perfilado con efectos significativos sobre el interesado.",
            "MEDIA", 4,
            Arrays.asList(
                "decisiones automatizadas", "decision automatizada", "toma de decisiones automatizada",
                "perfilado", "elaboracion de perfiles", "creacion de perfiles",
                "algoritmo", "sistema automatizado", "inteligencia artificial",
                "tratamiento automatizado", "logica aplicada",
                "no realizamos decisiones automatizadas", "no aplicamos perfilado",
                "no utilizamos algoritmos para tomar decisiones",
                "art. 22", "articulo 22", "articulo 22 rgpd",
                "decision significativa", "efectos juridicos", "efecto significativo"
            ),
            Collections.emptyList(),
            "Incumplimiento del Art. 13.2.f y Art. 22 RGPD. El interesado tiene derecho a no ser objeto de decisiones basadas únicamente en tratamiento automatizado que produzcan efectos significativos.",
            "Indicar si se aplican decisiones automatizadas o perfilado. Si no aplica, declararlo expresamente. Si aplica, informar de la lógica aplicada y sus consecuencias.",
            "RGPD Art. 22 — Decisiones automatizadas y perfilado / Art. 13.2.f",
            "\"No adoptamos decisiones basadas únicamente en tratamiento automatizado que produzcan efectos jurídicos o significativos sobre usted. / Utilizamos perfilado para [finalidad]. Tiene derecho a obtener intervención humana y expresar su punto de vista.\"",
            "Información sobre decisiones automatizadas y/o perfilado (Art. 22 RGPD)"
        ));

        // R18 — Notificación de brechas de seguridad (MEDIA, peso 3) — NUEVA
        REGLAS.add(new Regla(
            "R18",
            "Procedimiento ante brechas de seguridad no mencionado",
            "No se hace referencia al procedimiento de notificación de brechas de seguridad a la AEPD y a los interesados afectados.",
            "MEDIA", 3,
            Arrays.asList(
                "brecha de seguridad", "violacion de seguridad", "incidente de seguridad",
                "notificacion de brecha", "comunicacion de brecha",
                "notificaremos a la aepd", "comunicaremos el incidente",
                "en caso de brecha", "ante un incidente",
                "art. 33", "art. 34", "articulo 33", "articulo 34",
                "plazo de 72 horas", "72 horas", "notificacion en 72",
                "protocolo de seguridad", "plan de respuesta", "gestion de incidentes",
                "comunicar brechas"
            ),
            Collections.emptyList(),
            "Incumplimiento de los Arts. 33-34 RGPD. El responsable debe notificar brechas de seguridad a la AEPD en 72 horas y a los afectados si suponen alto riesgo.",
            "Mencionar la existencia de un procedimiento de detección y notificación de brechas de seguridad conforme a los Arts. 33-34 RGPD.",
            "RGPD Arts. 33-34 — Notificación de brechas de seguridad",
            "\"Disponemos de procedimientos para detectar, analizar y notificar posibles brechas de seguridad a la AEPD en el plazo de 72 horas y a los interesados afectados cuando exista alto riesgo (Art. 34 RGPD).\"",
            "Procedimiento de notificación de brechas de seguridad (Arts. 33-34 RGPD)"
        ));

        // R19 — Exclusión publicitaria / comunicaciones comerciales (MEDIA, peso 3) — NUEVA
        // Basada en LOPDGDD Art. 23 consolidado 2025 y Ley 10/2025
        REGLAS.add(new Regla(
            "R19",
            "Información sobre comunicaciones comerciales y derecho de exclusión publicitaria ausente",
            "No se informa sobre el envío de comunicaciones comerciales ni sobre el derecho a oponerse o darse de baja.",
            "MEDIA", 3,
            Arrays.asList(
                "comunicaciones comerciales", "comunicacion comercial",
                "publicidad", "email marketing", "boletin", "newsletter",
                "informacion comercial", "oferta comercial", "promociones",
                "darse de baja", "cancelar la suscripcion", "dejar de recibir",
                "derecho de exclusion publicitaria", "lista robinson", "lista de exclusion",
                "no desea recibir", "puede darse de baja", "puede oponerse",
                "baja en la lista", "desubscribirse", "unsubscribe",
                "art. 21 lssi", "articulo 21 lssi", "art. 23 lopdgdd"
            ),
            Arrays.asList(
                "enviaremos publicidad sin su consentimiento",
                "no puede darse de baja de nuestras comunicaciones"
            ),
            "Incumplimiento del Art. 21 LSSI-CE y Art. 23 LOPDGDD (consolidada 2025). Las comunicaciones comerciales requieren consentimiento previo y el usuario debe poder oponerse fácilmente.",
            "Informar sobre el envío de comunicaciones comerciales, la base legal que lo ampara y el mecanismo sencillo para oponerse o darse de baja.",
            "LSSI-CE Art. 21 — Comunicaciones comerciales / LOPDGDD Art. 23 (consolidada 2025)",
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

    private static final List<String[]> PATRONES_RIESGO_GLOBALES = Arrays.asList(
        new String[]{
            "Cláusula abusiva de cesión de datos",
            "Se detecta lenguaje que indica cesión o venta de datos sin base legal adecuada.",
            "ALTA",
            "cederemos sus datos", "venderemos sus datos", "vendemos sus datos",
            "transferimos sus datos a socios publicitarios sin restriccion"
        },
        new String[]{
            "Conservación de datos de forma indefinida",
            "Se detecta referencia a conservación de datos sin límite temporal, contraria al principio de minimización (Art. 5.1.e RGPD).",
            "ALTA",
            "indefinidamente", "sin limite de tiempo", "de forma indefinida",
            "almacenamos sus datos permanentemente"
        },
        new String[]{
            "Consentimiento tácito o por defecto",
            "Se detectan prácticas de consentimiento que no cumplen con los requisitos del Art. 7 RGPD (libre, específico, informado e inequívoco).",
            "ALTA",
            "continuar navegando implica", "al usar este sitio acepta",
            "se entiende prestado el consentimiento", "el silencio implica aceptacion"
        },
        new String[]{
            "Exclusión de responsabilidad en seguridad",
            "El documento declina responsabilidad sobre la seguridad, contradiciendo el Art. 32 RGPD.",
            "MEDIA",
            "no podemos garantizar la seguridad", "no somos responsables de brechas",
            "transmision no es segura", "no garantizamos la confidencialidad"
        },
        new String[]{
            "Tecnologías de seguimiento invasivo sin información",
            "Se detectan tecnologías de seguimiento (tracking, fingerprinting, beacons) que requieren información y consentimiento explícito.",
            "MEDIA",
            "fingerprinting", "web beacon", "pixel de seguimiento",
            "pixel de rastreo", "supercookie", "local shared object"
        }
    );

    // -----------------------------------------------------------------------
    // Análisis legal principal
    // -----------------------------------------------------------------------

    public ResultadoAuditoria analyze(String texto, String tipoDocumento) {

        if (texto == null || texto.isBlank()) {
            return generarResultadoTextoVacio();
        }

        String textoNorm = normalizar(texto);

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
        for (Regla regla : REGLAS) {
            boolean cumple = contienePalabraClave(textoNorm, regla.palabrasClave);

            if (!cumple) {
                // No cumple → no suma puntos, genera incidencia
                ErrorAuditoria error = new ErrorAuditoria();
                error.setTitulo(regla.titulo);
                error.setDescripcion(regla.descripcion);
                error.setSeveridad(regla.severidad);
                error.setEvidencia("Cláusula no detectada en el documento analizado.");
                error.setImpacto(regla.impacto);
                error.setAccion(regla.accion);
                errores.add(error);

                riesgos.add(regla.titulo);
                recomendaciones.add(regla.accion);
                textosSugeridos.add(regla.textoCumplimiento);
                faltantes.add(regla.campoFaltante);

                if (!referenciasLegales.contains(regla.referenciaLegal)) {
                    referenciasLegales.add(regla.referenciaLegal);
                }

                switch (regla.severidad) {
                    case "ALTA":  incumplimientosAlta++;  break;
                    case "MEDIA": incumplimientosMedia++; break;
                    default:      incumplimientosBaja++;  break;
                }

            } else {
                // Cumple → comprobar si tiene lenguaje de riesgo dentro
                boolean tieneRiesgo = false;
                for (String patron : regla.patronesRiesgo) {
                    if (textoNorm.contains(normalizar(patron))) {
                        tieneRiesgo = true;
                        ErrorAuditoria error = new ErrorAuditoria();
                        error.setTitulo("Cláusula presente pero con redacción problemática: " + regla.titulo);
                        error.setDescripcion("La cláusula existe pero contiene lenguaje que puede ser ilegal o abusivo: \"" + patron + "\"");
                        error.setSeveridad("ALTA");
                        error.setEvidencia("Patrón problemático detectado: \"" + patron + "\"");
                        error.setImpacto("Esta redacción puede ser considerada ilegal o abusiva según la normativa vigente.");
                        error.setAccion("Revisar y reformular el texto que contiene \"" + patron + "\"");
                        errores.add(error);
                        riesgos.add("Redacción problemática en: " + regla.titulo);
                        incumplimientosAlta++;
                        break;
                    }
                }
                // Cumplimiento completo → peso completo; con riesgo → mitad del peso
                puntuacion += tieneRiesgo ? (regla.peso / 2) : regla.peso;
            }
        }

        // ---- Evaluar patrones de riesgo globales (restan hasta 2 pts cada uno) ----
        for (String[] patron : PATRONES_RIESGO_GLOBALES) {
            String nombreRiesgo = patron[0];
            String descripRiesgo = patron[1];
            String severidadRiesgo = patron[2];

            for (int i = 3; i < patron.length; i++) {
                if (textoNorm.contains(normalizar(patron[i]))) {
                    ErrorAuditoria error = new ErrorAuditoria();
                    error.setTitulo(nombreRiesgo);
                    error.setDescripcion(descripRiesgo);
                    error.setSeveridad(severidadRiesgo);
                    error.setEvidencia("Patrón detectado: \"" + patron[i] + "\"");
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

        // ---- Recomendaciones generales siempre presentes ----
        recomendaciones.add("Revisar y actualizar el documento al menos una vez al año o ante cambios normativos.");
        recomendaciones.add("Asegurar que todos los formularios de recogida de datos incluyen información en dos capas (básica + enlace a política completa).");
        recomendaciones.add("Formar al personal en protección de datos y establecer un procedimiento interno de gestión de brechas de seguridad (Arts. 33-34 RGPD).");

        // ---- Generar resumen ----
        String resumen = generarResumen(puntuacion, incumplimientosAlta, incumplimientosMedia, incumplimientosBaja, errores.size());

        ResultadoAuditoria resultado = new ResultadoAuditoria();
        resultado.setResumen(resumen);
        resultado.setPuntuacionRiesgo(puntuacion);
        resultado.setRiesgos(riesgos);
        resultado.setErrores(errores);
        resultado.setRecomendaciones(recomendaciones);
        resultado.setTextosSugeridos(textosSugeridos);
        resultado.setReferenciasLegales(referenciasLegales);
        resultado.setFaltantes(faltantes);

        return resultado;
    }

    // -----------------------------------------------------------------------
    // Métodos auxiliares
    // -----------------------------------------------------------------------

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
     * Comprueba si el texto contiene al menos una de las palabras clave.
     */
    private boolean contienePalabraClave(String textoNorm, List<String> palabrasClave) {
        for (String clave : palabrasClave) {
            if (textoNorm.contains(normalizar(clave))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Genera un resumen textual en función de los resultados del análisis.
     */
    private String generarResumen(int puntuacion, int alta, int media, int baja, int totalErrores) {
        String nivel;
        String descripcionNivel;

        if (puntuacion >= 85) {
            nivel = "BAJO RIESGO";
            descripcionNivel = "El documento presenta un nivel de cumplimiento elevado. Se han detectado aspectos menores que conviene revisar para alcanzar el cumplimiento total.";
        } else if (puntuacion >= 65) {
            nivel = "RIESGO MODERADO";
            descripcionNivel = "El documento presenta carencias significativas. Se requieren mejoras concretas para alcanzar un nivel de cumplimiento adecuado y evitar sanciones.";
        } else if (puntuacion >= 40) {
            nivel = "RIESGO ALTO";
            descripcionNivel = "El documento presenta incumplimientos graves de la normativa vigente. Es necesaria una revisión profunda e inmediata para evitar sanciones regulatorias.";
        } else {
            nivel = "RIESGO MUY ALTO";
            descripcionNivel = "El documento presenta incumplimientos críticos y generalizados. La situación actual supone una exposición máxima a sanciones de la AEPD y la Comisión Europea.";
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
