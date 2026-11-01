package com.smartaudits.service.html;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

import java.util.Set;

/**
 * Convierte HTML aportado por el usuario en texto legible para el motor legal.
 *
 * jsoup solo analiza la cadena recibida: sin URL base, sin descargar recursos y sin
 * ejecutar nada. Se descartan scripts, estilos, contenido embebido, controles de
 * formulario, navegación y elementos ocultos; los atributos nunca pasan al texto.
 * Encabezados y párrafos quedan como bloques separados por una línea en blanco y
 * cada elemento de lista como una línea que empieza por "- ".
 */
public final class ExtractorTextoHtml {

    /** Tamaño máximo del HTML recibido, antes de extraer el texto. */
    public static final int MAX_HTML_LENGTH = 500_000;

    private static final String DESCARTADOS = String.join(", ",
            "head", "script", "style", "noscript", "template", "iframe", "frame", "frameset",
            "object", "embed", "applet", "svg", "math", "canvas", "video", "audio", "picture",
            "map", "button", "select", "option", "input", "textarea", "nav",
            "[hidden]", "[aria-hidden=true]", "[style~=(?i)display\\s*:\\s*none]");

    /** Marca interna de salto de párrafo (separador de registro ASCII, eliminado del texto). */
    private static final char PARRAFO = (char) 0x1E;

    /** Bloques que se separan con una línea en blanco. */
    private static final Set<String> PARRAFOS = Set.of(
            "p", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre", "table",
            "ul", "ol", "dl", "section", "article", "header", "footer", "main", "aside",
            "address", "figure", "details", "fieldset");

    /** Bloques que empiezan en una línea nueva. */
    private static final Set<String> LINEAS = Set.of(
            "div", "li", "tr", "dt", "dd", "caption", "figcaption", "summary", "hr", "form");

    public String extraer(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Document documento = Jsoup.parse(html);
        documento.select(DESCARTADOS).remove();
        Recorrido recorrido = new Recorrido();
        NodeTraversor.traverse(recorrido, documento.body());
        return normalizar(recorrido.salida.toString());
    }

    private static final class Recorrido implements NodeVisitor {
        private final StringBuilder salida = new StringBuilder();

        @Override
        public void head(Node nodo, int profundidad) {
            if (nodo instanceof TextNode texto) {
                salida.append(texto.text().replace(PARRAFO, ' '));
            } else if (nodo instanceof Element elemento) {
                String etiqueta = elemento.normalName();
                if (etiqueta.equals("br")) {
                    salida.append('\n');
                } else if (etiqueta.equals("td") || etiqueta.equals("th")) {
                    salida.append(' ');
                } else if (PARRAFOS.contains(etiqueta)) {
                    salida.append(PARRAFO);
                } else if (LINEAS.contains(etiqueta)) {
                    salida.append('\n');
                    if (etiqueta.equals("li")) {
                        salida.append("- ");
                    }
                }
            }
        }

        @Override
        public void tail(Node nodo, int profundidad) {
            if (nodo instanceof Element elemento) {
                String etiqueta = elemento.normalName();
                if (PARRAFOS.contains(etiqueta)) {
                    salida.append(PARRAFO);
                } else if (LINEAS.contains(etiqueta)) {
                    salida.append('\n');
                }
            }
        }
    }

    /**
     * Espacios simples dentro de cada línea, sin líneas ni viñetas vacías. Una serie de
     * saltos se reduce al más fuerte: salto de párrafo (línea en blanco) o de línea.
     */
    private static String normalizar(String bruto) {
        StringBuilder resultado = new StringBuilder();
        StringBuilder linea = new StringBuilder();
        int salto = 0;
        for (int i = 0; i <= bruto.length(); i++) {
            char c = i < bruto.length() ? bruto.charAt(i) : PARRAFO;
            if (c != '\n' && c != PARRAFO) {
                linea.append(c == (char) 0xA0 ? ' ' : c);
                continue;
            }
            String limpia = linea.toString().replaceAll("\\s+", " ").trim();
            linea.setLength(0);
            if (!limpia.isEmpty() && !limpia.equals("-")) {
                if (resultado.length() > 0) {
                    resultado.append(salto == 2 ? "\n\n" : "\n");
                }
                resultado.append(limpia);
                salto = 0;
            }
            salto = Math.max(salto, c == PARRAFO ? 2 : 1);
        }
        return resultado.toString();
    }
}
