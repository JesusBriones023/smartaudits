package com.smartaudits.v2.acquisition.capture;

import java.time.Duration;
import java.util.Objects;

/**
 * Acquisition-wide bounds of one real capture (PV-15). {@code total} is the single deadline of a whole
 * {@link TransporteHttpReal#capturar} call: every hop, DNS family, connection and decoding step of that
 * call shares it. It is a code-level setting of the prepared transport, not an operator property.
 */
public record LimitesAdquisicion(Duration total) {

    public static final Duration POR_DEFECTO = Duration.ofSeconds(20);
    public static final Duration MAXIMO = Duration.ofSeconds(60);

    public LimitesAdquisicion {
        Objects.requireNonNull(total, "total");
        if (total.isNegative() || total.isZero() || total.compareTo(MAXIMO) > 0) {
            throw new IllegalArgumentException("el plazo total de adquisición debe ser positivo y no superar 60 s");
        }
    }

    public static LimitesAdquisicion porDefecto() {
        return new LimitesAdquisicion(POR_DEFECTO);
    }
}
