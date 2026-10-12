package com.smartaudits.v2.time;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** SmartAudits 2.0 normalizes timestamps before persistence, independently of JDBC rounding. */
public final class UtcMicros {
    private UtcMicros() { }

    public static Instant truncate(Instant value) {
        return Objects.requireNonNull(value, "value must not be null").truncatedTo(ChronoUnit.MICROS);
    }

    public static Instant now(Clock clock) {
        return truncate(Objects.requireNonNull(clock, "clock must not be null").instant());
    }
}
