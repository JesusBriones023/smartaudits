package com.smartaudits.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartaudits.config.SecurityConfig;
import com.smartaudits.controller.AuditoriaController;
import com.smartaudits.model.Auditoria;
import com.smartaudits.model.Role;
import com.smartaudits.model.TipoFuente;
import com.smartaudits.model.Usuario;
import com.smartaudits.model.dto.ResultadoAuditoria;
import com.smartaudits.repository.AuditoriaRepository;
import com.smartaudits.repository.UsuarioRepository;
import com.smartaudits.service.AuditQuotaService;
import com.smartaudits.service.AuditoriaService;
import com.smartaudits.service.HistorialService;
import com.smartaudits.service.UsuarioService;
import com.smartaudits.service.html.ExtractorTextoHtml;
import com.smartaudits.service.motor.AnalizadorLegal;
import com.smartaudits.service.motor.EntradaAnalisis;
import com.smartaudits.service.motor.VersionAnalizador;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Encoders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// POST /auditorias/html with the real controller, validation, JWT chain and audit service; persistence isolated.
@WebMvcTest(AuditoriaController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtUtil.class,
        CustomUserDetailsService.class, AuditoriaService.class})
class AuditoriaHtmlEndpointTest {
    private static final SecretKey KEY = Jwts.SIG.HS256.key().build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jwt.secret", () -> Encoders.BASE64.encode(KEY.getEncoded()));
    }

    @Autowired MockMvc mvc;
    @Autowired JwtUtil jwt;
    @Autowired ObjectMapper mapper;

    @MockBean UsuarioRepository users;
    @MockBean UsuarioService userService;
    @MockBean AuditoriaRepository audits;
    @MockBean HistorialService history;
    @MockBean AnalizadorLegal motor;
    @MockBean AuditQuotaService auditQuotaService;

    @BeforeEach
    void setup() {
        var analysis = new ResultadoAuditoria();
        analysis.setResumen("Resultado HTML");
        analysis.setPuntuacionRiesgo(40);
        analysis.setErrores(List.of());
        when(motor.version()).thenReturn(new VersionAnalizador("2", "1"));
        when(motor.analyze(any())).thenReturn(analysis);
        when(audits.save(any(Auditoria.class))).thenAnswer(invocation -> {
            Auditoria value = invocation.getArgument(0);
            value.setId(9L);
            value.setFechaCreacion(LocalDateTime.of(2026, 1, 2, 12, 0));
            return value;
        });
    }

    String bearer(Long id) {
        Usuario actor = new Usuario();
        actor.setId(id);
        actor.setEmail("actor@example.invalid");
        actor.setRole(Role.CLIENTE);
        when(users.findById(id)).thenReturn(Optional.of(actor));
        when(userService.obtenerPorId(id)).thenReturn(Optional.of(actor));
        return "Bearer " + jwt.generateToken(new CustomUserDetails(actor));
    }

    String body(String html) throws Exception {
        return mapper.writeValueAsString(Map.of("titulo", "Auditoría HTML", "tipoDocumento", "Aviso Legal",
                "html", html, "urlOpcional", "https://example.invalid/aviso"));
    }

    @Test
    void createsAnHtmlAuditAnalysingOnlyTheExtractedText() throws Exception {
        String html = "<h1>Aviso Legal</h1><script>alert(1)</script><p onclick=\"x()\">Titular: Ejemplo S.L.</p>";

        JsonNode created = mapper.readTree(mvc.perform(post("/auditorias/html")
                        .header("Authorization", bearer(1000L)).contentType("application/json").content(body(html)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        var input = ArgumentCaptor.forClass(EntradaAnalisis.class);
        verify(motor).analyze(input.capture());
        assertThat(input.getValue()).isEqualTo(new EntradaAnalisis("Aviso Legal\n\nTitular: Ejemplo S.L.", "Aviso Legal"));
        assertThat(created.path("tipoFuente").asText()).isEqualTo(TipoFuente.MANUAL.name());
        assertThat(created.path("textoOriginal").asText()).isEqualTo("Aviso Legal\n\nTitular: Ejemplo S.L.");
        assertThat(created.path("urlOpcional").asText()).isEqualTo("https://example.invalid/aviso");
        assertThat(created.path("resultado").path("resumen").asText()).isEqualTo("Resultado HTML");
        verify(history).registrarCreacion(any(), any(), any());
    }

    @Test
    void unauthenticatedRequestIsRejectedBeforeAnyAnalysis() throws Exception {
        mvc.perform(post("/auditorias/html").contentType("application/json").content(body("<p>Texto</p>")))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(motor, audits);
    }

    @Test
    void htmlWithoutReadableTextIsA400AndNothingIsAnalysed() throws Exception {
        mvc.perform(post("/auditorias/html").header("Authorization", bearer(1000L))
                        .contentType("application/json").content(body("<script>alert(1)</script>")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_HTML"))
                .andExpect(jsonPath("$.message").value("El HTML no contiene texto legible para auditar"));
        verifyNoInteractions(motor, audits);
    }

    @Test
    void htmlOverTheSizeLimitIsA400ValidationError() throws Exception {
        String html = "<p>" + "x".repeat(ExtractorTextoHtml.MAX_HTML_LENGTH) + "</p>";

        mvc.perform(post("/auditorias/html").header("Authorization", bearer(1000L))
                        .contentType("application/json").content(body(html)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.html").value("El HTML no puede superar los 500000 caracteres"));
        verifyNoInteractions(motor, audits);
    }

    @Test
    void missingHtmlAndNonHttpUrlAreValidationErrors() throws Exception {
        mvc.perform(post("/auditorias/html").header("Authorization", bearer(1000L)).contentType("application/json")
                        .content("{\"titulo\":\"t\",\"tipoDocumento\":\"Aviso Legal\",\"html\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.html").value("El HTML a auditar es obligatorio"));
        mvc.perform(post("/auditorias/html").header("Authorization", bearer(1000L)).contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("titulo", "t", "tipoDocumento", "Aviso Legal",
                                "html", "<p>Texto</p>", "urlOpcional", "file:///etc/passwd"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.urlOpcional").exists());
        verifyNoInteractions(motor, audits);
    }

    @Test
    void legalLinkDetectionReturnsCandidatesWithoutCreatingAnAudit() throws Exception {
        String html = "<footer><a href=\"/privacidad\">Política de privacidad</a>"
                + "<a href=\"javascript:alert(1)\">Cookies</a><a href=\"https://otra.example.invalid/legal\">Aviso legal</a></footer>";

        JsonNode found = mapper.readTree(mvc.perform(post("/auditorias/html/enlaces-legales")
                        .header("Authorization", bearer(1000L)).contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("html", html, "urlOpcional", "https://www.ejemplo.es/"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(found.path("detectados")).hasSize(2);
        assertThat(found.path("detectados").get(0).path("categoria").asText()).isEqualTo("POLITICA_PRIVACIDAD");
        assertThat(found.path("detectados").get(0).path("url").asText()).isEqualTo("https://www.ejemplo.es/privacidad");
        assertThat(found.path("detectados").get(0).path("externo").asBoolean()).isFalse();
        assertThat(found.path("detectados").get(1).path("categoria").asText()).isEqualTo("AVISO_LEGAL");
        assertThat(found.path("detectados").get(1).path("externo").asBoolean()).isTrue();
        assertThat(found.path("noDetectados")).hasSize(5);
        assertThat(found.path("enlacesDescartados").intValue()).isEqualTo(1);
        assertThat(found.path("referenciaAplicada").asBoolean()).isTrue();
        verifyNoInteractions(motor, audits, history, auditQuotaService);
    }

    @Test
    void legalLinkDetectionRequiresAuthenticationAndValidInput() throws Exception {
        mvc.perform(post("/auditorias/html/enlaces-legales").contentType("application/json")
                        .content("{\"html\":\"<a href='/privacidad'>Privacidad</a>\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/auditorias/html/enlaces-legales").header("Authorization", bearer(1000L))
                        .contentType("application/json").content("{\"html\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.html").value("El HTML a auditar es obligatorio"));
        mvc.perform(post("/auditorias/html/enlaces-legales").header("Authorization", bearer(1000L))
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("html", "<p>x</p>", "urlOpcional", "javascript:alert(1)"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.urlOpcional").exists());
        mvc.perform(post("/auditorias/html/enlaces-legales").header("Authorization", bearer(1000L))
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("html", "x".repeat(ExtractorTextoHtml.MAX_HTML_LENGTH + 1)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.html").value("El HTML no puede superar los 500000 caracteres"));
        verifyNoInteractions(motor, audits, history, auditQuotaService);
    }

    @Test
    void manualCreationKeepsItsContract() throws Exception {
        mvc.perform(post("/auditorias").header("Authorization", bearer(1000L)).contentType("application/json")
                        .content("{\"titulo\":\"Manual\",\"tipoDocumento\":\"Aviso Legal\",\"textoOriginal\":\"<b>literal</b>\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tipoFuente").value("MANUAL"))
                .andExpect(jsonPath("$.textoOriginal").value("<b>literal</b>"));
        verify(motor).analyze(new EntradaAnalisis("<b>literal</b>", "Aviso Legal"));
    }
}
