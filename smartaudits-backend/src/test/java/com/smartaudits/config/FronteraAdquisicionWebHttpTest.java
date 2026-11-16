package com.smartaudits.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartaudits.config.SondaFronteraAdquisicion.Estado;
import com.smartaudits.config.SondaFronteraAdquisicion.Observaciones;
import com.smartaudits.model.Auditoria;
import com.smartaudits.model.Role;
import com.smartaudits.model.TipoFuente;
import com.smartaudits.model.Usuario;
import com.smartaudits.repository.AuditoriaRepository;
import com.smartaudits.repository.HistorialAuditoriaRepository;
import com.smartaudits.repository.IncidenciaRepository;
import com.smartaudits.repository.UsuarioRepository;
import com.smartaudits.security.CustomUserDetails;
import com.smartaudits.security.JwtUtil;
import com.smartaudits.service.AuditQuotaService;
import com.smartaudits.service.url.IngestaUrlService;
import com.smartaudits.v2.acquisition.capture.CapturaException;
import com.smartaudits.v2.acquisition.capture.CapturaException.Motivo;
import com.smartaudits.v2.acquisition.capture.CapturaHtml;
import com.smartaudits.v2.acquisition.capture.CapturaRealDePrueba;
import com.smartaudits.v2.acquisition.capture.TransporteHtml;
import com.smartaudits.v2.acquisition.capture.TransporteLocalDePrueba;
import com.smartaudits.v2.acquisition.destination.DestinationUrl;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Encoders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.server.PathContainer;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.handler.WebRequestHandlerInterceptorAdapter;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import javax.crypto.SecretKey;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.smartaudits.config.SondaFronteraAdquisicion.CABECERA;
import static com.smartaudits.config.SondaFronteraAdquisicion.LIBRE;
import static com.smartaudits.config.SondaFronteraAdquisicion.ORDINARIA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ACQ-OSIV-BOUNDARY-01 through the real request lifecycle: embedded Tomcat on loopback, the real
 * security chain and JWT filter, the real DispatcherServlet and interceptors, real services and
 * repositories, and an isolated H2 database behind a Hikari pool. Only the transport is replaced,
 * so every capture is synthetic and no request leaves 127.0.0.1.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.location=optional:classpath:/acq-osiv-no-config.properties",
        "server.address=127.0.0.1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.hikari.minimum-idle=0", "spring.datasource.hikari.maximum-pool-size=4",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false", "spring.sql.init.mode=never",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "app.admin.bootstrap.enabled=false", "app.audit.daily-limit=1",
        "app.acquisition.url.execution.max-concurrent=1",
        "debug=false", "logging.level.root=WARN",
        "logging.level.org.springframework=WARN", "logging.level.org.hibernate=WARN"})
@ActiveProfiles("test")
@Import(SondaFronteraAdquisicion.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FronteraAdquisicionWebHttpTest {
    private static final SecretKey KEY = Jwts.SIG.HS256.key().build();
    private static final String DATABASE = "jdbc:h2:mem:acq_osiv_http_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    static final String URL = "https://www.ejemplo.es/legal";
    static final String FINAL_URL = "https://legal.ejemplo.es/v2/privacidad";
    static final String HTML = "<h1>Política de privacidad</h1><p>Responsable del tratamiento: Ejemplo S.L.</p>";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DATABASE);
        registry.add("jwt.secret", () -> Encoders.BASE64.encode(KEY.getEncoded()));
        registry.add("jwt.issuer", () -> "acq-osiv-test");
        registry.add("jwt.audience", () -> "acq-osiv-test-api");
    }

    @LocalServerPort int port;
    @Autowired ApplicationContext context;
    @Autowired UsuarioRepository users;
    @Autowired AuditoriaRepository audits;
    @Autowired IncidenciaRepository incidents;
    @Autowired HistorialAuditoriaRepository history;
    @Autowired JwtUtil jwt;
    @Autowired Observaciones observaciones;
    @Autowired IngestaUrlService ingesta;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
    @MockBean TransporteHtml transporte;
    @SpyBean AuditQuotaService cuota;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<String> eventos = new CopyOnWriteArrayList<>();

    /** One HTTP exchange with the probe: its request id, status and JSON body. */
    record Respuesta(String id, int estado, JsonNode cuerpo) {
        String texto(String campo) {
            return cuerpo.path(campo).isNull() || cuerpo.path(campo).isMissingNode() ? null : cuerpo.path(campo).asText();
        }
    }

    @BeforeEach
    void setup() {
        borrarFilas();
        reset(transporte, cuota);
        observaciones.reiniciar();
        eventos.clear();
        // Default: the trusted synthetic fixture, recorded on the thread that asked for the capture.
        when(transporte.capturar(any())).thenAnswer(invocation -> {
            observaciones.registrar("transporte");
            eventos.add("transporte");
            return real(invocation.getArgument(0));
        });
    }

    @AfterEach
    void cleanup() {
        borrarFilas();
        assertThat(observaciones.prestamos()).as("no connection stays borrowed after a test").isZero();
        assertThat(observaciones.repetidas()).as("every lifecycle point was observed at most once per request").isEmpty();
    }

    private void borrarFilas() {
        history.deleteAllInBatch();
        incidents.deleteAllInBatch();
        audits.deleteAllInBatch();
        users.deleteAllInBatch();
    }

    private static CapturaHtml real(DestinationUrl solicitada) {
        return CapturaRealDePrueba.crear(solicitada, TransporteLocalDePrueba.destino(FINAL_URL), HTML);
    }

    Usuario usuario(Role role) {
        Usuario u = new Usuario();
        u.setNombre("Fixture " + role + " " + UUID.randomUUID());
        u.setEmail(UUID.randomUUID() + "@acq-osiv.example.invalid");
        u.setPassword("unused-test-fixture");
        u.setRole(role);
        return users.saveAndFlush(u);
    }

    String token(Usuario u) {
        return jwt.generateToken(new CustomUserDetails(u));
    }

    private HttpRequest peticion(String metodo, String ruta, String token, String id) {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + ruta))
                .timeout(Duration.ofSeconds(30)).header(CABECERA, id);
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return builder.method(metodo, HttpRequest.BodyPublishers.noBody()).build();
    }

    private Respuesta respuesta(String id, HttpResponse<String> http) throws Exception {
        // The body can arrive before the request thread has left the DispatcherServlet: wait for that
        // real event, so every later assertion sees a finished request. A caller stopped by the
        // security chain never reached the probe filter and has nothing to wait for.
        if (observaciones.existe(id, "filtro:antes")) {
            observaciones.de(id, "filtro:despues");
        }
        String body = http.body();
        return new Respuesta(id, http.statusCode(), body == null || body.isBlank() ? json.nullNode() : json.readTree(body));
    }

    Respuesta enviar(String metodo, String ruta, String token) throws Exception {
        String id = UUID.randomUUID().toString();
        return respuesta(id, client.send(peticion(metodo, ruta, token, id), HttpResponse.BodyHandlers.ofString()));
    }

    static String ruta(String base, String entrada, String url, String modo) {
        return base + "/" + entrada + "?url=" + URLEncoder.encode(url, StandardCharsets.UTF_8) + "&modo=" + modo;
    }

    Respuesta sonda(String base, String entrada, String modo, String token) throws Exception {
        return enviar("POST", ruta(base, entrada, URL, modo), token);
    }

    private void sinFilas() {
        assertThat(audits.count()).isZero();
        assertThat(incidents.count()).isZero();
        assertThat(history.count()).isZero();
    }

    private void rechazada(Respuesta r, Class<?> excepcion) {
        assertThat(r.estado()).isEqualTo(200);
        assertThat(r.texto("resultado")).as("probe body: %s", r.cuerpo()).isEqualTo("RECHAZADA");
        assertThat(r.texto("excepcion")).isEqualTo(excepcion.getSimpleName());
    }

    private void rechazada(Respuesta r, Motivo motivo) {
        rechazada(r, CapturaException.class);
        assertThat(r.texto("motivo")).isEqualTo(motivo.name());
    }

    private void aceptada(Respuesta r) {
        assertThat(r.estado()).isEqualTo(200);
        assertThat(r.texto("resultado")).as("probe body: %s", r.cuerpo()).isEqualTo("OK");
    }

    // ---- 6, 7, 8: the boundary reaches the transport with nothing bound and nothing borrowed ----

    @ParameterizedTest
    @CsvSource({"capturar,limpio", "analizar,limpio", "auditar,limpio",
            "capturar,trabajo-previo", "analizar,trabajo-previo", "auditar,trabajo-previo"})
    void theAcquisitionRouteReachesTheTransportOnTheRequestThreadWithNothingBoundOrBorrowed(String entrada, String modo)
            throws Exception {
        Respuesta r = sonda(LIBRE, entrada, modo, token(usuario(Role.CLIENTE)));

        aceptada(r);
        verify(transporte, times(1)).capturar(any());
        Estado antesDelDispatcher = observaciones.de(r.id(), "filtro:antes");
        Estado entradaHandler = observaciones.de(r.id(), "handler:entrada");
        Estado antesDeIngesta = observaciones.de(r.id(), "handler:antes-de-ingesta");
        Estado enTransporte = observaciones.de(r.id(), "transporte");
        Estado despues = observaciones.de(r.id(), "filtro:despues");
        // Authentication loaded the user in the security chain and released everything.
        assertThat(antesDelDispatcher.limpio()).as("%s", antesDelDispatcher).isTrue();
        assertThat(antesDelDispatcher.prestamos()).isZero();
        // No request-scoped EntityManager was opened for this route.
        assertThat(entradaHandler.limpio()).as("%s", entradaHandler).isTrue();
        assertThat(entradaHandler.prestamos()).isZero();
        // The handler's own initial database work was self-contained.
        assertThat(antesDeIngesta.limpio()).as("%s", antesDeIngesta).isTrue();
        assertThat(antesDeIngesta.prestamos()).isZero();
        // The transport ran on the request thread itself, with nothing bound and no pool loan.
        assertThat(enTransporte.limpio()).as("%s", enTransporte).isTrue();
        assertThat(enTransporte.prestamos()).isZero();
        assertThat(enTransporte.hilo()).isEqualTo(entradaHandler.hilo()).startsWith("http-nio-");
        assertThat(despues.limpio()).as("%s", despues).isTrue();
        assertThat(despues.prestamos()).isZero();
    }

    // ---- 14, 15: the trusted fixture persists through the unchanged transaction ----

    @Test
    void aTrustedFixtureCapturePersistsACrawlerAuditOwnedByTheAuthenticatedCaller() throws Exception {
        Usuario caller = usuario(Role.CLIENTE);
        usuario(Role.CLIENTE); // another account that must stay untouched
        doAnswer(invocation -> {
            eventos.add("cuota-previa tx=" + TransactionSynchronizationManager.isActualTransactionActive());
            return invocation.callRealMethod();
        }).when(cuota).precomprobarPuedeCrear(any());
        doAnswer(invocation -> {
            eventos.add("cuota-final tx=" + TransactionSynchronizationManager.isActualTransactionActive());
            return invocation.callRealMethod();
        }).when(cuota).verificarPuedeCrear(any());

        Respuesta r = enviar("POST", ruta(LIBRE, "auditar", URL, "limpio") + "&ip=192.0.2.77", token(caller));

        aceptada(r);
        assertThat(r.texto("tipoFuente")).isEqualTo("CRAWLER");
        assertThat(r.texto("urlOpcional")).isEqualTo(FINAL_URL);
        assertThat(r.cuerpo().path("usuarioId").asLong()).isEqualTo(caller.getId());
        // Advisory check outside a transaction, then the capture, then the locked check inside the transaction.
        assertThat(eventos).containsExactly("cuota-previa tx=false", "transporte", "cuota-final tx=true");
        Auditoria stored = audits.findAll().get(0);
        assertThat(audits.count()).isOne();
        assertThat(stored.getId()).isEqualTo(r.cuerpo().path("id").asLong());
        assertThat(stored.getTipoFuente()).isEqualTo(TipoFuente.CRAWLER);
        assertThat(stored.getUrlOpcional()).isEqualTo(FINAL_URL);
        assertThat(stored.getTextoOriginal()).contains("Responsable del tratamiento").doesNotContain("<");
        assertThat(incidents.count()).isPositive();
        assertThat(history.count()).isOne();
        assertThat(history.findAll().get(0).getAccion()).isEqualTo("CREACION");
        assertThat(history.findAll().get(0).getIpAcceso()).isEqualTo("192.0.2.77");
        assertThat(audits.countByUsuarioIdAndFechaCreacionGreaterThanEqual(caller.getId(), LocalDateTime.now().minusDays(1)))
                .as("owned by the authenticated caller").isOne();
        // The daily limit is 1: the same caller is now refused by the advisory check, before any capture.
        Respuesta segunda = sonda(LIBRE, "auditar", "limpio", token(caller));
        rechazada(segunda, com.smartaudits.service.AuditQuotaExceededException.class);
        verify(transporte, times(1)).capturar(any());
        assertThat(audits.count()).isOne();
    }

    // ---- 7, 16: observed from outside while a request is parked inside acquisition ----

    @Test
    void whileARequestIsParkedInAcquisitionNothingIsBorrowedAndASecondRequestIsRefusedWithoutSideEffects() throws Exception {
        var dentro = new CountDownLatch(1);
        var salir = new CountDownLatch(1);
        doAnswer(invocation -> {
            observaciones.registrar("transporte");
            dentro.countDown();
            if (!salir.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("fixture gate was never opened");
            return real(invocation.getArgument(0));
        }).when(transporte).capturar(any());
        String primera = UUID.randomUUID().toString();
        CompletableFuture<HttpResponse<String>> enCurso = client.sendAsync(
                peticion("POST", ruta(LIBRE, "auditar", URL, "trabajo-previo"), token(usuario(Role.CLIENTE)), primera),
                HttpResponse.BodyHandlers.ofString());
        try {
            assertThat(dentro.await(15, TimeUnit.SECONDS)).as("the first request reached the transport").isTrue();
            // The request thread is blocked in "the network" right now.
            assertThat(observaciones.prestamos()).as("pool loans while a capture is in flight").isZero();
            assertThat(observaciones.metricas().getThreadsAwaitingConnection()).isZero();
            assertThat(observaciones.de(primera, "transporte").limpio()).isTrue();
            assertThat(observaciones.existe(primera, "filtro:despues")).as("the request is still in flight").isFalse();

            // Capacity is 1 and its only permit is held by the parked request.
            Respuesta saturada = sonda(LIBRE, "auditar", "limpio", token(usuario(Role.CLIENTE)));
            rechazada(saturada, Motivo.CAPACIDAD_AGOTADA);
            assertThat(observaciones.existe(saturada.id(), "transporte")).isFalse();
            verify(transporte, times(1)).capturar(any());
            verify(cuota, never()).verificarPuedeCrear(any());
            sinFilas();
            assertThat(observaciones.prestamos()).isZero();
        } finally {
            salir.countDown();
        }
        aceptada(respuesta(primera, enCurso.get(30, TimeUnit.SECONDS)));
        assertThat(audits.count()).isOne();
        // The permit was released after the success: the next request is admitted and persists.
        aceptada(sonda(LIBRE, "auditar", "limpio", token(usuario(Role.CLIENTE))));
        assertThat(audits.count()).isEqualTo(2);
    }

    @Test
    void admissionIsReleasedAfterAFailedAcquisition() throws Exception {
        doThrow(new CapturaException(Motivo.TRANSPORTE, "fixture failure")).when(transporte).capturar(any());
        rechazada(sonda(LIBRE, "auditar", "limpio", token(usuario(Role.CLIENTE))), Motivo.TRANSPORTE);
        sinFilas();

        reset(transporte);
        doAnswer(invocation -> real(invocation.getArgument(0))).when(transporte).capturar(any());
        aceptada(sonda(LIBRE, "auditar", "limpio", token(usuario(Role.CLIENTE))));
        assertThat(audits.count()).isOne();
    }

    // ---- 5: an ordinary request keeps Open EntityManager in View and stays fail-closed ----

    @ParameterizedTest
    @CsvSource({"capturar,limpio", "analizar,limpio", "auditar,limpio",
            "capturar,trabajo-previo", "analizar,trabajo-previo", "auditar,trabajo-previo"})
    void anOrdinaryOsivBoundRequestRemainsFailClosed(String entrada, String modo) throws Exception {
        Respuesta r = sonda(ORDINARIA, entrada, modo, token(usuario(Role.CLIENTE)));

        rechazada(r, IllegalStateException.class);
        assertThat(r.texto("mensaje")).contains("OSIV");
        Estado entradaHandler = observaciones.de(r.id(), "handler:entrada");
        Estado antesDeIngesta = observaciones.de(r.id(), "handler:antes-de-ingesta");
        assertThat(observaciones.de(r.id(), "filtro:antes").limpio()).isTrue();
        assertThat(entradaHandler.recursos()).containsExactly("EntityManagerHolder");
        assertThat(entradaHandler.prestamos()).as("the request-scoped EntityManager has no connection yet").isZero();
        assertThat(antesDeIngesta.recursos()).containsExactly("EntityManagerHolder");
        // The exact problem: after its first query the request-scoped EntityManager keeps its connection.
        assertThat(antesDeIngesta.prestamos()).isEqualTo(modo.equals("trabajo-previo") ? 1 : 0);
        assertThat(observaciones.existe(r.id(), "transporte")).isFalse();
        verify(transporte, never()).capturar(any());
        verify(cuota, never()).precomprobarPuedeCrear(any());
        verify(cuota, never()).verificarPuedeCrear(any());
        sinFilas();
        // Its owner (the interceptor) closed it when the request ended; the guard touched nothing.
        Estado despues = observaciones.de(r.id(), "filtro:despues");
        assertThat(despues.limpio()).as("%s", despues).isTrue();
        assertThat(despues.prestamos()).isZero();
    }

    // ---- 1-4: the route does not suspend the PV-13 guard ----

    @ParameterizedTest
    @CsvSource({"transaccion,capturar", "transaccion,analizar", "transaccion,auditar",
            "transaccion-lectura,capturar", "transaccion-lectura,analizar", "transaccion-lectura,auditar",
            "entity-manager,capturar", "entity-manager,analizar", "entity-manager,auditar",
            "conexion,capturar", "conexion,analizar", "conexion,auditar",
            "sincronizacion,capturar", "sincronizacion,analizar", "sincronizacion,auditar"})
    void aHandlerInsideTheNamespaceThatHoldsResourcesIsStillRejected(String modo, String entrada) throws Exception {
        Respuesta r = sonda(LIBRE, entrada, modo, token(usuario(Role.CLIENTE)));

        rechazada(r, IllegalStateException.class);
        assertThat(r.texto("mensaje")).contains(modo.startsWith("transaccion") ? "fuera de una transacción" : "JPA/JDBC");
        assertThat(observaciones.de(r.id(), "handler:entrada").limpio()).as("the route itself bound nothing").isTrue();
        Estado antesDeIngesta = observaciones.de(r.id(), "handler:antes-de-ingesta");
        assertThat(antesDeIngesta.limpio()).as("%s", antesDeIngesta).isFalse();
        assertThat(observaciones.existe(r.id(), "transporte")).isFalse();
        verify(transporte, never()).capturar(any());
        verify(cuota, never()).precomprobarPuedeCrear(any());
        sinFilas();
        assertThat(observaciones.de(r.id(), "filtro:despues").limpio()).as("the handler released what it bound").isTrue();
    }

    // ---- 10: authentication and authorization ----

    @ParameterizedTest
    @ValueSource(strings = {"sin-token", "malformado", "otra-clave", "caducado", "inactivo", "version-antigua"})
    void aCallerThatIsNotAuthenticatedNeverReachesTheNamespace(String caso) throws Exception {
        Usuario u = usuario(Role.CLIENTE);
        String token = switch (caso) {
            case "sin-token" -> null;
            case "malformado" -> "malformed-token";
            case "otra-clave" -> firmado(u, Jwts.SIG.HS256.key().build(), new Date(System.currentTimeMillis() + 60_000));
            case "caducado" -> firmado(u, KEY, new Date(System.currentTimeMillis() - 60_000));
            default -> token(u);
        };
        if (caso.equals("inactivo")) {
            u.setActivo(false);
            users.saveAndFlush(u);
        }
        if (caso.equals("version-antigua")) {
            u.setTokenVersion(u.getTokenVersion() + 1);
            users.saveAndFlush(u);
        }

        for (String ruta : List.of(ruta(LIBRE, "auditar", URL, "limpio"), ruta(ORDINARIA, "auditar", URL, "limpio"))) {
            Respuesta r = enviar("POST", ruta, token);
            assertThat(r.estado()).as(ruta).isEqualTo(401);
            assertThat(r.cuerpo().isNull()).isTrue();
            assertThat(observaciones.existe(r.id(), "filtro:antes")).as("stopped inside the security chain").isFalse();
        }
        assertThat(observaciones.vacias()).isTrue();
        verify(transporte, never()).capturar(any());
        sinFilas();
    }

    private String firmado(Usuario u, SecretKey clave, Date caducidad) {
        return Jwts.builder().issuer("acq-osiv-test").audience().add("acq-osiv-test-api").and()
                .subject(u.getId().toString()).claim("userId", u.getId())
                .claim("tokenVersion", u.getTokenVersion()).expiration(caducidad)
                .signWith(clave, Jwts.SIG.HS256).compact();
    }

    @Test
    void methodSecurityStillAppliesInsideTheNamespace() throws Exception {
        Respuesta cliente = enviar("GET", LIBRE + "/solo-admin", token(usuario(Role.CLIENTE)));
        assertThat(cliente.estado()).isEqualTo(403);
        assertThat(observaciones.existe(cliente.id(), "handler:solo-admin")).isFalse();

        Respuesta admin = enviar("GET", LIBRE + "/solo-admin", token(usuario(Role.ADMIN)));
        aceptada(admin);
        assertThat(observaciones.de(admin.id(), "handler:solo-admin").limpio()).isTrue();
    }

    @Test
    void anUnmappedPathInsideTheNamespaceIsNotAnEndpoint() throws Exception {
        String dentro = FronteraAdquisicionWebConfig.PREFIJO + "/auditorias";
        String fuera = "/ruta-inexistente/auditorias";
        String token = token(usuario(Role.CLIENTE));
        for (String metodo : List.of("GET", "POST")) {
            assertThat(enviar(metodo, dentro, null).estado()).isEqualTo(401);
            // Authenticated, it answers exactly like any other path nobody serves.
            int estado = enviar(metodo, dentro, token).estado();
            assertThat(estado).as("%s %s", metodo, dentro).isGreaterThanOrEqualTo(400)
                    .isEqualTo(enviar(metodo, fuera, token).estado());
        }
        verify(transporte, never()).capturar(any());
        sinFilas();
    }

    // ---- 11: an invalid URL never reaches quota, admission or transport ----

    @ParameterizedTest
    @ValueSource(strings = {"https://127.0.0.1/", "http://localhost/", "https://[::1]/", "ftp://www.ejemplo.es/",
            "https://user:pass@www.ejemplo.es/", "not a url", "https://10.0.0.8/legal"})
    void anInvalidUrlIsRejectedBeforeQuotaOrTransport(String url) throws Exception {
        Respuesta r = enviar("POST", ruta(LIBRE, "auditar", url, "limpio"), token(usuario(Role.CLIENTE)));

        rechazada(r, Motivo.URL_RECHAZADA);
        assertThat(observaciones.existe(r.id(), "transporte")).isFalse();
        verify(transporte, never()).capturar(any());
        verify(cuota, never()).precomprobarPuedeCrear(any());
        verify(cuota, never()).verificarPuedeCrear(any());
        sinFilas();
    }

    // ---- 12, 17: a failed acquisition persists nothing and keeps its classification ----

    @ParameterizedTest
    @ValueSource(strings = {"PLAZO_AGOTADO", "CANCELADA", "TRANSPORTE", "NO_AUTORIZADA", "RESPUESTA_NO_VALIDA", "runtime", "null"})
    void aFailedAcquisitionPersistsNothingAndKeepsItsClassification(String fallo) throws Exception {
        if (fallo.equals("null")) {
            doReturn(null).when(transporte).capturar(any());
        } else if (fallo.equals("runtime")) {
            doThrow(new IllegalStateException("fixture runtime failure")).when(transporte).capturar(any());
        } else {
            doThrow(new CapturaException(Motivo.valueOf(fallo), "fixture " + fallo)).when(transporte).capturar(any());
        }

        Respuesta r = sonda(LIBRE, "auditar", "trabajo-previo", token(usuario(Role.CLIENTE)));

        rechazada(r, fallo.equals("runtime") || fallo.equals("null") ? Motivo.TRANSPORTE : Motivo.valueOf(fallo));
        verify(transporte, times(1)).capturar(any());
        verify(cuota, times(1)).precomprobarPuedeCrear(any());
        verify(cuota, never()).verificarPuedeCrear(any());
        sinFilas();
        assertThat(observaciones.de(r.id(), "filtro:despues").limpio()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"estado", "tipo", "url", "vacio"})
    void anInvalidResponseOrEmptyContentPersistsNothing(String fallo) throws Exception {
        doAnswer(invocation -> {
            DestinationUrl solicitada = invocation.getArgument(0);
            if (fallo.equals("vacio")) {
                return CapturaRealDePrueba.crear(solicitada, solicitada, "<script>hidden()</script>");
            }
            return CapturaRealDePrueba.crear(fallo.equals("url") ? TransporteLocalDePrueba.destino(FINAL_URL) : solicitada,
                    solicitada, fallo.equals("estado") ? 404 : 200, fallo.equals("tipo") ? "application/pdf" : "text/html", HTML);
        }).when(transporte).capturar(any());

        Respuesta r = sonda(LIBRE, "auditar", "limpio", token(usuario(Role.CLIENTE)));

        if (fallo.equals("vacio")) {
            rechazada(r, com.smartaudits.service.html.ContenidoHtmlInvalidoException.class);
        } else {
            rechazada(r, Motivo.RESPUESTA_NO_VALIDA);
        }
        verify(cuota, never()).verificarPuedeCrear(any());
        sinFilas();
    }

    // ---- 13: a simulated capture is analysable but never persisted ----

    @Test
    void aSimulatedCaptureIsAnalysableButNeverPersisted() throws Exception {
        doAnswer(invocation -> {
            DestinationUrl solicitada = invocation.getArgument(0);
            return TransporteLocalDePrueba.captura(solicitada, solicitada, 200, "text/html", HTML);
        }).when(transporte).capturar(any());
        String token = token(usuario(Role.CLIENTE));

        Respuesta analisis = sonda(LIBRE, "analizar", "limpio", token);
        aceptada(analisis);
        assertThat(analisis.texto("texto")).contains("Responsable del tratamiento");

        rechazada(sonda(LIBRE, "auditar", "limpio", token), Motivo.CAPTURA_SIMULADA);
        verify(cuota, never()).verificarPuedeCrear(any());
        sinFilas();
    }

    // ---- 15: the authoritative quota check and the rollback are unchanged ----

    private Auditoria existente(Usuario propietario) {
        Auditoria a = new Auditoria();
        a.registrarProcedencia("historic", "historic", LocalDateTime.of(2025, 1, 1, 0, 0), TipoFuente.MANUAL);
        a.setTitulo("Existing audit");
        a.setUsuario(propietario);
        a.setResultadoJson("{}");
        return audits.saveAndFlush(a);
    }

    @Test
    void quotaConsumedWhileTheRequestIsInAcquisitionIsRefusedByTheLockedCheck() throws Exception {
        Usuario caller = usuario(Role.CLIENTE);
        var dentro = new CountDownLatch(1);
        var salir = new CountDownLatch(1);
        doAnswer(invocation -> {
            dentro.countDown();
            if (!salir.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("fixture gate was never opened");
            return real(invocation.getArgument(0));
        }).when(transporte).capturar(any());
        String id = UUID.randomUUID().toString();
        var enCurso = client.sendAsync(peticion("POST", ruta(LIBRE, "auditar", URL, "limpio"), token(caller), id),
                HttpResponse.BodyHandlers.ofString());
        try {
            assertThat(dentro.await(15, TimeUnit.SECONDS)).isTrue();
            existente(caller); // another completed request of the same user takes the last slot meanwhile
        } finally {
            salir.countDown();
        }

        rechazada(respuesta(id, enCurso.get(30, TimeUnit.SECONDS)), com.smartaudits.service.AuditQuotaExceededException.class);
        verify(cuota, times(1)).precomprobarPuedeCrear(any());
        verify(cuota, times(1)).verificarPuedeCrear(any());
        assertThat(audits.count()).as("only the other request's audit, never a partial URL audit").isOne();
        assertThat(audits.findAll().get(0).getTipoFuente()).isEqualTo(TipoFuente.MANUAL);
        assertThat(incidents.count()).isZero();
        assertThat(history.count()).isZero();
    }

    @Test
    void aHistoryFailureRollsBackTheWholeAudit() throws Exception {
        // ip_acceso is VARCHAR(50): the creation-history insert fails inside the persistence transaction.
        Respuesta r = enviar("POST", ruta(LIBRE, "auditar", URL, "limpio") + "&ip=" + "x".repeat(51),
                token(usuario(Role.CLIENTE)));

        rechazada(r, org.springframework.dao.DataIntegrityViolationException.class);
        verify(cuota, times(1)).verificarPuedeCrear(any());
        sinFilas();
        assertThat(observaciones.de(r.id(), "filtro:despues").limpio()).isTrue();
    }

    @Test
    void anAdminKeepsTheQuotaExemptionButNotAnAdmissionExemption() throws Exception {
        Usuario admin = usuario(Role.ADMIN);
        existente(admin);
        aceptada(sonda(LIBRE, "auditar", "limpio", token(admin)));
        assertThat(audits.count()).isEqualTo(2);
    }

    // ---- 9: existing endpoints keep Open EntityManager in View ----

    @Test
    void anExistingEndpointThatReadsALazyAssociationStillWorks() throws Exception {
        Usuario owner = usuario(Role.CLIENTE);
        Auditoria audit = existente(owner);
        String admin = token(usuario(Role.ADMIN));

        // AuditoriaService.obtenerAuditoriaPorId is not transactional and reads audit.usuario (LAZY).
        Respuesta r = enviar("GET", "/auditorias/" + audit.getId(), admin);
        assertThat(r.estado()).isEqualTo(200);
        assertThat(r.texto("usuarioNombre")).isEqualTo(owner.getNombre());
        assertThat(r.cuerpo().path("usuarioId").asLong()).isEqualTo(owner.getId());

        assertThat(enviar("GET", "/auditorias/mias", token(owner)).estado()).isEqualTo(200);
        assertThat(enviar("GET", "/auditorias", admin).estado()).isEqualTo(200);
        assertThat(enviar("GET", "/usuarios", admin).estado()).isEqualTo(200);
    }

    @Test
    void everyProductionMappingIsOutsideTheNamespaceAndKeepsTheInterceptor() throws Exception {
        PathPattern exclusion = new PathPatternParser().parse(FronteraAdquisicionWebConfig.RUTAS);
        List<String> produccion = new ArrayList<>();
        List<String> sonda = new ArrayList<>();
        for (var mapping : mappings.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = mapping.getKey();
            boolean deSonda = mapping.getValue().getBeanType().getName().startsWith(SondaFronteraAdquisicion.class.getName());
            for (PathPattern patron : info.getPathPatternsCondition().getPatterns()) {
                String muestra = patron.getPatternString().replaceAll("\\{[^/]+}", "1");
                boolean excluida = exclusion.matches(PathContainer.parsePath(muestra));
                var metodos = info.getMethodsCondition().getMethods();
                var peticion = new MockHttpServletRequest(
                        (metodos.isEmpty() ? RequestMethod.GET : metodos.iterator().next()).name(), muestra);
                ServletRequestPathUtils.parseAndCache(peticion);
                HandlerExecutionChain cadena = mappings.getHandler(peticion);
                assertThat(cadena).as("handler for %s", muestra).isNotNull();
                boolean conOsiv = cadena.getInterceptorList().stream()
                        .anyMatch(WebRequestHandlerInterceptorAdapter.class::isInstance);
                assertThat(conOsiv).as("%s keeps Open EntityManager in View unless excluded", muestra).isEqualTo(!excluida);
                if (deSonda) {
                    sonda.add(muestra + " excluida=" + excluida);
                    continue;
                }
                produccion.add(patron.getPatternString());
                assertThat(excluida).as("production mapping %s is outside the namespace", muestra).isFalse();
                for (String dentro : List.of(FronteraAdquisicionWebConfig.PREFIJO, FronteraAdquisicionWebConfig.PREFIJO + "/",
                        FronteraAdquisicionWebConfig.PREFIJO + "/x", FronteraAdquisicionWebConfig.PREFIJO + "/x/y")) {
                    assertThat(patron.matches(PathContainer.parsePath(dentro)))
                            .as("%s must not serve %s", patron.getPatternString(), dentro).isFalse();
                }
            }
        }
        // This block maps nothing in the namespace: only the test probe lives there.
        assertThat(produccion).contains("/auditorias/{id}", "/auditorias", "/auditorias/html", "/auditorias-conjuntas/{id}",
                "/usuarios", "/admin/historial", "/auth/login", "/error");
        assertThat(sonda).containsExactlyInAnyOrder(LIBRE + "/1 excluida=true", ORDINARIA + "/1 excluida=false",
                LIBRE + "/solo-admin excluida=true");
        // Near misses are ordinary requests.
        for (String cercana : List.of("/adquisicionx/sonda", "/ADQUISICION/sonda", "/x/adquisicion/sonda", "/adquisicio")) {
            assertThat(exclusion.matches(PathContainer.parsePath(cercana))).as(cercana).isFalse();
        }
        for (String dentro : List.of("/adquisicion", "/adquisicion/", "/adquisicion/a", "/adquisicion/a/b")) {
            assertThat(exclusion.matches(PathContainer.parsePath(dentro))).as(dentro).isTrue();
        }
    }

    @Test
    void exactlyOneOpenEntityManagerInViewRegistrationExists() throws Exception {
        assertThat(context.getBeansOfType(OpenEntityManagerInViewInterceptor.class)).hasSize(1);
        assertThat(context.getBeansOfType(FronteraAdquisicionWebConfig.class)).hasSize(1);
        // Spring Boot's own registration backed off, so the exclusion cannot be undone by a second interceptor.
        Class<?> deBoot = Class.forName(
                "org.springframework.boot.autoconfigure.orm.jpa.JpaBaseConfiguration$JpaWebConfiguration");
        assertThat(context.getBeanNamesForType(deBoot)).isEmpty();
    }

    // ---- L: the same entry from a thread that is not a request ----

    @Test
    void theSameEntryIsUsableFromANonWebThread() throws Exception {
        Usuario caller = usuario(Role.CLIENTE);
        String id = UUID.randomUUID().toString();
        ExecutorService trabajador = Executors.newSingleThreadExecutor(tarea -> {
            Thread hilo = new Thread(tarea, "trabajador-de-prueba");
            hilo.setDaemon(true);
            return hilo;
        });
        try {
            var creada = trabajador.submit(() -> observaciones.enPeticion(id,
                    () -> ingesta.auditar(URL, "Trabajador", SondaFronteraAdquisicion.TIPO, caller, "192.0.2.9")))
                    .get(30, TimeUnit.SECONDS);
            assertThat(creada.getTipoFuente()).isEqualTo(TipoFuente.CRAWLER);
            assertThat(creada.getUsuarioId()).isEqualTo(caller.getId());
        } finally {
            trabajador.shutdownNow();
            assertThat(trabajador.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        Estado enTransporte = observaciones.de(id, "transporte");
        assertThat(enTransporte.hilo()).isEqualTo("trabajador-de-prueba");
        assertThat(enTransporte.limpio()).as("%s", enTransporte).isTrue();
        assertThat(enTransporte.prestamos()).isZero();
        assertThat(audits.count()).isOne();
        assertThat(history.count()).isOne();
    }
}
