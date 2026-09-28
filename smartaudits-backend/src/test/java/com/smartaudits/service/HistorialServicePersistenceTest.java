package com.smartaudits.service;

import com.smartaudits.model.Auditoria;
import com.smartaudits.model.Role;
import com.smartaudits.model.Usuario;
import com.smartaudits.model.TipoFuente;
import com.smartaudits.repository.AuditoriaRepository;
import com.smartaudits.repository.HistorialAuditoriaRepository;
import com.smartaudits.repository.UsuarioRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(showSql = false, properties = {
        "spring.config.location=optional:classpath:/phase11-no-config.properties",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
        "spring.sql.init.mode=never",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "logging.level.root=WARN", "logging.level.org.springframework=WARN"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Import(HistorialService.class)
class HistorialServicePersistenceTest {
    @Autowired HistorialService service;
    @Autowired HistorialAuditoriaRepository history;
    @Autowired AuditoriaRepository audits;
    @Autowired UsuarioRepository users;
    @Autowired TestEntityManager entityManager;

    @ParameterizedTest(name = "{0}: persists audit, actor, action, timestamp and IP")
    @ValueSource(strings = {"CREACION", "CONSULTA", "DESCARGA"})
    void recordsAndRetrievesEachActionWithItsActualActor(String action) {
        Usuario owner = user("owner", Role.CLIENTE);
        Usuario actor = action.equals("CREACION") ? owner : user("admin", Role.ADMIN);
        Auditoria audit = new Auditoria();
        audit.registrarProcedencia("history-test", "history-rules",
                LocalDateTime.of(2025, 1, 1, 12, 0), TipoFuente.MANUAL);
        audit.setUsuario(owner);
        audit.setTitulo("Auditoría del propietario");
        audits.saveAndFlush(audit);
        String ip = action.equals("DESCARGA") ? null : "192.0.2.20";
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);

        switch (action) {
            case "CREACION" -> service.registrarCreacion(audit, actor, ip);
            case "CONSULTA" -> service.registrarConsulta(audit, actor, ip);
            case "DESCARGA" -> service.registrarDescarga(audit, actor, ip);
            default -> throw new AssertionError("Unexpected test action");
        }
        entityManager.flush();
        entityManager.clear();

        assertThat(history.count()).isEqualTo(1);
        var entries = history.findByAuditoriaIdOrderByFechaAccesoDesc(audit.getId());
        assertThat(entries).singleElement().satisfies(event -> {
            assertThat(event.getId()).isNotNull();
            assertThat(event.getAuditoria().getId()).isEqualTo(audit.getId());
            assertThat(event.getUsuario().getId()).isEqualTo(actor.getId());
            assertThat(event.getAccion()).isEqualTo(action);
            assertThat(event.getFechaAcceso()).isBetween(before, LocalDateTime.now().plusSeconds(1));
            assertThat(event.getIpAcceso()).isEqualTo(ip == null ? "desconocida" : ip);
        });
        assertThat(history.findByUsuarioIdOrderByFechaAccesoDesc(actor.getId()))
                .extracting(event -> event.getId()).containsExactly(entries.get(0).getId());
        if (!actor.getId().equals(owner.getId())) {
            assertThat(history.findByUsuarioIdOrderByFechaAccesoDesc(owner.getId())).isEmpty();
        }
    }

    private Usuario user(String name, Role role) {
        Usuario user = new Usuario();
        user.setNombre(name);
        user.setEmail(name + "@example.invalid");
        user.setPassword("unused-test-fixture");
        user.setRole(role);
        return users.saveAndFlush(user);
    }
}
