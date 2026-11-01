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

/**
 * Context-aware evaluation of the privacy information rules R03, R05, R06, R07 and R08 and of the
 * global risks G01 and G02, part of {@link MotorAnalisisLegal}. It reads the clean text as clauses
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

    static final Set<String> IDS = Set.of("R03", "R05", "R06", "R07", "R08");
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
        if (problema != null) {
            return new Evaluacion(EstadoEvidencia.POSIBLE_INCIDENCIA, suficiente != null, true, cita(problema), detalleProblema);
        }
        if (suficiente != null) {
            return new Evaluacion(EstadoEvidencia.SUFICIENTE, true, false, cita(suficiente), detalleSuficiente);
        }
        if (!aplicaInformacionRgpd(tipo)) {
            return new Evaluacion(EstadoEvidencia.NO_APLICABLE, false, false, null, "No se exige en un documento de tipo «"
                    + tipo.trim() + "»: esta información corresponde a la política de privacidad.");
        }
        if (mencion != null) {
            return new Evaluacion(EstadoEvidencia.INSUFICIENTE, false, false, cita(mencion), detalleMencion);
        }
        return new Evaluacion(ausenciaNoVerificable ? EstadoEvidencia.NO_VERIFICABLE : EstadoEvidencia.POSIBLE_INCIDENCIA,
                false, false, null, detalleAusencia);
    }

    static Evaluacion evaluar(String ruleId, List<Clausula> clausulas, String tipo) {
        return switch (ruleId) {
            case "R03" -> delegado(clausulas, tipo);
            case "R05" -> baseJuridica(clausulas, tipo);
            case "R06" -> derechos(clausulas, tipo);
            case "R07" -> conservacion(clausulas, tipo);
            case "R08" -> destinatarios(clausulas, tipo);
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

    private static boolean contacto(Clausula c) {
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
            } else if (contacto(c)) {
                if (conContacto == null) conContacto = c;
            } else if (i + 1 < cs.size() && c.original().endsWith(":") && contacto(cs.get(i + 1))) {
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
}
