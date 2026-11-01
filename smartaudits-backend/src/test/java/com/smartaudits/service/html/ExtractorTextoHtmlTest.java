package com.smartaudits.service.html;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractorTextoHtmlTest {
    private final ExtractorTextoHtml extractor = new ExtractorTextoHtml();

    @Test
    void validHtmlKeepsHeadingsParagraphsAndLists() {
        String html = """
                <!doctype html><html><head><title>Ignorado</title></head><body>
                <h1>Política de Privacidad</h1>
                <p>Responsable del   tratamiento: <strong>Ejemplo S.L.</strong>,
                   con CIF B12345678.</p>
                <h2>Derechos</h2>
                <ul><li>Acceso</li><li>Rectificación &amp; supresión</li></ul>
                <p>Contacto:<br>privacidad@example.invalid</p>
                </body></html>
                """;

        assertThat(extractor.extraer(html)).isEqualTo("""
                Política de Privacidad

                Responsable del tratamiento: Ejemplo S.L., con CIF B12345678.

                Derechos

                - Acceso
                - Rectificación & supresión

                Contacto:
                privacidad@example.invalid""");
    }

    @Test
    void malformedHtmlIsRecoveredWithoutLosingText() {
        String html = "<div><p>Primer párrafo <b>sin cerrar<p>Segundo párrafo</div></span><li>Suelto";

        String texto = extractor.extraer(html);

        assertThat(texto).contains("Primer párrafo sin cerrar", "Segundo párrafo", "- Suelto");
        assertThat(texto).doesNotContain("<", ">");
    }

    @Test
    void scriptsStylesEmbeddedContentAndHiddenElementsAreExcluded() {
        String html = """
                <style>body{display:none}</style>
                <script>alert('xss')</script>
                <noscript>Activa JavaScript</noscript>
                <nav><a href="/">Inicio</a></nav>
                <iframe src="https://evil.example.invalid"></iframe>
                <svg><text>vector</text></svg>
                <template><p>plantilla</p></template>
                <p hidden>oculto</p><p aria-hidden="true">decorativo</p><p style="DISPLAY: none">invisible</p>
                <form><label>Nombre</label><input value="campo"><button>Enviar</button></form>
                <p>Texto legal visible</p>
                """;

        assertThat(extractor.extraer(html)).isEqualTo("Nombre\n\nTexto legal visible");
    }

    @Test
    void dangerousAttributesAndUrlsNeverReachTheText() {
        String html = """
                <p onclick="steal()">Cláusula <a href="javascript:alert(1)">uno</a>
                <img src=x onerror="alert(2)"> dos</p>
                <a href="data:text/html;base64,PHNjcmlwdD4=">tres</a>
                <p>&lt;script&gt;alert(3)&lt;/script&gt;</p>
                """;

        String texto = extractor.extraer(html);

        assertThat(texto).isEqualTo("Cláusula uno dos\n\ntres\n\n<script>alert(3)</script>");
        assertThat(texto).doesNotContain("javascript:", "onerror", "onclick", "data:text");
    }

    @Test
    void emptyOrTextlessHtmlGivesEmptyText() {
        assertThat(extractor.extraer(null)).isEmpty();
        assertThat(extractor.extraer("   ")).isEmpty();
        assertThat(extractor.extraer("<html><head><title>t</title></head><body> <script>x()</script> </body></html>")).isEmpty();
        assertThat(extractor.extraer("<ul><li> </li></ul><p>&nbsp;</p>")).isEmpty();
    }

    @Test
    void tablesKeepCellsOnTheirRow() {
        String html = "<table><tr><th>Finalidad</th><th>Base</th></tr><tr><td>Facturación</td><td>Contrato</td></tr></table>";

        assertThat(extractor.extraer(html)).isEqualTo("Finalidad Base\nFacturación Contrato");
    }

    @Test
    void largeInputUpToTheLimitIsHandled() {
        String parrafo = "<p>" + "Texto legal. ".repeat(70) + "</p>";
        String html = parrafo.repeat(ExtractorTextoHtml.MAX_HTML_LENGTH / parrafo.length());

        assertThat(html.length()).isLessThanOrEqualTo(ExtractorTextoHtml.MAX_HTML_LENGTH);
        assertThat(extractor.extraer(html)).startsWith("Texto legal.").doesNotContain("<p>");
    }
}
