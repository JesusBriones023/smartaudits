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
 * Context-aware evaluation of rules R02, R03, R05, R06, R07, R08, R10, R12 and R15 and of the global
 * risks G01, G02 and G03, part of {@link MotorAnalisisLegal}. It reads the clean text as clauses
 * (sentences, paragraphs and list items) and matches whole words with their negation, so that:
 *
 * - a single keyword is never enough: mentioning an obligation is told apart from giving its content;
 * - "no cedemos sus datos" or "no los conservamos indefinidamente" are not read as the opposite;
 * - information that the text alone cannot settle is reported as such, not as a breach;
 * - Art. 13 RGPD information is not demanded from documents whose subject is something else.
 *
 * Every evidence is a clause of the analysed text, with its whitespace collapsed.
 */
final class ReglasContextuales {

    private ReglasContextuales() {}

    static final Set<String> IDS = Set.of("R02", "R03", "R05", "R06", "R07", "R08", "R10", "R12", "R15");
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

    static final Map<String, Textos> TEXTOS = Map.of(
            "R02", new Textos("Medios de contacto del responsable mencionados sin concretarlos",
                    "Se alude al contacto sin un medio concreto (correo, teléfono, dirección o formulario), o aparece un dato "
                            + "de contacto que no se presenta como tal (Art. 13.1.a RGPD)."),
            "R10", new Textos("Información sobre cookies parcial o meramente mencionada",
                    "Se mencionan cookies sin explicar lo suficiente sus tipos, finalidades, duración y cómo gestionarlas o "
                            + "rechazarlas (Art. 22.2 LSSI-CE)."),
            "R12", new Textos("Consentimiento mencionado sin describir cómo se presta y se retira",
                    "Se menciona el consentimiento sin explicar cómo se presta y cómo puede retirarse (Art. 7 RGPD)."),
            "R15", new Textos("Medidas de seguridad mencionadas de forma genérica",
                    "El documento declara aplicar medidas de seguridad sin concretar ninguna."),
            "R03", new Textos("Delegado de Protección de Datos mencionado sin datos de contacto",
                    "Se menciona un Delegado de Protección de Datos, pero no un medio para contactar con él (Art. 13.1.b RGPD)."),
            "R05", new Textos("Base legal del tratamiento mencionada sin identificarla",
                    "Se alude a la legitimación del tratamiento sin indicar cuál de las bases del Art. 6.1 RGPD se aplica."),
            "R06", new Textos("Derechos del interesado informados de forma incompleta",
                    "Se informa de algunos derechos del interesado, pero no de los suficientes (Art. 13.2.b RGPD)."),
            "R07", new Textos("Conservación de los datos mencionada sin plazo ni criterio",
                    "Se alude a la conservación de los datos sin un plazo concreto ni los criterios para determinarlo (Art. 13.2.a RGPD)."),
            "R08", new Textos("Destinatarios de los datos mencionados sin identificarlos",
                    "Se indica que los datos pueden comunicarse a terceros sin identificar a los destinatarios ni sus categorías (Art. 13.1.e RGPD)."));

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
        for (int k = posicion - 1; k >= 0 && k >= posicion - ALCANCE_NEGACION; k--) {
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
            case "R02" -> contacto(clausulas, tipo);
            case "R03" -> delegado(clausulas, tipo);
            case "R05" -> baseJuridica(clausulas, tipo);
            case "R06" -> derechos(clausulas, tipo);
            case "R07" -> conservacion(clausulas, tipo);
            case "R08" -> destinatarios(clausulas, tipo);
            case "R10" -> cookies(clausulas, tipo);
            case "R12" -> consentimiento(clausulas, tipo);
            case "R15" -> seguridad(clausulas, tipo);
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
                "Se declara una conservación sin límite temporal, contraria al Art. 5.1.e RGPD.", mencion,
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
                problema, "El texto describe un consentimiento tácito, por defecto o no revocable, contrario al Art. 7 RGPD.",
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
}
