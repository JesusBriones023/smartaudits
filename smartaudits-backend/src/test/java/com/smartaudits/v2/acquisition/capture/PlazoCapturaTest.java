package com.smartaudits.v2.acquisition.capture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Deterministic: every case runs on a manual monotonic source, no wall clock and no waiting. */
class PlazoCapturaTest {
    private final AtomicLong reloj = new AtomicLong(1_000L);

    private PlazoCaptura plazo(Duration total) {
        return PlazoCaptura.iniciar(total, reloj::get);
    }

    @Test
    void theDeadlineIsFixedOnceAndOnlyEverBecomesEarlier() {
        PlazoCaptura total = plazo(Duration.ofSeconds(10));
        reloj.addAndGet(Duration.ofSeconds(4).toNanos());
        assertThat(total.restanteNanos()).isEqualTo(Duration.ofSeconds(6).toNanos());
        // A longer bound than the time left returns the same deadline: nothing is ever extended.
        assertThat(total.acotado(Duration.ofSeconds(30))).isSameAs(total);
        PlazoCaptura corto = total.acotado(Duration.ofSeconds(2));
        assertThat(corto.restanteNanos()).isEqualTo(Duration.ofSeconds(2).toNanos());
        reloj.addAndGet(Duration.ofSeconds(2).toNanos());
        assertThat(corto.vencido()).isTrue();
        assertThat(total.vencido()).isFalse();
        assertThat(total.restanteNanos()).isEqualTo(Duration.ofSeconds(4).toNanos());
    }

    @Test
    void theDeadlineInstantItselfIsExpired() {
        PlazoCaptura p = plazo(Duration.ofMillis(5));
        reloj.addAndGet(Duration.ofMillis(5).toNanos() - 1);
        assertThat(p.vencido()).isFalse();
        assertThat(p.restanteNanos()).isEqualTo(1);
        reloj.incrementAndGet();
        assertThat(p.restanteNanos()).isZero();
        assertThat(p.vencido()).isTrue();
        reloj.addAndGet(Duration.ofDays(1).toNanos());
        assertThat(p.vencido()).isTrue();
    }

    @Test
    void socketTimeoutsRoundUpAreNeverZeroAndAnExpiredDeadlineIsNeverExtended() throws Exception {
        PlazoCaptura p = plazo(Duration.ofNanos(1));
        assertThat(p.milisDeEspera()).isEqualTo(1); // sub-millisecond rest: 1 ms, never 0 (= infinite)
        p = plazo(Duration.ofNanos(999_999));
        assertThat(p.milisDeEspera()).isEqualTo(1);
        p = plazo(Duration.ofMillis(1));
        assertThat(p.milisDeEspera()).isEqualTo(1);
        p = plazo(Duration.ofNanos(1_000_001));
        assertThat(p.milisDeEspera()).isEqualTo(2);
        p = plazo(Duration.ofSeconds(10));
        assertThat(p.milisDeEspera(Duration.ofMillis(700))).isEqualTo(700); // the operation's own limit
        reloj.addAndGet(Duration.ofMillis(9_800).toNanos());
        assertThat(p.milisDeEspera(Duration.ofMillis(700))).isEqualTo(200); // the time left
        reloj.addAndGet(Duration.ofMillis(200).toNanos());
        PlazoCaptura vencido = p;
        assertThatThrownBy(vencido::milisDeEspera).isInstanceOf(PlazoCaptura.Vencido.class);
        assertThatThrownBy(() -> vencido.milisDeEspera(Duration.ofSeconds(5))).isInstanceOf(PlazoCaptura.Vencido.class);
        assertThat(plazo(Duration.ofDays(100)).milisDeEspera()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void aWrappingMonotonicSourceKeepsCorrectDifferences() {
        reloj.set(Long.MAX_VALUE - 5);
        PlazoCaptura p = plazo(Duration.ofSeconds(1));
        assertThat(p.restanteNanos()).isEqualTo(1_000_000_000L);
        reloj.addAndGet(999_999_999L); // wraps past Long.MAX_VALUE
        assertThat(reloj.get()).isNegative();
        assertThat(p.restanteNanos()).isEqualTo(1);
        assertThat(p.vencido()).isFalse();
        reloj.incrementAndGet();
        assertThat(p.vencido()).isTrue();
    }

    @Test
    void durationsMustBePositiveAndHugeOnesSaturateWithoutOverflow() {
        assertThatThrownBy(() -> plazo(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> plazo(Duration.ofNanos(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> plazo(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PlazoCaptura.iniciar(Duration.ofSeconds(1), null)).isInstanceOf(NullPointerException.class);
        PlazoCaptura enorme = plazo(Duration.ofSeconds(Long.MAX_VALUE));
        assertThat(enorme.restanteNanos()).isEqualTo(PlazoCaptura.MAX_NANOS);
        assertThatThrownBy(() -> enorme.acotado(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void durationsUpToTheSaturationLimitAreExactAndLongerOnesAreOnlyEverShortened() {
        long max = PlazoCaptura.MAX_NANOS; // 2_305_843_009_213_693_951 ns
        assertThat(max).isEqualTo(2_305_843_009_213_693_951L);
        // The reported counterexample: whole seconds at the limit's second, below the limit itself.
        assertThat(PlazoCaptura.nanosPositivos(Duration.ofSeconds(2_305_843_009L))).isEqualTo(2_305_843_009_000_000_000L);
        assertThat(PlazoCaptura.nanosPositivos(Duration.ofSeconds(2_305_843_008L))).isEqualTo(2_305_843_008_000_000_000L);
        assertThat(PlazoCaptura.nanosPositivos(Duration.ofSeconds(2_305_843_009L, 213_693_950))).isEqualTo(max - 1);
        assertThat(PlazoCaptura.nanosPositivos(Duration.ofSeconds(2_305_843_009L, 213_693_951))).isEqualTo(max);
        assertThat(PlazoCaptura.nanosPositivos(Duration.ofNanos(max - 1))).isEqualTo(max - 1);
        assertThat(PlazoCaptura.nanosPositivos(Duration.ofNanos(max))).isEqualTo(max);
        // Above the limit: reduced to it, never more.
        assertThat(PlazoCaptura.nanosPositivos(Duration.ofNanos(max + 1))).isEqualTo(max);
        assertThat(PlazoCaptura.nanosPositivos(Duration.ofSeconds(2_305_843_009L, 213_693_952))).isEqualTo(max);
        assertThat(PlazoCaptura.nanosPositivos(Duration.ofSeconds(2_305_843_010L))).isEqualTo(max);
        assertThat(PlazoCaptura.nanosPositivos(Duration.ofSeconds(Long.MAX_VALUE, 999_999_999))).isEqualTo(max);
        // The production range is unaffected and exact.
        assertThat(PlazoCaptura.nanosPositivos(LimitesAdquisicion.POR_DEFECTO)).isEqualTo(20_000_000_000L);
        assertThat(PlazoCaptura.nanosPositivos(LimitesAdquisicion.MAXIMO)).isEqualTo(60_000_000_000L);
        assertThat(PlazoCaptura.nanosPositivos(Duration.ofNanos(1))).isEqualTo(1);
        // A deadline built from the counterexample keeps exactly the requested budget.
        assertThat(plazo(Duration.ofSeconds(2_305_843_009L)).restanteNanos()).isEqualTo(2_305_843_009_000_000_000L);
    }

    @Test
    void theAcquisitionTotalDefaultsTo20SecondsAndAccepts60AtMost() {
        assertThat(LimitesAdquisicion.porDefecto().total()).isEqualTo(Duration.ofSeconds(20));
        assertThat(LimitesAdquisicion.POR_DEFECTO).isEqualTo(Duration.ofSeconds(20));
        assertThat(LimitesAdquisicion.MAXIMO).isEqualTo(Duration.ofSeconds(60));
        assertThat(new LimitesAdquisicion(Duration.ofSeconds(60)).total()).isEqualTo(Duration.ofSeconds(60));
        assertThat(new LimitesAdquisicion(Duration.ofNanos(1)).total()).isEqualTo(Duration.ofNanos(1));
        assertThatThrownBy(() -> new LimitesAdquisicion(null)).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, 60_000_000_001L, Long.MAX_VALUE})
    void invalidOrExcessiveAcquisitionTotalsAreRejected(long nanos) {
        assertThatThrownBy(() -> new LimitesAdquisicion(Duration.ofNanos(nanos)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("60 s");
    }

    @Test
    void theNewReasonIsAppendedAfterTheExistingOnes() {
        assertThat(CapturaException.Motivo.values()).containsExactly(CapturaException.Motivo.NO_AUTORIZADA,
                CapturaException.Motivo.URL_RECHAZADA, CapturaException.Motivo.TRANSPORTE,
                CapturaException.Motivo.RESPUESTA_NO_VALIDA, CapturaException.Motivo.CAPTURA_SIMULADA,
                CapturaException.Motivo.CAPACIDAD_AGOTADA, CapturaException.Motivo.CANCELADA,
                CapturaException.Motivo.PLAZO_AGOTADO);
    }
}
