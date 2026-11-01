package com.smartaudits.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartaudits.model.Role;
import com.smartaudits.model.Usuario;
import com.smartaudits.repository.AuditoriaConjuntaRepository;
import com.smartaudits.repository.AuditoriaRepository;
import com.smartaudits.repository.UsuarioRepository;
import com.smartaudits.service.motor.MotorAnalisisLegal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import static com.smartaudits.service.AuditoriaConjuntaIntegrationTest.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The daily quota counts one audit per document and is checked for the whole batch first. */
@DataJpaTest(showSql = false, properties = {
        "spring.config.location=optional:classpath:/phase21-no-config.properties",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
        "spring.sql.init.mode=never", "app.audit.daily-limit=2",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "logging.level.root=WARN", "logging.level.org.springframework=WARN"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Import({AuditoriaService.class, HistorialService.class, AuditQuotaService.class,
        MotorAnalisisLegal.class, ObjectMapper.class, AuditoriaConjuntaService.class})
class AuditoriaConjuntaQuotaTest {
    @Autowired AuditoriaConjuntaService service;
    @Autowired AuditoriaRepository audits;
    @Autowired AuditoriaConjuntaRepository groups;
    @Autowired UsuarioRepository users;

    @Test
    void aBatchLargerThanTheRemainingQuotaIsRejectedWhole() {
        Usuario owner = new Usuario();
        owner.setNombre("Cuota");
        owner.setEmail("quota@example.invalid");
        owner.setPassword("unused-test-fixture");
        owner.setRole(Role.CLIENTE);
        users.saveAndFlush(owner);

        assertThatThrownBy(() -> service.crear(request("Tres", doc("P", "Política de Privacidad", PRIVACIDAD),
                doc("C", "Política de Cookies", COOKIES), doc("A", "Aviso Legal", AVISO)), owner, "192.0.2.41"))
                .isInstanceOf(AuditQuotaExceededException.class);
        assertThat(audits.count()).isZero();
        assertThat(groups.count()).isZero();

        assertThat(service.crear(request("Dos", doc("P", "Política de Privacidad", PRIVACIDAD),
                doc("C", "Política de Cookies", COOKIES)), owner, "192.0.2.41").documentosAnalizados()).isEqualTo(2);
        assertThatThrownBy(() -> service.crear(request("Una más", doc("A", "Aviso Legal", AVISO)), owner, "192.0.2.41"))
                .isInstanceOf(AuditQuotaExceededException.class);
        assertThat(audits.count()).isEqualTo(2);
    }
}
