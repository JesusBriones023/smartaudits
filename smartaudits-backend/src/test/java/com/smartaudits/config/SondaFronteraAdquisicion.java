package com.smartaudits.config;

import com.smartaudits.model.Usuario;
import com.smartaudits.repository.AuditoriaRepository;
import com.smartaudits.repository.UsuarioRepository;
import com.smartaudits.security.CustomUserDetails;
import com.smartaudits.service.url.IngestaUrlService;
import com.smartaudits.v2.acquisition.capture.CapturaException;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Test-only probe of the request lifecycle (ACQ-OSIV-BOUNDARY-01). It lives in test sources, is
 * never component-scanned (nested in a {@code @TestConfiguration}) and exists only in contexts
 * that import it. One handler method is mapped on two routes, so the route is the only difference
 * between a request inside {@link FronteraAdquisicionWebConfig#RUTAS} and an ordinary one.
 */
@TestConfiguration(proxyBeanMethods = false)
class SondaFronteraAdquisicion {

    static final String CABECERA = "X-Sonda";
    /** Inside the reserved namespace: no request-scoped EntityManager. */
    static final String LIBRE = FronteraAdquisicionWebConfig.PREFIJO + "/sonda";
    /** An ordinary route: Open EntityManager in View applies. */
    static final String ORDINARIA = "/sonda-ordinaria";
    static final String TIPO = "Política de Privacidad";

    /** What a thread holds at one point of a request. */
    record Estado(String peticion, String punto, String hilo, boolean transaccion, boolean sincronizacion,
                  List<String> recursos, int prestamos) {
        /** No transaction, no synchronization and no thread-bound resource of any kind. */
        boolean limpio() {
            return !transaccion && !sincronizacion && recursos.isEmpty();
        }
    }

    static final class Observaciones {
        private final Map<String, CompletableFuture<Estado>> porClave = new ConcurrentHashMap<>();
        private final List<String> repetidas = new CopyOnWriteArrayList<>();
        private final ThreadLocal<String> peticion = new ThreadLocal<>();
        private final HikariDataSource pool;

        Observaciones(DataSource dataSource) {
            this.pool = (HikariDataSource) dataSource;
        }

        HikariPoolMXBean metricas() {
            return pool.getHikariPoolMXBean();
        }

        /** Connections currently borrowed from the isolated pool, by any thread. */
        int prestamos() {
            return metricas().getActiveConnections();
        }

        private CompletableFuture<Estado> futuro(String id, String punto) {
            return porClave.computeIfAbsent(id + "|" + punto, clave -> new CompletableFuture<>());
        }

        void registrar(String punto) {
            String id = String.valueOf(peticion.get());
            List<String> recursos = TransactionSynchronizationManager.getResourceMap().values().stream()
                    .map(recurso -> recurso.getClass().getSimpleName()).sorted().toList();
            Estado estado = new Estado(id, punto, Thread.currentThread().getName(),
                    TransactionSynchronizationManager.isActualTransactionActive(),
                    TransactionSynchronizationManager.isSynchronizationActive(), recursos, prestamos());
            if (!futuro(id, punto).complete(estado)) {
                repetidas.add(id + "|" + punto);
            }
        }

        boolean existe(String id, String punto) {
            return futuro(id, punto).isDone();
        }

        /** The observation of that point, waiting a bounded time for the request thread to record it. */
        Estado de(String id, String punto) {
            try {
                return futuro(id, punto).get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new AssertionError("No observation " + punto + " for request " + id, e);
            }
        }

        List<String> repetidas() {
            return List.copyOf(repetidas);
        }

        boolean vacias() {
            return porClave.values().stream().noneMatch(CompletableFuture::isDone);
        }

        /** Runs the work as request {@code id} on the calling thread (a non-web caller). */
        <T> T enPeticion(String id, Supplier<T> trabajo) {
            peticion.set(id);
            try {
                return trabajo.get();
            } finally {
                peticion.remove();
            }
        }

        void reiniciar() {
            porClave.clear();
            repetidas.clear();
        }
    }

    @Bean
    Observaciones observaciones(DataSource dataSource) {
        return new Observaciones(dataSource);
    }

    /** Inside the security chain (-100) and before the DispatcherServlet: a rejected caller never gets here. */
    @Bean
    FilterRegistrationBean<Filter> filtroSonda(Observaciones observaciones) {
        Filter filtro = (request, response, chain) -> {
            observaciones.peticion.set(((HttpServletRequest) request).getHeader(CABECERA));
            observaciones.registrar("filtro:antes");
            try {
                chain.doFilter(request, response);
            } finally {
                observaciones.registrar("filtro:despues");
                observaciones.peticion.remove();
            }
        };
        var registro = new FilterRegistrationBean<>(filtro);
        registro.setOrder(-99);
        return registro;
    }

    @RestController
    static class Controlador {
        private final Observaciones observaciones;
        private final IngestaUrlService ingesta;
        private final UsuarioRepository usuarios;
        private final AuditoriaRepository auditorias;
        private final PlatformTransactionManager transacciones;
        private final EntityManagerFactory fabrica;
        private final DataSource dataSource;

        Controlador(Observaciones observaciones, IngestaUrlService ingesta, UsuarioRepository usuarios,
                    AuditoriaRepository auditorias, PlatformTransactionManager transacciones,
                    EntityManagerFactory fabrica, DataSource dataSource) {
            this.observaciones = observaciones;
            this.ingesta = ingesta;
            this.usuarios = usuarios;
            this.auditorias = auditorias;
            this.transacciones = transacciones;
            this.fabrica = fabrica;
            this.dataSource = dataSource;
        }

        @PostMapping({LIBRE + "/{entrada}", ORDINARIA + "/{entrada}"})
        public Map<String, Object> ejecutar(@PathVariable String entrada, @RequestParam String url,
                                            @RequestParam(defaultValue = "limpio") String modo,
                                            @RequestParam(defaultValue = "192.0.2.44") String ip,
                                            @AuthenticationPrincipal CustomUserDetails principal) {
            observaciones.registrar("handler:entrada");
            Map<String, Object> respuesta = new LinkedHashMap<>();
            try {
                respuesta.putAll(conModo(modo, principal.getUsuario(), () -> {
                    observaciones.registrar("handler:antes-de-ingesta");
                    return ingerir(entrada, url, principal.getUsuario(), ip);
                }));
                respuesta.put("resultado", "OK");
            } catch (RuntimeException e) {
                respuesta.put("resultado", "RECHAZADA");
                respuesta.put("excepcion", e.getClass().getSimpleName());
                respuesta.put("motivo", e instanceof CapturaException captura ? captura.motivo().name() : null);
                respuesta.put("mensaje", e.getMessage());
            }
            observaciones.registrar("handler:salida");
            return respuesta;
        }

        /** Method security must keep working inside the namespace. */
        @GetMapping(LIBRE + "/solo-admin")
        @PreAuthorize("hasRole('ADMIN')")
        public Map<String, Object> soloAdmin() {
            observaciones.registrar("handler:solo-admin");
            return Map.of("resultado", "OK");
        }

        private Map<String, Object> ingerir(String entrada, String url, Usuario usuario, String ip) {
            Map<String, Object> datos = new LinkedHashMap<>();
            switch (entrada) {
                case "capturar" -> datos.put("estadoHttp", ingesta.capturar(url).estadoHttp());
                case "analizar" -> datos.put("texto", ingesta.analizar(url, TIPO).texto());
                case "auditar" -> {
                    var creada = ingesta.auditar(url, "Sonda", TIPO, usuario, ip);
                    datos.put("id", creada.getId());
                    datos.put("tipoFuente", String.valueOf(creada.getTipoFuente()));
                    datos.put("urlOpcional", creada.getUrlOpcional());
                    datos.put("usuarioId", creada.getUsuarioId());
                }
                default -> throw new IllegalArgumentException(entrada);
            }
            return datos;
        }

        /** How the handler prepares its own thread before calling ingestion. It releases only what it binds. */
        private Map<String, Object> conModo(String modo, Usuario usuario, Supplier<Map<String, Object>> ingestion) {
            switch (modo) {
                case "limpio":
                    return ingestion.get();
                case "trabajo-previo":
                    // Typical initial work of a handler: a CRUD read, a declared query and a write transaction.
                    usuarios.findById(usuario.getId()).orElseThrow();
                    auditorias.countByUsuarioIdAndFechaCreacionGreaterThanEqual(usuario.getId(),
                            LocalDateTime.now().minusDays(1));
                    new TransactionTemplate(transacciones).executeWithoutResult(estado -> auditorias.count());
                    return ingestion.get();
                case "transaccion":
                case "transaccion-lectura":
                    var plantilla = new TransactionTemplate(transacciones);
                    plantilla.setReadOnly(modo.equals("transaccion-lectura"));
                    return plantilla.execute(estado -> ingestion.get());
                case "entity-manager":
                    if (TransactionSynchronizationManager.hasResource(fabrica)) {
                        return ingestion.get();
                    }
                    EntityManager propio = fabrica.createEntityManager();
                    TransactionSynchronizationManager.bindResource(fabrica, new EntityManagerHolder(propio));
                    try {
                        return ingestion.get();
                    } finally {
                        TransactionSynchronizationManager.unbindResource(fabrica);
                        propio.close();
                    }
                case "conexion":
                    try (Connection conexion = dataSource.getConnection()) {
                        TransactionSynchronizationManager.bindResource(dataSource, new ConnectionHolder(conexion));
                        try {
                            return ingestion.get();
                        } finally {
                            TransactionSynchronizationManager.unbindResource(dataSource);
                        }
                    } catch (java.sql.SQLException e) {
                        throw new AssertionError(e);
                    }
                case "sincronizacion":
                    TransactionSynchronizationManager.initSynchronization();
                    try {
                        return ingestion.get();
                    } finally {
                        TransactionSynchronizationManager.clearSynchronization();
                    }
                default:
                    throw new IllegalArgumentException(modo);
            }
        }
    }
}
