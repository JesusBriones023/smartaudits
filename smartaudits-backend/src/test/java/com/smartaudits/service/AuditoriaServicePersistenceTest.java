package com.smartaudits.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartaudits.model.Auditoria;
import com.smartaudits.model.Usuario;
import com.smartaudits.model.dto.AuditoriaRequest;
import com.smartaudits.model.dto.AuditoriaResponse;
import com.smartaudits.model.dto.ResultadoAuditoria;
import com.smartaudits.repository.AuditoriaRepository;
import com.smartaudits.repository.HistorialAuditoriaRepository;
import com.smartaudits.repository.UsuarioRepository;
import com.smartaudits.service.motor.AnalizadorLegal;
import com.smartaudits.service.motor.EntradaAnalisis;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
        analysis.setResumen("Falta información sobre conservación y derechos.");
        analysis.setPuntuacionRiesgo(62);
        analysis.setRiesgos(List.of("Conservación sin definir"));
        analysis.setRecomendaciones(List.of("Definir el plazo", "Explicar los derechos"));
        analysis.setTextosSugeridos(List.of("Conservaremos los datos durante el plazo indicado."));
        analysis.setReferenciasLegales(List.of("Referencia de prueba"));
        analysis.setFaltantes(List.of("Plazo de conservación"));
        var error = new ResultadoAuditoria.ErrorAuditoria(
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
        assertThat(stored.getPuntuacionRiesgo()).isEqualTo(62);
        assertThat(stored.getFechaCreacion()).isBetween(before, LocalDateTime.now().plusSeconds(1));
        assertThat(mapper.readValue(stored.getResultadoJson(), ResultadoAuditoria.class)).isEqualTo(analysis);
        assertThat(stored.getResultado()).satisfies(result -> {
            assertThat(result.getId()).isNotNull();
            assertThat(result.getAuditoria().getId()).isEqualTo(stored.getId());
            assertThat(result.getResumenGeneral()).isEqualTo(analysis.getResumen());
            assertThat(result.getPuntuacionCumplimiento()).isEqualTo(62);
            assertThat(result.getRecomendacionesGenerales()).isEqualTo("Definir el plazo | Explicar los derechos");
            assertThat(result.getFechaResultado()).isBetween(before, LocalDateTime.now().plusSeconds(1));
        });
        assertThat(stored.getIncidencias()).singleElement().satisfies(incident -> {
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
        AnalizadorLegal alternative = input -> {
            received.add(input);
            return analysis;
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
            assertThat(response.getUsuarioId()).isEqualTo(owner.getId());
            assertThat(response.getUrlOpcional()).isEqualTo(request.getUrlOpcional());
        }

        entityManager.flush();
        entityManager.clear();
        assertThat(received).containsExactly(content, content);
        var stored = audits.findAll();
        assertThat(stored).hasSize(2);
        for (Auditoria audit : stored) {
            assertThat(audit.getPuntuacionRiesgo()).isEqualTo(37);
            assertThat(audit.getTextoOriginal()).isEqualTo(content.texto());
            assertThat(mapper.readValue(audit.getResultadoJson(), ResultadoAuditoria.class)).isEqualTo(analysis);
        }
        assertThat(history.findAll()).hasSize(2).allSatisfy(event ->
                assertThat(event.getAccion()).isEqualTo("CREACION"));
        verifyNoInteractions(motor);
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
        audit.setUsuario(owner);
        audit.setTitulo(title);
        audit.setTextoOriginal("Texto de prueba");
        audits.saveAndFlush(audit);
        // Equal timestamps exercise the ID tie-breaker without sleeps.
        audit.setFechaCreacion(LocalDateTime.of(2025, 1, 1, 12, 0));
        return audit;
    }
}
