package com.smartaudits.v2.acquisition.capture.http;

import com.smartaudits.v2.acquisition.capture.CapturaException;
import com.smartaudits.v2.acquisition.capture.CapturaException.Motivo;
import com.smartaudits.v2.acquisition.capture.CapturaHtml;
import com.smartaudits.v2.acquisition.capture.ProcedenciaCaptura;
import com.smartaudits.v2.acquisition.capture.TransporteHttpReal;
import com.smartaudits.v2.acquisition.destination.DestinationUrl;
import com.smartaudits.v2.acquisition.destination.DnsAnswer;
import com.smartaudits.v2.acquisition.destination.DnsAnswer.FamilyAnswer;
import com.smartaudits.v2.acquisition.destination.DnsAnswer.Status;
import com.smartaudits.v2.acquisition.destination.HostResolver;
import com.smartaudits.v2.acquisition.destination.IpAddress;
import com.smartaudits.v2.acquisition.destination.UrlParseResult;
import com.smartaudits.v2.acquisition.destination.ValidatedHost;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real transport code over loopback doubles: a fake resolver, a connector that routes approved
 * (public) IPs to a 127.0.0.1 test server and declares that peer, and real JSSE TLS with a throwaway
 * test CA. No external network. These tests prove the code paths, not the physical system:
 * the peer observed by the OS, real DNS and real egress remain physical validations (QH/QD, R-EGR).
 */
class TransporteHttpRealTest {
    static final String URL = "https://www.ejemplo.es/legal/privacidad?x=1%2F";
    static final String PAGINA = "<html><body><h1>Política de Privacidad</h1><p>Responsable: Ejemplo S.L.</p></body></html>";
    static final IpAddress PUBLICA = ip("8.8.8.8");
    static final IpAddress OTRA_PUBLICA = ip("1.1.1.1");
    static final Clock RELOJ = Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneOffset.UTC);
    static final LimitesHttp RAPIDOS = new LimitesHttp(Duration.ofSeconds(2), Duration.ofMillis(700), Duration.ofMillis(1500),
            32 * 1024, 100, 64 * 1024, 128 * 1024, 3);

    static PkiDePrueba.Credencial ca;
    static PkiDePrueba.Credencial caAjena;
    static SSLContext cliente;

    final List<ServidorHttpDePrueba> servidores = new ArrayList<>();
    final ResolverFalso resolver = new ResolverFalso();
    final ConectorDePrueba conector = new ConectorDePrueba();

    @BeforeAll
    static void pki() throws Exception {
        ca = PkiDePrueba.ca("CA de pruebas SmartAudits");
        caAjena = PkiDePrueba.ca("CA ajena");
        cliente = PkiDePrueba.cliente(ca);
    }

    @AfterEach
    void cerrar() throws IOException {
        for (ServidorHttpDePrueba s : servidores) {
            s.close();
        }
    }

    // ------------------------------------------------------------------ helpers

    static IpAddress ip(String texto) {
        String[] p = texto.split("\\.");
        return IpAddress.ipv4(new byte[]{(byte) Integer.parseInt(p[0]), (byte) Integer.parseInt(p[1]),
                (byte) Integer.parseInt(p[2]), (byte) Integer.parseInt(p[3])});
    }

    static DestinationUrl url(String texto) {
        return ((UrlParseResult.Parsed) DestinationUrl.parse(texto)).url();
    }

    static final class ResolverFalso implements HostResolver {
        final Map<String, List<byte[]>> direcciones = new HashMap<>();
        final List<String> consultas = new ArrayList<>();

        ResolverFalso con(String host, IpAddress... ips) {
            List<byte[]> lista = new ArrayList<>();
            for (IpAddress i : ips) {
                lista.add(i.bytes());
            }
            direcciones.put(host, lista);
            return this;
        }

        @Override
        public DnsAnswer resolve(ValidatedHost host) {
            consultas.add(host.name());
            List<byte[]> lista = direcciones.get(host.name());
            if (lista == null) {
                return DnsAnswer.of(FamilyAnswer.of(Status.NXDOMAIN, List.of(), List.of()), FamilyAnswer.of(Status.NXDOMAIN, List.of(), List.of()));
            }
            return DnsAnswer.of(FamilyAnswer.addresses(lista), FamilyAnswer.nodata());
        }
    }

    ServidorHttpDePrueba tls(String host, ServidorHttpDePrueba.Manejador m) throws Exception {
        var hoja = PkiDePrueba.hoja(ca, host);
        var s = ServidorHttpDePrueba.tls(PkiDePrueba.servidor(hoja, ca), m);
        servidores.add(s);
        return s;
    }

    ServidorHttpDePrueba plano(ServidorHttpDePrueba.Manejador m) throws IOException {
        var s = ServidorHttpDePrueba.plano(m);
        servidores.add(s);
        return s;
    }

    TransporteHttpReal transporte(LimitesHttp limites) {
        return new TransporteHttpReal(resolver, new ClienteHttpFijado(conector, cliente.getSocketFactory(), limites), RELOJ);
    }

    CapturaException falla(String texto, LimitesHttp limites, Motivo motivo, String mensaje) {
        try {
            transporte(limites).capturar(url(texto));
        } catch (CapturaException e) {
            assertThat(e.motivo()).as(e.getMessage()).isEqualTo(motivo);
            assertThat(e.getMessage()).contains(mensaje);
            return e;
        }
        throw new AssertionError("se esperaba CapturaException " + motivo);
    }

    // ------------------------------------------------------------------ pinning, policy, TLS

    @Test
    void connectsOnlyToTheApprovedAddressWithSniHostAndRawTarget() throws Exception {
        var servidor = tls("www.ejemplo.es", ServidorHttpDePrueba.html(PAGINA));
        resolver.con("www.ejemplo.es", PUBLICA);
        conector.ruta(PUBLICA, servidor.puerto());

        CapturaHtml captura = transporte(RAPIDOS).capturar(url(URL));

        assertThat(conector.llamadas()).containsExactly("8.8.8.8:443");
        assertThat(resolver.consultas).containsExactly("www.ejemplo.es");
        assertThat(servidor.sniVistos()).containsExactly("www.ejemplo.es");
        var peticion = servidor.peticiones().get(0);
        assertThat(peticion.linea()).isEqualTo("GET /legal/privacidad?x=1%2F HTTP/1.1");
        assertThat(peticion.cabeceras()).containsEntry("host", "www.ejemplo.es").containsEntry("connection", "close")
                .doesNotContainKeys("proxy-authorization", "cookie", "proxy-connection");
        assertThat(captura.urlSolicitada().canonicalForm()).isEqualTo(URL);
        assertThat(captura.urlFinal().canonicalForm()).isEqualTo(URL);
        assertThat(captura.estadoHttp()).isEqualTo(200);
        assertThat(captura.tipoContenido()).isEqualTo("text/html; charset=utf-8");
        assertThat(captura.html()).isEqualTo(PAGINA);
        assertThat(captura.fechaCaptura()).isEqualTo(RELOJ.instant());
        // The peer was declared by the test connector, not observed on the socket: never REAL.
        assertThat(captura.procedencia().esReal()).isFalse();
        assertThat(captura.procedencia().origen()).isEqualTo(ProcedenciaCaptura.Origen.SIMULADA_LOCAL);
    }

    @Test
    void privateAndMixedAnswersAreRejectedBeforeAnyConnection() {
        resolver.con("privada.ejemplo.es", ip("10.0.0.1")).con("mixta.ejemplo.es", PUBLICA, ip("192.168.1.10"));
        falla("https://privada.ejemplo.es/", RAPIDOS, Motivo.URL_RECHAZADA, "ADDRESS_FORBIDDEN");
        falla("https://mixta.ejemplo.es/", RAPIDOS, Motivo.URL_RECHAZADA, "ADDRESS_MIXED");
        falla("https://inexistente.ejemplo.es/", RAPIDOS, Motivo.URL_RECHAZADA, "DNS_EMPTY");
        assertThat(conector.llamadas()).isEmpty();
    }

    @Test
    void aConnectionThatReachedAnotherPeerIsRejectedBeforeAnyRequest() throws Exception {
        var servidor = tls("www.ejemplo.es", ServidorHttpDePrueba.html(PAGINA));
        resolver.con("www.ejemplo.es", PUBLICA);
        conector.ruta(PUBLICA, servidor.puerto()).declararOtroPeer(ip("9.9.9.9"));

        falla(URL, RAPIDOS, Motivo.TRANSPORTE, "no llegó a la dirección aprobada");
        Thread.sleep(100);
        assertThat(servidor.peticiones()).isEmpty();
    }

    @Test
    void aCertificateForAnotherDomainOrFromAnUntrustedCaFailsWithoutSendingTheRequest() throws Exception {
        var otroDominio = tls("otro.ejemplo.es", ServidorHttpDePrueba.html(PAGINA));
        var hojaAjena = PkiDePrueba.hoja(caAjena, "www.ejemplo.es");
        var caNoConfiable = ServidorHttpDePrueba.tls(PkiDePrueba.servidor(hojaAjena, caAjena), ServidorHttpDePrueba.html(PAGINA));
        servidores.add(caNoConfiable);
        resolver.con("www.ejemplo.es", PUBLICA).con("b.ejemplo.es", OTRA_PUBLICA);
        conector.ruta(PUBLICA, otroDominio.puerto()).ruta(OTRA_PUBLICA, caNoConfiable.puerto());

        falla(URL, RAPIDOS, Motivo.TRANSPORTE, "identidad TLS");
        falla("https://b.ejemplo.es/", RAPIDOS, Motivo.TRANSPORTE, "identidad TLS");
        Thread.sleep(100);
        assertThat(otroDominio.peticiones()).isEmpty();
        assertThat(caNoConfiable.peticiones()).isEmpty();
        // JSSE itself aborted both handshakes (endpoint identification and chain), before the extra check.
        assertThat(otroDominio.handshakesFallidos()).isEqualTo(1);
        assertThat(caNoConfiable.handshakesFallidos()).isEqualTo(1);
    }

    @Test
    void anExpiredCertificateFails() throws Exception {
        var caducada = PkiDePrueba.hoja(ca, "www.ejemplo.es", Instant.now().minus(Duration.ofDays(3)),
                Instant.now().minus(Duration.ofDays(1)), "www.ejemplo.es");
        var servidor = ServidorHttpDePrueba.tls(PkiDePrueba.servidor(caducada, ca), ServidorHttpDePrueba.html(PAGINA));
        servidores.add(servidor);
        resolver.con("www.ejemplo.es", PUBLICA);
        conector.ruta(PUBLICA, servidor.puerto());
        falla(URL, RAPIDOS, Motivo.TRANSPORTE, "identidad TLS");
        assertThat(servidor.peticiones()).isEmpty();
    }

    @Test
    void plainHttpSendsNoTlsAndCannotBeUsedToDowngradeHttps() throws Exception {
        var plano = plano(ServidorHttpDePrueba.html(PAGINA));
        resolver.con("www.ejemplo.es", PUBLICA);
        conector.ruta(PUBLICA, plano.puerto());
        CapturaHtml captura = transporte(RAPIDOS).capturar(url("http://www.ejemplo.es/"));
        assertThat(captura.html()).isEqualTo(PAGINA);
        assertThat(conector.llamadas()).containsExactly("8.8.8.8:80");

        // https towards a plain server never falls back to http.
        var conector443 = new ConectorDePrueba().ruta(PUBLICA, plano.puerto());
        var t = new TransporteHttpReal(resolver, new ClienteHttpFijado(conector443, cliente.getSocketFactory(), RAPIDOS), RELOJ);
        assertThatThrownBy(() -> t.capturar(url(URL))).isInstanceOf(CapturaException.class);
        assertThat(conector443.llamadas()).containsExactly("8.8.8.8:443");
    }

    // ------------------------------------------------------------------ redirects

    static ServidorHttpDePrueba.Manejador redirige(String location) {
        return ServidorHttpDePrueba.fija("HTTP/1.1 302 Found\r\nLocation: " + location + "\r\nContent-Length: 0\r\n\r\n");
    }

    @Test
    void aRedirectIsEvaluatedAnewAndConnectsOnlyToTheNewApproval() throws Exception {
        var primero = tls("www.ejemplo.es", redirige("https://b.ejemplo.es/destino"));
        var segundo = tls("b.ejemplo.es", ServidorHttpDePrueba.html(PAGINA));
        resolver.con("www.ejemplo.es", PUBLICA).con("b.ejemplo.es", OTRA_PUBLICA);
        conector.ruta(PUBLICA, primero.puerto()).ruta(OTRA_PUBLICA, segundo.puerto());

        CapturaHtml captura = transporte(RAPIDOS).capturar(url(URL));

        assertThat(resolver.consultas).containsExactly("www.ejemplo.es", "b.ejemplo.es");
        assertThat(conector.llamadas()).containsExactly("8.8.8.8:443", "1.1.1.1:443");
        assertThat(segundo.sniVistos()).containsExactly("b.ejemplo.es");
        assertThat(segundo.peticiones().get(0).cabeceras()).containsEntry("host", "b.ejemplo.es");
        assertThat(captura.urlSolicitada().canonicalForm()).isEqualTo(URL);
        assertThat(captura.urlFinal().canonicalForm()).isEqualTo("https://b.ejemplo.es/destino");
    }

    @Test
    void redirectsToForbiddenOrUnapprovableDestinationsNeverConnect() throws Exception {
        for (String[] caso : new String[][]{
                {"https://10.0.0.1/admin", "IP_LITERAL"},
                {"https://interna.ejemplo.es/", "ADDRESS_FORBIDDEN"},
                {"https://sin-dns.ejemplo.es/", "DNS_EMPTY"},
                {"http://www.ejemplo.es/", "HTTPS a HTTP"},
                {"https://localhost/", "HOST_FORBIDDEN"},
                {"file:///etc/passwd", "SCHEME"}}) {
            var c = new ConectorDePrueba();
            var r = new ResolverFalso().con("www.ejemplo.es", PUBLICA).con("interna.ejemplo.es", ip("10.1.2.3"));
            var servidor = tls("www.ejemplo.es", redirige(caso[0]));
            c.ruta(PUBLICA, servidor.puerto());
            var t = new TransporteHttpReal(r, new ClienteHttpFijado(c, cliente.getSocketFactory(), RAPIDOS), RELOJ);
            assertThatThrownBy(() -> t.capturar(url(URL))).as(caso[0]).isInstanceOf(CapturaException.class)
                    .hasMessageContaining(caso[1])
                    .satisfies(e -> assertThat(((CapturaException) e).motivo()).isEqualTo(Motivo.URL_RECHAZADA));
            // Only the first, approved connection; the earlier approval never authorizes the new target.
            assertThat(c.llamadas()).as(caso[0]).containsExactly("8.8.8.8:443");
        }
    }

    @Test
    void redirectLoopsAndTooManyHopsAreRejected() throws Exception {
        var a = tls("www.ejemplo.es", (p, out) -> out.write(("HTTP/1.1 301 Moved\r\nLocation: "
                + (p.linea().contains("/a") ? "/b" : "/a") + "\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1)));
        resolver.con("www.ejemplo.es", PUBLICA);
        conector.ruta(PUBLICA, a.puerto());
        falla("https://www.ejemplo.es/a", RAPIDOS, Motivo.RESPUESTA_NO_VALIDA, "Bucle de redirecciones");

        AtomicInteger n = new AtomicInteger();
        var cadena = tls("b.ejemplo.es", (p, out) -> out.write(("HTTP/1.1 307 Temp\r\nLocation: /paso" + n.incrementAndGet()
                + "\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1)));
        resolver.con("b.ejemplo.es", OTRA_PUBLICA);
        conector.ruta(OTRA_PUBLICA, cadena.puerto());
        falla("https://b.ejemplo.es/inicio", RAPIDOS, Motivo.RESPUESTA_NO_VALIDA, "Demasiadas redirecciones");
        assertThat(cadena.conexiones()).isEqualTo(RAPIDOS.maxRedirecciones() + 1);
    }

    // ------------------------------------------------------------------ limits, framing, content

    ServidorHttpDePrueba servido(ServidorHttpDePrueba.Manejador m) throws Exception {
        var s = tls("www.ejemplo.es", m);
        resolver.con("www.ejemplo.es", PUBLICA);
        conector.ruta(PUBLICA, s.puerto());
        return s;
    }

    @Test
    void oversizedResponsesAreRejectedWhateverTheFraming() throws Exception {
        servido(ServidorHttpDePrueba.fija("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 10485760\r\n\r\n"));
        falla(URL, RAPIDOS, Motivo.RESPUESTA_NO_VALIDA, "demasiado grande");
        cerrar();
        servidores.clear();

        String trozo = Integer.toHexString(40_000) + "\r\n" + "a".repeat(40_000) + "\r\n";
        servido(ServidorHttpDePrueba.fija("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nTransfer-Encoding: chunked\r\n\r\n"
                + trozo + trozo + "0\r\n\r\n"));
        falla(URL, RAPIDOS, Motivo.RESPUESTA_NO_VALIDA, "demasiado grande");
        cerrar();
        servidores.clear();

        servido(ServidorHttpDePrueba.fija("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n" + "a".repeat(70_000)));
        falla(URL, RAPIDOS, Motivo.RESPUESTA_NO_VALIDA, "demasiado grande");
    }

    @Test
    void aDecompressionBombIsStoppedAtTheDecompressedLimit() throws Exception {
        ByteArrayOutputStream comprimido = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(comprimido)) {
            gzip.write(new byte[5 * 1024 * 1024]); // 5 MiB of zeros: a few KiB compressed
        }
        byte[] bomba = comprimido.toByteArray();
        assertThat(bomba.length).isLessThan(RAPIDOS.maxCuerpoTransferido() > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) RAPIDOS.maxCuerpoTransferido());
        servido((p, out) -> {
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Encoding: gzip\r\nContent-Length: " + bomba.length
                    + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(bomba);
        });
        falla(URL, RAPIDOS, Motivo.RESPUESTA_NO_VALIDA, "descomprimido demasiado grande");
    }

    @Test
    void aSmallGzipPageIsDecompressed() throws Exception {
        ByteArrayOutputStream comprimido = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(comprimido)) {
            gzip.write(PAGINA.getBytes(StandardCharsets.UTF_8));
        }
        byte[] cuerpo = comprimido.toByteArray();
        servido((p, out) -> {
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Encoding: gzip\r\nContent-Length: "
                    + cuerpo.length + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(cuerpo);
        });
        assertThat(transporte(RAPIDOS).capturar(url(URL)).html()).isEqualTo(PAGINA);
    }

    @Test
    void silentAndDrippingServersHitTheDeadline() throws Exception {
        servido((p, out) -> Thread.sleep(5_000));
        long inicio = System.nanoTime();
        falla(URL, RAPIDOS, Motivo.TRANSPORTE, "Tiempo de espera agotado");
        assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofSeconds(3));
        cerrar();
        servidores.clear();

        servido((p, out) -> {
            out.write("HTTP/1.1 200 OK\r\n".getBytes(StandardCharsets.ISO_8859_1));
            for (int i = 0; i < 100; i++) { // one header byte every 100 ms: never ends before the deadline
                out.write('X');
                out.flush();
                Thread.sleep(100);
            }
        });
        inicio = System.nanoTime();
        falla(URL, RAPIDOS, Motivo.TRANSPORTE, "Tiempo de espera agotado");
        assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void nonHtmlContentAndErrorStatusesAreNotAccepted() throws Exception {
        servido((p, out) -> {
            out.write("HTTP/1.1 200 OK\r\nContent-Type: application/pdf\r\nContent-Length: 1000000\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            Thread.sleep(5_000); // the body is never read
        });
        long inicio = System.nanoTime();
        falla(URL, RAPIDOS, Motivo.RESPUESTA_NO_VALIDA, "no es HTML");
        assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofSeconds(1));
        cerrar();
        servidores.clear();
        servido(ServidorHttpDePrueba.fija("HTTP/1.1 404 Not Found\r\nContent-Type: text/html\r\nContent-Length: 0\r\n\r\n"));
        falla(URL, RAPIDOS, Motivo.RESPUESTA_NO_VALIDA, "estado HTTP 404");
    }

    @Test
    void ambiguousOrTruncatedFramingIsRejected() throws Exception {
        String base = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n";
        for (String respuesta : List.of(
                base + "Content-Length: 5\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n",
                base + "Content-Length: 5\r\nContent-Length: 6\r\n\r\nhola!",
                base + "Content-Length: -1\r\n\r\n",
                base + "Transfer-Encoding: gzip, chunked\r\n\r\n",
                base + "Content-Length: 50\r\n\r\ncorto",
                base + "Transfer-Encoding: chunked\r\n\r\n10\r\nincompleto",
                base + "Content-Encoding: br\r\nContent-Length: 4\r\n\r\nabcd",
                "HTTP/1.1 200 OK\nContent-Type: text/html\r\nContent-Length: 0\r\n\r\n",
                base + " plegada: si\r\nContent-Length: 0\r\n\r\n",
                "HTTP/1.1 100 Continue\r\n\r\n",
                "HTTP/2 200\r\n\r\n")) {
            var s = servido(ServidorHttpDePrueba.fija(respuesta)).cerrandoTrasResponder(); // truncations need the server's EOF
            assertThatThrownBy(() -> transporte(RAPIDOS).capturar(url(URL))).as(respuesta)
                    .isInstanceOf(CapturaException.class)
                    .satisfies(e -> assertThat(((CapturaException) e).motivo()).isEqualTo(Motivo.RESPUESTA_NO_VALIDA));
            s.close();
        }
    }

    @Test
    void charsetsAreDecodedAndUnknownOnesRejected() throws Exception {
        byte[] latin = "<p>Política</p>".getBytes(StandardCharsets.ISO_8859_1);
        servido((p, out) -> {
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=ISO-8859-1\r\nContent-Length: " + latin.length
                    + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(latin);
        });
        assertThat(transporte(RAPIDOS).capturar(url(URL)).html()).isEqualTo("<p>Política</p>");
        cerrar();
        servidores.clear();
        servido(ServidorHttpDePrueba.fija("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=x-raro\r\nContent-Length: 2\r\n\r\nhi"));
        falla(URL, RAPIDOS, Motivo.RESPUESTA_NO_VALIDA, "Codificación");
    }

    // ------------------------------------------------------------------ observed peer and proxies

    @Test
    void aRealProvenanceNeedsTheSocketsOwnPeerToMatchTheApproval() throws Exception {
        var servidor = tls("www.ejemplo.es", ServidorHttpDePrueba.html(PAGINA));
        resolver.con("www.ejemplo.es", PUBLICA);
        // The connector reads the peer from the socket itself (as production does): it is 127.0.0.1,
        // which is not the approved address, so the capture fails; it can never be faked as REAL.
        Conector observado = (ip, puerto, timeout) -> {
            Socket s = new Socket(Proxy.NO_PROXY);
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), servidor.puerto()), 2000);
            return Conexion.delSocket(s);
        };
        var t = new TransporteHttpReal(resolver, new ClienteHttpFijado(observado, cliente.getSocketFactory(), RAPIDOS), RELOJ);
        assertThatThrownBy(() -> t.capturar(url(URL))).isInstanceOf(CapturaException.class)
                .hasMessageContaining("no llegó a la dirección aprobada");
        assertThat(servidor.peticiones()).isEmpty();
    }

    @Test
    void theDirectConnectorIgnoresSystemProxiesAndReportsTheObservedPeer() throws Exception {
        var servidor = plano(ServidorHttpDePrueba.html(PAGINA));
        ProxySelector anterior = ProxySelector.getDefault();
        Map<String, String> propiedades = Map.of("http.proxyHost", "203.0.113.81", "http.proxyPort", "3128",
                "https.proxyHost", "203.0.113.81", "https.proxyPort", "3128", "socksProxyHost", "203.0.113.81");
        Map<String, String> previas = new HashMap<>();
        AtomicInteger consultasSelector = new AtomicInteger();
        try {
            propiedades.forEach((k, v) -> previas.put(k, System.setProperty(k, v)));
            ProxySelector.setDefault(new ProxySelector() {
                @Override
                public List<Proxy> select(URI uri) {
                    consultasSelector.incrementAndGet();
                    return List.of(new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("203.0.113.81", 3128)));
                }

                @Override
                public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                }
            });
            // Loopback here only to exercise the connector without external traffic.
            Conexion conexion = new ConectorDirecto().conectar(IpAddress.ipv4(new byte[]{127, 0, 0, 1}), servidor.puerto(), Duration.ofSeconds(2));
            try (Socket s = conexion.socket()) {
                assertThat(conexion.peerObservado()).isTrue();
                assertThat(conexion.peer().getAddress().getAddress()).containsExactly(127, 0, 0, 1);
                assertThat(conexion.peer().getPort()).isEqualTo(servidor.puerto());
                s.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                s.getOutputStream().flush();
                assertThat(s.getInputStream().read()).isNotNegative();
            }
            assertThat(consultasSelector.get()).isZero();
            assertThat(servidor.conexiones()).isEqualTo(1);
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

    @Test
    void identityMatchingIsStrict() throws Exception {
        assertThat(IdentidadTls.cubre("www.ejemplo.es", "www.ejemplo.es")).isTrue();
        assertThat(IdentidadTls.cubre("*.ejemplo.es", "www.ejemplo.es")).isTrue();
        assertThat(IdentidadTls.cubre("*.ejemplo.es", "a.b.ejemplo.es")).isFalse();
        assertThat(IdentidadTls.cubre("*.ejemplo.es", "ejemplo.es")).isFalse();
        assertThat(IdentidadTls.cubre("*.es", "ejemplo.es")).isFalse();
        assertThat(IdentidadTls.cubre("w*.ejemplo.es", "www.ejemplo.es")).isFalse();
        assertThat(IdentidadTls.cubre("www.ejemplo.es.", "www.ejemplo.es")).isFalse();
        assertThat(IdentidadTls.cubre("www.ejemplo.es", "www.ejemplo.es.evil.example")).isFalse();
        var soloCn = PkiDePrueba.hoja(ca, "www.ejemplo.es", Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600));
        assertThat(IdentidadTls.coincide(soloCn.certificado(), "www.ejemplo.es")).isFalse(); // no CN fallback
        assertThat(IdentidadTls.coincide(PkiDePrueba.hoja(ca, "www.ejemplo.es").certificado(), "WWW.Ejemplo.ES")).isTrue();
    }
}
