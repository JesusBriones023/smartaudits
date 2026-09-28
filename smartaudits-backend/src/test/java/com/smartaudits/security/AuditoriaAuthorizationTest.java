package com.smartaudits.security;

import com.smartaudits.config.SecurityConfig;
import com.smartaudits.controller.AuditoriaController;
import com.smartaudits.model.Auditoria;
import com.smartaudits.model.Role;
import com.smartaudits.model.Usuario;
import com.smartaudits.repository.AuditoriaRepository;
import com.smartaudits.repository.UsuarioRepository;
import com.smartaudits.service.AuditoriaService;
import com.smartaudits.service.HistorialService;
import com.smartaudits.service.UsuarioService;
import com.smartaudits.service.motor.AnalizadorLegal;
import com.smartaudits.service.AuditQuotaService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Encoders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Controller, audit service, JWT and security chain are real. Persistence is isolated.
@WebMvcTest(AuditoriaController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtUtil.class,
        CustomUserDetailsService.class, AuditoriaService.class})
class AuditoriaAuthorizationTest {
    private static final SecretKey KEY = Jwts.SIG.HS256.key().build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jwt.secret", () -> Encoders.BASE64.encode(KEY.getEncoded()));
    }

    @Autowired MockMvc mvc;
    @Autowired JwtUtil jwt;

    @MockBean UsuarioRepository users;
    @MockBean UsuarioService userService;
    @MockBean AuditoriaRepository audits;
    @MockBean HistorialService history;
    @MockBean AnalizadorLegal motor;
    @MockBean AuditQuotaService auditQuotaService;
    Auditoria audit;

    @BeforeEach
    void setup() {
        Usuario owner = new Usuario();
        owner.setId(1000L);
        owner.setEmail("owner@example.invalid");
        audit = new Auditoria();
        audit.setId(7L);
        audit.setUsuario(owner);
        audit.setTitulo("Auditoria de prueba");
        audit.setResultadoJson("{\"resumen\":\"Resultado de prueba\"}");
        when(audits.findById(7L)).thenReturn(Optional.of(audit));
    }

    String bearer(Long id, Role role) {
        Usuario actor = new Usuario();
        actor.setId(id);
        actor.setEmail("actor@example.invalid");
        actor.setRole(role);
        when(users.findById(id)).thenReturn(Optional.of(actor));
        when(userService.obtenerPorId(id)).thenReturn(Optional.of(actor));
        return "Bearer " + jwt.generateToken(new CustomUserDetails(actor));
    }

    @Test
    void ownerCanReadAuditByStableId() throws Exception {
        // Different objects and emails, but the same stable owner ID.
        mvc.perform(get("/auditorias/7").header("Authorization", bearer(1000L, Role.CLIENTE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.usuarioId").value(1000))
                .andExpect(jsonPath("$.resultado.resumen").value("Resultado de prueba"));
        verify(history).registrarConsulta(same(audit), argThat(u -> u.getId().equals(1000L)), anyString());
    }

    @Test
    void adminCanReadAnotherUsersAudit() throws Exception {
        mvc.perform(get("/auditorias/7").header("Authorization", bearer(2000L, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usuarioId").value(1000));
        verify(history).registrarConsulta(same(audit), argThat(u -> u.getId().equals(2000L)), anyString());
    }

    @Test
    void authenticatedNonOwnerReceives403BeforeReadingResultOrRecordingHistory() throws Exception {
        audit.setResultadoJson("invalid-json-must-not-be-read");
        mvc.perform(get("/auditorias/7").header("Authorization", bearer(3000L, Role.CLIENTE)))
                .andExpect(status().isForbidden())
                .andExpect(content().string(""));
        verify(audits).findById(7L);
        verifyNoInteractions(history, motor);
    }

    @Test
    void unauthenticatedRequestReceives401BeforeReachingAuditService() throws Exception {
        mvc.perform(get("/auditorias/7"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(audits, userService, history, motor);
    }
    @Test
    void ownerCanRegisterDownload() throws Exception {
        mvc.perform(post("/auditorias/7/descarga")
                    .header("Authorization", bearer(1000L, Role.CLIENTE)))
                .andExpect(status().isOk());

        verify(history).registrarDescarga(
            same(audit),
            argThat(u -> u.getId().equals(1000L)),
            anyString()
        );
    }

    @Test
    void adminCanRegisterDownloadForAnotherUsersAudit() throws Exception {
        mvc.perform(post("/auditorias/7/descarga")
                    .header("Authorization", bearer(2000L, Role.ADMIN)))
                .andExpect(status().isOk());

        verify(history).registrarDescarga(
            same(audit),
            argThat(u -> u.getId().equals(2000L)),
            anyString()
        );
    }

    @Test
    void authenticatedNonOwnerCannotRegisterDownload() throws Exception {
        mvc.perform(post("/auditorias/7/descarga")
                    .header("Authorization", bearer(3000L, Role.CLIENTE)))
                .andExpect(status().isForbidden())
                .andExpect(content().string(""));

        verify(audits).findById(7L);
        verifyNoInteractions(history, motor);
    }

    @Test
    void unauthenticatedUserCannotRegisterDownload() throws Exception {
        mvc.perform(post("/auditorias/7/descarga"))
            .andExpect(status().isUnauthorized());

        verifyNoInteractions(audits, userService, history, motor);
    }
}
