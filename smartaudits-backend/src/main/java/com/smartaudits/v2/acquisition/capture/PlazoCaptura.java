package com.smartaudits.v2.acquisition.capture;

import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Absolute monotonic deadline (PV-15). The instant is fixed once at creation on the scale of a
 * monotonic nanosecond source ({@code System::nanoTime} in production, a manual source in tests) and
 * is never extended: {@link #acotado} can only produce an earlier or equal deadline. Comparisons use
 * the difference of two readings, never their order, so a wrapping source stays correct. No wall clock
 * ({@code Clock}, {@code Instant.now}) takes part in any timeout. This class does no I/O.
 *
 * <p>Expired means no time left: the deadline instant itself is already expired.
 */
public final class PlazoCaptura {

    /** Longest representable duration: differences between readings must never overflow. */
    static final long MAX_NANOS = Long.MAX_VALUE / 4;

    private final LongSupplier reloj;
    private final long limite;

    private PlazoCaptura(LongSupplier reloj, long limite) {
        this.reloj = reloj;
        this.limite = limite;
    }

    /** A deadline {@code total} after the current reading of {@code reloj}. */
    public static PlazoCaptura iniciar(Duration total, LongSupplier reloj) {
        Objects.requireNonNull(reloj, "reloj");
        long nanos = nanosPositivos(total);
        return new PlazoCaptura(reloj, reloj.getAsLong() + nanos);
    }

    /** The earlier of this deadline and {@code tope} from now, on the same source. Never later than this one. */
    public PlazoCaptura acotado(Duration tope) {
        long candidato = reloj.getAsLong() + nanosPositivos(tope);
        return candidato - limite < 0 ? new PlazoCaptura(reloj, candidato) : this;
    }

    /** Nanoseconds left; zero or negative once expired. */
    public long restanteNanos() {
        return limite - reloj.getAsLong();
    }

    public boolean vencido() {
        return restanteNanos() <= 0;
    }

    /** JDK wait timeout for the time left: see {@link #milisDeEspera(Duration)}. */
    public int milisDeEspera() throws Vencido {
        return milis(restanteNanos());
    }

    /**
     * JDK wait timeout in milliseconds (connect, read, receive) for the smaller of {@code tope} and the
     * time left. The JDK reads 0 as "no timeout", so the result is never 0: a positive sub-millisecond
     * rest becomes 1 ms, and every rest is rounded up, which adds less than 1 ms to the requested wait so
     * that a timeout caused by this deadline is not signalled before it. That rounding is not a bound on
     * lateness: when the wait actually ends also depends on thread scheduling, timer granularity and
     * JVM pauses. An expired deadline throws {@link Vencido} instead of being extended.
     */
    public int milisDeEspera(Duration tope) throws Vencido {
        return milis(Math.min(restanteNanos(), nanosPositivos(tope)));
    }

    private static int milis(long nanos) throws Vencido {
        if (nanos <= 0) {
            throw new Vencido();
        }
        long ms = nanos / 1_000_000 + (nanos % 1_000_000 == 0 ? 0 : 1);
        return (int) Math.min(Integer.MAX_VALUE, ms);
    }

    /**
     * Positive duration in nanoseconds. Up to {@link #MAX_NANOS} (about 73 years) the value is exact;
     * a longer duration is reduced to {@link #MAX_NANOS}, so saturation can only shorten a request,
     * never extend it. No ArithmeticException: whole seconds are compared before any multiplication.
     */
    static long nanosPositivos(Duration duracion) {
        Objects.requireNonNull(duracion, "duracion");
        if (duracion.isNegative() || duracion.isZero()) {
            throw new IllegalArgumentException("el plazo debe ser positivo");
        }
        long segundos = duracion.getSeconds();
        if (segundos > MAX_NANOS / 1_000_000_000L) {
            return MAX_NANOS;
        }
        // segundos <= 2_305_843_009, so this sum stays far below Long.MAX_VALUE.
        long nanos = segundos * 1_000_000_000L + duracion.getNano();
        return Math.min(MAX_NANOS, nanos);
    }

    @Override
    public String toString() {
        return "PlazoCaptura[restante=" + restanteNanos() + "ns]";
    }

    /**
     * The deadline expired before a wait could start. An I/O timeout (the family of the JDK's own
     * timeout exception), so callers handle it where they handle their I/O timeouts.
     */
    public static final class Vencido extends InterruptedIOException {
        public Vencido() {
            super("plazo agotado");
        }
    }
}
