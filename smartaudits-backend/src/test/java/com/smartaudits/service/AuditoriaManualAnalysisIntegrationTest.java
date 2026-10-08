package com.smartaudits.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartaudits.model.Usuario;
import com.smartaudits.model.TipoFuente;
import com.smartaudits.model.dto.AuditoriaRequest;
import com.smartaudits.model.dto.ResultadoAuditoria;
import com.smartaudits.repository.AuditoriaRepository;
import com.smartaudits.repository.HistorialAuditoriaRepository;
import com.smartaudits.repository.UsuarioRepository;
import com.smartaudits.service.motor.AnalizadorLegal;
import com.smartaudits.service.motor.EntradaAnalisis;
import com.smartaudits.service.motor.MotorAnalisisLegal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(showSql = false, properties = {
        "spring.config.location=optional:classpath:/phase21-no-config.properties",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
        "spring.sql.init.mode=never",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "logging.level.root=WARN", "logging.level.org.springframework=WARN"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Import({AuditoriaService.class, HistorialService.class, AuditQuotaService.class,
        MotorAnalisisLegal.class, ObjectMapper.class})
class AuditoriaManualAnalysisIntegrationTest {
    @Autowired AuditoriaService service;
    @Autowired AnalizadorLegal analizador;
    @Autowired AuditoriaRepository audits;
    @Autowired UsuarioRepository users;
    @Autowired HistorialAuditoriaRepository history;
    @Autowired TestEntityManager entityManager;
    @Autowired ObjectMapper mapper;

    @Test
    void manualCreationPersistsAndReadsTheRealEngineResultThroughTheContract() throws Exception {
        Usuario owner = new Usuario();
        owner.setNombre("Cliente manual");
        owner.setEmail("manual@example.invalid");
        owner.setPassword("unused-test-fixture");
        users.saveAndFlush(owner);
        AuditoriaRequest request = new AuditoriaRequest();
        request.setTitulo("Auditoría manual");
        request.setTextoOriginal("Responsable del tratamiento: Ejemplo. Contacto: manual@example.invalid.");
        request.setTipoDocumento("Política de Privacidad");
        request.setUrlOpcional("https://example.invalid/privacidad");
        ResultadoAuditoria expected = analizador.analyze(new EntradaAnalisis(
                request.getTextoOriginal(), request.getTipoDocumento()));

        var created = service.crearAuditoria(request, owner, "192.0.2.11");
        entityManager.flush();
        entityManager.clear();

        assertThat(created.getResultado()).isEqualTo(expected);
        assertThat(created.getPuntuacionRiesgo()).isEqualTo(expected.getPuntuacionRiesgo());
        var stored = audits.findById(created.getId()).orElseThrow();
        assertThat(stored.getUsuario().getId()).isEqualTo(owner.getId());
        assertThat(stored.getVersionMotor()).isEqualTo(analizador.version().versionMotor());
        assertThat(stored.getVersionReglas()).isEqualTo(analizador.version().versionReglas());
        assertThat(stored.getTipoFuente()).isEqualTo(TipoFuente.MANUAL);
        assertThat(stored.getFechaAnalisis()).isEqualTo(created.getFechaAnalisis());
        assertThat(stored.getTextoOriginal()).isEqualTo(request.getTextoOriginal());
        assertThat(stored.getTipoDocumento()).isEqualTo(request.getTipoDocumento());
        assertThat(stored.getUrlOpcional()).isEqualTo(request.getUrlOpcional());
        assertThat(mapper.readValue(stored.getResultadoJson(), ResultadoAuditoria.class)).isEqualTo(expected);
        assertThat(stored.getIncidencias()).hasSize(expected.getErrores().size());
        assertThat(stored.getPuntuacionRiesgo()).isEqualTo(expected.getPuntuacionRiesgo());

        var read = service.obtenerAuditoriaPorId(created.getId(), owner.getId(), false, owner, "192.0.2.11");
        assertThat(read.getResultado()).isEqualTo(expected);
        assertThat(read.getVersionMotor()).isEqualTo(stored.getVersionMotor());
        assertThat(read.getVersionReglas()).isEqualTo(stored.getVersionReglas());
        assertThat(read.getFechaAnalisis()).isEqualTo(stored.getFechaAnalisis());
        assertThat(read.getTipoFuente()).isEqualTo(stored.getTipoFuente());
        assertThat(history.findAll()).extracting(event -> event.getAccion())
                .containsExactlyInAnyOrder("CREACION", "CONSULTA");
    }
}
