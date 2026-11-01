package com.smartaudits.v2.acquisition.capture.http;

import java.time.Duration;
import java.util.Objects;

/**
 * Bounds of one capture. {@code total} is an absolute deadline per connection (headers and body);
 * {@code lectura} bounds every single read. Body sizes are checked as received and, separately,
 * after decompression. {@code maxRedirecciones} is the hop limit that ADR-0002 section 17 leaves to
 * 4.1-B.
 */
public record LimitesHttp(Duration conexion, Duration lectura, Duration total, int maxCabecera, int maxCabeceras,
                          long maxCuerpoTransferido, long maxCuerpoDescomprimido, int maxRedirecciones) {

    public LimitesHttp {
        Objects.requireNonNull(conexion, "conexion");
        Objects.requireNonNull(lectura, "lectura");
        Objects.requireNonNull(total, "total");
        for (Duration d : new Duration[]{conexion, lectura, total}) {
            if (d.isNegative() || d.isZero()) {
                throw new IllegalArgumentException("los plazos deben ser positivos");
            }
        }
        if (maxCabecera < 256 || maxCabeceras < 1 || maxCuerpoTransferido < 1 || maxCuerpoDescomprimido < 1
                || maxRedirecciones < 0 || maxRedirecciones > 10) {
            throw new IllegalArgumentException("límites HTTP");
        }
    }

    /** 2 MiB fit the 500 000 characters of the capture contract even in 4-byte UTF-8. */
    public static LimitesHttp porDefecto() {
        return new LimitesHttp(Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(15),
                32 * 1024, 100, 2L * 1024 * 1024, 2L * 1024 * 1024, 5);
    }
}
