package com.smartaudits.v2.acquisition.capture.http;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

/**
 * Strict HTTP/1.1 response reader (fail closed, lesson of Q-HTTP QF-1/QF-2):
 * - status line {@code HTTP/1.0} or {@code HTTP/1.1}, three-digit code; informational codes rejected;
 * - every line ends in CRLF (bare LF or CR rejected), no obs-fold, token header names,
 *   bounded header bytes and count;
 * - framing: exactly {@code Transfer-Encoding: chunked}, or equal Content-Length values, or read
 *   to close; both TE and CL, other codings or non-numeric lengths are rejected; any EOF inside a
 *   declared length or chunk is a truncation, never a short success;
 * - {@code Content-Encoding} absent, {@code identity} or a single {@code gzip}, decompressed under
 *   its own limit.
 */
final class LectorHttp {

    record Cabecera(int estado, Map<String, List<String>> cabeceras) {}

    private final InputStream in;
    private final LimitesHttp limites;
    private int bytesCabecera;

    LectorHttp(InputStream in, LimitesHttp limites) {
        this.in = in;
        this.limites = limites;
    }

    Cabecera leerCabecera() throws IOException {
        String estado = linea(true);
        if (!(estado.startsWith("HTTP/1.1 ") || estado.startsWith("HTTP/1.0 ")) || estado.length() < 12
                || (estado.length() > 12 && estado.charAt(12) != ' ')) {
            throw new ErrorRespuesta("línea de estado no válida");
        }
        int codigo;
        try {
            String digitos = estado.substring(9, 12);
            if (!digitos.chars().allMatch(c -> c >= '0' && c <= '9')) {
                throw new NumberFormatException();
            }
            codigo = Integer.parseInt(digitos);
        } catch (NumberFormatException e) {
            throw new ErrorRespuesta("código de estado no válido");
        }
        if (codigo < 200 || codigo > 599) {
            throw new ErrorRespuesta("código de estado no admitido");
        }
        Map<String, List<String>> cabeceras = new LinkedHashMap<>();
        int numero = 0;
        while (true) {
            String linea = linea(true);
            if (linea.isEmpty()) {
                break;
            }
            if (++numero > limites.maxCabeceras()) {
                throw new ErrorRespuesta("demasiadas cabeceras");
            }
            if (linea.charAt(0) == ' ' || linea.charAt(0) == '\t') {
                throw new ErrorRespuesta("cabecera plegada (obs-fold)");
            }
            int dosPuntos = linea.indexOf(':');
            if (dosPuntos <= 0 || !token(linea.substring(0, dosPuntos))) {
                throw new ErrorRespuesta("nombre de cabecera no válido");
            }
            String valor = linea.substring(dosPuntos + 1).strip();
            if (valor.chars().anyMatch(c -> (c < 0x20 && c != '\t') || c == 0x7F)) {
                throw new ErrorRespuesta("valor de cabecera no válido");
            }
            cabeceras.computeIfAbsent(linea.substring(0, dosPuntos).toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(valor);
        }
        cabeceras.replaceAll((k, v) -> Collections.unmodifiableList(v));
        return new Cabecera(codigo, Collections.unmodifiableMap(cabeceras));
    }

    byte[] leerCuerpo(Cabecera cabecera) throws IOException {
        List<String> te = valores(cabecera, "transfer-encoding");
        List<String> cl = cabecera.cabeceras().getOrDefault("content-length", List.of());
        byte[] crudo;
        if (!te.isEmpty()) {
            if (!cl.isEmpty() || te.size() != 1 || !te.get(0).equalsIgnoreCase("chunked")) {
                throw new ErrorRespuesta("Transfer-Encoding no admitido o ambiguo");
            }
            crudo = troceado();
        } else if (!cl.isEmpty()) {
            crudo = conLongitud(longitud(cl));
        } else {
            crudo = hastaCierre();
        }
        List<String> ce = valores(cabecera, "content-encoding");
        if (ce.isEmpty() || (ce.size() == 1 && ce.get(0).equalsIgnoreCase("identity"))) {
            return crudo;
        }
        if (ce.size() == 1 && ce.get(0).equalsIgnoreCase("gzip")) {
            return descomprimir(crudo);
        }
        throw new ErrorRespuesta("Content-Encoding no admitido");
    }

    private long longitud(List<String> valores) throws ErrorRespuesta {
        String primero = valores.get(0);
        for (String v : valores) {
            if (!v.equals(primero) || v.isEmpty() || v.length() > 18 || !v.chars().allMatch(c -> c >= '0' && c <= '9')) {
                throw new ErrorRespuesta("Content-Length no válido o ambiguo");
            }
        }
        long n = Long.parseLong(primero);
        if (n > limites.maxCuerpoTransferido()) {
            throw new ErrorRespuesta("respuesta demasiado grande");
        }
        return n;
    }

    private byte[] conLongitud(long n) throws IOException {
        byte[] cuerpo = new byte[(int) n];
        int leidos = 0;
        while (leidos < n) {
            int r = in.read(cuerpo, leidos, (int) n - leidos);
            if (r < 0) {
                throw new ErrorRespuesta("cuerpo truncado");
            }
            leidos += r;
        }
        return cuerpo;
    }

    private byte[] hastaCierre() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int r;
        while ((r = in.read(buffer)) >= 0) {
            if (out.size() + (long) r > limites.maxCuerpoTransferido()) {
                throw new ErrorRespuesta("respuesta demasiado grande");
            }
            out.write(buffer, 0, r);
        }
        return out.toByteArray();
    }

    private byte[] troceado() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String linea = linea(false);
            int fin = linea.indexOf(';');
            String hex = (fin >= 0 ? linea.substring(0, fin) : linea).strip();
            if (hex.isEmpty() || hex.length() > 8 || !hex.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
                throw new ErrorRespuesta("tamaño de trozo no válido");
            }
            long tamano = Long.parseLong(hex, 16);
            if (tamano == 0) {
                break;
            }
            if (out.size() + tamano > limites.maxCuerpoTransferido()) {
                throw new ErrorRespuesta("respuesta demasiado grande");
            }
            out.write(conLongitud(tamano));
            if (!linea(false).isEmpty()) {
                throw new ErrorRespuesta("trozo sin CRLF final");
            }
        }
        while (!linea(true).isEmpty()) { // trailers: bounded by the header limit, discarded
        }
        return out.toByteArray();
    }

    private byte[] descomprimir(byte[] comprimido) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(comprimido), 8192)) {
            byte[] buffer = new byte[8192];
            int r;
            while ((r = gzip.read(buffer)) >= 0) {
                if (out.size() + (long) r > limites.maxCuerpoDescomprimido()) {
                    throw new ErrorRespuesta("contenido descomprimido demasiado grande");
                }
                out.write(buffer, 0, r);
            }
        } catch (ZipException | java.io.EOFException e) {
            throw new ErrorRespuesta("contenido gzip no válido");
        }
        return out.toByteArray();
    }

    /** One CRLF-terminated line; header lines count against the header limit. */
    private String linea(boolean cabecera) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int maximo = cabecera ? limites.maxCabecera() : 1024;
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new ErrorRespuesta("respuesta truncada");
            }
            if (cabecera && ++bytesCabecera > limites.maxCabecera()) {
                throw new ErrorRespuesta("cabecera demasiado grande");
            }
            if (b == '\n') {
                throw new ErrorRespuesta("LF sin CR");
            }
            if (b == '\r') {
                if (in.read() != '\n') {
                    throw new ErrorRespuesta("CR sin LF");
                }
                if (cabecera) {
                    bytesCabecera++;
                }
                return out.toString(StandardCharsets.ISO_8859_1);
            }
            if (out.size() >= maximo) {
                throw new ErrorRespuesta("línea demasiado larga");
            }
            out.write(b);
        }
    }

    private static List<String> valores(Cabecera cabecera, String nombre) {
        List<String> resultado = new ArrayList<>();
        for (String v : cabecera.cabeceras().getOrDefault(nombre, List.of())) {
            for (String parte : v.split(",")) {
                if (!parte.isBlank()) {
                    resultado.add(parte.strip());
                }
            }
        }
        return resultado;
    }

    private static boolean token(String nombre) {
        return nombre.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || "!#$%&'*+-.^_`|~".indexOf(c) >= 0);
    }
}
