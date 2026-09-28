package com.smartaudits.service;

import com.smartaudits.model.*;
import com.smartaudits.model.dto.*;
import com.smartaudits.repository.*;
import com.smartaudits.security.*;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Encoders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(showSql = false, properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "logging.level.root=WARN", "logging.level.org.springframework=WARN","spring.flyway.enabled=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"})
@Import({UsuarioService.class, JwtUtil.class, BCryptPasswordEncoder.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UsuarioSecurityPersistenceTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        String key = Encoders.BASE64.encode(Jwts.SIG.HS256.key().build().getEncoded());
        registry.add("jwt.secret", () -> key);
    }
    @Autowired UsuarioService service;
    @Autowired UsuarioRepository users;
    @Autowired RolRepository roles;
    @Autowired JwtUtil jwt;
    @Autowired BCryptPasswordEncoder encoder;
    @Autowired PlatformTransactionManager transactions;
    @MockBean AuthenticationManager authenticationManager;
    @MockBean HistorialAdminService history;

    @BeforeEach void setup() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            users.deleteAll();
            users.flush();
            roles.deleteAll();
            roles.flush();
            for (String name : List.of("ADMIN", "CLIENTE")) {
                Rol rol = new Rol();
                rol.setNombre(name);
                roles.save(rol);
            }
        });
    }

    Usuario create(Role role, boolean protectedAccount) {
        Usuario user = new Usuario();
        user.setNombre("Prueba");
        user.setEmail(java.util.UUID.randomUUID() + "@example.invalid");
        user.setPassword(encoder.encode("test-password-before"));
        user.setRole(role);
        user.setProtegido(protectedAccount);
        return users.saveAndFlush(user);
    }

    String token(Usuario user) { return jwt.generateToken(new CustomUserDetails(user)); }
    boolean valid(String token, Long id) {
        return jwt.isTokenValid(token, new CustomUserDetails(users.findById(id).orElseThrow()));
    }

    @Test void passwordChangeInvalidatesOldTokenAndNewTokenUsesFlushedVersion() {
        Usuario user = create(Role.CLIENTE, false);
        String before = token(user);
        ActualizarPerfilRequest request = profile(user);
        request.setPasswordActual("test-password-before");
        request.setPasswordNueva("test-password-after");
        AuthResponse response = service.actualizarPerfil(user.getId(), request);
        assertThat(valid(before, user.getId())).isFalse();
        assertThat(valid(response.getToken(), user.getId())).isTrue();
        assertThat(users.findById(user.getId()).orElseThrow().getTokenVersion())
                .isGreaterThan(user.getTokenVersion());
        assertThat(encoder.matches("test-password-after", users.findById(user.getId()).orElseThrow().getPassword())).isTrue();
    }

    @Test void nameOnlyChangeAdvancesJpaVersionWithoutRevokingTokens() {
        Usuario user = create(Role.CLIENTE, false);
        String before = token(user);
        ActualizarPerfilRequest request = profile(user);
        request.setNombre("Otro nombre");
        AuthResponse response = service.actualizarPerfil(user.getId(), request);
        Usuario updated = users.findById(user.getId()).orElseThrow();
        assertThat(updated.getRowVersion()).isGreaterThan(user.getRowVersion());
        assertThat(updated.getTokenVersion()).isEqualTo(user.getTokenVersion());
        assertThat(valid(before, user.getId())).isTrue();
        assertThat(valid(response.getToken(), user.getId())).isTrue();
    }

    @Test void unchangedProfileAndNoOpAdministrationDoNotRevokeTokens() {
        Usuario admin = create(Role.ADMIN, true);
        Usuario client = create(Role.CLIENTE, false);
        String before = token(client);
        service.actualizarPerfil(client.getId(), profile(client));
        service.cambiarRol(client.getId(), "CLIENTE", admin.getId());
        service.reactivarUsuario(client.getId(), admin.getId());
        assertThat(valid(before, client.getId())).isTrue();
        assertThat(users.findById(client.getId()).orElseThrow().getRowVersion()).isEqualTo(client.getRowVersion());
    }

    @Test void combinedEmailAndPasswordChangeRevokesOnceAndFailedChangeRollsBackBoth() {
        Usuario user = create(Role.CLIENTE, false);
        String before = token(user);
        ActualizarPerfilRequest request = profile(user);
        request.setEmail("new-credentials@example.invalid");
        request.setPasswordNueva("test-password-after");
        request.setPasswordActual("incorrect-test-password");
        assertThatThrownBy(() -> service.actualizarPerfil(user.getId(), request))
                .hasMessage("La contraseña actual es incorrecta");
        assertThat(users.findById(user.getId()).orElseThrow().getEmail()).isEqualTo(user.getEmail());
        assertThat(valid(before, user.getId())).isTrue();
        request.setPasswordActual("test-password-before");
        AuthResponse response = service.actualizarPerfil(user.getId(), request);
        assertThat(users.findById(user.getId()).orElseThrow().getTokenVersion()).isEqualTo(user.getTokenVersion() + 1);
        assertThat(valid(before, user.getId())).isFalse();
        assertThat(valid(response.getToken(), user.getId())).isTrue();
    }

    @Test void loginUsesPersistedCredentialsAndRevocationVersion() {
        var provider = new org.springframework.security.authentication.dao.DaoAuthenticationProvider();
        provider.setUserDetailsService(new CustomUserDetailsService(users));
        provider.setPasswordEncoder(encoder);
        org.mockito.Mockito.when(authenticationManager.authenticate(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> provider.authenticate(invocation.getArgument(0)));
        Usuario user = create(Role.CLIENTE, false);
        LoginRequest login = new LoginRequest();
        login.setEmail(user.getEmail());
        login.setPassword("test-password-before");
        assertThat(valid(service.login(login).getToken(), user.getId())).isTrue();
        ActualizarPerfilRequest request = profile(user);
        request.setPasswordActual("test-password-before");
        request.setPasswordNueva("test-password-after");
        service.actualizarPerfil(user.getId(), request);
        assertThatThrownBy(() -> service.login(login))
                .isInstanceOf(org.springframework.security.authentication.BadCredentialsException.class);
        login.setPassword("test-password-after");
        assertThat(valid(service.login(login).getToken(), user.getId())).isTrue();
        service.desactivarUsuario(user.getId());
        assertThatThrownBy(() -> service.login(login))
                .isInstanceOf(org.springframework.security.authentication.DisabledException.class);
    }

    @Test void registrationReturnsUsableTokenAfterRoleAssignment() {
        RegisterRequest request = new RegisterRequest();
        request.setNombre("Registro de prueba");
        request.setEmail("registration@example.invalid");
        request.setPassword("test-registration-password");
        AuthResponse response = service.register(request);
        assertThat(valid(response.getToken(), response.getUserId())).isTrue();
        assertThat(response.getRole()).isEqualTo("CLIENTE");
        assertThat(users.findById(response.getUserId()).orElseThrow().getTokenVersion()).isZero();
    }

    @Test void staleEntityCannotOverwriteNewCredentialVersion() {
        Usuario user = create(Role.CLIENTE, false);
        Usuario stale = users.findById(user.getId()).orElseThrow();
        ActualizarPerfilRequest request = profile(user);
        request.setPasswordActual("test-password-before");
        request.setPasswordNueva("test-password-after");
        AuthResponse response = service.actualizarPerfil(user.getId(), request);
        stale.setNombre("Edición concurrente antigua");
        assertThatThrownBy(() -> users.saveAndFlush(stale))
                .isInstanceOf(org.springframework.orm.ObjectOptimisticLockingFailureException.class);
        assertThat(valid(response.getToken(), user.getId())).isTrue();
    }

    @Test void failedPasswordChangeDoesNotInvalidateExistingToken() {
        Usuario user = create(Role.CLIENTE, false);
        String before = token(user);
        ActualizarPerfilRequest request = profile(user);
        request.setPasswordActual("incorrect-test-password");
        request.setPasswordNueva("test-password-after");
        assertThatThrownBy(() -> service.actualizarPerfil(user.getId(), request)).isInstanceOf(RuntimeException.class)
                .hasMessage("La contraseña actual es incorrecta");
        assertThat(valid(before, user.getId())).isTrue();
    }

    @Test void emailChangeAndReuseCannotTransferTokenIdentity() {
        Usuario user = create(Role.CLIENTE, false);
        String before = token(user);
        ActualizarPerfilRequest request = profile(user);
        request.setEmail("changed@example.invalid");
        AuthResponse response = service.actualizarPerfil(user.getId(), request);
        Usuario other = create(Role.CLIENTE, false);
        other.setEmail(user.getEmail());
        users.saveAndFlush(other);
        assertThat(valid(before, user.getId())).isFalse();
        assertThat(valid(before, other.getId())).isFalse();
        assertThat(valid(response.getToken(), user.getId())).isTrue();
    }

    @Test void reactivationDoesNotResurrectOldTokens() {
        Usuario admin = create(Role.ADMIN, true);
        Usuario client = create(Role.CLIENTE, false);
        String before = token(client);
        service.desactivarUsuario(client.getId());
        assertThat(valid(before, client.getId())).isFalse();
        assertThat(users.findById(client.getId()).orElseThrow().getTokenVersion()).isEqualTo(client.getTokenVersion() + 1);
        service.reactivarUsuario(client.getId(), admin.getId());
        assertThat(valid(before, client.getId())).isFalse();
        assertThat(users.findById(client.getId()).orElseThrow().getActivo()).isTrue();
        assertThat(users.findById(client.getId()).orElseThrow().getTokenVersion()).isEqualTo(client.getTokenVersion() + 2);
    }

    @Test void protectedAdminCannotDeactivateSelfOrBeDegradedOrDeactivatedByAdmin() {
        Usuario protectedAdmin = create(Role.ADMIN, true);
        Usuario otherAdmin = create(Role.ADMIN, false);
        assertThatThrownBy(() -> service.desactivarUsuario(protectedAdmin.getId())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.desactivarUsuarioPorAdmin(protectedAdmin.getId(), otherAdmin.getId())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.cambiarRol(protectedAdmin.getId(), "CLIENTE", otherAdmin.getId())).isInstanceOf(AccessDeniedException.class);
        assertThat(users.findById(protectedAdmin.getId()).orElseThrow().getActivo()).isTrue();
        assertThat(users.findById(protectedAdmin.getId()).orElseThrow().getRole()).isEqualTo(Role.ADMIN);
    }

    @Test void lastUnprotectedAdminCannotDeactivateSelf() {
        Usuario admin = create(Role.ADMIN, false);
        assertThatThrownBy(() -> service.desactivarUsuario(admin.getId())).isInstanceOf(AccessDeniedException.class);
        assertThat(users.countByRoleAndActivoTrue(Role.ADMIN)).isEqualTo(1);
    }

    @Test void clientAndInactiveAdminCannotPerformAdministrativeChanges() {
        Usuario admin = create(Role.ADMIN, true);
        Usuario client = create(Role.CLIENTE, false);
        assertThatThrownBy(() -> service.cambiarRol(admin.getId(), "CLIENTE", client.getId())).isInstanceOf(AccessDeniedException.class);
        admin.setActivo(false);
        users.saveAndFlush(admin); // Fixture externa: comprobar autorización del servicio.
        assertThatThrownBy(() -> service.reactivarUsuario(client.getId(), admin.getId())).isInstanceOf(AccessDeniedException.class);
    }

    @Test void roleChangesInvalidateTokensAndKeepRelationalRoleInSync() {
        Usuario admin = create(Role.ADMIN, true);
        Usuario client = create(Role.CLIENTE, false);
        String before = token(client);
        service.cambiarRol(client.getId(), "ADMIN", admin.getId());
        assertThat(valid(before, client.getId())).isFalse();
        String promoted = token(users.findById(client.getId()).orElseThrow());
        service.cambiarRol(client.getId(), "CLIENTE", admin.getId());
        assertThat(valid(promoted, client.getId())).isFalse();
        new TransactionTemplate(transactions).executeWithoutResult(status ->
                assertThat(users.findById(client.getId()).orElseThrow().getRoles())
                        .extracting(Rol::getNombre).containsExactly("CLIENTE"));
    }

    @Test void concurrentSelfDeactivationsPreserveOneActiveAdmin() throws Exception {
        Usuario first = create(Role.ADMIN, false);
        Usuario second = create(Role.ADMIN, false);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Callable<Boolean> firstAction = () -> deactivateTogether(first.getId(), ready, go);
            Callable<Boolean> secondAction = () -> deactivateTogether(second.getId(), ready, go);
            Future<Boolean> one = executor.submit(firstAction);
            Future<Boolean> two = executor.submit(secondAction);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(users.countByRoleAndActivoTrue(Role.ADMIN)).isEqualTo(1);
        } finally {
            go.countDown();
            executor.shutdownNow();
        }
    }

    @Test void concurrentCrossDemotionsCannotRemoveAllAdmins() throws Exception {
        Usuario first = create(Role.ADMIN, false);
        Usuario second = create(Role.ADMIN, false);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<Boolean> one = executor.submit(() -> demoteTogether(second.getId(), first.getId(), ready, go));
            Future<Boolean> two = executor.submit(() -> demoteTogether(first.getId(), second.getId(), ready, go));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(users.countByRoleAndActivoTrue(Role.ADMIN)).isEqualTo(1);
        } finally {
            go.countDown();
            executor.shutdownNow();
        }
    }

    boolean demoteTogether(Long target, Long actor, CountDownLatch ready, CountDownLatch go) throws InterruptedException {
        ready.countDown();
        if (!go.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test synchronization timed out");
        try {
            service.cambiarRol(target, "CLIENTE", actor);
            return true;
        } catch (AccessDeniedException expected) {
            return false;
        }
    }

    boolean deactivateTogether(Long id, CountDownLatch ready, CountDownLatch go) throws InterruptedException {
        ready.countDown();
        if (!go.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test synchronization timed out");
        try {
            service.desactivarUsuario(id);
            return true;
        } catch (AccessDeniedException expected) {
            return false;
        }
    }

    ActualizarPerfilRequest profile(Usuario user) {
        ActualizarPerfilRequest request = new ActualizarPerfilRequest();
        request.setNombre(user.getNombre());
        request.setEmail(user.getEmail());
        return request;
    }
}
