package com.smartaudits.v2.acquisition.capture.http;

/**
 * Scheduling seam of the connection watchdog (package-private: production uses
 * {@link ClienteHttpFijado.VigilanteDelSistema}; tests fire or record alarms deterministically).
 */
interface Vigilante {

    /** Runs {@code accion} once after {@code retrasoNanos}, unless cancelled first. */
    Alarma programar(Runnable accion, long retrasoNanos);

    interface Alarma {
        /** Idempotent; a cancelled alarm leaves no pending task behind. */
        void cancelar();
    }
}
