package com.smartaudits.v2.time;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class UtcMicrosTest {
    @Test
    void truncatesNanoseconds() {
        assertEquals(Instant.parse("2024-02-29T23:58:57.987654Z"),
                UtcMicros.truncate(Instant.parse("2024-02-29T23:58:57.987654321Z")));
    }

    @Test
    void preservesMicrosecondAlignedValue() {
        Instant value = Instant.parse("2024-02-29T23:58:57.987654Z");
        assertEquals(value, UtcMicros.truncate(value));
    }

    @Test
    void truncatesWithoutRoundingUpToNextSecond() {
        assertEquals(Instant.parse("2024-03-31T02:30:00.999999Z"),
                UtcMicros.truncate(Instant.parse("2024-03-31T02:30:00.999999999Z")));
    }

    @Test
    void usesSuppliedClockAndNormalizesItsInstant() {
        Clock clock = Clock.fixed(Instant.parse("2024-03-31T02:30:00.123456789Z"), ZoneOffset.UTC);
        assertEquals(Instant.parse("2024-03-31T02:30:00.123456Z"), UtcMicros.now(clock));
    }

    @Test
    void rejectsNullInstantClearly() {
        assertEquals("value must not be null", assertThrows(NullPointerException.class,
                () -> UtcMicros.truncate(null)).getMessage());
    }

    @Test
    void rejectsNullClockClearly() {
        assertEquals("clock must not be null", assertThrows(NullPointerException.class,
                () -> UtcMicros.now(null)).getMessage());
    }
}
