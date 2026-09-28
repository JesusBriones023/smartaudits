package com.smartaudits.security;

import com.smartaudits.model.Auditoria;
import com.smartaudits.model.Role;
import com.smartaudits.model.Usuario;
import com.smartaudits.repository.AuditoriaRepository;
import com.smartaudits.repository.HistorialAccionAdminRepository;
import com.smartaudits.repository.HistorialAuditoriaRepository;
import com.smartaudits.repository.UsuarioRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Encoders;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;

// Real Tomcat dispatches ERROR after sendError; MockMvc alone does not reproduce it.
// All services and repositories are real, with an explicitly isolated in-memory DB.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.location=optional:classpath:/security-http-no-config.properties",
        "server.address=127.0.0.1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "app.admin.bootstrap.enabled=false", "debug=false", "logging.level.root=WARN",
        "logging.level.org.springframework=WARN", "logging.level.org.hibernate=WARN"})
@ActiveProfiles("test")
@Import(SecurityHttpResponseTest.DispatchObservation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SecurityHttpResponseTest {
    private static final SecretKey KEY = Jwts.SIG.HS256.key().build();
    private static final String DATABASE = "jdbc:h2:mem:http_security_" + UUID.randomUUID();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DATABASE);
        registry.add("jwt.secret", () -> Encoders.BASE64.encode(KEY.getEncoded()));
        registry.add("jwt.issuer", () -> "http-test");
        registry.add("jwt.audience", () -> "http-test-api");
    }

    @LocalServerPort int port;
    @Autowired UsuarioRepository users;
    @Autowired AuditoriaRepository audits;
    @Autowired HistorialAccionAdminRepository adminHistory;
    @Autowired HistorialAuditoriaRepository auditHistory;
    @Autowired JwtUtil jwt;
    @Autowired DispatchTrace trace;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void setup() {
        adminHistory.deleteAll();
        audits.deleteAll();
        users.deleteAll();
        trace.events.clear();
    }

    Usuario user(Role role, boolean protectedAccount) {
        Usuario user = new Usuario();
        user.setNombre("HTTP test");
        user.setEmail(UUID.randomUUID() + "@example.invalid");
        user.setPassword(UUID.randomUUID().toString()); // No password login in these fixtures.
        user.setRole(role);
        user.setProtegido(protectedAccount);
        return users.saveAndFlush(user);
    }

    String token(Usuario user) {
        return jwt.generateToken(new CustomUserDetails(user));
    }

    HttpResponse<String> request(String method, String path, String token, String body)
            throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    void assertStatusWithoutErrorDispatch(HttpResponse<String> response, int expected) {
        assertThat(response.statusCode()).as("dispatch trace: %s", List.copyOf(trace.events))
                .isEqualTo(expected);
        assertThat(trace.events).noneMatch(event -> event.startsWith("ERROR "));
        assertThat(response.body()).isEmpty();
    }

    void denied(Usuario actor, String method, String path, String body) throws Exception {
        String token = token(actor);
        assertThat(request("GET", "/auditorias/mias", token, null).statusCode()).isEqualTo(200);
        trace.events.clear();
        assertStatusWithoutErrorDispatch(request(method, path, token, body), 403);
        assertThat(request("GET", "/auditorias/mias", token, null).statusCode()).isEqualTo(200);
        assertThat(jwt.isTokenValid(token, new CustomUserDetails(users.findById(actor.getId()).orElseThrow())))
                .isTrue();
    }

    void unchanged(Usuario before) {
        Usuario after = users.findById(before.getId()).orElseThrow();
        assertThat(after.getRole()).isEqualTo(before.getRole());
        assertThat(after.getActivo()).isEqualTo(before.getActivo());
        assertThat(after.getProtegido()).isEqualTo(before.getProtegido());
        assertThat(after.getTokenVersion()).isEqualTo(before.getTokenVersion());
        assertThat(after.getRowVersion()).isEqualTo(before.getRowVersion());
    }

    @Test void missingTokenReturns401() throws Exception {
        assertStatusWithoutErrorDispatch(request("GET", "/auditorias/mias", null, null), 401);
    }

    @Test void malformedTokenReturns401() throws Exception {
        assertStatusWithoutErrorDispatch(request("GET", "/auditorias/mias", "malformed-token", null), 401);
    }

    String signedToken(Usuario user, SecretKey key, Date expiry) {
        return Jwts.builder().issuer("http-test").audience().add("http-test-api").and()
                .subject(user.getId().toString()).claim("userId", user.getId())
                .claim("tokenVersion", user.getTokenVersion()).expiration(expiry)
                .signWith(key, Jwts.SIG.HS256).compact();
    }

    @Test void invalidSignatureReturns401() throws Exception {
        Usuario user = user(Role.CLIENTE, false);
        String invalid = signedToken(user, Jwts.SIG.HS256.key().build(), new Date(System.currentTimeMillis() + 60000));
        assertStatusWithoutErrorDispatch(request("GET", "/auditorias/mias", invalid, null), 401);
    }

    @Test void expiredTokenReturns401() throws Exception {
        Usuario user = user(Role.CLIENTE, false);
        String expired = signedToken(user, KEY, new Date(System.currentTimeMillis() - 60000));
        assertStatusWithoutErrorDispatch(request("GET", "/auditorias/mias", expired, null), 401);
    }

    @Test void explicitControllerForbiddenResponseRemains403() throws Exception {
        denied(user(Role.CLIENTE, false), "GET", "/usuarios", null);
    }

    Auditoria audit(Usuario owner) {
        Auditoria audit = new Auditoria();
        audit.registrarProcedencia("http-test", "http-rules",
                java.time.LocalDateTime.of(2025, 1, 1, 12, 0), com.smartaudits.model.TipoFuente.MANUAL);
        audit.setUsuario(owner);
        audit.setTitulo("HTTP authorization test");
        audit.setResultadoJson("{}");
        return audits.saveAndFlush(audit);
    }

    @Test void ownershipAccessDeniedExceptionReturns403WithoutErrorRedispatch() throws Exception {
        Auditoria audit = audit(user(Role.CLIENTE, false));
        denied(user(Role.CLIENTE, false), "GET", "/auditorias/" + audit.getId(), null);
        assertThat(auditHistory.count()).isZero();
    }

    @Test void ownerAndAdminStillReadAudit() throws Exception {
        Usuario owner = user(Role.CLIENTE, false);
        Auditoria audit = audit(owner);
        String path = "/auditorias/" + audit.getId();
        assertThat(request("GET", path, token(owner), null).statusCode()).isEqualTo(200);
        assertThat(request("GET", path, token(user(Role.ADMIN, false)), null).statusCode()).isEqualTo(200);
    }

    @Test void protectedAccountCannotDeactivateItself() throws Exception {
        Usuario protectedAdmin = user(Role.ADMIN, true);
        denied(protectedAdmin, "DELETE", "/auth/baja", null);
        unchanged(protectedAdmin);
    }

    @Test void protectedAccountCannotBeDeactivatedByAnotherAdmin() throws Exception {
        Usuario protectedAdmin = user(Role.ADMIN, true);
        denied(user(Role.ADMIN, false), "PATCH", "/usuarios/" + protectedAdmin.getId() + "/desactivar", null);
        unchanged(protectedAdmin);
        assertThat(adminHistory.count()).isZero();
    }

    @Test void protectedAccountCannotBeDemotedByAnotherAdmin() throws Exception {
        Usuario protectedAdmin = user(Role.ADMIN, true);
        denied(user(Role.ADMIN, false), "PATCH", "/usuarios/" + protectedAdmin.getId() + "/rol",
                "{\"nuevoRol\":\"CLIENTE\"}");
        unchanged(protectedAdmin);
        assertThat(adminHistory.count()).isZero();
    }

    @Test void lastActiveAdminReceives403AndRemainsActive() throws Exception {
        Usuario lastAdmin = user(Role.ADMIN, false);
        denied(lastAdmin, "DELETE", "/auth/baja", null);
        unchanged(lastAdmin);
        assertThat(users.findAll().stream().filter(u -> u.getRole() == Role.ADMIN && u.getActivo()).count())
                .isEqualTo(1);
    }

    @Test void directErrorEndpointDoesNotBypassAuthentication() throws Exception {
        assertStatusWithoutErrorDispatch(request("GET", "/error", null, null), 401);
    }

    static class DispatchTrace {
        final ConcurrentLinkedQueue<String> events = new ConcurrentLinkedQueue<>();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DispatchObservation {
        @Bean DispatchTrace dispatchTrace() { return new DispatchTrace(); }

        @Bean FilterRegistrationBean<Filter> dispatchObserver(DispatchTrace trace) {
            Filter filter = (request, response, chain) -> {
                var req = (HttpServletRequest) request;
                trace.events.add(req.getDispatcherType() + " " + req.getRequestURI()
                        + " originalError=" + req.getAttribute(RequestDispatcher.ERROR_STATUS_CODE));
                var wrapped = new HttpServletResponseWrapper((HttpServletResponse) response) {
                    @Override public void sendError(int status) throws IOException {
                        trace.events.add("sendError=" + status + " dispatcher=" + req.getDispatcherType()
                                + " authenticated=" + (SecurityContextHolder.getContext().getAuthentication() != null));
                        super.sendError(status);
                    }
                };
                chain.doFilter(request, wrapped);
            };
            var registration = new FilterRegistrationBean<>(filter);
            registration.setOrder(-101); // Observe both sides of the security chain (-100).
            registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ERROR);
            return registration;
        }
    }
}
