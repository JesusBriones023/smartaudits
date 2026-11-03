package com.smartaudits.v2.acquisition.capture;

import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * Per-application-instance admission for synchronous capture sections, not whole audits.
 * No queue, worker or transferable permit: only the executing caller releases capacity,
 * after its operation actually exits. Interruption is cooperative, not socket cancellation.
 */
public final class ControlConcurrenciaCaptura {
    private final Semaphore capacidad;

    public ControlConcurrenciaCaptura(int maxConcurrent) {
        if (maxConcurrent < 1 || maxConcurrent > 32) {
            throw new IllegalArgumentException("maxConcurrent debe estar entre 1 y 32");
        }
        capacidad = new Semaphore(maxConcurrent);
    }

    /** Package-private seam to deterministically interrupt at admission in tests. */
    ControlConcurrenciaCaptura(Semaphore capacidad) {
        this.capacidad = Objects.requireNonNull(capacidad, "capacidad");
        if (capacidad.availablePermits() < 1 || capacidad.availablePermits() > 32) {
            throw new IllegalArgumentException("capacidad debe estar entre 1 y 32");
        }
    }

    public CapturaHtml ejecutar(Supplier<CapturaHtml> operacion) {
        Objects.requireNonNull(operacion, "operacion");
        comprobarInterrupcion();
        if (!capacidad.tryAcquire()) {
            throw new CapturaException(CapturaException.Motivo.CAPACIDAD_AGOTADA,
                    "No hay capacidad disponible para otra captura");
        }
        try {
            comprobarInterrupcion();
            CapturaHtml captura = operacion.get();
            // Only normal completion is checked: a primary failure must never be masked.
            comprobarInterrupcion();
            return captura;
        } finally {
            capacidad.release();
        }
    }

    private static void comprobarInterrupcion() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CapturaException(CapturaException.Motivo.CANCELADA, "La captura fue interrumpida");
        }
    }
}
