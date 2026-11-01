package com.smartaudits.service.html;

import com.smartaudits.model.dto.DeteccionEnlacesResponse;
import com.smartaudits.model.dto.DeteccionEnlacesResponse.EnlaceLegalDetectado;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Descubre, por análisis estático, enlaces del HTML que parecen apuntar a documentos
 * legales. Nunca descarga, sigue ni abre ninguna URL: solo lee los atributos href.
 *
 * - Solo se aceptan destinos http(s) o rutas relativas; javascript:, data:, file:,
 *   mailto: y cualquier otro esquema se descartan, igual que las URL con credenciales.
 * - Las etiquetas &lt;base&gt; se ignoran: las rutas relativas solo se resuelven contra
 *   la URL de referencia que da el usuario y, sin ella, se conservan tal cual.
 * - Un enlace es un candidato, no un documento verificado.
 */
public final class DetectorEnlacesLegales {

    public static final int MAX_ENLACES_PROCESADOS = 2_000;
    public static final int MAX_RESULTADOS = 50;
    static final int MAX_HREF_LENGTH = 2_048;
    static final int MAX_TEXTO_LENGTH = 200;

    private static final Pattern ESQUEMA = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:");
    private static final Pattern NO_ALFANUMERICO = Pattern.compile("[^a-z0-9]+");
    private static final Pattern MARCAS = Pattern.compile("\\p{M}+");

    public DeteccionEnlacesResponse detectar(String html, String urlReferencia) {
        URI base = referencia(urlReferencia);
        List<Element> anclas = Jsoup.parse(html == null ? "" : html).select("a[href]");
        int procesados = Math.min(anclas.size(), MAX_ENLACES_PROCESADOS);
        boolean limite = anclas.size() > MAX_ENLACES_PROCESADOS;
        int descartados = 0;
        Set<String> vistos = new LinkedHashSet<>();
        List<EnlaceLegalDetectado> detectados = new ArrayList<>();

        for (Element ancla : anclas.subList(0, procesados)) {
            Destino destino = destino(ancla.attr("href"), base);
            if (destino == null) {
                descartados++;
                continue;
            }
            String texto = recortar(ancla.text());
            for (Clasificacion c : clasificar(ancla, texto, destino.ruta)) {
                if (!vistos.add(c.categoria + " " + destino.url)) {
                    continue;
                }
                if (detectados.size() == MAX_RESULTADOS) {
                    limite = true;
                    break;
                }
                detectados.add(new EnlaceLegalDetectado(c.categoria, texto, destino.url, destino.relativa,
                        destino.externo, contexto(ancla), c.senal));
            }
        }
        Set<CategoriaDocumentoLegal> ausentes = EnumSet.allOf(CategoriaDocumentoLegal.class);
        detectados.forEach(d -> ausentes.remove(d.categoria()));
        return new DeteccionEnlacesResponse(detectados, List.copyOf(ausentes), procesados, descartados, limite,
                base != null);
    }

    private record Destino(String url, String ruta, boolean relativa, Boolean externo) {}

    private record Clasificacion(CategoriaDocumentoLegal categoria, String senal) {}

    /** La URL de referencia ya está validada (http/https) por el DTO; aquí solo se interpreta. */
    private static URI referencia(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(codificar(url.trim()));
            return esHttp(uri) && uri.getHost() != null && uri.getRawUserInfo() == null ? uri : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** Destino seguro y normalizado del href, o null si no es un enlace http(s) aceptable. */
    private static Destino destino(String href, URI base) {
        String limpio = href.replaceAll("[\\t\\n\\r]", "").strip();
        if (limpio.isEmpty() || limpio.startsWith("#") || limpio.length() > MAX_HREF_LENGTH) {
            return null;
        }
        boolean conEsquema = ESQUEMA.matcher(limpio).find();
        String minusculas = limpio.toLowerCase(Locale.ROOT);
        if (conEsquema && !minusculas.startsWith("http:") && !minusculas.startsWith("https:")) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(codificar(limpio));
        } catch (URISyntaxException e) {
            return null;
        }
        boolean relativa = !uri.isAbsolute();
        if (relativa && base != null) {
            uri = base.resolve(uri);
            relativa = false;
        }
        if (!relativa && (!esHttp(uri) || uri.getHost() == null)) {
            return null;
        }
        if (uri.getRawUserInfo() != null) {
            return null;
        }
        String url = sinFragmento(uri, relativa);
        Boolean externo = relativa || base == null ? null
                : !uri.getHost().equalsIgnoreCase(base.getHost());
        String ruta = uri.getRawPath() == null ? "" : uri.getRawPath();
        return new Destino(url, ruta, relativa, externo);
    }

    private static boolean esHttp(URI uri) {
        String esquema = uri.getScheme();
        return esquema != null && (esquema.equalsIgnoreCase("http") || esquema.equalsIgnoreCase("https"));
    }

    private static String sinFragmento(URI uri, boolean relativa) {
        try {
            if (relativa) {
                return new URI(null, null, uri.getRawPath() == null ? null : uri.getPath(), uri.getQuery(), null)
                        .toASCIIString().replaceFirst("^$", "./");
            }
            int puerto = uri.getPort();
            boolean porDefecto = (puerto == 80 && "http".equalsIgnoreCase(uri.getScheme()))
                    || (puerto == 443 && "https".equalsIgnoreCase(uri.getScheme()));
            return new URI(uri.getScheme().toLowerCase(Locale.ROOT), null, uri.getHost().toLowerCase(Locale.ROOT),
                    porDefecto ? -1 : puerto, uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath(),
                    uri.getQuery(), null).toASCIIString();
        } catch (URISyntaxException e) {
            return uri.toASCIIString();
        }
    }

    /** Codifica en UTF-8 los caracteres que java.net.URI no admite (espacios, no ASCII). */
    private static String codificar(String valor) {
        StringBuilder salida = new StringBuilder();
        for (byte b : valor.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (c <= 0x20 || c >= 0x7F || c == '"' || c == '<' || c == '>' || c == '`' || c == '{' || c == '}'
                    || c == '|' || c == '^' || c == '\\') {
                salida.append('%').append(String.format("%02X", c));
            } else {
                salida.append((char) c);
            }
        }
        return salida.toString();
    }

    /**
     * Categorías del enlace: primero por su texto visible; si no dice nada, por atributos
     * descriptivos (title, aria-label, rel) y, en último lugar, por la ruta.
     */
    private static List<Clasificacion> clasificar(Element ancla, String texto, String ruta) {
        Set<CategoriaDocumentoLegal> porTexto = coincidencias(normalizar(texto), false);
        if (!porTexto.isEmpty()) {
            return lista(porTexto, "texto");
        }
        String atributos = normalizar(ancla.attr("title") + " " + ancla.attr("aria-label"));
        Set<CategoriaDocumentoLegal> porAtributo = coincidencias(atributos, false);
        Set<String> rel = Set.of(ancla.attr("rel").toLowerCase(Locale.ROOT).split("\\s+"));
        for (CategoriaDocumentoLegal categoria : CategoriaDocumentoLegal.values()) {
            if (categoria.rel().stream().anyMatch(rel::contains)) {
                porAtributo.add(categoria);
            }
        }
        if (!porAtributo.isEmpty()) {
            return lista(porAtributo, "atributo");
        }
        return lista(coincidencias(normalizar(decodificar(ruta)), true), "ruta");
    }

    private static Set<CategoriaDocumentoLegal> coincidencias(String normalizado, boolean ruta) {
        Set<CategoriaDocumentoLegal> encontradas = EnumSet.noneOf(CategoriaDocumentoLegal.class);
        if (normalizado.isBlank()) {
            return encontradas;
        }
        String conBordes = " " + normalizado + " ";
        for (CategoriaDocumentoLegal categoria : CategoriaDocumentoLegal.values()) {
            List<String> expresiones = ruta ? categoria.expresionesRuta() : categoria.expresiones();
            if (expresiones.stream().anyMatch(e -> conBordes.contains(" " + e + " "))) {
                encontradas.add(categoria);
            }
        }
        // Un enlace genérico "Legal" solo se toma como aviso legal si no dice nada más.
        if (!ruta && normalizado.equals("legal")) {
            encontradas.add(CategoriaDocumentoLegal.AVISO_LEGAL);
        }
        // Las condiciones de uso o de compra son más concretas que unos términos genéricos.
        if (encontradas.contains(CategoriaDocumentoLegal.CONDICIONES_USO)
                || encontradas.contains(CategoriaDocumentoLegal.CONDICIONES_COMPRA)) {
            encontradas.remove(CategoriaDocumentoLegal.TERMINOS_CONDICIONES);
        }
        return encontradas;
    }

    private static List<Clasificacion> lista(Set<CategoriaDocumentoLegal> categorias, String senal) {
        return categorias.stream().map(c -> new Clasificacion(c, senal)).toList();
    }

    /** Minúsculas sin tildes, con todo lo que no sea letra o dígito convertido en espacio. */
    static String normalizar(String valor) {
        String sinTildes = MARCAS.matcher(Normalizer.normalize(valor, Normalizer.Form.NFD)).replaceAll("");
        return NO_ALFANUMERICO.matcher(sinTildes.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }

    private static String decodificar(String ruta) {
        try {
            return java.net.URLDecoder.decode(ruta.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return ruta;
        }
    }

    private static String recortar(String texto) {
        String limpio = texto.strip();
        return limpio.length() <= MAX_TEXTO_LENGTH ? limpio : limpio.substring(0, MAX_TEXTO_LENGTH - 1) + "…";
    }

    /** Zona de la página donde aparece el enlace: pie, navegación, cabecera, lateral o cuerpo. */
    private static String contexto(Element ancla) {
        for (Element e = ancla.parent(); e != null; e = e.parent()) {
            String etiqueta = e.normalName();
            String rol = e.attr("role").toLowerCase(Locale.ROOT);
            if (etiqueta.equals("footer") || rol.equals("contentinfo")) {
                return "footer";
            }
            if (etiqueta.equals("nav") || rol.equals("navigation")) {
                return "nav";
            }
            if (etiqueta.equals("header") || rol.equals("banner")) {
                return "header";
            }
            if (etiqueta.equals("aside")) {
                return "aside";
            }
        }
        return "cuerpo";
    }
}
