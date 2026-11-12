package com.smartaudits.v2.acquisition.capture.http;

import com.smartaudits.v2.acquisition.capture.PlazoCaptura;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.time.Duration;
import java.util.Objects;

/**
 * Socket input under an absolute deadline: before every read the socket timeout becomes the
 * smaller of the per-read limit and the time left, so a server dripping bytes cannot extend the
 * capture beyond the deadline. An expired deadline fails before reading (never a 0 = infinite timeout).
 */
final class EntradaConPlazo extends InputStream {

    private final Socket socket;
    private final InputStream in;
    private final PlazoCaptura plazo;
    private final Duration lectura;

    EntradaConPlazo(Socket socket, PlazoCaptura plazo, Duration lectura) throws IOException {
        this.socket = socket;
        this.plazo = Objects.requireNonNull(plazo, "plazo");
        this.lectura = Objects.requireNonNull(lectura, "lectura");
        this.in = socket.getInputStream();
    }

    private void prepararLectura() throws IOException {
        socket.setSoTimeout(plazo.milisDeEspera(lectura));
    }

    @Override
    public int read() throws IOException {
        prepararLectura();
        return in.read();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        prepararLectura();
        return in.read(b, off, len);
    }
}
