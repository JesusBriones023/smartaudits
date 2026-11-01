package com.smartaudits.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartaudits.model.Auditoria;
import com.smartaudits.model.Role;
import com.smartaudits.model.TipoFuente;
import com.smartaudits.model.Usuario;
import com.smartaudits.model.dto.AuditoriaConjuntaRequest;
import com.smartaudits.model.dto.AuditoriaConjuntaResponse;
import com.smartaudits.model.dto.AuditoriaRequest;
import com.smartaudits.model.dto.CoberturaDocumento;
import com.smartaudits.model.dto.DeteccionEnlacesResponse;
import com.smartaudits.model.dto.ResultadoAuditoria;
import com.smartaudits.repository.AuditoriaConjuntaRepository;
import com.smartaudits.repository.AuditoriaRepository;
import com.smartaudits.repository.UsuarioRepository;
import com.smartaudits.service.html.CategoriaDocumentoLegal;
import com.smartaudits.service.html.CoberturaDocumental;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/** Varios HTML → motor real por documento → auditorías + grupo persistidos → informe conjunto. */
@DataJpaTest(showSql = false, properties = {
        "spring.config.location=optional:classpath:/phase21-no-config.properties",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
        "spring.sql.init.mode=never",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "logging.level.root=WARN", "logging.level.org.springframework=WARN"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Import({AuditoriaService.class, HistorialService.class, AuditQuotaService.class,
        MotorAnalisisLegal.class, ObjectMapper.class, AuditoriaConjuntaService.class})
class AuditoriaConjuntaIntegrationTest {
    static final String PRIVACIDAD = """
            <h1>Política de Privacidad</h1><script>alert(1)</script>
            <p>Responsable del tratamiento: Ejemplo S.L. Contacto: privacidad@example.invalid.</p>
            <ul><li>Derecho de acceso</li><li>Derecho de supresión</li></ul>""";
    static final String COOKIES = "<h1>Política de Cookies</h1><p>Usamos cookies técnicas y analíticas.</p>";
    static final String AVISO = "<h1>Aviso Legal</h1><p>Titular: Ejemplo S.L., CIF B12345678, Madrid.</p>";

    @Autowired AuditoriaConjuntaService service;
    @Autowired AuditoriaService auditorias;
    @Autowired AnalizadorLegal analizador;
    @Autowired AuditoriaRepository audits;
    @Autowired AuditoriaConjuntaRepository groups;
    @Autowired UsuarioRepository users;
    @Autowired com.smartaudits.repository.HistorialAuditoriaRepository history;
    @Autowired TestEntityManager entityManager;

    Usuario user(String email, Role role) {
        Usuario usuario = new Usuario();
        usuario.setNombre("Cliente " + email);
        usuario.setEmail(email);
        usuario.setPassword("unused-test-fixture");
        usuario.setRole(role);
        return users.saveAndFlush(usuario);
    }

    static AuditoriaConjuntaRequest.Documento doc(String titulo, String tipo, String html) {
        var d = new AuditoriaConjuntaRequest.Documento();
        d.setTitulo(titulo);
        d.setTipoDocumento(tipo);
        d.setHtml(html);
        d.setUrlOpcional("https://example.invalid/" + titulo.length());
        return d;
    }

    static AuditoriaConjuntaRequest request(String titulo, AuditoriaConjuntaRequest.Documento... docs) {
        var r = new AuditoriaConjuntaRequest();
        r.setTitulo(titulo);
        r.setDocumentos(List.of(docs));
        return r;
    }

    long auditsOf(Usuario u) {
        return audits.findAll().stream().filter(a -> a.getUsuario().getId().equals(u.getId())).count();
    }

    @Test
    void threeDocumentsAreAnalysedIndividuallyPersistedAndReportedTogether() {
        Usuario owner = user("joint@example.invalid", Role.CLIENTE);
        var request = request("Web de Ejemplo", doc("Privacidad", "Política de Privacidad", PRIVACIDAD),
                doc("Cookies", "Política de Cookies", COOKIES), doc("Aviso", "Aviso Legal", AVISO));

        AuditoriaConjuntaResponse created = service.crear(request, owner, "192.0.2.31");
        entityManager.flush();
        entityManager.clear();

        assertThat(created.documentosAnalizados()).isEqualTo(3);
        assertThat(created.categoriasNoAportadas()).containsExactly("Términos y Condiciones", "Condiciones de Uso",
                "Condiciones de Compra", "Accesibilidad");
        ExtractorTextoHtml extractor = new ExtractorTextoHtml();
        for (int i = 0; i < 3; i++) {
            var input = request.getDocumentos().get(i);
            ResultadoAuditoria expected = analizador.analyze(new EntradaAnalisis(extractor.extraer(input.getHtml()), input.getTipoDocumento()));
            var d = created.documentos().get(i);
            assertThat(d.titulo()).isEqualTo(input.getTitulo());
            assertThat(d.tipoDocumento()).isEqualTo(input.getTipoDocumento());
            assertThat(d.puntuacionRiesgo()).isEqualTo(expected.getPuntuacionRiesgo());
            assertThat(d.incidencias()).isEqualTo(expected.getErrores());
            Auditoria stored = audits.findById(d.auditoriaId()).orElseThrow();
            assertThat(stored.getTipoFuente()).isEqualTo(TipoFuente.MANUAL);
            assertThat(stored.getTextoOriginal()).doesNotContain("<", "alert");
            assertThat(stored.getIncidencias()).hasSize(expected.getErrores().size());
            assertThat(stored.getUsuario().getId()).isEqualTo(owner.getId());
        }
        var group = groups.findById(created.id()).orElseThrow();
        assertThat(group.documentoIds()).containsExactlyElementsOf(created.documentos().stream()
                .map(AuditoriaConjuntaResponse.Documento::auditoriaId).toList());

        // Recommendations appear once each, attributed to every document that produced them.
        Set<String> texts = new HashSet<>();
        for (var rec : created.recomendaciones()) {
            assertThat(texts.add(rec.texto())).as("duplicate recommendation " + rec.texto()).isTrue();
            assertThat(rec.auditoriaIds()).isNotEmpty().hasSameSizeAs(rec.documentos());
            for (Long id : rec.auditoriaIds()) {
                var stored = auditorias.obtenerAuditoriaPorId(id, owner.getId(), false, owner, "192.0.2.31");
                assertThat(stored.getResultado().getRecomendaciones()).contains(rec.texto());
            }
        }

        AuditoriaConjuntaResponse read = service.obtener(created.id(), owner, false, "192.0.2.31");
        assertThat(read).isEqualTo(created);
        assertThat(service.listarMias(owner.getId(), 0, 10).content())
                .extracting(AuditoriaConjuntaResponse.Resumen::id, AuditoriaConjuntaResponse.Resumen::documentosAnalizados)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(created.id(), 3));
    }

    @Test
    void downloadReturnsThePersistedReportRecordsDownloadsAndChangesNoAudit() {
        Usuario owner = user("pdf@example.invalid", Role.CLIENTE);
        Usuario admin = user("pdf-admin@example.invalid", Role.ADMIN);
        Usuario other = user("pdf-other@example.invalid", Role.CLIENTE);
        var created = service.crear(request("Web PDF", doc("Privacidad", "Política de Privacidad", PRIVACIDAD),
                doc("Cookies", "Política de Cookies", COOKIES)), owner, "192.0.2.51");
        entityManager.flush();
        entityManager.clear();
        List<String> before = created.documentos().stream().map(d -> snapshot(d.auditoriaId())).toList();
        int downloadsBefore = history.findByAccion("DESCARGA").size();

        var downloaded = service.descargar(created.id(), owner, false, "192.0.2.51");
        entityManager.flush();
        entityManager.clear();

        assertThat(downloaded).isEqualTo(created);
        assertThat(downloaded.documentos()).allSatisfy(d -> assertThat(d.riesgos()).isNotNull());
        assertThat(created.documentos().stream().map(d -> snapshot(d.auditoriaId())).toList()).isEqualTo(before);
        assertThat(history.findByAccion("DESCARGA")).hasSize(downloadsBefore + 2);
        assertThat(service.descargar(created.id(), admin, true, "192.0.2.52")).isEqualTo(created);
        assertThatThrownBy(() -> service.descargar(created.id(), other, false, "192.0.2.53"))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(history.findByAccion("DESCARGA")).hasSize(downloadsBefore + 4);
    }

    String snapshot(Long auditoriaId) {
        Auditoria a = audits.findById(auditoriaId).orElseThrow();
        return String.join("|", a.getTitulo(), a.getTipoDocumento(), a.getTextoOriginal(), a.getResultadoJson(),
                String.valueOf(a.getPuntuacionRiesgo()), String.valueOf(a.getFechaCreacion()), a.getEstado(),
                String.valueOf(a.getIncidencias().size()));
    }

    @Test
    void theCoverageMatrixIsStoredWithEachResultAndReadBackUnchanged() throws Exception {
        Usuario owner = user("coverage@example.invalid", Role.CLIENTE);
        Usuario admin = user("coverage-admin@example.invalid", Role.ADMIN);
        Usuario other = user("coverage-other@example.invalid", Role.CLIENTE);
        String pie = "<footer><a href='/accesibilidad#inicio'>Accesibilidad</a><a href='javascript:alert(1)'>Aviso legal</a>"
                + "<a href='https://example.invalid/condiciones-de-compra'>Condiciones de compra</a></footer>";
        var created = service.crear(request("Web cobertura", doc("Privacidad", "Política de Privacidad", PRIVACIDAD + pie),
                doc("Accesibilidad declarada", "Accesibilidad", "<h1>Declaración de accesibilidad</h1><p>Nivel AA.</p>" + pie),
                doc("Compra", "Condiciones de Compra", "<h1>Política de cookies</h1><p>Texto.</p>")), owner, "192.0.2.61");
        entityManager.flush();
        entityManager.clear();

        var m = created.cobertura();
        assertThat(m.categorias()).extracting(f -> f.categoria(), f -> f.estado().name()).containsExactly(
                tuple(CategoriaDocumentoLegal.POLITICA_PRIVACIDAD, "DOCUMENTO_ANALIZADO"),
                tuple(CategoriaDocumentoLegal.POLITICA_COOKIES, "DOCUMENTO_ANALIZADO"),
                tuple(CategoriaDocumentoLegal.AVISO_LEGAL, "NO_VERIFICADO"),
                tuple(CategoriaDocumentoLegal.TERMINOS_CONDICIONES, "NO_VERIFICADO"),
                tuple(CategoriaDocumentoLegal.CONDICIONES_USO, "NO_VERIFICADO"),
                tuple(CategoriaDocumentoLegal.CONDICIONES_COMPRA, "ENLACE_DETECTADO_NO_ANALIZADO"),
                tuple(CategoriaDocumentoLegal.DECLARACION_ACCESIBILIDAD, "DOCUMENTO_ANALIZADO"));
        // "Compra" was declared as purchase conditions but is a cookie policy: reported, not counted.
        var compra = m.categorias().get(5);
        assertThat(compra.contradicciones()).extracting(c -> c.titulo()).containsExactly("Compra");
        assertThat(compra.enlaces()).singleElement().satisfies(e -> assertThat(e.origenes())
                .extracting(o -> o.titulo()).containsExactly("Privacidad", "Accesibilidad declarada"));
        // Relative links were resolved only against each document's own reference URL; fragments dropped.
        assertThat(m.categorias().get(6).enlaces()).isNotEmpty().allSatisfy(e ->
                assertThat(e.url()).startsWith("https://example.invalid/").doesNotContain("#"));

        for (var d : created.documentos()) {
            String json = audits.findById(d.auditoriaId()).orElseThrow().getResultadoJson();
            assertThat(json).contains("coberturaDocumento").doesNotContain("<h1", "<script", "javascript:", "<footer");
            assertThat(new ObjectMapper().readValue(json, ResultadoAuditoria.class).getCoberturaDocumento()).isNotNull();
        }
        assertThat(service.obtener(created.id(), owner, false, "192.0.2.61").cobertura()).isEqualTo(m);
        assertThat(service.obtener(created.id(), admin, true, "192.0.2.62").cobertura()).isEqualTo(m);
        assertThat(service.descargar(created.id(), owner, false, "192.0.2.61").cobertura()).isEqualTo(m);
        assertThatThrownBy(() -> service.obtener(created.id(), other, false, "192.0.2.63")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.descargar(created.id(), other, false, "192.0.2.63")).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void auditsStoredBeforeCoverageExistedAreReportedWithTheirLimitationAndNeverRewritten() {
        Usuario owner = user("legacy-coverage@example.invalid", Role.CLIENTE);
        // The pre-existing path stores no coverage, exactly like every audit created before this feature.
        var antigua = auditorias.crearAuditoriaAnalizada("Privacidad 2025", "Política de Privacidad",
                "Responsable del tratamiento: Ejemplo S.L.", null, TipoFuente.MANUAL, owner, "192.0.2.64");
        var nueva = auditorias.crearAuditoriaAnalizada("Cookies", "Política de Cookies", "Usamos cookies.", null,
                TipoFuente.MANUAL, owner, "192.0.2.64", CoberturaDocumental.clasificar(
                        "<h1>Cookies</h1><a href='https://example.invalid/aviso-legal'>Aviso legal</a>", null, "Política de Cookies"));
        var grupo = new com.smartaudits.model.AuditoriaConjunta();
        grupo.setUsuario(owner);
        grupo.setTitulo("Grupo antiguo");
        grupo.setFechaCreacion(LocalDateTime.of(2025, 6, 1, 10, 0));
        grupo.asignarDocumentos(List.of(antigua.getId(), nueva.getId()));
        Long id = groups.saveAndFlush(grupo).getId();
        entityManager.flush();
        entityManager.clear();
        String antes = audits.findById(antigua.getId()).orElseThrow().getResultadoJson();
        assertThat(antes).doesNotContain("coberturaDocumento");

        var m = service.obtener(id, owner, false, "192.0.2.64").cobertura();
        service.descargar(id, owner, false, "192.0.2.64");
        entityManager.flush();
        entityManager.clear();

        assertThat(m.sinEvidencias()).extracting(r -> r.auditoriaId()).containsExactly(antigua.getId());
        var privacidad = m.categorias().get(0);
        assertThat(privacidad.estado().name()).isEqualTo("DOCUMENTO_ANALIZADO");
        assertThat(privacidad.documentos()).singleElement().satisfies(d -> {
            assertThat(d.historico()).isTrue();
            assertThat(d.evidencias()).singleElement().satisfies(s -> assertThat(s.origen()).contains("sin evidencias conservadas"));
        });
        assertThat(m.categorias().get(2).estado().name()).isEqualTo("ENLACE_DETECTADO_NO_ANALIZADO");
        assertThat(audits.findById(antigua.getId()).orElseThrow().getResultadoJson()).isEqualTo(antes);
    }

    @Test
    void coverageNeverMakesTheStoredResultExceedItsColumn() {
        Usuario owner = user("coverage-size@example.invalid", Role.CLIENTE);
        // Worst case the classifier can produce: every category at its link cap, long URLs, multibyte and escaped texts.
        String comilla = String.valueOf((char) 34);
        String enlaces = Arrays.stream(CategoriaDocumentoLegal.values()).flatMap(c -> IntStream.range(0, 5)
                .mapToObj(i -> "<a href='https://example.invalid/" + c.name().toLowerCase() + "/" + "x".repeat(420) + i + "'>"
                        + c.etiqueta() + " " + ("€" + comilla).repeat(90) + "</a>")).collect(Collectors.joining());
        var maxima = CoberturaDocumental.clasificar("<h1>Aviso legal</h1>" + enlaces, null, "Aviso Legal");
        var creada = auditorias.crearAuditoriaAnalizada("Máxima", "Aviso Legal", "Sin cláusulas.", null,
                TipoFuente.MANUAL, owner, "192.0.2.65", maxima);
        assertThat(bytes(creada.getId())).isLessThanOrEqualTo(AuditoriaService.MAX_RESULTADO_JSON_BYTES);
        // It fits as is: the guard below did not have to cut anything.
        assertThat(creada.getResultado().getCoberturaDocumento().enlaces().detectados()).hasSize(35);
        assertThat(creada.getResultado().getCoberturaDocumento().enlaces().limiteAlcanzado()).isFalse();

        // Beyond any classifier output, the guard drops links (never signals) until it fits and flags the cut.
        var enlace = maxima.enlaces().detectados().get(0);
        var desmesurada = new CoberturaDocumento(maxima.senales(), new DeteccionEnlacesResponse(
                Collections.nCopies(400, enlace), List.of(), 400, 0, false, false));
        var recortada = auditorias.crearAuditoriaAnalizada("Desmesurada", "Aviso Legal", "Sin cláusulas.", null,
                TipoFuente.MANUAL, owner, "192.0.2.65", desmesurada);
        entityManager.flush();
        entityManager.clear();
        assertThat(bytes(recortada.getId())).isLessThanOrEqualTo(AuditoriaService.MAX_RESULTADO_JSON_BYTES);
        var guardada = auditorias.leerAuditoriaAutorizada(recortada.getId(), owner.getId(), false).getResultado().getCoberturaDocumento();
        assertThat(guardada.enlaces().limiteAlcanzado()).isTrue();
        assertThat(guardada.enlaces().detectados()).isNotEmpty().hasSizeLessThan(400);
        assertThat(guardada.senales()).isEqualTo(maxima.senales());
        assertThat(guardada).isEqualTo(recortada.getResultado().getCoberturaDocumento());
    }

    int bytes(Long auditoriaId) {
        return audits.findById(auditoriaId).orElseThrow().getResultadoJson().getBytes(StandardCharsets.UTF_8).length;
    }

    @Test
    void anInvalidDocumentRejectsTheWholeBatchBeforeAnythingIsStored() {
        Usuario owner = user("invalid@example.invalid", Role.CLIENTE);

        assertThatThrownBy(() -> service.crear(request("Lote", doc("Privacidad", "Política de Privacidad", PRIVACIDAD),
                doc("Vacío", "Aviso Legal", "<script>x()</script>")), owner, "192.0.2.32"))
                .isInstanceOf(ContenidoHtmlInvalidoException.class)
                .hasMessage("Documento 2 («Vacío»): El HTML no contiene texto legible para auditar");
        assertThatThrownBy(() -> service.crear(request("Lote", doc("A", "Aviso Legal", AVISO),
                doc("B", "Aviso Legal", AVISO)), owner, "192.0.2.32"))
                .isInstanceOf(ContenidoHtmlInvalidoException.class)
                .hasMessageContaining("repetida: Aviso Legal");
        String grande = "<p>" + "x".repeat(400_000) + "</p>";
        assertThatThrownBy(() -> service.crear(request("Lote", doc("A", "Aviso Legal", grande),
                doc("B", "Política de Cookies", grande), doc("C", "Condiciones de Uso", grande)), owner, "192.0.2.32"))
                .isInstanceOf(ContenidoHtmlInvalidoException.class)
                .hasMessageContaining("no puede superar los 1000000");
        assertThat(auditsOf(owner)).isZero();
        assertThat(groups.count()).isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aFailureAfterTheDocumentsWereSavedRollsBackTheWholeBatch() {
        Usuario owner = user("rollback-" + System.nanoTime() + "@example.invalid", Role.CLIENTE);
        long groupsBefore = groups.count();
        // Bypasses DTO validation on purpose: the group row fails at flush, after its documents.
        var tooLong = request("t".repeat(201), doc("Privacidad", "Política de Privacidad", PRIVACIDAD),
                doc("Cookies", "Política de Cookies", COOKIES));

        assertThatThrownBy(() -> service.crear(tooLong, owner, "192.0.2.33")).isInstanceOf(RuntimeException.class);

        assertThat(auditsOf(owner)).isZero();
        assertThat(groups.count()).isEqualTo(groupsBefore);
    }

    @Test
    void onlyTheOwnerOrAnAdminCanReadTheJointReport() {
        Usuario owner = user("owner-joint@example.invalid", Role.CLIENTE);
        Usuario other = user("other-joint@example.invalid", Role.CLIENTE);
        Usuario admin = user("admin-joint@example.invalid", Role.ADMIN);
        var created = service.crear(request("Web", doc("Aviso", "Aviso Legal", AVISO)), owner, "192.0.2.34");

        assertThatThrownBy(() -> service.obtener(created.id(), other, false, "192.0.2.35"))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(service.obtener(created.id(), admin, true, "192.0.2.36").documentosAnalizados()).isEqualTo(1);
        assertThat(service.listarMias(other.getId(), 0, 10).content()).isEmpty();
        assertThatThrownBy(() -> service.obtener(987654L, owner, false, "192.0.2.34"))
                .hasMessage("Auditoría conjunta no encontrada");
    }

    @Test
    void individualAuditsKeepWorkingAlongsideJointOnes() {
        Usuario owner = user("mixed@example.invalid", Role.CLIENTE);
        AuditoriaRequest manual = new AuditoriaRequest();
        manual.setTitulo("Manual");
        manual.setTipoDocumento("Aviso Legal");
        manual.setTextoOriginal("Titular: Ejemplo S.L.");
        var single = auditorias.crearAuditoria(manual, owner, "192.0.2.37");
        var joint = service.crear(request("Web", doc("Aviso", "Aviso Legal", AVISO)), owner, "192.0.2.37");

        assertThat(auditorias.obtenerMisAuditorias(owner.getId(), 0, 10).content())
                .extracting(r -> r.getId()).contains(single.getId(), joint.documentos().get(0).auditoriaId());
        assertThat(auditorias.obtenerAuditoriaPorId(single.getId(), owner.getId(), false, owner, "192.0.2.37")
                .getTextoOriginal()).isEqualTo("Titular: Ejemplo S.L.");
    }
}
