package com.smartaudits.v2.acquisition.capture.http;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Objects;

/**
 * A connected socket and the peer it is connected to. When the peer comes from the socket itself
 * ({@link #delSocket}) it is an observation of the operating system; a test double can only
 * declare one ({@link #declarada}), and a capture through a declared peer is never REAL.
 */
public final class Conexion {

    private final Socket socket;
    private final InetSocketAddress peer;
    private final boolean peerObservado;

    private Conexion(Socket socket, InetSocketAddress peer, boolean peerObservado) {
        this.socket = Objects.requireNonNull(socket, "socket");
        this.peer = Objects.requireNonNull(peer, "peer");
        this.peerObservado = peerObservado;
    }

    /** Peer read from the connected socket (the address the OS actually connected to). */
    static Conexion delSocket(Socket socket) {
        if (!(socket.getRemoteSocketAddress() instanceof InetSocketAddress remoto) || remoto.getAddress() == null) {
            throw new IllegalStateException("socket sin peer conectado");
        }
        return new Conexion(socket, remoto, true);
    }

    /** Test doubles only: the peer is stated by the double, not observed. */
    public static Conexion declarada(Socket socket, InetSocketAddress peer) {
        return new Conexion(socket, peer, false);
    }

    public Socket socket() {
        return socket;
    }

    public InetSocketAddress peer() {
        return peer;
    }

    public boolean peerObservado() {
        return peerObservado;
    }
}
