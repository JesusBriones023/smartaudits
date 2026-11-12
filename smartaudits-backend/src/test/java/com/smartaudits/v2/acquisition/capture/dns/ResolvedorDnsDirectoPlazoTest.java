package com.smartaudits.v2.acquisition.capture.dns;

import com.smartaudits.v2.acquisition.capture.PlazoCaptura;
import com.smartaudits.v2.acquisition.capture.TrabajadoresCapturaDePrueba;
import com.smartaudits.v2.acquisition.capture.dns.ServidorDnsDePrueba.Consulta;
import com.smartaudits.v2.acquisition.capture.dns.ServidorDnsDePrueba.Registro;
import com.smartaudits.v2.acquisition.destination.DestinationDecision;
import com.smartaudits.v2.acquisition.destination.DestinationPolicy;
import com.smartaudits.v2.acquisition.destination.DestinationUrl;
import com.smartaudits.v2.acquisition.destination.DnsAnswer;
import com.smartaudits.v2.acquisition.destination.DnsAnswer.Status;
import com.smartaudits.v2.acquisition.destination.RejectionCode;
import com.smartaudits.v2.acquisition.destination.UrlParseResult;
import com.smartaudits.v2.acquisition.destination.ValidatedHost;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.smartaudits.v2.acquisition.capture.dns.ResolvedorDnsDirecto.TYPE_A;
import static com.smartaudits.v2.acquisition.capture.dns.ServidorDnsDePrueba.respuesta;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PV-15 DNS deadline. Deterministic cases use a manual monotonic source (socket waits then stay far
 * from their real limits); where a fixture thread moves that source, the assertions accept every outcome
 * the contract allows for the resulting interleaving. The two marked REAL TIME measure an actual socket
 * timeout over loopback and use bounded tolerances; one of them paces its slow server with a sleep, and
 * no case relies on sleeping for coordination.
 */
class ResolvedorDnsDirectoPlazoTest {
    static final ValidatedHost HOST = ((UrlParseResult.Parsed) DestinationUrl.parse("https://www.ejemplo.es/")).url().host();
    static final byte[] V6 = {0x2a, 0x00, 0x14, 0x50, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};

    final AtomicLong reloj = new AtomicLong();
    ServidorDnsDePrueba servidor;

    @AfterEach
    void cerrar() throws IOException {
        if (servidor != null) {
            servidor.close();
        }
    }

    ResolvedorDnsDirecto resolvedor(ServidorDnsDePrueba.Guion guion, Duration timeout) throws IOException {
        servidor = new ServidorDnsDePrueba(guion);
        return new ResolvedorDnsDirecto(new byte[]{127, 0, 0, 1}, servidor.puerto(), timeout);
    }

    static List<byte[]> normal(byte[] q) {
        return List.of(ServidorDnsDePrueba.tipo(q) == TYPE_A
                ? respuesta(q, 0, List.of(Registro.a("www.ejemplo.es", 8, 8, 8, 8)))
                : respuesta(q, 0, List.of(Registro.aaaa("www.ejemplo.es", V6))));
    }

    @Test
    void anExpiredCaptureDeadlineOpensNoSocketForEitherFamily() throws Exception {
        var r = resolvedor((q, tcp) -> normal(q), Duration.ofSeconds(2));
        PlazoCaptura plazo = PlazoCaptura.iniciar(Duration.ofSeconds(1), reloj::get);
        reloj.addAndGet(Duration.ofSeconds(1).toNanos());
        DnsAnswer answer = r.resolver(HOST, plazo);
        assertThat(answer.a().status()).isEqualTo(Status.FAILURE);
        assertThat(answer.aaaa().status()).isEqualTo(Status.FAILURE);
        // An ordinary resolution afterwards is served in order: had a query been sent, it would show first.
        assertThat(r.resolve(HOST).a().status()).isEqualTo(Status.ADDRESSES);
        assertThat(servidor.consultas()).extracting(Consulta::tipo).containsExactly(TYPE_A, ResolvedorDnsDirecto.TYPE_AAAA);
    }

    @Test
    void theSecondFamilyIsNotStartedOnceTheSharedDeadlineHasExpired() throws Exception {
        var r = resolvedor((q, tcp) -> {
            if (ServidorDnsDePrueba.tipo(q) == TYPE_A) {
                reloj.addAndGet(Duration.ofSeconds(2).toNanos()); // the A exchange consumes the whole deadline
            }
            return normal(q);
        }, Duration.ofSeconds(2));
        PlazoCaptura plazo = PlazoCaptura.iniciar(Duration.ofSeconds(1), reloj::get);
        DnsAnswer answer = r.resolver(HOST, plazo);
        assertThat(plazo.vencido()).as("the shared capture deadline expired during the A exchange").isTrue();
        // The deadline expires while A is in flight. The resolver sets its receive timeout only after
        // sending, so if the clock moved first it correctly refuses to wait (FAILURE); if it was already
        // waiting it still reads the answer (ADDRESSES). Both are valid for an expired capture; this
        // test is about the second family. Successful answers are covered by the other tests.
        assertThat(answer.a().status()).isIn(Status.ADDRESSES, Status.FAILURE);
        assertThat(answer.aaaa().status()).isEqualTo(Status.FAILURE);
        // Exactly one A query reached the server and no AAAA query was ever started.
        assertThat(servidor.consultas()).extracting(Consulta::tipo).containsExactly(TYPE_A);
        // Through the unchanged policy this is the documented DNS_FAILURE; the transport, not the
        // policy, tells deadline expiry apart from an ordinary DNS failure.
        PlazoCaptura otro = PlazoCaptura.iniciar(Duration.ofSeconds(1), reloj::get);
        DestinationDecision d = DestinationPolicy.using(h -> r.resolver(h, otro)).evaluate("https://www.ejemplo.es/");
        assertThat(((DestinationDecision.Rejected) d).code()).isEqualTo(RejectionCode.DNS_FAILURE);
        assertThat(otro.vencido()).isTrue();
        // The policy's resolution sent one more A query and, again, no AAAA query.
        assertThat(servidor.consultas()).extracting(Consulta::tipo).containsExactly(TYPE_A, TYPE_A);
    }

    @Test
    void anInterruptionBetweenFamiliesStopsBeforeAaaaAndKeepsTheFlag() throws Exception {
        var llamante = new java.util.concurrent.atomic.AtomicReference<Thread>();
        var r = resolvedor((q, tcp) -> {
            if (ServidorDnsDePrueba.tipo(q) == TYPE_A) {
                llamante.get().interrupt(); // the caller is interrupted while A is in flight
            }
            return normal(q);
        }, Duration.ofSeconds(2));
        try (var workers = new TrabajadoresCapturaDePrueba(1)) {
            workers.enviar(() -> {
                llamante.set(Thread.currentThread());
                PlazoCaptura plazo = PlazoCaptura.iniciar(Duration.ofSeconds(5), reloj::get);
                assertThatThrownBy(() -> r.resolver(HOST, plazo)).isInstanceOf(InterruptedException.class);
                assertThat(Thread.currentThread().isInterrupted()).as("flag left set").isTrue();
                // The policy contract: DNS_FAILURE and the flag restored, never cleared.
                DestinationDecision d = DestinationPolicy.using(h -> r.resolver(h, plazo)).evaluate("https://www.ejemplo.es/");
                assertThat(((DestinationDecision.Rejected) d).code()).isEqualTo(RejectionCode.DNS_FAILURE);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
                return null;
            }).get(5, TimeUnit.SECONDS);
        }
        assertThat(servidor.consultas()).extracting(Consulta::tipo).containsExactly(TYPE_A);
    }

    @Test
    void everyReadOfTheTcpLengthPrefixGetsOnlyTheTimeLeft() throws Exception {
        List<Integer> timeouts = new ArrayList<>();
        Socket socket = new Socket() {
            @Override
            public synchronized void setSoTimeout(int timeout) {
                timeouts.add(timeout);
            }
        };
        PlazoCaptura plazo = PlazoCaptura.iniciar(Duration.ofMillis(1000), reloj::get);
        InputStream goteo = new InputStream() { // one byte per read, each arriving 600 ms later
            @Override
            public int read() {
                return 0;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                reloj.addAndGet(Duration.ofMillis(600).toNanos());
                b[off] = 7;
                return 1;
            }
        };
        byte[] prefijo = new byte[2];
        ResolvedorDnsDirecto.leerCompleto(goteo, prefijo, socket, plazo);
        assertThat(timeouts).containsExactly(1000, 400); // not 1000 again for the second byte
        // Once the time left is gone, the next read is not attempted at all.
        timeouts.clear();
        PlazoCaptura corto = PlazoCaptura.iniciar(Duration.ofMillis(500), reloj::get);
        assertThatThrownBy(() -> ResolvedorDnsDirecto.leerCompleto(goteo, new byte[2], socket, corto))
                .isInstanceOf(PlazoCaptura.Vencido.class);
        assertThat(timeouts).containsExactly(500);
        socket.close();
    }

    /** REAL TIME: a silent server; the shared 400 ms deadline, not the 2 s exchange timeout, ends A. */
    @Test
    void aSilentServerIsCutAtTheSharedDeadlineAndAaaaIsNeverAsked() throws Exception {
        var r = resolvedor((q, tcp) -> List.of(), Duration.ofSeconds(2));
        long inicio = System.nanoTime();
        DnsAnswer answer = r.resolver(HOST, PlazoCaptura.iniciar(Duration.ofMillis(400), System::nanoTime));
        Duration transcurrido = Duration.ofNanos(System.nanoTime() - inicio);
        assertThat(answer.a().status()).isEqualTo(Status.FAILURE);
        assertThat(answer.aaaa().status()).isEqualTo(Status.FAILURE);
        assertThat(transcurrido).isGreaterThanOrEqualTo(Duration.ofMillis(300)).isLessThan(Duration.ofMillis(1_900));
        assertThat(servidor.consultas()).extracting(Consulta::tipo).containsExactly(TYPE_A);
    }

    /**
     * REAL TIME: TC=1, then over TCP the first length byte arrives late (1.5 s pacing by the fixture)
     * and the second never does. The exchange ends by its 2 s limit; reading the second byte with a
     * fresh full timeout (the corrected defect) would have ended near 3.5 s.
     */
    @Test
    void aLateTcpLengthPrefixCannotStretchTheExchangeBeyondItsLimit() throws Exception {
        var r = resolvedor((q, tcp) -> ServidorDnsDePrueba.tipo(q) == TYPE_A && !tcp
                ? List.of(ServidorDnsDePrueba.respuesta(q, ServidorDnsDePrueba.id(q), 0, true, List.of()))
                : normal(q), Duration.ofSeconds(2));
        servidor.conEscritorTcp((conexion, respuestas) -> {
            Thread.sleep(1_500); // fixture behaviour: a slow server, not test coordination
            conexion.getOutputStream().write(0);
            conexion.getOutputStream().flush();
            try {
                conexion.getInputStream().read(); // holds the second byte until the client hangs up
            } catch (IOException ignored) {
                // closed by the client
            }
        });
        long inicio = System.nanoTime();
        DnsAnswer answer = r.resolve(HOST);
        Duration transcurrido = Duration.ofNanos(System.nanoTime() - inicio);
        assertThat(answer.a().status()).isEqualTo(Status.FAILURE);
        assertThat(transcurrido).isGreaterThanOrEqualTo(Duration.ofMillis(1_500)).isLessThan(Duration.ofMillis(2_900));
        assertThat(servidor.consultas()).extracting(Consulta::tipo, Consulta::tcp)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(TYPE_A, false), org.assertj.core.groups.Tuple.tuple(TYPE_A, true),
                        org.assertj.core.groups.Tuple.tuple(ResolvedorDnsDirecto.TYPE_AAAA, false));
    }
}
