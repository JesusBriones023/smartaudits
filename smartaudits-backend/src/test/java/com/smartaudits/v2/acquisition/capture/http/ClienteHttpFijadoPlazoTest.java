package com.smartaudits.v2.acquisition.capture.http;

import com.smartaudits.v2.acquisition.capture.CapturaException;
import com.smartaudits.v2.acquisition.capture.CapturaException.Motivo;
import com.smartaudits.v2.acquisition.capture.PlazoCaptura;
import com.smartaudits.v2.acquisition.capture.TrabajadoresCapturaDePrueba;
import com.smartaudits.v2.acquisition.destination.DestinationUrl;
import com.smartaudits.v2.acquisition.destination.IpAddress;
import com.smartaudits.v2.acquisition.destination.UrlParseResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static com.smartaudits.v2.acquisition.capture.TrabajadoresCapturaDePrueba.esperar;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PV-15 connection deadline, watchdog and socket ownership of {@link ClienteHttpFijado}. Unless marked
 * REAL TIME, cases are deterministic: a manual monotonic source and a manual watchdog that fires only
 * when the test says so. Loopback servers only.
 */
class ClienteHttpFijadoPlazoTest {
    static final String HOST = "www.ejemplo.es";
    static final DestinationUrl HTTP = url("http://www.ejemplo.es/legal");
    static final DestinationUrl HTTPS = url("https://www.ejemplo.es/legal");
    static final IpAddress LOCAL = IpAddress.ipv4(new byte[]{127, 0, 0, 1});
    static final String PAGINA = "<html><body><h1>Aviso legal</h1></body></html>";
    /** Wide per-connection limits: the acquisition deadline is the binding one. */
    static final LimitesHttp AMPLIOS = new LimitesHttp(Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(15),
            32 * 1024, 100, 64 * 1024, 128 * 1024, 3);

    static PkiDePrueba.Credencial ca;
    static SSLContext confianza;

    final AtomicLong reloj = new AtomicLong();
    final List<AutoCloseable> recursos = Collections.synchronizedList(new ArrayList<>());
    final ConectorGrabador conector = new ConectorGrabador();

    @BeforeAll
    static void pki() throws Exception {
        ca = PkiDePrueba.ca("CA plazo SmartAudits");
        confianza = PkiDePrueba.cliente(ca);
    }

    @AfterEach
    void cerrar() throws Exception {
        List<AutoCloseable> propios;
        synchronized (recursos) {
            propios = List.copyOf(recursos);
        }
        desmontar(null, propios.stream().<Paso>map(r -> r::close).toArray(Paso[]::new));
    }

    /** One teardown step; it may fail in any way. */
    interface Paso {
        void ejecutar() throws Exception;
    }

    /**
     * Runs every step, in order, even when earlier ones fail, with the precedence of the production
     * cleanup. With a primary failure of the test body, the cleanup failures are attached to it as
     * suppressed and it stays the one the caller throws; a cleanup Error over a non-Error primary is
     * thrown instead, carrying the primary. Without a primary the first cleanup failure (an Error
     * first) is thrown with the others attached. An interruption seen here is restored.
     */
    static void desmontar(Throwable primaria, Paso... pasos) throws Exception {
        Throwable fallo = null;
        for (Paso paso : pasos) {
            try {
                paso.ejecutar();
            } catch (Throwable t) {
                if (t instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                fallo = acumularFallo(fallo, t);
            }
        }
        if (fallo == null) {
            return;
        }
        if (primaria == null) {
            if (fallo instanceof Error error) {
                throw error;
            }
            throw (Exception) fallo;
        }
        if (fallo instanceof Error error && !(primaria instanceof Error)) {
            error.addSuppressed(primaria);
            throw error;
        }
        if (fallo != primaria) {
            primaria.addSuppressed(fallo);
        }
    }

    private static Throwable acumularFallo(Throwable acumulado, Throwable nuevo) {
        if (acumulado == null || acumulado == nuevo) {
            return nuevo;
        }
        if (nuevo instanceof Error && !(acumulado instanceof Error)) {
            nuevo.addSuppressed(acumulado);
            return nuevo;
        }
        acumulado.addSuppressed(nuevo);
        return acumulado;
    }

    static DestinationUrl url(String texto) {
        return ((UrlParseResult.Parsed) DestinationUrl.parse(texto)).url();
    }

    PlazoCaptura plazo(Duration total) {
        return PlazoCaptura.iniciar(total, reloj::get);
    }

    ServidorHttpDePrueba plano(ServidorHttpDePrueba.Manejador m) throws IOException {
        var s = ServidorHttpDePrueba.plano(m);
        recursos.add(s);
        return s;
    }

    ServidorHttpDePrueba tls(ServidorHttpDePrueba.Manejador m) throws Exception {
        var s = ServidorHttpDePrueba.tls(PkiDePrueba.servidor(PkiDePrueba.hoja(ca, HOST), ca), m);
        recursos.add(s);
        return s;
    }

    ClienteHttpFijado cliente(int puerto, Vigilante vigilante) {
        return new ClienteHttpFijado(conector, confianza.getSocketFactory(), AMPLIOS, p -> puerto, vigilante);
    }

    static CapturaException falla(Runnable accion, Motivo motivo, String mensaje) {
        Throwable t = catchThrowable(accion::run);
        assertThat(t).isInstanceOf(CapturaException.class);
        CapturaException e = (CapturaException) t;
        assertThat(e.motivo()).as(e.getMessage()).isEqualTo(motivo);
        assertThat(e.getMessage()).contains(mensaje);
        return e;
    }

    /** Bounded wait for a condition observed by a fixture thread (polling, never a fixed sleep). */
    static void esperarHasta(BooleanSupplier condicion) throws InterruptedException {
        long fin = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condicion.getAsBoolean()) {
            assertThat(fin - System.nanoTime()).as("condición de la prueba").isPositive();
            Thread.sleep(10);
        }
    }

    /** Records alarms and runs one only when the test fires it. */
    static final class VigilanteManual implements Vigilante {
        final List<Long> retrasos = Collections.synchronizedList(new ArrayList<>());
        final List<Runnable> acciones = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger cancelaciones = new AtomicInteger();

        @Override
        public Alarma programar(Runnable accion, long retrasoNanos) {
            retrasos.add(retrasoNanos);
            acciones.add(accion);
            return cancelaciones::incrementAndGet;
        }
    }

    /** The production connector, recording each timeout and keeping the sockets it opened. */
    static final class ConectorGrabador implements Conector {
        final List<Duration> timeouts = Collections.synchronizedList(new ArrayList<>());
        final List<Socket> sockets = Collections.synchronizedList(new ArrayList<>());
        private final ConectorDirecto directo = new ConectorDirecto();

        @Override
        public Conexion conectar(IpAddress ip, int puerto, Duration timeout) throws IOException {
            timeouts.add(timeout);
            Conexion c = directo.conectar(ip, puerto, timeout);
            sockets.add(c.socket());
            return c;
        }
    }

    // ------------------------------------------------------------------ connect budget (J, K)

    @Test
    void connectGetsTheSmallerOfItsOwnLimitAndTheAcquisitionRest() {
        List<Duration> presupuestos = new ArrayList<>();
        Conector sinRespuesta = (ip, puerto, timeout) -> {
            presupuestos.add(timeout);
            throw new SocketTimeoutException("connect timed out");
        };
        var c = new ClienteHttpFijado(sinRespuesta, mock(SSLSocketFactory.class), AMPLIOS, p -> 1, new VigilanteManual());
        PlazoCaptura casiAgotado = plazo(Duration.ofSeconds(10));
        reloj.addAndGet(Duration.ofMillis(9_700).toNanos());
        // An ordinary connect timeout before the acquisition deadline keeps its TRANSPORTE meaning.
        falla(() -> c.obtener(HTTP, LOCAL, casiAgotado), Motivo.TRANSPORTE, "No se pudo conectar");
        falla(() -> c.obtener(HTTP, LOCAL, plazo(Duration.ofSeconds(20))), Motivo.TRANSPORTE, "No se pudo conectar");
        assertThat(presupuestos).containsExactly(Duration.ofMillis(300), Duration.ofSeconds(5));
    }

    @Test
    void aConnectFailureOnceTheAcquisitionHasExpiredIsTheDeadline() {
        PlazoCaptura adquisicion = plazo(Duration.ofSeconds(2));
        Conector lento = (ip, puerto, timeout) -> {
            reloj.addAndGet(Duration.ofSeconds(2).toNanos());
            throw new SocketTimeoutException("connect timed out");
        };
        var c = new ClienteHttpFijado(lento, mock(SSLSocketFactory.class), AMPLIOS, p -> 1, new VigilanteManual());
        var e = falla(() -> c.obtener(HTTP, LOCAL, adquisicion), Motivo.PLAZO_AGOTADO, ClienteHttpFijado.MENSAJE_PLAZO);
        assertThat(e).hasCauseInstanceOf(SocketTimeoutException.class);
    }

    @Test
    void anExpiredAcquisitionNeverDials() {
        PlazoCaptura adquisicion = plazo(Duration.ofSeconds(1));
        reloj.addAndGet(Duration.ofSeconds(1).toNanos());
        var vigilante = new VigilanteManual();
        falla(() -> cliente(1, vigilante).obtener(HTTP, LOCAL, adquisicion), Motivo.PLAZO_AGOTADO, ClienteHttpFijado.MENSAJE_PLAZO);
        assertThat(conector.timeouts).isEmpty();
        assertThat(vigilante.retrasos).isEmpty();
    }

    // ------------------------------------------------------------------ watchdog and ownership (S, T, U, V, W)

    @Test
    void theAlarmStaysArmedUntilTheTlsAndRawSocketsAreClosed() throws Exception {
        var servidor = tls(ServidorHttpDePrueba.html(PAGINA));
        List<Boolean> cerradoAlCancelar = Collections.synchronizedList(new ArrayList<>());
        Vigilante comprobador = (accion, retraso) -> () -> cerradoAlCancelar.add(conector.sockets.get(0).isClosed());
        assertThat(cliente(servidor.puerto(), comprobador).obtener(HTTPS, LOCAL, plazo(Duration.ofSeconds(20))).estado())
                .isEqualTo(200);
        // The raw socket is closed last (after the TLS close_notify), so it being closed proves both are.
        assertThat(cerradoAlCancelar).containsExactly(true);
        // The graceful TLS close is still seen by the server as the client hanging up.
        esperarHasta(() -> servidor.cerradasPorCliente() == 1);
    }

    @Test
    void theWatchdogIsArmedForTheEarlierDeadlineAndCancelledExactlyOnce() throws Exception {
        var servidor = plano(ServidorHttpDePrueba.html(PAGINA));
        var vigilante = new VigilanteManual();
        PlazoCaptura adquisicion = plazo(Duration.ofSeconds(20));
        reloj.addAndGet(Duration.ofSeconds(17).toNanos()); // 3 s left, less than the 15 s connection total
        RespuestaHttp r = cliente(servidor.puerto(), vigilante).obtener(HTTP, LOCAL, adquisicion);
        assertThat(r.estado()).isEqualTo(200);
        assertThat(vigilante.retrasos).containsExactly(Duration.ofSeconds(3).toNanos());
        assertThat(conector.timeouts).containsExactly(Duration.ofSeconds(3));
        assertThat(vigilante.cancelaciones.get()).isEqualTo(1);
        assertThat(conector.sockets).allMatch(Socket::isClosed);
        // Without an acquisition deadline the connection keeps its own total, as before PV-15.
        var sinAdquisicion = new VigilanteManual();
        assertThat(cliente(servidor.puerto(), sinAdquisicion).obtener(HTTP, LOCAL).estado()).isEqualTo(200);
        assertThat(sinAdquisicion.retrasos.get(0)).isBetween(Duration.ofSeconds(14).toNanos(), Duration.ofSeconds(15).toNanos());
        assertThat(sinAdquisicion.cancelaciones.get()).isEqualTo(1);
    }

    @Test
    void aFailureToArmTheWatchdogStillClosesTheConnectionAndAnErrorKeepsItsIdentity() throws Exception {
        var servidor = plano(ServidorHttpDePrueba.html(PAGINA));
        Vigilante rechaza = (accion, retraso) -> {
            throw new RejectedExecutionException("watchdog stopped");
        };
        falla(() -> cliente(servidor.puerto(), rechaza).obtener(HTTP, LOCAL, plazo(Duration.ofSeconds(20))),
                Motivo.TRANSPORTE, "No se pudo vigilar el plazo");
        AssertionError fatal = new AssertionError("fatal fixture error");
        Vigilante revienta = (accion, retraso) -> {
            throw fatal;
        };
        assertThat(catchThrowable(() -> cliente(servidor.puerto(), revienta).obtener(HTTP, LOCAL, plazo(Duration.ofSeconds(20)))))
                .isSameAs(fatal);
        assertThat(conector.sockets).hasSize(2).allMatch(Socket::isClosed);
        esperarHasta(() -> servidor.cerradasPorCliente() == 2);
        assertThat(servidor.peticiones()).isEmpty();
    }

    @Test
    void anUnexpectedRuntimeFailureClosesTheSocketCancelsTheAlarmAndIsNotConverted() throws Exception {
        var servidor = plano(ServidorHttpDePrueba.html(PAGINA));
        IllegalStateException fallo = new IllegalStateException("unexpected TLS factory failure");
        SSLSocketFactory fabrica = mock(SSLSocketFactory.class);
        when(fabrica.createSocket(any(Socket.class), anyString(), anyInt(), anyBoolean())).thenThrow(fallo);
        var vigilante = new VigilanteManual();
        var c = new ClienteHttpFijado(conector, fabrica, AMPLIOS, p -> servidor.puerto(), vigilante);
        assertThat(catchThrowable(() -> c.obtener(HTTPS, LOCAL, plazo(Duration.ofSeconds(20))))).isSameAs(fallo);
        assertThat(conector.sockets).hasSize(1).allMatch(Socket::isClosed);
        assertThat(vigilante.cancelaciones.get()).isEqualTo(1);
    }

    @Test
    void aWatchdogCloseAtTheDeadlineIsReportedAsTheDeadlineAndTheSecondCloseIsHarmless() throws Exception {
        var peticion = new CountDownLatch(1);
        var liberar = new CountDownLatch(1);
        var servidor = plano((p, out) -> {
            peticion.countDown();
            liberar.await(5, TimeUnit.SECONDS);
        });
        var vigilante = new VigilanteManual();
        PlazoCaptura adquisicion = plazo(Duration.ofSeconds(10));
        try (var workers = new TrabajadoresCapturaDePrueba(1)) {
            var resultado = workers.enviar(() -> catchThrowable(() -> cliente(servidor.puerto(), vigilante).obtener(HTTP, LOCAL, adquisicion)));
            try {
                esperar(peticion);
                reloj.addAndGet(Duration.ofSeconds(10).toNanos()); // the deadline is reached ...
                vigilante.acciones.get(0).run(); // ... and the watchdog closes the raw socket
                Throwable t = resultado.get(5, TimeUnit.SECONDS);
                assertThat(t).isInstanceOf(CapturaException.class);
                assertThat(((CapturaException) t).motivo()).isEqualTo(Motivo.PLAZO_AGOTADO);
            } finally {
                liberar.countDown();
            }
        }
        assertThat(vigilante.cancelaciones.get()).isEqualTo(1);
        assertThat(conector.sockets).hasSize(1).allMatch(Socket::isClosed);
    }

    @Test
    void aCloseBeforeAnyDeadlineIsACommunicationFailureNotATimeout() throws Exception {
        var peticion = new CountDownLatch(1);
        var liberar = new CountDownLatch(1);
        var servidor = plano((p, out) -> {
            peticion.countDown();
            liberar.await(5, TimeUnit.SECONDS);
        });
        var vigilante = new VigilanteManual();
        try (var workers = new TrabajadoresCapturaDePrueba(1)) {
            var resultado = workers.enviar(() -> catchThrowable(() ->
                    cliente(servidor.puerto(), vigilante).obtener(HTTP, LOCAL, plazo(Duration.ofSeconds(10)))));
            try {
                esperar(peticion);
                vigilante.acciones.get(0).run();
                Throwable t = resultado.get(5, TimeUnit.SECONDS);
                assertThat(((CapturaException) t).motivo()).isEqualTo(Motivo.TRANSPORTE);
                assertThat(t.getMessage()).contains("Error de comunicación");
            } finally {
                liberar.countDown();
            }
        }
    }

    @Test
    void aStaleAlarmOnlyEverClosesItsOwnConnection() throws Exception {
        var vigilante = new VigilanteManual();
        var numero = new AtomicInteger();
        byte[] cuerpo = PAGINA.getBytes(StandardCharsets.UTF_8);
        var servidor = plano((p, out) -> {
            if (numero.incrementAndGet() == 2) {
                vigilante.acciones.get(0).run(); // the first attempt's alarm fires late, during the second
            }
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: " + cuerpo.length + "\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.write(cuerpo);
        });
        var c = cliente(servidor.puerto(), vigilante);
        assertThat(c.obtener(HTTP, LOCAL, plazo(Duration.ofSeconds(20))).estado()).isEqualTo(200);
        RespuestaHttp segunda = c.obtener(HTTP, LOCAL, plazo(Duration.ofSeconds(20)));
        assertThat(segunda.estado()).isEqualTo(200);
        assertThat(new String(segunda.cuerpo(), StandardCharsets.UTF_8)).isEqualTo(PAGINA);
        assertThat(vigilante.cancelaciones.get()).isEqualTo(2);
    }

    @Test
    void theProductionWatchdogKeepsOneDaemonThreadAndNoCancelledAlarm() throws Exception {
        var propio = new ClienteHttpFijado.VigilanteDelSistema("pv15-vigilante-de-prueba");
        try {
            var bueno = plano(ServidorHttpDePrueba.html(PAGINA));
            var malo = plano(ServidorHttpDePrueba.fija("HTTP/1.1 200 OK\nContent-Length: 0\r\n\r\n"));
            for (int i = 0; i < 20; i++) {
                assertThat(cliente(bueno.puerto(), propio).obtener(HTTP, LOCAL, plazo(Duration.ofSeconds(20))).estado()).isEqualTo(200);
                assertThat(propio.pendientes()).isZero();
            }
            for (int i = 0; i < 5; i++) {
                falla(() -> cliente(malo.puerto(), propio).obtener(HTTP, LOCAL, plazo(Duration.ofSeconds(20))),
                        Motivo.RESPUESTA_NO_VALIDA, "LF sin CR");
                assertThat(propio.pendientes()).isZero();
            }
            List<Thread> hilos = Thread.getAllStackTraces().keySet().stream()
                    .filter(h -> h.getName().equals("pv15-vigilante-de-prueba")).toList();
            assertThat(hilos).hasSize(1).allMatch(Thread::isDaemon);
            assertThat(conector.sockets).hasSize(25).allMatch(Socket::isClosed);
        } finally {
            propio.detener();
        }
    }

    // ------------------------------------------------------------------ classification precedence (Q)

    @Test
    void responseAndCertificateFactsOutrankALateTimingCheck() {
        PlazoCaptura vencida = plazo(Duration.ofSeconds(1));
        reloj.addAndGet(Duration.ofSeconds(1).toNanos());
        PlazoCaptura vigente = plazo(Duration.ofSeconds(10));
        IOException marco = new ErrorRespuesta("respuesta demasiado grande");
        SSLHandshakeException certificado = new SSLHandshakeException("PKIX path validation failed");
        certificado.initCause(new CertificateException("untrusted"));
        IOException cerrado = new SocketException("Socket closed");
        IOException handshakeCortado = new SSLException("Connection reset during handshake");

        assertClasificado(ClienteHttpFijado.clasificar(marco, vencida, vencida), marco, Motivo.RESPUESTA_NO_VALIDA, "demasiado grande");
        assertClasificado(ClienteHttpFijado.clasificar(certificado, vencida, vencida), certificado, Motivo.TRANSPORTE, "identidad TLS");
        assertClasificado(ClienteHttpFijado.clasificar(cerrado, vencida, vencida), cerrado, Motivo.PLAZO_AGOTADO, ClienteHttpFijado.MENSAJE_PLAZO);
        assertClasificado(ClienteHttpFijado.clasificar(handshakeCortado, vencida, vencida), handshakeCortado, Motivo.PLAZO_AGOTADO,
                ClienteHttpFijado.MENSAJE_PLAZO);
        // This connection's own limit reached first: an ordinary timeout, not the acquisition deadline.
        IOException lectura = new SocketTimeoutException("Read timed out");
        assertClasificado(ClienteHttpFijado.clasificar(lectura, vencida, vigente), lectura, Motivo.TRANSPORTE, "Tiempo de espera agotado");
        assertClasificado(ClienteHttpFijado.clasificar(lectura, vigente, vigente), lectura, Motivo.TRANSPORTE, "Tiempo de espera agotado");
        assertClasificado(ClienteHttpFijado.clasificar(lectura, vencida, null), lectura, Motivo.TRANSPORTE, "Tiempo de espera agotado");
        assertClasificado(ClienteHttpFijado.clasificar(handshakeCortado, vigente, vigente), handshakeCortado, Motivo.TRANSPORTE,
                ClienteHttpFijado.MENSAJE_TLS);
        IOException red = new IOException("broken pipe");
        assertClasificado(ClienteHttpFijado.clasificar(red, vigente, vigente), red, Motivo.TRANSPORTE, "Error de comunicación");
    }

    static void assertClasificado(CapturaException e, IOException causa, Motivo motivo, String mensaje) {
        assertThat(e.motivo()).isEqualTo(motivo);
        assertThat(e.getMessage()).contains(mensaje);
        assertThat(e.getCause()).isSameAs(causa);
    }

    // ------------------------------------------------------------------ R4: TLS diagnostics

    @Test
    void onlyAuthenticationFailuresClaimAnInvalidTlsIdentity() {
        PlazoCaptura vencida = plazo(Duration.ofSeconds(1));
        reloj.addAndGet(Duration.ofSeconds(1).toNanos());
        PlazoCaptura vigente = plazo(Duration.ofSeconds(10));
        SSLPeerUnverifiedException sinVerificar = new SSLPeerUnverifiedException("peer not authenticated");
        SSLHandshakeException cadena = new SSLHandshakeException("PKIX path building failed");
        cadena.initCause(new CertificateException("untrusted chain"));
        SSLException anidada = new SSLException("wrapped alert");
        anidada.initCause(new IOException("decoding", new CertificateException("no subject alternative DNS name matching")));
        for (IOException identidad : List.<IOException>of(sinVerificar, cadena, anidada)) {
            for (PlazoCaptura adquisicion : java.util.Arrays.asList(vigente, vencida, null)) {
                CapturaException e = ClienteHttpFijado.clasificar(identidad, adquisicion == null ? vencida : adquisicion, adquisicion);
                assertThat(e.motivo()).isEqualTo(Motivo.TRANSPORTE);
                assertThat(e.getMessage()).isEqualTo(ClienteHttpFijado.MENSAJE_IDENTIDAD_TLS);
                assertThat(e.getCause()).isSameAs(identidad);
            }
        }
        SSLHandshakeException sinCifrado = new SSLHandshakeException("Received fatal alert: handshake_failure");
        SSLException protocolo = new SSLException("Unsupported or unrecognized SSL message");
        SSLException reiniciada = new SSLException("Connection reset");
        for (IOException generica : List.<IOException>of(sinCifrado, protocolo, reiniciada)) {
            CapturaException e = ClienteHttpFijado.clasificar(generica, vigente, vigente);
            assertThat(e.motivo()).isEqualTo(Motivo.TRANSPORTE);
            assertThat(e.getMessage()).isEqualTo(ClienteHttpFijado.MENSAJE_TLS).doesNotContain("identidad");
            assertThat(e.getCause()).isSameAs(generica);
            // Watchdog-induced TLS I/O after the acquisition deadline stays the deadline.
            assertClasificado(ClienteHttpFijado.clasificar(generica, vencida, vencida), generica, Motivo.PLAZO_AGOTADO,
                    ClienteHttpFijado.MENSAJE_PLAZO);
            // This connection's own limit first: an ordinary timeout.
            assertClasificado(ClienteHttpFijado.clasificar(generica, vencida, vigente), generica, Motivo.TRANSPORTE,
                    "Tiempo de espera agotado");
        }
    }

    @Test
    void aGenericHandshakeFailureIsReportedAsATlsFailureWithoutClaimingAnIdentityProblem() throws Exception {
        var servidor = plano((p, out) -> { });
        SSLHandshakeException alerta = new SSLHandshakeException("Received fatal alert: protocol_version");
        var c = new ClienteHttpFijado(conector, fabricaDe(tlsQueFallaElHandshake(alerta)), AMPLIOS, p -> servidor.puerto(),
                new VigilanteManual());
        var e = falla(() -> c.obtener(HTTPS, LOCAL, plazo(Duration.ofSeconds(20))), Motivo.TRANSPORTE, ClienteHttpFijado.MENSAJE_TLS);
        assertThat(e.getMessage()).doesNotContain("identidad");
        assertThat(e.getCause()).isSameAs(alerta);
        // IdentidadTls rejecting a certificate JSSE accepted keeps the identity message.
        SSLSession sesion = mock(SSLSession.class);
        when(sesion.getPeerCertificates()).thenReturn(new Certificate[]{PkiDePrueba.hoja(ca, "otro.ejemplo.es").certificado()});
        var rechazada = new ClienteHttpFijado(conector, fabricaDe(tlsSimulado(sesion)), AMPLIOS, p -> servidor.puerto(),
                new VigilanteManual());
        var identidad = falla(() -> rechazada.obtener(HTTPS, LOCAL, plazo(Duration.ofSeconds(20))), Motivo.TRANSPORTE,
                ClienteHttpFijado.MENSAJE_IDENTIDAD_TLS);
        assertThat(identidad.getCause()).isNull();
        assertThat(conector.sockets).hasSize(2).allMatch(Socket::isClosed);
    }

    // ------------------------------------------------------------------ F1: unauthenticated peer

    @Test
    void anUnauthenticatedPeerStaysATlsIdentityFailureEvenAfterTheDeadline() {
        PlazoCaptura vencida = plazo(Duration.ofSeconds(1));
        reloj.addAndGet(Duration.ofSeconds(1).toNanos());
        PlazoCaptura vigente = plazo(Duration.ofSeconds(10));
        // getPeerCertificates() throws this with no CertificateException anywhere in its chain.
        SSLPeerUnverifiedException sinVerificar = new SSLPeerUnverifiedException("peer not authenticated");
        assertThat(sinVerificar.getCause()).isNull();
        assertClasificado(ClienteHttpFijado.clasificar(sinVerificar, vigente, vigente), sinVerificar, Motivo.TRANSPORTE, "identidad TLS");
        assertClasificado(ClienteHttpFijado.clasificar(sinVerificar, vencida, vencida), sinVerificar, Motivo.TRANSPORTE, "identidad TLS");
        assertClasificado(ClienteHttpFijado.clasificar(sinVerificar, vencida, null), sinVerificar, Motivo.TRANSPORTE, "identidad TLS");
        // A plain timeout or a generic TLS I/O failure keeps its timing meaning.
        IOException lectura = new SocketTimeoutException("Read timed out");
        assertClasificado(ClienteHttpFijado.clasificar(lectura, vencida, vencida), lectura, Motivo.PLAZO_AGOTADO, ClienteHttpFijado.MENSAJE_PLAZO);
        IOException generica = new SSLException("Connection reset");
        assertClasificado(ClienteHttpFijado.clasificar(generica, vencida, vencida), generica, Motivo.PLAZO_AGOTADO, ClienteHttpFijado.MENSAJE_PLAZO);
    }

    @Test
    void anUnauthenticatedSessionAfterAHandshakeThatOutlivedTheDeadlineIsNotReportedAsTheDeadline() throws Exception {
        var servidor = plano((p, out) -> {
            // never reached: the TLS layer is simulated over the real raw connection
        });
        SSLPeerUnverifiedException sinVerificar = new SSLPeerUnverifiedException("peer not authenticated");
        SSLSession sesion = mock(SSLSession.class);
        when(sesion.getPeerCertificates()).thenThrow(sinVerificar);
        SSLSocket ssl = tlsSimulado(sesion);
        doAnswer(i -> {
            reloj.addAndGet(Duration.ofSeconds(30).toNanos()); // the handshake ends after the deadline
            return null;
        }).when(ssl).startHandshake();
        var vigilante = new VigilanteManual();
        var c = new ClienteHttpFijado(conector, fabricaDe(ssl), AMPLIOS, p -> servidor.puerto(), vigilante);
        var e = falla(() -> c.obtener(HTTPS, LOCAL, plazo(Duration.ofSeconds(20))), Motivo.TRANSPORTE, "identidad TLS");
        assertThat(e.getCause()).isSameAs(sinVerificar);
        assertThat(conector.sockets).hasSize(1).allMatch(Socket::isClosed);
        assertThat(vigilante.cancelaciones.get()).isEqualTo(1);
    }

    /** A TLS layer simulated over the real raw socket; it never touches the network itself. */
    static SSLSocket tlsSimulado(SSLSession sesion) {
        SSLSocket ssl = mock(SSLSocket.class);
        when(ssl.getSSLParameters()).thenAnswer(i -> new SSLParameters());
        when(ssl.getSupportedProtocols()).thenReturn(new String[]{"TLSv1.3", "TLSv1.2"});
        when(ssl.getSession()).thenReturn(sesion);
        return ssl;
    }

    static SSLSocketFactory fabricaDe(SSLSocket ssl) throws IOException {
        SSLSocketFactory fabrica = mock(SSLSocketFactory.class);
        when(fabrica.createSocket(any(Socket.class), anyString(), anyInt(), anyBoolean())).thenReturn(ssl);
        return fabrica;
    }

    // ------------------------------------------------------------------ F2: exceptional cleanup

    /** Records, at cancellation, whether the raw socket had already been closed. */
    final class VigilanteQueComprueba implements Vigilante {
        final List<Runnable> acciones = Collections.synchronizedList(new ArrayList<>());
        final List<Boolean> crudoCerradoAlCancelar = Collections.synchronizedList(new ArrayList<>());

        @Override
        public Alarma programar(Runnable accion, long retrasoNanos) {
            acciones.add(accion);
            return () -> crudoCerradoAlCancelar.add(conector.sockets.get(conector.sockets.size() - 1).isClosed());
        }
    }

    SSLSocket tlsQueFallaElHandshake(SSLHandshakeException primaria) throws IOException {
        SSLSocket ssl = tlsSimulado(mock(SSLSession.class));
        doThrow(primaria).when(ssl).startHandshake();
        return ssl;
    }

    @Test
    void anUncheckedTlsCloseFailureStillClosesTheRawSocketAndIsSuppressedOnThePrimaryFailure() throws Exception {
        var servidor = plano((p, out) -> { });
        SSLHandshakeException handshake = new SSLHandshakeException("fixture handshake failure");
        SSLSocket ssl = tlsQueFallaElHandshake(handshake);
        IllegalStateException alCerrar = new IllegalStateException("fixture TLS close failure");
        doThrow(alCerrar).when(ssl).close();
        var vigilante = new VigilanteQueComprueba();
        var c = new ClienteHttpFijado(conector, fabricaDe(ssl), AMPLIOS, p -> servidor.puerto(), vigilante);
        var e = falla(() -> c.obtener(HTTPS, LOCAL, plazo(Duration.ofSeconds(20))), Motivo.TRANSPORTE, ClienteHttpFijado.MENSAJE_TLS);
        assertThat(e.getCause()).isSameAs(handshake); // the primary failure is the one thrown
        assertThat(e.getSuppressed()).containsExactly(alCerrar);
        assertThat(conector.sockets).hasSize(1).allMatch(Socket::isClosed);
        assertThat(vigilante.crudoCerradoAlCancelar).containsExactly(true);
    }

    @Test
    void aTlsCloseErrorIsNeverConvertedAndKeepsThePrimaryFailureAttached() throws Exception {
        var servidor = plano((p, out) -> { });
        SSLSocket ssl = tlsQueFallaElHandshake(new SSLHandshakeException("fixture handshake failure"));
        AssertionError fatal = new AssertionError("fixture fatal TLS close error");
        doThrow(fatal).when(ssl).close();
        var vigilante = new VigilanteQueComprueba();
        var c = new ClienteHttpFijado(conector, fabricaDe(ssl), AMPLIOS, p -> servidor.puerto(), vigilante);
        Throwable t = catchThrowable(() -> c.obtener(HTTPS, LOCAL, plazo(Duration.ofSeconds(20))));
        assertThat(t).isSameAs(fatal);
        assertThat(t.getSuppressed()).hasSize(1);
        assertThat(t.getSuppressed()[0]).isInstanceOf(CapturaException.class);
        assertThat(((CapturaException) t.getSuppressed()[0]).motivo()).isEqualTo(Motivo.TRANSPORTE);
        assertThat(conector.sockets).hasSize(1).allMatch(Socket::isClosed);
        assertThat(vigilante.crudoCerradoAlCancelar).containsExactly(true);
    }

    /** Connector whose raw socket closes for real and then reports an unchecked failure. */
    static final class ConectorCierreFallido implements Conector {
        final List<Socket> sockets = Collections.synchronizedList(new ArrayList<>());
        final RuntimeException fallo = new IllegalStateException("fixture raw close failure");

        @Override
        public Conexion conectar(IpAddress ip, int puerto, Duration timeout) throws IOException {
            Socket s = new Socket(Proxy.NO_PROXY) {
                @Override
                public synchronized void close() throws IOException {
                    super.close();
                    throw fallo;
                }
            };
            sockets.add(s);
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), puerto), (int) timeout.toMillis());
            return Conexion.delSocket(s);
        }
    }

    @Test
    void anUncheckedRawCloseFailureFailsClosedOnSuccessAndIsSuppressedOnAPrimaryFailure() throws Exception {
        var bueno = plano(ServidorHttpDePrueba.html(PAGINA));
        var malo = plano(ServidorHttpDePrueba.fija("HTTP/1.1 200 OK\nContent-Length: 0\r\n\r\n"));
        var cierre = new ConectorCierreFallido();
        var vigilante = new VigilanteManual();
        // Success, but the connection could not be released cleanly: the failure is not hidden.
        var cBueno = new ClienteHttpFijado(cierre, mock(SSLSocketFactory.class), AMPLIOS, p -> bueno.puerto(), vigilante);
        assertThat(catchThrowable(() -> cBueno.obtener(HTTP, LOCAL, plazo(Duration.ofSeconds(20))))).isSameAs(cierre.fallo);
        // A primary failure stays the thrown one.
        var cMalo = new ClienteHttpFijado(cierre, mock(SSLSocketFactory.class), AMPLIOS, p -> malo.puerto(), vigilante);
        var e = falla(() -> cMalo.obtener(HTTP, LOCAL, plazo(Duration.ofSeconds(20))), Motivo.RESPUESTA_NO_VALIDA, "LF sin CR");
        assertThat(e.getSuppressed()).containsExactly(cierre.fallo);
        // A primary Error stays the thrown one too, with the cleanup failure attached.
        AssertionError fatal = new AssertionError("fixture fatal watchdog error");
        Vigilante revienta = (accion, retraso) -> {
            throw fatal;
        };
        var cFatal = new ClienteHttpFijado(cierre, mock(SSLSocketFactory.class), AMPLIOS, p -> bueno.puerto(), revienta);
        Throwable t = catchThrowable(() -> cFatal.obtener(HTTP, LOCAL, plazo(Duration.ofSeconds(20))));
        assertThat(t).isSameAs(fatal);
        assertThat(t.getSuppressed()).contains(cierre.fallo);
        assertThat(cierre.sockets).hasSize(3).allMatch(Socket::isClosed);
        assertThat(vigilante.cancelaciones.get()).isEqualTo(2);
    }

    @Test
    void anUncheckedCancellationFailureIsReportedAfterBothSocketsWereClosed() throws Exception {
        var servidor = plano(ServidorHttpDePrueba.html(PAGINA));
        IllegalStateException alCancelar = new IllegalStateException("fixture cancellation failure");
        Vigilante cancelaMal = (accion, retraso) -> () -> {
            throw alCancelar;
        };
        assertThat(catchThrowable(() -> cliente(servidor.puerto(), cancelaMal).obtener(HTTP, LOCAL, plazo(Duration.ofSeconds(20)))))
                .isSameAs(alCancelar);
        assertThat(conector.sockets).hasSize(1).allMatch(Socket::isClosed);
    }

    /**
     * A contract test of this class's ordering over a simulated TLS layer whose close blocks until the
     * raw socket is closed. It does not prove how JSSE itself blocks in close_notify.
     */
    @Test
    void aTlsCloseBlockedOnThePeerIsEndedByTheWatchdogClosingTheRawSocket() throws Exception {
        var servidor = plano((p, out) -> { });
        SSLHandshakeException handshake = new SSLHandshakeException("fixture handshake failure");
        SSLSocket ssl = tlsQueFallaElHandshake(handshake);
        CountDownLatch cerrandoTls = new CountDownLatch(1);
        CountDownLatch vigilanteDisparado = new CountDownLatch(1);
        List<String> eventos = Collections.synchronizedList(new ArrayList<>());
        List<Runnable> acciones = Collections.synchronizedList(new ArrayList<>());
        doAnswer(i -> {
            // close_notify cannot be written: this close only returns once the raw socket is closed.
            cerrandoTls.countDown();
            boolean liberado = vigilanteDisparado.await(5, TimeUnit.SECONDS);
            eventos.add(liberado && conector.sockets.get(0).isClosed() ? "TLS close released, raw closed" : "TLS close NOT released");
            return null;
        }).when(ssl).close();
        Vigilante vigilante = (accion, retraso) -> {
            acciones.add(accion);
            eventos.add("armed");
            return () -> eventos.add(conector.sockets.get(0).isClosed() ? "cancelled, raw closed" : "cancelled, raw OPEN");
        };
        var c = new ClienteHttpFijado(conector, fabricaDe(ssl), AMPLIOS, p -> servidor.puerto(), vigilante);
        try (var workers = new TrabajadoresCapturaDePrueba(1)) {
            var resultado = workers.enviar(() -> catchThrowable(() -> c.obtener(HTTPS, LOCAL, plazo(Duration.ofSeconds(20)))));
            try {
                esperar(cerrandoTls);
                Socket crudo = conector.sockets.get(0);
                // Immediately before the watchdog fires: raw socket open, capture unfinished, alarm not cancelled.
                assertThat(crudo.isClosed()).as("raw socket still open").isFalse();
                assertThat(resultado.isDone()).as("capture still blocked in the TLS close").isFalse();
                assertThat(eventos).as("armed, not cancelled").containsExactly("armed");
                acciones.get(0).run(); // the watchdog fires (synchronously, on this thread)
                assertThat(crudo.isClosed()).as("the callback closed this attempt's raw socket").isTrue();
                eventos.add("watchdog fired");
            } finally {
                vigilanteDisparado.countDown();
            }
            Throwable t = resultado.get(5, TimeUnit.SECONDS); // the capture worker exits
            assertThat(t).isInstanceOf(CapturaException.class);
            assertThat(t.getCause()).isSameAs(handshake);
        } // the owned worker is shut down and its termination asserted
        assertThat(eventos).containsExactly("armed", "watchdog fired", "TLS close released, raw closed", "cancelled, raw closed");
        assertThat(acciones).hasSize(1);
        assertThat(conector.sockets).hasSize(1).allMatch(Socket::isClosed);
    }

    // ------------------------------------------------------------------ R1: raw close IOException

    /** How a fixture raw socket's close fails. */
    enum FalloCierre {
        NINGUNO,
        /** IOException before anything is released: the socket stays open. */
        IO_ANTES,
        /** Releases, then IOException: from the outside this cannot be told apart from IO_ANTES. */
        IO_DESPUES,
        /**
         * The first close releases and throws IOException; later closes return normally, as a second
         * close of a JDK socket whose descriptor close failed does.
         */
        IO_LA_PRIMERA,
        /** Releases, then a fatal Error. */
        ERROR_DESPUES
    }

    /** A real loopback socket whose close fails as configured; the teardown always releases it for real. */
    static final class SocketDeCierre extends Socket {
        final FalloCierre modo;
        final IOException io = new IOException("fixture raw close failure");
        final AssertionError error = new AssertionError("fixture fatal raw close error");
        final AtomicInteger cierres = new AtomicInteger();

        SocketDeCierre(FalloCierre modo) {
            super(Proxy.NO_PROXY);
            this.modo = modo;
        }

        @Override
        public synchronized void close() throws IOException {
            int numero = cierres.incrementAndGet();
            switch (modo) {
                case IO_ANTES -> throw io;
                case IO_DESPUES -> {
                    super.close();
                    throw io;
                }
                case IO_LA_PRIMERA -> {
                    super.close();
                    if (numero == 1) {
                        throw io;
                    }
                }
                case ERROR_DESPUES -> {
                    super.close();
                    throw error;
                }
                default -> super.close();
            }
        }

        /** The real release, whatever failure is simulated. */
        void liberar() throws IOException {
            super.close();
        }
    }

    /** Real loopback connections over {@link SocketDeCierre}; each socket is registered for the teardown. */
    final class ConectorDeCierre implements Conector {
        final FalloCierre modo;
        final InetSocketAddress peerDeclarado;
        final List<SocketDeCierre> sockets = Collections.synchronizedList(new ArrayList<>());

        ConectorDeCierre(FalloCierre modo) {
            this(modo, null);
        }

        /** {@code peerDeclarado}: a peer other than the dialled one, to provoke a pinning failure; null observes it. */
        ConectorDeCierre(FalloCierre modo, InetSocketAddress peerDeclarado) {
            this.modo = modo;
            this.peerDeclarado = peerDeclarado;
        }

        @Override
        public Conexion conectar(IpAddress ip, int puerto, Duration timeout) throws IOException {
            SocketDeCierre s = new SocketDeCierre(modo);
            sockets.add(s);
            recursos.add(s::liberar);
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), puerto), (int) timeout.toMillis());
            return peerDeclarado == null ? Conexion.delSocket(s) : Conexion.declarada(s, peerDeclarado);
        }

        SocketDeCierre ultimo() {
            return sockets.get(sockets.size() - 1);
        }
    }

    /** Records each alarm and, at its cancellation, how many closes that attempt's raw socket had seen. */
    static final class VigilanteQueCuenta implements Vigilante {
        final ConectorDeCierre conector;
        final List<Runnable> acciones = Collections.synchronizedList(new ArrayList<>());
        final List<Integer> cierresAlCancelar = Collections.synchronizedList(new ArrayList<>());

        VigilanteQueCuenta(ConectorDeCierre conector) {
            this.conector = conector;
        }

        @Override
        public Alarma programar(Runnable accion, long retrasoNanos) {
            acciones.add(accion);
            SocketDeCierre propio = conector.ultimo();
            return () -> cierresAlCancelar.add(propio.cierres.get());
        }
    }

    ClienteHttpFijado clienteDe(Conector c, int puerto, Vigilante v) {
        return new ClienteHttpFijado(c, confianza.getSocketFactory(), AMPLIOS, p -> puerto, v);
    }

    static InetSocketAddress otroPeer() throws IOException {
        return new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 9}), 1); // no lookup: literal bytes
    }

    /** A TLS layer simulated over the real raw socket that completes one exchange from memory. */
    SSLSocket tlsQueResponde() throws Exception {
        byte[] cuerpo = PAGINA.getBytes(StandardCharsets.UTF_8);
        byte[] respuesta = ("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: " + cuerpo.length + "\r\n\r\n" + PAGINA)
                .getBytes(StandardCharsets.UTF_8);
        SSLSession sesion = mock(SSLSession.class);
        when(sesion.getPeerCertificates()).thenReturn(new Certificate[]{PkiDePrueba.hoja(ca, HOST).certificado()});
        when(sesion.getProtocol()).thenReturn("TLSv1.3");
        SSLSocket ssl = tlsSimulado(sesion);
        AtomicReference<SSLParameters> parametros = new AtomicReference<>(new SSLParameters());
        when(ssl.getSSLParameters()).thenAnswer(i -> parametros.get());
        doAnswer(i -> {
            parametros.set(i.getArgument(0));
            return null;
        }).when(ssl).setSSLParameters(any());
        when(ssl.getInputStream()).thenReturn(new ByteArrayInputStream(respuesta));
        when(ssl.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        return ssl;
    }

    @Test
    void aRawCloseIOExceptionBeforeReleaseTurnsAValidResponseIntoATransportFailure() throws Exception {
        var servidor = plano(ServidorHttpDePrueba.html(PAGINA));
        var cierre = new ConectorDeCierre(FalloCierre.IO_ANTES);
        var vigilante = new VigilanteQueCuenta(cierre);
        var e = falla(() -> clienteDe(cierre, servidor.puerto(), vigilante).obtener(HTTP, LOCAL, plazo(Duration.ofSeconds(20))),
                Motivo.TRANSPORTE, ClienteHttpFijado.MENSAJE_CIERRE);
        SocketDeCierre crudo = cierre.ultimo();
        assertThat(e.getCause()).isSameAs(crudo.io);
        assertThat(e.getSuppressed()).isEmpty();
        assertThat(crudo.isClosed()).as("the failing close released nothing").isFalse();
        assertThat(servidor.peticiones()).hasSize(1); // the exchange itself had completed
        assertThat(vigilante.cierresAlCancelar).as("cancelled once, after the failed close").containsExactly(1);
    }

    @Test
    void aRawCloseIOExceptionAfterReleaseIsConservativelyAFailureAndANormalCloseKeepsTheResponse() throws Exception {
        var servidor = plano(ServidorHttpDePrueba.html(PAGINA));
        var despues = new ConectorDeCierre(FalloCierre.IO_DESPUES);
        var vDespues = new VigilanteQueCuenta(despues);
        var e = falla(() -> clienteDe(despues, servidor.puerto(), vDespues).obtener(HTTP, LOCAL, plazo(Duration.ofSeconds(20))),
                Motivo.TRANSPORTE, ClienteHttpFijado.MENSAJE_CIERRE);
        assertThat(e.getCause()).isSameAs(despues.ultimo().io);
        assertThat(despues.ultimo().isClosed()).as("released, which the exception cannot tell").isTrue();
        assertThat(vDespues.cierresAlCancelar).containsExactly(1);

        var normal = new ConectorDeCierre(FalloCierre.NINGUNO);
        var vNormal = new VigilanteQueCuenta(normal);
        RespuestaHttp r = clienteDe(normal, servidor.puerto(), vNormal).obtener(HTTP, LOCAL, plazo(Duration.ofSeconds(20)));
        assertThat(r.estado()).isEqualTo(200);
        assertThat(new String(r.cuerpo(), StandardCharsets.UTF_8)).isEqualTo(PAGINA);
        assertThat(normal.ultimo().isClosed()).isTrue();
        assertThat(vNormal.cierresAlCancelar).containsExactly(1);
    }

    @Test
    void aRawCloseIOExceptionNeverHidesAPrimaryTransportFramingOrDeadlineFailure() throws Exception {
        // TRANSPORTE: the connection did not reach the approved address.
        var bueno = plano(ServidorHttpDePrueba.html(PAGINA));
        var ajeno = new ConectorDeCierre(FalloCierre.IO_ANTES, otroPeer());
        var vAjeno = new VigilanteQueCuenta(ajeno);
        var transporte = falla(() -> clienteDe(ajeno, bueno.puerto(), vAjeno).obtener(HTTP, LOCAL, plazo(Duration.ofSeconds(20))),
                Motivo.TRANSPORTE, "no llegó a la dirección aprobada");
        assertThat(transporte.getSuppressed()).containsExactly(ajeno.ultimo().io);
        assertThat(vAjeno.cierresAlCancelar).containsExactly(1);

        // RESPUESTA_NO_VALIDA: a framing violation.
        var malo = plano(ServidorHttpDePrueba.fija("HTTP/1.1 200 OK\nContent-Length: 0\r\n\r\n"));
        var marco = new ConectorDeCierre(FalloCierre.IO_ANTES);
        var vMarco = new VigilanteQueCuenta(marco);
        var invalida = falla(() -> clienteDe(marco, malo.puerto(), vMarco).obtener(HTTP, LOCAL, plazo(Duration.ofSeconds(20))),
                Motivo.RESPUESTA_NO_VALIDA, "LF sin CR");
        assertThat(invalida.getSuppressed()).containsExactly(marco.ultimo().io);
        assertThat(vMarco.cierresAlCancelar).containsExactly(1);

        // PLAZO_AGOTADO: the watchdog's close at the deadline releases the socket but reports an
        // IOException, and the owner's close reports the same instance again: kept once, never
        // self-suppressed, and the deadline stays the outcome.
        var peticion = new CountDownLatch(1);
        var liberar = new CountDownLatch(1);
        var mudo = plano((p, out) -> {
            peticion.countDown();
            liberar.await(5, TimeUnit.SECONDS);
        });
        var vencido = new ConectorDeCierre(FalloCierre.IO_DESPUES);
        var vVencido = new VigilanteQueCuenta(vencido);
        PlazoCaptura adquisicion = plazo(Duration.ofSeconds(10));
        try (var workers = new TrabajadoresCapturaDePrueba(1)) {
            var resultado = workers.enviar(() -> catchThrowable(() ->
                    clienteDe(vencido, mudo.puerto(), vVencido).obtener(HTTP, LOCAL, adquisicion)));
            try {
                esperar(peticion);
                reloj.addAndGet(Duration.ofSeconds(10).toNanos());
                vVencido.acciones.get(0).run();
                Throwable t = resultado.get(5, TimeUnit.SECONDS);
                assertThat(t).isInstanceOf(CapturaException.class);
                assertThat(((CapturaException) t).motivo()).isEqualTo(Motivo.PLAZO_AGOTADO);
                assertThat(t.getSuppressed()).containsExactly(vencido.ultimo().io);
                assertThat(vencido.ultimo().io.getSuppressed()).isEmpty();
            } finally {
                liberar.countDown();
            }
        }
        assertThat(vencido.ultimo().cierres.get()).isEqualTo(2); // the watchdog's, then the owner's
        assertThat(vVencido.cierresAlCancelar).containsExactly(2);
    }

    @Test
    void aRawCloseIOExceptionNeverHidesATlsSecurityFailure() throws Exception {
        var servidor = plano((p, out) -> { });
        // JSSE rejected the certificate during the handshake.
        SSLHandshakeException rechazo = new SSLHandshakeException("fixture PKIX failure");
        rechazo.initCause(new CertificateException("fixture untrusted chain"));
        var c1 = new ConectorDeCierre(FalloCierre.IO_ANTES);
        var v1 = new VigilanteQueCuenta(c1);
        var cliente1 = new ClienteHttpFijado(c1, fabricaDe(tlsQueFallaElHandshake(rechazo)), AMPLIOS, p -> servidor.puerto(), v1);
        var e1 = falla(() -> cliente1.obtener(HTTPS, LOCAL, plazo(Duration.ofSeconds(20))), Motivo.TRANSPORTE,
                ClienteHttpFijado.MENSAJE_IDENTIDAD_TLS);
        assertThat(e1.getCause()).isSameAs(rechazo);
        assertThat(e1.getSuppressed()).containsExactly(c1.ultimo().io);
        assertThat(v1.cierresAlCancelar).containsExactly(1);

        // The peer is unauthenticated after the handshake.
        SSLPeerUnverifiedException sinVerificar = new SSLPeerUnverifiedException("peer not authenticated");
        SSLSession sesion = mock(SSLSession.class);
        when(sesion.getPeerCertificates()).thenThrow(sinVerificar);
        var c2 = new ConectorDeCierre(FalloCierre.IO_ANTES);
        var v2 = new VigilanteQueCuenta(c2);
        var cliente2 = new ClienteHttpFijado(c2, fabricaDe(tlsSimulado(sesion)), AMPLIOS, p -> servidor.puerto(), v2);
        var e2 = falla(() -> cliente2.obtener(HTTPS, LOCAL, plazo(Duration.ofSeconds(20))), Motivo.TRANSPORTE,
                ClienteHttpFijado.MENSAJE_IDENTIDAD_TLS);
        assertThat(e2.getCause()).isSameAs(sinVerificar);
        assertThat(e2.getSuppressed()).containsExactly(c2.ultimo().io);
        assertThat(v2.cierresAlCancelar).containsExactly(1);

        // IdentidadTls rejects a certificate for another host.
        SSLSession otra = mock(SSLSession.class);
        when(otra.getPeerCertificates()).thenReturn(new Certificate[]{PkiDePrueba.hoja(ca, "otro.ejemplo.es").certificado()});
        var c3 = new ConectorDeCierre(FalloCierre.IO_ANTES);
        var v3 = new VigilanteQueCuenta(c3);
        var cliente3 = new ClienteHttpFijado(c3, fabricaDe(tlsSimulado(otra)), AMPLIOS, p -> servidor.puerto(), v3);
        var e3 = falla(() -> cliente3.obtener(HTTPS, LOCAL, plazo(Duration.ofSeconds(20))), Motivo.TRANSPORTE,
                ClienteHttpFijado.MENSAJE_IDENTIDAD_TLS);
        assertThat(e3.getSuppressed()).containsExactly(c3.ultimo().io);
        assertThat(v3.cierresAlCancelar).containsExactly(1);
    }

    @Test
    void aTlsCloseIOExceptionIsIgnoredWhenTheRawCloseSucceeds() throws Exception {
        var servidor = plano((p, out) -> { });
        SSLSocket ssl = tlsQueResponde();
        doThrow(new IOException("fixture close_notify failure")).when(ssl).close();
        var c = new ConectorDeCierre(FalloCierre.NINGUNO);
        var v = new VigilanteQueCuenta(c);
        RespuestaHttp r = new ClienteHttpFijado(c, fabricaDe(ssl), AMPLIOS, p -> servidor.puerto(), v)
                .obtener(HTTPS, LOCAL, plazo(Duration.ofSeconds(20)));
        assertThat(r.estado()).isEqualTo(200);
        assertThat(r.observacion().tls()).isTrue();
        assertThat(new String(r.cuerpo(), StandardCharsets.UTF_8)).isEqualTo(PAGINA);
        verify(ssl).close();
        assertThat(c.ultimo().isClosed()).isTrue();
        assertThat(v.cierresAlCancelar).containsExactly(1);
    }

    @Test
    void aRawCloseErrorIsNeverConvertedOnSuccessOrOverAPrimaryFailure() throws Exception {
        var servidor = plano(ServidorHttpDePrueba.html(PAGINA));
        var exito = new ConectorDeCierre(FalloCierre.ERROR_DESPUES);
        var vExito = new VigilanteQueCuenta(exito);
        Throwable t = catchThrowable(() -> clienteDe(exito, servidor.puerto(), vExito).obtener(HTTP, LOCAL, plazo(Duration.ofSeconds(20))));
        assertThat(t).isSameAs(exito.ultimo().error);
        assertThat(t.getSuppressed()).isEmpty();
        assertThat(vExito.cierresAlCancelar).containsExactly(1);

        var ajeno = new ConectorDeCierre(FalloCierre.ERROR_DESPUES, otroPeer());
        var vAjeno = new VigilanteQueCuenta(ajeno);
        Throwable sobrePrimaria = catchThrowable(() ->
                clienteDe(ajeno, servidor.puerto(), vAjeno).obtener(HTTP, LOCAL, plazo(Duration.ofSeconds(20))));
        assertThat(sobrePrimaria).isSameAs(ajeno.ultimo().error);
        assertThat(sobrePrimaria.getSuppressed()).hasSize(1);
        assertThat(sobrePrimaria.getSuppressed()[0]).isInstanceOf(CapturaException.class).hasMessageContaining("no llegó");
        assertThat(vAjeno.cierresAlCancelar).containsExactly(1);
        assertThat(exito.ultimo().isClosed()).isTrue();
        assertThat(ajeno.ultimo().isClosed()).isTrue();
    }

    @Test
    void aWatchdogRawCloseFailureDuringCleanupStillPreventsASuccess() throws Exception {
        var servidor = plano((p, out) -> { });
        // The exchange completed; the watchdog fires while the TLS layer closes. Its raw close
        // releases and reports an IOException; the owner's own close then returns normally.
        var c = new ConectorDeCierre(FalloCierre.IO_LA_PRIMERA);
        var v = new VigilanteQueCuenta(c);
        SSLSocket ssl = tlsQueResponde();
        doAnswer(i -> {
            v.acciones.get(0).run();
            return null;
        }).when(ssl).close();
        var cliente = new ClienteHttpFijado(c, fabricaDe(ssl), AMPLIOS, p -> servidor.puerto(), v);
        var e = falla(() -> cliente.obtener(HTTPS, LOCAL, plazo(Duration.ofSeconds(20))), Motivo.TRANSPORTE,
                ClienteHttpFijado.MENSAJE_CIERRE);
        assertThat(e.getCause()).isSameAs(c.ultimo().io);
        assertThat(c.ultimo().cierres.get()).isEqualTo(2); // the watchdog's, then the owner's (silent)
        assertThat(v.cierresAlCancelar).containsExactly(2);

        // A watchdog close that succeeds during cleanup does not fail a completed exchange.
        var bien = new ConectorDeCierre(FalloCierre.NINGUNO);
        var vBien = new VigilanteQueCuenta(bien);
        SSLSocket ssl2 = tlsQueResponde();
        doAnswer(i -> {
            vBien.acciones.get(0).run();
            return null;
        }).when(ssl2).close();
        RespuestaHttp r = new ClienteHttpFijado(bien, fabricaDe(ssl2), AMPLIOS, p -> servidor.puerto(), vBien)
                .obtener(HTTPS, LOCAL, plazo(Duration.ofSeconds(20)));
        assertThat(r.estado()).isEqualTo(200);
        assertThat(bien.ultimo().isClosed()).isTrue();
        assertThat(vBien.cierresAlCancelar).containsExactly(2);
    }

    // ------------------------------------------------------------------ N1: cycle-safe cleanup aggregation

    /**
     * An unconnected socket whose n-th close releases it and then throws the n-th given failure (none
     * past the list): the same instances can be made to reappear on several cleanup paths.
     */
    static final class SocketQueLanza extends Socket {
        private final Throwable[] porCierre;
        final AtomicInteger cierres = new AtomicInteger();

        SocketQueLanza(Throwable... porCierre) {
            super(Proxy.NO_PROXY);
            this.porCierre = porCierre;
        }

        @Override
        public synchronized void close() throws IOException {
            int i = cierres.getAndIncrement();
            super.close();
            Throwable f = i < porCierre.length ? porCierre[i] : null;
            if (f instanceof IOException e) {
                throw e;
            }
            if (f instanceof RuntimeException e) {
                throw e;
            }
            if (f instanceof Error e) {
                throw e;
            }
        }

        /** The real release, without the simulated failure. */
        void liberar() throws IOException {
            super.close();
        }
    }

    /** The production cleanup of one attempt; what it threw, or null when the caller throws the primary. */
    static Throwable limpiar(ClienteHttpFijado.CierreCrudo cierre, Socket tls, Vigilante.Alarma alarma, Throwable primaria) {
        return catchThrowable(() -> ClienteHttpFijado.cerrarTodo(cierre, tls, alarma, primaria));
    }

    /** Fails if the cause/suppressed graph reachable from {@code raiz} contains a cycle (identity). */
    static void assertSinCiclos(Throwable raiz) {
        visitar(raiz, Collections.newSetFromMap(new IdentityHashMap<>()), Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static void visitar(Throwable t, Set<Throwable> enCurso, Set<Throwable> terminadas) {
        if (terminadas.contains(t)) {
            return;
        }
        assertThat(enCurso.add(t)).as("cause/suppressed cycle through %s", t).isTrue();
        for (Throwable s : t.getSuppressed()) {
            visitar(s, enCurso, terminadas);
        }
        if (t.getCause() != null) {
            visitar(t.getCause(), enCurso, terminadas);
        }
        enCurso.remove(t);
        terminadas.add(t);
    }

    /** How many cause or suppressed links point at {@code buscada} in the graph reachable from {@code raiz}. */
    static int enlacesHacia(Throwable raiz, Throwable buscada) {
        int enlaces = 0;
        Set<Throwable> vistas = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> pendientes = new ArrayDeque<>(List.of(raiz));
        while (!pendientes.isEmpty()) {
            Throwable t = pendientes.pop();
            if (!vistas.add(t)) {
                continue;
            }
            List<Throwable> hijos = new ArrayList<>(Arrays.asList(t.getSuppressed()));
            if (t.getCause() != null) {
                hijos.add(t.getCause());
            }
            for (Throwable h : hijos) {
                if (h == buscada) {
                    enlaces++;
                }
                pendientes.push(h);
            }
        }
        return enlaces;
    }

    @Test
    void theAbaCounterexampleKeepsThePrimaryOnTopWithTheOtherFailureAttachedAndNoCycle() {
        // Codex's case: primary A, the TLS close throws B, the raw close throws the same A.
        IllegalStateException a = new IllegalStateException("primary A");
        IllegalStateException b = new IllegalStateException("TLS close B");
        var crudo = new SocketQueLanza(a);
        var tls = new SocketQueLanza(b);
        AtomicInteger cancelaciones = new AtomicInteger();
        assertThat(limpiar(new ClienteHttpFijado.CierreCrudo(crudo), tls, cancelaciones::incrementAndGet, a))
                .as("the caller throws the primary").isNull();
        assertThat(a.getSuppressed()).containsExactly(b);
        assertThat(b.getSuppressed()).as("A is not attached under B").isEmpty();
        assertSinCiclos(a);
        assertThat(crudo.isClosed()).isTrue();
        assertThat(tls.isClosed()).isTrue();
        assertThat(cancelaciones.get()).isEqualTo(1);

        // The same through the watchdog handoff: the watchdog's close throws W, the owner's the primary A2.
        IllegalStateException a2 = new IllegalStateException("primary A2");
        IllegalStateException w = new IllegalStateException("watchdog close W");
        var crudo2 = new SocketQueLanza(w, a2);
        var cierre2 = new ClienteHttpFijado.CierreCrudo(crudo2);
        cierre2.cerrarDesdeVigilante();
        assertThat(limpiar(cierre2, crudo2, cancelaciones::incrementAndGet, a2)).isNull();
        assertThat(a2.getSuppressed()).containsExactly(w);
        assertThat(w.getSuppressed()).isEmpty();
        assertSinCiclos(a2);
        assertThat(crudo2.cierres.get()).isEqualTo(2);
    }

    @Test
    void theAbaCounterexampleThroughARealExchangeThrowsThePrimaryWithoutACycle() throws Exception {
        var servidor = plano((p, out) -> { });
        IllegalStateException a = new IllegalStateException("fixture handshake failure A");
        IllegalStateException b = new IllegalStateException("fixture TLS close failure B");
        SSLSocket ssl = tlsSimulado(mock(SSLSession.class));
        doThrow(a).when(ssl).startHandshake();
        doThrow(b).when(ssl).close();
        List<SocketQueLanza> crudos = Collections.synchronizedList(new ArrayList<>());
        Conector relanzaLaPrimaria = (ip, puerto, timeout) -> {
            var s = new SocketQueLanza(a); // the raw close rethrows the primary instance
            crudos.add(s);
            recursos.add(s::liberar);
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), puerto), (int) timeout.toMillis());
            return Conexion.delSocket(s);
        };
        var vigilante = new VigilanteManual();
        var c = new ClienteHttpFijado(relanzaLaPrimaria, fabricaDe(ssl), AMPLIOS, p -> servidor.puerto(), vigilante);
        Throwable t = catchThrowable(() -> c.obtener(HTTPS, LOCAL, plazo(Duration.ofSeconds(20))));
        assertThat(t).isSameAs(a);
        assertThat(a.getSuppressed()).containsExactly(b);
        assertThat(b.getSuppressed()).isEmpty();
        assertSinCiclos(t);
        assertThat(crudos).hasSize(1).allMatch(Socket::isClosed);
        assertThat(vigilante.cancelaciones.get()).isEqualTo(1);
    }

    @Test
    void anInstanceMetOnSeveralCleanupPathsIsRecordedOnce() {
        // The same X from the TLS close, the watchdog's close, the owner's close and the cancellation.
        IllegalStateException x = new IllegalStateException("reused X");
        var crudo = new SocketQueLanza(x, x);
        var cierre = new ClienteHttpFijado.CierreCrudo(crudo);
        cierre.cerrarDesdeVigilante();
        Throwable sinPrimaria = limpiar(cierre, new SocketQueLanza(x), () -> {
            throw x;
        }, null);
        assertThat(sinPrimaria).isSameAs(x);
        assertThat(x.getSuppressed()).isEmpty();

        // With an ordinary primary, X is attached to it exactly once.
        CapturaException primaria = new CapturaException(Motivo.TRANSPORTE, "fixture primary failure");
        IllegalStateException y = new IllegalStateException("reused Y");
        var crudoY = new SocketQueLanza(y, y);
        var cierreY = new ClienteHttpFijado.CierreCrudo(crudoY);
        cierreY.cerrarDesdeVigilante();
        assertThat(limpiar(cierreY, new SocketQueLanza(y), () -> {
            throw y;
        }, primaria)).isNull();
        assertThat(primaria.getSuppressed()).containsExactly(y);
        assertThat(enlacesHacia(primaria, y)).isEqualTo(1);
        assertThat(y.getSuppressed()).isEmpty();

        // The primary instance itself on every path adds nothing.
        IllegalStateException a = new IllegalStateException("primary A on every path");
        var crudoA = new SocketQueLanza(a, a);
        var cierreA = new ClienteHttpFijado.CierreCrudo(crudoA);
        cierreA.cerrarDesdeVigilante();
        assertThat(limpiar(cierreA, new SocketQueLanza(a), () -> {
            throw a;
        }, a)).isNull();
        assertThat(a.getSuppressed()).isEmpty();

        // Mixed: primary X2 is reused by the watchdog's and the owner's close, B by the TLS close and the cancellation.
        IllegalStateException x2 = new IllegalStateException("primary X2");
        IllegalStateException b = new IllegalStateException("reused B");
        var crudoMixto = new SocketQueLanza(x2, x2);
        var cierreMixto = new ClienteHttpFijado.CierreCrudo(crudoMixto);
        cierreMixto.cerrarDesdeVigilante();
        assertThat(limpiar(cierreMixto, new SocketQueLanza(b), () -> {
            throw b;
        }, x2)).isNull();
        assertThat(x2.getSuppressed()).containsExactly(b);
        assertThat(b.getSuppressed()).isEmpty();
        for (Throwable raiz : List.of(x, primaria, a, x2)) {
            assertSinCiclos(raiz);
        }
    }

    @Test
    void directSelfSuppressionIsStillPrevented() {
        IllegalStateException a = new IllegalStateException("primary A");
        var crudo = new SocketQueLanza(a);
        // Plain HTTP: the raw socket is the only one; its close rethrows the primary.
        assertThat(limpiar(new ClienteHttpFijado.CierreCrudo(crudo), crudo, () -> { }, a)).isNull();
        assertThat(a.getSuppressed()).isEmpty();

        // The same raw-close IOException from the watchdog and from the owner, no primary.
        IOException r = new IOException("raw close R");
        var crudoR = new SocketQueLanza(r, r);
        var cierreR = new ClienteHttpFijado.CierreCrudo(crudoR);
        cierreR.cerrarDesdeVigilante();
        Throwable t = limpiar(cierreR, crudoR, () -> { }, null);
        assertThat(t).isInstanceOf(CapturaException.class).hasMessage(ClienteHttpFijado.MENSAJE_CIERRE);
        assertThat(t.getCause()).isSameAs(r);
        assertThat(r.getSuppressed()).isEmpty();
    }

    @Test
    void distinctCleanupFailuresAreAllKeptUnderTheMostSeriousOne() {
        // TLS close B (unchecked), watchdog W and owner R (IOException), cancellation C (unchecked).
        IllegalStateException b = new IllegalStateException("TLS close B");
        IOException w = new IOException("watchdog close W");
        IOException r = new IOException("owner raw close R");
        IllegalStateException c = new IllegalStateException("cancellation C");
        var crudo = new SocketQueLanza(w, r);
        var cierre = new ClienteHttpFijado.CierreCrudo(crudo);
        cierre.cerrarDesdeVigilante();
        Throwable t = limpiar(cierre, new SocketQueLanza(b), () -> {
            throw c;
        }, null);
        assertThat(t).as("the first unchecked failure, thrown as is").isSameAs(b);
        assertThat(b.getSuppressed()).containsExactly(w, r, c);
        for (Throwable otra : List.of(w, r, c)) {
            assertThat(otra.getSuppressed()).isEmpty();
        }
        assertSinCiclos(t);

        // An Error among them is the one thrown, carrying the others.
        IllegalStateException b2 = new IllegalStateException("TLS close B2");
        IOException r2 = new IOException("owner raw close R2");
        AssertionError e = new AssertionError("fatal cancellation error E");
        Throwable fatal = limpiar(new ClienteHttpFijado.CierreCrudo(new SocketQueLanza(r2)), new SocketQueLanza(b2), () -> {
            throw e;
        }, null);
        assertThat(fatal).isSameAs(e);
        assertThat(e.getSuppressed()).containsExactly(b2, r2);
        assertSinCiclos(fatal);
    }

    @Test
    void aPrimarySecurityFailureKeepsItsMeaningAndCauseOverCleanupFailures() {
        SSLHandshakeException rechazo = new SSLHandshakeException("fixture PKIX failure");
        rechazo.initCause(new CertificateException("fixture untrusted chain"));
        PlazoCaptura vigente = plazo(Duration.ofSeconds(10));
        CapturaException primaria = ClienteHttpFijado.clasificar(rechazo, vigente, vigente);
        IllegalStateException b = new IllegalStateException("TLS close B");
        IOException r = new IOException("owner raw close R");
        // The watchdog's close throws the primary's own cause instance: already recorded, not again.
        var crudo = new SocketQueLanza(rechazo, r);
        var cierre = new ClienteHttpFijado.CierreCrudo(crudo);
        cierre.cerrarDesdeVigilante();
        assertThat(limpiar(cierre, new SocketQueLanza(b), () -> { }, primaria)).isNull();
        assertThat(primaria.motivo()).isEqualTo(Motivo.TRANSPORTE);
        assertThat(primaria.getMessage()).isEqualTo(ClienteHttpFijado.MENSAJE_IDENTIDAD_TLS);
        assertThat(primaria.getCause()).isSameAs(rechazo);
        // Each distinct cleanup failure directly on the primary, in the order met.
        assertThat(primaria.getSuppressed()).containsExactly(b, r);
        assertThat(b.getSuppressed()).isEmpty();
        assertThat(r.getSuppressed()).isEmpty();
        assertThat(rechazo.getSuppressed()).as("the security cause is not modified").isEmpty();
        assertThat(enlacesHacia(primaria, rechazo)).isEqualTo(1);
        assertSinCiclos(primaria);
    }

    @Test
    void aPrimaryDeadlineFailureKeepsItsMeaningOverCleanupFailures() {
        PlazoCaptura vencida = plazo(Duration.ofSeconds(1));
        reloj.addAndGet(Duration.ofSeconds(1).toNanos());
        SocketException cerrado = new SocketException("Socket closed");
        CapturaException primaria = ClienteHttpFijado.clasificar(cerrado, vencida, vencida);
        IOException w = new IOException("watchdog close W");
        IllegalStateException c = new IllegalStateException("cancellation C");
        // The owner's close rethrows the primary's cause instance.
        var crudo = new SocketQueLanza(w, cerrado);
        var cierre = new ClienteHttpFijado.CierreCrudo(crudo);
        cierre.cerrarDesdeVigilante();
        assertThat(limpiar(cierre, crudo, () -> {
            throw c;
        }, primaria)).isNull();
        assertThat(primaria.motivo()).isEqualTo(Motivo.PLAZO_AGOTADO);
        assertThat(primaria.getCause()).isSameAs(cerrado);
        assertThat(primaria.getSuppressed()).containsExactly(w, c);
        assertThat(c.getSuppressed()).isEmpty();
        assertThat(w.getSuppressed()).isEmpty();
        assertThat(cerrado.getSuppressed()).isEmpty();
        assertSinCiclos(primaria);
    }

    @Test
    void aPrimaryErrorStaysOnTopOfOrdinaryAndFatalCleanupFailures() {
        AssertionError primaria = new AssertionError("primary fatal error");
        IllegalStateException b = new IllegalStateException("TLS close B");
        IOException r = new IOException("owner raw close R");
        AssertionError f = new AssertionError("fatal cancellation error F");
        assertThat(limpiar(new ClienteHttpFijado.CierreCrudo(new SocketQueLanza(r)), new SocketQueLanza(b), () -> {
            throw f;
        }, primaria)).as("the caller throws the primary Error").isNull();
        // A primary Error stays primary; every cleanup failure, the cleanup Error included, sits on it.
        assertThat(primaria.getSuppressed()).containsExactly(b, r, f);
        assertThat(f.getSuppressed()).isEmpty();
        assertSinCiclos(primaria);

        // The primary Error rethrown by the TLS close is not attached to anything.
        AssertionError otra = new AssertionError("primary fatal error rethrown");
        assertThat(limpiar(new ClienteHttpFijado.CierreCrudo(new SocketQueLanza()), new SocketQueLanza(otra), () -> { }, otra))
                .isNull();
        assertThat(otra.getSuppressed()).isEmpty();
    }

    @Test
    void aCleanupErrorOverAnOrdinaryPrimaryIsThrownCarryingIt() {
        CapturaException primaria = new CapturaException(Motivo.TRANSPORTE, "fixture primary failure");
        IllegalStateException b = new IllegalStateException("TLS close B");
        AssertionError e = new AssertionError("fatal raw close error E");
        // The cancellation rethrows the primary instance: it is attached to E once, last.
        Throwable t = limpiar(new ClienteHttpFijado.CierreCrudo(new SocketQueLanza(e)), new SocketQueLanza(b), () -> {
            throw primaria;
        }, primaria);
        assertThat(t).as("never converted").isSameAs(e);
        assertThat(e.getSuppressed()).containsExactly(b, primaria);
        assertThat(primaria.getSuppressed()).isEmpty();
        assertThat(enlacesHacia(e, primaria)).isEqualTo(1);
        assertSinCiclos(t);
    }

    @Test
    void aRawCloseIOExceptionWithoutAPrimaryIsTheCauseOfTheTransportFailure() {
        IOException r = new IOException("owner raw close R");
        Throwable t = limpiar(new ClienteHttpFijado.CierreCrudo(new SocketQueLanza(r)), new SocketQueLanza(), () -> { }, null);
        assertThat(t).isInstanceOf(CapturaException.class).hasMessage(ClienteHttpFijado.MENSAJE_CIERRE);
        assertThat(((CapturaException) t).motivo()).isEqualTo(Motivo.TRANSPORTE);
        assertThat(t.getCause()).isSameAs(r);
        assertThat(r.getSuppressed()).isEmpty();

        // A TLS-close IOException stays ignored; the watchdog's W is first, the owner's R attached to it.
        IOException w = new IOException("watchdog close W");
        IOException r2 = new IOException("owner raw close R2");
        var crudo = new SocketQueLanza(w, r2);
        var cierre = new ClienteHttpFijado.CierreCrudo(crudo);
        cierre.cerrarDesdeVigilante();
        Throwable t2 = limpiar(cierre, new SocketQueLanza(new IOException("TLS close_notify failure")), () -> { }, null);
        assertThat(t2).isInstanceOf(CapturaException.class).hasMessage(ClienteHttpFijado.MENSAJE_CIERRE);
        assertThat(t2.getCause()).isSameAs(w);
        assertThat(w.getSuppressed()).containsExactly(r2);
        assertSinCiclos(t2);
    }

    @Test
    void noLoopIsClosedThroughExistingCauseOrSuppressedLinks() {
        // A cleanup failure that already has the primary as its cause cannot go under it.
        CapturaException primaria = new CapturaException(Motivo.TRANSPORTE, "fixture primary failure");
        IllegalStateException envuelve = new IllegalStateException("wraps the primary", primaria);
        assertThat(limpiar(new ClienteHttpFijado.CierreCrudo(new SocketQueLanza()), new SocketQueLanza(envuelve), () -> { }, primaria))
                .isNull();
        assertThat(primaria.getSuppressed()).isEmpty();
        assertSinCiclos(primaria);
        assertSinCiclos(envuelve);

        // A cleanup Error with the primary as its cause is thrown; the primary is already inside it.
        CapturaException primaria2 = new CapturaException(Motivo.TRANSPORTE, "fixture primary failure 2");
        AssertionError fatal = new AssertionError("fatal error wrapping the primary", primaria2);
        Throwable t = limpiar(new ClienteHttpFijado.CierreCrudo(new SocketQueLanza(fatal)), new SocketQueLanza(), () -> { }, primaria2);
        assertThat(t).isSameAs(fatal);
        assertThat(fatal.getSuppressed()).isEmpty();
        assertThat(enlacesHacia(fatal, primaria2)).isEqualTo(1);
        assertSinCiclos(t);

        // A loop already present in foreign exceptions does not hang the checks; this code adds none.
        IllegalStateException k1 = new IllegalStateException("foreign K1");
        IllegalStateException k2 = new IllegalStateException("foreign K2");
        k1.addSuppressed(k2);
        k2.addSuppressed(k1);
        CapturaException primaria3 = new CapturaException(Motivo.TRANSPORTE, "fixture primary failure 3");
        assertThat(limpiar(new ClienteHttpFijado.CierreCrudo(new SocketQueLanza()), new SocketQueLanza(k1), () -> { }, primaria3))
                .isNull();
        assertThat(primaria3.getSuppressed()).containsExactly(k1);
        assertThat(k1.getSuppressed()).containsExactly(k2);
        assertThat(k2.getSuppressed()).containsExactly(k1);
    }

    // ------------------------------------------------------------------ N1a: a cleanup Error keeps its precedence

    @Test
    void aCleanupErrorAlreadyCausingThePrimaryIsStillTheOneThrown() {
        // Codex's N1a case: the primary's cause is the very Error the raw close rethrows.
        AssertionError fatal = new AssertionError("raw close failed");
        CapturaException primaria = new CapturaException(Motivo.TRANSPORTE, "operation failed", fatal);
        Throwable t = limpiar(new ClienteHttpFijado.CierreCrudo(new SocketQueLanza(fatal)), new SocketQueLanza(), () -> { }, primaria);
        assertThat(t).as("the original Error instance propagates, not the ordinary primary").isSameAs(fatal);
        // The primary cannot go under the Error that is its own cause (a loop); the existing link stays.
        assertThat(fatal.getSuppressed()).isEmpty();
        assertThat(primaria.getCause()).isSameAs(fatal);
        assertThat(primaria.getSuppressed()).isEmpty();
        assertSinCiclos(fatal);
        assertSinCiclos(primaria);
    }

    @Test
    void aFatalErrorFromTheTlsCloseOrTheWatchdogHandoffIsThrownCarryingThePrimary() {
        // From the TLS close, with an independent raw-close failure too.
        CapturaException p1 = new CapturaException(Motivo.TRANSPORTE, "operation failed 1");
        AssertionError tls = new AssertionError("TLS close fatal");
        IOException r = new IOException("raw close R");
        Throwable t1 = limpiar(new ClienteHttpFijado.CierreCrudo(new SocketQueLanza(r)), new SocketQueLanza(tls), () -> { }, p1);
        assertThat(t1).isSameAs(tls);
        assertThat(tls.getSuppressed()).containsExactly(r, p1);

        // From the watchdog's raw close, handed to the owner (whose own close succeeds).
        CapturaException p2 = new CapturaException(Motivo.TRANSPORTE, "operation failed 2");
        AssertionError vigilante = new AssertionError("watchdog close fatal");
        var crudo2 = new SocketQueLanza(vigilante);
        var cierre2 = new ClienteHttpFijado.CierreCrudo(crudo2);
        cierre2.cerrarDesdeVigilante();
        Throwable t2 = limpiar(cierre2, crudo2, () -> { }, p2);
        assertThat(t2).isSameAs(vigilante);
        assertThat(vigilante.getSuppressed()).containsExactly(p2);
        assertThat(crudo2.cierres.get()).isEqualTo(2);

        // From the watchdog, when that Error is also the primary's cause: still the one thrown.
        AssertionError causa = new AssertionError("watchdog close fatal, also the primary's cause");
        CapturaException p3 = new CapturaException(Motivo.TRANSPORTE, "operation failed 3", causa);
        var crudo3 = new SocketQueLanza(causa);
        var cierre3 = new ClienteHttpFijado.CierreCrudo(crudo3);
        cierre3.cerrarDesdeVigilante();
        assertThat(limpiar(cierre3, crudo3, () -> { }, p3)).isSameAs(causa);
        assertThat(causa.getSuppressed()).isEmpty();
        for (Throwable raiz : List.of(t1, t2, causa, p3)) {
            assertSinCiclos(raiz);
        }
    }

    @Test
    void underAFatalCleanupErrorOtherFailuresAndThePrimaryStayReachableWithoutDuplicates() {
        // b (unchecked, wraps the primary a), w (watchdog IOException), e (Error, owner's close), d (cancellation).
        CapturaException a = new CapturaException(Motivo.TRANSPORTE, "operation failed");
        IllegalStateException b = new IllegalStateException("TLS close failed", a);
        IOException w = new IOException("watchdog close W");
        AssertionError e = new AssertionError("owner raw close fatal E");
        IllegalStateException d = new IllegalStateException("cancellation D");
        var crudo = new SocketQueLanza(w, e);
        var cierre = new ClienteHttpFijado.CierreCrudo(crudo);
        cierre.cerrarDesdeVigilante();
        Throwable t = limpiar(cierre, new SocketQueLanza(b), () -> {
            throw d;
        }, a);
        assertThat(t).isSameAs(e);
        // b can go under e; a is then already reachable through b's cause and is not linked again.
        assertThat(e.getSuppressed()).containsExactly(b, w, d);
        assertThat(enlacesHacia(e, a)).isEqualTo(1);
        assertSinCiclos(e);
    }

    // ------------------------------------------------------------------ N1b: independent cleanup failures are kept

    @Test
    void anIndependentRawCloseFailureIsKeptWhenAnotherCleanupFailureCannotBeAttached() {
        // Codex's N1b case: b already contains the primary a; c is independent.
        CapturaException a = new CapturaException(Motivo.TRANSPORTE, "operation failed");
        IllegalStateException b = new IllegalStateException("TLS close failed", a);
        IOException c = new IOException("raw close failed");
        assertThat(limpiar(new ClienteHttpFijado.CierreCrudo(new SocketQueLanza(c)), new SocketQueLanza(b), () -> { }, a))
                .as("the caller throws the primary").isNull();
        assertThat(a.getSuppressed()).as("c kept; b would close a loop").containsExactly(c);
        assertThat(b.getSuppressed()).isEmpty();
        assertThat(c.getSuppressed()).isEmpty();
        assertSinCiclos(a);
        assertSinCiclos(b);

        // A further independent failure (the cancellation) is kept as well.
        CapturaException a2 = new CapturaException(Motivo.TRANSPORTE, "operation failed 2");
        IllegalStateException b2 = new IllegalStateException("TLS close failed 2", a2);
        IOException c2 = new IOException("raw close failed 2");
        IllegalStateException d2 = new IllegalStateException("cancellation failed 2");
        assertThat(limpiar(new ClienteHttpFijado.CierreCrudo(new SocketQueLanza(c2)), new SocketQueLanza(b2), () -> {
            throw d2;
        }, a2)).isNull();
        assertThat(a2.getSuppressed()).containsExactly(c2, d2);
        assertSinCiclos(a2);
    }

    @Test
    void failuresReachingThePrimaryThroughACauseChainOrSuppressedLinksAreSkippedOneByOne() {
        CapturaException a = new CapturaException(Motivo.TRANSPORTE, "operation failed");
        // b reaches a through a two-level cause chain; w through a suppressed link it already carries.
        IllegalStateException b = new IllegalStateException("TLS close failed", new RuntimeException("wrapper", a));
        IOException w = new IOException("watchdog close W");
        w.addSuppressed(a);
        IOException r = new IOException("owner raw close R");
        IllegalStateException d = new IllegalStateException("cancellation D");
        var crudo = new SocketQueLanza(w, r);
        var cierre = new ClienteHttpFijado.CierreCrudo(crudo);
        cierre.cerrarDesdeVigilante();
        assertThat(limpiar(cierre, new SocketQueLanza(b), () -> {
            throw d;
        }, a)).isNull();
        assertThat(a.getSuppressed()).containsExactly(r, d);
        assertSinCiclos(a);
        assertSinCiclos(b);
        assertSinCiclos(w);
    }

    @Test
    void withoutAPrimaryAFailureAlreadyReachableThroughACauseIsNotLinkedAgain() {
        // The TLS close throws b caused by c; the raw close rethrows that c; the cancellation throws d.
        IOException c = new IOException("raw close C");
        IllegalStateException b = new IllegalStateException("TLS close B", c);
        IllegalStateException d = new IllegalStateException("cancellation D");
        Throwable t = limpiar(new ClienteHttpFijado.CierreCrudo(new SocketQueLanza(c)), new SocketQueLanza(b), () -> {
            throw d;
        }, null);
        assertThat(t).as("the first unchecked failure").isSameAs(b);
        assertThat(b.getSuppressed()).containsExactly(d);
        assertThat(enlacesHacia(b, c)).isEqualTo(1);
        assertSinCiclos(b);
    }

    // ------------------------------------------------------------------ REAL TIME: stalls cut by the acquisition deadline (L, M, N)

    @Test
    void aDrippingTlsHandshakeIsCutByTheAcquisitionDeadline() throws Exception {
        ServerSocket s = new ServerSocket();
        AtomicReference<Socket> aceptado = new AtomicReference<>();
        CountDownLatch cierreVisto = new CountDownLatch(1);
        Thread hilo = new Thread(() -> {
            try (Socket c = s.accept()) {
                aceptado.set(c);
                OutputStream out = c.getOutputStream();
                out.write(new byte[]{0x16, 0x03, 0x03, 0x40, 0x00}); // a 16 KiB handshake record that never ends
                out.flush();
                for (int i = 0; i < 100; i++) { // fixture pacing: one byte every 100 ms
                    Thread.sleep(100);
                    out.write(0x02);
                    out.flush();
                }
            } catch (IOException e) {
                cierreVisto.countDown(); // the client hung up (or the test closed this fixture)
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "goteo-tls-pv15");
        hilo.setDaemon(true);
        Throwable primaria = null;
        try {
            s.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            hilo.start();
            cortadoPorElPlazo(new ClienteHttpFijado(new ConectorDirecto(), confianza.getSocketFactory(), AMPLIOS, p -> s.getLocalPort()), HTTPS);
            assertThat(cierreVisto.await(5, TimeUnit.SECONDS)).as("the server saw the client close").isTrue();
        } catch (Throwable t) {
            primaria = t;
            throw t;
        } finally {
            desmontarGoteo(primaria, s, aceptado, hilo);
        }
    }

    /**
     * Unconditional teardown of the TLS drip fixture: every step is attempted, in order, even when an
     * earlier close fails, and a failure of the test body stays the primary one (see {@link #desmontar}).
     */
    static void desmontarGoteo(Throwable primaria, ServerSocket s, AtomicReference<Socket> aceptado, Thread hilo) throws Exception {
        desmontar(primaria,
                s::close, // unblocks a pending accept
                () -> {
                    Socket c = aceptado.get();
                    if (c != null) {
                        c.close(); // unblocks a pending write
                    }
                },
                hilo::interrupt, // ends the pacing sleep
                () -> {
                    hilo.join(TimeUnit.SECONDS.toMillis(5));
                    assertThat(hilo.isAlive()).as("fixture thread terminated").isFalse();
                });
    }

    /** A real listener or socket whose close releases it and then fails. */
    static final class ServidorQueFallaAlCerrar extends ServerSocket {
        final IOException fallo;

        ServidorQueFallaAlCerrar(IOException fallo) throws IOException {
            this.fallo = fallo;
        }

        @Override
        public void close() throws IOException {
            super.close();
            throw fallo;
        }
    }

    static final class SocketQueFallaAlCerrar extends Socket {
        final IOException fallo;

        SocketQueFallaAlCerrar(IOException fallo) {
            super(Proxy.NO_PROXY);
            this.fallo = fallo;
        }

        @Override
        public synchronized void close() throws IOException {
            super.close();
            throw fallo;
        }
    }

    @Test
    void theDripTeardownStillInterruptsAndJoinsItsWorkerWhenItsClosesFail() throws Exception {
        for (boolean conPrimaria : new boolean[]{false, true}) {
            IOException alCerrarServidor = new IOException("simulated listener close failure");
            IOException alCerrarAceptado = new IOException("simulated accepted socket close failure");
            AssertionError primaria = conPrimaria ? new AssertionError("simulated failure of the test body") : null;
            var s = new ServidorQueFallaAlCerrar(alCerrarServidor);
            var aceptado = new SocketQueFallaAlCerrar(alCerrarAceptado);
            CountDownLatch nunca = new CountDownLatch(1);
            CountDownLatch esperando = new CountDownLatch(1);
            Thread hilo = new Thread(() -> {
                esperando.countDown();
                try {
                    nunca.await(); // only an interruption ends this wait
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "desmontaje-pv15");
            hilo.setDaemon(true);
            hilo.start();
            try {
                esperar(esperando);
                Throwable t = catchThrowable(() -> desmontarGoteo(primaria, s, new AtomicReference<>(aceptado), hilo));
                assertThat(hilo.isAlive()).as("interrupted and joined despite both close failures").isFalse();
                assertThat(s.isClosed()).isTrue();
                assertThat(aceptado.isClosed()).isTrue();
                if (conPrimaria) {
                    // The primary stays the caller's to throw; the cleanup failures, accumulated as when
                    // there is no primary, are attached to it.
                    assertThat(t).isNull();
                    assertThat(primaria.getSuppressed()).containsExactly(alCerrarServidor);
                } else {
                    assertThat(t).isSameAs(alCerrarServidor);
                }
                assertThat(alCerrarServidor.getSuppressed()).containsExactly(alCerrarAceptado);
            } finally {
                // This test's own fixture, whatever the outcome above.
                nunca.countDown();
                hilo.join(TimeUnit.SECONDS.toMillis(5));
            }
            assertThat(hilo.isAlive()).isFalse();
        }
    }

    @Test
    void aSilentServerAndADrippedBodyAreCutByTheAcquisitionDeadlineAndClosed() throws Exception {
        var mudo = tls((p, out) -> {
            // no response at all: the server then waits for the client to hang up
        });
        var goteo = tls((p, out) -> {
            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 5000\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            for (int i = 0; i < 100; i++) { // fixture pacing: one body byte every 100 ms
                out.write('x');
                out.flush();
                Thread.sleep(100);
            }
        });
        for (ServidorHttpDePrueba servidor : List.of(mudo, goteo)) {
            cortadoPorElPlazo(new ClienteHttpFijado(new ConectorDirecto(), confianza.getSocketFactory(), AMPLIOS, p -> servidor.puerto()), HTTPS);
            esperarHasta(() -> servidor.cerradasPorCliente() == 1);
        }
    }

    /** 700 ms acquisition deadline against 5 s reads and a 15 s connection total. */
    static void cortadoPorElPlazo(ClienteHttpFijado c, DestinationUrl url) {
        long inicio = System.nanoTime();
        PlazoCaptura adquisicion = PlazoCaptura.iniciar(Duration.ofMillis(700), System::nanoTime);
        falla(() -> c.obtener(url, LOCAL, adquisicion), Motivo.PLAZO_AGOTADO, ClienteHttpFijado.MENSAJE_PLAZO);
        Duration transcurrido = Duration.ofNanos(System.nanoTime() - inicio);
        assertThat(transcurrido).isGreaterThanOrEqualTo(Duration.ofMillis(600)).isLessThan(Duration.ofMillis(1_700));
    }
}
