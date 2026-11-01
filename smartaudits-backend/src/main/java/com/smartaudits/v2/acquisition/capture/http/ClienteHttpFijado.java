package com.smartaudits.v2.acquisition.capture.http;

import com.smartaudits.v2.acquisition.capture.CapturaException;
import com.smartaudits.v2.acquisition.capture.CapturaException.Motivo;
import com.smartaudits.v2.acquisition.destination.DestinationUrl;
import com.smartaudits.v2.acquisition.destination.IpAddress;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.IntUnaryOperator;

/**
 * One GET over one connection to one approved address, with nothing automatic: no DNS (the
 * connector receives the IP), no proxy, no redirects, retries, cookies, cache or connection reuse.
 * For https the TLS identity is the authorized host name, never the IP: SNI and endpoint
 * identification use the host, JSSE validates the chain with the given trust (the system trust
 * store in production) and {@link IdentidadTls} checks the certificate again independently.
 */
public final class ClienteHttpFijado {

    static final String USER_AGENT = "SmartAudits/2.0 (auditoria legal; +https://smartaudits.es)";
    private static final List<String> PROTOCOLOS = List.of("TLSv1.3", "TLSv1.2");
    private static final ScheduledExecutorService VIGILANTE = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread hilo = new Thread(r, "smartaudits-plazo-http");
        hilo.setDaemon(true);
        return hilo;
    });

    private final Conector conector;
    private final SSLSocketFactory fabricaTls;
    private final LimitesHttp limites;
    private final IntUnaryOperator puertoTcp;

    public ClienteHttpFijado(Conector conector, SSLSocketFactory fabricaTls, LimitesHttp limites) {
        this(conector, fabricaTls, limites, IntUnaryOperator.identity());
    }

    /**
     * Package-private seam for loopback tests of this layer only: the TCP port actually dialled for
     * the URL's effective port. The observation keeps the dialled port, so TransporteHttpReal (which
     * requires it to equal the URL port) rejects any mapping: it cannot be used through the product flow.
     */
    ClienteHttpFijado(Conector conector, SSLSocketFactory fabricaTls, LimitesHttp limites, IntUnaryOperator puertoTcp) {
        this.conector = Objects.requireNonNull(conector, "conector");
        this.fabricaTls = Objects.requireNonNull(fabricaTls, "fabricaTls");
        this.limites = Objects.requireNonNull(limites, "limites");
        this.puertoTcp = Objects.requireNonNull(puertoTcp, "puertoTcp");
    }

    public LimitesHttp limites() {
        return limites;
    }

    public RespuestaHttp obtener(DestinationUrl url, IpAddress ip) {
        long limite = System.nanoTime() + limites.total().toNanos();
        int puerto = puertoTcp.applyAsInt(url.port());
        Conexion conexion;
        try {
            // The watchdog starts only after connect: its timeout must already fit the total budget.
            // Socket.connect interprets zero milliseconds as unlimited, so keep a positive value.
            long conexionMs = Math.max(1, Math.min(limites.conexion().toMillis(), restante(limite)));
            conexion = conector.conectar(ip, puerto, Duration.ofMillis(conexionMs));
        } catch (IOException e) {
            throw new CapturaException(Motivo.TRANSPORTE, "No se pudo conectar con la dirección aprobada", e);
        }
        Socket socket = conexion.socket();
        // Per-read timeouts alone let a server drip a TLS handshake (or anything) forever: at the
        // absolute deadline the underlying socket is closed, which aborts any blocked read or write.
        ScheduledFuture<?> alarma = VIGILANTE.schedule(() -> cerrar(conexion.socket()),
                Math.max(1, limite - System.nanoTime()), TimeUnit.NANOSECONDS);
        try {
            byte[] bytesPeer = conexion.peer().getAddress().getAddress();
            IpAddress peer = bytesPeer.length == 4 ? IpAddress.ipv4(bytesPeer) : IpAddress.ipv6(bytesPeer);
            if (!peer.equals(ip) || conexion.peer().getPort() != puerto) {
                throw new CapturaException(Motivo.TRANSPORTE, "La conexión no llegó a la dirección aprobada");
            }
            String host = url.host().name();
            boolean tls = url.scheme().equals("https");
            String sni = null;
            String identidad = null;
            String protocolo = null;
            if (tls) {
                SSLSocket ssl = (SSLSocket) fabricaTls.createSocket(socket, host, url.port(), true);
                socket = ssl;
                SSLParameters parametros = ssl.getSSLParameters();
                parametros.setEndpointIdentificationAlgorithm("HTTPS");
                parametros.setServerNames(List.of(new SNIHostName(host)));
                parametros.setProtocols(PROTOCOLOS.stream().filter(p -> Arrays.asList(ssl.getSupportedProtocols()).contains(p))
                        .toArray(String[]::new));
                ssl.setSSLParameters(parametros);
                ssl.setSoTimeout(restante(limite));
                ssl.startHandshake();
                var sesion = ssl.getSession();
                if (!(sesion.getPeerCertificates()[0] instanceof X509Certificate hoja) || !IdentidadTls.coincide(hoja, host)) {
                    throw new CapturaException(Motivo.TRANSPORTE, "La identidad TLS del servidor no es válida para el host autorizado");
                }
                sni = ((SNIHostName) ssl.getSSLParameters().getServerNames().get(0)).getAsciiName();
                identidad = host;
                protocolo = sesion.getProtocol();
            }
            enviar(socket, url, host);
            LectorHttp lector = new LectorHttp(new BufferedInputStream(new EntradaConPlazo(socket, limite, limites.lectura()), 8192), limites);
            LectorHttp.Cabecera cabecera = lector.leerCabecera();
            byte[] cuerpo = cabecera.estado() == 200 && esHtml(cabecera) ? lector.leerCuerpo(cabecera) : new byte[0];
            return new RespuestaHttp(cabecera.estado(), cabecera.cabeceras(), cuerpo, new ObservacionConexion(url, peer,
                    conexion.peer().getPort(), conexion.peerObservado(), tls, sni, identidad, protocolo, host));
        } catch (IOException e) {
            if (e instanceof SocketTimeoutException || System.nanoTime() - limite >= 0) {
                throw new CapturaException(Motivo.TRANSPORTE, "Tiempo de espera agotado", e);
            }
            if (e instanceof ErrorRespuesta) {
                throw new CapturaException(Motivo.RESPUESTA_NO_VALIDA, "Respuesta HTTP no válida: " + e.getMessage(), e);
            }
            if (e instanceof SSLException) {
                throw new CapturaException(Motivo.TRANSPORTE, "La identidad TLS del servidor no es válida para el host autorizado", e);
            }
            throw new CapturaException(Motivo.TRANSPORTE, "Error de comunicación con el servidor", e);
        } finally {
            alarma.cancel(false);
            cerrar(socket);
            cerrar(conexion.socket());
        }
    }

    /** Raw path and query of the canonical form, Host of the authorized name, no body. */
    private static void enviar(Socket socket, DestinationUrl url, String host) throws IOException {
        String destino = url.path() + url.query().map(q -> "?" + q).orElse("");
        String peticion = "GET " + destino + " HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "User-Agent: " + USER_AGENT + "\r\n"
                + "Accept: text/html, application/xhtml+xml\r\n"
                + "Accept-Encoding: gzip, identity\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(peticion.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    static boolean esHtml(LectorHttp.Cabecera cabecera) {
        List<String> tipos = cabecera.cabeceras().getOrDefault("content-type", List.of());
        if (tipos.size() != 1) {
            return false;
        }
        String tipo = tipos.get(0).split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        return tipo.equals("text/html") || tipo.equals("application/xhtml+xml");
    }

    private static int restante(long limite) throws SocketTimeoutException {
        long ms = (limite - System.nanoTime()) / 1_000_000;
        if (ms <= 0) {
            throw new SocketTimeoutException("plazo total agotado");
        }
        return (int) Math.min(Integer.MAX_VALUE, ms);
    }

    private static void cerrar(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // nothing else to release
        }
    }
}
