package com.smartaudits.config;

import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.servlet.handler.WebRequestHandlerInterceptorAdapter;
import org.springframework.web.util.ServletRequestPathUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Conditions and exact exclusion of the configuration alone; the request lifecycle is in FronteraAdquisicionWebHttpTest. */
class FronteraAdquisicionWebConfigTest {

    private final WebApplicationContextRunner web = new WebApplicationContextRunner()
            .withBean(EntityManagerFactory.class, () -> mock(EntityManagerFactory.class))
            .withUserConfiguration(FronteraAdquisicionWebConfig.class);

    /** Exposes what a configurer registered. */
    static final class Registro extends InterceptorRegistry {
        List<Object> registrados() {
            return getInterceptors();
        }
    }

    private static MappedInterceptor registrado(WebMvcConfigurer configurer) {
        var registro = new Registro();
        configurer.addInterceptors(registro);
        assertThat(registro.registrados()).singleElement().isInstanceOf(MappedInterceptor.class);
        return (MappedInterceptor) registro.registrados().get(0);
    }

    private static boolean aplica(MappedInterceptor interceptor, String ruta) {
        var peticion = new MockHttpServletRequest("POST", ruta);
        ServletRequestPathUtils.parseAndCache(peticion);
        return interceptor.matches(peticion);
    }

    @Test
    void theReservedNamespaceIsPinned() {
        // Changing the namespace changes which requests lose the request-scoped EntityManager: review it.
        assertThat(FronteraAdquisicionWebConfig.PREFIJO).isEqualTo("/adquisicion");
        assertThat(FronteraAdquisicionWebConfig.RUTAS).isEqualTo("/adquisicion/**");
    }

    @ParameterizedTest
    @ValueSource(strings = {"spring.application.name=sin-propiedad", "spring.jpa.open-in-view=true"})
    void theInterceptorIsRegisteredByDefaultAndWhenEnabledExcludingOnlyTheNamespace(String propiedad) {
        web.withPropertyValues(propiedad).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(OpenEntityManagerInViewInterceptor.class);
            assertThat(context).hasSingleBean(WebMvcConfigurer.class);
            MappedInterceptor interceptor = registrado(context.getBean(WebMvcConfigurer.class));
            // The standard adapter around the standard interceptor: nothing custom runs on existing endpoints.
            assertThat(interceptor.getInterceptor()).isInstanceOf(WebRequestHandlerInterceptorAdapter.class);
            for (String ordinaria : List.of("/", "/auditorias", "/auditorias/1", "/auditorias/html", "/auditorias-conjuntas/1",
                    "/usuarios", "/admin/historial", "/auth/login", "/error", "/adquisicionx", "/adquisicionx/sonda",
                    "/ADQUISICION/sonda", "/x/adquisicion/sonda", "/adquisicio")) {
                assertThat(aplica(interceptor, ordinaria)).as(ordinaria).isTrue();
            }
            for (String reservada : List.of("/adquisicion", "/adquisicion/", "/adquisicion/sonda",
                    "/adquisicion/sonda/auditar", "/adquisicion/a/b/c")) {
                assertThat(aplica(interceptor, reservada)).as(reservada).isFalse();
            }
        });
    }

    @Test
    void disablingOpenEntityManagerInViewKeepsItsMeaningAndRegistersNothing() {
        web.withPropertyValues("spring.jpa.open-in-view=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(OpenEntityManagerInViewInterceptor.class);
            assertThat(context).doesNotHaveBean(WebMvcConfigurer.class);
            assertThat(context).doesNotHaveBean(FronteraAdquisicionWebConfig.class);
        });
    }

    /**
     * Structural guards, not proofs. This block adds the boundary only: no production code calls
     * ingestion yet, so no endpoint or worker exists. A future caller must be added here on purpose,
     * after reviewing its route and its thread: the PV-13 guard is per thread and cannot see a caller
     * that hands acquisition to another thread while its own request keeps a connection.
     */
    @Test
    void noProductionCodeCallsIngestionAndOpenEntityManagerInViewHasOneOwner() throws IOException {
        List<String> llamanIngesta = new ArrayList<>();
        List<String> registranOsiv = new ArrayList<>();
        List<Path> fuentes;
        try (Stream<Path> files = Files.walk(Paths.get("src/main/java"))) {
            fuentes = files.filter(f -> f.toString().endsWith(".java")).toList();
        }
        assertThat(fuentes).hasSizeGreaterThan(100);
        for (Path fuente : fuentes) {
            String codigo = Files.readString(fuente);
            String nombre = fuente.getFileName().toString();
            if (codigo.contains("service.url.IngestaUrlService")) llamanIngesta.add(nombre);
            if (codigo.contains("OpenEntityManagerInView") && codigo.contains("import org.springframework.orm.jpa.support.")) {
                registranOsiv.add(nombre);
            }
        }
        assertThat(llamanIngesta).as("production callers of IngestaUrlService outside its package").isEmpty();
        try (Stream<Path> paquete = Files.list(Paths.get("src/main/java/com/smartaudits/service/url"))) {
            assertThat(paquete.map(f -> f.getFileName().toString()).toList()).containsExactly("IngestaUrlService.java");
        }
        assertThat(registranOsiv).containsExactly("FronteraAdquisicionWebConfig.java");
        // The behaviour is decided by code, not by a property a deployment could flip unnoticed.
        try (Stream<Path> recursos = Files.list(Paths.get("src/main/resources"))) {
            for (Path recurso : recursos.filter(f -> f.toString().endsWith(".properties")).toList()) {
                assertThat(Files.readString(recurso)).as(recurso.toString()).doesNotContain("open-in-view");
            }
        }
    }

    @Test
    void aNonWebApplicationRegistersNothing() {
        new ApplicationContextRunner()
                .withBean(EntityManagerFactory.class, () -> mock(EntityManagerFactory.class))
                .withUserConfiguration(FronteraAdquisicionWebConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(OpenEntityManagerInViewInterceptor.class);
                    assertThat(context).doesNotHaveBean(WebMvcConfigurer.class);
                });
    }
}
