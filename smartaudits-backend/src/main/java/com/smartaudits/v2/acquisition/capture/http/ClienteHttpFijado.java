package com.smartaudits.v2.acquisition.capture.http;

import com.smartaudits.v2.acquisition.capture.CapturaException;
import com.smartaudits.v2.acquisition.capture.CapturaException.Motivo;
import com.smartaudits.v2.acquisition.capture.PlazoCaptura;
import com.smartaudits.v2.acquisition.destination.DestinationUrl;
import com.smartaudits.v2.acquisition.destination.IpAddress;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.IntUnaryOperator;

/**
 * One GET over one connection to one approved address, with nothing automatic: no DNS (the
 * connector receives the IP), no proxy, no redirects, retries, cookies, cache or connection reuse.
 * For https the TLS identity is the authorized host name, never the IP: SNI and endpoint
 * identification use the host, JSSE validates the chain with the given trust (the system trust
 * store in production) and {@link IdentidadTls} checks the certificate again independently.
 *
 * <p>Time (PV-15): the connection deadline is the earlier of {@link LimitesHttp#total()} and the
 * acquisition deadline received from the transport. Connect, the TLS handshake and every read use the
 * smaller of their own limit and the time left; at the connection deadline the watchdog closes the raw
 * socket, which also aborts a blocked write.
 */
public final class ClienteHttpFijado {

    static final String USER_AGENT = "SmartAudits/2.0 (auditoria legal; +https://smartaudits.es)";
    static final String MENSAJE_PLAZO = "Plazo total de adquisición agotado";
    static final String MENSAJE_IDENTIDAD_TLS = "La identidad TLS del servidor no es válida para el host autorizado";
    static final String MENSAJE_TLS = "Fallo de comunicación o de protocolo TLS con el servidor";
    static final String MENSAJE_CIERRE = "No se pudo cerrar la conexión con el servidor";
    private static final List<String> PROTOCOLOS = List.of("TLSv1.3", "TLSv1.2");
    /**
     * One daemon thread for the class loader's lifetime; cancelled alarms are removed at once. It is
     * not owned by Spring: the real transport is not wired, and its lifecycle belongs to the future
     * authorized activation.
     */
    static final VigilanteDelSistema VIGILANTE = new VigilanteDelSistema("smartaudits-plazo-http");

    private final Conector conector;
    private final SSLSocketFactory fabricaTls;
    private final LimitesHttp limites;
    private final IntUnaryOperator puertoTcp;
    private final Vigilante vigilante;

    public ClienteHttpFijado(Conector conector, SSLSocketFactory fabricaTls, LimitesHttp limites) {
        this(conector, fabricaTls, limites, IntUnaryOperator.identity());
    }

    /**
     * Package-private seam for loopback tests of this layer only: the TCP port actually dialled for
     * the URL's effective port. The observation keeps the dialled port, so TransporteHttpReal (which
     * requires it to equal the URL port) rejects any mapping: it cannot be used through the product flow.
     */
    ClienteHttpFijado(Conector conector, SSLSocketFactory fabricaTls, LimitesHttp limites, IntUnaryOperator puertoTcp) {
        this(conector, fabricaTls, limites, puertoTcp, VIGILANTE);
    }

    /** Package-private seam for deterministic watchdog tests. */
    ClienteHttpFijado(Conector conector, SSLSocketFactory fabricaTls, LimitesHttp limites, IntUnaryOperator puertoTcp,
                      Vigilante vigilante) {
        this.conector = Objects.requireNonNull(conector, "conector");
        this.fabricaTls = Objects.requireNonNull(fabricaTls, "fabricaTls");
        this.limites = Objects.requireNonNull(limites, "limites");
        this.puertoTcp = Objects.requireNonNull(puertoTcp, "puertoTcp");
        this.vigilante = Objects.requireNonNull(vigilante, "vigilante");
    }

    public LimitesHttp limites() {
        return limites;
    }

    /** One connection bounded only by these limits (no acquisition deadline). */
    public RespuestaHttp obtener(DestinationUrl url, IpAddress ip) {
        return obtenerCon(url, ip, null);
    }

    /** One connection that also ends by the acquisition deadline {@code adquisicion}, which it never extends. */
    public RespuestaHttp obtener(DestinationUrl url, IpAddress ip, PlazoCaptura adquisicion) {
        return obtenerCon(url, ip, Objects.requireNonNull(adquisicion, "adquisicion"));
    }

    private RespuestaHttp obtenerCon(DestinationUrl url, IpAddress ip, PlazoCaptura adquisicion) {
        PlazoCaptura plazo = adquisicion == null ? PlazoCaptura.iniciar(limites.total(), System::nanoTime)
                : adquisicion.acotado(limites.total());
        int puerto = puertoTcp.applyAsInt(url.port());
        Conexion conexion;
        try {
            // The watchdog starts only after connect, so connect gets its own bound. It is never 0
            // (Socket.connect reads 0 as unlimited) and an expired deadline fails here, before dialling.
            conexion = conector.conectar(ip, puerto, Duration.ofMillis(plazo.milisDeEspera(limites.conexion())));
        } catch (IOException e) {
            if (adquisicion != null && adquisicion.vencido()) {
                throw new CapturaException(Motivo.PLAZO_AGOTADO, MENSAJE_PLAZO, e);
            }
            throw new CapturaException(Motivo.TRANSPORTE, "No se pudo conectar con la dirección aprobada", e);
        }
        Socket crudo = Objects.requireNonNull(conexion, "conexion").socket();
        CierreCrudo cierre = new CierreCrudo(crudo);
        Socket socket = crudo;
        Vigilante.Alarma alarma = null;
        Throwable primaria = null;
        try {
            // The connection is owned from here on: every exit, including a failure to arm the
            // watchdog, reaches the finally below. Per-read timeouts alone let a server drip a TLS
            // handshake (or anything) forever: at the deadline the raw socket is closed, which aborts
            // any blocked read or write. The alarm only knows this attempt's own raw socket.
            alarma = armar(cierre, plazo);
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
                SSLSocket ssl = (SSLSocket) fabricaTls.createSocket(crudo, host, url.port(), true);
                socket = ssl;
                SSLParameters parametros = ssl.getSSLParameters();
                parametros.setEndpointIdentificationAlgorithm("HTTPS");
                parametros.setServerNames(List.of(new SNIHostName(host)));
                parametros.setProtocols(PROTOCOLOS.stream().filter(p -> Arrays.asList(ssl.getSupportedProtocols()).contains(p))
                        .toArray(String[]::new));
                ssl.setSSLParameters(parametros);
                ssl.setSoTimeout(plazo.milisDeEspera());
                ssl.startHandshake();
                var sesion = ssl.getSession();
                if (!(sesion.getPeerCertificates()[0] instanceof X509Certificate hoja) || !IdentidadTls.coincide(hoja, host)) {
                    throw new CapturaException(Motivo.TRANSPORTE, MENSAJE_IDENTIDAD_TLS);
                }
                sni = ((SNIHostName) ssl.getSSLParameters().getServerNames().get(0)).getAsciiName();
                identidad = host;
                protocolo = sesion.getProtocol();
            }
            enviar(socket, url, host);
            LectorHttp lector = new LectorHttp(new BufferedInputStream(new EntradaConPlazo(socket, plazo, limites.lectura()), 8192), limites);
            LectorHttp.Cabecera cabecera = lector.leerCabecera();
            byte[] cuerpo = cabecera.estado() == 200 && esHtml(cabecera) ? lector.leerCuerpo(cabecera) : new byte[0];
            return new RespuestaHttp(cabecera.estado(), cabecera.cabeceras(), cuerpo, new ObservacionConexion(url, peer,
                    conexion.peer().getPort(), conexion.peerObservado(), tls, sni, identidad, protocolo, host));
        } catch (IOException e) {
            CapturaException clasificada = clasificar(e, plazo, adquisicion);
            primaria = clasificada;
            throw clasificada;
        } catch (RuntimeException | Error e) {
            primaria = e;
            throw e;
        } finally {
            cerrarTodo(cierre, socket, alarma, primaria);
        }
    }

    private Vigilante.Alarma armar(CierreCrudo cierre, PlazoCaptura plazo) {
        try {
            return vigilante.programar(cierre::cerrarDesdeVigilante, Math.max(1, plazo.restanteNanos()));
        } catch (RejectedExecutionException e) {
            throw new CapturaException(Motivo.TRANSPORTE, "No se pudo vigilar el plazo de la conexión", e);
        }
    }

    /**
     * Facts about the response and the peer's authentication are kept first, so a timing check made
     * late never hides them: a framing or size violation stays RESPUESTA_NO_VALIDA; a rejected
     * certificate (a CertificateException anywhere in the cause chain) or an unauthenticated peer
     * (SSLPeerUnverifiedException, which carries no such cause) stays a TLS identity failure. Only then
     * is a timeout, or a close by the watchdog, a deadline expiry: PLAZO_AGOTADO when the acquisition
     * deadline has passed, otherwise this connection's own timeout. Any other SSLException (a handshake
     * cut by the watchdog among them) remains eligible for PLAZO_AGOTADO; otherwise it is reported as a
     * TLS communication or protocol failure, which claims nothing about the server's identity.
     */
    static CapturaException clasificar(IOException e, PlazoCaptura conexion, PlazoCaptura adquisicion) {
        if (e instanceof ErrorRespuesta) {
            return new CapturaException(Motivo.RESPUESTA_NO_VALIDA, "Respuesta HTTP no válida: " + e.getMessage(), e);
        }
        if (e instanceof SSLPeerUnverifiedException || (e instanceof SSLException && causaDeCertificado(e))) {
            return new CapturaException(Motivo.TRANSPORTE, MENSAJE_IDENTIDAD_TLS, e);
        }
        if (e instanceof SocketTimeoutException || conexion.vencido()) {
            if (adquisicion != null && adquisicion.vencido()) {
                return new CapturaException(Motivo.PLAZO_AGOTADO, MENSAJE_PLAZO, e);
            }
            return new CapturaException(Motivo.TRANSPORTE, "Tiempo de espera agotado", e);
        }
        if (e instanceof SSLException) {
            return new CapturaException(Motivo.TRANSPORTE, MENSAJE_TLS, e);
        }
        return new CapturaException(Motivo.TRANSPORTE, "Error de comunicación con el servidor", e);
    }

    private static boolean causaDeCertificado(Throwable e) {
        Throwable actual = e;
        for (int i = 0; i < 16 && actual != null; i++) {
            if (actual instanceof CertificateException) {
                return true;
            }
            actual = actual.getCause();
        }
        return false;
    }

    /**
     * Cleanup of one attempt. The TLS layer is closed first (a graceful close_notify, as before PV-15),
     * then the raw socket, and the raw close is attempted even when the TLS close fails in any way.
     * Writing close_notify can block on a peer that does not read, so the alarm is cancelled only after
     * both close attempts: by the connection deadline the watchdog closes the raw socket, which ends such
     * a blocked close (it does not end any other non-cooperative wait).
     *
     * <p>An IOException from the TLS close is expected (close_notify on a socket the watchdog already
     * closed, a peer that went away) and ignored, as before: the raw close that follows decides the
     * release. An IOException from a raw close (the owner's or an earlier watchdog close) means the
     * release is uncertain: it may have been thrown before anything was released. It is never ignored:
     * after a successful exchange it fails the call as TRANSPORTE, with that IOException as cause, so no
     * response is returned. This conservative rule also applies when the socket had in fact been released.
     *
     * <p>What propagates is decided first, before anything is linked: a primary Error stays; otherwise
     * the first cleanup Error, even one already inside the primary (it is never converted); otherwise the
     * primary (security, deadline, framing, ...), which an ordinary cleanup failure never replaces;
     * without a primary, the most serious cleanup failure (unchecked before IOException, the first among
     * equals). Then every other distinct failure, the primary last, is attached to it individually as
     * suppressed through {@link #agregarSuprimida}, which skips only one already reachable from it or one
     * that would close a loop: a failure that cannot be attached never takes the others with it.
     */
    static void cerrarTodo(CierreCrudo cierre, Socket socket, Vigilante.Alarma alarma, Throwable primaria) {
        List<Throwable> fallos = new ArrayList<>(4);
        try {
            try {
                if (socket != cierre.socket()) {
                    anotar(fallos, cerrarTls(socket));
                }
            } finally {
                for (Throwable f : cierre.cerrar()) {
                    anotar(fallos, f);
                }
            }
        } finally {
            if (alarma != null) {
                anotar(fallos, cancelarRegistrando(alarma));
            }
        }
        Throwable grave = masGrave(fallos);
        Throwable lanzada = primaria instanceof Error ? primaria
                : grave instanceof Error ? grave
                : primaria != null ? primaria : grave;
        if (lanzada == null) {
            return;
        }
        for (Throwable f : fallos) {
            agregarSuprimida(lanzada, f);
        }
        if (primaria != null) {
            agregarSuprimida(lanzada, primaria);
        }
        if (lanzada == primaria) {
            return; // the caller throws it
        }
        if (lanzada instanceof IOException e) {
            throw new CapturaException(Motivo.TRANSPORTE, MENSAJE_CIERRE, e);
        }
        lanzar(lanzada);
    }

    /** null, or the unchecked failure of the TLS close (an IOException from it is ignored). */
    private static Throwable cerrarTls(Socket tls) {
        try {
            tls.close();
            return null;
        } catch (IOException ignored) {
            return null;
        } catch (RuntimeException | Error e) {
            return e;
        }
    }

    /** null, or any failure of the raw close, an IOException included. */
    private static Throwable cerrarCrudo(Socket crudo) {
        try {
            crudo.close();
            return null;
        } catch (IOException | RuntimeException | Error e) {
            return e;
        }
    }

    private static Throwable cancelarRegistrando(Vigilante.Alarma alarma) {
        try {
            alarma.cancelar();
            return null;
        } catch (RuntimeException | Error e) {
            return e;
        }
    }

    /** Adds a failure unless it is null or that very instance is already listed (identity, not equals). */
    private static void anotar(List<Throwable> fallos, Throwable fallo) {
        if (fallo == null) {
            return;
        }
        for (Throwable visto : fallos) {
            if (visto == fallo) {
                return;
            }
        }
        fallos.add(fallo);
    }

    /** The first failure of the highest severity: an Error, then an unchecked failure, then an IOException. */
    private static Throwable masGrave(List<Throwable> fallos) {
        Throwable elegida = null;
        for (Throwable f : fallos) {
            if (elegida == null || gravedad(f) > gravedad(elegida)) {
                elegida = f;
            }
        }
        return elegida;
    }

    private static int gravedad(Throwable t) {
        return t instanceof Error ? 2 : t instanceof RuntimeException ? 1 : 0;
    }

    /**
     * Attaches {@code suprimida} to {@code principal} unless it is already reachable from it (itself
     * included), so no instance is recorded twice, or {@code principal} is reachable from it, which
     * would close a loop. The cause/suppressed graph therefore never gains a cycle from this code.
     */
    private static void agregarSuprimida(Throwable principal, Throwable suprimida) {
        if (principal == suprimida) {
            return;
        }
        if (!alcanza(principal, suprimida) && !alcanza(suprimida, principal)) {
            principal.addSuppressed(suprimida);
        }
    }

    /**
     * Whether {@code buscada} is {@code desde} or reachable from it through causes and suppressed
     * exceptions, by identity. Each instance is visited once, so an existing loop cannot hang it.
     */
    private static boolean alcanza(Throwable desde, Throwable buscada) {
        Set<Throwable> vistas = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> pendientes = new ArrayDeque<>();
        pendientes.push(desde);
        while (!pendientes.isEmpty()) {
            Throwable t = pendientes.pop();
            if (t == buscada) {
                return true;
            }
            if (vistas.add(t)) {
                Throwable causa = t.getCause();
                if (causa != null) {
                    pendientes.push(causa);
                }
                for (Throwable s : t.getSuppressed()) {
                    pendientes.push(s);
                }
            }
        }
        return false;
    }

    private static void lanzar(Throwable t) {
        if (t instanceof Error error) {
            throw error;
        }
        throw (RuntimeException) t; // only unchecked failures are ever recorded
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

    /**
     * The raw socket of one attempt, closed by its owner and possibly by the watchdog. Both raw closes
     * run under this object's lock and a failure of either, an IOException included, is kept for the
     * owner: a watchdog close that failed before the owner's own close completed is reported by the
     * owner (with a JDK socket the owner's later close would otherwise return normally, since the socket
     * implementation marks itself closed even when releasing the descriptor fails). The lock covers only
     * the raw close, which JDK 17 already runs under the socket's own monitor; the TLS close never runs
     * under it, so the watchdog can still end a blocked close_notify. No close is retried.
     */
    static final class CierreCrudo {
        private final Socket crudo;
        private final List<Throwable> fallos = new ArrayList<>(1); // guarded by this
        private boolean entregado; // guarded by this

        CierreCrudo(Socket crudo) {
            this.crudo = crudo;
        }

        Socket socket() {
            return crudo;
        }

        /**
         * The watchdog's action. The reporting cutoff is the owner's raw-close handoff ({@link #cerrar}),
         * not the end of every cleanup step: after it nobody reads a failure, so an unchecked one stays
         * in the task's future and an IOException is dropped, as before.
         */
        synchronized void cerrarDesdeVigilante() {
            Throwable f = cerrarCrudo(crudo);
            if (!entregado) {
                anotar(fallos, f);
            } else if (f instanceof RuntimeException || f instanceof Error) {
                lanzar(f);
            }
        }

        /**
         * The owner's raw close and the handoff: any earlier watchdog close failures, then the owner's
         * own, each instance once and none linked to another (the caller links them).
         */
        synchronized List<Throwable> cerrar() {
            Throwable propia = cerrarCrudo(crudo);
            List<Throwable> todas = new ArrayList<>(fallos);
            anotar(todas, propia);
            fallos.clear();
            entregado = true;
            return todas;
        }
    }

    /** Production watchdog: one daemon thread; a cancelled alarm is removed from the queue at once. */
    static final class VigilanteDelSistema implements Vigilante {
        private final ScheduledThreadPoolExecutor ejecutor;

        VigilanteDelSistema(String nombreHilo) {
            ejecutor = new ScheduledThreadPoolExecutor(1, r -> {
                Thread hilo = new Thread(r, nombreHilo);
                hilo.setDaemon(true);
                return hilo;
            });
            ejecutor.setRemoveOnCancelPolicy(true);
        }

        @Override
        public Alarma programar(Runnable accion, long retrasoNanos) {
            ScheduledFuture<?> tarea = ejecutor.schedule(accion, retrasoNanos, TimeUnit.NANOSECONDS);
            return () -> tarea.cancel(false);
        }

        /** Alarms still queued (tests only). */
        int pendientes() {
            return ejecutor.getQueue().size();
        }

        /** Stops a test-owned instance; the production instance is never shut down. */
        void detener() {
            ejecutor.shutdownNow();
        }
    }
}
