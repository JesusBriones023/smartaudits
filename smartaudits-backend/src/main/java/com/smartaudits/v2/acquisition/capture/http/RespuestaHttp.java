package com.smartaudits.v2.acquisition.capture.http;

import java.util.List;
import java.util.Map;

/**
 * Response of one connection. The body is only read for a 200 with an HTML media type; for any
 * other response it is empty (the connection is closed without draining it).
 *
 * @param cabeceras lowercase names, values in order
 * @param cuerpo    body after transfer decoding and decompression, within the limits
 */
public record RespuestaHttp(int estado, Map<String, List<String>> cabeceras, byte[] cuerpo, ObservacionConexion observacion) {

    public String cabecera(String nombre) {
        List<String> valores = cabeceras.get(nombre);
        return valores == null || valores.isEmpty() ? null : valores.get(0);
    }

    public int numeroDe(String nombre) {
        List<String> valores = cabeceras.get(nombre);
        return valores == null ? 0 : valores.size();
    }
}
