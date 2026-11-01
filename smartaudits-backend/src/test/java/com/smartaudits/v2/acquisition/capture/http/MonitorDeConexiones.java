package com.smartaudits.v2.acquisition.capture.http;

import org.junit.jupiter.api.Assumptions;

import java.security.Permission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Records, for the calling thread only, every name lookup and every connection target the JDK
 * performs, through the hooks the JDK itself calls: {@code checkConnect(host, -1)} before resolving
 * a host name, {@code checkConnect(host, port)} before connecting or sending. It grants everything
 * (it observes, it does not sandbox). It needs a JDK where System.setSecurityManager still works
 * (Java 17, the project's runtime); otherwise the test is skipped, not passed.
 */
public final class MonitorDeConexiones extends SecurityManager {

    record Evento(String host, int puerto) {
        boolean esResolucion() {
            return puerto == -1;
        }
    }

    private final Thread vigilado = Thread.currentThread();
    private final List<Evento> eventos = Collections.synchronizedList(new ArrayList<>());

    public static <T> Observado<T> durante(Callable<T> accion) throws Exception {
        MonitorDeConexiones monitor = new MonitorDeConexiones();
        try {
            System.setSecurityManager(monitor);
        } catch (UnsupportedOperationException e) {
            Assumptions.abort("este JDK no permite instalar un SecurityManager: no se puede observar la resolución");
        }
        try {
            T resultado;
            Throwable error = null;
            try {
                resultado = accion.call();
            } catch (Throwable t) {
                resultado = null;
                error = t;
            }
            return new Observado<>(resultado, error, List.copyOf(monitor.eventos));
        } finally {
            System.setSecurityManager(null);
        }
    }

    public record Observado<T>(T resultado, Throwable error, List<Evento> eventos) {
        List<Evento> resoluciones() {
            return eventos.stream().filter(Evento::esResolucion).toList();
        }

        List<Evento> conexiones() {
            return eventos.stream().filter(e -> !e.esResolucion()).toList();
        }
    }

    @Override
    public void checkConnect(String host, int port) {
        if (Thread.currentThread() == vigilado) {
            eventos.add(new Evento(host, port));
        }
    }

    @Override
    public void checkConnect(String host, int port, Object context) {
        checkConnect(host, port);
    }

    @Override
    public void checkPermission(Permission perm) {
        // observe only
    }

    @Override
    public void checkPermission(Permission perm, Object context) {
        // observe only
    }
}
