package com.smartaudits.v2.acquisition.capture.http;

import com.smartaudits.v2.acquisition.destination.IpAddress;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test connector: routes an approved (public, documentation-only) IP to a loopback test server and
 * DECLARES that peer, because the real peer is 127.0.0.1. Captures through it are never REAL. It
 * records every (IP, port) the transport asked for, which is what the pinning tests check.
 */
public final class ConectorDePrueba implements Conector {

    private final Map<IpAddress, Integer> rutas = new HashMap<>();
    private final List<String> llamadas = new ArrayList<>();
    private IpAddress peerFalso;

    public ConectorDePrueba ruta(IpAddress ip, int puertoLocal) {
        rutas.put(ip, puertoLocal);
        return this;
    }

    /** Declares this peer instead of the requested IP (a connection that went elsewhere). */
    public ConectorDePrueba declararOtroPeer(IpAddress otro) {
        this.peerFalso = otro;
        return this;
    }

    public List<String> llamadas() {
        return List.copyOf(llamadas);
    }

    @Override
    public Conexion conectar(IpAddress ip, int puerto, Duration timeout) throws IOException {
        llamadas.add(texto(ip) + ":" + puerto);
        Integer local = rutas.get(ip);
        if (local == null) {
            throw new IOException("sin ruta de prueba para esa dirección");
        }
        Socket socket = new Socket(Proxy.NO_PROXY);
        socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), local), (int) timeout.toMillis());
        IpAddress declarada = peerFalso != null ? peerFalso : ip;
        return Conexion.declarada(socket, new InetSocketAddress(InetAddress.getByAddress(declarada.bytes()), puerto));
    }

    static String texto(IpAddress ip) {
        byte[] b = ip.bytes();
        return (b[0] & 0xFF) + "." + (b[1] & 0xFF) + "." + (b[2] & 0xFF) + "." + (b[3] & 0xFF);
    }
}
