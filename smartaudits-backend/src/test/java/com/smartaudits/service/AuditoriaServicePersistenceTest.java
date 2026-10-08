package com.smartaudits.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartaudits.model.Auditoria;
import com.smartaudits.model.Usuario;
import com.smartaudits.model.TipoFuente;
import com.smartaudits.model.Incidencia;
import com.smartaudits.model.dto.AuditoriaRequest;
import com.smartaudits.model.dto.AuditoriaResponse;
import com.smartaudits.model.dto.ResultadoAuditoria;
import com.smartaudits.repository.AuditoriaRepository;
import com.smartaudits.repository.HistorialAuditoriaRepository;
import com.smartaudits.repository.UsuarioRepository;
import com.smartaudits.service.motor.AnalizadorLegal;
import com.smartaudits.service.motor.EntradaAnalisis;
import com.smartaudits.service.motor.VersionAnalizador;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

@DataJpaTest(showSql = false, properties = {
        "spring.config.location=optional:classpath:/phase11-no-config.properties",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
        "spring.sql.init.mode=never",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "logging.level.root=WARN", "logging.level.org.springframework=WARN"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Import({AuditoriaService.class, HistorialService.class, AuditQuotaService.class, ObjectMapper.class})
class AuditoriaServicePersistenceTest {
    @Autowired AuditoriaService service;
    @Autowired AuditoriaRepository audits;
    @Autowired com.smartaudits.repository.IncidenciaRepository incidents;
    @Autowired UsuarioRepository users;
    @Autowired HistorialAuditoriaRepository history;
    @Autowired TestEntityManager entityManager;
    @Autowired ObjectMapper mapper;
    @Autowired HistorialService historialService;
    @Autowired AuditQuotaService quotaService;
    @MockBean AnalizadorLegal motor;

    @Test
    void creationPersistsOwnerAnalysisAndHistoryAndReturnsCompleteResponse() throws Exception {
        Usuario owner = user("owner");
        AuditoriaRequest request = new AuditoriaRequest();
        request.setTitulo("Política de prueba");
        request.setTipoDocumento("Política de Privacidad");
        request.setTextoOriginal("Responsable del tratamiento: Ejemplo. Contacto: owner@example.invalid.");
        request.setUrlOpcional("https://example.invalid/privacidad");

        ResultadoAuditoria analysis = new ResultadoAuditoria();
        var version = new VersionAnalizador("test-engine", "test-rules");
        when(motor.version()).thenReturn(version);
        analysis.setResumen("Falta información sobre conservación y derechos.");
        analysis.setPuntuacionRiesgo(62);
        analysis.setRiesgos(List.of("Conservación sin definir"));
        analysis.setRecomendaciones(List.of("Definir el plazo", "Explicar los derechos"));
        analysis.setTextosSugeridos(List.of("Conservaremos los datos durante el plazo indicado."));
        analysis.setReferenciasLegales(List.of("Referencia de prueba"));
        analysis.setFaltantes(List.of("Plazo de conservación"));
        var error = new ResultadoAuditoria.ErrorAuditoria(
                "ALT-07", "ALTERNATIVE_ENGINE", version.versionReglas(),
                "Conservación", "Falta un plazo concreto", "MEDIA",
                "Cláusula ausente", "Conservación excesiva", "Indicar un plazo");
        analysis.setErrores(List.of(error));
        EntradaAnalisis input = new EntradaAnalisis(request.getTextoOriginal(), request.getTipoDocumento());
        when(motor.analyze(input)).thenReturn(analysis);

        LocalDateTime before = LocalDateTime.now().minusSeconds(1);
        AuditoriaResponse response = service.crearAuditoria(request, owner, "192.0.2.10");
        entityManager.flush();
        entityManager.clear(); // Assert database state, not just the managed entity graph.

        verify(motor).analyze(input);
        Auditoria stored = audits.findById(response.getId()).orElseThrow();
        assertThat(audits.count()).isEqualTo(1);
        assertThat(stored.getUsuario().getId()).isEqualTo(owner.getId());
        assertThat(stored.getTitulo()).isEqualTo(request.getTitulo());
        assertThat(stored.getTipoDocumento()).isEqualTo(request.getTipoDocumento());
        assertThat(stored.getTextoOriginal()).isEqualTo(request.getTextoOriginal());
        assertThat(stored.getUrlOpcional()).isEqualTo(request.getUrlOpcional());
        assertThat(stored.getEstado()).isEqualTo("COMPLETADA");
        assertThat(stored.getVersionMotor()).isEqualTo(version.versionMotor());
        assertThat(stored.getVersionReglas()).isEqualTo(version.versionReglas());
        assertThat(stored.getFechaAnalisis()).isBetween(before, LocalDateTime.now());
        assertThat(stored.getTipoFuente()).isEqualTo(TipoFuente.MANUAL);
        assertThat(response.getVersionMotor()).isEqualTo(stored.getVersionMotor());
        assertThat(response.getVersionReglas()).isEqualTo(stored.getVersionReglas());
        assertThat(response.getFechaAnalisis()).isEqualTo(stored.getFechaAnalisis());
        assertThat(response.getTipoFuente()).isEqualTo(stored.getTipoFuente());
        assertThat(stored.getPuntuacionRiesgo()).isEqualTo(62);
        assertThat(stored.getFechaCreacion()).isBetween(before, LocalDateTime.now().plusSeconds(1));
        assertThat(mapper.readValue(stored.getResultadoJson(), ResultadoAuditoria.class)).isEqualTo(analysis);
        assertThat(mapper.readTree(stored.getResultadoJson()).path("puntuacionRiesgo").intValue())
                .isEqualTo(stored.getPuntuacionRiesgo());
        assertThat(stored.getIncidencias()).singleElement().satisfies(incident -> {
            assertThat(incident.getRuleId()).isEqualTo(error.getRuleId());
            assertThat(incident.getMotor()).isEqualTo(error.getMotor());
            assertThat(incident.getVersion()).isEqualTo(version.versionReglas());
            assertThat(incident.getId()).isNotNull();
            assertThat(incident.getAuditoria().getId()).isEqualTo(stored.getId());
            assertThat(incident.getCategoria()).isEqualTo(error.getTitulo());
            assertThat(incident.getDescripcion()).isEqualTo(error.getDescripcion());
            assertThat(incident.getSeveridad()).isEqualTo(error.getSeveridad());
            assertThat(incident.getEvidencia()).isEqualTo(error.getEvidencia());
            assertThat(incident.getImpacto()).isEqualTo(error.getImpacto());
            assertThat(incident.getRecomendacion()).isEqualTo(error.getAccion());
        });
        assertThat(response.getTitulo()).isEqualTo(request.getTitulo());
        assertThat(response.getTipoDocumento()).isEqualTo(request.getTipoDocumento());
        assertThat(response.getTextoOriginal()).isEqualTo(request.getTextoOriginal());
        assertThat(response.getUrlOpcional()).isEqualTo(request.getUrlOpcional());
        assertThat(response.getFechaCreacion()).isBetween(before, LocalDateTime.now().plusSeconds(1));
        assertThat(response.getPuntuacionRiesgo()).isEqualTo(62);
        assertThat(response.getResultado()).isEqualTo(analysis);
        var storedFinding = mapper.readTree(stored.getResultadoJson()).path("errores").get(0);
        assertThat(storedFinding.path("ruleId").asText()).isEqualTo(error.getRuleId());
        assertThat(storedFinding.path("motor").asText()).isEqualTo(error.getMotor());
        assertThat(storedFinding.path("version").asText()).isEqualTo(version.versionReglas());
        assertThat(response.getUsuarioId()).isEqualTo(owner.getId());
        assertThat(response.getUsuarioNombre()).isEqualTo(owner.getNombre());
        assertThat(response.getUsuarioEmail()).isEqualTo(owner.getEmail());
        assertThat(history.findAll()).singleElement().satisfies(event -> {
            assertThat(event.getAuditoria().getId()).isEqualTo(stored.getId());
            assertThat(event.getUsuario().getId()).isEqualTo(owner.getId());
            assertThat(event.getAccion()).isEqualTo("CREACION");
            assertThat(event.getIpAcceso()).isEqualTo("192.0.2.10");
        });
    }

    @Test
    void personalListingFiltersOtherOwnersAndPaginatesWithStableOrdering() {
        Usuario owner = user("owner");
        Usuario other = user("other");
        Auditoria first = audit(owner, "Primera");
        Auditoria second = audit(owner, "Segunda");
        audit(other, "Ajena");
        entityManager.flush();
        entityManager.clear();

        var page = service.obtenerMisAuditorias(owner.getId(), 0, 1);
        assertThat(page.content()).extracting(AuditoriaResponse::getId).containsExactly(second.getId());
        assertThat(page.content()).singleElement().satisfies(item -> {
            assertThat(item.getUsuarioId()).isEqualTo(owner.getId());
            assertThat(item.getTitulo()).isEqualTo("Segunda");
            assertThat(item.getTextoOriginal()).isNull();
            assertThat(item.getResultado()).isNull();
        });
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(1);
        assertThat(page.first()).isTrue();
        assertThat(page.last()).isFalse();
        var last = service.obtenerMisAuditorias(owner.getId(), 1, 1);
        assertThat(last.content()).extracting(AuditoriaResponse::getId).containsExactly(first.getId());
        assertThat(last.last()).isTrue();
        assertThat(history.count()).isZero();
        verifyNoInteractions(motor);
    }

    @Test
    void missingAuditRejectsReadAndDownloadWithoutRecordingHistory() {
        Usuario actor = user("actor");
        assertThatThrownBy(() -> service.obtenerAuditoriaPorId(-1L, actor.getId(), false, actor, "192.0.2.10"))
                .isInstanceOf(RuntimeException.class).hasMessage("Auditoría no encontrada");
        assertThatThrownBy(() -> service.registrarDescarga(-1L, actor.getId(), false, actor, "192.0.2.10"))
                .isInstanceOf(RuntimeException.class).hasMessage("Auditoría no encontrada");
        entityManager.flush();
        entityManager.clear();
        assertThat(history.count()).isZero();
        assertThat(audits.count()).isZero();
        verifyNoInteractions(motor);
    }

    @Test
    void alternativeAnalyzerReceivesOnlyContentRegardlessOfAuditMetadata() throws Exception {
        var received = new ArrayList<EntradaAnalisis>();
        ResultadoAuditoria analysis = new ResultadoAuditoria();
        analysis.setResumen("Resultado del analizador alternativo");
        analysis.setPuntuacionRiesgo(37);
        var version = new VersionAnalizador("alternative-engine", "alternative-rules");
        AnalizadorLegal alternative = new AnalizadorLegal() {
            public VersionAnalizador version() { return version; }
            public ResultadoAuditoria analyze(EntradaAnalisis input) {
                received.add(input);
                return analysis;
            }
        };
        var alternativeService = new AuditoriaService(
                audits, alternative, historialService, mapper, quotaService);
        EntradaAnalisis content = new EntradaAnalisis("  Texto sin normalizar.\n", "Aviso Legal");

        for (String name : List.of("first", "second")) {
            Usuario owner = user(name);
            AuditoriaRequest request = new AuditoriaRequest();
            request.setTitulo("Auditoría " + name);
            request.setTextoOriginal(content.texto());
            request.setTipoDocumento(content.tipoDocumento());
            request.setUrlOpcional("https://" + name + ".example.invalid/legal");
            AuditoriaResponse response = alternativeService.crearAuditoria(request, owner, null);
            assertThat(response.getResultado()).isEqualTo(analysis);
            assertThat(response.getVersionMotor()).isEqualTo(version.versionMotor());
            assertThat(response.getVersionReglas()).isEqualTo(version.versionReglas());
            assertThat(response.getUsuarioId()).isEqualTo(owner.getId());
            assertThat(response.getUrlOpcional()).isEqualTo(request.getUrlOpcional());
        }

        entityManager.flush();
        entityManager.clear();
        assertThat(received).containsExactly(content, content);
        var stored = audits.findAll();
        assertThat(stored).hasSize(2);
        for (Auditoria audit : stored) {
            assertThat(audit.getVersionMotor()).isEqualTo(version.versionMotor());
            assertThat(audit.getVersionReglas()).isEqualTo(version.versionReglas());
            assertThat(audit.getPuntuacionRiesgo()).isEqualTo(37);
            assertThat(audit.getTextoOriginal()).isEqualTo(content.texto());
            assertThat(mapper.readValue(audit.getResultadoJson(), ResultadoAuditoria.class)).isEqualTo(analysis);
        }
        assertThat(history.findAll()).hasSize(2).allSatisfy(event ->
                assertThat(event.getAccion()).isEqualTo("CREACION"));
        verifyNoInteractions(motor);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "https://example.invalid/reference-only")
    void sourceRemainsManualAndTimestampCapturesAnalysisExecution(String url) {
        Usuario owner = user("manual");
        var version = new VersionAnalizador("execution-engine", "execution-rules");
        var analysis = new ResultadoAuditoria();
        analysis.setPuntuacionRiesgo(50);
        LocalDateTime[] executionWindow = new LocalDateTime[2];
        when(motor.version()).thenAnswer(invocation -> {
            executionWindow[0] = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
            return version;
        });
        when(motor.analyze(any())).thenAnswer(invocation -> {
            executionWindow[1] = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
            return analysis;
        });
        var request = new AuditoriaRequest();
        request.setTitulo("Manual");
        request.setTextoOriginal("Texto introducido por el usuario");
        request.setTipoDocumento("Aviso Legal");
        request.setUrlOpcional(url);

        var response = service.crearAuditoria(request, owner, null);
        entityManager.flush();
        entityManager.clear();

        var stored = audits.findById(response.getId()).orElseThrow();
        assertThat(stored.getFechaAnalisis()).isBetween(executionWindow[0], executionWindow[1]);
        assertThat(stored.getVersionMotor()).isEqualTo(version.versionMotor());
        assertThat(stored.getVersionReglas()).isEqualTo(version.versionReglas());
        assertThat(stored.getTipoFuente()).isEqualTo(TipoFuente.MANUAL);
        assertThat(response.getFechaAnalisis()).isEqualTo(stored.getFechaAnalisis());
        assertThat(response.getTipoFuente()).isEqualTo(TipoFuente.MANUAL);
    }

    @Test
    void historicalReadAndListingsReturnStoredProvenanceWithoutConsultingAnalyzer() throws Exception {
        Usuario owner = user("historical");
        var historical = audit(owner, "Histórica");
        String originalJson = """
                { "resumen": "Resultado original", "puntuacionRiesgo": 23,
                  "errores": [{"titulo":"Categoría histórica","severidad":"MEDIA","evidencia":"Original  "}] }
                """;
        historical.setResultadoJson(originalJson);
        entityManager.flush();
        entityManager.clear();

        var read = service.obtenerAuditoriaPorId(historical.getId(), owner.getId(), false, owner, null);
        var personal = service.obtenerMisAuditorias(owner.getId(), 0, 10).content().get(0);
        var administrative = service.obtenerTodasLasAuditorias(0, 10, "").content().get(0);
        for (var response : List.of(read, personal, administrative)) {
            assertThat(response.getVersionMotor()).isEqualTo(historical.getVersionMotor());
            assertThat(response.getVersionReglas()).isEqualTo(historical.getVersionReglas());
            assertThat(response.getFechaAnalisis()).isEqualTo(historical.getFechaAnalisis());
            assertThat(response.getTipoFuente()).isEqualTo(historical.getTipoFuente());
        }
        assertThat(read.getResultado()).isEqualTo(mapper.readValue(originalJson, ResultadoAuditoria.class));
        assertThat(read.getResultado().getErrores()).singleElement().satisfies(error -> {
            assertThat(error.getRuleId()).isNull();
            assertThat(error.getMotor()).isNull();
            assertThat(error.getVersion()).isNull();
        });
        entityManager.flush();
        entityManager.clear();
        assertThat(audits.findById(historical.getId()).orElseThrow().getResultadoJson()).isEqualTo(originalJson);
        verifyNoInteractions(motor);
    }

    @Test
    void persistedProvenanceCannotBeReplacedWhenAuditIsUpdated() {
        var historical = audit(user("immutable"), "Original");
        entityManager.flush();
        entityManager.clear();
        var stored = audits.findById(historical.getId()).orElseThrow();

        assertThatThrownBy(() -> stored.registrarProcedencia("other-engine", "other-rules",
                LocalDateTime.of(2030, 1, 1, 0, 0), TipoFuente.CRAWLER))
                .isInstanceOf(IllegalStateException.class);
        stored.setTitulo("Título actualizado");
        entityManager.flush();
        entityManager.clear();

        var reloaded = audits.findById(historical.getId()).orElseThrow();
        assertThat(reloaded.getTitulo()).isEqualTo("Título actualizado");
        assertThat(reloaded.getVersionMotor()).isEqualTo(historical.getVersionMotor());
        assertThat(reloaded.getVersionReglas()).isEqualTo(historical.getVersionReglas());
        assertThat(reloaded.getFechaAnalisis()).isEqualTo(historical.getFechaAnalisis());
        assertThat(reloaded.getTipoFuente()).isEqualTo(historical.getTipoFuente());
    }

    @Test
    void persistenceRejectsNewAuditWithoutProvenance() {
        var missing = new Auditoria();
        missing.setUsuario(user("missing-provenance"));
        missing.setTitulo("Sin metadatos");
        assertThatThrownBy(() -> audits.saveAndFlush(missing))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void incidentProvenanceCannotBeReassignedAfterReload() {
        var audit = audit(user("incident-owner"), "Incidencia");
        var incident = new Incidencia();
        incident.setAuditoria(audit);
        incident.setCategoria("Categoría independiente del ID");
        incident.setSeveridad("MEDIA");
        incident.registrarProcedencia("ALT-19", "TEST_ANALYZER", "test-rules");
        audit.getIncidencias().add(incident);
        audits.saveAndFlush(audit);
        entityManager.clear();

        var loaded = audits.findById(audit.getId()).orElseThrow().getIncidencias().get(0);
        assertThatThrownBy(() -> loaded.registrarProcedencia("R01", "LEGAL_TEXT", "replacement"))
                .isInstanceOf(IllegalStateException.class);
        loaded.setDescripcion("Descripción actualizada");
        entityManager.flush();
        entityManager.clear();

        var reloaded = audits.findById(audit.getId()).orElseThrow().getIncidencias().get(0);
        assertThat(reloaded.getRuleId()).isEqualTo("ALT-19");
        assertThat(reloaded.getMotor()).isEqualTo("TEST_ANALYZER");
        assertThat(reloaded.getVersion()).isEqualTo("test-rules");
    }

    @Test
    void persistenceRejectsIncidentWithoutProvenance() {
        var audit = audit(user("missing-incident"), "Sin procedencia");
        var incident = new Incidencia();
        incident.setAuditoria(audit);
        incident.setSeveridad("MEDIA");
        audit.getIncidencias().add(incident);
        assertThatThrownBy(() -> audits.saveAndFlush(audit))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void serviceRollsBackAuditIncidentsAndHistoryWhenAnalyzerOmitsProvenance() {
        // No surrounding test transaction: observe the service proxy's own rollback.
        Usuario owner = user("rollback-provenance");
        long auditCount = audits.count();
        long historyCount = history.count();
        long incidentCount = incidents.count();
        var request = new AuditoriaRequest();
        request.setTitulo("Rollback");
        request.setTextoOriginal("Texto manual");
        request.setTipoDocumento("Aviso Legal");
        var invalidFinding = new ResultadoAuditoria.ErrorAuditoria();
        invalidFinding.setTitulo("Finding without provenance");
        invalidFinding.setSeveridad("MEDIA");
        var result = new ResultadoAuditoria();
        result.setPuntuacionRiesgo(25);
        result.setErrores(List.of(invalidFinding));
        when(motor.version()).thenReturn(new VersionAnalizador("test-engine", "test-rules"));
        when(motor.analyze(any())).thenReturn(result);
        try {
            assertThatThrownBy(() -> service.crearAuditoria(request, owner, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(audits.count()).isEqualTo(auditCount);
            assertThat(history.count()).isEqualTo(historyCount);
            assertThat(incidents.count()).isEqualTo(incidentCount);
            assertThat(audits.findByUsuarioId(owner.getId(), org.springframework.data.domain.Pageable.unpaged()))
                    .isEmpty();
        } finally {
            users.deleteById(owner.getId());
        }
    }

    private Usuario user(String name) {
        Usuario user = new Usuario();
        user.setNombre(name);
        user.setEmail(name + "@example.invalid");
        user.setPassword("unused-test-fixture");
        return users.saveAndFlush(user);
    }

    private Auditoria audit(Usuario owner, String title) {
        Auditoria audit = new Auditoria();
        audit.registrarProcedencia("historical-engine", "historical-rules",
                LocalDateTime.of(2025, 1, 1, 12, 0), TipoFuente.MANUAL);
        audit.setUsuario(owner);
        audit.setTitulo(title);
        audit.setTextoOriginal("Texto de prueba");
        audits.saveAndFlush(audit);
        // Equal timestamps exercise the ID tie-breaker without sleeps.
        audit.setFechaCreacion(LocalDateTime.of(2025, 1, 1, 12, 0));
        return audit;
    }
}
