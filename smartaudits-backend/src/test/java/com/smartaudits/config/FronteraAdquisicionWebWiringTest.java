package com.smartaudits.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartaudits.SmartAuditsApplication;
import com.smartaudits.config.SondaFronteraAdquisicion.Observaciones;
import com.smartaudits.model.Role;
import com.smartaudits.model.Usuario;
import com.smartaudits.repository.AuditoriaRepository;
import com.smartaudits.repository.HistorialAuditoriaRepository;
import com.smartaudits.repository.IncidenciaRepository;
import com.smartaudits.repository.UsuarioRepository;
import com.smartaudits.security.CustomUserDetails;
import com.smartaudits.security.JwtUtil;
import com.smartaudits.v2.acquisition.capture.CapturaUrlConfig;
import com.smartaudits.v2.acquisition.capture.ControlConcurrenciaCaptura;
import com.smartaudits.v2.acquisition.capture.TransporteHtml;
import com.smartaudits.v2.acquisition.capture.TransporteHttpNoDisponible;
import com.smartaudits.v2.acquisition.capture.TransporteHttpReal;
import com.smartaudits.v2.acquisition.capture.dns.ResolvedorDnsDirecto;
import com.smartaudits.v2.acquisition.capture.http.ClienteHttpFijado;
import com.smartaudits.v2.acquisition.destination.HostResolver;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Encoders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.crypto.SecretKey;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.smartaudits.config.SondaFronteraAdquisicion.CABECERA;
import static com.smartaudits.config.SondaFronteraAdquisicion.LIBRE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The whole application with its production transport wiring (nothing mocked): the boundary changes
 * neither which transport exists nor the fail-closed startup gate. Loopback and isolated H2 only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.location=optional:classpath:/acq-osiv-no-config.properties",
        "server.address=127.0.0.1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.hikari.minimum-idle=0", "spring.datasource.hikari.maximum-pool-size=4",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false", "spring.sql.init.mode=never",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "app.admin.bootstrap.enabled=false",
        "debug=false", "logging.level.root=WARN",
        "logging.level.org.springframework=WARN", "logging.level.org.hibernate=WARN"})
@ActiveProfiles("test")
@Import(SondaFronteraAdquisicion.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FronteraAdquisicionWebWiringTest {
    private static final SecretKey KEY = Jwts.SIG.HS256.key().build();
    private static final String DATABASE = "jdbc:h2:mem:acq_osiv_wiring_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DATABASE);
        registry.add("jwt.secret", () -> Encoders.BASE64.encode(KEY.getEncoded()));
    }

    @LocalServerPort int port;
    @Autowired ApplicationContext context;
    @Autowired UsuarioRepository users;
    @Autowired AuditoriaRepository audits;
    @Autowired IncidenciaRepository incidents;
    @Autowired HistorialAuditoriaRepository history;
    @Autowired JwtUtil jwt;
    @Autowired Observaciones observaciones;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    @AfterEach
    void limpiar() {
        history.deleteAllInBatch();
        incidents.deleteAllInBatch();
        audits.deleteAllInBatch();
        users.deleteAllInBatch();
        observaciones.reiniciar();
    }

    @Test
    void theFullApplicationInstallsOnlyTheRefusingTransport() {
        assertThat(context.getBeansOfType(TransporteHtml.class)).hasSize(1);
        assertThat(context.getBean(TransporteHtml.class)).isExactlyInstanceOf(TransporteHttpNoDisponible.class);
        assertThat(context.getBeansOfType(TransporteHttpReal.class)).isEmpty();
        assertThat(context.getBeansOfType(ClienteHttpFijado.class)).isEmpty();
        assertThat(context.getBeansOfType(ResolvedorDnsDirecto.class)).isEmpty();
        assertThat(context.getBeansOfType(HostResolver.class)).isEmpty();
        // Nothing of the prepared real composition (HTTP client, DNS resolver, bootstrap) is a bean.
        for (String nombre : context.getBeanDefinitionNames()) {
            Class<?> tipo = context.getType(nombre);
            String paquete = tipo == null ? "" : tipo.getPackageName();
            assertThat(paquete).as("bean %s", nombre)
                    .doesNotStartWith("com.smartaudits.v2.acquisition.capture.http")
                    .doesNotStartWith("com.smartaudits.v2.acquisition.capture.dns");
            assertThat(tipo == null ? "" : tipo.getSimpleName()).as("bean %s", nombre).isNotEqualTo("PreparacionTransporte");
        }
        assertThat(context.getBeansOfType(ControlConcurrenciaCaptura.class)).hasSize(1);
        assertThat(context.getEnvironment().getProperty(CapturaUrlConfig.PROPIEDAD)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"capturar", "analizar", "auditar"})
    void aRequestThroughTheNamespaceMeetsTheRefusingTransportAndPersistsNothing(String entrada) throws Exception {
        Usuario caller = new Usuario();
        caller.setNombre("Fixture wiring");
        caller.setEmail(UUID.randomUUID() + "@acq-osiv.example.invalid");
        caller.setPassword("unused-test-fixture");
        caller.setRole(Role.CLIENTE);
        caller = users.saveAndFlush(caller);
        String id = UUID.randomUUID().toString();

        HttpResponse<String> http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + LIBRE + "/" + entrada
                                + "?url=https%3A%2F%2Fwww.ejemplo.es%2Flegal"))
                        .timeout(Duration.ofSeconds(30)).header(CABECERA, id)
                        .header("Authorization", "Bearer " + jwt.generateToken(new CustomUserDetails(caller)))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());

        // The guard let the request through (nothing was bound), and the only transport there is refused it.
        assertThat(http.statusCode()).isEqualTo(200);
        JsonNode cuerpo = json.readTree(http.body());
        assertThat(cuerpo.path("resultado").asText()).as("probe body: %s", cuerpo).isEqualTo("RECHAZADA");
        assertThat(cuerpo.path("excepcion").asText()).isEqualTo("CapturaException");
        assertThat(cuerpo.path("motivo").asText()).isEqualTo("NO_AUTORIZADA");
        assertThat(cuerpo.path("mensaje").asText()).isEqualTo(TransporteHttpNoDisponible.MENSAJE);
        assertThat(observaciones.de(id, "handler:antes-de-ingesta").limpio()).isTrue();
        assertThat(observaciones.de(id, "filtro:despues").prestamos()).isZero();
        assertThat(audits.count()).isZero();
        assertThat(incidents.count()).isZero();
        assertThat(history.count()).isZero();
    }

    @Test
    void theFullApplicationStillRefusesToStartWhenTheRealTransportIsRequested() {
        // The same application and isolation as this test class (which started), plus the activation request.
        List<String> argumentos = new ArrayList<>(List.of(
                "--spring.config.location=optional:classpath:/acq-osiv-no-config.properties",
                "--server.address=127.0.0.1", "--server.port=0",
                "--spring.datasource.url=jdbc:h2:mem:acq_osiv_gate_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                "--spring.datasource.driver-class-name=org.h2.Driver",
                "--spring.datasource.username=sa", "--spring.datasource.password=",
                "--spring.jpa.hibernate.ddl-auto=create-drop", "--spring.flyway.enabled=false",
                "--spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
                "--jwt.secret=" + Encoders.BASE64.encode(KEY.getEncoded()),
                "--app.admin.bootstrap.enabled=false", "--spring.main.banner-mode=off",
                "--logging.level.root=WARN", "--logging.level.org.springframework=WARN", "--logging.level.org.hibernate=WARN",
                // The expected startup failure is asserted below; its stack trace is not log-worthy.
                "--logging.level.org.springframework.boot.SpringApplication=OFF",
                "--" + CapturaUrlConfig.PROPIEDAD + "=true"));

        assertThatThrownBy(() -> {
            ConfigurableApplicationContext arrancada = new SpringApplicationBuilder(SmartAuditsApplication.class)
                    .run(argumentos.toArray(String[]::new));
            arrancada.close(); // Never reached unless the gate is broken; do not leave a server running.
        }).rootCause().isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(CapturaUrlConfig.PROPIEDAD + "=true no es aplicable")
                .hasMessageContaining("no existe un transporte HTTP validado");
    }
}
