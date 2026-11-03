package com.smartaudits.v2.acquisition.capture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.smartaudits.v2.acquisition.capture.TrabajadoresCapturaDePrueba.esperar;
import static org.assertj.core.api.Assertions.*;

class ControlConcurrenciaCapturaTest {
    private static final CapturaHtml CAPTURA = TransporteLocalDePrueba.captura(
            TransporteLocalDePrueba.destino("https://www.ejemplo.es/"),
            TransporteLocalDePrueba.destino("https://www.ejemplo.es/"), 200, "text/html", "<p>Fixture</p>");

    private static void razon(Throwable error, CapturaException.Motivo motivo) {
        assertThat(error).isInstanceOfSatisfying(CapturaException.class, e -> assertThat(e.motivo()).isEqualTo(motivo));
    }

    @Test
    void successRunsOnTheCallerAndReusesCapacity() {
        var control = new ControlConcurrenciaCaptura(1);
        Thread caller = Thread.currentThread();
        assertThat(control.ejecutar(() -> {
            assertThat(Thread.currentThread()).isSameAs(caller);
            return CAPTURA;
        })).isSameAs(CAPTURA);
        assertThat(control.ejecutar(() -> CAPTURA)).isSameAs(CAPTURA);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 33, Integer.MAX_VALUE})
    void invalidCapacityIsNeverUnlimited(int capacidad) {
        assertThatThrownBy(() -> new ControlConcurrenciaCaptura(capacidad)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nestedAdmissionRejectsWithoutReleasingTheOwnersPermit() {
        var control = new ControlConcurrenciaCaptura(1);
        AtomicInteger callbacks = new AtomicInteger();
        control.ejecutar(() -> {
            for (int i = 0; i < 2; i++) {
                razon(catchThrowable(() -> control.ejecutar(() -> {
                    callbacks.incrementAndGet();
                    return CAPTURA;
                })), CapturaException.Motivo.CAPACIDAD_AGOTADA);
            }
            assertThat(callbacks).hasValue(0);
            return CAPTURA;
        });
        assertThat(control.ejecutar(() -> CAPTURA)).isSameAs(CAPTURA);
    }

    @Test
    void concurrentCallersNeverExceedTheLimitAndRejectedCallsNeverEnter() throws Exception {
        var control = new ControlConcurrenciaCaptura(2);
        var salida = new CountDownLatch(1);
        var iniciar = new CountDownLatch(1);
        var dentro = new CountDownLatch(2);
        var rechazadas = new CountDownLatch(2);
        var activas = new AtomicInteger();
        var maximo = new AtomicInteger();
        var llamadas = new AtomicInteger();
        try (var workers = new TrabajadoresCapturaDePrueba(4)) {
            var futuros = new ArrayList<Future<?>>();
            try {
                for (int i = 0; i < 4; i++) futuros.add(workers.enviar(() -> {
                    esperar(iniciar);
                    try {
                        control.ejecutar(() -> {
                            llamadas.incrementAndGet();
                            maximo.accumulateAndGet(activas.incrementAndGet(), Math::max);
                            dentro.countDown();
                            try { esperar(salida); }
                            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                            finally { activas.decrementAndGet(); }
                            return CAPTURA;
                        });
                    } catch (CapturaException e) {
                        razon(e, CapturaException.Motivo.CAPACIDAD_AGOTADA);
                        rechazadas.countDown();
                    }
                    return null;
                }));
                iniciar.countDown();
                esperar(dentro);
                esperar(rechazadas); // Rejections complete while both owners are still held.
                assertThat(llamadas).hasValue(2);
                assertThat(maximo).hasValue(2);
            } finally { iniciar.countDown(); salida.countDown(); }
            for (Future<?> futuro : futuros) futuro.get(5, TimeUnit.SECONDS);
        }
        assertThat(activas).hasValue(0);
        assertThat(control.ejecutar(() -> CAPTURA)).isSameAs(CAPTURA);
    }

    @Test
    void primaryRuntimeTimeoutAndFatalErrorsReleaseWithoutConversion() {
        var control = new ControlConcurrenciaCaptura(1);
        Throwable[] fallos = {new IllegalStateException("fixture"),
                new CapturaException(CapturaException.Motivo.TRANSPORTE, "timeout", new SocketTimeoutException()),
                new AssertionError("fatal fixture")};
        for (Throwable fallo : fallos) {
            assertThat(catchThrowable(() -> control.ejecutar(() -> {
                if (fallo instanceof Error error) throw error;
                throw (RuntimeException) fallo;
            }))).isSameAs(fallo);
            assertThat(control.ejecutar(() -> CAPTURA)).isSameAs(CAPTURA);
        }
    }

    @Test
    void alreadyInterruptedCallerDoesNotEnterAndKeepsItsFlag() throws Exception {
        var control = new ControlConcurrenciaCaptura(1);
        var llamada = new AtomicBoolean();
        try (var workers = new TrabajadoresCapturaDePrueba(1)) {
            workers.enviar(() -> {
                Thread.currentThread().interrupt();
                razon(catchThrowable(() -> control.ejecutar(() -> { llamada.set(true); return CAPTURA; })),
                        CapturaException.Motivo.CANCELADA);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
                return null;
            }).get(5, TimeUnit.SECONDS);
        }
        assertThat(llamada).isFalse();
        assertThat(control.ejecutar(() -> CAPTURA)).isSameAs(CAPTURA);
    }

    @Test
    void interruptionAtSuccessfulAdmissionReleasesWithoutEnteringTheDelegate() throws Exception {
        var primera = new AtomicBoolean(true);
        var control = new ControlConcurrenciaCaptura(new Semaphore(1) {
            @Override public boolean tryAcquire() {
                boolean adquirida = super.tryAcquire();
                if (adquirida && primera.getAndSet(false)) Thread.currentThread().interrupt();
                return adquirida;
            }
        });
        var llamada = new AtomicBoolean();
        try (var workers = new TrabajadoresCapturaDePrueba(1)) {
            workers.enviar(() -> {
                razon(catchThrowable(() -> control.ejecutar(() -> { llamada.set(true); return CAPTURA; })),
                        CapturaException.Motivo.CANCELADA);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
                return null;
            }).get(5, TimeUnit.SECONDS);
        }
        assertThat(llamada).isFalse();
        assertThat(control.ejecutar(() -> CAPTURA)).isSameAs(CAPTURA);
    }

    @Test
    void normalReturnCannotHideInterruptionAndPrimaryFailuresAreNotMasked() throws Exception {
        var control = new ControlConcurrenciaCaptura(1);
        var primario = new IllegalStateException("primary fixture");
        try (var workers = new TrabajadoresCapturaDePrueba(1)) {
            workers.enviar(() -> {
                razon(catchThrowable(() -> control.ejecutar(() -> {
                    Thread.currentThread().interrupt(); return CAPTURA;
                })), CapturaException.Motivo.CANCELADA);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
                return null;
            }).get(5, TimeUnit.SECONDS);
            workers.enviar(() -> {
                assertThat(catchThrowable(() -> control.ejecutar(() -> {
                    Thread.currentThread().interrupt(); throw primario;
                }))).isSameAs(primario);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
                return null;
            }).get(5, TimeUnit.SECONDS);
        }
        assertThat(control.ejecutar(() -> CAPTURA)).isSameAs(CAPTURA);
    }

    @Test
    void cooperativeInterruptionReleasesOnlyWhenTheOperationExits() throws Exception {
        cancellationCase(true);
    }

    @Test
    void cancellingAFutureCannotReleaseANonCooperativeDelegatesCapacity() throws Exception {
        cancellationCase(false);
    }

    private void cancellationCase(boolean cooperativa) throws Exception {
        var control = new ControlConcurrenciaCaptura(1);
        var entrada = new CountDownLatch(1);
        var interrupcion = new CountDownLatch(1);
        var salida = new CountDownLatch(1);
        var terminada = new CountDownLatch(1);
        try (var workers = new TrabajadoresCapturaDePrueba(1)) {
            Future<?> futuro = workers.enviar(() -> {
                try {
                    return control.ejecutar(() -> {
                        entrada.countDown();
                        boolean interrumpida = false;
                        try {
                            while (true) {
                                try { esperar(salida); break; }
                                catch (InterruptedException e) {
                                    interrumpida = true;
                                    interrupcion.countDown();
                                    if (cooperativa) throw new CapturaException(CapturaException.Motivo.CANCELADA, "fixture", e);
                                }
                            }
                            return CAPTURA;
                        } finally { if (interrumpida) Thread.currentThread().interrupt(); }
                    });
                } finally { terminada.countDown(); }
            });
            try {
                esperar(entrada);
                assertThat(futuro.cancel(true)).isTrue();
                esperar(interrupcion);
                if (!cooperativa) {
                    assertThat(futuro.isCancelled()).isTrue();
                    assertThat(terminada.getCount()).isOne();
                    razon(catchThrowable(() -> control.ejecutar(() -> CAPTURA)), CapturaException.Motivo.CAPACIDAD_AGOTADA);
                }
            } finally { salida.countDown(); }
            esperar(terminada);
        }
        assertThat(control.ejecutar(() -> CAPTURA)).isSameAs(CAPTURA);
    }
}
