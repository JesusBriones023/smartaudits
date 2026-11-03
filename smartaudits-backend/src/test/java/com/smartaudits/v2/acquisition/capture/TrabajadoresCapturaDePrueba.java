package com.smartaudits.v2.acquisition.capture;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/** Owned test workers only; gates must be released by the test before close. */
public final class TrabajadoresCapturaDePrueba implements AutoCloseable {
    private final ExecutorService ejecutor;

    public TrabajadoresCapturaDePrueba(int numero) {
        ejecutor = Executors.newFixedThreadPool(numero, tarea -> {
            Thread hilo = new Thread(tarea, "pv14-fixture");
            hilo.setDaemon(true); // A failed termination check must not hang the test JVM.
            return hilo;
        });
    }

    public <T> Future<T> enviar(Callable<T> tarea) {
        return ejecutor.submit(tarea);
    }

    public static void esperar(CountDownLatch senal) throws InterruptedException {
        assertThat(senal.await(5, TimeUnit.SECONDS)).as("fixture signal").isTrue();
    }

    @Override
    public void close() throws InterruptedException {
        for (Runnable pendiente : ejecutor.shutdownNow()) {
            if (pendiente instanceof Future<?> futuro) futuro.cancel(false);
        }
        boolean interrumpido = false;
        boolean terminado = false;
        long fin = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try {
            while (!terminado && fin - System.nanoTime() > 0) {
                try {
                    terminado = ejecutor.awaitTermination(fin - System.nanoTime(), TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    interrumpido = true;
                }
            }
            assertThat(terminado).as("all owned capture workers terminated").isTrue();
            if (interrumpido) throw new InterruptedException("Fixture cleanup was interrupted");
        } finally {
            if (interrumpido) Thread.currentThread().interrupt();
        }
    }
}
