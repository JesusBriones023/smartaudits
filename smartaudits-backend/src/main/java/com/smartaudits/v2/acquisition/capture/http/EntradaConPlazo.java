package com.smartaudits.v2.acquisition.capture.http;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;

/**
 * Socket input under an absolute deadline: before every read the socket timeout becomes the
 * smaller of the per-read limit and the time left, so a server dripping bytes cannot extend the
 * capture beyond the deadline.
 */
final class EntradaConPlazo extends InputStream {

    private final Socket socket;
    private final InputStream in;
    private final long limiteNanos;
    private final long lecturaMs;

    EntradaConPlazo(Socket socket, long limiteNanos, Duration lectura) throws IOException {
        this.socket = socket;
        this.in = socket.getInputStream();
        this.limiteNanos = limiteNanos;
        this.lecturaMs = lectura.toMillis();
    }

    private void prepararLectura() throws IOException {
        long restanteMs = (limiteNanos - System.nanoTime()) / 1_000_000;
        if (restanteMs <= 0) {
            throw new SocketTimeoutException("plazo total agotado");
        }
        socket.setSoTimeout((int) Math.max(1, Math.min(Math.min(restanteMs, lecturaMs), Integer.MAX_VALUE)));
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
