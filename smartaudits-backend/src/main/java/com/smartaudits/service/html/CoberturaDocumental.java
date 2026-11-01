package com.smartaudits.service.html;

import com.smartaudits.model.dto.AuditoriaConjuntaResponse.CoberturaCategoria;
import com.smartaudits.model.dto.AuditoriaConjuntaResponse.Contradiccion;
import com.smartaudits.model.dto.AuditoriaConjuntaResponse.DocumentoCobertura;
import com.smartaudits.model.dto.AuditoriaConjuntaResponse.DocumentoRef;
import com.smartaudits.model.dto.AuditoriaConjuntaResponse.EnlaceCobertura;
import com.smartaudits.model.dto.AuditoriaConjuntaResponse.EstadoCobertura;
import com.smartaudits.model.dto.AuditoriaConjuntaResponse.MatrizCobertura;
import com.smartaudits.model.dto.AuditoriaConjuntaResponse.OrigenEnlace;
import com.smartaudits.model.dto.AuditoriaResponse;
import com.smartaudits.model.dto.CoberturaDocumento;
import com.smartaudits.model.dto.CoberturaDocumento.Senal;
import com.smartaudits.model.dto.DeteccionEnlacesResponse;
import com.smartaudits.model.dto.DeteccionEnlacesResponse.EnlaceLegalDetectado;
import org.jsoup.Jsoup;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Document coverage of a joint audit: which legal categories the supplied documents are,
 * and which ones only appear as links. Static analysis of the supplied HTML only: nothing is
 * fetched, followed or executed. It is not a compliance engine, and not finding a category
 * says nothing about whether the website publishes it.
 */
public final class CoberturaDocumental {

    static final int MAX_SENALES = 14;
    static final int MAX_EVIDENCIA_LENGTH = 200;
    static final int MAX_ENLACES_POR_CATEGORIA = 5;
    static final int MAX_URL_ENLACE = 512;

    static final String ORIGEN_DECLARADA = "Categoría declarada por el usuario";
    static final String ORIGEN_HISTORICO = "Categoría declarada (auditoría anterior sin evidencias conservadas)";

    private CoberturaDocumental() {}

    /** Evidence of what the supplied document is. Navigation, hidden content and links never classify it. */
    public static CoberturaDocumento clasificar(String html, String url, String declarada) {
        var documento = Jsoup.parse(html == null ? "" : html);
        List<Senal> senales = new ArrayList<>();
        agregar(senales, documento.title(), "Título HTML", false);
        documento.select("script,style,noscript,template,iframe,object,svg,nav,footer,aside,a,[hidden],[aria-hidden=true],"
                + "[style~=(?i)display\\s*:\\s*none]").remove();
        documento.select("h1").stream().limit(10).forEach(h -> agregar(senales, h.text(), "H1", false));
        // Explicit document headings outrank an operator-supplied URL.
        if (senales.isEmpty() && url != null) {
            try {
                String ruta = URI.create(url.trim()).getRawPath();
                if (ruta != null) agregar(senales, DetectorEnlacesLegales.decodificar(ruta), "URL de referencia", true);
            } catch (IllegalArgumentException ignored) { /* the URL is optional metadata */ }
        }
        if (senales.isEmpty()) {
            documento.select("p").stream().limit(12).map(p -> p.text()).filter(t -> t.length() >= 40).forEach(t -> {
                // Only a document-identifying opening, not a passing mention in prose.
                String inicio = DetectorEnlacesLegales.normalizar(t);
                for (var categoria : CategoriaDocumentoLegal.values()) {
                    if (categoria.expresiones().stream().filter(e -> e.contains(" ")).anyMatch(e -> inicio.startsWith(e + " "))) {
                        senales.add(new Senal(categoria, "Contenido introductorio", recortar(t)));
                    }
                }
            });
        }
        if (senales.isEmpty()) {
            categoria(declarada).ifPresent(c -> senales.add(new Senal(c, ORIGEN_DECLARADA, c.etiqueta())));
        }
        return new CoberturaDocumento(senales.stream().distinct().limit(MAX_SENALES).toList(),
                acotar(new DetectorEnlacesLegales().detectar(html, url)));
    }

    /** Keeps the first links of each category with a storable URL; anything dropped flags the limit. */
    private static DeteccionEnlacesResponse acotar(DeteccionEnlacesResponse deteccion) {
        Map<CategoriaDocumentoLegal, Integer> porCategoria = new EnumMap<>(CategoriaDocumentoLegal.class);
        List<EnlaceLegalDetectado> conservados = new ArrayList<>();
        for (var e : deteccion.detectados()) {
            int n = porCategoria.merge(e.categoria(), 1, Integer::sum);
            if (n <= MAX_ENLACES_POR_CATEGORIA && e.url().length() <= MAX_URL_ENLACE) conservados.add(e);
        }
        boolean limite = deteccion.limiteAlcanzado() || conservados.size() < deteccion.detectados().size();
        return new DeteccionEnlacesResponse(List.copyOf(conservados), deteccion.noDetectados(),
                deteccion.enlacesAnalizados(), deteccion.enlacesDescartados(), limite, deteccion.referenciaAplicada());
    }

    private static void agregar(List<Senal> salida, String texto, String origen, boolean ruta) {
        for (var categoria : DetectorEnlacesLegales.coincidencias(DetectorEnlacesLegales.normalizar(texto), ruta)) {
            salida.add(new Senal(categoria, origen, recortar(texto.strip())));
        }
    }

    private static String recortar(String texto) {
        return texto.length() <= MAX_EVIDENCIA_LENGTH ? texto : texto.substring(0, MAX_EVIDENCIA_LENGTH - 1) + "…";
    }

    private static Optional<CategoriaDocumentoLegal> categoria(String etiqueta) {
        return Arrays.stream(CategoriaDocumentoLegal.values()).filter(c -> c.etiqueta().equals(etiqueta)).findFirst();
    }

    /**
     * Matrix of the seven categories over the member documents, already authorized and loaded.
     * A document counts as analysed for a category only through its own evidence; links only
     * through the documents that contain them. Historical documents keep their declared
     * category and are listed as lacking evidence: nothing is reconstructed for them.
     */
    public static MatrizCobertura resumir(List<AuditoriaResponse> auditorias) {
        List<DocumentoRef> sinEvidencias = new ArrayList<>();
        List<DocumentoRef> enlacesLimitados = new ArrayList<>();
        Map<Long, List<Senal>> senales = new LinkedHashMap<>();
        for (var a : auditorias) {
            CoberturaDocumento metadata = metadata(a);
            if (metadata == null) {
                sinEvidencias.add(new DocumentoRef(a.getId(), a.getTitulo()));
                senales.put(a.getId(), categoria(a.getTipoDocumento())
                        .map(c -> List.of(new Senal(c, ORIGEN_HISTORICO, c.etiqueta()))).orElse(List.of()));
            } else {
                senales.put(a.getId(), metadata.senales() == null ? List.of() : metadata.senales());
                if (metadata.enlaces() != null && metadata.enlaces().limiteAlcanzado()) {
                    enlacesLimitados.add(new DocumentoRef(a.getId(), a.getTitulo()));
                }
            }
        }

        List<CoberturaCategoria> filas = new ArrayList<>();
        for (var categoria : CategoriaDocumentoLegal.values()) {
            List<DocumentoCobertura> documentos = new ArrayList<>();
            List<Contradiccion> contradicciones = new ArrayList<>();
            Map<String, EnlaceCobertura> enlaces = new LinkedHashMap<>();
            for (var a : auditorias) {
                List<Senal> propias = senales.get(a.getId());
                var evidencias = propias.stream().filter(s -> s.categoria() == categoria).toList();
                if (!evidencias.isEmpty()) {
                    documentos.add(new DocumentoCobertura(a.getId(), a.getTitulo(), a.getTipoDocumento(),
                            evidencias, metadata(a) == null));
                } else if (categoria.etiqueta().equals(a.getTipoDocumento())) {
                    // Declared here, but its own content identifies other categories: not counted here.
                    contradicciones.add(new Contradiccion(a.getId(), a.getTitulo(),
                            propias.stream().map(s -> s.categoria().etiqueta()).distinct().toList()));
                }
                CoberturaDocumento metadata = metadata(a);
                if (metadata == null || metadata.enlaces() == null) continue;
                for (var e : metadata.enlaces().detectados()) {
                    if (e.categoria() != categoria) continue;
                    // The same absolute URL is one link; an unresolved relative path belongs to its own document.
                    String clave = e.relativa() ? a.getId() + " " + e.url() : e.url();
                    var origen = new OrigenEnlace(a.getId(), a.getTitulo(), e.texto(), e.contexto(), e.senal());
                    var enlace = enlaces.computeIfAbsent(clave, k -> new EnlaceCobertura(e.url(), e.relativa(), new ArrayList<>()));
                    if (!enlace.origenes().contains(origen)) enlace.origenes().add(origen);
                }
            }
            var estado = !documentos.isEmpty() ? EstadoCobertura.DOCUMENTO_ANALIZADO
                    : !enlaces.isEmpty() ? EstadoCobertura.ENLACE_DETECTADO_NO_ANALIZADO : EstadoCobertura.NO_VERIFICADO;
            filas.add(new CoberturaCategoria(categoria, categoria.etiqueta(), estado, List.copyOf(documentos),
                    enlaces.values().stream().map(e -> new EnlaceCobertura(e.url(), e.relativa(), List.copyOf(e.origenes())))
                            .toList(), List.copyOf(contradicciones)));
        }
        return new MatrizCobertura(List.copyOf(filas), List.copyOf(sinEvidencias), List.copyOf(enlacesLimitados));
    }

    private static CoberturaDocumento metadata(AuditoriaResponse a) {
        return a.getResultado() == null ? null : a.getResultado().getCoberturaDocumento();
    }
}
