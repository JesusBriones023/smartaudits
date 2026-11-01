package com.smartaudits.service.html;

import com.smartaudits.model.dto.AuditoriaConjuntaResponse.CoberturaCategoria;
import com.smartaudits.model.dto.AuditoriaConjuntaResponse.Contradiccion;
import com.smartaudits.model.dto.AuditoriaConjuntaResponse.DocumentoCobertura;
import com.smartaudits.model.dto.AuditoriaConjuntaResponse.DocumentoRef;
import com.smartaudits.model.dto.AuditoriaConjuntaResponse.EnlaceCobertura;
import com.smartaudits.model.dto.AuditoriaConjuntaResponse.MatrizCobertura;
import com.smartaudits.model.dto.AuditoriaConjuntaResponse.OrigenEnlace;
import com.smartaudits.model.dto.AuditoriaResponse;
import com.smartaudits.model.dto.CoberturaDocumento;
import com.smartaudits.model.dto.CoberturaDocumento.Senal;
import com.smartaudits.model.dto.ResultadoAuditoria;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.smartaudits.model.dto.AuditoriaConjuntaResponse.EstadoCobertura.DOCUMENTO_ANALIZADO;
import static com.smartaudits.model.dto.AuditoriaConjuntaResponse.EstadoCobertura.ENLACE_DETECTADO_NO_ANALIZADO;
import static com.smartaudits.model.dto.AuditoriaConjuntaResponse.EstadoCobertura.NO_VERIFICADO;
import static com.smartaudits.service.html.CategoriaDocumentoLegal.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class CoberturaDocumentalTest {

    private final AtomicLong ids = new AtomicLong(100);

    /** A member document as the joint report receives it after authorization and loading. */
    private AuditoriaResponse doc(String titulo, String declarada, CoberturaDocumento cobertura) {
        var resultado = new ResultadoAuditoria();
        resultado.setCoberturaDocumento(cobertura);
        var a = new AuditoriaResponse();
        a.setId(ids.incrementAndGet());
        a.setTitulo(titulo);
        a.setTipoDocumento(declarada);
        a.setResultado(resultado);
        return a;
    }

    private AuditoriaResponse supplied(String titulo, String declarada, String html, String url) {
        return doc(titulo, declarada, CoberturaDocumental.clasificar(html, url, declarada));
    }

    private static CoberturaCategoria fila(MatrizCobertura m, CategoriaDocumentoLegal c) {
        return m.categorias().stream().filter(f -> f.categoria() == c).findFirst().orElseThrow();
    }

    private static List<CategoriaDocumentoLegal> categorias(CoberturaDocumento c) {
        return c.senales().stream().map(Senal::categoria).distinct().toList();
    }

    @Test
    void theSevenCategoriesAreAnalysedFromTheirOwnDocuments() {
        var docs = Arrays.stream(CategoriaDocumentoLegal.values())
                .map(c -> supplied(c.etiqueta(), c.etiqueta(), "<h1>" + c.etiqueta() + "</h1><p>Texto.</p>", null)).toList();

        MatrizCobertura m = CoberturaDocumental.resumir(docs);

        assertThat(m.categorias()).extracting(CoberturaCategoria::categoria).containsExactly(CategoriaDocumentoLegal.values());
        assertThat(m.categorias()).extracting(CoberturaCategoria::etiqueta).containsExactly("Política de Privacidad",
                "Política de Cookies", "Aviso Legal", "Términos y Condiciones", "Condiciones de Uso",
                "Condiciones de Compra", "Accesibilidad");
        for (int i = 0; i < 7; i++) {
            var f = m.categorias().get(i);
            assertThat(f.estado()).as(f.etiqueta()).isEqualTo(DOCUMENTO_ANALIZADO);
            assertThat(f.documentos()).extracting(DocumentoCobertura::auditoriaId).containsExactly(docs.get(i).getId());
            assertThat(f.documentos().get(0).evidencias()).extracting(Senal::origen).containsExactly("H1");
            assertThat(f.contradicciones()).isEmpty();
        }
        assertThat(m.sinEvidencias()).isEmpty();
    }

    @Test
    void linksAreDetectedButNeverAnalysedAndSilenceIsOnlyNotVerified() {
        var privacidad = supplied("Privacidad", "Política de Privacidad", """
                <h1>Política de privacidad</h1><p>Responsable: Ejemplo S.L.</p>
                <footer><a href="/cookies">Cookies</a> <a href="/accesibilidad">Accesibilidad</a></footer>""",
                "https://www.ejemplo.es/privacidad");

        MatrizCobertura m = CoberturaDocumental.resumir(List.of(privacidad));

        assertThat(m.categorias()).extracting(CoberturaCategoria::categoria, CoberturaCategoria::estado).containsExactly(
                tuple(POLITICA_PRIVACIDAD, DOCUMENTO_ANALIZADO),
                tuple(POLITICA_COOKIES, ENLACE_DETECTADO_NO_ANALIZADO),
                tuple(AVISO_LEGAL, NO_VERIFICADO),
                tuple(TERMINOS_CONDICIONES, NO_VERIFICADO),
                tuple(CONDICIONES_USO, NO_VERIFICADO),
                tuple(CONDICIONES_COMPRA, NO_VERIFICADO),
                tuple(DECLARACION_ACCESIBILIDAD, ENLACE_DETECTADO_NO_ANALIZADO));
        var cookies = fila(m, POLITICA_COOKIES);
        assertThat(cookies.documentos()).isEmpty();
        assertThat(cookies.enlaces()).extracting(EnlaceCobertura::url).containsExactly("https://www.ejemplo.es/cookies");
        assertThat(cookies.enlaces().get(0).origenes()).extracting(OrigenEnlace::auditoriaId, OrigenEnlace::contexto)
                .containsExactly(tuple(privacidad.getId(), "footer"));
        // A link does not make the linking document a document of that category either.
        assertThat(fila(m, POLITICA_PRIVACIDAD).enlaces()).isEmpty();
        assertThat(fila(m, AVISO_LEGAL).documentos()).isEmpty();
        assertThat(fila(m, AVISO_LEGAL).enlaces()).isEmpty();
    }

    @Test
    void navigationLinksHiddenContentAndScriptsNeverClassifyTheDocument() {
        var c = CoberturaDocumental.clasificar("""
                <nav><a href="/cookies">Política de cookies</a></nav>
                <h1 hidden>Política de privacidad</h1><h1 style="display: none">Condiciones de uso</h1>
                <div aria-hidden="true"><h1>Términos y condiciones</h1></div>
                <script>document.write('<h1>Accesibilidad</h1>')</script><template><h1>Condiciones de compra</h1></template>
                <h1>Aviso legal</h1><footer><h1>Cookies</h1></footer>""", null, "Aviso Legal");

        assertThat(c.senales()).extracting(Senal::categoria, Senal::origen, Senal::evidencia)
                .containsExactly(tuple(AVISO_LEGAL, "H1", "Aviso legal"));
        // The navigation link is still a detected link, nothing more.
        assertThat(c.enlaces().detectados()).extracting(e -> e.categoria()).containsExactly(POLITICA_COOKIES);
    }

    @Test
    void headingsOutrankTheUrlWhichOutranksTheOpeningAndOnlyThenTheDeclaredCategory() {
        assertThat(categorias(CoberturaDocumental.clasificar("<title>Cookies | Ejemplo</title><h1>Ejemplo</h1>",
                "https://www.ejemplo.es/privacidad", "Política de Privacidad"))).containsExactly(POLITICA_COOKIES);
        var porUrl = CoberturaDocumental.clasificar("<h1>Ejemplo S.L.</h1><p>Texto.</p>",
                "https://www.ejemplo.es/legal/declaracion-de-accesibilidad", "Accesibilidad");
        assertThat(porUrl.senales()).extracting(Senal::categoria, Senal::origen)
                .containsExactly(tuple(DECLARACION_ACCESIBILIDAD, "URL de referencia"));
        var porInicio = CoberturaDocumental.clasificar(
                "<p>Condiciones generales de contratación de la tienda online de Ejemplo S.L. para compras.</p>", null,
                "Condiciones de Compra");
        assertThat(porInicio.senales()).extracting(Senal::categoria, Senal::origen)
                .containsExactly(tuple(CONDICIONES_COMPRA, "Contenido introductorio"));
        // A passing mention in prose is not a document-identifying opening: the declared category is used.
        var declarada = CoberturaDocumental.clasificar(
                "<p>En esta página explicamos cómo tratamos la política de privacidad y las cookies de la web.</p>", null,
                "Condiciones de Uso");
        assertThat(declarada.senales()).extracting(Senal::categoria, Senal::origen, Senal::evidencia)
                .containsExactly(tuple(CONDICIONES_USO, CoberturaDocumental.ORIGEN_DECLARADA, "Condiciones de Uso"));
        var m = CoberturaDocumental.resumir(List.of(doc("Uso", "Condiciones de Uso", declarada)));
        assertThat(fila(m, CONDICIONES_USO).estado()).isEqualTo(DOCUMENTO_ANALIZADO);
        assertThat(fila(m, CONDICIONES_USO).documentos().get(0).historico()).isFalse();
    }

    @Test
    void aDocumentWhoseContentContradictsItsDeclaredCategoryIsNotCountedForIt() {
        var cookies = supplied("Mi privacidad", "Política de Privacidad",
                "<h1>Política de cookies</h1><p>Usamos cookies técnicas.</p>", null);

        MatrizCobertura m = CoberturaDocumental.resumir(List.of(cookies));

        var privacidad = fila(m, POLITICA_PRIVACIDAD);
        assertThat(privacidad.estado()).isEqualTo(NO_VERIFICADO);
        assertThat(privacidad.documentos()).isEmpty();
        assertThat(privacidad.contradicciones()).extracting(Contradiccion::auditoriaId, Contradiccion::identificadas)
                .containsExactly(tuple(cookies.getId(), List.of("Política de Cookies")));
        var filaCookies = fila(m, POLITICA_COOKIES);
        assertThat(filaCookies.estado()).isEqualTo(DOCUMENTO_ANALIZADO);
        assertThat(filaCookies.documentos()).extracting(DocumentoCobertura::categoriaDeclarada)
                .containsExactly("Política de Privacidad");
        // A combined document identifies both of its categories and contradicts neither.
        var combinado = supplied("Privacidad y cookies", "Política de Privacidad",
                "<h1>Política de privacidad y cookies</h1>", null);
        var m2 = CoberturaDocumental.resumir(List.of(combinado));
        assertThat(fila(m2, POLITICA_PRIVACIDAD).estado()).isEqualTo(DOCUMENTO_ANALIZADO);
        assertThat(fila(m2, POLITICA_COOKIES).estado()).isEqualTo(DOCUMENTO_ANALIZADO);
        assertThat(m2.categorias()).allSatisfy(f -> assertThat(f.contradicciones()).isEmpty());
    }

    @Test
    void duplicatesCollapseByAbsoluteUrlButRelativePathsStayWithTheirDocument() {
        String pie = """
                <h1>%s</h1><footer>
                <a href="https://www.ejemplo.es/cookies#top">Cookies</a>
                <a href="HTTPS://WWW.EJEMPLO.ES:443/cookies">Política de cookies</a>
                <a href="/accesibilidad">Accesibilidad</a></footer>""";
        var a = supplied("Aviso", "Aviso Legal", pie.formatted("Aviso legal"), null);
        var b = supplied("Términos", "Términos y Condiciones", pie.formatted("Términos y condiciones"), null);

        MatrizCobertura m = CoberturaDocumental.resumir(List.of(a, b));

        var cookies = fila(m, POLITICA_COOKIES);
        assertThat(cookies.enlaces()).hasSize(1);
        assertThat(cookies.enlaces().get(0).url()).isEqualTo("https://www.ejemplo.es/cookies");
        assertThat(cookies.enlaces().get(0).origenes()).extracting(OrigenEnlace::auditoriaId)
                .containsExactly(a.getId(), b.getId());
        // Without a reference URL the two "/accesibilidad" may belong to different sites.
        var accesibilidad = fila(m, DECLARACION_ACCESIBILIDAD);
        assertThat(accesibilidad.enlaces()).extracting(EnlaceCobertura::url, EnlaceCobertura::relativa)
                .containsExactly(tuple("/accesibilidad", true), tuple("/accesibilidad", true));
        assertThat(accesibilidad.enlaces()).extracting(e -> e.origenes().get(0).auditoriaId())
                .containsExactly(a.getId(), b.getId());
        // The same origin is never listed twice for one link.
        assertThat(CoberturaDocumental.resumir(List.of(a, a)).categorias().get(1).enlaces().get(0).origenes()).hasSize(1);
        // Repeated signals of one document are kept once.
        var repetido = CoberturaDocumental.clasificar("<h1>Aviso legal</h1><h1>Aviso legal</h1>", null, "Aviso Legal");
        assertThat(repetido.senales()).hasSize(1);
    }

    @Test
    void historicalDocumentsKeepTheirDeclaredCategoryAndAreFlaggedWithoutReconstructingAnything() {
        var antiguo = doc("Privacidad antigua", "Política de Privacidad", null);
        var sinResultado = doc("Aviso antiguo", "Aviso Legal", null);
        sinResultado.setResultado(null);
        var nuevo = supplied("Cookies", "Política de Cookies",
                "<h1>Cookies</h1><footer><a href='/terminos'>Términos</a></footer>", null);

        MatrizCobertura m = CoberturaDocumental.resumir(List.of(antiguo, sinResultado, nuevo));

        assertThat(m.sinEvidencias()).extracting(DocumentoRef::auditoriaId).containsExactly(antiguo.getId(), sinResultado.getId());
        var privacidad = fila(m, POLITICA_PRIVACIDAD);
        assertThat(privacidad.estado()).isEqualTo(DOCUMENTO_ANALIZADO);
        assertThat(privacidad.documentos().get(0).historico()).isTrue();
        assertThat(privacidad.documentos().get(0).evidencias()).extracting(Senal::origen)
                .containsExactly(CoberturaDocumental.ORIGEN_HISTORICO);
        assertThat(fila(m, AVISO_LEGAL).documentos()).extracting(DocumentoCobertura::historico).containsExactly(true);
        assertThat(fila(m, TERMINOS_CONDICIONES).estado()).isEqualTo(ENLACE_DETECTADO_NO_ANALIZADO);
        assertThat(m.categorias()).allSatisfy(f -> assertThat(f.enlaces()).allSatisfy(e ->
                assertThat(e.origenes()).extracting(OrigenEnlace::auditoriaId).containsOnly(nuevo.getId())));
        assertThat(antiguo.getResultado().getCoberturaDocumento()).isNull();
    }

    @Test
    void storedLinksAndSignalsAreBoundedAndTheCutIsReported() {
        String enlaces = IntStream.range(0, 9).mapToObj(i -> "<a href='/cookies-" + i + "'>Cookies " + i + "</a>")
                .collect(Collectors.joining()) + "<a href='/privacidad?x=" + "y".repeat(600) + "'>Privacidad</a>";
        String h1 = IntStream.range(0, 30).mapToObj(i -> "<h1>Política de privacidad " + "ñ".repeat(300) + i + "</h1>")
                .collect(Collectors.joining());
        var c = CoberturaDocumental.clasificar(h1 + "<footer>" + enlaces + "</footer>", "https://www.ejemplo.es/", "Política de Privacidad");

        assertThat(c.enlaces().detectados()).hasSize(CoberturaDocumental.MAX_ENLACES_POR_CATEGORIA)
                .allSatisfy(e -> assertThat(e.categoria()).isEqualTo(POLITICA_COOKIES));
        assertThat(c.enlaces().limiteAlcanzado()).isTrue();
        assertThat(c.senales()).hasSizeLessThanOrEqualTo(CoberturaDocumental.MAX_SENALES)
                .allSatisfy(s -> assertThat(s.evidencia()).hasSizeLessThanOrEqualTo(CoberturaDocumental.MAX_EVIDENCIA_LENGTH));
        var m = CoberturaDocumental.resumir(List.of(doc("Privacidad", "Política de Privacidad", c)));
        assertThat(m.enlacesLimitados()).extracting(DocumentoRef::titulo).containsExactly("Privacidad");
        var sinCorte = CoberturaDocumental.clasificar("<h1>Aviso legal</h1><a href='/cookies'>Cookies</a>", null, "Aviso Legal");
        assertThat(sinCorte.enlaces().limiteAlcanzado()).isFalse();
    }

    @Test
    void hostileHtmlIsOnlyReadAsTextAndUnsafeLinksAreDropped() {
        var c = CoberturaDocumental.clasificar("""
                <title>Aviso legal<script>alert(1)</script></title>
                <h1>Aviso legal<img src=x onerror="alert(2)"><svg onload="alert(3)"></svg></h1>
                <footer><a href="javascript:alert(4)">Cookies</a><a href="data:text/html,<script>alert(5)</script>">Privacidad</a>
                <a href="https://user:pw@www.ejemplo.es/terminos">Términos</a><a href="/accesibilidad" onclick="alert(6)">Accesibilidad</a></footer>""",
                null, "Aviso Legal");

        // <title> is raw text: its evidence is that literal text (always rendered as text); markup in H1 is dropped.
        assertThat(c.senales()).extracting(Senal::categoria, Senal::origen, Senal::evidencia).containsExactly(
                tuple(AVISO_LEGAL, "Título HTML", "Aviso legal<script>alert(1)</script>"),
                tuple(AVISO_LEGAL, "H1", "Aviso legal"));
        assertThat(c.enlaces().detectados()).extracting(e -> e.url()).containsExactly("/accesibilidad");
        assertThat(c.enlaces().detectados().get(0).texto()).isEqualTo("Accesibilidad");
    }
}
