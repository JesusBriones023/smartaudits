package com.smartaudits.service.motor;

import com.smartaudits.model.dto.ResultadoAuditoria.EstadoEvidencia;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Context-aware evaluation of rules R01 to R19 and of the global risks G01, G02 and G03, part of
 * {@link MotorAnalisisLegal}. It reads the clean text as clauses (sentences, paragraphs and list items) and matches
 * whole words with their negation, so that:
 *
 * - a single keyword is never enough: mentioning an obligation is told apart from giving its content;
 * - "no cedemos sus datos" or "no los conservamos indefinidamente" are not read as the opposite;
 * - information that the text alone cannot settle is reported as such, not as a breach;
 * - Art. 13 RGPD information is not demanded from documents whose subject is something else;
 * - a practice (minors' data, special categories, profiling, breaches, marketing) is never assumed to happen.
 *
 * Every evidence is a clause of the analysed text, with its whitespace collapsed.
 */
final class ReglasContextuales {

    private ReglasContextuales() {}

    static final Set<String> IDS = Set.of("R01", "R02", "R03", "R04", "R05", "R06", "R07", "R08", "R09", "R10", "R11",
            "R12", "R13", "R14", "R15", "R16", "R17", "R18", "R19");
    static final int MAX_EVIDENCIA = 300;

    private static final char LF = 10;
    private static final char CR = 13;

    /** Document types whose subject is not the Art. 13 RGPD information: it belongs to the privacy policy. */
    private static final Set<String> TIPOS_SIN_INFORMACION_RGPD = Set.of("Aviso Legal", "Política de Cookies",
            "Términos y Condiciones", "Condiciones de Uso", "Condiciones de Compra", "Accesibilidad");

    record Clausula(String original, List<String> tokens) {}

    /**
     * @param suficiente   the text substantiates the information, whatever else it says
     * @param problematica the wording itself may be contrary to the rules (denial, indefinite retention, sale)
     * @param evidencia    the clause relied on, quoted, or null when there is none
     */
    record Evaluacion(EstadoEvidencia estado, boolean suficiente, boolean problematica, String evidencia, String detalle) {}

    record Textos(String tituloInsuficiente, String descripcionInsuficiente) {}

    static final Map<String, Textos> TEXTOS = Map.ofEntries(
            Map.entry("R01", new Textos("Responsable del tratamiento mencionado sin identificarlo",
                    "Se alude al responsable del tratamiento sin indicar su nombre o razón social, o se nombra una entidad sin "
                            + "presentarla como responsable del tratamiento (Art. 13.1.a RGPD).")),
            Map.entry("R02", new Textos("Medios de contacto del responsable mencionados sin concretarlos",
                    "Se alude al contacto sin un medio concreto (correo, teléfono, dirección o formulario), o aparece un dato "
                            + "de contacto que no se presenta como tal (Art. 13.1.a RGPD).")),
            Map.entry("R04", new Textos("Finalidades del tratamiento mencionadas sin concretarlas",
                    "Se alude a la finalidad del tratamiento sin describir para qué se utilizan los datos (Art. 13.1.c RGPD).")),
            Map.entry("R09", new Textos("Transferencias internacionales mencionadas sin garantías concretas",
                    "Se alude a transferencias de datos fuera del Espacio Económico Europeo sin indicar el mecanismo de garantía "
                            + "aplicable, o solo se indica dónde se alojan los datos (Art. 13.1.f RGPD).")),
            Map.entry("R10", new Textos("Información sobre cookies parcial o meramente mencionada",
                    "Se mencionan cookies sin explicar lo suficiente sus tipos, finalidades, duración y cómo gestionarlas o "
                            + "rechazarlas (Art. 22.2 LSSI-CE).")),
            Map.entry("R11", new Textos("Derecho a reclamar ante la autoridad de control informado de forma incompleta",
                    "Se menciona la autoridad de control sin informar del derecho a presentar una reclamación ante ella, o se "
                            + "informa de una reclamación sin identificar la autoridad (Art. 13.2.d RGPD).")),
            Map.entry("R12", new Textos("Consentimiento mencionado sin describir cómo se presta y se retira",
                    "Se menciona el consentimiento sin explicar cómo se presta y cómo puede retirarse (Art. 7 RGPD).")),
            Map.entry("R15", new Textos("Medidas de seguridad mencionadas de forma genérica",
                    "El documento declara aplicar medidas de seguridad sin concretar ninguna.")),
            Map.entry("R16", new Textos("Información del prestador (Art. 10 LSSI-CE) incompleta",
                    "El aviso legal contiene parte de la información general del Art. 10 LSSI-CE, pero faltan datos del prestador.")),
            Map.entry("R03", new Textos("Delegado de Protección de Datos mencionado sin datos de contacto",
                    "Se menciona un Delegado de Protección de Datos, pero no un medio para contactar con él (Art. 13.1.b RGPD).")),
            Map.entry("R05", new Textos("Base legal del tratamiento mencionada sin identificarla",
                    "Se alude a la legitimación del tratamiento sin indicar cuál de las bases del Art. 6.1 RGPD se aplica.")),
            Map.entry("R06", new Textos("Derechos del interesado informados de forma incompleta",
                    "Se informa de algunos derechos del interesado, pero no de los suficientes (Art. 13.2.b RGPD).")),
            Map.entry("R07", new Textos("Conservación de los datos mencionada sin plazo ni criterio",
                    "Se alude a la conservación de los datos sin un plazo concreto ni los criterios para determinarlo (Art. 13.2.a RGPD).")),
            Map.entry("R08", new Textos("Destinatarios de los datos mencionados sin identificarlos",
                    "Se indica que los datos pueden comunicarse a terceros sin identificar a los destinatarios ni sus categorías (Art. 13.1.e RGPD).")),
            Map.entry("R13", new Textos("Tratamiento de datos de menores descrito sin edad mínima ni consentimiento parental",
                    "Se describe el tratamiento de datos de menores sin indicar la edad mínima ni cómo se recaba el "
                            + "consentimiento de quien ejerce la patria potestad o tutela (Art. 8 RGPD y Art. 7 LOPDGDD).")),
            Map.entry("R14", new Textos("Categorías especiales de datos mencionadas sin la excepción que permite tratarlas",
                    "Se menciona el tratamiento de categorías especiales de datos sin indicar cuál de las excepciones del "
                            + "Art. 9.2 RGPD lo permite.")),
            Map.entry("R17", new Textos("Decisiones automatizadas o perfilado informados de forma incompleta",
                    "Se alude a decisiones automatizadas o a la elaboración de perfiles sin información suficiente sobre la "
                            + "lógica aplicada, sus consecuencias o las garantías del interesado (Arts. 13.2.f y 22 RGPD).")),
            Map.entry("R18", new Textos("Brechas de seguridad mencionadas sin indicar su notificación",
                    "Se alude a brechas o incidentes de seguridad sin indicar si se notifican a la autoridad de control o a "
                            + "las personas afectadas (Arts. 33 y 34 RGPD).")),
            Map.entry("R19", new Textos("Comunicaciones comerciales informadas de forma incompleta",
                    "Se informa del envío de comunicaciones comerciales sin indicar en qué se basa (consentimiento previo o "
                            + "relación contractual previa) o cómo darse de baja (Arts. 21 y 22 LSSI-CE).")));

    // ---------------------------------------------------------------------------------------
    // Clauses and matching
    // ---------------------------------------------------------------------------------------

    private static final Set<String> NEGADORES = Set.of("no", "nunca", "jamas", "tampoco", "ni", "ningun", "ninguna",
            "ninguno", "nadie");
    /** A negation does not reach across these. */
    private static final Set<String> CORTES = Set.of(",", "pero", "aunque", "sino", "y", "e", "salvo", "excepto");
    private static final int ALCANCE_NEGACION = 5;
    private static final Set<String> ABREVIATURAS = Set.of("art", "arts", "num", "pag", "sr", "sra", "sres", "sl", "sa",
            "slu", "cp", "tel", "apdo", "avda", "ej", "vid", "cfr", "aptdo", "nif", "cif");

    /** Sentences, paragraphs (blank line) and list items ("- "); a single line break is only a wrap. */
    static List<Clausula> segmentar(String texto) {
        List<Clausula> clausulas = new ArrayList<>();
        StringBuilder actual = new StringBuilder();
        int n = texto.length();
        for (int i = 0; i < n; i++) {
            char c = texto.charAt(i);
            if (c == LF || c == CR) {
                int j = i;
                int saltos = 0;
                while (j < n && Character.isWhitespace(texto.charAt(j))) {
                    if (texto.charAt(j) == LF) saltos++;
                    j++;
                }
                boolean elementoLista = j + 1 < n && texto.charAt(j) == '-' && Character.isWhitespace(texto.charAt(j + 1));
                if (saltos >= 2 || elementoLista) cortar(clausulas, actual);
                else actual.append(' ');
                i = j - 1;
            } else if ((c == '.' || c == ';' || c == '!' || c == '?')
                    && (i + 1 == n || Character.isWhitespace(texto.charAt(i + 1)))
                    && !(c == '.' && abreviatura(actual))) {
                actual.append(c);
                cortar(clausulas, actual);
            } else {
                actual.append(c);
            }
        }
        cortar(clausulas, actual);
        return clausulas;
    }

    private static boolean abreviatura(StringBuilder actual) {
        int inicio = actual.length();
        while (inicio > 0 && !Character.isWhitespace(actual.charAt(inicio - 1))) inicio--;
        String palabra = actual.substring(inicio).replaceAll("[^\\p{L}]", "").toLowerCase(Locale.ROOT);
        return palabra.length() == 1 || ABREVIATURAS.contains(palabra);
    }

    private static void cortar(List<Clausula> clausulas, StringBuilder actual) {
        String texto = String.join(" ", actual.toString().trim().split("\\s+")).trim();
        actual.setLength(0);
        if (texto.startsWith("- ")) texto = texto.substring(2);
        if (!texto.isBlank()) clausulas.add(new Clausula(texto, tokens(texto)));
    }

    /** Lower case, no diacritics, words of letters, digits or "@"; commas kept as tokens to bound negations. */
    static List<String> tokens(String texto) {
        String base = Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>();
        StringBuilder palabra = new StringBuilder();
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '@') {
                palabra.append(c);
                continue;
            }
            if (palabra.length() > 0) {
                tokens.add(palabra.toString());
                palabra.setLength(0);
            }
            if (c == ',') tokens.add(",");
        }
        if (palabra.length() > 0) tokens.add(palabra.toString());
        return List.copyOf(tokens);
    }

    private static List<List<String>> frases(String... frases) {
        return Arrays.stream(frases).map(ReglasContextuales::tokens).toList();
    }

    private static List<Integer> posiciones(Clausula c, List<String> frase) {
        List<Integer> posiciones = new ArrayList<>();
        List<String> t = c.tokens();
        for (int i = 0; i + frase.size() <= t.size(); i++) {
            if (t.subList(i, i + frase.size()).equals(frase)) posiciones.add(i);
        }
        return posiciones;
    }

    private static boolean negada(List<String> tokens, int posicion) {
        return negada(tokens, posicion, ALCANCE_NEGACION);
    }

    private static boolean negada(List<String> tokens, int posicion, int alcance) {
        for (int k = posicion - 1; k >= 0 && k >= posicion - alcance; k--) {
            if (CORTES.contains(tokens.get(k))) return false;
            if (NEGADORES.contains(tokens.get(k))) return true;
        }
        return false;
    }

    private static boolean alguna(Clausula c, List<List<String>> frases) {
        return frases.stream().anyMatch(f -> !posiciones(c, f).isEmpty());
    }

    private static boolean algunaAfirmada(Clausula c, List<List<String>> frases) {
        return frases.stream().anyMatch(f -> posiciones(c, f).stream().anyMatch(p -> !negada(c.tokens(), p)));
    }

    private static boolean algunaNegada(Clausula c, List<List<String>> frases) {
        return frases.stream().anyMatch(f -> posiciones(c, f).stream().anyMatch(p -> negada(c.tokens(), p)));
    }

    /** Labels whose phrases appear affirmed (not negated) in the clause. */
    private static Set<String> etiquetasAfirmadas(Clausula c, Map<String, List<List<String>>> catalogo) {
        Set<String> encontradas = new LinkedHashSet<>();
        catalogo.forEach((etiqueta, frases) -> {
            if (algunaAfirmada(c, frases)) encontradas.add(etiqueta);
        });
        return encontradas;
    }

    private static boolean encabezado(Clausula c, String palabra) {
        return c.tokens().size() <= 6 && c.tokens().contains(palabra);
    }

    private static Clausula unir(Clausula a, Clausula b) {
        List<String> tokens = new ArrayList<>(a.tokens());
        tokens.addAll(b.tokens());
        return new Clausula(a.original() + " " + b.original(), List.copyOf(tokens));
    }

    static String cita(Clausula c) {
        String texto = c.original();
        return "«" + (texto.length() <= MAX_EVIDENCIA ? texto : texto.substring(0, MAX_EVIDENCIA - 1) + "…") + "»";
    }

    private static boolean aplicaInformacionRgpd(String tipo) {
        return tipo == null || !TIPOS_SIN_INFORMACION_RGPD.contains(tipo.trim());
    }

    /**
     * Settles a rule from what was found, in a fixed order: problematic wording, sufficient
     * content, not applicable to the document type, a mere mention, and absence.
     */
    private static Evaluacion cerrar(String tipo, Clausula suficiente, String detalleSuficiente, Clausula problema,
                                     String detalleProblema, Clausula mencion, String detalleMencion, String detalleAusencia,
                                     boolean ausenciaNoVerificable) {
        return cerrarSegun(aplicaInformacionRgpd(tipo) ? null : "No se exige en un documento de tipo «" + tipo.trim()
                        + "»: esta información corresponde a la política de privacidad.", suficiente, detalleSuficiente,
                problema, detalleProblema, mencion, detalleMencion, detalleAusencia, ausenciaNoVerificable);
    }

    /** @param noAplicable why the document type does not have to carry this information, or null if it does */
    private static Evaluacion cerrarSegun(String noAplicable, Clausula suficiente, String detalleSuficiente, Clausula problema,
                                     String detalleProblema, Clausula mencion, String detalleMencion, String detalleAusencia,
                                     boolean ausenciaNoVerificable) {
        if (problema != null) {
            return new Evaluacion(EstadoEvidencia.POSIBLE_INCIDENCIA, suficiente != null, true, cita(problema), detalleProblema);
        }
        if (suficiente != null) {
            return new Evaluacion(EstadoEvidencia.SUFICIENTE, true, false, cita(suficiente), detalleSuficiente);
        }
        if (noAplicable != null) {
            return new Evaluacion(EstadoEvidencia.NO_APLICABLE, false, false, null, noAplicable);
        }
        if (mencion != null) {
            return new Evaluacion(EstadoEvidencia.INSUFICIENTE, false, false, cita(mencion), detalleMencion);
        }
        return new Evaluacion(ausenciaNoVerificable ? EstadoEvidencia.NO_VERIFICABLE : EstadoEvidencia.POSIBLE_INCIDENCIA,
                false, false, null, detalleAusencia);
    }

    static Evaluacion evaluar(String ruleId, List<Clausula> clausulas, String tipo) {
        return switch (ruleId) {
            case "R01" -> responsable(clausulas, tipo);
            case "R04" -> finalidades(clausulas, tipo);
            case "R09" -> transferencias(clausulas, tipo);
            case "R11" -> reclamacion(clausulas, tipo);
            case "R16" -> avisoLegal(clausulas, tipo);
            case "R02" -> contacto(clausulas, tipo);
            case "R03" -> delegado(clausulas, tipo);
            case "R05" -> baseJuridica(clausulas, tipo);
            case "R06" -> derechos(clausulas, tipo);
            case "R07" -> conservacion(clausulas, tipo);
            case "R08" -> destinatarios(clausulas, tipo);
            case "R10" -> cookies(clausulas, tipo);
            case "R12" -> consentimiento(clausulas, tipo);
            case "R15" -> seguridad(clausulas, tipo);
            case "R13" -> menores(clausulas, tipo);
            case "R14" -> categoriasEspeciales(clausulas, tipo);
            case "R17" -> decisionesAutomatizadas(clausulas, tipo);
            case "R18" -> brechas(clausulas, tipo);
            case "R19" -> comunicacionesComerciales(clausulas, tipo);
            default -> throw new IllegalArgumentException("Regla sin evaluación contextual: " + ruleId);
        };
    }

    // ---------------------------------------------------------------------------------------
    // R03 — Delegado de Protección de Datos
    // ---------------------------------------------------------------------------------------

    private static final List<List<String>> DPO = frases("delegado de proteccion de datos", "delegada de proteccion de datos",
            "dpo", "dpd", "data protection officer", "delegado de privacidad");
    private static final List<List<String>> DELEGADO = frases("delegado", "delegada");
    private static final List<List<String>> CONTEXTO_DELEGADO = frases("designar", "designacion", "designado", "designada",
            "nombrar", "nombrado", "proteccion de datos");
    private static final List<List<String>> NO_DESIGNADO = frases("no ha designado", "no hemos designado",
            "no se ha designado", "no ha nombrado", "no hemos nombrado", "no es obligatorio", "no esta obligado",
            "no esta obligada", "no estamos obligados", "no resulta obligatorio", "no procede");
    private static final List<List<String>> CONTACTO = frases("contacto", "contactar", "escribiendo", "escribir",
            "direccion", "correo", "email", "telefono", "formulario");

    private static boolean contactoDelegado(Clausula c) {
        return c.tokens().stream().anyMatch(t -> t.contains("@")) || alguna(c, CONTACTO);
    }

    private static Evaluacion delegado(List<Clausula> cs, String tipo) {
        Clausula conContacto = null;
        Clausula noDesignado = null;
        Clausula mencion = null;
        for (int i = 0; i < cs.size(); i++) {
            Clausula c = cs.get(i);
            if (!alguna(c, DPO) && !(alguna(c, DELEGADO) && alguna(c, CONTEXTO_DELEGADO))) continue;
            if (algunaNegada(c, DPO) || algunaNegada(c, DELEGADO) || alguna(c, NO_DESIGNADO)) {
                if (noDesignado == null) noDesignado = c;
            } else if (contactoDelegado(c)) {
                if (conContacto == null) conContacto = c;
            } else if (i + 1 < cs.size() && c.original().endsWith(":") && contactoDelegado(cs.get(i + 1))) {
                if (conContacto == null) conContacto = unir(c, cs.get(i + 1));
            } else if (mencion == null) {
                mencion = c;
            }
        }
        Clausula suficiente = conContacto != null ? conContacto : noDesignado;
        String detalle = conContacto != null ? "Se identifica al Delegado de Protección de Datos con un medio de contacto."
                : "El documento declara que no hay Delegado de Protección de Datos designado; el texto no permite comprobar "
                + "si su designación es obligatoria (Art. 37 RGPD y Art. 34 LOPDGDD).";
        return cerrar(tipo, suficiente, detalle, null, null, mencion,
                "Se menciona un Delegado de Protección de Datos sin indicar cómo contactar con él.",
                "El documento no menciona un Delegado de Protección de Datos. Su designación solo es obligatoria en los casos "
                        + "del Art. 37 RGPD y del Art. 34 LOPDGDD, que no pueden comprobarse con el texto.", true);
    }

    // ---------------------------------------------------------------------------------------
    // R05 — Base jurídica del tratamiento
    // ---------------------------------------------------------------------------------------

    private static final Map<String, List<List<String>>> BASES = new LinkedHashMap<>();
    private static final Map<String, String> LETRAS_ART_6_1 = Map.of("a", "consentimiento", "b", "ejecución de un contrato",
            "c", "obligación legal", "d", "interés vital", "e", "interés público", "f", "interés legítimo");

    static {
        BASES.put("consentimiento", frases("consentimiento"));
        BASES.put("ejecución de un contrato", frases("ejecucion del contrato", "ejecucion de un contrato",
                "ejecucion de la relacion contractual", "relacion contractual", "medidas precontractuales",
                "cumplimiento del contrato", "contrato suscrito"));
        BASES.put("obligación legal", frases("obligacion legal", "obligaciones legales", "imperativo legal", "exigencia legal"));
        BASES.put("interés legítimo", frases("interes legitimo", "intereses legitimos"));
        BASES.put("interés vital", frases("interes vital", "intereses vitales"));
        BASES.put("interés público", frases("interes publico", "poderes publicos"));
    }

    private static final List<List<String>> TEMA_BASE = frases("base legal", "base juridica", "bases legales",
            "bases juridicas", "legitimacion", "licitud del tratamiento", "legitima el tratamiento",
            "legitiman el tratamiento", "nos legitima", "amparo legal");
    private static final List<List<String>> TRATAMIENTO = frases("tratamiento", "tratamientos", "tratamos", "tratar",
            "tratemos", "trataremos", "tratados", "tratadas", "tratara", "trataran", "sus datos", "tus datos",
            "datos personales");
    private static final Set<String> ARTICULO = Set.of("art", "arts", "articulo", "articulos");

    /** Legal bases of Art. 6.1 RGPD named (not negated) in the clause, by name or as "art. 6.1.x". */
    private static Set<String> bases(Clausula c) {
        Set<String> bases = etiquetasAfirmadas(c, BASES);
        List<String> t = c.tokens();
        for (int i = 1; i + 2 < t.size(); i++) {
            boolean articulo = ARTICULO.contains(t.get(i - 1)) || (i >= 2 && ARTICULO.contains(t.get(i - 2)));
            if (articulo && t.get(i).equals("6") && t.get(i + 1).equals("1") && LETRAS_ART_6_1.containsKey(t.get(i + 2))) {
                bases.add(LETRAS_ART_6_1.get(t.get(i + 2)));
            }
        }
        return bases;
    }

    private static Evaluacion baseJuridica(List<Clausula> cs, String tipo) {
        Clausula suficiente = null;
        Clausula mencion = null;
        Set<String> identificadas = new LinkedHashSet<>();
        for (int i = 0; i < cs.size(); i++) {
            Clausula c = cs.get(i);
            Set<String> aqui = bases(c);
            boolean tema = alguna(c, TEMA_BASE);
            // A basis counts only when tied to the processing; "consentimiento" alone says nothing about it.
            if (!aqui.isEmpty() && (tema || alguna(c, TRATAMIENTO))) {
                identificadas.addAll(aqui);
                if (suficiente == null) suficiente = c;
            } else if (tema && aqui.isEmpty()) {
                Set<String> siguiente = i + 1 < cs.size() ? bases(cs.get(i + 1)) : Set.of();
                if (!siguiente.isEmpty() && c.tokens().size() <= 6) {
                    identificadas.addAll(siguiente);
                    if (suficiente == null) suficiente = unir(c, cs.get(i + 1));
                } else if (mencion == null) {
                    mencion = c;
                }
            }
        }
        return cerrar(tipo, suficiente, "Bases jurídicas identificadas: " + String.join(", ", identificadas) + ".",
                null, null, mencion,
                "Se menciona la base jurídica sin indicar cuál de las del Art. 6.1 RGPD legitima el tratamiento.",
                "No se indica en qué base jurídica (Art. 6.1 RGPD) se apoya el tratamiento.", false);
    }

    // ---------------------------------------------------------------------------------------
    // R06 — Derechos del interesado
    // ---------------------------------------------------------------------------------------

    private static final Map<String, List<List<String>>> DERECHOS = new LinkedHashMap<>();

    static {
        DERECHOS.put("acceso", frases("acceso", "acceder"));
        DERECHOS.put("rectificación", frases("rectificacion", "rectificar"));
        DERECHOS.put("supresión", frases("supresion", "suprimir", "olvido", "cancelacion", "borrado"));
        DERECHOS.put("limitación del tratamiento", frases("limitacion", "limitar"));
        DERECHOS.put("portabilidad", frases("portabilidad"));
        DERECHOS.put("oposición", frases("oposicion", "oponerse"));
    }

    private static final int DERECHOS_SUFICIENTES = 4;
    private static final List<List<String>> CONTEXTO_DERECHOS = frases("derecho", "derechos", "ejercer", "ejercitar",
            "ejercicio");
    private static final List<List<String>> DERECHOS_GENERICOS = frases("sus derechos", "derechos arco", "arco",
            "arsulipo", "derechos del interesado", "derechos que le asisten", "ejercicio de derechos");
    private static final List<List<String>> RECONOCIMIENTO = frases("puede ejercer", "podra ejercer", "puede ejercitar",
            "podra ejercitar", "pueden ejercer", "podran ejercer", "tiene derecho", "tendra derecho", "tienen derecho",
            "reconocemos", "atenderemos", "atendemos", "se atenderan", "se atendera");
    /** Conditions the rules themselves allow (Art. 12 RGPD): not a denial of the rights. */
    private static final List<List<String>> CONDICION_LEGITIMA = frases("identidad", "acreditar", "acredite",
            "acreditada", "acreditado", "dni", "documento identificativo", "manifiestamente infundadas", "excesivas");
    private static final List<List<String>> SOBRE_DATOS = frases("sus derechos", "datos", "proteccion de datos");

    private static Evaluacion derechos(List<Clausula> cs, String tipo) {
        Set<String> nombrados = new LinkedHashSet<>();
        Clausula mejor = null;
        int mejorCuenta = 0;
        Clausula generica = null;
        Clausula denegacion = null;
        int ventana = 0;
        for (Clausula c : cs) {
            boolean contexto = alguna(c, CONTEXTO_DERECHOS) || ventana > 0;
            if (ventana > 0) ventana--;
            // A short "Derechos" heading lets the following list items name the rights.
            if (encabezado(c, "derechos")) ventana = 6;
            Set<String> aqui = etiquetasAfirmadas(c, DERECHOS);
            // A denial needs no "derecho": "no atenderemos solicitudes de supresión".
            boolean deniega = algunaNegada(c, RECONOCIMIENTO) && (!aqui.isEmpty() || alguna(c, SOBRE_DATOS))
                    && !alguna(c, CONDICION_LEGITIMA);
            if (deniega) {
                if (denegacion == null) denegacion = c;
                continue;
            }
            if (!contexto) continue;
            nombrados.addAll(aqui);
            if (aqui.size() > mejorCuenta) {
                mejor = c;
                mejorCuenta = aqui.size();
            }
            if (aqui.isEmpty() && generica == null && alguna(c, DERECHOS_GENERICOS)) generica = c;
        }
        List<String> omitidos = DERECHOS.keySet().stream().filter(d -> !nombrados.contains(d)).toList();
        Clausula suficiente = nombrados.size() >= DERECHOS_SUFICIENTES ? mejor : null;
        String informados = "Derechos informados: " + (nombrados.isEmpty() ? "ninguno concreto" : String.join(", ", nombrados));
        String detalleSuficiente = informados + (omitidos.isEmpty() ? "."
                : ". No se mencionan: " + String.join(", ", omitidos) + " (pueden no aplicarse a todos los tratamientos).");
        return cerrar(tipo, suficiente, detalleSuficiente, denegacion,
                "El texto niega o restringe el ejercicio de los derechos del interesado. " + informados + ".",
                mejor != null ? mejor : generica, informados + ". No se mencionan: " + String.join(", ", omitidos) + ".",
                "No se informa de los derechos de acceso, rectificación, supresión, limitación, portabilidad y oposición.",
                false);
    }

    // ---------------------------------------------------------------------------------------
    // R07 — Plazo de conservación (y riesgo global G02)
    // ---------------------------------------------------------------------------------------

    private static final List<List<String>> TEMA_CONSERVACION = frases("plazo de conservacion", "plazos de conservacion",
            "periodo de conservacion", "periodos de conservacion", "tiempo de conservacion", "conservacion de los datos",
            "conservacion de datos", "conservacion de sus datos");
    private static final List<List<String>> VERBOS_CONSERVACION = frases("conservar", "conservaremos", "conservamos",
            "conservaran", "conservara", "conservados", "conservadas", "conservan", "mantendremos", "mantenemos",
            "mantendran", "almacenaremos", "almacenamos", "almacenados", "almacenadas", "guardaremos", "guardamos",
            "guardados", "suprimiremos", "suprimidos", "eliminaremos", "eliminados", "bloqueados", "bloquearemos");
    private static final List<List<String>> DATOS = frases("datos", "informacion personal");
    private static final List<List<String>> CRITERIOS = frases("mientras dure", "mientras se mantenga",
            "mientras mantenga", "mientras permanezca", "durante la vigencia", "hasta la finalizacion", "hasta que finalice",
            "tras finalizar", "una vez finalizada", "una vez finalizado", "plazos legales", "plazo legal",
            "plazos de prescripcion", "prescripcion", "legalmente establecido", "legalmente establecidos",
            "legalmente previsto", "legalmente previstos", "obligaciones legales", "hasta que retire", "hasta que revoque",
            "hasta la retirada", "hasta que se oponga");
    private static final List<List<String>> INDEFINIDA = frases("indefinidamente", "de forma indefinida",
            "por tiempo indefinido", "tiempo indefinido", "sin limite de tiempo", "sin limite temporal",
            "sin plazo determinado", "permanentemente", "de forma permanente", "para siempre", "hasta que lo solicite",
            "hasta que usted lo solicite");
    private static final List<List<String>> G02 = frases("indefinidamente", "sin limite de tiempo", "de forma indefinida",
            "almacenamos sus datos permanentemente");
    private static final Set<String> NUMEROS = Set.of("un", "una", "uno", "dos", "tres", "cuatro", "cinco", "seis",
            "siete", "ocho", "nueve", "diez", "once", "doce", "quince", "dieciocho", "veinte", "treinta", "cuarenta",
            "sesenta", "noventa", "cien");
    private static final Set<String> UNIDADES = Set.of("dia", "dias", "semana", "semanas", "mes", "meses", "ano", "anos");

    private static boolean periodo(Clausula c) {
        List<String> t = c.tokens();
        for (int i = 0; i + 1 < t.size(); i++) {
            boolean numero = t.get(i).chars().allMatch(Character::isDigit) || NUMEROS.contains(t.get(i));
            if (numero && UNIDADES.contains(t.get(i + 1))) return true;
        }
        return false;
    }

    private static List<Clausula> clausulasConservacion(List<Clausula> cs) {
        List<Clausula> resultado = new ArrayList<>();
        boolean trasEncabezado = false;
        for (Clausula c : cs) {
            if (alguna(c, TEMA_CONSERVACION) || (alguna(c, VERBOS_CONSERVACION) && alguna(c, DATOS)) || trasEncabezado) {
                resultado.add(c);
            }
            trasEncabezado = encabezado(c, "conservacion");
        }
        return resultado;
    }

    private static Evaluacion conservacion(List<Clausula> cs, String tipo) {
        Clausula suficiente = null;
        Clausula problema = null;
        Clausula mencion = null;
        for (Clausula c : clausulasConservacion(cs)) {
            if (problema == null && algunaAfirmada(c, INDEFINIDA)) problema = c;
            // A maximum stays a period even when negated: "no los conservaremos más de cinco años".
            if (suficiente == null && (periodo(c) || alguna(c, CRITERIOS))) suficiente = c;
            if (mencion == null) mencion = c;
        }
        return cerrar(tipo, suficiente, "Se indica un plazo o un criterio de conservación.", problema,
                "Se declara una conservación sin límite temporal, que puede ser contraria al Art. 5.1.e RGPD.", mencion,
                "Se habla de conservar los datos, pero con fórmulas que no fijan un plazo ni un criterio verificable.",
                "No se informa del plazo de conservación ni de los criterios para determinarlo.", false);
    }

    /** G02 only when the indefinite retention is affirmed in a clause about personal data. */
    static Clausula g02(List<Clausula> cs) {
        for (Clausula c : cs) {
            if ((alguna(c, DATOS) || alguna(c, TEMA_CONSERVACION)) && algunaAfirmada(c, G02)) return c;
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------
    // R08 — Destinatarios (y riesgo global G01)
    // ---------------------------------------------------------------------------------------

    private static final List<List<String>> CESION = frases("ceder", "cedemos", "cederemos", "cedera", "cederan",
            "cedidos", "cedidas", "cesion", "cesiones", "comunicar", "comunicamos", "comunicaremos", "comunicara",
            "comunicaran", "comunicados", "comunicadas", "compartir", "compartimos", "compartiremos", "compartidos",
            "compartidas", "facilitar", "facilitamos", "facilitaremos", "revelar", "revelamos", "divulgar", "divulgamos",
            "vender", "vendemos", "venderemos", "vendidos", "vendera");
    private static final List<List<String>> VENTA = frases("vender", "vendemos", "venderemos", "vendidos", "vendera",
            "venta de sus datos", "venta de datos");
    private static final List<List<String>> SIN_GARANTIAS = frases("sin su consentimiento", "sin restriccion",
            "sin restricciones", "sin limitacion", "libremente", "a cualquier tercero");
    private static final List<List<String>> DESTINATARIOS_GENERICOS = frases("destinatarios", "destinatario", "terceros",
            "tercero", "terceras partes", "terceras empresas", "encargados", "encargado");
    private static final List<List<String>> DATOS_CESION = frases("datos", "informacion personal", "destinatarios",
            "destinatario", "encargados del tratamiento", "encargado del tratamiento");
    private static final List<List<String>> CATEGORIAS = frases("encargados del tratamiento", "encargado del tratamiento",
            "encargados de tratamiento", "proveedores", "proveedor", "prestadores de servicios", "prestador de servicios",
            "entidades del grupo", "empresas del grupo", "sociedades del grupo", "administraciones publicas",
            "administracion tributaria", "agencia tributaria", "autoridades", "organismos publicos",
            "fuerzas y cuerpos de seguridad", "juzgados", "tribunales", "entidades bancarias", "entidades financieras",
            "bancos", "pasarela de pago", "pasarelas de pago", "plataforma de pago", "plataformas de pago",
            "empresas de transporte", "empresas de mensajeria", "transportistas", "asesoria", "asesores", "gestoria",
            "aseguradoras", "alojamiento", "hosting", "servicios en la nube", "notarios", "registros publicos");
    private static final List<List<String>> G01 = frases("cederemos sus datos", "venderemos sus datos",
            "vendemos sus datos", "transferimos sus datos a socios publicitarios sin restriccion");
    /** A disclosure qualified like this is not an abusive transfer. */
    private static final List<List<String>> CESION_CALIFICADA = frases("obligacion legal", "cuando lo exija la ley",
            "exigencia legal", "requerimiento", "legalmente", "autoridades", "administraciones publicas",
            "encargados del tratamiento", "con su consentimiento", "previo consentimiento");

    private static Evaluacion destinatarios(List<Clausula> cs, String tipo) {
        Clausula identificados = null;
        Clausula noCede = null;
        Clausula problema = null;
        Clausula mencion = null;
        Set<String> categorias = new LinkedHashSet<>();
        for (Clausula c : cs) {
            if (!alguna(c, DATOS_CESION)) continue;
            boolean cedeAfirmado = algunaAfirmada(c, CESION);
            if (problema == null && (algunaAfirmada(c, VENTA) || (cedeAfirmado && alguna(c, SIN_GARANTIAS)))) problema = c;
            if (algunaNegada(c, CESION) && !cedeAfirmado) {
                if (noCede == null) noCede = c;
                continue;
            }
            if (!cedeAfirmado && !alguna(c, DESTINATARIOS_GENERICOS)) continue;
            List<String> aqui = CATEGORIAS.stream().filter(f -> !posiciones(c, f).isEmpty())
                    .map(f -> String.join(" ", f)).toList();
            if (!aqui.isEmpty()) {
                categorias.addAll(aqui);
                if (identificados == null) identificados = c;
            } else if (mencion == null) {
                mencion = c;
            }
        }
        Clausula suficiente = identificados != null ? identificados : noCede;
        String detalle = identificados != null ? "Destinatarios o categorías identificados: " + String.join(", ", categorias) + "."
                : "El documento declara que no comunica los datos a terceros.";
        return cerrar(tipo, suficiente, detalle, problema,
                "Se declara la venta de los datos o su comunicación sin garantías.", mencion,
                "Se alude a terceros o destinatarios sin identificarlos ni indicar sus categorías.",
                "No se informa de si los datos se comunican a terceros ni a quién.", false);
    }

    /** G01 only for an affirmed sale or unqualified transfer; "no cederemos sus datos" is not one. */
    static Clausula g01(List<Clausula> cs) {
        for (Clausula c : cs) {
            if (algunaAfirmada(c, G01) && !(alguna(c, frases("cederemos sus datos")) && alguna(c, CESION_CALIFICADA)
                    && !algunaAfirmada(c, VENTA))) {
                return c;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------
    // Shared by R10, R12 and G03: quoted or illustrative wording is not the document's own practice
    // ---------------------------------------------------------------------------------------

    private static final Pattern ENTRECOMILLADO = Pattern.compile("[«“\"]([^»”\"]+)[»”\"]");
    private static final List<List<String>> ILUSTRACION = frases("por ejemplo", "practicas como", "expresiones como",
            "frases como", "formulas como", "clausulas como", "no es valido", "no es valida", "no son validas",
            "no son validos", "no se considera", "no se consideran", "no constituye", "no equivale", "no admitimos");

    /** The phrase is quoted, or the clause presents it as an example of what is not acceptable. */
    private static boolean ilustrativa(Clausula c, List<List<String>> frases) {
        if (alguna(c, ILUSTRACION)) return true;
        Matcher m = ENTRECOMILLADO.matcher(c.original());
        while (m.find()) {
            Clausula cita = new Clausula(m.group(1), tokens(m.group(1)));
            if (alguna(cita, frases)) return true;
        }
        return false;
    }

    private static boolean practicaAfirmada(Clausula c, List<List<String>> frases) {
        return algunaAfirmada(c, frases) && !ilustrativa(c, frases);
    }

    // ---------------------------------------------------------------------------------------
    // R02 — Medios de contacto del responsable
    // ---------------------------------------------------------------------------------------

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+");
    private static final Pattern TELEFONO = Pattern.compile("\\+?\\d[\\d ().-]{7,}\\d");
    private static final List<List<String>> CONTEXTO_CONTACTO = frases("contacto", "contactar", "contacte", "contactenos",
            "contactarnos", "escribanos", "escribenos", "escribir", "escribiendo", "escriba", "dirigirse", "dirijase",
            "dirigiendose", "llamar", "llamenos", "llamando", "atencion al cliente", "servicio al cliente", "consultas",
            "consulta", "solicitudes", "correo electronico", "email", "e mail", "telefono", "responsable", "titular",
            "domicilio", "direccion postal", "nuestro equipo");
    /** An invitation to get in touch: a mention of contact, unlike the controller's identity words above. */
    private static final List<List<String>> INVITACION_CONTACTO = frases("contacto", "contactar", "contacte",
            "contactenos", "contactarnos", "escribanos", "escribenos", "dirigirse", "dirijase", "llamenos",
            "atencion al cliente", "servicio al cliente");
    private static final List<List<String>> EJEMPLO = frases("por ejemplo", "p ej", "ejemplo de", "con el formato");
    private static final List<List<String>> DIRECCION = frases("calle", "avenida", "avda", "plaza", "paseo", "domicilio",
            "direccion postal", "apartado de correos");
    private static final List<List<String>> FORMULARIO = frases("formulario de contacto", "formulario web",
            "formulario disponible");

    /** Concrete channels in the clause: an e-mail address, a phone number, a postal address or a contact form. */
    private static List<String> canales(Clausula c) {
        List<String> canales = new ArrayList<>();
        if (EMAIL.matcher(c.original()).find() && !alguna(c, EJEMPLO)) canales.add("correo electrónico");
        Matcher telefono = TELEFONO.matcher(c.original());
        while (telefono.find()) {
            long digitos = telefono.group().chars().filter(Character::isDigit).count();
            if (digitos >= 9 && digitos <= 15) {
                canales.add("teléfono");
                break;
            }
        }
        if (alguna(c, DIRECCION) && c.tokens().stream().anyMatch(t -> t.length() == 5 && t.chars().allMatch(Character::isDigit))) {
            canales.add("dirección postal");
        }
        if (alguna(c, FORMULARIO)) canales.add("formulario de contacto");
        return canales;
    }

    private static Evaluacion contacto(List<Clausula> cs, String tipo) {
        Clausula suficiente = null;
        Clausula mencion = null;
        Set<String> medios = new LinkedHashSet<>();
        for (Clausula c : cs) {
            // The DPO's address is R03; it is not the controller's contact.
            if (alguna(c, DPO) && !alguna(c, frases("responsable", "titular"))) continue;
            List<String> aqui = canales(c);
            boolean contexto = alguna(c, CONTEXTO_CONTACTO);
            if (!aqui.isEmpty() && contexto) {
                medios.addAll(aqui);
                if (suficiente == null) suficiente = c;
            } else if ((algunaAfirmada(c, INVITACION_CONTACTO) || !aqui.isEmpty()) && mencion == null) {
                mencion = c;
            }
        }
        return cerrar(tipo, suficiente, "Medios de contacto indicados: " + String.join(", ", medios) + ".", null, null,
                mencion, "Se alude al contacto, o aparece un dato de contacto, sin un medio concreto presentado como tal.",
                "No se indica ningún medio para contactar con el responsable.", false);
    }

    // ---------------------------------------------------------------------------------------
    // R10 — Información sobre cookies (documented information only, never the banner's behaviour)
    // ---------------------------------------------------------------------------------------

    private static final List<List<String>> COOKIES = frases("cookie", "cookies", "dispositivos de almacenamiento",
            "tecnologias similares");
    private static final Map<String, List<List<String>>> ASPECTOS_COOKIES = new LinkedHashMap<>();

    static {
        ASPECTOS_COOKIES.put("tipos", frases("tecnicas", "necesarias", "analiticas", "de analisis", "estadisticas",
                "publicitarias", "de publicidad", "comportamental", "de preferencias", "de personalizacion", "funcionales",
                "de rendimiento", "propias", "de terceros", "marketing"));
        ASPECTOS_COOKIES.put("finalidades", frases("finalidad", "finalidades", "para medir", "para analizar", "para recordar",
                "para mostrar", "para personalizar", "sirven para", "se utilizan para", "utilizamos para", "con el fin de"));
        ASPECTOS_COOKIES.put("duración", frases("duracion", "caducidad", "caducan", "expiran", "expiracion", "de sesion",
                "persistentes", "al cerrar el navegador", "permanecen"));
        ASPECTOS_COOKIES.put("gestión", frases("configurar", "configurarlas", "configuracion", "rechazar", "rechazarlas",
                "aceptar", "aceptarlas", "panel", "centro de preferencias", "preferencias", "desactivar", "desactivarlas",
                "bloquear", "bloquearlas", "eliminarlas", "borrarlas", "navegador", "gestionar", "gestionarlas",
                "retirar el consentimiento"));
    }

    private static final int ASPECTOS_COOKIES_SUFICIENTES = 3;
    private static final List<List<String>> COOKIES_PROBLEMATICAS = frases("instalamos cookies sin su consentimiento",
            "instalan cookies sin su consentimiento", "antes de obtener su consentimiento", "no puede rechazar",
            "no es posible rechazar", "no se pueden rechazar", "no podra rechazar", "solo puede aceptar",
            "unicamente puede aceptar", "continuar navegando implica aceptar", "al continuar navegando",
            "si continua navegando");
    /** Technical cookies are exempt from consent: not being able to reject them is lawful. */
    private static final List<List<String>> EXENTAS = frases("tecnicas", "necesarias", "estrictamente necesarias", "exentas");
    private static final List<List<String>> REMISION_COOKIES = frases("consulte", "consultar", "puede consultar", "ver",
            "mas informacion", "informacion detallada", "remitimos", "disponible en");
    private static final String TIPO_COOKIES = "Política de Cookies";

    private static Evaluacion cookies(List<Clausula> cs, String tipo) {
        Map<String, Clausula> aspectos = new LinkedHashMap<>();
        // In a cookie policy every clause is about cookies, even when it does not repeat the word.
        boolean politicaCookies = tipo != null && tipo.trim().equals(TIPO_COOKIES);
        Clausula primera = null;
        Clausula problema = null;
        Clausula remision = null;
        int ventana = 0;
        for (Clausula c : cs) {
            boolean menciona = alguna(c, COOKIES) || ventana > 0;
            if (ventana > 0) ventana--;
            if (encabezado(c, "cookies")) ventana = 3;
            if (!menciona && !politicaCookies) continue;
            if (menciona && primera == null) primera = c;
            if (problema == null && practicaAfirmada(c, COOKIES_PROBLEMATICAS)
                    && !(alguna(c, frases("rechazar", "rechazarlas")) && alguna(c, EXENTAS))) {
                problema = c;
            }
            if (remision == null && alguna(c, frases("politica de cookies")) && alguna(c, REMISION_COOKIES)) remision = c;
            int periodo = periodo(c) ? 1 : 0;
            ASPECTOS_COOKIES.forEach((aspecto, frases) -> {
                if (!aspectos.containsKey(aspecto) && (alguna(c, frases) || (aspecto.equals("duración") && periodo == 1))) {
                    aspectos.put(aspecto, c);
                }
            });
        }
        List<String> faltan = ASPECTOS_COOKIES.keySet().stream().filter(a -> !aspectos.containsKey(a)).toList();
        boolean suficiente = aspectos.size() >= ASPECTOS_COOKIES_SUFICIENTES;
        String informado = "Se informa de: " + (aspectos.isEmpty() ? "nada concreto" : String.join(", ", aspectos.keySet()))
                + (faltan.isEmpty() ? "." : "; falta: " + String.join(", ", faltan) + ".");
        String sinVerificar = " El funcionamiento real del aviso o panel de cookies no se ha verificado.";
        if (problema != null) {
            return new Evaluacion(EstadoEvidencia.POSIBLE_INCIDENCIA, suficiente, true, cita(problema),
                    "El texto describe una práctica de cookies que puede ser contraria a la normativa. " + informado);
        }
        if (suficiente) {
            return new Evaluacion(EstadoEvidencia.SUFICIENTE, true, false, cita(aspectos.values().iterator().next()),
                    informado + sinVerificar);
        }
        if (!politicaCookies && !aplicaInformacionRgpd(tipo)) {
            return new Evaluacion(EstadoEvidencia.NO_APLICABLE, false, false, null, "No se exige en un documento de tipo «"
                    + tipo.trim() + "»: esta información corresponde a la política de cookies.");
        }
        if (!politicaCookies && remision != null) {
            return new Evaluacion(EstadoEvidencia.NO_VERIFICABLE, false, false, cita(remision),
                    "El documento remite a la política de cookies, que no forma parte de este análisis.");
        }
        if (primera != null) {
            return new Evaluacion(EstadoEvidencia.INSUFICIENTE, false, false, cita(primera),
                    (aspectos.isEmpty() ? "Solo se mencionan las cookies. " : "Información parcial. ") + informado);
        }
        return politicaCookies
                ? new Evaluacion(EstadoEvidencia.POSIBLE_INCIDENCIA, false, false, null,
                        "La política de cookies no contiene información sobre las cookies utilizadas.")
                : new Evaluacion(EstadoEvidencia.NO_VERIFICABLE, false, false, null,
                        "El documento no menciona cookies; el texto no permite saber si el sitio las utiliza.");
    }

    // ---------------------------------------------------------------------------------------
    // R12 — Consentimiento (documented mechanism only, never the form's behaviour) and G03
    // ---------------------------------------------------------------------------------------

    private static final List<List<String>> CONSENTIMIENTO = frases("consentimiento", "consentimientos", "consiente",
            "consienta", "consentir");
    private static final List<List<String>> COMO_SE_PRESTA = frases("casilla", "casillas", "marcando", "marcar",
            "checkbox", "accion afirmativa", "acto afirmativo", "boton", "pulsando", "pulsar", "haciendo clic", "hacer clic",
            "de forma expresa", "expresamente", "firmando", "firma");
    private static final List<List<String>> RETIRADA = frases("retirar", "retirarlo", "retirarse", "retirada", "revocar",
            "revocarlo", "revocarse", "revocacion", "retire", "revoque");
    private static final List<List<String>> CONSENTIMIENTO_TACITO = frases("se entendera prestado", "se entiende prestado",
            "se considerara prestado", "se considera prestado", "se entendera que acepta", "se entiende que acepta",
            "se considerara que acepta", "el silencio implica", "casilla marcada por defecto", "casillas marcadas por defecto",
            "casilla premarcada", "casillas premarcadas", "continuar navegando implica", "al continuar navegando",
            "si continua navegando", "al usar este sitio acepta", "el uso de este sitio implica", "el uso del sitio implica");
    private static final List<List<String>> G03 = frases("continuar navegando implica", "al usar este sitio acepta",
            "se entiende prestado el consentimiento", "el silencio implica aceptacion");
    private static final String TIPOS_CONSENTIMIENTO = "esta información corresponde a la política de privacidad o de cookies.";

    private static Evaluacion consentimiento(List<Clausula> cs, String tipo) {
        Clausula comoSePresta = null;
        Clausula retirada = null;
        Clausula problema = null;
        Clausula mencion = null;
        for (Clausula c : cs) {
            boolean deConsentimiento = alguna(c, CONSENTIMIENTO);
            if (problema == null && practicaAfirmada(c, CONSENTIMIENTO_TACITO)) problema = c;
            if (!deConsentimiento) continue;
            // "No podrá retirar su consentimiento" denies Art. 7.3 RGPD.
            if (problema == null && algunaNegada(c, RETIRADA) && !algunaAfirmada(c, RETIRADA)) problema = c;
            if (comoSePresta == null && alguna(c, COMO_SE_PRESTA)) comoSePresta = c;
            if (retirada == null && algunaAfirmada(c, RETIRADA)) retirada = c;
            if (mencion == null) mencion = c;
        }
        Clausula suficiente = comoSePresta != null && retirada != null ? comoSePresta : null;
        String noAplicable = TIPO_COOKIES.equals(tipo == null ? null : tipo.trim()) || aplicaInformacionRgpd(tipo) ? null
                : "No se exige en un documento de tipo «" + tipo.trim() + "»: " + TIPOS_CONSENTIMIENTO;
        String falta = comoSePresta == null && retirada == null ? "No se explica cómo se presta ni cómo se retira."
                : comoSePresta == null ? "Se explica cómo retirarlo, pero no cómo se presta."
                : "Se explica cómo se presta, pero no cómo retirarlo.";
        return cerrarSegun(noAplicable, suficiente, "Se describe cómo se presta y cómo se retira el consentimiento. "
                        + "El comportamiento real del formulario o del aviso no se ha verificado.",
                problema, "El texto describe un consentimiento tácito, por defecto o no revocable, que puede ser contrario al Art. 7 RGPD.",
                mencion, falta, "El documento no menciona el consentimiento; el texto no permite saber si algún tratamiento "
                        + "se basa en él.", true);
    }

    /** G03 only for implied consent the document affirms as its own practice. */
    static Clausula g03(List<Clausula> cs) {
        for (Clausula c : cs) {
            if (practicaAfirmada(c, G03)) return c;
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------
    // R15 — Medidas de seguridad (stated measures only, never their implementation)
    // ---------------------------------------------------------------------------------------

    private static final List<List<String>> TEMA_SEGURIDAD = frases("medidas de seguridad", "medidas tecnicas",
            "medidas organizativas", "seguridad de los datos", "seguridad de sus datos", "seguridad de la informacion",
            "proteger sus datos", "proteger los datos", "proteger la informacion", "nivel de seguridad");
    private static final List<List<String>> MEDIDAS = frases("cifrado", "cifrados", "cifrada", "cifradas", "encriptacion",
            "encriptados", "conexion segura", "conexiones seguras", "protocolo seguro", "protocolo https", "conexion https",
            "certificado ssl", "certificados ssl", "cifrado ssl", "cifrado tls", "ssl tls", "control de acceso",
            "controles de acceso", "acceso restringido", "copias de seguridad", "copia de seguridad", "seudonimizacion",
            "pseudonimizacion", "anonimizacion", "autenticacion", "doble factor", "cortafuegos", "firewall",
            "auditorias de seguridad", "personal autorizado", "deber de confidencialidad", "compromiso de confidencialidad",
            "iso 27001", "esquema nacional de seguridad", "registro de accesos");
    private static final List<List<String>> AMBITO_SEGURIDAD = frases("datos", "informacion", "sistemas", "servidores",
            "plataforma");
    private static final List<List<String>> SEGURIDAD_PROBLEMATICA = frases("no podemos garantizar la seguridad",
            "no somos responsables de brechas de seguridad", "transmision de datos no es segura", "transmision no garantizada");

    private static Evaluacion seguridad(List<Clausula> cs, String tipo) {
        Clausula suficiente = null;
        Clausula problema = null;
        Clausula mencion = null;
        Set<String> declaradas = new LinkedHashSet<>();
        for (Clausula c : cs) {
            if (problema == null && alguna(c, SEGURIDAD_PROBLEMATICA)) problema = c;
            boolean tema = alguna(c, TEMA_SEGURIDAD);
            // Incidental vocabulary (an https link, a "copia" of a contract) is no security statement.
            List<String> aqui = (tema || alguna(c, AMBITO_SEGURIDAD)) ? MEDIDAS.stream()
                    .filter(f -> posiciones(c, f).stream().anyMatch(pos -> !negada(c.tokens(), pos)))
                    .map(f -> String.join(" ", f)).toList() : List.of();
            if (!aqui.isEmpty()) {
                declaradas.addAll(aqui);
                if (suficiente == null) suficiente = c;
            } else if (tema && mencion == null) {
                mencion = c;
            }
        }
        return cerrar(tipo, suficiente, "Medidas declaradas: " + String.join(", ", declaradas)
                        + ". El texto no acredita que estén implantadas.", problema,
                "El documento declina la responsabilidad sobre la seguridad de los datos.", mencion,
                "Se declara aplicar medidas de seguridad sin concretar ninguna.",
                "El documento no describe medidas de seguridad. Describirlas no es una obligación de información; su "
                        + "aplicación (Art. 32 RGPD) no puede comprobarse con el texto.", true);
    }

    // ---------------------------------------------------------------------------------------
    // Shared by R01 and R16: who is named (legal form, tax identifier or a proper name after a label)
    // ---------------------------------------------------------------------------------------

    /** Spanish NIF/CIF/NIE layout only; the control character is not validated. */
    private static final Pattern NIF = Pattern.compile("(?<![A-Za-z0-9])(?:[A-HJ-NP-SUVW][- ]?\\d{7}[- ]?[0-9A-J]"
            + "|\\d{8}[- ]?[A-HJ-NP-TV-Z]|[XYZ][- ]?\\d{7}[- ]?[A-HJ-NP-TV-Z])(?![A-Za-z0-9])", Pattern.CASE_INSENSITIVE);
    private static final List<List<String>> FORMAS_JURIDICAS = frases("s l", "sl", "s l u", "slu", "s a", "s a u", "sau",
            "s l l", "sll", "s l p", "slp", "s coop", "sociedad limitada", "sociedad anonima", "sociedad cooperativa",
            "sociedad civil", "comunidad de bienes", "c b");
    /** Companies that must be entered in the Registro Mercantil, so Art. 10.1.b LSSI-CE applies to them. */
    private static final List<List<String>> FORMAS_MERCANTILES = frases("s l", "sl", "s l u", "slu", "s a", "s a u", "sau",
            "s l l", "sll", "s l p", "slp", "sociedad limitada", "sociedad anonima");
    /** Capitalised words that follow a label without naming anyone. */
    private static final Set<String> NO_NOMBRES = Set.of("El", "La", "Los", "Las", "Lo", "Un", "Una", "Nuestra", "Nuestro",
            "Nuestras", "Nuestros", "Esta", "Este", "Estos", "Estas", "Dicha", "Dicho", "Su", "Sus", "Usted", "Nosotros", "Es",
            "Sera", "Se", "No", "Ver", "Consulte", "Datos", "Contacto", "Domicilio", "Direccion", "Correo", "Email", "Telefono",
            "NIF", "CIF", "Responsable", "Titular", "Indicado", "Indicada");

    /** A capitalised name right after one of the labels: «Responsable del tratamiento: Ejemplo», «Titular: Ana Pérez». */
    private static Pattern nombreTras(String etiquetas) {
        return Pattern.compile("(?<!\\p{L})(?i:" + etiquetas + ")(?!\\p{L})\\s*[:,-]?\\s*(?:(?i:es|sera|corresponde a)\\s+)?"
                + "(\\p{Lu}[\\p{L}\\p{N}&'.-]*)");
    }

    private static boolean nombra(Clausula c, Pattern etiqueta) {
        Matcher m = etiqueta.matcher(Normalizer.normalize(c.original(), Normalizer.Form.NFD).replaceAll("\\p{M}+", ""));
        while (m.find()) {
            if (!NO_NOMBRES.contains(m.group(1).replaceAll("[.,'-]+$", ""))) return true;
        }
        return false;
    }

    private static boolean nif(Clausula c) {
        return NIF.matcher(c.original()).find();
    }

    private static boolean identifica(Clausula c, Pattern etiqueta) {
        return alguna(c, FORMAS_JURIDICAS) || nif(c) || nombra(c, etiqueta);
    }

    // ---------------------------------------------------------------------------------------
    // R01 — Identidad del responsable del tratamiento
    // ---------------------------------------------------------------------------------------

    private static final List<List<String>> ROL_RESPONSABLE = frases("responsable del tratamiento",
            "responsable de tratamiento", "responsables del tratamiento", "corresponsable del tratamiento",
            "corresponsables del tratamiento", "identidad del responsable", "responsable de sus datos",
            "responsable de los datos", "responsable de tus datos");
    /** «Ejemplo S.L. trata sus datos»: the named entity is presented as the one processing them. */
    private static final List<List<String>> TRATA_DATOS = frases("trata sus datos", "tratara sus datos", "tratamos sus datos",
            "trataremos sus datos", "trata los datos", "tratara los datos", "trata tus datos", "tratamos tus datos");
    private static final Pattern NOMBRE_RESPONSABLE = nombreTras("responsables? de(?:l)? tratamiento"
            + "|corresponsables? del tratamiento|identidad del responsable|responsable de (?:sus|los|tus) datos|responsable");

    /** The controller's role is stated: its wording affirmed, or a «Responsable:» label opening the clause. */
    private static boolean rolResponsable(Clausula c) {
        List<String> t = c.tokens();
        int dosPuntos = c.original().indexOf(':');
        boolean etiqueta = !t.isEmpty() && t.get(0).equals("responsable") && (t.size() <= 3 || (dosPuntos >= 0 && dosPuntos < 30));
        return etiqueta || algunaAfirmada(c, ROL_RESPONSABLE) || algunaAfirmada(c, TRATA_DATOS);
    }

    private static Evaluacion responsable(List<Clausula> cs, String tipo) {
        Clausula suficiente = null;
        Clausula sinIdentidad = null;
        Clausula sinRol = null;
        for (int i = 0; i < cs.size(); i++) {
            Clausula c = cs.get(i);
            boolean rol = rolResponsable(c);
            if (rol && identifica(c, NOMBRE_RESPONSABLE)) {
                if (suficiente == null) suficiente = c;
            } else if (rol) {
                // A heading («Responsable del tratamiento») followed by the entity's own clause.
                Clausula siguiente = i + 1 < cs.size() ? cs.get(i + 1) : null;
                boolean cabecera = c.tokens().size() <= 6 || c.original().endsWith(":");
                if (cabecera && siguiente != null && (alguna(siguiente, FORMAS_JURIDICAS) || nif(siguiente))) {
                    if (suficiente == null) suficiente = unir(c, siguiente);
                } else if (sinIdentidad == null) {
                    sinIdentidad = c;
                }
            } else if (sinRol == null && (alguna(c, FORMAS_JURIDICAS) || nif(c))) {
                sinRol = c;
            }
        }
        Clausula mencion = sinIdentidad != null ? sinIdentidad : sinRol;
        return cerrar(tipo, suficiente, "Se identifica al responsable del tratamiento por su nombre, razón social o NIF. "
                        + "El texto no permite comprobar que esos datos sean correctos.", null, null, mencion,
                sinIdentidad != null ? "Se alude al responsable del tratamiento sin indicar su nombre o razón social."
                        : "Se nombra una entidad, pero no se indica que sea la responsable del tratamiento.",
                "No se identifica al responsable del tratamiento.", false);
    }

    // ---------------------------------------------------------------------------------------
    // R04 — Finalidades del tratamiento
    // ---------------------------------------------------------------------------------------

    private static final List<List<String>> MARCA_FINALIDAD = frases("finalidad", "finalidades", "fines", "proposito",
            "propositos", "para que tratamos", "para que usamos", "para que utilizamos", "objetivo del tratamiento");
    private static final List<List<String>> USO_DATOS = frases("tratamos", "trataremos", "utilizamos", "utilizaremos",
            "usamos", "usaremos", "recogemos", "recabamos", "se utilizaran", "seran tratados", "seran utilizados",
            "se trataran", "sus datos", "tus datos", "datos personales");
    private static final List<List<String>> PARA = frases("para", "con el fin de", "con el objeto de", "con objeto de");
    private static final Map<String, List<List<String>>> FINES = new LinkedHashMap<>();

    static {
        FINES.put("prestación del servicio", frases("prestacion del servicio", "prestacion de los servicios",
                "prestar el servicio", "prestar los servicios", "prestarle el servicio", "prestarle los servicios",
                "servicio contratado", "servicios contratados"));
        FINES.put("gestión de clientes o del contrato", frases("gestion de clientes", "gestionar la relacion",
                "gestion de la relacion", "relacion contractual", "gestionar el contrato", "ejecutar el contrato",
                "ejecucion del contrato"));
        FINES.put("pedidos, pagos y facturación", frases("pedido", "pedidos", "compra", "compras", "facturacion", "facturar",
                "factura", "facturas", "pagos", "cobro", "envio de los productos", "entrega de los productos", "devoluciones"));
        FINES.put("atención de consultas y solicitudes", frases("consulta", "consultas", "solicitud", "solicitudes",
                "peticiones", "atencion al cliente", "atender", "responder"));
        FINES.put("comunicaciones comerciales", frases("comunicaciones comerciales", "newsletter", "boletin", "publicidad",
                "marketing", "promociones", "ofertas", "informacion comercial"));
        FINES.put("cuentas de usuario", frases("registro de usuarios", "registro como usuario", "registrarse",
                "cuenta de usuario", "su cuenta", "tu cuenta", "gestion de usuarios", "area privada"));
        FINES.put("análisis y mejora", frases("analitica", "analisis estadistico", "estadisticas", "estadisticos", "mejorar",
                "mejora", "medir", "medicion", "calidad del servicio"));
        FINES.put("obligaciones legales", frases("obligaciones legales", "obligacion legal", "cumplimiento legal", "cumplir con"));
        FINES.put("selección de personal", frases("seleccion de personal", "procesos de seleccion", "curriculum", "candidatos",
                "candidatura"));
        FINES.put("seguridad y prevención del fraude", frases("prevencion del fraude", "prevenir el fraude", "fraude",
                "seguridad de la plataforma", "seguridad del sitio"));
        FINES.put("perfiles y personalización", frases("elaborar perfiles", "perfiles", "perfilado", "personalizar",
                "personalizacion"));
    }

    /** Purposes left open, contrary to purpose limitation (Art. 5.1.b RGPD). */
    private static final List<List<String>> FINES_ABIERTOS = frases("cualquier finalidad", "cualquier fin",
            "cualquier otra finalidad", "cualquier otro fin", "cualquier proposito", "cualquier otro proposito",
            "cualquier uso", "fines que estimemos", "finalidades que estimemos", "fines que consideremos",
            "finalidades que consideremos");

    private static Evaluacion finalidades(List<Clausula> cs, String tipo) {
        Clausula suficiente = null;
        Clausula problema = null;
        Clausula mencion = null;
        Set<String> fines = new LinkedHashSet<>();
        int ventana = 0;
        for (Clausula c : cs) {
            boolean trasEncabezado = ventana > 0;
            if (ventana > 0) ventana--;
            // A short «Finalidades» heading lets the following list items state them.
            if (encabezado(c, "finalidad") || encabezado(c, "finalidades") || encabezado(c, "fines")) ventana = 6;
            // The purposes of cookies are R10's subject.
            if (alguna(c, COOKIES)) continue;
            boolean deFinalidad = trasEncabezado || algunaAfirmada(c, MARCA_FINALIDAD)
                    || (algunaAfirmada(c, USO_DATOS) && alguna(c, PARA));
            if (problema == null && algunaAfirmada(c, FINES_ABIERTOS) && (deFinalidad || alguna(c, DATOS))) problema = c;
            if (!deFinalidad) continue;
            Set<String> aqui = etiquetasAfirmadas(c, FINES);
            if (!aqui.isEmpty()) {
                fines.addAll(aqui);
                if (suficiente == null) suficiente = c;
            } else if (mencion == null && (algunaAfirmada(c, MARCA_FINALIDAD) || algunaAfirmada(c, USO_DATOS))) {
                mencion = c;
            }
        }
        return cerrar(tipo, suficiente, "Finalidades indicadas: " + String.join(", ", fines) + ".", problema,
                "Se reserva el uso de los datos para cualquier finalidad, lo que puede ser contrario al principio de limitación de la "
                        + "finalidad (Art. 5.1.b RGPD).", mencion,
                "Se alude a la finalidad del tratamiento sin describir para qué se utilizan los datos.",
                "No se indican las finalidades del tratamiento.", false);
    }

    // ---------------------------------------------------------------------------------------
    // R09 — Transferencias internacionales (never assumed: silence is not verifiable)
    // ---------------------------------------------------------------------------------------

    private static final List<List<String>> TRANSFERENCIA_INTERNACIONAL = frases("transferencia internacional",
            "transferencias internacionales");
    private static final List<List<String>> TRANSFERIR = frases("transferir", "transferirse", "transferimos",
            "transferiremos", "transferira", "transferiran", "transferidos", "transferidas", "transfiere", "transfieren",
            "transferencia", "transferencias", "alojados", "alojadas",
            "alojamos", "almacenados", "almacenadas", "almacenamos", "ubicados", "ubicadas", "se almacenan", "se alojan",
            "tratados", "tratadas");
    private static final List<List<String>> FUERA_EEE = frases("fuera del eee", "fuera del espacio economico europeo",
            "fuera de la union europea", "fuera de la ue", "fuera de europa", "terceros paises", "tercer pais",
            "paises terceros", "otros paises", "cualquier pais", "estados unidos", "ee uu", "eeuu", "reino unido", "suiza",
            "canada", "japon", "india", "china", "australia", "brasil", "mexico", "argentina");
    private static final List<List<String>> DENTRO_EEE = frases("espacio economico europeo", "eee", "union europea", "ue",
            "dentro de europa");
    private static final List<List<String>> ALOJAMIENTO = frases("servidores", "alojados", "alojadas", "alojamos",
            "almacenados", "almacenadas", "almacenamos", "ubicados", "ubicadas", "se almacenan", "se alojan");
    private static final List<List<String>> SALIDA = frases("salen", "saldran", "salir", "sale");
    private static final List<List<String>> PERMANENCIA = frases("permanecen en", "permaneceran en", "permanecen dentro",
            "se mantienen en", "se mantienen dentro");
    private static final List<List<String>> EXCEPCION = frases("salvo", "excepto", "a excepcion", "con excepcion");
    private static final Map<String, List<List<String>>> GARANTIAS = new LinkedHashMap<>();

    static {
        GARANTIAS.put("cláusulas contractuales tipo", frases("clausulas contractuales tipo", "clausulas tipo",
                "clausulas contractuales estandar", "standard contractual clauses"));
        GARANTIAS.put("decisión de adecuación", frases("decision de adecuacion", "decisiones de adecuacion"));
        GARANTIAS.put("Marco de Privacidad de Datos UE-EE. UU.", frases("marco de privacidad de datos",
                "data privacy framework"));
        GARANTIAS.put("normas corporativas vinculantes", frases("normas corporativas vinculantes", "binding corporate rules",
                "bcr"));
        GARANTIAS.put("excepciones del Art. 49 RGPD", frases("consentimiento explicito", "articulo 49", "art 49"));
    }

    private static final List<List<String>> GARANTIAS_GENERICAS = frases("garantias adecuadas", "garantias apropiadas",
            "garantias suficientes", "garantias necesarias");
    private static final List<List<String>> PRIVACY_SHIELD = frases("privacy shield", "escudo de privacidad");
    private static final List<List<String>> TRANSFERENCIA_SIN_GARANTIAS = frases("sin garantias", "sin ninguna garantia", "cualquier pais",
            "sin restricciones", "sin restriccion");

    private static Evaluacion transferencias(List<Clausula> cs, String tipo) {
        Clausula conGarantias = null;
        Clausula sinGarantias = null;
        Clausula noTransfiere = null;
        Clausula soloAlojamiento = null;
        Clausula problema = null;
        Set<String> garantias = new LinkedHashSet<>();
        boolean generica = false;
        boolean privacyShield = false;
        int ventana = 0;
        for (Clausula c : cs) {
            boolean trasEncabezado = ventana > 0;
            if (ventana > 0) ventana--;
            if (encabezado(c, "transferencias")) ventana = 3;
            Set<String> aqui = etiquetasAfirmadas(c, GARANTIAS);
            boolean transfiere = alguna(c, TRANSFERENCIA_INTERNACIONAL) || (alguna(c, TRANSFERIR) && alguna(c, FUERA_EEE))
                    || (trasEncabezado && (alguna(c, FUERA_EEE) || !aqui.isEmpty()));
            if (!transfiere) {
                // Where the data stay, not where they go.
                if (alguna(c, DENTRO_EEE) && (alguna(c, ALOJAMIENTO) || alguna(c, SALIDA) || alguna(c, PERMANENCIA))) {
                    if (algunaNegada(c, SALIDA) || algunaNegada(c, TRANSFERIR) || algunaAfirmada(c, PERMANENCIA)) {
                        if (noTransfiere == null) noTransfiere = c;
                    } else if (soloAlojamiento == null) {
                        soloAlojamiento = c;
                    }
                }
                continue;
            }
            boolean afirmada = algunaAfirmada(c, TRANSFERENCIA_INTERNACIONAL)
                    || (algunaAfirmada(c, TRANSFERIR) && alguna(c, FUERA_EEE)) || trasEncabezado
                    // «No realizamos transferencias, salvo a Estados Unidos con cláusulas tipo» discloses one.
                    || (alguna(c, EXCEPCION) && (alguna(c, FUERA_EEE) || !aqui.isEmpty()));
            if (!afirmada) {
                if (noTransfiere == null) noTransfiere = c;
                continue;
            }
            if (problema == null && alguna(c, TRANSFERENCIA_SIN_GARANTIAS)) problema = c;
            if (!aqui.isEmpty()) {
                garantias.addAll(aqui);
                if (conGarantias == null) conGarantias = c;
            } else if (sinGarantias == null) {
                sinGarantias = c;
                generica = alguna(c, GARANTIAS_GENERICAS);
                privacyShield = alguna(c, PRIVACY_SHIELD);
            }
        }
        // A disclosed transfer without safeguards outweighs a general denial.
        Clausula suficiente = conGarantias != null ? conGarantias : sinGarantias == null ? noTransfiere : null;
        String detalleSuficiente = conGarantias != null
                ? "Se informa de transferencias internacionales con estas garantías: " + String.join(", ", garantias)
                        + ". El texto no permite comprobar que se apliquen."
                : "El documento declara que no realiza transferencias internacionales; el texto no permite comprobarlo.";
        Clausula mencion = sinGarantias != null ? sinGarantias : soloAlojamiento;
        String detalleMencion = sinGarantias != null
                ? "Se mencionan transferencias internacionales sin indicar el mecanismo de garantía (Arts. 45 a 49 RGPD)."
                        + (generica ? " Se alude a garantías adecuadas sin concretarlas." : "")
                        + (privacyShield ? " El Escudo de Privacidad UE-EE. UU. fue anulado por el TJUE en 2020 (asunto C-311/18)." : "")
                : "Se indica dónde se alojan los datos, pero no si se transfieren fuera del Espacio Económico Europeo.";
        return cerrar(tipo, suficiente, detalleSuficiente, problema,
                "Se declaran transferencias internacionales sin garantías o hacia cualquier país.", mencion, detalleMencion,
                "El documento no menciona transferencias internacionales; el texto no permite saber si se realizan.", true);
    }

    // ---------------------------------------------------------------------------------------
    // R11 — Derecho a reclamar ante la autoridad de control
    // ---------------------------------------------------------------------------------------

    private static final List<List<String>> AUTORIDAD = frases("aepd", "agencia espanola de proteccion de datos",
            "autoridad de control", "autoridades de control", "autoridad de proteccion de datos", "autoridad supervisora",
            "autoridad de supervision", "autoridad competente en materia de proteccion de datos",
            "autoridad catalana de proteccion de datos", "apdcat", "agencia vasca de proteccion de datos",
            "consejo de transparencia y proteccion de datos");
    private static final List<List<String>> RECLAMAR = frases("reclamacion", "reclamaciones", "reclamar", "reclame",
            "denuncia", "denunciar", "tutela");
    private static final List<List<String>> SOBRE_DATOS_RECLAMACION = frases("datos", "tratamiento", "proteccion de datos",
            "privacidad", "derechos");
    /** Explicit denials or waivers: generic negation would misread «Si no obtiene respuesta, puede reclamar». */
    private static final List<List<String>> NIEGA_RECLAMAR = frases("no puede reclamar", "no podra reclamar",
            "no podran reclamar", "no puede presentar", "no podra presentar", "no podran presentar",
            "no tiene derecho a reclamar", "no cabe reclamacion", "no se admitiran reclamaciones", "no se admiten reclamaciones",
            "renuncia a reclamar", "renuncia a presentar", "renuncia a cualquier reclamacion", "renuncia a interponer");

    private static Evaluacion reclamacion(List<Clausula> cs, String tipo) {
        Clausula suficiente = null;
        Clausula problema = null;
        Clausula soloAutoridad = null;
        Clausula soloReclamacion = null;
        for (int i = 0; i < cs.size(); i++) {
            Clausula c = cs.get(i);
            boolean autoridad = alguna(c, AUTORIDAD);
            boolean reclama = alguna(c, RECLAMAR);
            if (alguna(c, NIEGA_RECLAMAR) && (autoridad || alguna(c, SOBRE_DATOS_RECLAMACION))) {
                if (problema == null) problema = c;
                continue;
            }
            Clausula anterior = i > 0 ? cs.get(i - 1) : null;
            Clausula siguiente = i + 1 < cs.size() ? cs.get(i + 1) : null;
            if (autoridad && reclama) {
                if (suficiente == null) suficiente = c;
            } else if (autoridad) {
                // «Puede presentar una reclamación. La autoridad de control es la AEPD.»
                if (anterior != null && alguna(anterior, RECLAMAR) && !alguna(anterior, NIEGA_RECLAMAR)) {
                    if (suficiente == null) suficiente = unir(anterior, c);
                } else if (soloAutoridad == null) {
                    soloAutoridad = c;
                }
            } else if (reclama && alguna(c, SOBRE_DATOS_RECLAMACION)) {
                if (siguiente != null && alguna(siguiente, AUTORIDAD) && !alguna(siguiente, NIEGA_RECLAMAR)) {
                    if (suficiente == null) suficiente = unir(c, siguiente);
                } else if (soloReclamacion == null) {
                    soloReclamacion = c;
                }
            }
        }
        Clausula mencion = soloAutoridad != null ? soloAutoridad : soloReclamacion;
        return cerrar(tipo, suficiente, "Se informa del derecho a presentar una reclamación ante la autoridad de control.",
                problema, "El texto niega o condiciona el derecho a reclamar ante la autoridad de control (Art. 77 RGPD).",
                mencion, soloAutoridad != null
                        ? "Se menciona la autoridad de control sin informar del derecho a presentar una reclamación ante ella."
                        : "Se menciona una reclamación sin identificar la autoridad de control ante la que presentarla.",
                "No se informa del derecho a presentar una reclamación ante la autoridad de control.", false);
    }

    // ---------------------------------------------------------------------------------------
    // R16 — Información general del prestador (Art. 10 LSSI-CE)
    // ---------------------------------------------------------------------------------------

    private static final String TIPO_AVISO = "Aviso Legal";
    /** Documents whose subject is not the provider's general information: it belongs to the legal notice. */
    private static final Set<String> TIPOS_SIN_AVISO = Set.of("Política de Privacidad", "Política de Cookies",
            "Términos y Condiciones", "Condiciones de Uso", "Condiciones de Compra", "Accesibilidad");
    private static final List<List<String>> MARCAS_LSSI = frases("aviso legal", "nota legal", "informacion legal", "lssi",
            "ley 34 2002", "sociedad de la informacion", "datos identificativos", "prestador de servicios",
            "informacion general");
    private static final Pattern NOMBRE_PRESTADOR = nombreTras("titular(?: del (?:sitio(?: web)?|portal|dominio)"
            + "| de (?:la web|la pagina|este sitio(?: web)?))?|razon social|denominacion(?: social)?|prestador(?: del servicio)?"
            + "|nombre comercial|empresa|responsable");
    private static final List<List<String>> DOMICILIO = frases("domicilio", "domicilio social", "sede social", "con sede en",
            "sede en", "direccion postal", "domiciliada", "domiciliado", "establecimiento");
    private static final List<List<String>> REGISTRO = frases("registro mercantil", "inscrita en el", "inscrito en el",
            "registro de asociaciones", "registro de cooperativas", "registro de fundaciones", "datos registrales",
            "datos de inscripcion");
    private static final List<String> DATOS_PRESTADOR = List.of("identidad", "NIF", "domicilio", "datos registrales",
            "correo electrónico");

    /** The Art. 10.1 LSSI-CE items the clause gives, in {@link #DATOS_PRESTADOR} order. */
    private static Set<String> datosPrestador(Clausula c) {
        Set<String> datos = new LinkedHashSet<>();
        if (alguna(c, FORMAS_JURIDICAS) || nombra(c, NOMBRE_PRESTADOR)) datos.add("identidad");
        if (nif(c)) datos.add("NIF");
        boolean codigoPostal = c.tokens().stream().anyMatch(t -> t.length() == 5 && t.chars().allMatch(Character::isDigit));
        if (algunaAfirmada(c, DOMICILIO) || (alguna(c, DIRECCION) && codigoPostal)) datos.add("domicilio");
        if (alguna(c, REGISTRO) || (alguna(c, frases("tomo")) && alguna(c, frases("folio")))) datos.add("datos registrales");
        if (EMAIL.matcher(c.original()).find() && !alguna(c, EJEMPLO)) datos.add("correo electrónico");
        return datos;
    }

    private static Evaluacion avisoLegal(List<Clausula> cs, String tipo) {
        String t = tipo == null ? null : tipo.trim();
        boolean contexto = TIPO_AVISO.equals(t);
        boolean mercantil = false;
        Set<String> datos = new LinkedHashSet<>();
        Clausula mejor = null;
        int mejorCuenta = 0;
        for (Clausula c : cs) {
            contexto |= alguna(c, MARCAS_LSSI);
            mercantil |= alguna(c, FORMAS_MERCANTILES);
            Set<String> aqui = datosPrestador(c);
            datos.addAll(aqui);
            if (aqui.size() > mejorCuenta) {
                mejor = c;
                mejorCuenta = aqui.size();
            }
        }
        // A company in the Registro Mercantil must give its registry data (Art. 10.1.b); otherwise only if registered.
        boolean registroExigido = mercantil;
        List<String> exigidos = DATOS_PRESTADOR.stream().filter(d -> registroExigido || !d.equals("datos registrales")).toList();
        List<String> faltan = exigidos.stream().filter(d -> !datos.contains(d)).toList();
        String indicados = "Datos del prestador indicados: " + (datos.isEmpty() ? "ninguno" : String.join(", ", datos));
        if (faltan.isEmpty()) {
            return new Evaluacion(EstadoEvidencia.SUFICIENTE, true, false, cita(mejor), indicados + ". El texto no permite "
                    + "comprobar que sean correctos ni si el titular presta servicios de la sociedad de la información.");
        }
        if (t != null && TIPOS_SIN_AVISO.contains(t)) {
            return new Evaluacion(EstadoEvidencia.NO_APLICABLE, false, false, null, "No se exige en un documento de tipo «"
                    + t + "»: esta información corresponde al aviso legal.");
        }
        if (contexto && mejor != null) {
            return new Evaluacion(EstadoEvidencia.INSUFICIENTE, false, false, cita(mejor), indicados + "; falta: "
                    + String.join(", ", faltan) + ".");
        }
        if (TIPO_AVISO.equals(t)) {
            return new Evaluacion(EstadoEvidencia.POSIBLE_INCIDENCIA, false, false, null, "El aviso legal no identifica al "
                    + "prestador; el Art. 10 LSSI-CE lo exige si el titular presta servicios de la sociedad de la información.");
        }
        return new Evaluacion(EstadoEvidencia.NO_VERIFICABLE, false, false, null, (mejor != null
                ? "Aparecen datos del titular, pero el documento no se presenta como aviso legal. " : "")
                + "El texto no permite saber si el titular presta servicios de la sociedad de la información (Art. 10 "
                + "LSSI-CE) ni si esa información figura en otra página.");
    }

    // ---------------------------------------------------------------------------------------
    // Shared by R13, R14, R17, R18 and R19: a practice is never presumed, and what the text discloses
    // prevails over a general denial elsewhere, which is still reported
    // ---------------------------------------------------------------------------------------

    /** Further than ALCANCE_NEGACION: «No recogemos datos de menores de 14 años sin el consentimiento de sus padres». */
    private static final int ALCANCE_NEGACION_LARGO = 10;
    private static final List<List<String>> TRATA = frases("tratamos", "trataremos", "tratar", "tratara", "trataran",
            "tratan", "tratados", "tratadas", "tratamiento", "recogemos", "recogeremos", "recoger", "recogidos", "recogidas",
            "recabamos", "recabaremos", "recabar", "recabados", "recopilamos", "recopilaremos", "recopilar", "solicitamos",
            "solicitaremos", "solicitar", "pedimos", "pediremos", "almacenamos", "conservamos", "utilizamos", "utilizaremos",
            "usamos", "usaremos", "registramos", "procesamos", "procesar", "facilite", "facilitar", "facilitarnos",
            "proporcione", "proporcionar", "proporcionarnos", "permitimos", "aceptamos", "admitimos");
    /** «Tratamos de mejorar» (we try) and «tratamos a todos por igual» (we treat) are not processing. */
    private static final Set<String> TRATAR_VERBO = Set.of("tratamos", "trataremos", "tratar", "tratara", "trataran", "tratan");

    private static boolean algunaAfirmadaLejos(Clausula c, List<List<String>> frases) {
        return frases.stream().anyMatch(f -> posiciones(c, f).stream()
                .anyMatch(p -> !negada(c.tokens(), p, ALCANCE_NEGACION_LARGO)));
    }

    /** Positions of a verb of processing personal data. */
    private static List<Integer> tratamientos(Clausula c) {
        List<Integer> resultado = new ArrayList<>();
        List<String> t = c.tokens();
        for (List<String> f : TRATA) {
            for (int p : posiciones(c, f)) {
                int siguiente = p + f.size();
                boolean otroSentido = TRATAR_VERBO.contains(f.get(0)) && siguiente < t.size()
                        && (t.get(siguiente).equals("de") || t.get(siguiente).equals("a"));
                if (!otroSentido) resultado.add(p);
            }
        }
        return resultado;
    }

    private static boolean trataAfirmado(Clausula c) {
        return tratamientos(c).stream().anyMatch(p -> !negada(c.tokens(), p, ALCANCE_NEGACION_LARGO));
    }

    private static boolean trataNegado(Clausula c) {
        return tratamientos(c).stream().anyMatch(p -> negada(c.tokens(), p, ALCANCE_NEGACION_LARGO));
    }

    /** The phrases affirmed in the clause, in catalogue order. */
    private static List<String> afirmadas(Clausula c, List<List<String>> frases) {
        return frases.stream().filter(f -> posiciones(c, f).stream().anyMatch(p -> !negada(c.tokens(), p)))
                .map(f -> String.join(" ", f)).toList();
    }

    private static String contradiccion(Clausula negacion) {
        return negacion == null ? "" : " El documento también declara lo contrario: " + cita(negacion) + ".";
    }

    // ---------------------------------------------------------------------------------------
    // R13 — Datos de menores (Art. 8 RGPD, Art. 7 LOPDGDD): a mention of children is no processing of their data
    // ---------------------------------------------------------------------------------------

    /** A minor's own consent is valid in Spain from this age (Art. 7.1 LOPDGDD). */
    private static final int EDAD_CONSENTIMIENTO = 14;
    /** Whole phrases: «cambios menores» or «el menor tiempo posible» are not about minors. */
    private static final List<List<String>> MENORES = frases("menor de edad", "menores de edad", "los menores",
            "las menores", "del menor", "de menores", "a menores", "para menores", "ninos", "ninas", "nino", "nina",
            "sus hijos", "tus hijos", "hijos menores", "adolescentes");
    private static final Map<String, Integer> EDADES_EN_LETRA = Map.of("diez", 10, "once", 11, "doce", 12, "trece", 13,
            "catorce", 14, "quince", 15, "dieciseis", 16, "diecisiete", 17, "dieciocho", 18);
    private static final Set<String> MENOR_O_MAYOR = Set.of("menor", "menores", "mayor", "mayores");
    private static final Set<String> LIMITE_DE = Set.of("partir", "edad", "minima", "minimo");
    private static final Set<String> TENER = Set.of("tener", "tenga", "tengan", "tengas", "tiene", "tienen", "tienes",
            "cumplido", "cumplidos");
    /** What turns a mention of minors into processing of their data: data, consent, registration or an account. */
    private static final List<List<String>> DATOS_MENORES = frases("datos", "informacion personal", "consentimiento",
            "consentir", "consienta", "consientan", "registrarse", "registrarte", "registro", "registren", "registre",
            "cuenta", "formulario", "inscripcion", "inscribir", "suscribirse");
    /** The minor's own consent, the only case Art. 7 LOPDGDD regulates. */
    private static final List<List<String>> CONSIENTE = frases("consentimiento", "consentimientos", "consentir", "consienta",
            "consientan", "consiente");
    private static final List<List<String>> USO_SERVICIO = frases("usar", "utilizar", "uso", "acceder", "servicio",
            "servicios", "sitio", "web", "plataforma", "aplicacion");
    private static final List<List<String>> DIRIGIDO = frases("dirigido", "dirigida", "dirigidos", "dirigidas", "destinado",
            "destinada", "destinados", "destinadas", "se dirige", "se dirigen");
    private static final List<List<String>> PARENTAL = frases("consentimiento parental", "autorizacion parental",
            "consentimiento de sus padres", "consentimiento de los padres", "consentimiento de tus padres",
            "autorizacion de sus padres", "autorizacion de los padres", "autorizacion de tus padres", "padres o tutores",
            "padre madre o tutor", "titulares de la patria potestad", "patria potestad", "tutores legales", "tutor legal");
    /** Parental only in a clause about minors: «los representantes legales de la empresa» are not. */
    private static final List<List<String>> PARENTAL_CON_MENOR = frases("representantes legales", "representante legal",
            "tutores", "tutor", "sus padres", "tus padres", "los padres");
    private static final List<List<String>> SIN_PARENTAL = frases("sin el consentimiento de sus padres",
            "sin consentimiento de sus padres", "sin el consentimiento de los padres", "sin consentimiento de los padres",
            "sin consentimiento parental", "sin autorizacion parental", "sin la autorizacion de sus padres",
            "sin autorizacion de sus padres", "sin el consentimiento de sus representantes", "sin consentimiento de sus tutores");
    /** They carry their own negation and are matched as written. */
    private static final List<List<String>> EDAD_NO_VERIFICADA = frases("no verificamos la edad", "no comprobamos la edad",
            "no verificamos su edad", "no comprobamos su edad", "no se verifica la edad", "no se comprueba la edad",
            "sin verificar la edad", "sin comprobar la edad");
    private static final List<List<String>> SIN_LIMITE_EDAD = frases("sin restriccion de edad", "sin restricciones de edad",
            "no existe restriccion de edad", "no hay restriccion de edad", "sin limite de edad", "no hay limite de edad",
            "no existe limite de edad", "cualquier edad");

    private static Integer edad(String token) {
        if (!token.isEmpty() && token.length() <= 2 && token.chars().allMatch(Character::isDigit)) return Integer.valueOf(token);
        return EDADES_EN_LETRA.get(token);
    }

    /** Ages set as a limit: «menores de 14», «a partir de 16 años», «tener al menos 13 años», «mayor de edad». */
    private static List<Integer> umbralesEdad(Clausula c) {
        List<Integer> umbrales = new ArrayList<>();
        List<String> t = c.tokens();
        for (int i = 1; i < t.size(); i++) {
            Integer n = edad(t.get(i));
            if (n == null) continue;
            String antes = t.get(i - 1);
            boolean anos = i + 1 < t.size() && (t.get(i + 1).equals("anos") || t.get(i + 1).equals("ano"));
            boolean menorDe = antes.equals("de") && i >= 2 && MENOR_O_MAYOR.contains(t.get(i - 2));
            // A period alone is no age limit: «conservaremos sus datos al menos 5 años».
            boolean limite = anos && (TENER.contains(antes)
                    || (antes.equals("de") && i >= 2 && LIMITE_DE.contains(t.get(i - 2)))
                    || (antes.equals("los") && i >= 3 && t.get(i - 2).equals("de") && t.get(i - 3).equals("partir"))
                    || ((antes.equals("menos") || antes.equals("minimo")) && i >= 3 && TENER.contains(t.get(i - 3))));
            if (menorDe || limite) umbrales.add(n);
        }
        if (alguna(c, frases("mayor de edad", "mayores de edad", "mayoria de edad"))) umbrales.add(18);
        return umbrales;
    }

    private static Evaluacion menores(List<Clausula> cs, String tipo) {
        Clausula condiciones = null;
        Clausula exclusion = null;
        Clausula problema = null;
        Clausula mencion = null;
        Clausula registroBajo = null;
        Clausula sinBase = null;
        Clausula edadSinControl = null;
        int edadRegistro = 0;
        String detalleProblema = null;
        String detalleCondiciones = null;
        int ventana = 0;
        for (Clausula c : cs) {
            boolean trasEncabezado = ventana > 0;
            if (ventana > 0) ventana--;
            // A short «Menores» heading lets the following clauses speak of them.
            if (encabezado(c, "menores")) ventana = 3;
            List<String> t = c.tokens();
            boolean menor = trasEncabezado || alguna(c, MENORES) || (!t.isEmpty() && t.get(0).equals("menores"));
            List<Integer> umbrales = umbralesEdad(c);
            boolean datos = alguna(c, DATOS_MENORES);
            boolean sinParental = algunaAfirmadaLejos(c, SIN_PARENTAL);
            boolean parental = !sinParental && (alguna(c, PARENTAL) || (menor && alguna(c, PARENTAL_CON_MENOR)));
            List<Integer> bajos = umbrales.stream().filter(e -> e < EDAD_CONSENTIMIENTO).toList();
            // Art. 7 LOPDGDD governs processing based on the minor's consent; other legal bases do not depend on it.
            boolean consiente = alguna(c, CONSIENTE);
            boolean sinLimite = alguna(c, SIN_LIMITE_EDAD) && (datos || alguna(c, USO_SERVICIO));
            String motivo = null;
            if (sinParental) {
                motivo = "El texto admite datos de menores sin el consentimiento de sus padres o tutores, lo que puede ser "
                        + "contrario al Art. 8 RGPD y al Art. 7 LOPDGDD si el tratamiento se basa en su consentimiento.";
            } else if (consiente && (sinLimite || !bajos.isEmpty())) {
                motivo = sinLimite ? "El texto admite el consentimiento de usuarios de cualquier edad; el Art. 7 LOPDGDD solo "
                        + "admite el del propio menor cuando es mayor de 14 años."
                        : "El texto admite el consentimiento propio de menores desde los " + bajos.get(0) + " años; el Art. 7 "
                        + "LOPDGDD solo lo admite cuando el menor es mayor de 14 años.";
            }
            if (motivo != null) {
                if (problema == null) {
                    problema = c;
                    detalleProblema = motivo;
                }
                continue;
            }
            // Not checking ages is no breach by itself: it matters only if processing relies on minors' consent.
            if ((menor || alguna(c, frases("edad"))) && (alguna(c, EDAD_NO_VERIFICADA) || sinLimite)) {
                if (edadSinControl == null) edadSinControl = c;
                continue;
            }
            boolean noDirigido = menor && algunaNegada(c, DIRIGIDO);
            boolean relevante = parental || noDirigido || (menor && datos)
                    || (!umbrales.isEmpty() && (datos || alguna(c, USO_SERVICIO)));
            if (!relevante) continue;
            List<Integer> validos = umbrales.stream().filter(e -> e >= EDAD_CONSENTIMIENTO).sorted().toList();
            if (parental || !validos.isEmpty()) {
                if (condiciones == null) {
                    condiciones = c;
                    List<String> partes = new ArrayList<>();
                    if (!validos.isEmpty()) partes.add("edad mínima de " + validos.get(0) + " años");
                    if (parental) partes.add("consentimiento de sus padres o tutores");
                    detalleCondiciones = "Se indican condiciones para los datos de menores: " + String.join(" y ", partes)
                            + ". El texto no permite comprobar cómo se verifican la edad ni el consentimiento.";
                }
            } else if (noDirigido || (menor && trataNegado(c) && !trataAfirmado(c))) {
                if (exclusion == null) exclusion = c;
            } else if (!bajos.isEmpty()) {
                // «Para crear una cuenta debe tener al menos 13 años»: an age to register, not necessarily to consent.
                if (registroBajo == null) {
                    registroBajo = c;
                    edadRegistro = bajos.get(0);
                }
            } else if (consiente) {
                if (mencion == null) mencion = c;
            } else if (sinBase == null) {
                sinBase = c;
            }
        }
        // Processing described without conditions outweighs a general statement that minors are not addressed.
        boolean contradicha = exclusion != null && (mencion != null || registroBajo != null || sinBase != null);
        Clausula suficiente = condiciones != null ? condiciones : !contradicha ? exclusion : null;
        String notaEdad = suficiente != null && edadSinControl != null ? " El texto indica que no se comprueba la edad: si "
                + "algún tratamiento de datos de menores se basa en su consentimiento, el Art. 8.2 RGPD exige esfuerzos "
                + "razonables para verificar el consentimiento de sus padres o tutores." : "";
        String detalleSuficiente = (condiciones != null ? detalleCondiciones
                : "El documento declara que no se dirige a menores o que no trata sus datos; el texto no permite comprobarlo.")
                + notaEdad;
        Clausula insuficiente;
        String detalleInsuficiente;
        if (mencion != null) {
            insuficiente = mencion;
            detalleInsuficiente = "El texto basa en el consentimiento el tratamiento de datos de menores sin indicar la edad "
                    + "mínima ni el consentimiento de sus padres o tutores." + contradiccion(exclusion);
        } else if (registroBajo != null) {
            insuficiente = registroBajo;
            detalleInsuficiente = "El texto fija en " + edadRegistro + " años la edad para registrarse sin indicar si el "
                    + "registro se basa en el consentimiento; si es así, por debajo de los 14 años se requiere el de quien "
                    + "ejerce la patria potestad o tutela (Art. 7 LOPDGDD)." + contradiccion(exclusion);
        } else if (contradicha) {
            insuficiente = sinBase;
            detalleInsuficiente = "Se describe el tratamiento de datos de menores sin indicar la edad mínima ni el "
                    + "consentimiento de sus padres o tutores." + contradiccion(exclusion);
        } else if (suficiente == null && edadSinControl != null) {
            insuficiente = edadSinControl;
            detalleInsuficiente = "El texto indica que no se comprueba ni se limita la edad de los usuarios, sin indicar si el "
                    + "servicio se dirige a menores ni si algún tratamiento se basa en su consentimiento. No comprobar la "
                    + "edad no es por sí solo un incumplimiento.";
        } else {
            insuficiente = null;
            detalleInsuficiente = null;
        }
        Evaluacion resultado = cerrar(tipo, suficiente, detalleSuficiente, problema, detalleProblema, insuficiente,
                detalleInsuficiente, "El documento no indica si se tratan datos de menores; el texto no permite saber si el "
                        + "servicio se dirige a ellos ni si algún tratamiento se basa en su consentimiento.", true);
        if (resultado.estado() == EstadoEvidencia.NO_VERIFICABLE && sinBase != null) {
            return new Evaluacion(EstadoEvidencia.NO_VERIFICABLE, false, false, cita(sinBase), "Se mencionan datos de "
                    + "menores, pero el texto no indica si su tratamiento se basa en su consentimiento: la edad del Art. 7 "
                    + "LOPDGDD solo se aplica a ese caso, y otras bases jurídicas no dependen de ella.");
        }
        return resultado;
    }

    // ---------------------------------------------------------------------------------------
    // R14 — Categorías especiales de datos (Art. 9 RGPD): naming a sensitive topic is no processing
    // ---------------------------------------------------------------------------------------

    private static final Map<String, List<List<String>>> CATEGORIAS_ESPECIALES = new LinkedHashMap<>();
    /** Bare words that are special-category data only in a clause about collecting personal information. */
    private static final Map<String, List<List<String>>> CATEGORIAS_SI_SON_DATOS = new LinkedHashMap<>();
    private static final Map<String, List<List<String>>> EXCEPCIONES_ART_9 = new LinkedHashMap<>();

    static {
        CATEGORIAS_ESPECIALES.put("salud", frases("datos de salud", "datos relativos a la salud", "datos sobre su salud",
                "datos sobre la salud", "datos de su salud", "datos medicos", "datos clinicos", "datos sanitarios",
                "informacion medica", "informacion sanitaria", "informacion de salud", "informacion sobre su salud",
                "historial medico", "historia clinica", "historial clinico", "informes medicos", "estado de salud",
                "grado de discapacidad", "certificado de discapacidad"));
        CATEGORIAS_ESPECIALES.put("datos biométricos", frases("datos biometricos", "huella dactilar", "huellas dactilares",
                "reconocimiento facial", "biometria"));
        CATEGORIAS_ESPECIALES.put("datos genéticos", frases("datos geneticos", "informacion genetica"));
        CATEGORIAS_ESPECIALES.put("origen étnico o racial", frases("origen etnico", "origen racial"));
        CATEGORIAS_ESPECIALES.put("opiniones políticas", frases("opiniones politicas", "opinion politica",
                "ideologia politica", "afiliacion politica"));
        CATEGORIAS_ESPECIALES.put("convicciones religiosas o filosóficas", frases("creencias religiosas",
                "convicciones religiosas", "convicciones filosoficas"));
        CATEGORIAS_ESPECIALES.put("afiliación sindical", frases("afiliacion sindical"));
        CATEGORIAS_ESPECIALES.put("vida u orientación sexual", frases("orientacion sexual", "vida sexual"));
        CATEGORIAS_ESPECIALES.put("condenas e infracciones penales (Art. 10 RGPD)", frases("antecedentes penales",
                "condenas penales", "infracciones penales", "delitos de naturaleza sexual", "datos penales"));
        CATEGORIAS_ESPECIALES.put("categorías especiales sin concretar", frases("categorias especiales",
                "datos especialmente protegidos", "datos sensibles"));
        CATEGORIAS_SI_SON_DATOS.put("salud", frases("alergias", "intolerancias", "enfermedades", "medicacion"));
        CATEGORIAS_SI_SON_DATOS.put("convicciones religiosas o filosóficas", frases("religion", "creencias"));
        CATEGORIAS_SI_SON_DATOS.put("opiniones políticas", frases("ideologia"));

        EXCEPCIONES_ART_9.put("consentimiento explícito", frases("consentimiento explicito", "consentimiento expreso"));
        EXCEPCIONES_ART_9.put("obligaciones laborales o de protección social", frases("derecho laboral",
                "obligaciones laborales", "ambito laboral", "seguridad social", "proteccion social",
                "prevencion de riesgos laborales", "vigilancia de la salud", "medicina del trabajo"));
        EXCEPCIONES_ART_9.put("interés vital", frases("interes vital", "intereses vitales"));
        EXCEPCIONES_ART_9.put("asistencia sanitaria", frases("asistencia sanitaria", "diagnostico medico",
                "medicina preventiva", "tratamiento sanitario"));
        EXCEPCIONES_ART_9.put("interés público", frases("interes publico esencial", "salud publica"));
        EXCEPCIONES_ART_9.put("reclamaciones judiciales", frases("defensa de reclamaciones", "ejercicio o defensa"));
        EXCEPCIONES_ART_9.put("datos hechos manifiestamente públicos", frases("manifiestamente publicos"));
        EXCEPCIONES_ART_9.put("habilitación legal", frases("obligacion legal", "habilitacion legal", "exigido por ley",
                "exigida por ley", "lo exige la ley", "norma con rango de ley"));
    }

    /** Stated safeguards: the text can only say they exist, never prove it. */
    private static final List<List<String>> GARANTIAS_ESPECIALES = frases("garantias reforzadas", "garantias adicionales",
            "medidas reforzadas", "medidas adicionales", "seguridad reforzada", "cifrado", "cifrados", "cifradas",
            "acceso restringido", "seudonimizacion", "pseudonimizacion", "secreto profesional", "deber de confidencialidad",
            "personal autorizado");
    /** Art. 9.1 LOPDGDD lists them: consent alone does not suffice to identify them. */
    private static final Set<String> CONSENTIMIENTO_NO_BASTA = Set.of("origen étnico o racial", "opiniones políticas",
            "convicciones religiosas o filosóficas", "afiliación sindical", "vida u orientación sexual");
    private static final List<List<String>> SIN_CONSENTIMIENTO = frases("sin su consentimiento", "sin consentimiento",
            "sin necesidad de consentimiento", "sin su autorizacion", "sin autorizacion");

    private static final List<List<String>> INFORMACION_PERSONAL = frases("datos", "informacion");

    /** Categories named, negated or not: «No tratamos datos de salud» names one too. */
    private static Set<String> categorias(Clausula c) {
        Set<String> categorias = new LinkedHashSet<>();
        CATEGORIAS_ESPECIALES.forEach((categoria, frases) -> {
            if (alguna(c, frases)) categorias.add(categoria);
        });
        // «Tratamos a todos por igual, sin importar su religión» names a belief, not data about it.
        if (alguna(c, INFORMACION_PERSONAL) && !tratamientos(c).isEmpty()) {
            CATEGORIAS_SI_SON_DATOS.forEach((categoria, frases) -> {
                if (alguna(c, frases)) categorias.add(categoria);
            });
        }
        return categorias;
    }

    private static Set<String> excepcionesArt9(Clausula c) {
        Set<String> excepciones = etiquetasAfirmadas(c, EXCEPCIONES_ART_9);
        List<String> t = c.tokens();
        for (int i = 1; i + 1 < t.size(); i++) {
            if (ARTICULO.contains(t.get(i - 1)) && t.get(i).equals("9") && t.get(i + 1).equals("2")) excepciones.add("Art. 9.2 RGPD");
        }
        return excepciones;
    }

    private static Evaluacion categoriasEspeciales(List<Clausula> cs, String tipo) {
        Clausula suficiente = null;
        Clausula problema = null;
        Clausula mencion = null;
        Clausula noTrata = null;
        // Each processing activity needs its own exception: one disclosed exception does not cover the others.
        Set<String> conExcepcion = new LinkedHashSet<>();
        Set<String> sinExcepcion = new LinkedHashSet<>();
        Set<String> excepciones = new LinkedHashSet<>();
        Set<String> garantias = new LinkedHashSet<>();
        for (int i = 0; i < cs.size(); i++) {
            Clausula c = cs.get(i);
            Set<String> aqui = categorias(c);
            if (aqui.isEmpty()) continue;
            boolean afirma = trataAfirmado(c);
            Set<String> excepcionesAqui = excepcionesArt9(c);
            List<String> garantiasAqui = afirmadas(c, GARANTIAS_ESPECIALES);
            if (trataNegado(c) && !afirma && excepcionesAqui.isEmpty()) {
                // «No tratamos datos sensibles, salvo los de salud» discloses the exception.
                if (!alguna(c, EXCEPCION)) {
                    if (noTrata == null) noTrata = c;
                    continue;
                }
                afirma = true;
            }
            // A definition or an incidental mention («respetamos todas las creencias religiosas») is no processing.
            if (!afirma && excepcionesAqui.isEmpty() && garantiasAqui.isEmpty()) continue;
            if (excepcionesAqui.isEmpty() && afirma && (algunaAfirmadaLejos(c, SIN_CONSENTIMIENTO)
                    || algunaAfirmada(c, VENTA) || algunaAfirmada(c, FINES_ABIERTOS))) {
                if (problema == null) problema = c;
                continue;
            }
            Clausula evidencia = c;
            // «Tratamos datos de salud. La base es su consentimiento explícito.»
            if (excepcionesAqui.isEmpty() && i + 1 < cs.size() && categorias(cs.get(i + 1)).isEmpty()) {
                excepcionesAqui = excepcionesArt9(cs.get(i + 1));
                if (!excepcionesAqui.isEmpty()) evidencia = unir(c, cs.get(i + 1));
            }
            garantias.addAll(garantiasAqui);
            if (!excepcionesAqui.isEmpty()) {
                conExcepcion.addAll(aqui);
                excepciones.addAll(excepcionesAqui);
                if (suficiente == null) suficiente = evidencia;
            } else {
                sinExcepcion.addAll(aqui);
                if (mencion == null) mencion = c;
            }
        }
        String declaradas = garantias.isEmpty() ? "" : " Garantías declaradas: " + String.join(", ", garantias) + ".";
        // Art. 9.1 LOPDGDD: consent alone does not lift the prohibition when the main purpose is to identify these data.
        boolean soloConsentimiento = excepciones.equals(Set.of("consentimiento explícito"))
                && conExcepcion.stream().anyMatch(CONSENTIMIENTO_NO_BASTA::contains);
        String notaArt9 = soloConsentimiento ? " Para ideología, afiliación sindical, religión, orientación sexual, creencias "
                + "u origen racial o étnico, el Art. 9.1 LOPDGDD indica que el solo consentimiento no basta cuando la "
                + "finalidad principal del tratamiento es identificar esos datos." : "";
        String detalleSuficiente = suficiente != null
                ? "Categorías especiales: " + String.join(", ", conExcepcion) + ". Excepción del Art. 9.2 RGPD indicada: "
                        + String.join(", ", excepciones) + "." + declaradas + notaArt9
                        + " El texto no acredita que la excepción se aplique ni que las garantías estén implantadas."
                : "El documento declara que no trata categorías especiales de datos; el texto no permite comprobarlo.";
        String parcial = suficiente != null && mencion != null ? " Se indica una excepción del Art. 9.2 RGPD ("
                + String.join(", ", excepciones) + ") para el tratamiento de " + String.join(", ", conExcepcion) + ", pero "
                + "cada tratamiento necesita la suya." : "";
        return cerrar(tipo, mencion != null ? null : suficiente != null ? suficiente : noTrata, detalleSuficiente, problema,
                "El texto declara tratar categorías especiales de datos sin consentimiento ni otra excepción del Art. 9.2 "
                        + "RGPD, para su venta o para cualquier finalidad.", mencion,
                "Se menciona el tratamiento de categorías especiales (" + String.join(", ", sinExcepcion) + ") sin indicar la "
                        + "excepción del Art. 9.2 RGPD que lo permite." + parcial + declaradas
                        + (garantias.isEmpty() ? "" : " Declararlas no acredita que estén implantadas.")
                        + contradiccion(noTrata),
                "El documento no indica si se tratan categorías especiales de datos (Art. 9 RGPD); puede que no se traten, "
                        + "y el texto no permite saberlo.", true);
    }

    // ---------------------------------------------------------------------------------------
    // R17 — Decisiones automatizadas y perfilado (Arts. 13.2.f y 22 RGPD): an algorithm is no automated decision
    // ---------------------------------------------------------------------------------------

    private static final Set<String> DECISION = Set.of("decision", "decisiones");
    private static final Set<String> AUTOMATIZACION = Set.of("automatizada", "automatizadas", "automatizado",
            "automatizados", "automatica", "automaticas", "automatico", "automaticos", "automaticamente");
    /** «Un algoritmo decide», «se rechazan automáticamente»: a decision verb next to its automation. */
    private static final Set<String> DECIDE = Set.of("decide", "deciden", "decidira", "decidiran", "aprueba", "aprueban",
            "aprobara", "aprobada", "aprobadas", "rechaza", "rechazan", "rechazara", "rechazada", "rechazadas", "deniega",
            "deniegan", "denegara", "denegada", "denegadas", "concede", "conceden", "concedera", "resuelve", "resuelven",
            "admite", "admiten", "excluye", "excluyen");
    private static final Set<String> ALGORITMO = Set.of("algoritmo", "algoritmos");
    private static final int CERCANIA_DECISION = 8;
    private static final int CERCANIA_VERBO = 4;
    /** «Tratamiento automatizado» or «fichero automatizado» alone only meant computerised processing. */
    private static final List<List<String>> PERFILES = frases("elaboracion de perfiles", "elaborar perfiles",
            "elaboramos perfiles", "elaboraremos perfiles", "creacion de perfiles", "crear perfiles", "creamos perfiles",
            "crearemos perfiles", "perfilado", "perfilamiento", "perfiles comerciales", "perfil comercial",
            "perfiles de consumo", "perfil de consumo", "perfiles de cliente");
    private static final List<List<String>> LOGICA_DECISION = frases("logica", "criterios", "criterio", "variables",
            "factores", "se basa en", "se basan en", "basandose en", "en funcion de", "teniendo en cuenta", "a partir de",
            "segun su", "segun sus", "analiza", "analizamos", "analizando", "puntuacion");
    private static final List<List<String>> CONSECUENCIAS_DECISION = frases("consecuencias", "consecuencia", "efectos",
            "importancia", "puede suponer", "podra suponer", "puede implicar", "podra implicar", "lo que supone",
            "denegacion", "denegar", "rechazo", "aprobacion");
    /** The safeguards of Art. 22.3 RGPD. */
    private static final List<List<String>> GARANTIAS_DECISION = frases("intervencion humana", "supervision humana",
            "revision humana", "participacion humana", "punto de vista", "impugnar", "impugnarla", "impugnarlas",
            "impugnacion", "no ser objeto", "solicitar la revision", "revision por una persona");
    private static final List<List<String>> OPOSICION = frases("oponerse", "oponerte", "oposicion");
    /** Human involvement stated as a fact: the decision is then not based solely on automated processing. */
    private static final List<List<String>> CON_PERSONA = frases("intervencion humana", "supervision humana",
            "revision humana", "participacion humana", "revisadas por una persona", "revisada por una persona",
            "revision por una persona");
    /** «Puede solicitar intervención humana» is a safeguard of an automated decision, not human involvement. */
    private static final List<List<String>> DERECHO_A_PEDIR = frases("derecho", "derechos", "solicitar", "pedir",
            "obtener", "puede", "podra", "pueden", "podran");
    /** Legal or similarly significant effects (Art. 22.1 RGPD), as far as words can tell. */
    private static final List<List<String>> EFECTOS_SIGNIFICATIVOS = frases("efectos juridicos", "efecto juridico",
            "afecte significativamente", "afecten significativamente", "afecta significativamente", "de modo similar",
            "credito", "creditos", "prestamo", "prestamos", "financiacion", "hipoteca", "solvencia", "contratacion",
            "empleo", "puesto de trabajo", "seleccion de personal", "candidatura", "candidaturas", "candidato",
            "candidatos", "seguro", "poliza", "admision", "beca", "becas", "subvencion", "prestacion social",
            "cancelacion de la cuenta", "bloqueo de la cuenta");
    private static final List<List<String>> RESULTADO_ADVERSO = frases("rechaza", "rechazan", "rechazada", "rechazadas",
            "rechazara", "deniega", "deniegan", "denegada", "denegadas", "denegara", "excluye", "excluyen");
    private static final List<List<String>> SOLICITUDES = frases("solicitud", "solicitudes", "peticion", "peticiones");
    /** «Elaboramos perfiles para decidir si concedemos un crédito»: profiling that feeds a decision. */
    private static final List<List<String>> DECIDIR = frases("decidir", "decide", "deciden", "conceder", "concedemos",
            "denegar", "denegamos", "aprobar", "aprobamos", "rechazar", "rechazamos");
    private static final List<List<String>> PARA_QUE = frases("para", "con el fin de", "con la finalidad de",
            "con el objeto de");
    private static final List<List<String>> FIN_COMERCIAL = frases("publicidad", "publicitarios", "publicitarias",
            "ofertas", "promociones", "comercial", "comerciales", "marketing", "mercadotecnia", "anuncios");
    private static final List<List<String>> SIN_RECURSO = frases("sin posibilidad de recurso", "sin posibilidad de impugnar",
            "sin posibilidad de impugnarla", "sin posibilidad de revision", "sin posibilidad de reclamar", "no cabe recurso",
            "no admite recurso", "no admiten recurso", "no es posible solicitar la revision");

    /** Positions where the clause speaks of an automated decision or of profiling. */
    private static List<Integer> anclasDecision(Clausula c) {
        List<Integer> anclas = anclasSoloDecision(c);
        PERFILES.forEach(f -> anclas.addAll(posiciones(c, f)));
        return anclas;
    }

    /** Positions of an automated decision, profiling aside. */
    private static List<Integer> anclasSoloDecision(Clausula c) {
        List<Integer> anclas = new ArrayList<>();
        List<String> t = c.tokens();
        for (int p = 0; p < t.size(); p++) {
            if (DECISION.contains(t.get(p))) {
                if (cerca(t, p, CERCANIA_DECISION, AUTOMATIZACION)) anclas.add(p);
            } else if (DECIDE.contains(t.get(p))) {
                if (cerca(t, p, CERCANIA_VERBO, AUTOMATIZACION) || cerca(t, p, CERCANIA_VERBO, ALGORITMO)) anclas.add(p);
            }
        }
        return anclas;
    }

    private static boolean cerca(List<String> t, int p, int distancia, Set<String> palabras) {
        for (int q = Math.max(0, p - distancia); q < Math.min(t.size(), p + distancia + 1); q++) {
            if (palabras.contains(t.get(q))) return true;
        }
        return false;
    }

    /** Affirmed, and not «sin intervención humana», which describes the decision instead of a safeguard. */
    private static boolean aspectoAfirmado(Clausula c, List<List<String>> frases) {
        List<String> t = c.tokens();
        return frases.stream().anyMatch(f -> posiciones(c, f).stream()
                .anyMatch(p -> !negada(t, p) && !(p > 0 && t.get(p - 1).equals("sin"))));
    }

    private static boolean negadaSinAfirmar(Clausula c, List<List<String>> frases) {
        return algunaNegada(c, frases) && !algunaAfirmada(c, frases);
    }

    /**
     * Art. 22 RGPD only covers decisions based solely on automated processing with legal or similarly significant
     * effects: for them Art. 13.2.f asks for the logic and the consequences. Ordinary profiling only needs its purpose
     * and, when it serves direct marketing, the right to object (Art. 21 RGPD). Each disclosure is judged on its own.
     */
    private static Evaluacion decisionesAutomatizadas(List<Clausula> cs, String tipo) {
        boolean oposicionEnDocumento = cs.stream().anyMatch(c -> algunaAfirmada(c, OPOSICION));
        Clausula noDecide = null;
        Clausula conPersona = null;
        Clausula problema = null;
        Clausula suficiente = null;
        Clausula insuficiente = null;
        String detalleProblema = null;
        String detalleSuficiente = null;
        String detalleInsuficiente = null;
        for (int i = 0; i < cs.size(); i++) {
            Clausula c = cs.get(i);
            List<Integer> anclas = anclasDecision(c);
            if (anclas.isEmpty()) continue;
            List<List<String>> garantias = new ArrayList<>(GARANTIAS_DECISION);
            garantias.addAll(OPOSICION);
            // In «No podrá oponerse a la elaboración de perfiles» the negation belongs to the safeguard, not to profiling.
            boolean salvaguardaNegada = negadaSinAfirmar(c, garantias) || alguna(c, SIN_RECURSO);
            if (!salvaguardaNegada && anclas.stream().allMatch(p -> negada(c.tokens(), p, ALCANCE_NEGACION_LARGO))) {
                if (noDecide == null) noDecide = c;
                continue;
            }
            // «Puede oponerse a la elaboración de perfiles» states the right to object (counted above), not a new activity.
            if (anclasSoloDecision(c).isEmpty() && algunaAfirmada(c, OPOSICION)) continue;
            if (aspectoAfirmado(c, CON_PERSONA) && !alguna(c, DERECHO_A_PEDIR)) {
                if (conPersona == null) conPersona = c;
                continue;
            }
            // The next sentence often completes it: «… para enviarle ofertas. Puede oponerse en cualquier momento.»
            Clausula siguiente = i + 1 < cs.size() && anclasDecision(cs.get(i + 1)).isEmpty() ? cs.get(i + 1) : null;
            Clausula ambito = siguiente == null ? c : unir(c, siguiente);
            boolean completa = siguiente != null && (aspectoAfirmado(siguiente, LOGICA_DECISION)
                    || aspectoAfirmado(siguiente, CONSECUENCIAS_DECISION) || aspectoAfirmado(siguiente, GARANTIAS_DECISION)
                    || aspectoAfirmado(siguiente, OPOSICION));
            Clausula evidencia = completa ? ambito : c;
            boolean garantiaNegada = salvaguardaNegada
                    || (siguiente != null && (negadaSinAfirmar(siguiente, garantias) || alguna(siguiente, SIN_RECURSO)));
            boolean significativa = alguna(ambito, EFECTOS_SIGNIFICATIVOS)
                    || (alguna(ambito, RESULTADO_ADVERSO) && alguna(ambito, SOLICITUDES));
            boolean decide = !anclasSoloDecision(c).isEmpty() || (significativa && alguna(c, DECIDIR));
            String motivo = null;
            String carencia = null;
            String completo = null;
            if (decide) {
                boolean logica = aspectoAfirmado(ambito, LOGICA_DECISION);
                boolean consecuencias = aspectoAfirmado(ambito, CONSECUENCIAS_DECISION);
                List<String> falta = new ArrayList<>();
                if (!logica) falta.add("la lógica aplicada");
                if (!consecuencias) falta.add("sus consecuencias");
                if (significativa && garantiaNegada) {
                    motivo = "El texto impide impugnar o pedir la revisión de decisiones automatizadas con efectos "
                            + "significativos, lo que puede ser contrario al Art. 22.3 RGPD.";
                } else if (significativa && falta.isEmpty()) {
                    completo = "Se informa de decisiones automatizadas con efectos significativos, de su lógica y de sus "
                            + "consecuencias (Arts. 13.2.f y 22 RGPD)."
                            + (aspectoAfirmado(ambito, GARANTIAS_DECISION) ? "" : " No se describen las garantías del Art. "
                            + "22.3 RGPD (intervención humana, expresar su punto de vista e impugnar la decisión), exigibles "
                            + "cuando la decisión se basa en un contrato o en el consentimiento explícito.")
                            + " El texto no permite comprobar cómo se adoptan las decisiones.";
                } else if (significativa) {
                    carencia = "Se describen decisiones automatizadas con efectos significativos sin indicar "
                            + String.join(" ni ", falta) + " (Art. 13.2.f RGPD).";
                } else if (falta.isEmpty()) {
                    completo = "Se informa de decisiones automatizadas, de su lógica y de sus consecuencias. El texto no "
                            + "indica si producen efectos jurídicos o significativos (Art. 22 RGPD) ni permite comprobar "
                            + "cómo se adoptan.";
                } else {
                    carencia = "Se alude a decisiones automatizadas sin indicar si producen efectos jurídicos o "
                            + "significativos (Art. 22 RGPD) ni " + String.join(" ni ", falta) + "."
                            + (garantiaNegada ? " El texto limita además la posibilidad de impugnarlas; si producen esos "
                            + "efectos, el Art. 22.3 RGPD lo exige." : "");
                }
            } else {
                boolean finalidad = alguna(ambito, PARA_QUE) || aspectoAfirmado(ambito, CONSECUENCIAS_DECISION);
                boolean comercial = alguna(ambito, FIN_COMERCIAL);
                if (negadaSinAfirmar(c, OPOSICION) || (siguiente != null && negadaSinAfirmar(siguiente, OPOSICION))) {
                    motivo = "El texto impide oponerse a la elaboración de perfiles; el Art. 21 RGPD reconoce ese derecho, "
                            + "sin excepciones cuando los perfiles sirven a la mercadotecnia directa (Arts. 21.2 y 21.3).";
                } else if (!finalidad) {
                    carencia = "Se alude a la elaboración de perfiles sin indicar su finalidad.";
                } else if (comercial && !oposicionEnDocumento) {
                    carencia = "Se elaboran perfiles con fines comerciales sin mencionar el derecho a oponerse a ellos, que "
                            + "debe presentarse de forma explícita (Arts. 21.2 y 21.4 RGPD).";
                } else {
                    completo = "Se informa de la elaboración de perfiles y de su finalidad. El texto no indica que den lugar "
                            + "a decisiones con efectos jurídicos o significativos, por lo que no se exige la información "
                            + "del Art. 22 RGPD.";
                }
            }
            if (motivo != null && problema == null) {
                problema = c;
                detalleProblema = motivo;
            } else if (carencia != null && insuficiente == null) {
                insuficiente = evidencia;
                detalleInsuficiente = carencia;
            } else if (completo != null && suficiente == null) {
                suficiente = evidencia;
                detalleSuficiente = completo;
            }
        }
        // An incomplete disclosure is not made sufficient by another, complete one.
        Clausula resuelta = insuficiente != null ? null : suficiente != null ? suficiente : conPersona != null ? conPersona
                : noDecide;
        String detalleResuelta = suficiente != null ? detalleSuficiente + contradiccion(noDecide)
                : conPersona != null ? "El texto indica que las decisiones cuentan con intervención humana, por lo que no "
                        + "las presenta como basadas únicamente en un tratamiento automatizado (Art. 22 RGPD); no permite "
                        + "comprobar que esa intervención sea real."
                : "El documento declara que no adopta decisiones automatizadas o que no elabora perfiles; el texto no "
                        + "permite comprobarlo.";
        return cerrar(tipo, resuelta, detalleResuelta, problema, detalleProblema, insuficiente,
                detalleInsuficiente + contradiccion(noDecide),
                "El documento no menciona decisiones automatizadas ni la elaboración de perfiles; el texto no permite saber "
                        + "si existen.", true);
    }

    // ---------------------------------------------------------------------------------------
    // R18 — Notificación de brechas (Arts. 33 y 34 RGPD): publishing it is no information duty, and a
    // mention of breaches never means one has happened
    // ---------------------------------------------------------------------------------------

    /** Whole phrases: «brecha digital» or «violación de estas condiciones» are not personal data breaches. */
    private static final List<List<String>> BRECHA = frases("brecha de seguridad", "brechas de seguridad", "brecha de datos",
            "brechas de datos", "violacion de seguridad", "violaciones de seguridad", "violacion de la seguridad",
            "violaciones de la seguridad", "violacion de datos", "violaciones de datos", "incidente de seguridad",
            "incidentes de seguridad", "fuga de datos", "fugas de datos", "filtracion de datos", "filtraciones de datos",
            "data breach");
    private static final List<List<String>> NOTIFICA = frases("notificar", "notificaremos", "notificara", "notificamos",
            "notifica", "notificada", "notificadas", "notificado", "notificados", "notificacion", "notificaciones",
            "notificarla", "notificarlas", "comunicar", "comunicaremos", "comunicara", "comunicaran", "comunicamos",
            "comunicarla", "comunicarlas", "informar", "informaremos", "informara", "informamos", "avisar", "avisaremos");
    private static final Map<String, List<List<String>>> DESTINOS_BRECHA = new LinkedHashMap<>();

    static {
        DESTINOS_BRECHA.put("autoridad de control", frases("aepd", "agencia espanola de proteccion de datos",
                "autoridad de control", "autoridades de control", "autoridad competente", "autoridades competentes"));
        DESTINOS_BRECHA.put("personas afectadas", frases("afectados", "afectadas", "interesados", "usuarios",
                "titulares de los datos", "le informaremos", "le notificaremos", "le comunicaremos", "le avisaremos",
                "les informaremos", "les comunicaremos"));
    }

    private static final List<List<String>> PLAZO_72_HORAS = frases("72 horas", "setenta y dos horas");
    /** Not notifying is lawful in the cases of Arts. 33.1 and 34.3 RGPD. */
    private static final List<List<String>> NOTIFICACION_LIMITADA = frases("improbable", "no constituya un riesgo",
            "no constituyan un riesgo", "no suponga un riesgo", "no supongan un riesgo", "alto riesgo", "cuando lo exija",
            "cuando proceda", "cuando sea obligatorio", "cuando la ley", "en los casos previstos", "todas las brechas",
            "todos los incidentes", "salvo", "excepto");
    /** Disclaimers of liability are R15's subject (and G04's), not a statement about notification. */
    private static final List<List<String>> EXIME = frases("no somos responsables", "no nos hacemos responsables",
            "no seremos responsables", "declinamos", "no asumimos");

    /** Destinations named, negated or not. */
    private static Set<String> destinosBrecha(Clausula c) {
        Set<String> destinos = new LinkedHashSet<>();
        DESTINOS_BRECHA.forEach((destino, frases) -> {
            if (alguna(c, frases)) destinos.add(destino);
        });
        return destinos;
    }

    /**
     * Notification to the supervisory authority (Art. 33 RGPD: within 72 hours, unless the breach is unlikely to result
     * in a risk) is not communication to the people affected (Art. 34: without undue delay, when the risk is high).
     */
    private static Evaluacion brechas(List<Clausula> cs, String tipo) {
        Clausula suficiente = null;
        Clausula problema = null;
        Clausula mencion = null;
        String detalleProblema = null;
        Set<String> destinos = new LinkedHashSet<>();
        boolean plazo72 = false;
        boolean altoRiesgo = false;
        for (int i = 0; i < cs.size(); i++) {
            Clausula c = cs.get(i);
            if (!alguna(c, BRECHA) || alguna(c, EXIME)) continue;
            Clausula evidencia = c;
            boolean afirma = algunaAfirmada(c, NOTIFICA);
            boolean niega = algunaNegada(c, NOTIFICA);
            // «Si se produce una brecha de seguridad, actuaremos con rapidez. La notificaremos a la AEPD.»
            if (!afirma && !niega && i + 1 < cs.size() && !alguna(cs.get(i + 1), BRECHA)
                    && algunaAfirmada(cs.get(i + 1), NOTIFICA)) {
                evidencia = unir(c, cs.get(i + 1));
                afirma = true;
            }
            Set<String> aqui = destinosBrecha(evidencia);
            boolean autoridad = aqui.contains("autoridad de control");
            boolean afectados = aqui.contains("personas afectadas");
            boolean riesgoAlto = alguna(evidencia, frases("alto riesgo"));
            if (niega && !afirma && !alguna(c, NOTIFICACION_LIMITADA)) {
                if (problema == null) {
                    problema = c;
                    detalleProblema = afectados && !autoridad
                            ? "El texto declara que no se comunicarán las brechas a las personas afectadas; el Art. 34 RGPD "
                                    + "obliga a hacerlo cuando entrañen un alto riesgo, salvo las excepciones de su apartado 3."
                            : autoridad ? "El texto declara que no se notificarán las brechas a la autoridad de control; el "
                                    + "Art. 33 RGPD obliga a hacerlo en 72 horas salvo que sea improbable que entrañen un riesgo."
                            : "El texto declara que no se notificarán las brechas de seguridad, sin limitarlo a los casos en "
                                    + "que los Arts. 33.1 y 34.3 RGPD lo permiten.";
                }
                continue;
            }
            if (afirma && autoridad && !afectados && riesgoAlto && problema == null) {
                // The high-risk threshold belongs to the communication to the people affected.
                problema = c;
                detalleProblema = "El texto limita la notificación a la autoridad de control a las brechas de alto riesgo; "
                        + "el Art. 33.1 RGPD exige notificarla salvo que sea improbable que entrañe un riesgo. El umbral "
                        + "del alto riesgo corresponde a la comunicación a los afectados (Art. 34).";
            }
            if (afirma || niega) {
                if (suficiente == null) {
                    suficiente = evidencia;
                    destinos.addAll(aqui);
                    plazo72 = alguna(evidencia, PLAZO_72_HORAS);
                    altoRiesgo = riesgoAlto;
                }
            } else if (mencion == null) {
                mencion = c;
            }
        }
        List<String> partes = new ArrayList<>();
        if (destinos.contains("autoridad de control")) {
            partes.add("la notificación a la autoridad de control" + (plazo72 ? " en 72 horas" : ""));
        }
        if (destinos.contains("personas afectadas")) {
            partes.add("la comunicación a las personas afectadas" + (altoRiesgo ? " cuando entrañe un alto riesgo" : ""));
        }
        String detalleSuficiente = (partes.isEmpty() ? "Se informa de la notificación de brechas de seguridad, sin indicar "
                + "a quién." : "Se informa de " + String.join(" y ", partes) + ".")
                + (plazo72 && !destinos.contains("autoridad de control") ? " El plazo de 72 horas del Art. 33 RGPD se refiere "
                + "a la notificación a la autoridad de control; la comunicación a los afectados (Art. 34) procede sin "
                + "dilación indebida cuando la brecha entraña un alto riesgo." : "")
                + " El texto no acredita que exista un procedimiento ni que se aplique.";
        return cerrar(tipo, suficiente, detalleSuficiente, problema, detalleProblema, mencion,
                "Se alude a brechas o incidentes de seguridad sin indicar si se notifican a la autoridad de control o a las "
                        + "personas afectadas. Mencionarlas no implica que se haya producido ninguna.",
                "El documento no informa sobre la notificación de brechas de seguridad. Publicarla no es una obligación de "
                        + "información, y si se produjera una brecha su notificación (Arts. 33 y 34 RGPD) no podría "
                        + "comprobarse con el texto.", true);
    }

    // ---------------------------------------------------------------------------------------
    // R19 — Comunicaciones comerciales (Arts. 21 y 22 LSSI-CE, Art. 21 RGPD): marketing is never presumed,
    // and transactional messages are not marketing
    // ---------------------------------------------------------------------------------------

    private static final List<List<String>> COMERCIAL = frases("comunicaciones comerciales", "comunicacion comercial",
            "comunicaciones publicitarias", "comunicaciones promocionales", "comunicaciones de marketing", "publicidad",
            "newsletter", "newsletters", "boletin", "boletines", "ofertas", "promociones", "informacion comercial",
            "marketing", "mercadotecnia", "prospeccion comercial", "mailing");
    /** Marketing that is a message by itself; «publicidad» or «ofertas» may be shown on the site instead. */
    private static final List<List<String>> COMERCIAL_ENVIADA = frases("comunicaciones comerciales", "comunicacion comercial",
            "comunicaciones publicitarias", "comunicaciones promocionales", "comunicaciones de marketing", "newsletter",
            "newsletters", "boletin", "boletines", "mailing");
    private static final List<List<String>> ENVIO = frases("enviar", "enviaremos", "enviamos", "enviarle", "enviarte",
            "enviarles", "enviara", "envio", "envios", "remitir", "remitiremos", "remitirle", "recibir", "reciba", "recibira",
            "recibiras", "correo", "correos", "email", "e mail", "sms", "whatsapp", "llamadas", "mensajes", "suscripcion",
            "suscribirse", "suscrito", "suscrita");
    private static final List<List<String>> COMUNICACIONES = frases("comunicaciones", "correos", "mensajes", "newsletter",
            "boletin", "suscripcion");
    private static final List<List<String>> BAJA = frases("darse de baja", "darte de baja", "dar de baja", "darle de baja",
            "solicitar la baja", "cancelar la suscripcion", "cancelar su suscripcion", "anular la suscripcion",
            "dejar de recibir", "desuscribirse", "unsubscribe", "oponerse", "oponerte", "oposicion", "revocar", "retirar",
            "lista robinson", "exclusion publicitaria");
    private static final List<List<String>> CONSENTIMIENTO_COMERCIAL = frases("consentimiento", "consiente", "consienta",
            "consentido", "autorizacion", "autorice", "autoriza", "autorizado", "autorizada", "solicitado", "solicite",
            "suscribirse", "se suscriba", "se suscribe", "se suscriben", "suscrito", "suscrita", "marque la casilla",
            "marcando la casilla");
    private static final String RELACION_PREVIA = "relación contractual previa (Art. 21.2 LSSI-CE)";
    private static final List<List<String>> CLIENTES = frases("cliente", "clientes", "contrataron", "contratado", "contratados",
            "contrato", "adquirido", "adquiridos", "compra", "compras", "relacion contractual");
    private static final List<List<String>> MEDIOS_ELECTRONICOS = frases("correo electronico", "email", "e mail", "sms",
            "whatsapp", "medios electronicos");
    private static final List<List<String>> SIN_CONSENTIMIENTO_COMERCIAL = frases("sin su consentimiento",
            "sin consentimiento", "sin su autorizacion", "sin autorizacion", "sin haberlo solicitado",
            "aunque no lo haya solicitado", "aunque no lo haya autorizado", "aunque se oponga", "aunque se haya dado de baja",
            "aunque solicite la baja");
    /** Consent tied to registration or to the terms is neither free nor specific (Arts. 7.4 RGPD, 6.3 LOPDGDD). */
    private static final List<List<String>> CONSENTIMIENTO_VINCULADO = frases("al registrarse acepta recibir",
            "al registrarte aceptas recibir", "al registrarse consiente", "al registrarte consientes",
            "al crear una cuenta acepta recibir", "al crear tu cuenta aceptas recibir", "al contratar acepta recibir",
            "al aceptar estas condiciones acepta recibir", "al aceptar estas condiciones consiente");
    private static final List<List<String>> CONDICION = frases("salvo", "excepto", "a menos que", "salvo que", "si nos",
            "si lo", "si usted", "si nos da", "si se suscribe");

    private static boolean comercial(Clausula c) {
        // Advertising cookies are R10's subject.
        if (alguna(c, COOKIES)) return false;
        return alguna(c, COMERCIAL_ENVIADA) || (alguna(c, COMERCIAL) && alguna(c, ENVIO))
                || (alguna(c, BAJA) && alguna(c, COMUNICACIONES));
    }

    /** Art. 21.2 LSSI-CE only covers similar products or services of the provider's own company. */
    private static final List<List<String>> PRODUCTOS_AJENOS = frases("de terceros", "de nuestros socios",
            "socios comerciales", "empresas colaboradoras", "entidades colaboradoras", "otras empresas", "empresas del grupo");
    private static final List<List<String>> SIMILARES = frases("similares", "analogos");
    /** Art. 21 LSSI-CE covers e-mail and equivalent electronic means, not post. */
    private static final List<List<String>> SOLO_POSTAL = frases("correo postal", "correo ordinario", "por carta", "cartas",
            "buzoneo", "folletos");

    private static boolean relacionPrevia(Clausula c) {
        return alguna(c, SIMILARES) && alguna(c, CLIENTES) && !alguna(c, PRODUCTOS_AJENOS);
    }

    /** Customers are named, but without the conditions of Art. 21.2 LSSI-CE. */
    private static boolean clientesSinCondiciones(Clausula c) {
        return alguna(c, frases("cliente", "clientes")) && !relacionPrevia(c);
    }

    private static Evaluacion comunicacionesComerciales(List<Clausula> cs, String tipo) {
        Clausula primera = null;
        Clausula conBase = null;
        Clausula problema = null;
        Clausula noEnvia = null;
        Set<String> bases = new LinkedHashSet<>();
        boolean baja = false;
        boolean electronico = false;
        boolean postal = false;
        boolean clientesSinCondiciones = false;
        for (int i = 0; i < cs.size(); i++) {
            Clausula c = cs.get(i);
            if (!comercial(c)) continue;
            boolean bajaNegada = algunaNegada(c, BAJA) && !algunaAfirmada(c, BAJA);
            if (alguna(c, CONSENTIMIENTO_VINCULADO) || bajaNegada
                    || (algunaAfirmadaLejos(c, SIN_CONSENTIMIENTO_COMERCIAL) && !relacionPrevia(c))) {
                if (problema == null) problema = c;
                continue;
            }
            // «No le enviaremos publicidad sin su consentimiento» is a condition, not an absence.
            boolean condicionada = alguna(c, SIN_CONSENTIMIENTO_COMERCIAL) || alguna(c, CONDICION);
            if (algunaNegada(c, ENVIO) && !algunaAfirmada(c, ENVIO) && !condicionada) {
                if (noEnvia == null) noEnvia = c;
                continue;
            }
            if (primera == null) primera = c;
            Set<String> aqui = new LinkedHashSet<>();
            if (alguna(c, CONSENTIMIENTO_COMERCIAL)) aqui.add("consentimiento");
            if (relacionPrevia(c)) aqui.add(RELACION_PREVIA);
            if (alguna(c, frases("interes legitimo"))) aqui.add("interés legítimo");
            if ((aqui.contains("consentimiento") || aqui.contains(RELACION_PREVIA)) && conBase == null) conBase = c;
            bases.addAll(aqui);
            electronico |= alguna(c, MEDIOS_ELECTRONICOS);
            postal |= alguna(c, SOLO_POSTAL);
            clientesSinCondiciones |= clientesSinCondiciones(c);
            baja |= algunaAfirmada(c, BAJA);
            // The way to unsubscribe often follows: «… ofertas de productos similares; pueden oponerse en cualquier momento.»
            Clausula siguiente = i + 1 < cs.size() && !comercial(cs.get(i + 1)) ? cs.get(i + 1) : null;
            if (siguiente != null) {
                if (problema == null && algunaNegada(siguiente, BAJA) && !algunaAfirmada(siguiente, BAJA)) problema = siguiente;
                baja |= algunaAfirmada(siguiente, BAJA);
            }
        }
        // Legitimate interest is a GDPR basis; by e-mail or equivalent means Art. 21 LSSI-CE still needs prior request or
        // express authorisation, or the prior customer relationship of Art. 21.2. Only post escapes it.
        boolean interesPostal = bases.contains("interés legítimo") && postal && !electronico;
        boolean baseValida = bases.contains("consentimiento") || bases.contains(RELACION_PREVIA) || interesPostal;
        boolean completa = baseValida && baja;
        String detalleSuficiente = primera != null
                ? "Se informa de las comunicaciones comerciales y de cómo darse de baja. Se basan en: "
                        + String.join(", ", bases) + "."
                        + (interesPostal ? " Por correo postal no se aplica el Art. 21 LSSI-CE; el interés legítimo exige "
                        + "respetar el derecho de oposición (Art. 21.2 RGPD)." : "")
                        + " El texto no permite comprobar que los envíos se ajusten a lo declarado." + contradiccion(noEnvia)
                : "El documento declara que no envía comunicaciones comerciales; el texto no permite comprobarlo.";
        String sinBaja = baja ? "" : " Tampoco se indica cómo darse de baja.";
        String detalleInsuficiente;
        if (!baseValida && bases.contains("interés legítimo")) {
            detalleInsuficiente = "Se invoca el interés legítimo para las comunicaciones comerciales. Es una base jurídica del "
                    + "RGPD que no sustituye el Art. 21 LSSI-CE: por correo electrónico u otro medio electrónico equivalente "
                    + "se necesita solicitud o autorización expresa previa, salvo relación contractual previa y productos o "
                    + "servicios similares de la propia empresa (Art. 21.2)."
                    + (electronico ? "" : " El texto no indica el canal de envío.") + sinBaja;
        } else if (!baseValida && clientesSinCondiciones) {
            detalleInsuficiente = "Se dirigen comunicaciones comerciales a clientes sin limitarlas a productos o servicios "
                    + "similares de la propia empresa a los contratados, condición de la excepción del Art. 21.2 LSSI-CE."
                    + sinBaja;
        } else {
            detalleInsuficiente = "Se informa de comunicaciones comerciales sin indicar " + (!baseValida && !baja
                    ? "en qué se basan ni cómo darse de baja" : !baseValida
                    ? "en qué se basan (consentimiento previo o relación contractual previa)" : "cómo darse de baja") + ".";
        }
        return cerrar(tipo, completa ? (conBase != null ? conBase : primera) : primera == null ? noEnvia : null,
                detalleSuficiente, problema,
                "El texto describe comunicaciones comerciales sin consentimiento, sin posibilidad de darse de baja o con un "
                        + "consentimiento ligado al registro, lo que puede ser contrario a los Arts. 21 y 22 LSSI-CE y al "
                        + "Art. 21 RGPD.", primera, detalleInsuficiente + contradiccion(noEnvia),
                "El documento no menciona comunicaciones comerciales; el texto no permite saber si se envían.", true);
    }
}
