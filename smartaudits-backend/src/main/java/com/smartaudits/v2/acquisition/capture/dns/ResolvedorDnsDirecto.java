package com.smartaudits.v2.acquisition.capture.dns;

import com.smartaudits.v2.acquisition.destination.DnsAnswer;
import com.smartaudits.v2.acquisition.destination.DnsAnswer.FamilyAnswer;
import com.smartaudits.v2.acquisition.destination.DnsAnswer.Status;
import com.smartaudits.v2.acquisition.destination.HostResolver;
import com.smartaudits.v2.acquisition.destination.ValidatedHost;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Real {@link HostResolver} (ADR-0002 section 10): one unicast query for A and one for AAAA of the
 * absolute name, sent only to a DNS server configured as an IP literal. It never uses the system
 * resolver, so no hosts file, search domains, mDNS, LLMNR or NetBIOS take part. Each query has a
 * random ID, RD=1 and EDNS (payload {@value #EDNS_PAYLOAD}, DO=0); a reply counts only if it is a
 * response from that server to exactly that question. TC=1 repeats the query once over TCP. CNAME
 * chains are followed inside the answer section only. Anything incomplete, inconsistent or late is
 * a FAILURE for that family; the destination policy then validates and classifies every address.
 * DNSSEC is not validated.
 */
public final class ResolvedorDnsDirecto implements HostResolver {

    public static final int EDNS_PAYLOAD = 1232;
    static final int MAX_UDP = 4096;
    static final int MAX_TCP = 16_384;
    static final int MAX_ANSWERS = 64;
    /** One more than the policy's limit, so a longer chain reaches it as DNS_LIMIT. */
    static final int MAX_CHAIN = 9;
    static final int MAX_DATAGRAMAS_AJENOS = 8;
    static final int TYPE_A = 1;
    static final int TYPE_CNAME = 5;
    static final int TYPE_AAAA = 28;
    static final int CLASS_IN = 1;

    private final InetSocketAddress servidor;
    private final Duration timeout;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param ipServidor 4 or 16 bytes of the DNS server address (never a name)
     * @param puerto     DNS port of that server
     * @param timeout    deadline of each query exchange (UDP and, if needed, TCP)
     */
    public ResolvedorDnsDirecto(byte[] ipServidor, int puerto, Duration timeout) {
        Objects.requireNonNull(ipServidor, "ipServidor");
        if (ipServidor.length != 4 && ipServidor.length != 16) {
            throw new IllegalArgumentException("la dirección del servidor DNS debe ser IPv4 o IPv6 literal");
        }
        if (puerto < 1 || puerto > 65535) {
            throw new IllegalArgumentException("puerto DNS");
        }
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero() || timeout.toMillis() > 10_000) {
            throw new IllegalArgumentException("timeout DNS entre 1 ms y 10 s");
        }
        try {
            this.servidor = new InetSocketAddress(InetAddress.getByAddress(ipServidor.clone()), puerto);
        } catch (IOException e) {
            throw new IllegalArgumentException("dirección del servidor DNS", e);
        }
    }

    @Override
    public DnsAnswer resolve(ValidatedHost host) {
        String nombre = host.name();
        return DnsAnswer.of(familia(nombre, TYPE_A), familia(nombre, TYPE_AAAA));
    }

    private FamilyAnswer familia(String nombre, int tipo) {
        try {
            return interpretar(nombre, tipo, consultar(nombre, tipo));
        } catch (IOException | RespuestaInvalida e) {
            return FamilyAnswer.of(Status.FAILURE, List.of(), List.of());
        }
    }

    // ---------------------------------------------------------------- exchange

    private byte[] consultar(String nombre, int tipo) throws IOException, RespuestaInvalida {
        int id = random.nextInt(0x10000);
        byte[] consulta = consulta(id, nombre, tipo);
        long limite = System.nanoTime() + timeout.toNanos();
        byte[] respuesta = porUdp(consulta, id, nombre, tipo, limite);
        if ((respuesta[2] & 0x02) != 0) { // TC=1: the complete answer only over TCP
            respuesta = porTcp(consulta, id, nombre, tipo, limite);
            if ((respuesta[2] & 0x02) != 0) {
                throw new RespuestaInvalida("TC sobre TCP");
            }
        }
        return respuesta;
    }

    private byte[] porUdp(byte[] consulta, int id, String nombre, int tipo, long limite) throws IOException, RespuestaInvalida {
        try (DatagramSocket socket = new DatagramSocket()) { // ephemeral source port chosen by the OS
            socket.connect(servidor); // only datagrams from the configured server are delivered
            socket.send(new DatagramPacket(consulta, consulta.length));
            byte[] buffer = new byte[MAX_UDP];
            for (int ajenos = 0; ajenos <= MAX_DATAGRAMAS_AJENOS; ajenos++) {
                socket.setSoTimeout(restante(limite));
                DatagramPacket paquete = new DatagramPacket(buffer, buffer.length);
                socket.receive(paquete);
                byte[] mensaje = java.util.Arrays.copyOf(paquete.getData(), paquete.getLength());
                if (corresponde(mensaje, id, nombre, tipo)) {
                    return mensaje;
                }
            }
            throw new RespuestaInvalida("demasiados datagramas que no corresponden a la consulta");
        }
    }

    private byte[] porTcp(byte[] consulta, int id, String nombre, int tipo, long limite) throws IOException, RespuestaInvalida {
        try (Socket socket = new Socket(Proxy.NO_PROXY)) {
            socket.connect(servidor, restante(limite));
            socket.setSoTimeout(restante(limite));
            OutputStream out = socket.getOutputStream();
            out.write(new byte[]{(byte) (consulta.length >> 8), (byte) consulta.length});
            out.write(consulta);
            out.flush();
            DataInputStream in = new DataInputStream(socket.getInputStream());
            int longitud = in.readUnsignedShort();
            if (longitud > MAX_TCP || longitud < 12) {
                throw new RespuestaInvalida("longitud TCP");
            }
            byte[] mensaje = new byte[longitud];
            leerCompleto(in, mensaje, socket, limite);
            if (!corresponde(mensaje, id, nombre, tipo)) {
                throw new RespuestaInvalida("la respuesta TCP no corresponde a la consulta");
            }
            return mensaje;
        }
    }

    private static void leerCompleto(InputStream in, byte[] destino, Socket socket, long limite) throws IOException {
        int leidos = 0;
        while (leidos < destino.length) {
            socket.setSoTimeout(restante(limite));
            int n = in.read(destino, leidos, destino.length - leidos);
            if (n < 0) {
                throw new IOException("respuesta TCP truncada");
            }
            leidos += n;
        }
    }

    private static int restante(long limite) throws SocketTimeoutException {
        long ms = (limite - System.nanoTime()) / 1_000_000;
        if (ms <= 0) {
            throw new SocketTimeoutException("plazo DNS agotado");
        }
        return (int) Math.min(Integer.MAX_VALUE, ms);
    }

    // ---------------------------------------------------------------- wire format

    static byte[] consulta(int id, String nombre, int tipo) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        escribir16(out, id);
        escribir16(out, 0x0100); // standard query, RD=1
        escribir16(out, 1);      // QDCOUNT
        escribir16(out, 0);
        escribir16(out, 0);
        escribir16(out, 1);      // ARCOUNT: EDNS OPT
        for (String etiqueta : nombre.split("\\.")) { // absolute name: no search domain is ever appended
            byte[] bytes = etiqueta.getBytes(StandardCharsets.US_ASCII);
            out.write(bytes.length);
            out.write(bytes, 0, bytes.length);
        }
        out.write(0);
        escribir16(out, tipo);
        escribir16(out, CLASS_IN);
        out.write(0);              // OPT owner: root
        escribir16(out, 41);       // OPT
        escribir16(out, EDNS_PAYLOAD);
        escribir16(out, 0);        // extended RCODE and version
        escribir16(out, 0);        // flags: DO=0
        escribir16(out, 0);        // no options
        return out.toByteArray();
    }

    /** A response (QR=1, standard opcode) to this ID and exactly this question. */
    static boolean corresponde(byte[] m, int id, String nombre, int tipo) {
        try {
            if (m.length < 12 || u16(m, 0) != id || (m[2] & 0x80) == 0 || ((m[2] >> 3) & 0x0F) != 0 || u16(m, 4) != 1) {
                return false;
            }
            Nombre q = leerNombre(m, 12);
            return q.texto.equalsIgnoreCase(nombre) && u16(m, q.fin) == tipo && u16(m, q.fin + 2) == CLASS_IN;
        } catch (RespuestaInvalida | IndexOutOfBoundsException e) {
            return false;
        }
    }

    static FamilyAnswer interpretar(String nombre, int tipo, byte[] m) throws RespuestaInvalida {
        try {
            int rcode = m[3] & 0x0F;
            if (rcode != 0 && rcode != 3) {
                return FamilyAnswer.of(Status.FAILURE, List.of(), List.of()); // SERVFAIL, REFUSED...
            }
            int respuestas = u16(m, 6);
            if (respuestas > MAX_ANSWERS) {
                throw new RespuestaInvalida("demasiados registros");
            }
            int pos = leerNombre(m, 12).fin + 4;
            List<String[]> cnames = new ArrayList<>(); // {owner, target}
            List<String> propietarios = new ArrayList<>();
            List<byte[]> datos = new ArrayList<>();
            for (int i = 0; i < respuestas; i++) {
                Nombre propietario = leerNombre(m, pos);
                int rtype = u16(m, propietario.fin);
                int rclass = u16(m, propietario.fin + 2);
                int rdlength = u16(m, propietario.fin + 8);
                int inicio = propietario.fin + 10;
                if (inicio + rdlength > m.length) {
                    throw new RespuestaInvalida("registro truncado");
                }
                if (rclass == CLASS_IN && rtype == TYPE_CNAME) {
                    Nombre destino = leerNombre(m, inicio);
                    // fin is the on-wire end (including a compression pointer), not the expanded name.
                    if (destino.fin != inicio + rdlength) {
                        throw new RespuestaInvalida("longitud CNAME no válida");
                    }
                    cnames.add(new String[]{propietario.texto.toLowerCase(Locale.ROOT), destino.texto});
                } else if (rclass == CLASS_IN && rtype == tipo) {
                    propietarios.add(propietario.texto.toLowerCase(Locale.ROOT));
                    datos.add(java.util.Arrays.copyOfRange(m, inicio, inicio + rdlength)); // raw: wrong lengths reach the policy
                }
                pos = inicio + rdlength;
            }
            // Follow the chain from the queried name; every record must belong to its end.
            List<String> cadena = new ArrayList<>();
            String actual = nombre.toLowerCase(Locale.ROOT);
            while (cadena.size() < MAX_CHAIN) {
                String siguiente = null;
                for (String[] c : cnames) {
                    if (c[0].equals(actual)) {
                        if (siguiente != null) {
                            throw new RespuestaInvalida("varios CNAME para el mismo nombre");
                        }
                        siguiente = c[1];
                    }
                }
                if (siguiente == null) {
                    break;
                }
                cadena.add(siguiente);
                actual = siguiente.toLowerCase(Locale.ROOT);
            }
            // An over-long chain is handed to the policy as is: it rejects it with DNS_LIMIT.
            if (cadena.size() < MAX_CHAIN) {
                if (cnames.size() > cadena.size()) {
                    throw new RespuestaInvalida("CNAME ajeno a la cadena");
                }
                for (String propietario : propietarios) {
                    if (!propietario.equals(actual)) {
                        throw new RespuestaInvalida("registro de dirección de un nombre ajeno a la cadena");
                    }
                }
            }
            if (rcode == 3) {
                return FamilyAnswer.of(Status.NXDOMAIN, cadena, datos);
            }
            return datos.isEmpty() ? FamilyAnswer.of(Status.NODATA, cadena, List.of())
                    : FamilyAnswer.of(Status.ADDRESSES, cadena, datos);
        } catch (IndexOutOfBoundsException e) {
            throw new RespuestaInvalida("mensaje truncado");
        }
    }

    private record Nombre(String texto, int fin) {}

    /** Name with compression pointers (backwards only, bounded jumps); labels must not contain dots. */
    private static Nombre leerNombre(byte[] m, int inicio) throws RespuestaInvalida {
        StringBuilder texto = new StringBuilder();
        int pos = inicio;
        int fin = -1;
        int saltos = 0;
        while (true) {
            int longitud = m[pos] & 0xFF;
            if ((longitud & 0xC0) == 0xC0) {
                int destino = ((longitud & 0x3F) << 8) | (m[pos + 1] & 0xFF);
                if (destino >= pos || ++saltos > 16) {
                    throw new RespuestaInvalida("puntero de compresión inválido");
                }
                if (fin < 0) {
                    fin = pos + 2;
                }
                pos = destino;
                continue;
            }
            if ((longitud & 0xC0) != 0) {
                throw new RespuestaInvalida("tipo de etiqueta no soportado");
            }
            if (longitud == 0) {
                return new Nombre(texto.toString(), fin < 0 ? pos + 1 : fin);
            }
            if (texto.length() + longitud > 253) {
                throw new RespuestaInvalida("nombre demasiado largo");
            }
            String etiqueta = new String(m, pos + 1, longitud, StandardCharsets.ISO_8859_1);
            if (etiqueta.indexOf('.') >= 0) {
                throw new RespuestaInvalida("etiqueta con punto");
            }
            if (texto.length() > 0) {
                texto.append('.');
            }
            texto.append(etiqueta);
            pos += longitud + 1;
        }
    }

    private static int u16(byte[] m, int i) {
        return ((m[i] & 0xFF) << 8) | (m[i + 1] & 0xFF);
    }

    private static void escribir16(ByteArrayOutputStream out, int valor) {
        out.write((valor >> 8) & 0xFF);
        out.write(valor & 0xFF);
    }

    static final class RespuestaInvalida extends Exception {
        RespuestaInvalida(String message) {
            super(message);
        }
    }
}
