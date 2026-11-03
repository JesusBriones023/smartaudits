package com.smartaudits.v2.acquisition.capture.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Admission only; no setting here can enable acquisition or bypass the capacity bound. */
@ConfigurationProperties(prefix = "app.acquisition.url.execution", ignoreUnknownFields = false)
public record ConfiguracionEjecucionCaptura(@DefaultValue("4") int maxConcurrent) {
    public ConfiguracionEjecucionCaptura {
        if (maxConcurrent < 1 || maxConcurrent > 32) {
            throw new IllegalArgumentException("app.acquisition.url.execution.max-concurrent debe estar entre 1 y 32");
        }
    }
}
