package com.smartaudits.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartaudits.config.SecurityConfig;
import com.smartaudits.controller.AuditoriaConjuntaController;
import com.smartaudits.model.Auditoria;
import com.smartaudits.model.AuditoriaConjunta;
import com.smartaudits.model.Role;
import com.smartaudits.model.Usuario;
import com.smartaudits.model.dto.ResultadoAuditoria;
import com.smartaudits.repository.AuditoriaConjuntaRepository;
import com.smartaudits.repository.AuditoriaRepository;
import com.smartaudits.repository.UsuarioRepository;
import com.smartaudits.service.AuditQuotaService;
import com.smartaudits.service.AuditoriaConjuntaService;
import com.smartaudits.service.AuditoriaService;
import com.smartaudits.service.HistorialService;
import com.smartaudits.service.UsuarioService;
import com.smartaudits.service.motor.AnalizadorLegal;
import com.smartaudits.service.motor.VersionAnalizador;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Real controller, validation, JWT chain and both audit services; persistence isolated.
@WebMvcTest(AuditoriaConjuntaController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtUtil.class,
        CustomUserDetailsService.class, AuditoriaService.class, AuditoriaConjuntaService.class})
class AuditoriaConjuntaEndpointTest {
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
    @MockBean AuditoriaConjuntaRepository groups;
    @MockBean HistorialService history;
    @MockBean AnalizadorLegal motor;
    @MockBean AuditQuotaService auditQuotaService;

    final List<Auditoria> saved = new ArrayList<>();

    @BeforeEach
    void setup() {
        var analysis = new ResultadoAuditoria();
        analysis.setResumen("Resumen");
        analysis.setPuntuacionRiesgo(30);
        analysis.setRecomendaciones(List.of("Revisar plazos"));
        analysis.setErrores(List.of(new ResultadoAuditoria.ErrorAuditoria(
                "R05", "LEGAL_TEXT", "1", "Plazo", "Falta el plazo", "MEDIA", "Evidencia", "Impacto", "Acción")));
        when(motor.version()).thenReturn(new VersionAnalizador("2", "1"));
        when(motor.analyze(any())).thenReturn(analysis);
        AtomicLong ids = new AtomicLong(100);
        when(audits.save(any(Auditoria.class))).thenAnswer(invocation -> {
            Auditoria value = invocation.getArgument(0);
            if (value.getId() == null) {
                value.setId(ids.incrementAndGet());
                value.setFechaCreacion(LocalDateTime.of(2026, 1, 2, 12, 0));
                saved.add(value);
            }
            return value;
        });
        when(groups.saveAndFlush(any(AuditoriaConjunta.class))).thenAnswer(invocation -> {
            AuditoriaConjunta value = invocation.getArgument(0);
            value.setId(7L);
            return value;
        });
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

    static Map<String, Object> doc(String titulo, String tipo) {
        return Map.of("titulo", titulo, "tipoDocumento", tipo, "html", "<h1>" + titulo + "</h1><p>Texto legal.</p>");
    }

    String body(Object documentos) throws Exception {
        return mapper.writeValueAsString(Map.of("titulo", "Web de prueba", "documentos", documentos));
    }

    @Test
    void createsAJointAuditWithOneAuditPerDocument() throws Exception {
        JsonNode created = mapper.readTree(mvc.perform(post("/auditorias-conjuntas")
                        .header("Authorization", bearer(1000L, Role.CLIENTE)).contentType("application/json")
                        .content(body(List.of(doc("Privacidad", "Política de Privacidad"), doc("Cookies", "Política de Cookies")))))
                .andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(fieldNames(created)).containsExactlyInAnyOrder("id", "titulo", "fechaCreacion", "usuarioId",
                "documentosAnalizados", "documentos", "recomendaciones", "categoriasNoAportadas");
        assertThat(created.has("puntuacionGlobal")).isFalse();
        assertThat(created.path("documentosAnalizados").intValue()).isEqualTo(2);
        assertThat(created.path("documentos").get(0).path("auditoriaId").longValue()).isEqualTo(101L);
        assertThat(created.path("documentos").get(1).path("puntuacionRiesgo").intValue()).isEqualTo(30);
        assertThat(created.path("documentos").get(1).path("incidencias").get(0).path("ruleId").asText()).isEqualTo("R05");
        assertThat(created.path("recomendaciones")).hasSize(1);
        assertThat(created.path("recomendaciones").get(0).path("documentos").toString()).isEqualTo("[\"Privacidad\",\"Cookies\"]");
        assertThat(created.path("categoriasNoAportadas").toString())
                .isEqualTo("[\"Aviso Legal\",\"Términos y Condiciones\",\"Condiciones de Uso\"]");
        verify(motor, times(2)).analyze(any());
        verify(auditQuotaService).verificarPuedeCrear(any(Usuario.class), eq(2));
        verify(history, times(2)).registrarCreacion(any(), any(), any());
        assertThat(saved).allSatisfy(a -> assertThat(a.getTextoOriginal()).doesNotContain("<"));
    }

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        mvc.perform(post("/auditorias-conjuntas").contentType("application/json")
                        .content(body(List.of(doc("A", "Aviso Legal"))))).andExpect(status().isUnauthorized());
        mvc.perform(get("/auditorias-conjuntas/7")).andExpect(status().isUnauthorized());
        mvc.perform(get("/auditorias-conjuntas/mias")).andExpect(status().isUnauthorized());
        verifyNoInteractions(motor, audits, groups);
    }

    @Test
    void invalidBatchesAreRejectedBeforeAnyAnalysis() throws Exception {
        String token = bearer(1000L, Role.CLIENTE);
        List<Object> bodies = List.of(
                List.of(),
                List.of(doc("A", "Aviso Legal"), doc("B", "Política de Cookies"), doc("C", "Política de Privacidad"),
                        doc("D", "Condiciones de Uso"), doc("E", "Términos y Condiciones")),
                List.of(doc("A", "Documento Completo")),
                List.of(Map.of("titulo", "A", "tipoDocumento", "Aviso Legal", "html", " ")),
                List.of(Map.of("titulo", "A", "tipoDocumento", "Aviso Legal", "html", "<p>x</p>", "urlOpcional", "file:///etc/passwd")),
                List.of(Map.of("titulo", "A", "tipoDocumento", "Aviso Legal", "html", "x".repeat(500_001))));
        for (Object documentos : bodies) {
            mvc.perform(post("/auditorias-conjuntas").header("Authorization", token).contentType("application/json")
                            .content(body(documentos)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        }
        mvc.perform(post("/auditorias-conjuntas").header("Authorization", token).contentType("application/json")
                        .content(body(List.of(doc("A", "Aviso Legal"), doc("B", "Aviso Legal")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_HTML"));
        mvc.perform(post("/auditorias-conjuntas").header("Authorization", token).contentType("application/json")
                        .content(body(List.of(doc("A", "Aviso Legal"),
                                Map.of("titulo", "Vacío", "tipoDocumento", "Política de Cookies", "html", "<script>x()</script>")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Documento 2 («Vacío»): El HTML no contiene texto legible para auditar"));
        verifyNoInteractions(motor, audits, groups);
    }

    @Test
    void aNonOwnerCannotReadAJointAudit() throws Exception {
        Usuario owner = new Usuario();
        owner.setId(1000L);
        AuditoriaConjunta group = new AuditoriaConjunta();
        group.setId(7L);
        group.setUsuario(owner);
        group.asignarDocumentos(List.of(101L));
        when(groups.findById(7L)).thenReturn(Optional.of(group));

        mvc.perform(get("/auditorias-conjuntas/7").header("Authorization", bearer(3000L, Role.CLIENTE)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(audits, history);
    }

    void storedGroup() throws Exception {
        Usuario owner = new Usuario();
        owner.setId(1000L);
        owner.setNombre("Propietario");
        owner.setEmail("owner@example.invalid");
        Auditoria audit = new Auditoria();
        audit.setId(101L);
        audit.setUsuario(owner);
        audit.setTitulo("Privacidad");
        audit.setTipoDocumento("Política de Privacidad");
        var analysis = new ResultadoAuditoria();
        analysis.setResumen("Resumen persistido");
        analysis.setRiesgos(List.of("Conservación indefinida"));
        analysis.setRecomendaciones(List.of("Fijar plazos"));
        analysis.setErrores(List.of());
        audit.setResultadoJson(mapper.writeValueAsString(analysis));
        audit.setPuntuacionRiesgo(55);
        when(audits.findById(101L)).thenReturn(Optional.of(audit));
        AuditoriaConjunta group = new AuditoriaConjunta();
        group.setId(7L);
        group.setUsuario(owner);
        group.setTitulo("Web persistida");
        group.asignarDocumentos(List.of(101L));
        when(groups.findById(7L)).thenReturn(Optional.of(group));
    }

    @Test
    void downloadServesThePersistedReportToTheOwnerAndAdmin() throws Exception {
        storedGroup();
        for (var token : List.of(bearer(1000L, Role.CLIENTE), bearer(2000L, Role.ADMIN))) {
            JsonNode report = mapper.readTree(mvc.perform(post("/auditorias-conjuntas/7/descarga").header("Authorization", token))
                    .andExpect(status().isOk()).andReturn().getResponse()
                    .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
            assertThat(report.path("titulo").asText()).isEqualTo("Web persistida");
            assertThat(report.path("documentos").get(0).path("riesgos").toString()).isEqualTo("[\"Conservación indefinida\"]");
            assertThat(report.path("documentos").get(0).path("resumen").asText()).isEqualTo("Resumen persistido");
        }
        verify(history, times(2)).registrarDescarga(any(), any(), any());
        verify(history, never()).registrarConsulta(any(), any(), any());
        verify(audits, never()).save(any());
        verifyNoInteractions(motor);
    }

    @Test
    void downloadIsRejectedWithoutAuthenticationAndForOtherUsers() throws Exception {
        storedGroup();
        mvc.perform(post("/auditorias-conjuntas/7/descarga")).andExpect(status().isUnauthorized());
        mvc.perform(post("/auditorias-conjuntas/7/descarga").header("Authorization", bearer(3000L, Role.CLIENTE)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(history);
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
