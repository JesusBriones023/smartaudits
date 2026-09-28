package com.smartaudits.security;

import com.smartaudits.config.SecurityConfig;
import com.smartaudits.controller.AuthController;
import com.smartaudits.controller.AuditoriaController;
import com.smartaudits.controller.HistorialAdminController;
import com.smartaudits.model.Auditoria;
import com.smartaudits.repository.AuditoriaRepository;
import com.smartaudits.service.AuditoriaService;
import com.smartaudits.service.HistorialService;
import com.smartaudits.service.HistorialAdminService;
import com.smartaudits.controller.UsuarioController;
import com.smartaudits.model.Role;
import com.smartaudits.model.Usuario;
import com.smartaudits.repository.UsuarioRepository;
import com.smartaudits.service.UsuarioService;
import com.smartaudits.controller.ApiExceptionHandler;
import com.smartaudits.model.dto.PaginaResponse;
import com.smartaudits.security.AuthRateLimitService;
import com.smartaudits.security.RateLimitExceededException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Encoders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import javax.crypto.SecretKey;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {AuthController.class, UsuarioController.class, AuditoriaController.class, HistorialAdminController.class}, properties = {
        "logging.level.root=WARN", "logging.level.org.springframework=WARN"})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtUtil.class, CustomUserDetailsService.class, ApiExceptionHandler.class})
class JwtSecurityTest {
    private static final SecretKey KEY = Jwts.SIG.HS512.key().build();
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jwt.secret", () -> Encoders.BASE64.encode(KEY.getEncoded()));
        registry.add("jwt.issuer", () -> "test-issuer");
        registry.add("jwt.audience", () -> "test-api");
    }
    @Autowired MockMvc mvc;
    @Autowired JwtUtil jwt;
    @Autowired AuthenticationManager authenticationManager;
    @MockBean UsuarioRepository users;
    @MockBean UsuarioService service;
    @MockBean AuditoriaService audits;
    @MockBean HistorialService auditHistory;
    @MockBean HistorialAdminService adminHistory;
    @MockBean AuditoriaRepository auditRepository;
    @MockBean AuthRateLimitService authRateLimitService;
    Usuario user;

    @BeforeEach void setup() {
        user = new Usuario();
        user.setId(10L);
        user.setNombre("Usuario de prueba");
        user.setEmail("first@example.invalid");
        user.setRole(Role.ADMIN);
        when(users.findById(10L)).thenAnswer(inv -> Optional.of(user));
        when(service.obtenerPorId(10L)).thenAnswer(inv -> Optional.of(user));
        when(service.listarTodos()).thenReturn(List.of());
    }

    io.jsonwebtoken.JwtBuilder builder() {
        return Jwts.builder().issuer("test-issuer").audience().add("test-api").and();
    }

    String token() { return jwt.generateToken(new CustomUserDetails(user)); }

    @Test void activeUserWithValidTokenCanAccess() throws Exception {
        mvc.perform(get("/usuarios").header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk());
        verify(service).listarTodos();
    }

    @Test void disabledUserCannotUsePreviouslyIssuedToken() throws Exception {
        String token = token();
        user.setActivo(false);
        mvc.perform(get("/usuarios").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test void authenticationProviderAlsoRejectsDisabledLogin() {
        user.setActivo(false);
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        assertThatThrownBy(() -> authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(user.getEmail(), "unused-test-input")))
                .isInstanceOf(DisabledException.class);
    }

    @Test void staleVersionCannotAccess() throws Exception {
        String token = token();
        user.setTokenVersion(user.getTokenVersion() + 1);
        mvc.perform(get("/usuarios").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test void clientCannotAccessAdminListingButCanDeactivateOwnAccount() throws Exception {
        user.setRole(Role.CLIENTE);
        String token = token();
        mvc.perform(get("/usuarios").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/auth/baja").param("userId", "999")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        verify(service).desactivarUsuario(10L);
        verify(service, never()).desactivarUsuario(999L);
        verify(service, never()).listarTodos();
    }

    @Test void currentDatabaseRoleOverridesRoleClaim() throws Exception {
        String adminToken = token();
        user.setRole(Role.CLIENTE); // Incluso si una modificación externa no incrementa versión.
        mvc.perform(get("/usuarios").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden());
        verify(service, never()).listarTodos();
    }

    @Test void identityRemainsStableWhenEmailIsReassigned() throws Exception {
        String token = token();
        String oldEmail = user.getEmail();
        user.setEmail("changed@example.invalid");
        Usuario other = new Usuario();
        other.setId(99L);
        other.setEmail(oldEmail);
        assertThat(jwt.isTokenValid(token, new CustomUserDetails(other))).isFalse();
        mvc.perform(delete("/auth/baja").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        verify(service).desactivarUsuario(10L);
        verify(users, never()).findByEmail(anyString());
    }

    @Test void legacyTokenWithoutVersionIsRejected() throws Exception {
        String legacy = builder().subject(user.getEmail()).claim("userId", user.getId())
                .expiration(new Date(System.currentTimeMillis() + 60_000)).signWith(KEY, Jwts.SIG.HS256).compact();
        mvc.perform(get("/usuarios").header("Authorization", "Bearer " + legacy))
                .andExpect(status().isUnauthorized());
        String missingVersion = builder().subject("10").claim("userId", 10L)
                .expiration(new Date(System.currentTimeMillis() + 60_000)).signWith(KEY, Jwts.SIG.HS256).compact();
        assertThat(jwt.isTokenValid(missingVersion, new CustomUserDetails(user))).isFalse();
    }

    @Test void mismatchedSubjectAndUserClaimAreRejected() throws Exception {
        String token = builder().subject("10").claim("userId", 99L).claim("tokenVersion", 0L)
                .expiration(new Date(System.currentTimeMillis() + 60_000)).signWith(KEY, Jwts.SIG.HS256).compact();
        mvc.perform(get("/usuarios").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test void expiredWrongSignatureAndMalformedTokensAreRejected() throws Exception {
        String expired = builder().subject("10").claim("userId", 10L).claim("tokenVersion", 0L)
                .expiration(new Date(System.currentTimeMillis() - 60_000)).signWith(KEY, Jwts.SIG.HS256).compact();
        String forged = builder().subject("10").claim("userId", 10L).claim("tokenVersion", 0L)
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Jwts.SIG.HS256.key().build()).compact();
        for (String invalid : List.of(expired, forged, "malformed")) {
            mvc.perform(get("/usuarios").header("Authorization", "Bearer " + invalid))
                    .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(service);
    }

    @Test void onlyExpectedAlgorithmIsAcceptedEvenWithLongKey() throws Exception {
        String accepted = token();
        assertThat(Jwts.parser().verifyWith(KEY).build().parseSignedClaims(accepted)
                .getHeader().getAlgorithm()).isEqualTo("HS256");
        for (var algorithm : List.of(Jwts.SIG.HS384, Jwts.SIG.HS512)) {
            String different = builder().subject("10").claim("userId", 10L).claim("tokenVersion", 0L)
                    .expiration(new Date(System.currentTimeMillis() + 60_000))
                    .signWith(KEY, algorithm).compact();
            // The signature is valid with this same key; rejection must be algorithm policy.
            assertThat(Jwts.parser().verifyWith(KEY).build().parseSignedClaims(different)).isNotNull();
            mvc.perform(get("/usuarios").header("Authorization", "Bearer " + different))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/usuarios").header("Authorization", "Bearer " + accepted))
                .andExpect(status().isOk());
    }

    @Test void issuerAudienceExpirationAndClaimTypesAreRequired() throws Exception {
        var base = builder().subject("10").claim("userId", 10L).claim("tokenVersion", 0L)
                .expiration(new Date(System.currentTimeMillis() + 60_000));
        for (String invalid : List.of(
                base.issuer("other-service").signWith(KEY, Jwts.SIG.HS256).compact(),
                base.issuer("test-issuer").audience().clear().add("other-api").and()
                        .signWith(KEY, Jwts.SIG.HS256).compact(),
                base.audience().clear().and().signWith(KEY, Jwts.SIG.HS256).compact(),
                base.audience().add("test-api").and().issuer(null).signWith(KEY, Jwts.SIG.HS256).compact(),
                base.issuer("test-issuer").expiration(null).signWith(KEY, Jwts.SIG.HS256).compact(),
                base.expiration(new Date(System.currentTimeMillis() + 60_000)).claim("tokenVersion", "0")
                        .signWith(KEY, Jwts.SIG.HS256).compact(),
                base.claim("tokenVersion", 0L).claim("userId", 10.5)
                        .signWith(KEY, Jwts.SIG.HS256).compact(),
                builder().subject("10").claim("userId", 10L).claim("tokenVersion", 0L)
                        .expiration(new Date(System.currentTimeMillis() + 60_000)).compact())) {
            mvc.perform(get("/usuarios").header("Authorization", "Bearer " + invalid))
                    .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(service);
    }

    @Test void missingAuthenticationAndBadLoginReturn401ButPermissionDenialReturns403() throws Exception {
        mvc.perform(get("/usuarios")).andExpect(status().isUnauthorized());
        when(service.login(any())).thenThrow(new org.springframework.security.authentication.BadCredentialsException("invalid"));
        mvc.perform(post("/auth/login").contentType("application/json")
                .content("{\"email\":\"first@example.invalid\",\"password\":\"test-input\"}"))
                .andExpect(status().isUnauthorized());
        doThrow(new org.springframework.security.access.AccessDeniedException("protected"))
                .when(service).desactivarUsuario(10L);
        mvc.perform(delete("/auth/baja").header("Authorization", "Bearer " + token()))
                .andExpect(status().isForbidden());
    }

    @Test void optimisticConflictsReturn409WithoutInternalDetails() throws Exception {
        when(service.actualizarPerfil(eq(10L), any()))
                .thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(Usuario.class, 10L))
                .thenThrow(new jakarta.persistence.OptimisticLockException("internal database detail"));
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(put("/usuarios/perfil").header("Authorization", "Bearer " + token())
                    .contentType("application/json")
                    .content("{\"nombre\":\"Updated name\",\"email\":\"first@example.invalid\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value(
                            "La cuenta se ha modificado en otra petición. Recarga los datos e inténtalo de nuevo."));
        }
    }

    // Simulate a reassignment between filter authentication (old snapshot) and controller lookup.
    Usuario reassignedEmailSnapshot() {
        Usuario updated = new Usuario();
        updated.setId(10L);
        updated.setEmail("changed@example.invalid");
        updated.setRole(Role.CLIENTE);
        Usuario other = new Usuario();
        other.setId(99L);
        other.setEmail(user.getEmail());
        other.setRole(Role.ADMIN);
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(other));
        when(service.obtenerPorId(10L)).thenReturn(Optional.of(updated));
        return updated;
    }

    @Test void auditOwnershipAndHistoryUseIdAfterConcurrentEmailReassignment() throws Exception {
        Usuario updated = reassignedEmailSnapshot();
        String bearer = "Bearer " + token();
        mvc.perform(get("/auditorias/mias").header("Authorization", bearer)).andExpect(status().isOk());
        mvc.perform(get("/auditorias/7").header("Authorization", bearer)).andExpect(status().isOk());
        mvc.perform(post("/auditorias").header("Authorization", bearer).contentType("application/json")
                .content("""
                        {
                                "titulo": "Prueba",
                                "tipoDocumento": "Política de Privacidad",
                                "textoOriginal": "Texto legal de prueba"
                        }
                        """))
                .andExpect(status().isCreated());
        mvc.perform(post("/auditorias/7/descarga").header("Authorization", bearer)).andExpect(status().isOk());
    verify(audits).obtenerMisAuditorias(
        10L,
        0,
        10
);
        verify(audits).obtenerAuditoriaPorId(eq(7L), eq(10L), eq(false), same(updated), anyString());
        verify(audits).crearAuditoria(any(), same(updated), anyString());
        verify(audits).registrarDescarga(
                eq(7L),
                eq(10L),
                eq(false),
                same(updated),
                anyString()
        );
        verify(users, never()).findByEmail(anyString());
    }

    @Test void reassignedEmailCannotGrantAdministrativeAuditOrHistoryPermissions() throws Exception {
        reassignedEmailSnapshot();
        String bearer = "Bearer " + token();
        mvc.perform(get("/auditorias").header("Authorization", bearer)).andExpect(status().isForbidden());
        mvc.perform(get("/admin/historial").header("Authorization", bearer)).andExpect(status().isForbidden());
        verifyNoInteractions(audits, adminHistory);
        verify(users, never()).findByEmail(anyString());
    }

    @Test void deletedUserIsRejected() throws Exception {
        String token = token();
        when(users.findById(10L)).thenReturn(Optional.empty());
        mvc.perform(get("/usuarios").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }
    @Test
void auditTextOverLimitReturns400BeforeServiceExecution() throws Exception {
    String oversizedText = "a".repeat(15_001);

    mvc.perform(post("/auditorias")
                    .header("Authorization", "Bearer " + token())
                    .contentType("application/json")
                    .content("""
                            {
                              "titulo": "Auditoría de límites",
                              "tipoDocumento": "Política de Privacidad",
                              "textoOriginal": "%s"
                            }
                            """.formatted(oversizedText)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.message")
                    .value("El texto a auditar no puede superar los 15000 caracteres"))
            .andExpect(jsonPath("$.fieldErrors.textoOriginal")
                    .value("El texto a auditar no puede superar los 15000 caracteres"));

    verify(audits, never())
            .crearAuditoria(any(), any(), anyString());
}

@Test
void invalidAuditUrlReturns400BeforeServiceExecution() throws Exception {
    mvc.perform(post("/auditorias")
                    .header("Authorization", "Bearer " + token())
                    .contentType("application/json")
                    .content("""
                            {
                              "titulo": "Auditoría URL",
                              "tipoDocumento": "Política de Privacidad",
                              "textoOriginal": "Texto legal válido",
                              "urlOpcional": "ftp://example.invalid/documento"
                            }
                            """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.fieldErrors.urlOpcional")
                    .value("La URL debe ser una dirección HTTP o HTTPS válida"));

    verify(audits, never())
            .crearAuditoria(any(), any(), anyString());
}

@Test
void unknownDocumentTypeReturns400BeforeServiceExecution() throws Exception {
    mvc.perform(post("/auditorias")
                    .header("Authorization", "Bearer " + token())
                    .contentType("application/json")
                    .content("""
                            {
                              "titulo": "Auditoría tipo",
                              "tipoDocumento": "TIPO_INVENTADO",
                              "textoOriginal": "Texto legal válido"
                            }
                            """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.fieldErrors.tipoDocumento")
                    .value("El tipo de documento no es válido"));

    verify(audits, never())
            .crearAuditoria(any(), any(), anyString());
}

@Test
void malformedJsonReturnsStandard400() throws Exception {
    mvc.perform(post("/auditorias")
                    .header("Authorization", "Bearer " + token())
                    .contentType("application/json")
                    .content("{\"titulo\":"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("INVALID_JSON"))
            .andExpect(jsonPath("$.message")
                    .value("El cuerpo de la petición no contiene un JSON válido"));

    verify(audits, never())
            .crearAuditoria(any(), any(), anyString());
}
@Test
void personalAuditListingAcceptsPaginationParameters() throws Exception {

    when(audits.obtenerMisAuditorias(10L, 2, 7))
            .thenReturn(new PaginaResponse<>(
                    List.of(),
                    2,
                    7,
                    20,
                    3,
                    false,
                    true
            ));

    mvc.perform(get("/auditorias/mias")
                    .param("page", "2")
                    .param("size", "7")
                    .header(
                            "Authorization",
                            "Bearer " + token()
                    ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(2))
            .andExpect(jsonPath("$.size").value(7))
            .andExpect(jsonPath("$.totalElements").value(20))
            .andExpect(jsonPath("$.totalPages").value(3))
            .andExpect(jsonPath("$.content").isArray());

    verify(audits)
            .obtenerMisAuditorias(
                    10L,
                    2,
                    7
            );
}

@Test
void adminAuditListingForwardsServerSideUserFilter() throws Exception {

    when(audits.obtenerTodasLasAuditorias(
            1,
            10,
            "Lucia"
    )).thenReturn(new PaginaResponse<>(
            List.of(),
            1,
            10,
            12,
            2,
            false,
            true
    ));

    mvc.perform(get("/auditorias")
                    .param("page", "1")
                    .param("size", "10")
                    .param("usuario", "Lucia")
                    .header(
                            "Authorization",
                            "Bearer " + token()
                    ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.size").value(10))
            .andExpect(jsonPath("$.totalElements").value(12))
            .andExpect(jsonPath("$.content").isArray());

    verify(audits)
            .obtenerTodasLasAuditorias(
                    1,
                    10,
                    "Lucia"
            );
}
}
