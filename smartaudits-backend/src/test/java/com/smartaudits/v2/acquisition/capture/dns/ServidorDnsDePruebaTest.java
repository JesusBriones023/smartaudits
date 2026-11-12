package com.smartaudits.v2.acquisition.capture.dns;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;
import java.util.function.IntSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Ownership of the fixture's own sockets while it is constructed. Failures are injected through its
 * factory seam, so no case depends on exhausting or occupying operating-system ports; the successful
 * cases bind only loopback port 0, which is portable.
 */
class ServidorDnsDePruebaTest {
    static final ServidorDnsDePrueba.Guion SILENCIO = (q, tcp) -> List.of();

    /**
     * A real TCP candidate that records its close and can fail its bind or its close. A failing close
     * still releases the socket first, so no case leaks one. Whatever number is requested, it really
     * binds loopback port 0 (the OS's choice), so these cases never depend on an explicit number being
     * free; the requested number is recorded.
     */
    static final class Candidato extends ServerSocket {
        final IOException alVincular;
        final Throwable alCerrar;
        volatile boolean cerrado;
        volatile int pedido = -1;

        Candidato(IOException alVincular, Throwable alCerrar) throws IOException {
            this.alVincular = alVincular;
            this.alCerrar = alCerrar;
        }

        @Override
        public void bind(SocketAddress direccion) throws IOException {
            pedido = ((InetSocketAddress) direccion).getPort();
            if (alVincular != null) {
                throw alVincular;
            }
            super.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        }

        @Override
        public void close() throws IOException {
            cerrado = true;
            super.close();
            if (alCerrar instanceof IOException e) {
                throw e;
            }
            if (alCerrar instanceof RuntimeException e) {
                throw e;
            }
            if (alCerrar instanceof Error e) {
                throw e;
            }
        }
    }

    static DatagramSocket udpReal(int puerto) throws IOException {
        return new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), puerto));
    }

    @Test
    void aFailedTcpBindClosesItsCandidateAndKeepsTheOriginalFailure() throws Exception {
        BindException fallo = new BindException("simulated TCP bind failure");
        IOException alCerrar = new IOException("simulated close failure");
        List<Candidato> creados = new ArrayList<>();
        AtomicInteger udp = new AtomicInteger();
        Throwable t = catchThrowable(() -> new ServidorDnsDePrueba(SILENCIO, () -> {
            Candidato c = new Candidato(fallo, alCerrar);
            creados.add(c);
            return c;
        }, puerto -> {
            udp.incrementAndGet();
            return udpReal(puerto);
        }));
        assertThat(t).isSameAs(fallo); // not retried and not replaced
        assertThat(t.getSuppressed()).containsExactly(alCerrar);
        assertThat(creados).hasSize(1).allMatch(c -> c.cerrado);
        assertThat(udp.get()).isZero();
    }

    @Test
    void udpCollisionsCloseEveryFailedCandidateBeforeRetrying() throws Exception {
        // Two scripted UDP collisions, then success. Simulated numbers over real loopback sockets bound
        // to port 0: no real UDP bind on an OS-chosen number, which would reintroduce correlated
        // candidates into this test (it did, in two of ten stability runs).
        AtomicInteger intentos = new AtomicInteger();
        var entorno = new Entorno(new AtomicInteger(50_000)::getAndIncrement, n -> false, n -> intentos.incrementAndGet() <= 2);
        ServidorDnsDePrueba servidor = new ServidorDnsDePrueba(SILENCIO, entorno.tcp(), entorno.udp());
        List<CandidatoSimulado> creados = entorno.candidatos;
        try {
            assertThat(creados).hasSize(3);
            assertThat(creados.subList(0, 2)).allMatch(c -> c.cerrado);
            assertThat(creados.get(2).cerrado).isFalse();
            assertThat(entorno.udpPedidos).hasSize(3).doesNotHaveDuplicates();
            assertThat(entorno.udpPedidos.get(2)).as("UDP and TCP share the number").isEqualTo(creados.get(2).numero);
            assertThat(entorno.tcpReservadoDuranteUdp).containsOnly(true);
        } finally {
            servidor.close();
        }
        assertThat(creados.get(2).cerrado).isTrue();
        assertThat(entorno.udpCreados).hasSize(1).allMatch(DatagramSocket::isClosed);
    }

    @Test
    void exhaustedRetriesCloseEveryCandidateAndReportTheLastCollision() {
        List<Candidato> creados = new ArrayList<>();
        List<BindException> colisiones = new ArrayList<>();
        Throwable t = catchThrowable(() -> new ServidorDnsDePrueba(SILENCIO, () -> {
            Candidato c = new Candidato(null, null);
            creados.add(c);
            return c;
        }, puerto -> {
            BindException e = new BindException("simulated UDP collision " + colisiones.size());
            colisiones.add(e);
            throw e;
        }));
        assertThat(t).isInstanceOf(ServidorDnsDePrueba.SinPuertoLibre.class).hasMessageStartingWith("no hay puerto libre UDP+TCP");
        assertThat(t.getCause()).isSameAs(colisiones.get(colisiones.size() - 1));
        assertThat(creados).hasSize(ServidorDnsDePrueba.INTENTOS).allMatch(c -> c.cerrado);
        // Every attempt is reported: its number, the UDP phase and the exception type.
        var intentos = ((ServidorDnsDePrueba.SinPuertoLibre) t).intentos();
        assertThat(intentos).hasSize(ServidorDnsDePrueba.INTENTOS)
                .allMatch(i -> i.fase().equals("UDP") && i.excepcion().equals("BindException"));
        assertThat(t.getMessage()).contains(String.valueOf(intentos.get(0).puerto()));
        assertThat(creados.get(0).pedido).as("the first candidate is the OS's choice").isZero();
        assertThat(creados.subList(1, creados.size())).as("later candidates are explicit numbers")
                .allMatch(c -> c.pedido >= ServidorDnsDePrueba.PRIMERO && c.pedido < ServidorDnsDePrueba.PRIMERO + ServidorDnsDePrueba.ESPACIO);
    }

    @Test
    void anUnexpectedUdpFailureClosesTheCandidateAndPropagatesUnchanged() {
        List<Candidato> creados = new ArrayList<>();
        IllegalStateException inesperado = new IllegalStateException("simulated UDP factory failure");
        Throwable t = catchThrowable(() -> new ServidorDnsDePrueba(SILENCIO, () -> {
            Candidato c = new Candidato(null, null);
            creados.add(c);
            return c;
        }, puerto -> {
            throw inesperado;
        }));
        assertThat(t).isSameAs(inesperado);
        assertThat(creados).hasSize(1).allMatch(c -> c.cerrado);
    }

    @Test
    void theSameExceptionFromBindAndCloseIsNotSelfSuppressedAndStaysTheOneThrown() {
        BindException mismo = new BindException("simulated failure thrown by bind and by close");
        List<Candidato> creados = new ArrayList<>();
        Throwable t = catchThrowable(() -> new ServidorDnsDePrueba(SILENCIO, () -> {
            Candidato c = new Candidato(mismo, mismo);
            creados.add(c);
            return c;
        }, ServidorDnsDePruebaTest::udpReal));
        // Not an IllegalArgumentException("Self-suppression not permitted") masking the failure.
        assertThat(t).isSameAs(mismo);
        assertThat(t.getSuppressed()).isEmpty();
        assertThat(creados).hasSize(1).allMatch(c -> c.cerrado);
    }

    @Test
    void aCloseErrorAfterAFailedConstructionPropagatesWithTheOriginalFailureAttached() {
        BindException fallo = new BindException("simulated TCP bind failure");
        AssertionError fatal = new AssertionError("simulated fatal close error");
        Throwable t = catchThrowable(() -> new ServidorDnsDePrueba(SILENCIO, () -> new Candidato(fallo, fatal),
                ServidorDnsDePruebaTest::udpReal));
        assertThat(t).isSameAs(fatal); // never swallowed, never converted
        assertThat(t.getSuppressed()).containsExactly(fallo);
        assertThat(fallo.getSuppressed()).isEmpty();

        // The same precedence when the close fails during a UDP collision: no retry past an Error.
        List<Candidato> creados = new ArrayList<>();
        BindException colision = new BindException("simulated UDP collision");
        AssertionError fatalEnReintento = new AssertionError("simulated fatal close error during a retry");
        Throwable enReintento = catchThrowable(() -> new ServidorDnsDePrueba(SILENCIO, () -> {
            Candidato c = new Candidato(null, fatalEnReintento);
            creados.add(c);
            return c;
        }, puerto -> {
            throw colision;
        }));
        assertThat(enReintento).isSameAs(fatalEnReintento);
        assertThat(enReintento.getSuppressed()).containsExactly(colision);
        assertThat(creados).hasSize(1).allMatch(c -> c.cerrado);
    }

    @Test
    void anOriginalErrorStaysTheOneThrownWithTheCloseFailureAttached() {
        AssertionError original = new AssertionError("simulated fatal UDP factory error");
        IOException alCerrar = new IOException("simulated close failure");
        List<Candidato> creados = new ArrayList<>();
        Throwable t = catchThrowable(() -> new ServidorDnsDePrueba(SILENCIO, () -> {
            Candidato c = new Candidato(null, alCerrar);
            creados.add(c);
            return c;
        }, puerto -> {
            throw original;
        }));
        assertThat(t).isSameAs(original);
        assertThat(t.getSuppressed()).containsExactly(alCerrar);
        assertThat(creados).hasSize(1).allMatch(c -> c.cerrado);
    }

    // ------------------------------------------------------------------ N3: dispersed, never repeated candidates

    /**
     * A real loopback listener (it binds port 0, so accept and close work) that reports a simulated
     * number: the next of a scripted "OS" sequence when port 0 is requested, otherwise the requested
     * number. It rejects numbers the scenario marks as busy for TCP. No case depends on real ephemeral
     * port sequences.
     */
    static final class CandidatoSimulado extends ServerSocket {
        private final IntSupplier sistema;
        private final IntPredicate tcpOcupado;
        volatile int pedido = -1;
        volatile int numero = -1;
        volatile boolean cerrado;

        CandidatoSimulado(IntSupplier sistema, IntPredicate tcpOcupado) throws IOException {
            this.sistema = sistema;
            this.tcpOcupado = tcpOcupado;
        }

        @Override
        public void bind(SocketAddress direccion) throws IOException {
            pedido = ((InetSocketAddress) direccion).getPort();
            int n = pedido == 0 ? sistema.getAsInt() : pedido;
            if (tcpOcupado.test(n)) {
                throw new BindException("simulated TCP: Address already in use");
            }
            super.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            numero = n;
        }

        @Override
        public int getLocalPort() {
            return numero;
        }

        @Override
        public void close() throws IOException {
            cerrado = true;
            super.close();
        }

        /** The number this candidate stood for: the one bound, or the one requested and refused. */
        int candidato() {
            return numero >= 0 ? numero : pedido;
        }
    }

    /** Simulated environment: scripted OS numbers and busy numbers; records every candidate and UDP bind. */
    static final class Entorno {
        final IntSupplier sistema;
        final IntPredicate tcpOcupado;
        final IntPredicate udpOcupado;
        final List<CandidatoSimulado> candidatos = Collections.synchronizedList(new ArrayList<>());
        final List<Integer> udpPedidos = Collections.synchronizedList(new ArrayList<>());
        final List<DatagramSocket> udpCreados = Collections.synchronizedList(new ArrayList<>());
        final List<Boolean> tcpReservadoDuranteUdp = Collections.synchronizedList(new ArrayList<>());

        Entorno(IntSupplier sistema, IntPredicate tcpOcupado, IntPredicate udpOcupado) {
            this.sistema = sistema;
            this.tcpOcupado = tcpOcupado;
            this.udpOcupado = udpOcupado;
        }

        ServidorDnsDePrueba.FabricaTcp tcp() {
            return () -> {
                CandidatoSimulado c = new CandidatoSimulado(sistema, tcpOcupado);
                candidatos.add(c);
                return c;
            };
        }

        ServidorDnsDePrueba.FabricaUdp udp() {
            return puerto -> {
                udpPedidos.add(puerto);
                CandidatoSimulado ultimo = candidatos.get(candidatos.size() - 1);
                tcpReservadoDuranteUdp.add(ultimo.numero == puerto && !ultimo.cerrado && ultimo.isBound());
                if (udpOcupado.test(puerto)) {
                    throw new BindException("simulated UDP: Address already in use");
                }
                DatagramSocket u = udpReal(0); // real loopback socket; its own number is irrelevant here
                udpCreados.add(u);
                return u;
            };
        }

        List<Integer> numeros() {
            List<Integer> numeros = new ArrayList<>();
            for (CandidatoSimulado c : List.copyOf(candidatos)) {
                numeros.add(c.candidato());
            }
            return numeros;
        }
    }

    @Test
    void aContiguousRejectedRegionIsLeftInsteadOfBeingSampledAgain() throws Exception {
        // The "OS" hands out consecutive numbers from 50000 and UDP is busy on 50000-50299, so every OS
        // choice lands in the busy region (the old fixture tried 50000-50019 and gave up).
        AtomicInteger siguiente = new AtomicInteger(50_000);
        IntPredicate region = n -> n >= 50_000 && n < 50_300;
        var entorno = new Entorno(siguiente::getAndIncrement, n -> false, region);
        ServidorDnsDePrueba servidor = new ServidorDnsDePrueba(SILENCIO, entorno.tcp(), entorno.udp());
        CandidatoSimulado elegido;
        try {
            List<Integer> numeros = entorno.numeros();
            assertThat(numeros.get(0)).isEqualTo(50_000);
            assertThat(numeros).doesNotHaveDuplicates();
            // Fallback numbers are at least 553 apart, so at most one more can fall in a 300-wide region.
            assertThat(numeros).hasSizeLessThanOrEqualTo(3);
            assertThat(numeros.subList(1, numeros.size()).stream().filter(region::test)).hasSizeLessThanOrEqualTo(1);
            elegido = entorno.candidatos.get(entorno.candidatos.size() - 1);
            assertThat(entorno.udpPedidos.get(entorno.udpPedidos.size() - 1)).as("TCP and UDP share the number")
                    .isEqualTo(elegido.numero);
            assertThat(entorno.tcpReservadoDuranteUdp).as("each TCP candidate stays bound while UDP binds").containsOnly(true);
            assertThat(entorno.candidatos.subList(0, entorno.candidatos.size() - 1)).allMatch(c -> c.cerrado);
            assertThat(elegido.cerrado).isFalse();
        } finally {
            servidor.close();
        }
        assertThat(elegido.cerrado).isTrue();
        assertThat(entorno.udpCreados).allMatch(DatagramSocket::isClosed);
    }

    @Test
    void aNumberAlreadyRejectedIsNeverTriedAgain() throws Exception {
        // The "OS" keeps handing back the same number, the one UDP rejects (the old fixture tried it 20 times).
        var entorno = new Entorno(() -> 50_000, n -> false, n -> n == 50_000);
        ServidorDnsDePrueba servidor = new ServidorDnsDePrueba(SILENCIO, entorno.tcp(), entorno.udp());
        try {
            assertThat(entorno.numeros()).hasSize(2).doesNotHaveDuplicates().startsWith(50_000);
            assertThat(entorno.candidatos.get(0).cerrado).isTrue();
        } finally {
            servidor.close();
        }
        assertThat(entorno.candidatos).allMatch(c -> c.cerrado);
    }

    @Test
    void dispersedNumbersAreInRangeNeverRepeatedAndFarApart() {
        for (long semilla : new long[]{0, 1, 7, 49_151, 16_383, 16_384, -1, Long.MIN_VALUE, Long.MAX_VALUE, System.nanoTime()}) {
            IntSupplier fuente = ServidorDnsDePrueba.dispersos(semilla);
            List<Integer> numeros = new ArrayList<>();
            for (int i = 0; i < ServidorDnsDePrueba.INTENTOS - 1; i++) {
                numeros.add(fuente.getAsInt());
            }
            assertThat(numeros).doesNotHaveDuplicates()
                    .allMatch(n -> n >= ServidorDnsDePrueba.PRIMERO && n < ServidorDnsDePrueba.PRIMERO + ServidorDnsDePrueba.ESPACIO);
            List<Integer> ordenados = new ArrayList<>(numeros);
            Collections.sort(ordenados);
            for (int i = 1; i < ordenados.size(); i++) {
                assertThat(ordenados.get(i) - ordenados.get(i - 1)).as("seed %d", semilla).isGreaterThanOrEqualTo(512);
            }
        }
        // The walk is a permutation of the range: no repeat before every number was used once.
        IntSupplier completo = ServidorDnsDePrueba.dispersos(12_345);
        Set<Integer> vistos = new HashSet<>();
        for (int i = 0; i < ServidorDnsDePrueba.ESPACIO; i++) {
            assertThat(vistos.add(completo.getAsInt())).isTrue();
        }
    }

    @Test
    void exhaustionReportsEveryAttemptedNumberAndWhereItFailed() {
        // UDP is busy everywhere; two explicit numbers are also busy for TCP.
        IntSupplier fuente = ServidorDnsDePrueba.dispersos(7);
        IntSupplier previsto = ServidorDnsDePrueba.dispersos(7);
        int tcpOcupado1 = previsto.getAsInt();
        previsto.getAsInt();
        int tcpOcupado2 = previsto.getAsInt();
        var entorno = new Entorno(() -> 50_000, n -> n == tcpOcupado1 || n == tcpOcupado2, n -> true);
        Throwable t = catchThrowable(() -> new ServidorDnsDePrueba(SILENCIO, entorno.tcp(), entorno.udp(), fuente, Thread::new));
        assertThat(t).isInstanceOf(ServidorDnsDePrueba.SinPuertoLibre.class).hasMessageStartingWith("no hay puerto libre UDP+TCP");
        assertThat(t.getCause()).isInstanceOf(BindException.class);
        var intentos = ((ServidorDnsDePrueba.SinPuertoLibre) t).intentos();
        assertThat(intentos).hasSize(ServidorDnsDePrueba.INTENTOS);
        assertThat(intentos.stream().map(ServidorDnsDePrueba.Intento::puerto).toList()).doesNotHaveDuplicates()
                .startsWith(50_000, tcpOcupado1).isEqualTo(entorno.numeros());
        assertThat(intentos.stream().filter(i -> i.fase().equals("TCP")).map(ServidorDnsDePrueba.Intento::puerto).toList())
                .containsExactly(tcpOcupado1, tcpOcupado2);
        assertThat(intentos).allMatch(i -> i.excepcion().equals("BindException"));
        for (var i : intentos) {
            assertThat(t.getMessage()).contains(i.toString());
        }
        assertThat(entorno.candidatos).hasSize(ServidorDnsDePrueba.INTENTOS).allMatch(c -> c.cerrado);
    }

    @Test
    void aCandidateSourceRepeatingNumbersIsSkippedAndOneThatOnlyRepeatsFailsClearly() {
        // Repeats are skipped: 50000 (OS), then 50500, 50500 (skipped), 51000.
        int[] guion = {50_500, 50_500, 51_000};
        AtomicInteger i = new AtomicInteger();
        var entorno = new Entorno(() -> 50_000, n -> false, n -> n == 50_000 || n == 50_500);
        Throwable sinFallo = catchThrowable(() -> {
            try (var s = new ServidorDnsDePrueba(SILENCIO, entorno.tcp(), entorno.udp(),
                    () -> guion[Math.min(i.getAndIncrement(), guion.length - 1)], Thread::new)) {
                assertThat(entorno.numeros()).containsExactly(50_000, 50_500, 51_000);
            }
        });
        assertThat(sinFallo).isNull();
        // A source with nothing new left ends the search instead of looping.
        var repetido = new Entorno(() -> 50_000, n -> false, n -> true);
        Throwable t = catchThrowable(() -> new ServidorDnsDePrueba(SILENCIO, repetido.tcp(), repetido.udp(), () -> 50_500, Thread::new));
        assertThat(t).isInstanceOf(IllegalStateException.class).hasMessageContaining("candidatos");
        assertThat(repetido.numeros()).containsExactly(50_000, 50_500);
        assertThat(repetido.candidatos).allMatch(c -> c.cerrado);
    }

    @Test
    void aThreadStartFailureClosesTheReservedSocketsAndTheStartedThreadAndKeepsTheFailure() throws Exception {
        for (Throwable fallo : List.of(new IllegalStateException("simulated thread creation failure"),
                new AssertionError("simulated fatal thread creation error"))) {
            var entorno = new Entorno(new AtomicInteger(50_000)::getAndIncrement, n -> false, n -> false);
            List<Thread> creados = Collections.synchronizedList(new ArrayList<>());
            ThreadFactory fabrica = r -> {
                if (!creados.isEmpty()) {
                    if (fallo instanceof Error e) {
                        throw e;
                    }
                    throw (RuntimeException) fallo;
                }
                Thread hilo = new Thread(r);
                creados.add(hilo);
                return hilo;
            };
            Throwable t = catchThrowable(() -> new ServidorDnsDePrueba(SILENCIO, entorno.tcp(), entorno.udp(),
                    ServidorDnsDePrueba.dispersos(1), fabrica));
            assertThat(t).isSameAs(fallo);
            assertThat(t.getSuppressed()).isEmpty();
            assertThat(entorno.candidatos).hasSize(1).allMatch(c -> c.cerrado);
            assertThat(entorno.udpCreados).hasSize(1).allMatch(DatagramSocket::isClosed);
            assertThat(creados).hasSize(1);
            creados.get(0).join(TimeUnit.SECONDS.toMillis(5));
            assertThat(creados.get(0).isAlive()).as("the thread already started was stopped").isFalse();
        }
    }

    @Test
    void closeEndsAConnectionBlockedInARead() throws Exception {
        // The server thread waits for the second byte of the length prefix: an interruption cannot end
        // that socket read, only closing the served connection can.
        ServidorDnsDePrueba servidor = new ServidorDnsDePrueba(SILENCIO);
        try (Socket cliente = new Socket(Proxy.NO_PROXY)) {
            try {
                cliente.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), servidor.puerto()), 5_000);
                cliente.setSoTimeout(5_000);
                cliente.getOutputStream().write(0);
                cliente.getOutputStream().flush();
                assertThat(servidor.esperarConexion()).as("the connection is being served").isTrue();
                servidor.close();
                assertCerradaPorElServidor(cliente);
                assertThat(servidor.hilos()).hasSize(2).noneMatch(Thread::isAlive);
            } finally {
                servidor.close(); // closing twice is harmless
            }
        }
    }

    static void assertCerradaPorElServidor(Socket cliente) throws IOException {
        int leido;
        try {
            leido = cliente.getInputStream().read();
        } catch (SocketTimeoutException e) {
            throw new AssertionError("the served connection was left open", e);
        } catch (SocketException e) {
            leido = -1; // closed with a reset: also closed
        }
        assertThat(leido).isEqualTo(-1);
    }

    @Test
    void closeEndsTheServedConnectionAndJoinsTheFixtureThreads() throws Exception {
        // The server thread is inside a scripted pause that only an interruption ends.
        CountDownLatch enGuion = new CountDownLatch(1);
        CountDownLatch nunca = new CountDownLatch(1);
        ServidorDnsDePrueba servidor = new ServidorDnsDePrueba((q, tcp) -> {
            if (tcp) {
                enGuion.countDown();
                try {
                    nunca.await(); // a scripted pause that only an interruption ends
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return List.of();
        });
        try (Socket cliente = new Socket(Proxy.NO_PROXY)) {
            try {
                cliente.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), servidor.puerto()), 5_000);
                cliente.setSoTimeout(5_000);
                byte[] consulta = consultaMinima();
                OutputStream out = cliente.getOutputStream();
                out.write(new byte[]{0, (byte) consulta.length});
                out.write(consulta);
                out.flush();
                assertThat(enGuion.await(5, TimeUnit.SECONDS)).as("the connection is being served").isTrue();
                servidor.close();
                assertCerradaPorElServidor(cliente);
                assertThat(servidor.hilos()).hasSize(2).noneMatch(Thread::isAlive);
            } finally {
                nunca.countDown();
                servidor.close(); // closing twice is harmless
            }
        }
    }

    /** A minimal well-formed query for www.ejemplo.es, type A, class IN, RD set. */
    static byte[] consultaMinima() {
        byte[] cabecera = {0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0};
        byte[] nombre = {3, 'w', 'w', 'w', 7, 'e', 'j', 'e', 'm', 'p', 'l', 'o', 2, 'e', 's', 0};
        byte[] q = new byte[cabecera.length + nombre.length + 4];
        System.arraycopy(cabecera, 0, q, 0, cabecera.length);
        System.arraycopy(nombre, 0, q, cabecera.length, nombre.length);
        q[q.length - 3] = 1; // type A
        q[q.length - 1] = 1; // class IN
        return q;
    }
}
