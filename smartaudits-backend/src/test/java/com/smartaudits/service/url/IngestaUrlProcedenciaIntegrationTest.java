package com.smartaudits.service.url;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartaudits.model.Role;
import com.smartaudits.model.TipoFuente;
import com.smartaudits.model.Usuario;
import com.smartaudits.repository.AuditoriaRepository;
import com.smartaudits.repository.HistorialAuditoriaRepository;
import com.smartaudits.repository.IncidenciaRepository;
import com.smartaudits.repository.UsuarioRepository;
import com.smartaudits.service.AuditQuotaService;
import com.smartaudits.service.AuditoriaService;
import com.smartaudits.service.HistorialService;
import com.smartaudits.service.motor.MotorAnalisisLegal;
import com.smartaudits.v2.acquisition.capture.CapturaException;
import com.smartaudits.v2.acquisition.capture.CapturaException.Motivo;
import com.smartaudits.v2.acquisition.capture.CapturaHtml;
import com.smartaudits.v2.acquisition.capture.ControlConcurrenciaCaptura;
import com.smartaudits.v2.acquisition.capture.ProcedenciaCaptura;
import com.smartaudits.v2.acquisition.capture.TransporteHtml;
import com.smartaudits.v2.acquisition.capture.TransporteLocalDePrueba;
import com.smartaudits.v2.acquisition.destination.DestinationUrl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Constructor;
import java.time.Instant;

import static com.smartaudits.v2.acquisition.capture.CapturaRealDePrueba.crear;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ACQ-PROVENANCE-01 at the persistence decision: a caller-written transport OUTSIDE the capture package
 * tries every way to present untrusted HTML as real. Real Spring persistence proxies, real quota service
 * and an isolated H2 pool; daily limit 1, so any consumed quota would block the positive follow-up. Every
 * capture is synthetic and in memory: no DNS, socket or public URL.
 */
@DataJpaTest(showSql = false, properties = {
        "spring.config.location=optional:classpath:/pv13-no-config.properties",
        "spring.datasource.url=jdbc:h2:mem:acq-provenance-01;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.hikari.minimum-idle=0", "spring.datasource.hikari.maximum-pool-size=2",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false", "spring.sql.init.mode=never",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect", "app.audit.daily-limit=1",
        "logging.level.root=WARN", "logging.level.org.springframework=WARN"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({AuditoriaService.class, HistorialService.class, AuditQuotaService.class, MotorAnalisisLegal.class,
        ObjectMapper.class, IngestaUrlService.class, IngestaUrlProcedenciaIntegrationTest.Admission.class})
class IngestaUrlProcedenciaIntegrationTest {
    @TestConfiguration
    static class Admission {
        /** Capacity 1: a rejected attempt that kept its permit would make the follow-up fail. */
        @Bean ControlConcurrenciaCaptura concurrencia() { return new ControlConcurrenciaCaptura(1); }
    }

    static final String URL = "https://www.ejemplo.es/legal";
    static final String FINAL_URL = "https://legal.ejemplo.es/v2/privacidad";
    static final String TIPO = "Política de Privacidad";
    static final String HTML = "<h1>Política de privacidad</h1><p>Responsable del tratamiento: Ejemplo S.L.</p>";
    static final String HTML_AJENO = "<h1>Aviso legal</h1><p>Texto arbitrario del llamante, nunca capturado.</p>";
    static final String RECHAZO = "procedencia real no se adjunta";

    @Autowired IngestaUrlService ingesta;
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
        cleanupRows();
        owner = user();
        clearInvocations(transport, quota, persistence, audits, users, incidents, history);
    }

    @AfterEach
    void cleanup() {
        cleanupRows();
    }

    private void cleanupRows() {
        history.deleteAllInBatch();
        incidents.deleteAllInBatch();
        audits.deleteAllInBatch();
        users.deleteAllInBatch();
    }

    private Usuario user() {
        Usuario u = new Usuario();
        u.setNombre("Fixture ACQ-PROVENANCE-01");
        u.setEmail("cliente@acq-provenance.example.invalid");
        u.setPassword("unused-test-fixture");
        u.setRole(Role.CLIENTE);
        return users.saveAndFlush(u);
    }

    private static DestinationUrl finalUrl() {
        return TransporteLocalDePrueba.destino(FINAL_URL);
    }

    /** What an arbitrary transport outside the capture package can build from a requested destination. */
    private static CapturaHtml intento(String forma, DestinationUrl requested) throws Exception {
        return switch (forma) {
            case "copied-provenance" -> new CapturaHtml(requested, finalUrl(), 200, "text/html", HTML_AJENO,
                    Instant.now(), crear(requested, finalUrl(), HTML).procedencia());
            case "rebuilt-real-capture" -> {
                CapturaHtml g = crear(requested, finalUrl(), HTML);
                yield new CapturaHtml(g.urlSolicitada(), g.urlFinal(), g.estadoHttp(), g.tipoContenido(), g.html(),
                        g.fechaCaptura(), g.procedencia());
            }
            case "relabelled-simulated" -> {
                CapturaHtml s = TransporteLocalDePrueba.captura(requested, finalUrl(), 200, "text/html", HTML_AJENO);
                yield new CapturaHtml(s.urlSolicitada(), s.urlFinal(), s.estadoHttp(), s.tipoContenido(), s.html(),
                        s.fechaCaptura(), crear(requested, finalUrl(), HTML).procedencia());
            }
            case "reflective-http-real-label" -> {
                Constructor<ProcedenciaCaptura> c = ProcedenciaCaptura.class.getDeclaredConstructor(
                        ProcedenciaCaptura.Origen.class, String.class, String.class);
                c.setAccessible(true);
                ProcedenciaCaptura forjada = c.newInstance(ProcedenciaCaptura.Origen.HTTP_REAL, "forjado", "rev");
                yield new CapturaHtml(requested, finalUrl(), 200, "text/html", HTML_AJENO, Instant.now(), forjada);
            }
            default -> throw new AssertionError(forma);
        };
    }

    private void assertNothingPersistedAndNoQuotaLock() {
        verify(persistence, never()).crearAuditoriaAnalizada(any(), any(), any(), any(), any(), any(), any());
        verify(quota, never()).verificarPuedeCrear(any());
        verify(users, never()).bloquearPorId(any());
        verify(audits, never()).save(any());
        assertThat(audits.count()).isZero();
        assertThat(incidents.count()).isZero();
        assertThat(history.count()).isZero();
    }

    /** Positive follow-up: limit 1 and capacity 1, so it succeeds only if nothing was consumed or kept. */
    private void assertTrustedCaptureStillPersistsOnce() {
        doAnswer(i -> crear(i.getArgument(0), finalUrl(), HTML)).when(transport).capturar(any());
        var response = ingesta.auditar(URL, "ACQ", TIPO, owner, "192.0.2.15");
        assertThat(response.getTipoFuente()).isEqualTo(TipoFuente.CRAWLER);
        assertThat(audits.count()).isOne();
    }

    @Test
    void aTrustedCapturePersistsWithBothQuotaChecksAndTheCallerAsOwner() {
        doAnswer(i -> crear(i.getArgument(0), finalUrl(), HTML)).when(transport).capturar(any());
        var response = ingesta.auditar(URL, "ACQ", TIPO, owner, "192.0.2.15");
        assertThat(response.getTipoFuente()).isEqualTo(TipoFuente.CRAWLER);
        assertThat(response.getUrlOpcional()).isEqualTo(FINAL_URL);
        var stored = audits.findById(response.getId()).orElseThrow();
        assertThat(stored.getUsuario().getId()).isEqualTo(owner.getId());
        assertThat(stored.getTipoFuente()).isEqualTo(TipoFuente.CRAWLER);
        assertThat(history.findAll()).singleElement().satisfies(h -> assertThat(h.getAccion()).isEqualTo("CREACION"));
        verify(quota).precomprobarPuedeCrear(owner);
        verify(quota).verificarPuedeCrear(owner);
        verify(users).bloquearPorId(owner.getId());
        // The limit of 1 is now used: the final locked check still governs a trusted capture.
        assertThatThrownBy(() -> ingesta.auditar(URL, "ACQ", TIPO, owner, null))
                .isInstanceOf(com.smartaudits.service.AuditQuotaExceededException.class);
        assertThat(audits.count()).isOne();
    }

    @Test
    void arbitraryHtmlWithASimulatedLabelIsAnalysableButNeverPersisted() {
        doAnswer(i -> TransporteLocalDePrueba.captura(i.getArgument(0), finalUrl(), 200, "text/html", HTML_AJENO))
                .when(transport).capturar(any());

        var analisis = ingesta.analizar(URL, TIPO);
        assertThat(analisis.captura().esRealVerificada()).isFalse();
        assertThat(analisis.texto()).contains("Texto arbitrario").doesNotContain("<");
        assertThat(analisis.resultado()).isNotNull();

        assertThatThrownBy(() -> ingesta.auditar(URL, "ACQ", TIPO, owner, null)).isInstanceOfSatisfying(
                CapturaException.class, e -> assertThat(e.motivo()).isEqualTo(Motivo.CAPTURA_SIMULADA));
        assertNothingPersistedAndNoQuotaLock();
        assertTrustedCaptureStillPersistsOnce();
    }

    @ParameterizedTest
    @ValueSource(strings = {"copied-provenance", "rebuilt-real-capture", "relabelled-simulated", "reflective-http-real-label"})
    void forgedRelabelledOrRebuiltCapturesNeverReachPersistence(String forma) {
        doAnswer(i -> intento(forma, i.getArgument(0))).when(transport).capturar(any());

        assertThatThrownBy(() -> ingesta.auditar(URL, "ACQ", TIPO, owner, null)).isInstanceOfSatisfying(
                CapturaException.class, e -> {
                    assertThat(e.motivo()).isEqualTo(Motivo.TRANSPORTE);
                    assertThat(e.getCause()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(RECHAZO);
                });
        verify(transport).capturar(TransporteLocalDePrueba.destino(URL));
        verify(quota).precomprobarPuedeCrear(owner); // read-only pre-check, as before; it reserves nothing
        assertNothingPersistedAndNoQuotaLock();
        // Analysis cannot be fed the forged capture either: it is never constructed.
        assertThatThrownBy(() -> ingesta.analizar(URL, TIPO)).isInstanceOfSatisfying(
                CapturaException.class, e -> assertThat(e.motivo()).isEqualTo(Motivo.TRANSPORTE));
        assertTrustedCaptureStillPersistsOnce();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PLAZO_AGOTADO", "CANCELADA", "URL_RECHAZADA", "runtime"})
    void failedAcquisitionPersistsNothingAndKeepsItsClassification(String fallo) {
        RuntimeException error = fallo.equals("runtime") ? new IllegalStateException("fixture transport failure")
                : new CapturaException(Motivo.valueOf(fallo), "fixture " + fallo);
        doThrow(error).when(transport).capturar(any());

        var thrown = catchCapturaException(() -> ingesta.auditar(URL, "ACQ", TIPO, owner, null));
        if (fallo.equals("runtime")) {
            assertThat(thrown.motivo()).isEqualTo(Motivo.TRANSPORTE);
            assertThat(thrown.getCause()).isSameAs(error);
        } else {
            assertThat(thrown).isSameAs(error); // PV-15/PV-14 classifications pass through unchanged
        }
        assertNothingPersistedAndNoQuotaLock();
        assertTrustedCaptureStillPersistsOnce();
    }

    private static CapturaException catchCapturaException(Runnable r) {
        try {
            r.run();
        } catch (CapturaException e) {
            return e;
        }
        throw new AssertionError("expected CapturaException");
    }
}
