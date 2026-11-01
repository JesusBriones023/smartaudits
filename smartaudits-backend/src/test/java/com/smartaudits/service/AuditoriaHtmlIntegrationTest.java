package com.smartaudits.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartaudits.model.TipoFuente;
import com.smartaudits.model.Usuario;
import com.smartaudits.model.dto.AuditoriaHtmlRequest;
import com.smartaudits.model.dto.AuditoriaRequest;
import com.smartaudits.model.dto.ResultadoAuditoria;
import com.smartaudits.repository.AuditoriaRepository;
import com.smartaudits.repository.UsuarioRepository;
import com.smartaudits.service.html.ContenidoHtmlInvalidoException;
import com.smartaudits.service.html.ExtractorTextoHtml;
import com.smartaudits.service.motor.AnalizadorLegal;
import com.smartaudits.service.motor.EntradaAnalisis;
import com.smartaudits.service.motor.MotorAnalisisLegal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** HTML pegado → texto extraído → motor real → auditoría persistida y legible por su propietario. */
@DataJpaTest(showSql = false, properties = {
        "spring.config.location=optional:classpath:/phase21-no-config.properties",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
        "spring.sql.init.mode=never",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "logging.level.root=WARN", "logging.level.org.springframework=WARN"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Import({AuditoriaService.class, HistorialService.class, AuditQuotaService.class,
        MotorAnalisisLegal.class, ObjectMapper.class})
class AuditoriaHtmlIntegrationTest {
    private static final String HTML = """
            <html><head><script>fetch('https://evil.example.invalid')</script></head><body>
            <h1>Política de Privacidad</h1>
            <p>Responsable del tratamiento: Ejemplo S.L. Contacto: privacidad@example.invalid.</p>
            <ul><li>Derecho de acceso</li><li>Derecho de supresión</li></ul>
            <img src=x onerror="alert(1)">
            </body></html>
            """;

    @Autowired AuditoriaService service;
    @Autowired AnalizadorLegal analizador;
    @Autowired AuditoriaRepository audits;
    @Autowired UsuarioRepository users;
    @Autowired TestEntityManager entityManager;

    Usuario user(String email) {
        Usuario usuario = new Usuario();
        usuario.setNombre("Cliente " + email);
        usuario.setEmail(email);
        usuario.setPassword("unused-test-fixture");
        return users.saveAndFlush(usuario);
    }

    AuditoriaHtmlRequest request(String html) {
        AuditoriaHtmlRequest request = new AuditoriaHtmlRequest();
        request.setTitulo("Auditoría HTML");
        request.setTipoDocumento("Política de Privacidad");
        request.setHtml(html);
        request.setUrlOpcional("https://example.invalid/privacidad");
        return request;
    }

    @Test
    void htmlAuditIsAnalysedPersistedAndReadableAsTheVisibleReport() {
        Usuario owner = user("html@example.invalid");
        String texto = new ExtractorTextoHtml().extraer(HTML);
        ResultadoAuditoria expected = analizador.analyze(new EntradaAnalisis(texto, "Política de Privacidad"));

        var created = service.crearAuditoriaDesdeHtml(request(HTML), owner, "192.0.2.21");
        entityManager.flush();
        entityManager.clear();

        assertThat(created.getTipoFuente()).isEqualTo(TipoFuente.MANUAL);
        assertThat(created.getResultado()).isEqualTo(expected);
        assertThat(created.getTextoOriginal()).isEqualTo(texto)
                .contains("Política de Privacidad", "- Derecho de acceso")
                .doesNotContain("<", "script", "onerror", "evil.example");
        var stored = audits.findById(created.getId()).orElseThrow();
        assertThat(stored.getTipoFuente()).isEqualTo(TipoFuente.MANUAL);
        assertThat(stored.getTextoOriginal()).isEqualTo(texto);
        assertThat(stored.getUrlOpcional()).isEqualTo("https://example.invalid/privacidad");
        assertThat(stored.getPuntuacionRiesgo()).isEqualTo(expected.getPuntuacionRiesgo());
        assertThat(stored.getIncidencias()).hasSize(expected.getErrores().size());

        var read = service.obtenerAuditoriaPorId(created.getId(), owner.getId(), false, owner, "192.0.2.21");
        assertThat(read.getResultado()).isEqualTo(expected);
        assertThat(read.getTextoOriginal()).isEqualTo(texto);
    }

    @Test
    void htmlAndManualInputOfTheSameTextGiveTheSameAnalysis() {
        Usuario owner = user("parity@example.invalid");
        var html = service.crearAuditoriaDesdeHtml(request(HTML), owner, "192.0.2.22");
        AuditoriaRequest manual = new AuditoriaRequest();
        manual.setTitulo("Auditoría manual");
        manual.setTipoDocumento("Política de Privacidad");
        manual.setTextoOriginal(html.getTextoOriginal());

        var fromText = service.crearAuditoria(manual, owner, "192.0.2.22");

        assertThat(fromText.getTipoFuente()).isEqualTo(TipoFuente.MANUAL);
        assertThat(fromText.getResultado()).isEqualTo(html.getResultado());
    }

    @Test
    void htmlWithoutReadableTextIsRejectedAndNothingIsStored() {
        Usuario owner = user("empty@example.invalid");

        assertThatThrownBy(() -> service.crearAuditoriaDesdeHtml(
                request("<script>alert(1)</script><style>p{}</style>"), owner, "192.0.2.23"))
                .isInstanceOf(ContenidoHtmlInvalidoException.class)
                .hasMessageContaining("no contiene texto legible");
        assertThat(audits.count()).isZero();
    }

    @Test
    void extractedTextOverTheTextLimitIsRejected() {
        Usuario owner = user("long@example.invalid");
        String html = "<p>" + "a".repeat(AuditoriaRequest.MAX_TEXTO_LENGTH + 1) + "</p>";

        assertThatThrownBy(() -> service.crearAuditoriaDesdeHtml(request(html), owner, "192.0.2.24"))
                .isInstanceOf(ContenidoHtmlInvalidoException.class)
                .hasMessageContaining("supera los 15000 caracteres");
        assertThat(audits.count()).isZero();
    }

    @Test
    void onlyTheOwnerOrAnAdminCanReadAnHtmlAudit() {
        Usuario owner = user("owner-html@example.invalid");
        Usuario other = user("other-html@example.invalid");
        var created = service.crearAuditoriaDesdeHtml(request(HTML), owner, "192.0.2.25");

        assertThatThrownBy(() -> service.obtenerAuditoriaPorId(created.getId(), other.getId(), false, other, "192.0.2.26"))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(service.obtenerAuditoriaPorId(created.getId(), other.getId(), true, other, "192.0.2.26")
                .getId()).isEqualTo(created.getId());
    }
}
