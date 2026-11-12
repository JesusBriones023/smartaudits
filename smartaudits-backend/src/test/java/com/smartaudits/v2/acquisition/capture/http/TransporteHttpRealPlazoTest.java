package com.smartaudits.v2.acquisition.capture.http;

import com.smartaudits.v2.acquisition.capture.CapturaException;
import com.smartaudits.v2.acquisition.capture.CapturaException.Motivo;
import com.smartaudits.v2.acquisition.capture.CapturaHtml;
import com.smartaudits.v2.acquisition.capture.LimitesAdquisicion;
import com.smartaudits.v2.acquisition.capture.PlazoCaptura;
import com.smartaudits.v2.acquisition.capture.ResolvedorConPlazo;
import com.smartaudits.v2.acquisition.capture.TrabajadoresCapturaDePrueba;
import com.smartaudits.v2.acquisition.capture.TransporteHttpReal;
import com.smartaudits.v2.acquisition.destination.DestinationUrl;
import com.smartaudits.v2.acquisition.destination.DnsAnswer;
import com.smartaudits.v2.acquisition.destination.DnsAnswer.FamilyAnswer;
import com.smartaudits.v2.acquisition.destination.DnsAnswer.Status;
import com.smartaudits.v2.acquisition.destination.IpAddress;
import com.smartaudits.v2.acquisition.destination.UrlParseResult;
import com.smartaudits.v2.acquisition.destination.ValidatedHost;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSocketFactory;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntUnaryOperator;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;

/**
 * PV-15 acquisition deadline across a whole {@link TransporteHttpReal#capturar} call. Deterministic
 * unless marked REAL TIME: a manual monotonic source moved by the scripted resolver, connector or
 * response stream, and a manual watchdog. Approved public IPs are routed to loopback servers by a test
 * connector that declares its peer (captures are SIMULATED, never REAL). No external network.
 */
class TransporteHttpRealPlazoTest {
    static final IpAddress A = TransporteHttpRealTest.ip("8.8.8.8");
    static final IpAddress B = TransporteHttpRealTest.ip("1.1.1.1");
    static final IpAddress C = TransporteHttpRealTest.ip("9.9.9.9");
    static final Clock MARCA = Clock.fixed(Instant.parse("2026-11-04T10:00:00Z"), ZoneOffset.UTC);
    static final LimitesHttp LIMITES = new LimitesHttp(Duration.ofSeconds(4), Duration.ofSeconds(4), Duration.ofSeconds(5),
            32 * 1024, 100, 64 * 1024, 128 * 1024, 5);
    static final String PAGINA = "<html><body><h1>Aviso legal</h1><p>Responsable: Ejemplo S.L.</p></body></html>";
    static final String OK = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + PAGINA.length()
            + "\r\n\r\n" + PAGINA;

    final AtomicLong reloj = new AtomicLong();
    final List<ServidorHttpDePrueba> servidores = new ArrayList<>();
    final ResolvedorGuionado resolvedor = new ResolvedorGuionado();
    final ConectorDeRutas conector = new ConectorDeRutas();
    final ClienteHttpFijadoPlazoTest.VigilanteManual vigilante = new ClienteHttpFijadoPlazoTest.VigilanteManual();

    @AfterEach
    void cerrar() throws IOException {
        for (ServidorHttpDePrueba s : servidores) {
            s.close();
        }
    }

    // ------------------------------------------------------------------ fixtures

    static String redireccion(String destino) {
        return "HTTP/1.1 302 Found\r\nLocation: " + destino + "\r\nContent-Length: 0\r\n\r\n";
    }

    static DnsAnswer publica(IpAddress... ips) {
        List<byte[]> lista = new ArrayList<>();
        for (IpAddress ip : ips) {
            lista.add(ip.bytes());
        }
        return DnsAnswer.of(FamilyAnswer.addresses(lista), FamilyAnswer.nodata());
    }

    static DnsAnswer fallo() {
        return DnsAnswer.of(FamilyAnswer.of(Status.FAILURE, List.of(), List.of()), FamilyAnswer.nodata());
    }

    interface Respuesta {
        DnsAnswer dar(PlazoCaptura plazo) throws InterruptedException;
    }

    /** Deadline-aware resolver scripted per host; records every call and the deadline it received. */
    static final class ResolvedorGuionado implements ResolvedorConPlazo {
        final Map<String, Respuesta> guion = new HashMap<>();
        final List<String> hosts = Collections.synchronizedList(new ArrayList<>());
        final List<PlazoCaptura> plazos = Collections.synchronizedList(new ArrayList<>());

        ResolvedorGuionado con(String host, Respuesta respuesta) {
            guion.put(host, respuesta);
            return this;
        }

        @Override
        public DnsAnswer resolver(ValidatedHost host, PlazoCaptura plazo) throws InterruptedException {
            hosts.add(host.name());
            plazos.add(plazo);
            return guion.get(host.name()).dar(plazo);
        }
    }

    record Ruta(int puertoLocal, int umbral, Runnable alCompletar, IpAddress peerDeclarado) {}

    /** Routes an approved IP to a loopback server; optionally acts once the whole response was delivered. */
    final class ConectorDeRutas implements Conector {
        final Map<IpAddress, Ruta> rutas = new HashMap<>();
        final List<Duration> timeouts = Collections.synchronizedList(new ArrayList<>());
        final List<Socket> sockets = Collections.synchronizedList(new ArrayList<>());

        ConectorDeRutas ruta(IpAddress ip, ServidorHttpDePrueba servidor) {
            rutas.put(ip, new Ruta(servidor.puerto(), 0, null, ip));
            return this;
        }

        ConectorDeRutas ruta(IpAddress ip, ServidorHttpDePrueba servidor, int umbral, Runnable alCompletar) {
            rutas.put(ip, new Ruta(servidor.puerto(), umbral, alCompletar, ip));
            return this;
        }

        @Override
        public Conexion conectar(IpAddress ip, int puerto, Duration timeout) throws IOException {
            timeouts.add(timeout);
            Ruta ruta = rutas.get(ip);
            if (ruta == null) {
                throw new IOException("sin ruta de prueba para esa dirección");
            }
            Socket socket = ruta.alCompletar() == null ? new Socket() : new SocketQueAvisa(ruta.umbral(), ruta.alCompletar());
            sockets.add(socket);
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), ruta.puertoLocal()), (int) timeout.toMillis());
            return Conexion.declarada(socket, new InetSocketAddress(InetAddress.getByAddress(ruta.peerDeclarado().bytes()), puerto));
        }
    }

    /** Runs {@code alCompletar} on the reading thread right after {@code umbral} response bytes were delivered. */
    static final class SocketQueAvisa extends Socket {
        private final int umbral;
        private final Runnable alCompletar;

        SocketQueAvisa(int umbral, Runnable alCompletar) {
            this.umbral = umbral;
            this.alCompletar = alCompletar;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return new FilterInputStream(super.getInputStream()) {
                private int entregados;
                private boolean hecho;

                @Override
                public int read() throws IOException {
                    int b = super.read();
                    if (b >= 0) {
                        contar(1);
                    }
                    return b;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    int n = super.read(b, off, len);
                    if (n > 0) {
                        contar(n);
                    }
                    return n;
                }

                private void contar(int n) {
                    entregados += n;
                    if (!hecho && entregados >= umbral) {
                        hecho = true;
                        alCompletar.run();
                    }
                }
            };
        }
    }

    ServidorHttpDePrueba plano(ServidorHttpDePrueba.Manejador m) throws IOException {
        var s = ServidorHttpDePrueba.plano(m);
        servidores.add(s);
        return s;
    }

    TransporteHttpReal transporte(LimitesAdquisicion limites, LongSupplier fuente) {
        var cliente = new ClienteHttpFijado(conector, mock(SSLSocketFactory.class), LIMITES, IntUnaryOperator.identity(), vigilante);
        return new TransporteHttpReal(resolvedor, cliente, MARCA, limites, fuente);
    }

    TransporteHttpReal transporte() {
        return transporte(new LimitesAdquisicion(Duration.ofSeconds(20)), reloj::get);
    }

    static DestinationUrl url(String texto) {
        return ((UrlParseResult.Parsed) DestinationUrl.parse(texto)).url();
    }

    static CapturaException falla(TransporteHttpReal t, String texto, Motivo motivo, String mensaje) {
        Throwable e = catchThrowable(() -> t.capturar(url(texto)));
        assertThat(e).isInstanceOf(CapturaException.class);
        CapturaException c = (CapturaException) e;
        assertThat(c.motivo()).as(c.getMessage()).isEqualTo(motivo);
        assertThat(c.getMessage()).contains(mensaje);
        return c;
    }

    Respuesta avanzarY(Duration avance, DnsAnswer respuesta) {
        return plazo -> {
            reloj.addAndGet(avance.toNanos());
            return respuesta;
        };
    }

    // ------------------------------------------------------------------ one deadline (E, F, G)

    @Test
    void aSuccessfulCaptureUsesTheSameDeadlineForEveryHop() throws Exception {
        var a = plano(ServidorHttpDePrueba.fija(redireccion("http://b.ejemplo.es/legal")));
        var b = plano(ServidorHttpDePrueba.fija(OK));
        conector.ruta(A, a).ruta(B, b);
        resolvedor.con("a.ejemplo.es", p -> publica(A)).con("b.ejemplo.es", p -> publica(B));
        CapturaHtml captura = transporte().capturar(url("http://a.ejemplo.es/inicio"));
        assertThat(captura.urlFinal().canonicalForm()).isEqualTo("http://b.ejemplo.es/legal");
        assertThat(captura.html()).isEqualTo(PAGINA);
        assertThat(captura.procedencia().esReal()).isFalse(); // declared peers: SIMULATED, as before PV-15
        assertThat(captura.fechaCaptura()).isEqualTo(MARCA.instant()); // wall clock for the timestamp only
        assertThat(resolvedor.plazos).hasSize(2);
        assertThat(resolvedor.plazos.get(1)).isSameAs(resolvedor.plazos.get(0));
        assertThat(vigilante.retrasos).hasSize(2);
        assertThat(vigilante.cancelaciones.get()).isEqualTo(2);
        assertThat(conector.sockets).allMatch(Socket::isClosed);
    }

    @Test
    void theDeadlineIsSharedByEveryRedirectHopAndNeverRestarted() throws Exception {
        var a = plano(ServidorHttpDePrueba.fija(redireccion("http://b.ejemplo.es/")));
        var b = plano(ServidorHttpDePrueba.fija(redireccion("http://c.ejemplo.es/")));
        var c = plano(ServidorHttpDePrueba.fija(OK));
        conector.ruta(A, a).ruta(B, b).ruta(C, c);
        resolvedor.con("a.ejemplo.es", avanzarY(Duration.ofSeconds(10), publica(A)))
                .con("b.ejemplo.es", avanzarY(Duration.ofSeconds(7), publica(B)))
                .con("c.ejemplo.es", avanzarY(Duration.ofSeconds(4), publica(C)));
        falla(transporte(), "http://a.ejemplo.es/", Motivo.PLAZO_AGOTADO, "Plazo total de adquisición agotado");
        assertThat(resolvedor.hosts).containsExactly("a.ejemplo.es", "b.ejemplo.es", "c.ejemplo.es");
        assertThat(resolvedor.plazos).allMatch(p -> p == resolvedor.plazos.get(0));
        // Hop 1 at 10 s: connect 4 s (own limit), alarm 5 s (connection total). Hop 2 at 17 s: 3 s left bound both.
        assertThat(conector.timeouts).containsExactly(Duration.ofSeconds(4), Duration.ofSeconds(3));
        assertThat(vigilante.retrasos).containsExactly(Duration.ofSeconds(5).toNanos(), Duration.ofSeconds(3).toNanos());
        assertThat(a.conexiones() + b.conexiones()).isEqualTo(2);
        assertThat(c.conexiones()).isZero(); // a restarted budget would have connected to c
    }

    // ------------------------------------------------------------------ checkpoints (D, J, O, P)

    @Test
    void anAlreadyExpiredDeadlineDoesNoNetworkWork() {
        AtomicLong fuente = new AtomicLong();
        resolvedor.con("a.ejemplo.es", p -> publica(A));
        // Each reading of this source advances 1 ms: the 1 ns deadline has expired by the first checkpoint.
        var t = transporte(new LimitesAdquisicion(Duration.ofNanos(1)), () -> fuente.addAndGet(1_000_000));
        falla(t, "http://a.ejemplo.es/", Motivo.PLAZO_AGOTADO, "Plazo total");
        assertThat(resolvedor.hosts).isEmpty();
        assertThat(conector.timeouts).isEmpty();
    }

    @Test
    void expiryDuringDnsStopsBeforeConnecting() {
        resolvedor.con("a.ejemplo.es", avanzarY(Duration.ofSeconds(20), publica(A)));
        falla(transporte(), "http://a.ejemplo.es/", Motivo.PLAZO_AGOTADO, "Plazo total");
        assertThat(conector.timeouts).isEmpty();
        assertThat(vigilante.retrasos).isEmpty();
    }

    @Test
    void expiryExactlyBetweenTwoHopsStopsBeforeTheNextEvaluation() throws Exception {
        String r = redireccion("http://b.ejemplo.es/");
        var a = plano(ServidorHttpDePrueba.fija(r));
        conector.ruta(A, a, r.length(), () -> reloj.set(Duration.ofSeconds(20).toNanos())); // exactly the deadline
        resolvedor.con("a.ejemplo.es", p -> publica(A)).con("b.ejemplo.es", p -> publica(B));
        falla(transporte(), "http://a.ejemplo.es/", Motivo.PLAZO_AGOTADO, "Plazo total");
        assertThat(resolvedor.hosts).containsExactly("a.ejemplo.es");
        assertThat(conector.timeouts).hasSize(1);
    }

    @Test
    void noCaptureIsReturnedOnceExpiryIsObservedAfterTheBodyWasRead() throws Exception {
        var a = plano(ServidorHttpDePrueba.fija(OK));
        conector.ruta(A, a, OK.length(), () -> reloj.addAndGet(Duration.ofSeconds(21).toNanos()));
        resolvedor.con("a.ejemplo.es", p -> publica(A));
        falla(transporte(), "http://a.ejemplo.es/", Motivo.PLAZO_AGOTADO, "Plazo total");
        assertThat(a.peticiones()).hasSize(1);
        assertThat(vigilante.cancelaciones.get()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ precedence (Q)

    @Test
    void aDnsFailureIsTheDeadlineOnlyWhenTheDeadlineHasExpired() {
        resolvedor.con("a.ejemplo.es", avanzarY(Duration.ofSeconds(20), fallo()));
        falla(transporte(), "http://a.ejemplo.es/", Motivo.PLAZO_AGOTADO, "Plazo total");
        resolvedor.con("a.ejemplo.es", p -> fallo());
        falla(transporte(), "http://a.ejemplo.es/", Motivo.URL_RECHAZADA, "DNS_FAILURE");
        resolvedor.con("a.ejemplo.es", p -> {
            reloj.addAndGet(Duration.ofSeconds(20).toNanos());
            throw new IllegalStateException("resolver failure after expiry");
        });
        falla(transporte(), "http://a.ejemplo.es/", Motivo.PLAZO_AGOTADO, "Plazo total");
    }

    @Test
    void securityRejectionsAreNeverHiddenByAnExpiredDeadline() throws Exception {
        resolvedor.con("a.ejemplo.es", avanzarY(Duration.ofSeconds(20), publica(TransporteHttpRealTest.ip("10.0.0.1"))));
        falla(transporte(), "http://a.ejemplo.es/", Motivo.URL_RECHAZADA, "ADDRESS_FORBIDDEN");
        resolvedor.con("a.ejemplo.es", avanzarY(Duration.ofSeconds(20), publica(A, TransporteHttpRealTest.ip("10.0.0.1"))));
        falla(transporte(), "http://a.ejemplo.es/", Motivo.URL_RECHAZADA, "ADDRESS_MIXED");
        // A redirect to a forbidden name, with the deadline reached while its response was delivered.
        String r = redireccion("http://localhost/");
        var a = plano(ServidorHttpDePrueba.fija(r));
        conector.ruta(A, a, r.length(), () -> reloj.addAndGet(Duration.ofSeconds(30).toNanos()));
        resolvedor.con("a.ejemplo.es", p -> publica(A));
        falla(transporte(), "http://a.ejemplo.es/", Motivo.URL_RECHAZADA, "La redirección no es un destino válido");
        // A connection that reached another peer, with the deadline reached while connecting.
        var b = plano(ServidorHttpDePrueba.fija(OK));
        conector.rutas.put(B, new Ruta(b.puerto(), 1, null, C));
        resolvedor.con("b.ejemplo.es", p -> publica(B));
        Conector original = conector;
        Conector lento = (ip, puerto, timeout) -> {
            Conexion c = original.conectar(ip, puerto, timeout);
            reloj.addAndGet(Duration.ofSeconds(30).toNanos());
            return c;
        };
        var cliente = new ClienteHttpFijado(lento, mock(SSLSocketFactory.class), LIMITES, IntUnaryOperator.identity(), vigilante);
        var t = new TransporteHttpReal(resolvedor, cliente, MARCA, new LimitesAdquisicion(Duration.ofSeconds(20)), reloj::get);
        falla(t, "http://b.ejemplo.es/", Motivo.TRANSPORTE, "no llegó a la dirección aprobada");
        assertThat(b.peticiones()).isEmpty();
    }

    // ------------------------------------------------------------------ interruption (R)

    @Test
    void anInterruptionAtACheckpointIsCancellationAndTheFlagStaysSet() throws Exception {
        try (var workers = new TrabajadoresCapturaDePrueba(1)) {
            workers.enviar(() -> {
                resolvedor.con("a.ejemplo.es", p -> {
                    Thread.currentThread().interrupt();
                    return publica(A);
                });
                falla(transporte(), "http://a.ejemplo.es/", Motivo.CANCELADA, "interrumpida");
                assertThat(conector.timeouts).isEmpty();
                // Consumed explicitly (and asserted) so the next case starts uninterrupted.
                assertThat(Thread.interrupted()).as("flag left set by the transport").isTrue();
                // Interrupted and expired, reported through the policy as DNS_FAILURE: interruption first.
                resolvedor.con("a.ejemplo.es", p -> {
                    Thread.currentThread().interrupt();
                    reloj.addAndGet(Duration.ofSeconds(20).toNanos());
                    throw new InterruptedException("resolver interrupted");
                });
                falla(transporte(), "http://a.ejemplo.es/", Motivo.CANCELADA, "interrumpida");
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
                return null;
            }).get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void anInterruptionWhileTheBodyArrivesIsNeverReportedAsASuccessfulCapture() throws Exception {
        var a = plano(ServidorHttpDePrueba.fija(OK));
        conector.ruta(A, a, OK.length(), () -> Thread.currentThread().interrupt()); // runs on the capturing thread
        resolvedor.con("a.ejemplo.es", p -> publica(A));
        try (var workers = new TrabajadoresCapturaDePrueba(1)) {
            workers.enviar(() -> {
                falla(transporte(), "http://a.ejemplo.es/", Motivo.CANCELADA, "interrumpida");
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
                return null;
            }).get(5, TimeUnit.SECONDS);
        }
        assertThat(a.peticiones()).hasSize(1);
        assertThat(conector.sockets).allMatch(Socket::isClosed);
    }

    // ------------------------------------------------------------------ REAL TIME

    /**
     * REAL TIME: 1.5 s acquisition deadline; hop 1 answers its redirect after 1 s (fixture pacing), hop 2
     * never answers. The shared deadline ends hop 2 after about 0.5 s; a restarted budget would wait for
     * the 4 s read limit of hop 2.
     */
    @Test
    void aSlowFirstHopLeavesOnlyTheRestOfTheDeadlineToTheNextOne() throws Exception {
        var a = plano((p, out) -> {
            Thread.sleep(1_000);
            out.write(redireccion("http://b.ejemplo.es/").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        });
        var b = plano((p, out) -> {
            // silent: the server waits for the client to hang up
        });
        conector.ruta(A, a).ruta(B, b);
        resolvedor.con("a.ejemplo.es", p -> publica(A)).con("b.ejemplo.es", p -> publica(B));
        var cliente = new ClienteHttpFijado(conector, mock(SSLSocketFactory.class), LIMITES, IntUnaryOperator.identity());
        var t = new TransporteHttpReal(resolvedor, cliente, MARCA, new LimitesAdquisicion(Duration.ofMillis(1_500)), System::nanoTime);
        long inicio = System.nanoTime();
        falla(t, "http://a.ejemplo.es/", Motivo.PLAZO_AGOTADO, "Plazo total");
        assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isGreaterThanOrEqualTo(Duration.ofMillis(1_400))
                .isLessThan(Duration.ofMillis(2_700));
        assertThat(b.peticiones()).hasSize(1);
    }
}
