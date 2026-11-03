package com.smartaudits.service.url;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartaudits.model.Auditoria;
import com.smartaudits.model.HistorialAuditoria;
import com.smartaudits.model.Role;
import com.smartaudits.model.TipoFuente;
import com.smartaudits.model.Usuario;
import com.smartaudits.repository.AuditoriaRepository;
import com.smartaudits.repository.HistorialAuditoriaRepository;
import com.smartaudits.repository.IncidenciaRepository;
import com.smartaudits.repository.UsuarioRepository;
import com.smartaudits.service.AuditQuotaExceededException;
import com.smartaudits.service.AuditQuotaService;
import com.smartaudits.service.AuditoriaService;
import com.smartaudits.service.HistorialService;
import com.smartaudits.service.html.ContenidoHtmlInvalidoException;
import com.smartaudits.service.motor.MotorAnalisisLegal;
import com.smartaudits.v2.acquisition.capture.CapturaException;
import com.smartaudits.v2.acquisition.capture.CapturaHtml;
import com.smartaudits.v2.acquisition.capture.ControlConcurrenciaCaptura;
import com.smartaudits.v2.acquisition.capture.TrabajadoresCapturaDePrueba;
import com.smartaudits.v2.acquisition.capture.TransporteHtml;
import com.smartaudits.v2.acquisition.capture.TransporteLocalDePrueba;
import com.smartaudits.v2.acquisition.destination.DestinationUrl;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.smartaudits.v2.acquisition.capture.CapturaRealDePrueba.crear;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Spring persistence proxies and an isolated H2 pool; all captures are synthetic, with no network. */
@DataJpaTest(showSql = false, properties = {
        "spring.config.location=optional:classpath:/pv13-no-config.properties",
        "spring.datasource.url=jdbc:h2:mem:pv13-boundary;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.hikari.minimum-idle=0", "spring.datasource.hikari.maximum-pool-size=2",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false", "spring.sql.init.mode=never",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect", "app.audit.daily-limit=1",
        "logging.level.root=WARN", "logging.level.org.springframework=WARN"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({AuditoriaService.class, HistorialService.class, AuditQuotaService.class, MotorAnalisisLegal.class,
        ObjectMapper.class, IngestaUrlService.class, IngestaUrlTransactionIntegrationTest.Admission.class})
class IngestaUrlTransactionIntegrationTest {
    @TestConfiguration
    static class Admission {
        @Bean ControlConcurrenciaCaptura concurrencia() { return new ControlConcurrenciaCaptura(1); }
    }
    static final String URL = "https://www.ejemplo.es/legal";
    static final String FINAL_URL = "https://legal.ejemplo.es/v2/privacidad";
    static final String HTML = "<h1>Política de privacidad</h1><p>Responsable del tratamiento: Ejemplo S.L.</p>";

    @Autowired IngestaUrlService ingesta;
    @SpyBean ControlConcurrenciaCaptura concurrencia;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired EntityManagerFactory factory;
    @Autowired DataSource dataSource;
    @MockBean TransporteHtml transport;
    @SpyBean AuditQuotaService quota;
    @SpyBean AuditoriaService persistence;
    @SpyBean AuditoriaRepository audits;
    @SpyBean UsuarioRepository users;
    @SpyBean IncidenciaRepository incidents;
    @SpyBean HistorialAuditoriaRepository history;
    Usuario owner;

    @BeforeEach
    void setup() {
        // Each method commits its own fixture setup; no implicit test rollback hides service boundaries.
        cleanupRows();
        owner = user(Role.CLIENTE);
        clearInvocations(transport, quota, persistence, audits, users, incidents, history, concurrencia);
        when(transport.capturar(any())).thenAnswer(invocation -> {
            assertReleasedAtCapture();
            return realFixture(invocation.getArgument(0), HTML);
        });
    }

    @AfterEach
    void cleanup() {
        // Clear failure injection before deleting fixtures. These tables belong only to this H2 context.
        reset(audits, users, incidents, history);
        cleanupRows();
        assertReleasedAtCapture();
    }

    private void cleanupRows() {
        history.deleteAllInBatch();
        incidents.deleteAllInBatch();
        audits.deleteAllInBatch();
        users.deleteAllInBatch();
    }

    private Usuario user(Role role) {
        Usuario u = new Usuario();
        u.setNombre("Fixture PV13");
        u.setEmail(role.name().toLowerCase() + "@pv13.example.invalid");
        u.setPassword("unused-test-fixture");
        u.setRole(role);
        return users.saveAndFlush(u);
    }

    private CapturaHtml realFixture(DestinationUrl requested, String html) {
        return crear(requested, TransporteLocalDePrueba.destino(FINAL_URL), html);
    }

    private void invoke(String entry) {
        switch (entry) {
            case "capturar" -> ingesta.capturar(URL);
            case "analizar" -> ingesta.analizar(URL, "Política de Privacidad");
            case "auditar" -> ingesta.auditar(URL, "PV13", "Política de Privacidad", owner, "192.0.2.13");
            default -> throw new AssertionError(entry);
        }
    }

    private void assertReleasedAtCapture() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
        // A resource-map check alone cannot prove the pool connection was returned.
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        assertThat(((HikariDataSource) dataSource).getHikariPoolMXBean().getActiveConnections()).isZero();
    }

    private void assertNoWork() {
        verifyNoInteractions(transport, quota, persistence, audits, users, incidents, history, concurrencia);
    }

    private void assertNoAuditRows() {
        assertThat(audits.count()).isZero();
        assertThat(incidents.count()).isZero();
        assertThat(history.count()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"capturar,false", "analizar,false", "auditar,false", "capturar,true", "analizar,true", "auditar,true"})
    void outerTransactionsRejectAllEntryPointsBeforeAnyWork(String entry, boolean readOnly) {
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setReadOnly(readOnly);
        transaction.executeWithoutResult(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThatThrownBy(() -> invoke(entry)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("fuera de una transacción");
            assertNoWork();
        });
        assertNoAuditRows();
    }

    @Test
    void guardPrecedesEvenInvalidArgumentsAndDirectCallsNeedNoProxy() {
        var direct = new IngestaUrlService(transport, persistence, mock(MotorAnalisisLegal.class), quota, concurrencia);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThatThrownBy(() -> direct.capturar(null)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> direct.analizar(null, null)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> direct.auditar(null, null, null, null, null)).isInstanceOf(IllegalStateException.class);
            assertNoWork();
        });
    }

    @ParameterizedTest
    @CsvSource({"capturar,false", "analizar,false", "auditar,false", "capturar,true", "analizar,true", "auditar,true"})
    void osivContextIsRejectedEvenWithoutATransaction(String entry, boolean obtainConnection) {
        var manager = factory.createEntityManager();
        TransactionSynchronizationManager.bindResource(factory, new EntityManagerHolder(manager));
        try {
            if (obtainConnection) manager.createNativeQuery("select 1").getSingleResult();
            clearInvocations(transport, quota, persistence, audits, users, incidents, history, concurrencia);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThatThrownBy(() -> invoke(entry)).isInstanceOf(IllegalStateException.class).hasMessageContaining("OSIV");
            assertNoWork();
        } finally {
            TransactionSynchronizationManager.unbindResource(factory);
            manager.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"capturar", "analizar", "auditar"})
    void aBoundJdbcConnectionAlsoRejectsAcquisition(String entry) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            TransactionSynchronizationManager.bindResource(dataSource, new ConnectionHolder(connection));
            try {
                assertThatThrownBy(() -> invoke(entry)).isInstanceOf(IllegalStateException.class).hasMessageContaining("JPA/JDBC");
                assertNoWork();
            } finally {
                TransactionSynchronizationManager.unbindResource(dataSource);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"capturar", "analizar", "auditar"})
    void synchronizationWithoutATransactionCannotDeferQueryCleanup(String entry) {
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThatThrownBy(() -> invoke(entry)).isInstanceOf(IllegalStateException.class);
            assertNoWork();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"capturar", "analizar", "auditar"})
    void permittedEntryPointsReleaseResourcesBeforeCapture(String entry) {
        invoke(entry); // The transport answer asserts transaction, binding and actual pool release.
        verify(transport).capturar(any());
        assertReleasedAtCapture();
    }

    @Test
    void successfulSyntheticRealProvenanceAuditCommitsCompleteCrawlerResult() {
        var response = ingesta.auditar(URL, "PV13", "Política de Privacidad", owner, "192.0.2.13");
        assertThat(response.getTipoFuente()).isEqualTo(TipoFuente.CRAWLER);
        assertThat(response.getUrlOpcional()).isEqualTo(FINAL_URL);
        assertThat(response.getTextoOriginal()).doesNotContain("<");
        assertThat(response.getResultado().getErrores()).isNotEmpty();
        assertThat(audits.count()).isOne();
        assertThat(incidents.count()).isEqualTo(response.getResultado().getErrores().size());
        assertThat(history.findAll()).singleElement().satisfies(h -> {
            assertThat(h.getAuditoria().getId()).isEqualTo(response.getId());
            assertThat(h.getUsuario().getId()).isEqualTo(owner.getId());
            assertThat(h.getAccion()).isEqualTo("CREACION");
            assertThat(h.getIpAcceso()).isEqualTo("192.0.2.13");
        });
        var stored = audits.findById(response.getId()).orElseThrow();
        assertThat(stored.getTipoFuente()).isEqualTo(TipoFuente.CRAWLER);
        assertThat(stored.getUrlOpcional()).isEqualTo(FINAL_URL);
        assertThat(stored.getResultadoJson()).contains("coberturaEvaluacion");
        verify(quota).precomprobarPuedeCrear(owner);
        verify(quota).verificarPuedeCrear(owner);
        verify(users).bloquearPorId(owner.getId());
    }

    @Test
    void authoritativeQuotaLockRunsOnlyInsideThePersistenceTransaction() {
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            verify(transport).capturar(any());
            // Capacity must already be free while the persistence transaction is active.
            concurrencia.ejecutar(() -> realFixture(TransporteLocalDePrueba.destino(URL), HTML));
            return invocation.callRealMethod();
        }).when(quota).verificarPuedeCrear(owner);
        invoke("auditar");
        verify(users).bloquearPorId(owner.getId());
    }

    @Test
    void persistenceFailureAfterAuditInsertRollsBackEverything() {
        doThrow(new IllegalStateException("fixture persistence failure"))
                .when(audits).save(argThat(a -> a != null && !a.getIncidencias().isEmpty()));
        assertThatThrownBy(() -> invoke("auditar")).isInstanceOf(IllegalStateException.class)
                .hasMessage("fixture persistence failure");
        assertNoAuditRows();
    }

    @Test
    void historyCreationFailureAfterSavingIncidentsRollsBackEverything() {
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(audits.count()).isOne();
            assertThat(incidents.count()).isPositive();
            throw new IllegalStateException("fixture history failure");
        }).when(history).save(any(HistorialAuditoria.class));
        assertThatThrownBy(() -> invoke("auditar")).isInstanceOf(IllegalStateException.class)
                .hasMessage("fixture history failure");
        assertNoAuditRows();
    }

    @Test
    void aRealDatabaseHistoryConstraintFailureRollsBackTheWholeAudit() {
        assertThatThrownBy(() -> ingesta.auditar(URL, "PV13", "Política de Privacidad", owner, "x".repeat(51)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertNoAuditRows();
    }

    @Test
    void captureFailureWritesNothing() {
        doThrow(new CapturaException(CapturaException.Motivo.TRANSPORTE, "fixture")).when(transport).capturar(any());
        assertThatThrownBy(() -> invoke("auditar")).isInstanceOf(CapturaException.class);
        verifyNoInteractions(persistence, history, incidents);
        assertNoAuditRows();
    }

    @Test
    void simulatedCaptureWritesNothing() {
        doAnswer(i -> TransporteLocalDePrueba.captura(i.getArgument(0),
                TransporteLocalDePrueba.destino(FINAL_URL), 200, "text/html", HTML)).when(transport).capturar(any());
        assertThatThrownBy(() -> invoke("auditar")).isInstanceOfSatisfying(CapturaException.class,
                e -> assertThat(e.motivo()).isEqualTo(CapturaException.Motivo.CAPTURA_SIMULADA));
        verifyNoInteractions(persistence, history, incidents);
        assertNoAuditRows();
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty", "status", "url", "type"})
    void invalidCaptureOrContentWritesNothing(String failure) {
        doAnswer(i -> {
            DestinationUrl requested = i.getArgument(0);
            if (failure.equals("empty")) return realFixture(requested, "<script>hidden()</script>");
            return new CapturaHtml(failure.equals("url") ? TransporteLocalDePrueba.destino(FINAL_URL) : requested,
                    requested, failure.equals("status") ? 404 : 200,
                    failure.equals("type") ? "application/pdf" : "text/html", HTML, java.time.Instant.now(),
                    realFixture(requested, HTML).procedencia());
        }).when(transport).capturar(any());
        assertThatThrownBy(() -> invoke("auditar")).isInstanceOfAny(CapturaException.class, ContenidoHtmlInvalidoException.class);
        assertNoAuditRows();
    }

    @Test
    void invalidUrlIsRejectedBeforeQuotaOrTransport() {
        assertThatThrownBy(() -> ingesta.auditar("https://127.0.0.1/", "PV13", "Aviso Legal", owner, null))
                .isInstanceOf(CapturaException.class);
        assertNoWork();
    }

    @Test
    void resourcesIntroducedByTheQuotaPrecheckAreRejectedBeforeCapture() {
        assertReleasedAtCapture();
        var manager = factory.createEntityManager();
        var holder = new EntityManagerHolder(manager);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            assertReleasedAtCapture();
            TransactionSynchronizationManager.bindResource(factory, holder);
            return null;
        }).when(quota).precomprobarPuedeCrear(owner);
        try {
            assertThatThrownBy(() -> invoke("auditar")).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("JPA/JDBC");
            assertThat(TransactionSynchronizationManager.getResource(factory)).isSameAs(holder);
            verify(quota).precomprobarPuedeCrear(owner);
            verify(quota, never()).verificarPuedeCrear(any());
            verifyNoInteractions(transport, persistence, history, incidents, concurrencia);
            verify(audits, never()).save(any());
        } finally {
            TransactionSynchronizationManager.unbindResourceIfPossible(factory);
            manager.close();
        }
        assertNoAuditRows();
    }

    private void consumeQuota() {
        Auditoria a = new Auditoria();
        a.registrarProcedencia("historic", "historic", LocalDateTime.of(2025, 1, 1, 0, 0), TipoFuente.MANUAL);
        a.setTitulo("Existing audit");
        a.setUsuario(owner);
        audits.saveAndFlush(a);
    }

    @Test
    void exhaustedClientIsRejectedWithoutTransportOrLock() {
        consumeQuota();
        clearInvocations(transport, quota, persistence, audits, users, incidents, history, concurrencia);
        assertThatThrownBy(() -> invoke("auditar")).isInstanceOf(AuditQuotaExceededException.class);
        verifyNoInteractions(transport, persistence, users, incidents, history, concurrencia);
        verify(quota, never()).verificarPuedeCrear(any());
        assertThat(audits.count()).isOne();
    }

    @Test
    void quotaConsumedDuringCaptureIsRejectedByTheFinalLockedCheck() {
        doAnswer(i -> {
            assertReleasedAtCapture();
            consumeQuota(); // Simulate another completed request between pre-check and final transaction.
            return realFixture(i.getArgument(0), HTML);
        }).when(transport).capturar(any());
        assertThatThrownBy(() -> invoke("auditar")).isInstanceOf(AuditQuotaExceededException.class);
        verify(users).bloquearPorId(owner.getId());
        assertThat(audits.count()).isOne(); // Only the other request, never a partial URL audit.
        assertThat(incidents.count()).isZero();
        assertThat(history.count()).isZero();
    }

    @Test
    void adminIsExemptFromBothQuotaChecks() {
        owner = user(Role.ADMIN);
        consumeQuota();
        clearInvocations(users, audits);
        invoke("auditar");
        verify(users, never()).bloquearPorId(any());
        verify(audits, never()).countByUsuarioIdAndFechaCreacionGreaterThanEqual(any(), any());
        assertThat(audits.count()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLIENTE", "ADMIN"})
    void saturationWritesNothingAndDoesNotBypassCapacityForAdmin(String role) throws Exception {
        if (role.equals("ADMIN")) owner = user(Role.ADMIN);
        var entrada = new CountDownLatch(1);
        var salida = new CountDownLatch(1);
        try (var workers = new TrabajadoresCapturaDePrueba(1)) {
            var ocupado = workers.enviar(() -> concurrencia.ejecutar(() -> {
                entrada.countDown();
                try { TrabajadoresCapturaDePrueba.esperar(salida); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                return realFixture(TransporteLocalDePrueba.destino(URL), HTML);
            }));
            try {
                TrabajadoresCapturaDePrueba.esperar(entrada);
                assertThatThrownBy(() -> invoke("auditar")).isInstanceOfSatisfying(CapturaException.class,
                        e -> assertThat(e.motivo()).isEqualTo(CapturaException.Motivo.CAPACIDAD_AGOTADA));
                verify(quota).precomprobarPuedeCrear(owner);
                verifyNoInteractions(transport, persistence, history, incidents);
                verify(users, never()).bloquearPorId(any());
                assertNoAuditRows();
            } finally { salida.countDown(); }
            ocupado.get(5, TimeUnit.SECONDS);
        }
        invoke("auditar"); // Reuse capacity and persist normally after the owner exits.
        assertThat(audits.count()).isOne();
    }
}
