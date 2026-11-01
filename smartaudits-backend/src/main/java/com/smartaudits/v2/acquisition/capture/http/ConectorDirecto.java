package com.smartaudits.v2.acquisition.capture.http;

import com.smartaudits.v2.acquisition.destination.IpAddress;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.time.Duration;

/**
 * Production connector: a plain socket with {@link Proxy#NO_PROXY} (no system properties,
 * environment variables or default proxy selection), connected to the literal approved address. The address is built
 * from its bytes, so nothing is resolved. The peer is then read back from the socket.
 */
public final class ConectorDirecto implements Conector {

    @Override
    public Conexion conectar(IpAddress ip, int puerto, Duration timeout) throws IOException {
        Socket socket = new Socket(Proxy.NO_PROXY);
        try {
            socket.connect(new InetSocketAddress(InetAddress.getByAddress(ip.bytes()), puerto),
                    (int) Math.min(Integer.MAX_VALUE, timeout.toMillis()));
            return Conexion.delSocket(socket);
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }
}
