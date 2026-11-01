package com.smartaudits.service.html;

import com.smartaudits.model.dto.DeteccionEnlacesResponse;
import com.smartaudits.model.dto.DeteccionEnlacesResponse.EnlaceLegalDetectado;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.smartaudits.service.html.CategoriaDocumentoLegal.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class DetectorEnlacesLegalesTest {
    private final DetectorEnlacesLegales detector = new DetectorEnlacesLegales();

    private static final String PIE = """
            <html><body>
            <nav><a href="/">Inicio</a><a href="/productos">Productos</a></nav>
            <main><p>Contenido</p></main>
            <footer>
              <a href="/legal/privacidad">Política de Privacidad</a>
              <a href="/cookies">Política de cookies</a>
              <a href="/aviso-legal">Aviso legal</a>
              <a href="/terminos">Términos y condiciones</a>
              <a href="/condiciones-de-uso">Condiciones de uso</a>
              <a href="/condiciones-de-compra">Condiciones generales de contratación</a>
              <a href="/accesibilidad">Declaración de accesibilidad</a>
            </footer>
            </body></html>
            """;

    @Test
    void commonFooterLinksAreDetectedAndClassifiedWithBaseResolution() {
        DeteccionEnlacesResponse r = detector.detectar(PIE, "https://www.ejemplo.es/inicio");

        assertThat(r.detectados()).extracting(EnlaceLegalDetectado::categoria, EnlaceLegalDetectado::url,
                        EnlaceLegalDetectado::externo, EnlaceLegalDetectado::contexto, EnlaceLegalDetectado::senal)
                .containsExactly(
                        tuple(POLITICA_PRIVACIDAD, "https://www.ejemplo.es/legal/privacidad", false, "footer", "texto"),
                        tuple(POLITICA_COOKIES, "https://www.ejemplo.es/cookies", false, "footer", "texto"),
                        tuple(AVISO_LEGAL, "https://www.ejemplo.es/aviso-legal", false, "footer", "texto"),
                        tuple(TERMINOS_CONDICIONES, "https://www.ejemplo.es/terminos", false, "footer", "texto"),
                        tuple(CONDICIONES_USO, "https://www.ejemplo.es/condiciones-de-uso", false, "footer", "texto"),
                        tuple(CONDICIONES_COMPRA, "https://www.ejemplo.es/condiciones-de-compra", false, "footer", "texto"),
                        tuple(DECLARACION_ACCESIBILIDAD, "https://www.ejemplo.es/accesibilidad", false, "footer", "texto"));
        assertThat(r.noDetectados()).isEmpty();
        assertThat(r.enlacesAnalizados()).isEqualTo(9);
        assertThat(r.referenciaAplicada()).isTrue();
        assertThat(r.limiteAlcanzado()).isFalse();
    }

    @Test
    void englishVariantsAttributesRelAndPathsAreRecognised() {
        String html = """
                <nav aria-label="Legal"><a href="https://example.com/privacy-policy">Privacy Policy</a></nav>
                <div role="contentinfo">
                  <a href="https://example.com/terms-of-use">Terms of Use</a>
                  <a href="https://example.com/tos">Terms of Service</a>
                  <a href="https://example.com/imprint">Imprint</a>
                  <a href="https://example.com/a" title="Cookie Policy">Info</a>
                  <a href="https://example.com/b" rel="privacy-policy">Here</a>
                  <a href="https://example.com/legal/accessibility-statement">More</a>
                  <a href="https://example.com/shop/terms-of-sale">Sales</a>
                </div>
                """;

        var r = detector.detectar(html, null);

        assertThat(r.detectados()).extracting(EnlaceLegalDetectado::categoria, EnlaceLegalDetectado::senal,
                        EnlaceLegalDetectado::contexto)
                .containsExactly(
                        tuple(POLITICA_PRIVACIDAD, "texto", "nav"),
                        tuple(CONDICIONES_USO, "texto", "footer"),
                        tuple(TERMINOS_CONDICIONES, "texto", "footer"),
                        tuple(AVISO_LEGAL, "texto", "footer"),
                        tuple(POLITICA_COOKIES, "atributo", "footer"),
                        tuple(POLITICA_PRIVACIDAD, "atributo", "footer"),
                        tuple(DECLARACION_ACCESIBILIDAD, "ruta", "footer"),
                        tuple(CONDICIONES_COMPRA, "ruta", "footer"));
        assertThat(r.detectados()).allSatisfy(d -> assertThat(d.externo()).isNull());
    }

    @Test
    void relativeLinksWithoutReferenceStayRelativeAndAreNotInvented() {
        String html = "<a href=\"privacidad.html#datos\">Privacidad</a><a href=\"../legal/cookies\">Cookies</a>";

        var r = detector.detectar(html, null);

        assertThat(r.detectados()).extracting(EnlaceLegalDetectado::url, EnlaceLegalDetectado::relativa,
                        EnlaceLegalDetectado::externo)
                .containsExactly(tuple("privacidad.html", true, null), tuple("../legal/cookies", true, null));
        assertThat(r.referenciaAplicada()).isFalse();
    }

    @Test
    void absoluteLinksAreNormalisedAndMarkedExternalAgainstTheReference() {
        String html = """
                <a href="HTTPS://Legal.Example.COM:443/Privacidad?v=1#top">Privacidad</a>
                <a href="//cdn.example.net/cookies">Cookies</a>
                <a href="https://www.ejemplo.es/aviso legal">Aviso legal</a>
                <a href="/política">Política de privacidad</a>
                """;

        var r = detector.detectar(html, "https://www.ejemplo.es/");

        assertThat(r.detectados()).extracting(EnlaceLegalDetectado::url, EnlaceLegalDetectado::externo)
                .containsExactly(
                        tuple("https://legal.example.com/Privacidad?v=1", true),
                        tuple("https://cdn.example.net/cookies", true),
                        tuple("https://www.ejemplo.es/aviso%20legal", false),
                        tuple("https://www.ejemplo.es/pol%C3%ADtica", false));
    }

    @Test
    void baseElementIsIgnored() {
        String html = "<head><base href=\"https://evil.example.invalid/\"></head><a href=\"/privacidad\">Privacidad</a>";

        assertThat(detector.detectar(html, null).detectados()).extracting(EnlaceLegalDetectado::url)
                .containsExactly("/privacidad");
        assertThat(detector.detectar(html, "https://www.ejemplo.es/").detectados()).extracting(EnlaceLegalDetectado::url)
                .containsExactly("https://www.ejemplo.es/privacidad");
    }

    @Test
    void duplicatesAreRemovedPerCategoryAndUrl() {
        String html = """
                <header><a href="/privacidad">Privacidad</a></header>
                <footer><a href="/privacidad#arriba">Política de privacidad</a>
                <a href="https://www.ejemplo.es/privacidad">Privacy</a>
                <a href="/privacidad-clientes">Privacidad</a></footer>
                """;

        var r = detector.detectar(html, "https://www.ejemplo.es/");

        assertThat(r.detectados()).extracting(EnlaceLegalDetectado::url, EnlaceLegalDetectado::contexto)
                .containsExactly(tuple("https://www.ejemplo.es/privacidad", "header"),
                        tuple("https://www.ejemplo.es/privacidad-clientes", "footer"));
    }

    @Test
    void dangerousOrNonHttpSchemesAreDiscarded() {
        String html = """
                <a href="javascript:alert(1)">Política de privacidad</a>
                <a href=" JaVaScRiPt:alert(2)">Cookies</a>
                <a href="java&#x09;script:alert(3)">Aviso legal</a>
                <a href="data:text/html;base64,PHNjcmlwdD4=">Términos</a>
                <a href="file:///etc/passwd">Condiciones de uso</a>
                <a href="mailto:dpo@example.invalid">Privacidad</a>
                <a href="vbscript:msgbox">Accesibilidad</a>
                <a href="ftp://example.invalid/terms">Terms</a>
                <a href="https://user:pass@example.invalid/privacidad">Privacidad</a>
                <a href="#privacidad">Privacidad</a>
                <a href="">Cookies</a>
                <a href="http://[::1">Aviso legal</a>
                """;

        var r = detector.detectar(html, "https://www.ejemplo.es/");

        assertThat(r.detectados()).isEmpty();
        assertThat(r.enlacesDescartados()).isEqualTo(12);
        assertThat(r.noDetectados()).containsExactly(CategoriaDocumentoLegal.values());
    }

    @Test
    void malformedHtmlStillYieldsLinks() {
        String html = "<footer><div><a href=/privacidad>Privacidad<a href=\"/cookies\">Cookies</div></span>";

        assertThat(detector.detectar(html, null).detectados()).extracting(EnlaceLegalDetectado::categoria)
                .containsExactly(POLITICA_PRIVACIDAD, POLITICA_COOKIES);
    }

    @Test
    void htmlWithoutLinksOrWithoutLegalLinksDetectsNothing() {
        for (String html : List.of("<p>Sin enlaces</p>", "", "<a href=\"/blog\">Blog</a><a href=\"/contacto\">Contacto</a>",
                "<a>Privacidad sin href</a>", "<a href=\"/legalidad-vigente\">Noticias</a>")) {
            var r = detector.detectar(html, null);
            assertThat(r.detectados()).as(html).isEmpty();
            assertThat(r.noDetectados()).containsExactly(CategoriaDocumentoLegal.values());
        }
    }

    @Test
    void wordsMustMatchWholeAndSpecificConditionsWinOverGenericTerms() {
        String html = """
                <a href="/x1">Términos de uso</a>
                <a href="/x2">Cookies y privacidad</a>
                <a href="/x3">Legal</a>
                <a href="/x4">Privacidades varias</a>
                <a href="/x5">Determinación</a>
                """;

        assertThat(detector.detectar(html, null).detectados()).extracting(EnlaceLegalDetectado::url,
                        EnlaceLegalDetectado::categoria)
                .containsExactly(tuple("/x1", CONDICIONES_USO), tuple("/x2", POLITICA_PRIVACIDAD),
                        tuple("/x2", POLITICA_COOKIES), tuple("/x3", AVISO_LEGAL));
    }

    @Test
    void processingAndResultLimitsAreEnforced() {
        String muchos = "<a href=\"/pagina\">Inicio</a>".repeat(DetectorEnlacesLegales.MAX_ENLACES_PROCESADOS)
                + "<a href=\"/privacidad\">Privacidad</a>";
        var r = detector.detectar(muchos, null);
        assertThat(r.enlacesAnalizados()).isEqualTo(DetectorEnlacesLegales.MAX_ENLACES_PROCESADOS);
        assertThat(r.limiteAlcanzado()).isTrue();
        assertThat(r.detectados()).isEmpty();

        StringBuilder distintos = new StringBuilder();
        for (int i = 0; i < DetectorEnlacesLegales.MAX_RESULTADOS + 10; i++) {
            distintos.append("<a href=\"/privacidad/").append(i).append("\">Privacidad</a>");
        }
        var limitados = detector.detectar(distintos.toString(), null);
        assertThat(limitados.detectados()).hasSize(DetectorEnlacesLegales.MAX_RESULTADOS);
        assertThat(limitados.limiteAlcanzado()).isTrue();

        String largo = "<a href=\"/privacidad?" + "a".repeat(DetectorEnlacesLegales.MAX_HREF_LENGTH) + "\">Privacidad</a>"
                + "<a href=\"/p\">" + "Privacidad ".repeat(40) + "</a>";
        var recortado = detector.detectar(largo, null);
        assertThat(recortado.enlacesDescartados()).isEqualTo(1);
        assertThat(recortado.detectados()).singleElement()
                .satisfies(d -> assertThat(d.texto()).hasSize(DetectorEnlacesLegales.MAX_TEXTO_LENGTH).endsWith("…"));
    }

    @Test
    void anInvalidReferenceUrlIsNotUsed() {
        var r = detector.detectar("<a href=\"/privacidad\">Privacidad</a>", "https://user@ejemplo.es/");
        assertThat(r.referenciaAplicada()).isFalse();
        assertThat(r.detectados()).extracting(EnlaceLegalDetectado::url).containsExactly("/privacidad");
    }
}
