package com.smartaudits.v2.acquisition.capture;

import com.smartaudits.v2.acquisition.capture.bootstrap.ConfiguracionDnsTransporte;
import com.smartaudits.v2.acquisition.capture.bootstrap.ConfiguracionEjecucionCaptura;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Fail-closed wiring of the URL capture. The only transport bean is {@link TransporteHttpNoDisponible}.
 * Asking for the real transport ({@code app.acquisition.url.real-enabled=true}) stops the application
 * at startup instead of silently keeping the refusal: there is no validated transport to enable.
 */
@Configuration
@EnableConfigurationProperties({ConfiguracionDnsTransporte.class, ConfiguracionEjecucionCaptura.class})
public class CapturaUrlConfig {

    public static final String PROPIEDAD = "app.acquisition.url.real-enabled";

    @Bean
    public ControlConcurrenciaCaptura controlConcurrenciaCaptura(ConfiguracionEjecucionCaptura configuracion) {
        return new ControlConcurrenciaCaptura(configuracion.maxConcurrent());
    }

    @Bean
    public TransporteHtml transporteHtml(@Value("${" + PROPIEDAD + ":false}") boolean realEnabled) {
        if (realEnabled) {
            throw new IllegalStateException(PROPIEDAD + "=true no es aplicable: no existe un transporte HTTP validado "
                    + "(D1, D2 y D9 abiertas; R-EGR bloqueante).");
        }
        return new TransporteHttpNoDisponible();
    }
}
