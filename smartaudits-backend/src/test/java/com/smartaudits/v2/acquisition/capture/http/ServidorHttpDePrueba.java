package com.smartaudits.v2.acquisition.capture.http;

import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Loopback-only test server (127.0.0.1, ephemeral port), plain or TLS. It records what it received
 * (SNI, request line, headers) and answers with the bytes the test scripts. Never reachable from
 * outside the machine and never contacted with a real URL.
 */
final class ServidorHttpDePrueba implements AutoCloseable {

    interface Manejador {
        void responder(Peticion peticion, OutputStream out) throws Exception;
    }

    record Peticion(String linea, Map<String, String> cabeceras, List<String> sni) {}

    private final ServerSocket servidor;
    private final Manejador manejador;
    private final List<Peticion> peticiones = Collections.synchronizedList(new ArrayList<>());
    private final List<String> sniVistos = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger conexiones = new AtomicInteger();
    private final AtomicInteger handshakesFallidos = new AtomicInteger();

    private ServidorHttpDePrueba(ServerSocket servidor, Manejador manejador) {
        this.servidor = servidor;
        this.manejador = manejador;
        Thread hilo = new Thread(this::aceptar, "servidor-http-de-prueba");
        hilo.setDaemon(true);
        hilo.start();
    }

    static ServidorHttpDePrueba plano(Manejador manejador) throws IOException {
        ServerSocket s = new ServerSocket();
        s.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        return new ServidorHttpDePrueba(s, manejador);
    }

    static ServidorHttpDePrueba tls(SSLContext contexto, Manejador manejador) throws IOException {
        ServerSocket s = contexto.getServerSocketFactory().createServerSocket();
        s.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        return new ServidorHttpDePrueba(s, manejador);
    }

    /** Fixed raw response (ISO-8859-1 text). */
    static Manejador fija(String respuesta) {
        return (p, out) -> out.write(respuesta.getBytes(StandardCharsets.ISO_8859_1));
    }

    static Manejador html(String html) {
        byte[] cuerpo = html.getBytes(StandardCharsets.UTF_8);
        return (p, out) -> {
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + cuerpo.length
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(cuerpo);
        };
    }

    int puerto() {
        return servidor.getLocalPort();
    }

    List<Peticion> peticiones() {
        return List.copyOf(peticiones);
    }

    List<String> sniVistos() {
        return List.copyOf(sniVistos);
    }

    int conexiones() {
        return conexiones.get();
    }

    int handshakesFallidos() {
        return handshakesFallidos.get();
    }

    private void aceptar() {
        while (!servidor.isClosed()) {
            try {
                Socket s = servidor.accept();
                conexiones.incrementAndGet();
                Thread hilo = new Thread(() -> atender(s), "conexion-de-prueba");
                hilo.setDaemon(true);
                hilo.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void atender(Socket s) {
        try (s) {
            s.setSoTimeout(10_000);
            List<String> sni = List.of();
            if (s instanceof SSLSocket ssl) {
                try {
                    ssl.startHandshake();
                } catch (IOException e) {
                    handshakesFallidos.incrementAndGet();
                    return;
                }
                List<String> nombres = new ArrayList<>();
                for (SNIServerName n : ((ExtendedSSLSession) ssl.getSession()).getRequestedServerNames()) {
                    nombres.add(((SNIHostName) n).getAsciiName());
                }
                sni = List.copyOf(nombres);
                sniVistos.addAll(sni);
            }
            String cabeza = leerCabeza(s.getInputStream());
            if (cabeza == null) {
                return;
            }
            String[] lineas = cabeza.split("\r\n");
            Map<String, String> cabeceras = new LinkedHashMap<>();
            for (int i = 1; i < lineas.length; i++) {
                int dp = lineas[i].indexOf(':');
                if (dp > 0) {
                    cabeceras.put(lineas[i].substring(0, dp).toLowerCase(), lineas[i].substring(dp + 1).strip());
                }
            }
            Peticion peticion = new Peticion(lineas[0], cabeceras, sni);
            peticiones.add(peticion);
            OutputStream out = s.getOutputStream();
            manejador.responder(peticion, out);
            out.flush();
        } catch (Exception e) {
            // the client may close first (limits, timeouts): nothing to report
        }
    }

    private static String leerCabeza(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) >= 0) {
            out.write(b);
            byte[] a = out.toByteArray();
            int n = a.length;
            if (n >= 4 && a[n - 4] == '\r' && a[n - 3] == '\n' && a[n - 2] == '\r' && a[n - 1] == '\n') {
                return new String(a, 0, n - 4, StandardCharsets.ISO_8859_1);
            }
            if (n > 16_384) {
                return null;
            }
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        servidor.close();
    }
}
