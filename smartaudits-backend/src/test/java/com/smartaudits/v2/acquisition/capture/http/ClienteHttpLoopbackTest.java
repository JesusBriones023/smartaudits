package com.smartaudits.v2.acquisition.capture.http;

import com.smartaudits.v2.acquisition.capture.CapturaException;
import com.smartaudits.v2.acquisition.capture.CapturaException.Motivo;
import com.smartaudits.v2.acquisition.capture.TransporteHttpReal;
import com.smartaudits.v2.acquisition.capture.dns.ResolvedorDnsDirecto;
import com.smartaudits.v2.acquisition.destination.DestinationUrl;
import com.smartaudits.v2.acquisition.destination.IpAddress;
import com.smartaudits.v2.acquisition.destination.UrlParseResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The lower layers with REAL operating-system sockets on loopback: the production ConectorDirecto,
 * real JSSE handshakes and real HTTP exchanges against local servers. The URL's effective port is
 * mapped to the test server's ephemeral port through the package-private seam of
 * ClienteHttpFijado; DestinationPolicy is not involved (it rejects loopback, and must keep doing so:
 * see {@link #theProductFlowStillRejectsLoopbackEvenWithTheRealConnector}). No external network.
 */
class ClienteHttpLoopbackTest {
    static final String HOST = "www.ejemplo.es";
    static final DestinationUrl URL = url("https://www.ejemplo.es/legal/privacidad?x=1%2F");
    static final IpAddress LOCAL = IpAddress.ipv4(new byte[]{127, 0, 0, 1});
    static final String PAGINA = "<html><body><h1>Política</h1></body></html>";
    static final LimitesHttp LIMITES = new LimitesHttp(Duration.ofSeconds(2), Duration.ofMillis(700), Duration.ofMillis(1500),
            32 * 1024, 100, 64 * 1024, 128 * 1024, 3);

    static PkiDePrueba.Credencial ca;
    static PkiDePrueba.Credencial caAjena;
    static SSLContext confianza;

    final List<AutoCloseable> recursos = new ArrayList<>();

    @BeforeAll
    static void pki() throws Exception {
        ca = PkiDePrueba.ca("CA loopback SmartAudits");
        caAjena = PkiDePrueba.ca("CA ajena loopback");
        confianza = PkiDePrueba.cliente(ca);
    }

    @AfterEach
    void cerrar() throws Exception {
        for (AutoCloseable r : recursos) {
            r.close();
        }
    }

    static DestinationUrl url(String texto) {
        return ((UrlParseResult.Parsed) DestinationUrl.parse(texto)).url();
    }

    ServidorHttpDePrueba tls(PkiDePrueba.Credencial hoja, PkiDePrueba.Credencial emisora, ServidorHttpDePrueba.Manejador m) throws Exception {
        var s = ServidorHttpDePrueba.tls(PkiDePrueba.servidor(hoja, emisora), m);
        recursos.add(s);
        return s;
    }

    ServidorHttpDePrueba tls(ServidorHttpDePrueba.Manejador m) throws Exception {
        return tls(PkiDePrueba.hoja(ca, HOST), ca, m);
    }

    /** The production connector; only the dialled port is the test server's. */
    static ClienteHttpFijado cliente(int puertoServidor) {
        return new ClienteHttpFijado(new ConectorDirecto(), confianza.getSocketFactory(), LIMITES, p -> puertoServidor);
    }

    static CapturaException falla(Runnable accion, Motivo motivo, String mensaje) {
        try {
            accion.run();
        } catch (CapturaException e) {
            assertThat(e.motivo()).as(e.getMessage()).isEqualTo(motivo);
            assertThat(e.getMessage()).contains(mensaje);
            return e;
        }
        throw new AssertionError("se esperaba CapturaException " + motivo);
    }

    // ------------------------------------------------------------------ 1-5: real connection, peer, no DNS, Host, SNI

    @Test
    void aRealSocketReachesTheRequestedIpWithSniHostAndNoNameResolution() throws Exception {
        var servidor = tls(ServidorHttpDePrueba.html(PAGINA));

        var observado = MonitorDeConexiones.durante(() -> cliente(servidor.puerto()).obtener(URL, LOCAL));

        assertThat(observado.error()).isNull();
        RespuestaHttp respuesta = observado.resultado();
        assertThat(respuesta.estado()).isEqualTo(200);
        assertThat(new String(respuesta.cuerpo(), StandardCharsets.UTF_8)).isEqualTo(PAGINA);
        ObservacionConexion o = respuesta.observacion();
        assertThat(o.peerObservado()).isTrue();
        assertThat(o.peer()).isEqualTo(LOCAL);
        assertThat(o.puerto()).isEqualTo(servidor.puerto());
        assertThat(o.tls()).isTrue();
        assertThat(o.sni()).isEqualTo(HOST);
        assertThat(o.identidadTls()).isEqualTo(HOST);
        assertThat(o.protocoloTls()).isIn("TLSv1.3", "TLSv1.2");
        // The server really received the authorized name in the ClientHello and in Host.
        assertThat(servidor.sniVistos()).containsExactly(HOST);
        assertThat(servidor.peticiones().get(0).linea()).isEqualTo("GET /legal/privacidad?x=1%2F HTTP/1.1");
        assertThat(servidor.peticiones().get(0).cabeceras()).containsEntry("host", HOST);
        // The JDK resolved no name at all and connected to exactly one target: the requested IP.
        assertThat(observado.resoluciones()).isEmpty();
        assertThat(observado.conexiones()).extracting(MonitorDeConexiones.Evento::host, MonitorDeConexiones.Evento::puerto)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("127.0.0.1", servidor.puerto()));
    }

    @Test
    void theObservedPeerIsTheRequestedLoopbackAddressAndThereIsNoFallback() throws Exception {
        InetAddress segunda = InetAddress.getByAddress(new byte[]{127, 0, 0, 2});
        ServidorHttpDePrueba servidor;
        try {
            servidor = ServidorHttpDePrueba.tls(PkiDePrueba.servidor(PkiDePrueba.hoja(ca, HOST), ca), segunda,
                    ServidorHttpDePrueba.html(PAGINA));
        } catch (IOException e) {
            Assumptions.abort("este sistema no permite escuchar en 127.0.0.2");
            return;
        }
        recursos.add(servidor);
        IpAddress pedida = IpAddress.ipv4(new byte[]{127, 0, 0, 2});

        RespuestaHttp respuesta = cliente(servidor.puerto()).obtener(URL, pedida);
        assertThat(respuesta.observacion().peer()).isEqualTo(pedida);

        // Nothing listens on 127.0.0.3: the connection fails; no other address is tried.
        falla(() -> cliente(servidor.puerto()).obtener(URL, IpAddress.ipv4(new byte[]{127, 0, 0, 3})),
                Motivo.TRANSPORTE, "No se pudo conectar");
        assertThat(servidor.conexiones()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 6: certificates

    @Test
    void wrongExpiredOrUntrustedCertificatesAbortTheRealHandshake() throws Exception {
        var otroDominio = tls(PkiDePrueba.hoja(ca, "otro.ejemplo.es"), ca, ServidorHttpDePrueba.html(PAGINA));
        var caducado = tls(PkiDePrueba.hoja(ca, HOST, Instant.now().minus(Duration.ofDays(3)),
                Instant.now().minus(Duration.ofDays(1)), HOST), ca, ServidorHttpDePrueba.html(PAGINA));
        var ajeno = tls(PkiDePrueba.hoja(caAjena, HOST), caAjena, ServidorHttpDePrueba.html(PAGINA));
        var soloCn = tls(PkiDePrueba.hoja(ca, HOST, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600)), ca,
                ServidorHttpDePrueba.html(PAGINA));
        for (ServidorHttpDePrueba s : List.of(otroDominio, caducado, ajeno, soloCn)) {
            falla(() -> cliente(s.puerto()).obtener(URL, LOCAL), Motivo.TRANSPORTE, "identidad TLS");
        }
        Thread.sleep(200);
        for (ServidorHttpDePrueba s : List.of(otroDominio, caducado, ajeno, soloCn)) {
            assertThat(s.peticiones()).isEmpty();
            assertThat(s.cerradasPorCliente()).isEqualTo(1);
        }
        assertThat(otroDominio.handshakesFallidos() + caducado.handshakesFallidos() + ajeno.handshakesFallidos()).isEqualTo(3);
    }

    @Test
    void anHttpsUrlNeverDowngradesAgainstAPlainServer() throws Exception {
        var plano = ServidorHttpDePrueba.plano(ServidorHttpDePrueba.html(PAGINA));
        recursos.add(plano);
        assertThatThrownBy(() -> cliente(plano.puerto()).obtener(URL, LOCAL)).isInstanceOf(CapturaException.class);
        assertThat(plano.peticiones()).isEmpty();
    }

    // ------------------------------------------------------------------ 8-9: deadlines, limits, errors, closing

    /** A raw TCP server that drips a never-ending TLS record to the client and never reads. */
    ServerSocket goteoTls() throws IOException {
        ServerSocket s = new ServerSocket();
        s.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        recursos.add(s);
        Thread hilo = new Thread(() -> {
            try (Socket c = s.accept()) {
                OutputStream out = c.getOutputStream();
                out.write(new byte[]{0x16, 0x03, 0x03, 0x40, 0x00}); // handshake record of 16 KiB
                out.flush();
                for (int i = 0; i < 80; i++) { // one byte every 150 ms: each read is shorter than the read timeout
                    Thread.sleep(150);
                    out.write(0x02);
                    out.flush();
                }
            } catch (Exception ignored) {
                // closed by the client
            }
        }, "goteo-tls");
        hilo.setDaemon(true);
        hilo.start();
        return s;
    }

    @Test
    void aDrippingTlsHandshakeIsCutAtTheAbsoluteDeadline() throws Exception {
        ServerSocket goteo = goteoTls();
        long inicio = System.nanoTime();
        falla(() -> cliente(goteo.getLocalPort()).obtener(URL, LOCAL), Motivo.TRANSPORTE, "Tiempo de espera agotado");
        assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(LIMITES.total().plusSeconds(1));
    }

    @Test
    void silentAndDrippingHttpServersAreCutAtTheDeadlineAndClosed() throws Exception {
        var mudo = tls((p, out) -> Thread.sleep(6_000));
        var goteo = tls((p, out) -> {
            out.write("HTTP/1.1 200 OK\r\n".getBytes(StandardCharsets.ISO_8859_1));
            for (int i = 0; i < 60; i++) {
                out.write('X');
                out.flush();
                Thread.sleep(100);
            }
        });
        for (ServidorHttpDePrueba s : List.of(mudo, goteo)) {
            long inicio = System.nanoTime();
            falla(() -> cliente(s.puerto()).obtener(URL, LOCAL), Motivo.TRANSPORTE, "Tiempo de espera agotado");
            assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(LIMITES.total().plusSeconds(1));
        }
        Thread.sleep(400);
        assertThat(goteo.cerradasPorCliente()).isEqualTo(1);
    }

    @Test
    void limitsAndProtocolErrorsAreEnforcedOverRealSocketsAndEveryConnectionIsClosed() throws Exception {
        ByteArrayOutputStream bomba = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bomba)) {
            gz.write(new byte[4 * 1024 * 1024]);
        }
        byte[] bomb = bomba.toByteArray();
        String base = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n";
        Map<String, ServidorHttpDePrueba.Manejador> casos = new java.util.LinkedHashMap<>();
        casos.put("demasiado grande", ServidorHttpDePrueba.fija(base + "Content-Length: 104857600\r\n\r\n"));
        casos.put("descomprimido demasiado grande", (p, out) -> {
            out.write((base + "Content-Encoding: gzip\r\nContent-Length: " + bomb.length + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(bomb);
        });
        casos.put("ambiguo", ServidorHttpDePrueba.fija(base + "Content-Length: 3\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n"));
        casos.put("tamaño de trozo no válido", ServidorHttpDePrueba.fija(base + "Transfer-Encoding: chunked\r\n\r\n 5 \r\nhola!\r\n0\r\n\r\n"));
        casos.put("LF sin CR", ServidorHttpDePrueba.fija("HTTP/1.1 200 OK\nContent-Length: 0\r\n\r\n"));
        int total = 0;
        List<ServidorHttpDePrueba> servidores = new ArrayList<>();
        for (var caso : casos.entrySet()) {
            var s = tls(caso.getValue());
            servidores.add(s);
            falla(() -> cliente(s.puerto()).obtener(URL, LOCAL), Motivo.RESPUESTA_NO_VALIDA, caso.getKey().strip());
            total++;
        }
        // Bodies delimited by the server's close: too long, and closed before Content-Length.
        var hastaCierre = tls(ServidorHttpDePrueba.fija(base + "\r\n" + "a".repeat(70_000))).cerrandoTrasResponder();
        falla(() -> cliente(hastaCierre.puerto()).obtener(URL, LOCAL), Motivo.RESPUESTA_NO_VALIDA, "demasiado grande");
        var truncado = tls(ServidorHttpDePrueba.fija(base + "Content-Length: 99\r\n\r\ncorto")).cerrandoTrasResponder();
        falla(() -> cliente(truncado.puerto()).obtener(URL, LOCAL), Motivo.RESPUESTA_NO_VALIDA, "cuerpo truncado");
        // A stalled body (Content-Length never reached, connection kept open) hits the deadline instead.
        var estancado = tls(ServidorHttpDePrueba.fija(base + "Content-Length: 99\r\n\r\ncorto"));
        servidores.add(estancado);
        falla(() -> cliente(estancado.puerto()).obtener(URL, LOCAL), Motivo.TRANSPORTE, "Tiempo de espera agotado");
        total++;
        // A non-HTML 200 and an error status: returned without reading the body.
        var pdf = tls((p, out) -> {
            out.write((base.replace("text/html", "application/pdf") + "Content-Length: 900000\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            // no body is sent: a client that tried to read it would wait until the deadline
        });
        long inicio = System.nanoTime();
        RespuestaHttp r = cliente(pdf.puerto()).obtener(URL, LOCAL);
        assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofSeconds(1));
        assertThat(r.cuerpo()).isEmpty();
        servidores.add(pdf);
        total++;
        Thread.sleep(500);
        int cerradas = servidores.stream().mapToInt(ServidorHttpDePrueba::cerradasPorCliente).sum();
        int abiertas = servidores.stream().mapToInt(ServidorHttpDePrueba::abiertasTrasRespuesta).sum();
        assertThat(cerradas).as("conexiones cerradas por el cliente").isEqualTo(total);
        assertThat(abiertas).as("sockets del cliente sin cerrar").isZero();
    }

    @Test
    void repeatedFailuresLeaveNoOpenClientSocket() throws Exception {
        var servidor = tls(ServidorHttpDePrueba.fija("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 99\r\n\r\nx"));
        for (int i = 0; i < 25; i++) {
            assertThatThrownBy(() -> cliente(servidor.puerto()).obtener(URL, LOCAL)).isInstanceOf(CapturaException.class);
        }
        Thread.sleep(500);
        assertThat(servidor.conexiones()).isEqualTo(25);
        assertThat(servidor.cerradasPorCliente()).isEqualTo(25);
        assertThat(servidor.abiertasTrasRespuesta()).isZero();
    }

    @Test
    void concurrentTimeoutsCloseEveryClientSocket() throws Exception {
        var servidor = tls(ServidorHttpDePrueba.fija("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 99\r\n\r\nx"));
        var ejecutor = java.util.concurrent.Executors.newFixedThreadPool(4);
        var futuros = new ArrayList<java.util.concurrent.Future<?>>();
        try {
            for (int i = 0; i < 4; i++) {
                futuros.add(ejecutor.submit(() -> falla(() -> cliente(servidor.puerto()).obtener(URL, LOCAL),
                        Motivo.TRANSPORTE, "Tiempo de espera agotado")));
            }
            for (var futuro : futuros) {
                futuro.get(5, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            ejecutor.shutdownNow();
        }
        Thread.sleep(500);
        assertThat(servidor.conexiones()).isEqualTo(4);
        assertThat(servidor.cerradasPorCliente()).isEqualTo(4);
        assertThat(servidor.abiertasTrasRespuesta()).isZero();
    }

    // ------------------------------------------------------------------ 10: proxies

    @Test
    void systemProxySettingsDoNotRedirectTheAuthorizedConnection() throws Exception {
        var servidor = tls(ServidorHttpDePrueba.html(PAGINA));
        ProxySelector anterior = ProxySelector.getDefault();
        Map<String, String> previas = new HashMap<>();
        AtomicInteger consultas = new AtomicInteger();
        Map<String, String> señuelos = Map.of("http.proxyHost", "203.0.113.81", "http.proxyPort", "3128",
                "https.proxyHost", "203.0.113.81", "https.proxyPort", "3128",
                "socksProxyHost", "203.0.113.81", "socksProxyPort", "1080", "java.net.useSystemProxies", "true");
        try {
            señuelos.forEach((k, v) -> previas.put(k, System.setProperty(k, v)));
            ProxySelector.setDefault(new ProxySelector() {
                @Override
                public List<Proxy> select(URI uri) {
                    consultas.incrementAndGet();
                    return List.of(new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("203.0.113.81", 3128)));
                }

                @Override
                public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                }
            });
            var observado = MonitorDeConexiones.durante(() -> cliente(servidor.puerto()).obtener(URL, LOCAL));
            assertThat(observado.error()).isNull();
            assertThat(observado.resultado().estado()).isEqualTo(200);
            assertThat(observado.conexiones()).extracting(MonitorDeConexiones.Evento::host).containsOnly("127.0.0.1");
            assertThat(consultas.get()).isZero();
            assertThat(servidor.peticiones()).hasSize(1);
        } finally {
            ProxySelector.setDefault(anterior);
            previas.forEach((k, v) -> {
                if (v == null) {
                    System.clearProperty(k);
                } else {
                    System.setProperty(k, v);
                }
            });
        }
    }

    // ------------------------------------------------------------------ product flow keeps rejecting loopback

    @Test
    void theProductFlowStillRejectsLoopbackEvenWithTheRealConnector() throws Exception {
        var servidor = tls(ServidorHttpDePrueba.html(PAGINA));
        var resolver = new TransporteHttpRealTest.ResolverFalso().con(HOST, LOCAL);
        var transporte = new TransporteHttpReal(resolver, cliente(servidor.puerto()), Clock.systemUTC());
        falla(() -> transporte.capturar(URL), Motivo.URL_RECHAZADA, "ADDRESS_FORBIDDEN");
        // localhost cannot even become a DestinationUrl (rule 17), so no transport ever receives it.
        assertThat(DestinationUrl.parse("https://localhost/")).isInstanceOf(UrlParseResult.Rejected.class);
        assertThat(((UrlParseResult.Rejected) DestinationUrl.parse("https://localhost/")).code())
                .isEqualTo(com.smartaudits.v2.acquisition.destination.RejectionCode.HOST_FORBIDDEN);
        assertThat(servidor.conexiones()).isZero();
    }

    @Test
    void theDialledPortSeamCannotBeUsedThroughTheTransport() throws Exception {
        var servidor = tls(ServidorHttpDePrueba.html(PAGINA));
        // Even with an approval for a public IP routed to loopback, a mapped port is a mismatch.
        var resolver = new TransporteHttpRealTest.ResolverFalso().con(HOST, TransporteHttpRealTest.PUBLICA);
        var conector = new ConectorDePrueba().ruta(TransporteHttpRealTest.PUBLICA, servidor.puerto());
        var mapeado = new ClienteHttpFijado(conector, confianza.getSocketFactory(), LIMITES, p -> 8443);
        var transporte = new TransporteHttpReal(resolver, mapeado, Clock.systemUTC());
        falla(() -> transporte.capturar(URL), Motivo.TRANSPORTE, "no corresponde a la evaluación aprobada");
    }

    // ------------------------------------------------------------------ 7: DNS uses only the configured server

    @Test
    void theDirectResolverTalksOnlyToTheConfiguredServerAndResolvesNothingElse() throws Exception {
        var dns = new com.smartaudits.v2.acquisition.capture.dns.ServidorDnsDePruebaAcceso(true);
        recursos.add(dns);
        var resolvedor = new ResolvedorDnsDirecto(new byte[]{127, 0, 0, 1}, dns.puerto(), Duration.ofSeconds(1));
        var politica = com.smartaudits.v2.acquisition.destination.DestinationPolicy.using(resolvedor);

        var observado = MonitorDeConexiones.durante(() -> politica.evaluate("https://www.ejemplo.es/"));

        assertThat(observado.error()).isNull();
        assertThat(observado.resultado()).isInstanceOf(com.smartaudits.v2.acquisition.destination.DestinationDecision.Approved.class);
        assertThat(observado.resoluciones()).isEmpty();
        assertThat(observado.conexiones()).extracting(MonitorDeConexiones.Evento::host, MonitorDeConexiones.Evento::puerto)
                .containsOnly(org.assertj.core.groups.Tuple.tuple("127.0.0.1", dns.puerto()));
        assertThat(dns.consultasTcp()).isEqualTo(1); // the truncated A answer was repeated over TCP to the same server
    }
}
