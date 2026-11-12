package com.smartaudits.service.url;

import com.smartaudits.model.Role;
import com.smartaudits.model.Usuario;
import com.smartaudits.service.AuditQuotaService;
import com.smartaudits.service.AuditoriaService;
import com.smartaudits.service.motor.AnalizadorLegal;
import com.smartaudits.v2.acquisition.capture.CapturaException;
import com.smartaudits.v2.acquisition.capture.CapturaException.Motivo;
import com.smartaudits.v2.acquisition.capture.ControlConcurrenciaCaptura;
import com.smartaudits.v2.acquisition.capture.LimitesAdquisicion;
import com.smartaudits.v2.acquisition.capture.ResolvedorConPlazo;
import com.smartaudits.v2.acquisition.capture.TrabajadoresCapturaDePrueba;
import com.smartaudits.v2.acquisition.capture.TransporteHttpReal;
import com.smartaudits.v2.acquisition.capture.http.ClienteHttpFijado;
import com.smartaudits.v2.acquisition.capture.http.Conector;
import com.smartaudits.v2.acquisition.capture.http.Conexion;
import com.smartaudits.v2.acquisition.capture.http.LimitesHttp;
import com.smartaudits.v2.acquisition.destination.DnsAnswer;
import com.smartaudits.v2.acquisition.destination.DnsAnswer.FamilyAnswer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.smartaudits.v2.acquisition.capture.TrabajadoresCapturaDePrueba.esperar;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * PV-15 through the unchanged PV-14 admission and PV-13 boundaries: the prepared real transport (not
 * wired in production) with a manual monotonic source, a scripted deadline-aware resolver and a
 * connector that must never be reached. Deterministic: latches, no sleeps, owned workers only.
 */
class IngestaUrlPlazoTest {
    static final String URL = "https://www.ejemplo.es/legal";
    static final DnsAnswer PUBLICA = DnsAnswer.of(FamilyAnswer.addresses(List.of(new byte[]{8, 8, 8, 8})), FamilyAnswer.nodata());
    static final DnsAnswer NXDOMAIN = DnsAnswer.of(FamilyAnswer.of(DnsAnswer.Status.NXDOMAIN, List.of(), List.of()),
            FamilyAnswer.of(DnsAnswer.Status.NXDOMAIN, List.of(), List.of()));

    final AtomicLong reloj = new AtomicLong();
    final AtomicReference<ResolvedorConPlazo> comportamiento = new AtomicReference<>();
    final AtomicInteger conexiones = new AtomicInteger();
    final AuditoriaService persistencia = mock(AuditoriaService.class);
    final AuditQuotaService cuota = mock(AuditQuotaService.class);
    final AnalizadorLegal motor = mock(AnalizadorLegal.class);
    final ControlConcurrenciaCaptura control = new ControlConcurrenciaCaptura(1);
    IngestaUrlService ingesta;

    @BeforeEach
    void setup() {
        Conector nunca = (ip, puerto, timeout) -> {
            conexiones.incrementAndGet();
            throw new java.io.IOException("no network in this test");
        };
        var cliente = new ClienteHttpFijado(nunca, mock(SSLSocketFactory.class), LimitesHttp.porDefecto());
        var transporte = new TransporteHttpReal((host, plazo) -> comportamiento.get().resolver(host, plazo), cliente,
                Clock.systemUTC(), LimitesAdquisicion.porDefecto(), reloj::get);
        ingesta = new IngestaUrlService(transporte, persistencia, motor, cuota, control);
    }

    /** Ignores interruption until released (a non-cooperative adapter), then restores the flag. */
    ResolvedorConPlazo bloqueante(CountDownLatch entrada, CountDownLatch liberar) {
        return (host, plazo) -> {
            entrada.countDown();
            boolean interrumpido = false;
            while (true) {
                try {
                    liberar.await();
                    break;
                } catch (InterruptedException e) {
                    interrumpido = true;
                }
            }
            if (interrumpido) {
                Thread.currentThread().interrupt();
            }
            return PUBLICA;
        };
    }

    static Motivo motivo(Throwable t) {
        assertThat(t).isInstanceOf(CapturaException.class);
        return ((CapturaException) t).motivo();
    }

    @Test
    void aBlockedCaptureKeepsItsPermitPastTheDeadlineUntilItReallyExits() throws Exception {
        var entrada = new CountDownLatch(1);
        var liberar = new CountDownLatch(1);
        comportamiento.set(bloqueante(entrada, liberar));
        try (var workers = new TrabajadoresCapturaDePrueba(1)) {
            var propietaria = workers.enviar(() -> ingesta.capturar(URL));
            try {
                esperar(entrada);
                reloj.addAndGet(Duration.ofSeconds(25).toNanos()); // the deadline passes while DNS is blocked
                assertThat(motivo(catchThrowable(() -> ingesta.capturar(URL)))).isEqualTo(Motivo.CAPACIDAD_AGOTADA);
            } finally {
                liberar.countDown();
            }
            Throwable resultado = catchThrowable(() -> propietaria.get(5, TimeUnit.SECONDS));
            assertThat(resultado).isInstanceOf(ExecutionException.class);
            assertThat(motivo(resultado.getCause())).isEqualTo(Motivo.PLAZO_AGOTADO);
        }
        // Released only by the real exit: the next capture is admitted (and rejected later by DNS, not capacity).
        comportamiento.set((host, plazo) -> NXDOMAIN);
        assertThat(motivo(catchThrowable(() -> ingesta.capturar(URL)))).isEqualTo(Motivo.URL_RECHAZADA);
        assertThat(conexiones.get()).isZero();
    }

    @Test
    void cancellingTheFutureOfANonCooperativeCaptureDoesNotReturnItsPermit() throws Exception {
        var entrada = new CountDownLatch(1);
        var liberar = new CountDownLatch(1);
        var salida = new CountDownLatch(1);
        var observado = new AtomicReference<Throwable>();
        comportamiento.set(bloqueante(entrada, liberar));
        try (var workers = new TrabajadoresCapturaDePrueba(1)) {
            var propietaria = workers.enviar(() -> {
                try {
                    return ingesta.capturar(URL);
                } catch (RuntimeException e) {
                    observado.set(e);
                    throw e;
                } finally {
                    salida.countDown();
                }
            });
            try {
                esperar(entrada);
                assertThat(propietaria.cancel(true)).isTrue();
                assertThat(motivo(catchThrowable(() -> ingesta.capturar(URL)))).isEqualTo(Motivo.CAPACIDAD_AGOTADA);
            } finally {
                liberar.countDown();
            }
            esperar(salida);
        }
        // The interrupted owner saw its cancellation, not a success, and only then released capacity.
        assertThat(motivo(observado.get())).isEqualTo(Motivo.CANCELADA);
        comportamiento.set((host, plazo) -> NXDOMAIN);
        assertThat(motivo(catchThrowable(() -> ingesta.capturar(URL)))).isEqualTo(Motivo.URL_RECHAZADA);
        assertThat(conexiones.get()).isZero();
    }

    /**
     * An in-memory connection (no network) that serves one HTML response; its close blocks until the
     * test releases it and then fails before releasing anything. The test releases it for real.
     */
    static final class ConexionQueNoCierra extends Socket {
        final CountDownLatch cerrando = new CountDownLatch(1);
        final CountDownLatch liberar = new CountDownLatch(1);
        final IOException fallo = new IOException("fixture raw close failure");
        private final byte[] respuesta;

        ConexionQueNoCierra(String html) {
            super(Proxy.NO_PROXY);
            byte[] cuerpo = html.getBytes(StandardCharsets.UTF_8);
            respuesta = ("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: " + cuerpo.length + "\r\n\r\n" + html)
                    .getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(respuesta);
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public synchronized void close() throws IOException {
            cerrando.countDown();
            try {
                liberar.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw fallo;
        }

        void liberarDeVerdad() throws IOException {
            super.close();
        }
    }

    @Test
    void aFailingRawCloseKeepsThePermitUntilTheCaptureReallyExits() throws Exception {
        var conexion = new ConexionQueNoCierra("<html><body><h1>Aviso legal</h1></body></html>");
        try {
            Conector enMemoria = (ip, puerto, timeout) ->
                    Conexion.declarada(conexion, new InetSocketAddress(InetAddress.getByAddress(new byte[]{8, 8, 8, 8}), puerto));
            var cliente = new ClienteHttpFijado(enMemoria, mock(SSLSocketFactory.class), LimitesHttp.porDefecto());
            var transporte = new TransporteHttpReal((host, plazo) -> PUBLICA, cliente, Clock.systemUTC(),
                    LimitesAdquisicion.porDefecto(), reloj::get);
            var propia = new IngestaUrlService(transporte, persistencia, motor, cuota, control); // the same admission control
            comportamiento.set((host, plazo) -> NXDOMAIN);
            try (var workers = new TrabajadoresCapturaDePrueba(1)) {
                var propietaria = workers.enviar(() -> propia.capturar("http://www.ejemplo.es/legal"));
                try {
                    esperar(conexion.cerrando); // the exchange is complete; its raw close is blocked
                    assertThat(propietaria.isDone()).isFalse();
                    assertThat(motivo(catchThrowable(() -> ingesta.capturar(URL)))).isEqualTo(Motivo.CAPACIDAD_AGOTADA);
                } finally {
                    conexion.liberar.countDown();
                }
                Throwable resultado = catchThrowable(() -> propietaria.get(5, TimeUnit.SECONDS));
                assertThat(resultado).isInstanceOf(ExecutionException.class);
                assertThat(motivo(resultado.getCause())).isEqualTo(Motivo.TRANSPORTE);
                assertThat(resultado.getCause()).hasMessageContaining("No se pudo cerrar la conexión");
                assertThat(resultado.getCause().getCause()).isSameAs(conexion.fallo);
            }
            // Released only by the real exit, after the failed close: the next capture is admitted.
            assertThat(motivo(catchThrowable(() -> ingesta.capturar(URL)))).isEqualTo(Motivo.URL_RECHAZADA);
            verifyNoInteractions(persistencia, motor);
            assertThat(conexiones.get()).isZero();
        } finally {
            conexion.liberarDeVerdad();
        }
    }

    @Test
    void anExpiredDeadlinePersistsNothingAndPassesThroughUnwrapped() {
        Usuario usuario = new Usuario();
        usuario.setId(7L);
        usuario.setRole(Role.CLIENTE);
        comportamiento.set((host, plazo) -> {
            reloj.addAndGet(Duration.ofSeconds(20).toNanos());
            return PUBLICA;
        });
        Throwable t = catchThrowable(() -> ingesta.auditar(URL, "Plazo", "Aviso Legal", usuario, null));
        assertThat(motivo(t)).isEqualTo(Motivo.PLAZO_AGOTADO);
        assertThat(t.getMessage()).doesNotContain("ejemplo");
        verify(cuota).precomprobarPuedeCrear(usuario);
        verify(persistencia, never()).textoAuditableDeHtml(any());
        verify(persistencia, never()).crearAuditoriaAnalizada(any(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(motor);
        assertThat(conexiones.get()).isZero();
        // The permit was returned when the capture failed.
        comportamiento.set((host, plazo) -> NXDOMAIN);
        assertThat(motivo(catchThrowable(() -> ingesta.capturar(URL)))).isEqualTo(Motivo.URL_RECHAZADA);
    }
}
