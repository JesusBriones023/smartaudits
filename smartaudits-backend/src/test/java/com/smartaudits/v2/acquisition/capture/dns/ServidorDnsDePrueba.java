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
import java.util.List;
import java.util.function.BiFunction;

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

    ServidorDnsDePrueba(Guion guion) throws IOException {
        this.guion = guion;
        DatagramSocket u = null;
        ServerSocket t = null;
        IOException ultimo = null;
        for (int intento = 0; intento < 20 && t == null; intento++) {
            u = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            ServerSocket candidato = new ServerSocket();
            try {
                candidato.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), u.getLocalPort()));
                t = candidato;
            } catch (IOException e) {
                ultimo = e;
                candidato.close();
                u.close();
            }
        }
        if (t == null) {
            throw new IOException("no hay puerto libre UDP+TCP", ultimo);
        }
        this.udp = u;
        this.tcp = t;
        hilo(this::atenderUdp);
        hilo(this::atenderTcp);
    }

    int puerto() {
        return udp.getLocalPort();
    }

    List<Consulta> consultas() {
        return List.copyOf(consultas);
    }

    private static void hilo(Runnable r) {
        Thread t = new Thread(r, "dns-de-prueba");
        t.setDaemon(true);
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

    private void atenderTcp() {
        while (!tcp.isClosed()) {
            try (Socket s = tcp.accept()) {
                DataInputStream in = new DataInputStream(s.getInputStream());
                byte[] q = new byte[in.readUnsignedShort()];
                in.readFully(q);
                registrar(q, true);
                OutputStream out = s.getOutputStream();
                for (byte[] r : guion.apply(q, true)) {
                    out.write(new byte[]{(byte) (r.length >> 8), (byte) r.length});
                    out.write(r);
                }
                out.flush();
            } catch (IOException e) {
                if (tcp.isClosed()) {
                    return;
                }
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

    @Override
    public void close() throws IOException {
        udp.close();
        tcp.close();
    }
}
