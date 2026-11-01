package com.smartaudits.v2.acquisition.capture.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Objects;

/** Operator configuration only. An absent server is allowed while disabled, never for preparation. */
@ConfigurationProperties(prefix = "app.acquisition.url.dns", ignoreUnknownFields = false)
public record ConfiguracionDnsTransporte(String serverIp, @DefaultValue("53") int port,
                                         @DefaultValue("2s") Duration timeout) {
    public ConfiguracionDnsTransporte {
        if (serverIp != null) {
            literal(serverIp);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("app.acquisition.url.dns.port debe estar entre 1 y 65535");
        }
        Objects.requireNonNull(timeout, "app.acquisition.url.dns.timeout");
        if (timeout.compareTo(Duration.ofMillis(1)) < 0 || timeout.compareTo(Duration.ofSeconds(10)) > 0) {
            throw new IllegalArgumentException("app.acquisition.url.dns.timeout debe estar entre 1ms y 10s");
        }
    }

    byte[] direccion() {
        if (serverIp == null) {
            throw new IllegalStateException("Falta app.acquisition.url.dns.server-ip: no hay DNS por defecto");
        }
        return literal(serverIp);
    }

    private static byte[] literal(String texto) {
        try {
            InetAddress direccion;
            if (texto.contains(":")) {
                // The colon AND this ASCII-only whitelist force the JDK's numeric IPv6 path.
                // No hostname, zone ID, brackets, dotted IPv4 tail or URI can reach getByName.
                if (texto.length() > 39 || !texto.matches("[0-9a-fA-F:]+")) {
                    throw invalida();
                }
                direccion = InetAddress.getByName(texto);
                if (!(direccion instanceof Inet6Address)) { // no IPv4-mapped normalization
                    throw invalida();
                }
            } else {
                if (!texto.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
                    throw invalida();
                }
                String[] octetos = texto.split("\\.");
                byte[] bytes = new byte[4];
                for (int i = 0; i < bytes.length; i++) {
                    int valor = Integer.parseInt(octetos[i]);
                    if (valor > 255 || (octetos[i].length() > 1 && octetos[i].startsWith("0"))) {
                        throw invalida();
                    }
                    bytes[i] = (byte) valor;
                }
                direccion = InetAddress.getByAddress(bytes);
            }
            if (direccion.isAnyLocalAddress() || direccion.isMulticastAddress() || texto.equals("255.255.255.255")) {
                throw invalida();
            }
            return direccion.getAddress();
        } catch (UnknownHostException e) {
            throw invalida();
        }
    }

    private static IllegalArgumentException invalida() {
        return new IllegalArgumentException("app.acquisition.url.dns.server-ip debe ser una IP literal unicast "
                + "(IPv4 decimal sin ceros iniciales o IPv6 sin zona ni IPv4 embebida)");
    }
}
