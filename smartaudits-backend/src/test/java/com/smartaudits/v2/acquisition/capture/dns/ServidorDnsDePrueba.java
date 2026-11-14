package com.smartaudits.v2.acquisition.capture.dns;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.IntSupplier;

/**
 * Loopback-only scripted DNS server (UDP and TCP on the same 127.0.0.1 port). The script returns,
 * for each received query, the datagrams to send back (none: stay silent). It records every query.
 */
final class ServidorDnsDePrueba implements AutoCloseable {

    record Consulta(String nombre, int tipo, boolean tcp, boolean rd, boolean edns) {}

    /** (query bytes, over TCP) → responses to send, in order. */
    interface Guion extends BiFunction<byte[], Boolean, List<byte[]>> {}

    private final DatagramSocket udp;
    private final ServerSocket tcp;
    private final Guion guion;
    private final List<Consulta> consultas = Collections.synchronizedList(new ArrayList<>());

    /** Creates the unbound TCP candidate (a seam so failure paths are tested without exhausting ports). */
    interface FabricaTcp {
        ServerSocket crear() throws IOException;
    }

    /** Creates the UDP socket bound to the given loopback port. */
    interface FabricaUdp {
        DatagramSocket crear(int puerto) throws IOException;
    }

    static final int INTENTOS = 20;
    /** Fallback numbers: the IANA dynamic range 49152-65535, valid for unprivileged loopback binds on Windows and Linux. */
    static final int PRIMERO = 49152;
    static final int ESPACIO = 16384;
    /**
     * Odd, so the walk visits every number of the range exactly once; close to ESPACIO divided by the
     * golden ratio, so any 19 consecutive steps are at least 553 numbers apart and cover the range evenly.
     */
    static final int PASO = 10125;

    /** The TCP and UDP sockets that share one loopback number. */
    record Reserva(ServerSocket tcp, DatagramSocket udp) {}

    /** One rejected candidate: its number, where it failed (TCP or UDP) and the exception type. */
    record Intento(int puerto, String fase, String excepcion) {
        @Override
        public String toString() {
            return puerto + " " + fase + " " + excepcion;
        }
    }

    /** No shared number was found; carries every attempted number and where it failed. */
    static final class SinPuertoLibre extends IOException {
        private final transient List<Intento> intentos;

        SinPuertoLibre(List<Intento> intentos, IOException ultimo) {
            super("no hay puerto libre UDP+TCP; intentos: " + intentos, ultimo);
            this.intentos = List.copyOf(intentos);
        }

        List<Intento> intentos() {
            return intentos;
        }
    }

    private final List<Thread> hilos = new ArrayList<>(2);
    private volatile Socket enCurso; // the accepted connection being served, closed by close()
    private final CountDownLatch aceptada = new CountDownLatch(1);

    ServidorDnsDePrueba(Guion guion) throws IOException {
        this(guion, ServerSocket::new,
                puerto -> new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), puerto)));
    }

    ServidorDnsDePrueba(Guion guion, FabricaTcp fabricaTcp, FabricaUdp fabricaUdp) throws IOException {
        this(guion, fabricaTcp, fabricaUdp, dispersos(System.nanoTime()), Thread::new);
    }

    /**
     * Full seam: the fallback numbers and the thread factory too. The sockets come from
     * {@link #reservar}. If starting the threads fails, the fixture is closed (its sockets, and a thread
     * already started) and the original failure is kept (see {@link #cerrarConservando}).
     */
    ServidorDnsDePrueba(Guion guion, FabricaTcp fabricaTcp, FabricaUdp fabricaUdp, IntSupplier candidatos,
                        ThreadFactory fabricaHilos) throws IOException {
        this.guion = guion;
        Reserva reserva = reservar(fabricaTcp, fabricaUdp, candidatos);
        this.udp = reserva.udp();
        this.tcp = reserva.tcp();
        try {
            arrancar(fabricaHilos, this::atenderUdp);
            arrancar(fabricaHilos, this::atenderTcp);
        } catch (Throwable e) {
            Error fatal = cerrarConservando(this, e);
            if (fatal != null) {
                throw fatal;
            }
            throw e;
        }
    }

    /**
     * Fallback numbers: a {@value #PASO}-stride walk over the dynamic range from a start derived from
     * {@code semilla}. No number repeats within {@value #ESPACIO} draws, and successive numbers are far
     * apart, so a rejected contiguous region is left instead of sampled again.
     */
    static IntSupplier dispersos(long semilla) {
        int inicio = (int) Math.floorMod(semilla, (long) ESPACIO);
        int[] paso = {0};
        return () -> PRIMERO + (int) Math.floorMod(inicio + (long) PASO * paso[0]++, (long) ESPACIO);
    }

    /**
     * Finds one loopback number free for both TCP and UDP, within {@value #INTENTOS} candidates. The first
     * candidate is the number the OS picks for listening; every later one is an explicit number from
     * {@code candidatos}, never a number already tried, so a correlated or contiguous rejected region is not
     * sampled again. Each TCP candidate stays bound while UDP binds the same number. Every candidate is owned
     * from its creation: a failed one is closed before the next, and a failure that ends the search closes it
     * and keeps the original exception (see {@link #cerrarConservando}). A rejected number (TCP for an explicit
     * one, UDP for any) is retried with another; a TCP bind failure on the OS-picked number is reported
     * unchanged. Exhaustion reports every attempted number. A search that needed a fallback is noted on
     * stderr (numbers and exception types only), as evidence of how often the first choice fails.
     */
    static Reserva reservar(FabricaTcp fabricaTcp, FabricaUdp fabricaUdp, IntSupplier candidatos) throws IOException {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        List<Intento> rechazados = new ArrayList<>();
        Set<Integer> usados = new HashSet<>();
        IOException ultimo = null;
        for (int intento = 0; intento < INTENTOS; intento++) {
            int pedido = intento == 0 ? 0 : nuevo(candidatos, usados);
            ServerSocket candidato = fabricaTcp.crear();
            int puerto = pedido;
            boolean vinculado = false;
            try {
                candidato.bind(new InetSocketAddress(loopback, pedido));
                vinculado = true;
                puerto = candidato.getLocalPort();
                usados.add(puerto);
                Reserva reserva = new Reserva(candidato, fabricaUdp.crear(puerto)); // TCP still bound here
                if (!rechazados.isEmpty()) {
                    System.err.println("[ServidorDnsDePrueba] " + puerto + " elegido tras rechazar " + rechazados);
                }
                return reserva;
            } catch (IOException e) {
                Error fatal = cerrarConservando(candidato, e);
                if (fatal != null) {
                    throw fatal; // never retried past, nor swallowed
                }
                if (!vinculado && pedido == 0) {
                    throw e; // the OS gave no TCP number at all: not a collision
                }
                usados.add(puerto);
                rechazados.add(new Intento(puerto, vinculado ? "UDP" : "TCP", e.getClass().getSimpleName()));
                ultimo = e;
            } catch (Throwable e) {
                Error fatal = cerrarConservando(candidato, e);
                if (fatal != null) {
                    throw fatal;
                }
                throw e;
            }
        }
        throw new SinPuertoLibre(rechazados, ultimo);
    }

    /** The next candidate not tried yet; bounded, so a source that only repeats fails clearly. */
    private static int nuevo(IntSupplier candidatos, Set<Integer> usados) {
        for (int i = 0; i < ESPACIO; i++) {
            int p = candidatos.getAsInt();
            if (p > 0 && p <= 65535 && !usados.contains(p)) {
                return p;
            }
        }
        throw new IllegalStateException("la fuente de candidatos no da números nuevos");
    }

    /**
     * Closes a resource whose use ended with {@code original}, with the precedence of the production
     * cleanup: a non-Error close failure is attached to the original as suppressed (never to itself) and
     * the original is kept (null is returned); a close Error over a non-Error original is returned,
     * carrying the original as suppressed, to be thrown in its place; an original Error stays the one
     * thrown, with a close Error attached.
     */
    private static Error cerrarConservando(AutoCloseable recurso, Throwable original) {
        try {
            recurso.close();
            return null;
        } catch (Error e) {
            if (e == original) {
                return null;
            }
            if (original instanceof Error) {
                original.addSuppressed(e);
                return null;
            }
            e.addSuppressed(original);
            return e;
        } catch (Exception e) {
            if (e != original) {
                original.addSuppressed(e);
            }
            return null;
        }
    }

    int puerto() {
        return udp.getLocalPort();
    }

    List<Consulta> consultas() {
        return List.copyOf(consultas);
    }

    /** This fixture's own threads (tests only). */
    List<Thread> hilos() {
        return List.copyOf(hilos);
    }

    /** Waits, at most 5 s, until a TCP connection has been accepted and is being served (tests only). */
    boolean esperarConexion() throws InterruptedException {
        return aceptada.await(5, TimeUnit.SECONDS);
    }

    private void arrancar(ThreadFactory fabrica, Runnable tarea) {
        Thread t = fabrica.newThread(tarea);
        t.setName("dns-de-prueba");
        t.setDaemon(true);
        hilos.add(t);
        t.start();
    }

    private void atenderUdp() {
        byte[] buffer = new byte[4096];
        while (!udp.isClosed()) {
            try {
                DatagramPacket p = new DatagramPacket(buffer, buffer.length);
                udp.receive(p);
                byte[] q = java.util.Arrays.copyOf(p.getData(), p.getLength());
                registrar(q, false);
                for (byte[] r : guion.apply(q, false)) {
                    udp.send(new DatagramPacket(r, r.length, p.getSocketAddress()));
                }
            } catch (IOException e) {
                return;
            }
        }
    }

    /** Writes the scripted TCP responses on the accepted connection (PV-15 drip scenarios). */
    interface EscritorTcp {
        void escribir(Socket conexion, List<byte[]> respuestas) throws IOException, InterruptedException;
    }

    private volatile EscritorTcp escritorTcp;

    /** Replaces the default "length + message" TCP writing for the following connections. */
    ServidorDnsDePrueba conEscritorTcp(EscritorTcp escritor) {
        this.escritorTcp = escritor;
        return this;
    }

    private void atenderTcp() {
        while (!tcp.isClosed()) {
            Socket s;
            try {
                s = tcp.accept();
            } catch (IOException e) {
                if (tcp.isClosed()) {
                    return;
                }
                continue;
            }
            enCurso = s;
            aceptada.countDown();
            try (s) {
                if (tcp.isClosed()) {
                    return; // close() may have read enCurso before it was set: this connection ends here
                }
                DataInputStream in = new DataInputStream(s.getInputStream());
                byte[] q = new byte[in.readUnsignedShort()];
                in.readFully(q);
                registrar(q, true);
                EscritorTcp escritor = escritorTcp;
                if (escritor != null) {
                    escritor.escribir(s, guion.apply(q, true));
                    continue;
                }
                OutputStream out = s.getOutputStream();
                for (byte[] r : guion.apply(q, true)) {
                    out.write(new byte[]{(byte) (r.length >> 8), (byte) r.length});
                    out.write(r);
                }
                out.flush();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException e) {
                if (tcp.isClosed()) {
                    return;
                }
            } finally {
                enCurso = null;
            }
        }
    }

    private void registrar(byte[] q, boolean tcp) {
        consultas.add(new Consulta(nombre(q), tipo(q), tcp, (q[2] & 0x01) != 0, ((q[10] & 0xFF) << 8 | (q[11] & 0xFF)) == 1));
    }

    // ------------------------------------------------------------- response builder

    static String nombre(byte[] q) {
        StringBuilder sb = new StringBuilder();
        int i = 12;
        while (q[i] != 0) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(new String(q, i + 1, q[i], StandardCharsets.US_ASCII));
            i += q[i] + 1;
        }
        return sb.toString();
    }

    static int tipo(byte[] q) {
        int i = 12;
        while (q[i] != 0) {
            i += q[i] + 1;
        }
        return ((q[i + 1] & 0xFF) << 8) | (q[i + 2] & 0xFF);
    }

    static int id(byte[] q) {
        return ((q[0] & 0xFF) << 8) | (q[1] & 0xFF);
    }

    record Registro(String propietario, int tipo, byte[] datos, String destinoCname) {
        static Registro a(String propietario, int... octetos) {
            byte[] b = new byte[octetos.length];
            for (int i = 0; i < octetos.length; i++) {
                b[i] = (byte) octetos[i];
            }
            return new Registro(propietario, ResolvedorDnsDirecto.TYPE_A, b, null);
        }

        static Registro aaaa(String propietario, byte[] dieciseis) {
            return new Registro(propietario, ResolvedorDnsDirecto.TYPE_AAAA, dieciseis, null);
        }

        static Registro cname(String propietario, String destino) {
            return new Registro(propietario, ResolvedorDnsDirecto.TYPE_CNAME, null, destino);
        }
    }

    /** Response to the query q: same ID and question, the given RCODE, TC flag and answers. */
    static byte[] respuesta(byte[] q, int id, int rcode, boolean tc, List<Registro> respuestas) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int fin = 12;
        while (q[fin] != 0) {
            fin += q[fin] + 1;
        }
        fin += 5; // root + type + class
        dos(out, id);
        dos(out, 0x8000 | 0x0100 | 0x0080 | (tc ? 0x0200 : 0) | rcode); // QR, RD, RA
        dos(out, 1);
        dos(out, respuestas.size());
        dos(out, 0);
        dos(out, 0);
        out.write(q, 12, fin - 12);
        for (Registro r : respuestas) {
            nombreEnBruto(out, r.propietario());
            dos(out, r.tipo());
            dos(out, ResolvedorDnsDirecto.CLASS_IN);
            dos(out, 0);
            dos(out, 60);
            byte[] datos = r.destinoCname() != null ? enBruto(r.destinoCname()) : r.datos();
            dos(out, datos.length);
            out.write(datos, 0, datos.length);
        }
        return out.toByteArray();
    }

    static byte[] respuesta(byte[] q, int rcode, List<Registro> respuestas) {
        return respuesta(q, id(q), rcode, false, respuestas);
    }

    private static byte[] enBruto(String nombre) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        nombreEnBruto(out, nombre);
        return out.toByteArray();
    }

    private static void nombreEnBruto(ByteArrayOutputStream out, String nombre) {
        for (String etiqueta : nombre.split("\\.")) {
            byte[] b = etiqueta.getBytes(StandardCharsets.US_ASCII);
            out.write(b.length);
            out.write(b, 0, b.length);
        }
        out.write(0);
    }

    private static void dos(ByteArrayOutputStream out, int v) {
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    /**
     * Releases everything this fixture owns, each step attempted even when an earlier one fails: both
     * sockets (which ends a blocked receive or accept), the connection being served, then its threads,
     * interrupted (a scripted pause) and joined with a 5 s bound. A thread still alive after that is
     * reported. The first failure is thrown, later ones attached (an Error first).
     */
    @Override
    public void close() throws IOException {
        Throwable fallo = paso(null, udp::close);
        fallo = paso(fallo, tcp::close);
        fallo = paso(fallo, () -> {
            Socket s = enCurso;
            if (s != null) {
                s.close();
            }
        });
        fallo = paso(fallo, this::detenerHilos);
        if (fallo instanceof IOException e) {
            throw e;
        }
        if (fallo instanceof RuntimeException e) {
            throw e;
        }
        if (fallo instanceof Error e) {
            throw e;
        }
    }

    private interface Paso {
        void ejecutar() throws IOException;
    }

    private static Throwable paso(Throwable acumulado, Paso paso) {
        try {
            paso.ejecutar();
            return acumulado;
        } catch (IOException | RuntimeException | Error t) {
            if (acumulado == null || acumulado == t) {
                return t;
            }
            if (t instanceof Error && !(acumulado instanceof Error)) {
                t.addSuppressed(acumulado);
                return t;
            }
            acumulado.addSuppressed(t);
            return acumulado;
        }
    }

    private void detenerHilos() {
        for (Thread h : hilos) {
            h.interrupt();
        }
        boolean interrumpido = false;
        long fin = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        for (Thread h : hilos) {
            long resto;
            while (h.isAlive() && (resto = fin - System.nanoTime()) > 0) {
                try {
                    h.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(resto)));
                } catch (InterruptedException e) {
                    interrumpido = true;
                }
            }
        }
        if (interrumpido) {
            Thread.currentThread().interrupt();
        }
        for (Thread h : hilos) {
            if (h.isAlive()) {
                throw new IllegalStateException("un hilo del DNS de prueba sigue vivo tras cerrar");
            }
        }
    }
}
