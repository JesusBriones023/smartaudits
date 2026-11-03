package com.smartaudits.service.url;

import com.smartaudits.model.Role;
import com.smartaudits.model.TipoFuente;
import com.smartaudits.model.Usuario;
import com.smartaudits.model.dto.AuditoriaResponse;
import com.smartaudits.service.AuditQuotaService;
import com.smartaudits.service.AuditoriaService;
import com.smartaudits.service.motor.MotorAnalisisLegal;
import com.smartaudits.v2.acquisition.capture.*;
import com.smartaudits.v2.acquisition.destination.DestinationUrl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.SocketTimeoutException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.smartaudits.v2.acquisition.capture.CapturaRealDePrueba.crear;
import static com.smartaudits.v2.acquisition.capture.TrabajadoresCapturaDePrueba.esperar;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Admission integration only. HTTP_REAL fixtures are synthetic, never public acquisitions. */
class IngestaUrlConcurrencyTest {
    private static final String URL = "https://www.ejemplo.es/legal";
    private static final String TIPO = "Aviso Legal";
    private static final String HTML = "<p>Responsable: Empresa de prueba. Contacto: test@example.invalid.</p>";
    private static final DestinationUrl DESTINO = TransporteLocalDePrueba.destino(URL);
    private final TransporteHtml transporte = mock(TransporteHtml.class);
    private final AuditoriaService persistencia = mock(AuditoriaService.class);
    private final AuditQuotaService cuota = mock(AuditQuotaService.class);
    private final MotorAnalisisLegal motor = spy(new MotorAnalisisLegal());
    private final ControlConcurrenciaCaptura control = new ControlConcurrenciaCaptura(1);
    private final Usuario usuario = new Usuario();
    private IngestaUrlService ingesta;

    @BeforeEach
    void setup() {
        usuario.setId(1L);
        usuario.setRole(Role.CLIENTE);
        ingesta = servicio();
        when(transporte.capturar(any())).thenAnswer(i -> crear(i.getArgument(0), DESTINO, HTML));
        when(persistencia.textoAuditableDeHtml(HTML)).thenReturn("Responsable: Empresa de prueba.");
        when(persistencia.crearAuditoriaAnalizada(anyString(), anyString(), anyString(), anyString(),
                eq(TipoFuente.CRAWLER), eq(usuario), isNull())).thenReturn(new AuditoriaResponse());
    }

    private IngestaUrlService servicio() {
        return new IngestaUrlService(transporte, persistencia, motor, cuota, control);
    }

    private Object invocar(IngestaUrlService servicio, String entrada) {
        return switch (entrada) {
            case "capturar" -> servicio.capturar(URL);
            case "analizar" -> servicio.analizar(URL, TIPO);
            case "auditar" -> servicio.auditar(URL, "Fixture", TIPO, usuario, null);
            default -> throw new AssertionError(entrada);
        };
    }

    @ParameterizedTest
    @ValueSource(strings = {"capturar", "analizar", "auditar"})
    void allEntriesAndServiceInstancesShareCapacityWithoutDoubleAdmission(String propietaria) throws Exception {
        var entrada = new CountDownLatch(1);
        var salida = new CountDownLatch(1);
        doAnswer(i -> {
            entrada.countDown();
            esperar(salida);
            return crear(i.getArgument(0), DESTINO, HTML);
        }).when(transporte).capturar(any());
        IngestaUrlService otraInstancia = servicio();
        try (var workers = new TrabajadoresCapturaDePrueba(1)) {
            var propietariaActiva = workers.enviar(() -> invocar(ingesta, propietaria));
            try {
                esperar(entrada);
                for (String otra : new String[]{"capturar", "analizar", "auditar"}) {
                    assertThatThrownBy(() -> invocar(otraInstancia, otra)).isInstanceOfSatisfying(CapturaException.class,
                            e -> assertThat(e.motivo()).isEqualTo(CapturaException.Motivo.CAPACIDAD_AGOTADA));
                }
                verify(transporte, times(1)).capturar(any());
                verifyNoInteractions(persistencia, motor);
            } finally { salida.countDown(); }
            assertThat(propietariaActiva.get(5, TimeUnit.SECONDS)).isNotNull();
        }
        assertThat(ingesta.capturar(URL)).isNotNull();
        verify(transporte, times(2)).capturar(any());
    }

    @Test
    void capacityIsReleasedBeforeExtractionAnalysisAndPersistence() {
        doAnswer(i -> {
            assertThat(control.ejecutar(() -> crear(DESTINO, DESTINO, HTML))).isNotNull();
            return "Responsable: Empresa de prueba.";
        }).when(persistencia).textoAuditableDeHtml(HTML);
        doAnswer(i -> {
            assertThat(control.ejecutar(() -> crear(DESTINO, DESTINO, HTML))).isNotNull();
            return i.callRealMethod();
        }).when(motor).analyze(any());
        doAnswer(i -> {
            assertThat(control.ejecutar(() -> crear(DESTINO, DESTINO, HTML))).isNotNull();
            return new AuditoriaResponse();
        }).when(persistencia).crearAuditoriaAnalizada(anyString(), anyString(), anyString(), anyString(),
                eq(TipoFuente.CRAWLER), eq(usuario), isNull());
        assertThat(invocar(ingesta, "analizar")).isNotNull();
        assertThat(invocar(ingesta, "auditar")).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "status", "type", "url"})
    void invalidResponsesReleaseCapacity(String caso) {
        CapturaHtml invalida = caso.equals("null") ? null : new CapturaHtml(
                caso.equals("url") ? TransporteLocalDePrueba.destino("https://www.ejemplo.es/otra") : DESTINO,
                DESTINO, caso.equals("status") ? 503 : 200,
                caso.equals("type") ? "application/pdf" : "text/html", HTML, java.time.Instant.EPOCH,
                ProcedenciaCaptura.simulada("fixture"));
        doReturn(invalida).when(transporte).capturar(any());
        assertThatThrownBy(() -> ingesta.capturar(URL)).isInstanceOfSatisfying(CapturaException.class,
                e -> assertThat(e.motivo()).isEqualTo(caso.equals("null")
                        ? CapturaException.Motivo.TRANSPORTE : CapturaException.Motivo.RESPUESTA_NO_VALIDA));
        doReturn(crear(DESTINO, DESTINO, HTML)).when(transporte).capturar(any());
        assertThat(ingesta.capturar(URL)).isNotNull();
    }

    @Test
    void transportFailuresKeepTheirClassificationCauseAndCapacityIsReusable() {
        var timeout = new CapturaException(CapturaException.Motivo.TRANSPORTE, "timeout", new SocketTimeoutException());
        doThrow(timeout).when(transporte).capturar(any());
        assertThat(catchThrowable(() -> ingesta.capturar(URL))).isSameAs(timeout);
        var fallo = new IllegalStateException("unexpected fixture");
        doThrow(fallo).when(transporte).capturar(any());
        assertThatThrownBy(() -> ingesta.capturar(URL)).isInstanceOfSatisfying(CapturaException.class, e -> {
            assertThat(e.motivo()).isEqualTo(CapturaException.Motivo.TRANSPORTE);
            assertThat(e.getCause()).isSameAs(fallo);
        });
        doReturn(crear(DESTINO, DESTINO, HTML)).when(transporte).capturar(any());
        assertThat(ingesta.capturar(URL)).isNotNull();
    }

    @Test
    void interruptedCaptureCannotReachExtractionOrPersistence() throws Exception {
        doAnswer(i -> {
            Thread.currentThread().interrupt();
            return crear(DESTINO, DESTINO, HTML);
        }).when(transporte).capturar(any());
        try (var workers = new TrabajadoresCapturaDePrueba(1)) {
            workers.enviar(() -> {
                assertThatThrownBy(() -> invocar(ingesta, "auditar")).isInstanceOfSatisfying(CapturaException.class,
                        e -> assertThat(e.motivo()).isEqualTo(CapturaException.Motivo.CANCELADA));
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
                return null;
            }).get(5, TimeUnit.SECONDS);
        }
        verifyNoInteractions(persistencia, motor);
        doReturn(crear(DESTINO, DESTINO, HTML)).when(transporte).capturar(any());
        assertThat(invocar(ingesta, "auditar")).isNotNull();
    }
}
