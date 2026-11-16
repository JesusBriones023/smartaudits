# ACQ-OSIV-BOUNDARY-01 — Web entry boundary of URL acquisition

Baseline: clean `main` at `9acde7f262a2d03e4870f323fd069007e5c27930` (PR #21, ACQ-PROVENANCE-01, merged).
Implementation branch: `feature/acq-osiv-boundary-01`. Uncommitted, for independent review.

This block prepares where a future request handler or worker may enter URL acquisition. It adds no
endpoint and no worker, and nothing in production calls `IngestaUrlService`. Real URL acquisition
stays disabled: `CapturaUrlConfig` still registers only `TransporteHttpNoDisponible`, and
`app.acquisition.url.real-enabled=true` still fails startup.

## The integration gap

PV-13 made `IngestaUrlService.capturar`, `analizar` and `auditar` reject any calling thread that has
an active transaction, transaction synchronization, or a thread-bound `EntityManagerHolder` or
`ConnectionHolder`. That rule is right, and it is unchanged.

Open EntityManager in View (OSIV) is enabled by Spring Boot's default, so every Spring MVC handler
thread has an `EntityManagerHolder` bound. No handler could ever call ingestion: the guard was
fail-closed but could not be satisfied from a web request. PV-13, PV-14, `TRANSPORT-BOOTSTRAP.md`
and ACQ-PROVENANCE-01 all listed this as an open pre-activation item.

## OSIV behaviour verified in this project

A throwaway probe ran against an untouched `git archive` copy of the baseline (scratch directory,
not committed): the whole application on embedded Tomcat bound to loopback, the real security chain,
an isolated H2 database behind HikariCP, and a probe controller and filter.

| Point of one authenticated request (default configuration) | Bound to the thread | Pool loans |
|---|---|---|
| Servlet filter after the security chain, before the `DispatcherServlet` | nothing | 0 |
| Handler entry | `EntityManagerHolder` | 0 |
| After the first repository call (a read-only CRUD transaction) | `EntityManagerHolder` | 1 |
| After a declared non-transactional query, a lazy load and a completed write transaction | `EntityManagerHolder` | 1 |
| Handler exit | `EntityManagerHolder` | 1 |
| Back in the filter, after the `DispatcherServlet` returned | nothing | 0 |

What that means:

- **OSIV is an MVC interceptor here, not a servlet filter.** `JpaBaseConfiguration.JpaWebConfiguration`
  (Spring Boot 3.2.1) registers one `OpenEntityManagerInViewInterceptor` with
  `registry.addWebRequestInterceptor(interceptor)` and no path pattern. It runs inside the
  `DispatcherServlet`, after the security filter chain.
- **Authentication has finished and released everything before it.** `JwtAuthenticationFilter` loads
  the user with `UsuarioRepository.findById`, a self-contained read-only transaction. The principal's
  `Usuario` is therefore detached, and its `role` is a plain column.
- **The request-scoped EntityManager keeps its connection.** It has none at handler entry. From its
  first query it holds one pool connection until the interceptor closes it when the request ends,
  also after its transactions complete. Spring ORM 6.1.2's `HibernateJpaVendorAdapter` sets
  `hibernate.connection.handling_mode=DELAYED_ACQUISITION_AND_HOLD`; a second probe run with that
  value set explicitly gave identical observations.
- **Ingestion from such a handler is rejected**: `IllegalStateException` ("… sin recursos JPA/JDBC
  vinculados (incluido OSIV)") for `capturar` and `auditar`, before any transport call.

So the exact resource-lifecycle problem is not only a bound holder. A request that did any database
work in its handler would keep a pool connection borrowed for the whole DNS/HTTP duration if
acquisition ran on it, or while it waited for another thread to acquire.

## Alternatives considered

The probe measured each shortcut on an OSIV-bound handler that had already run one query (1 loan).

| Option | Evidence | Verdict |
|---|---|---|
| **A. Disable OSIV globally** (`spring.jpa.open-in-view=false`) | Handler threads had nothing bound, every database call returned its connection, and ingestion reached the transport. But `GET /auditorias/{id}` broke for an admin reading another user's audit: `AuditoriaService.obtenerAuditoriaPorId` is not transactional and reads `audit.usuario` (LAZY), giving `LazyInitializationException`; the request ended as 401 through the error dispatch | Rejected for this block. It is a behaviour change of every existing endpoint, and only one was examined. It needs its own endpoint-by-endpoint block with E2E |
| **B. An OSIV-free request boundary for acquisition routes** | See below | **Selected** |
| B′. The same, selected by a handler annotation | Needs a custom interceptor wrapping the OSIV one, with its pre/post/after and async callbacks, in the path of every existing endpoint | Rejected: B needs no custom lifecycle code |
| **C. A dedicated application service** | A service cannot release the request-scoped EntityManager: the interceptor owns it. A pass-through in front of `IngestaUrlService` would add surface and no check | Not added. `IngestaUrlService` stays the single entry |
| **D. Explicit transaction demarcation** | Already the PV-13 flow: a non-transactional advisory read, then the persistence transaction after the capture. As shortcuts on an OSIV thread: `NOT_SUPPORTED` left the holder bound, turned synchronization on and kept the loan (rejected by the guard); `REQUIRES_NEW` was an active transaction (rejected by the guard) | Kept as is; the shortcuts solve nothing |
| **E. A separate thread or worker** | The other thread had nothing bound, so **the PV-13 guard passed there and the transport was reached**, while the request thread kept its `EntityManagerHolder` and its loan for the whole wait | Rejected: it evades the per-thread guard and still retains the connection. No executor was added |
| F. Async MVC (`Callable`, `DeferredResult`) | Not measured; from the Spring ORM 6.1.2 bytecode: `afterConcurrentHandlingStarted` only unbinds, `AsyncRequestInterceptor.preProcess` binds the same EntityManager on the `Callable` thread, and it is closed only when the async request completes | Rejected: the same EntityManager and connection follow or outlive the hop |
| G. Unbind or close the OSIV EntityManager inside the service | Unbinding does not return the connection, and closing it disposes of a resource the interceptor owns and will close again | Rejected: violates resource ownership |

How the three realistic candidates compare:

| Criterion | A. Global disable | B. Route exclusion (selected) | E. Thread hop |
|---|---|---|---|
| Correctness for acquisition | Yes | Yes | No: the request keeps its loan |
| Security | Guard unchanged | Guard unchanged and still the enforcement | Guard evaded |
| Resource ownership | Nothing opened | Nothing opened for the route; nothing unbound or closed | Request-scoped EntityManager left open |
| Existing endpoints | At least one breaks | Same interceptor, same adapter, same chain | Unchanged |
| Authentication context | Unchanged | Unchanged (security chain runs first; same thread) | Must be propagated by hand |
| Transaction isolation | Per call | Per call inside the route | Unchanged |
| Pool behaviour | No request-long loan anywhere | No loan at all during acquisition on the route | One loan held per waiting request |
| Exception propagation | Direct | Direct, same thread | Through a `Future` |
| Cancellation and admission | Unchanged, caller thread | Unchanged, caller thread (PV-14 assumption holds) | A second thread model for PV-14 and PV-15 |
| Complexity | One property, broad effect | One small configuration class | Executor, propagation, lifecycle |
| Testability | Needs every endpoint re-verified | Real request lifecycle tested here | Needs concurrency tests |
| Future endpoint | Any route | Map the handler inside the namespace | Any route, unsafe |

## Selected architecture

One new production class, `com.smartaudits.config.FronteraAdquisicionWebConfig`:

- It defines the `OpenEntityManagerInViewInterceptor` bean and registers it with
  `registry.addWebRequestInterceptor(interceptor).excludePathPatterns(RUTAS)`, where
  `RUTAS = "/adquisicion/**"` (`PREFIJO = "/adquisicion"`).
- Defining that bean makes Spring Boot's `JpaWebConfiguration` back off
  (`@ConditionalOnMissingBean(OpenEntityManagerInViewInterceptor.class)`), so exactly one
  registration exists and the exclusion cannot be undone by a second, unconditional interceptor.
- It carries two of Spring Boot's conditions: a servlet web application, and
  `spring.jpa.open-in-view` true or missing. `spring.jpa.open-in-view=false` keeps its meaning:
  nothing is registered for any path.
- It does not carry Spring Boot's back-off for an `OpenEntityManagerInViewFilter`. If such a
  servlet filter were ever registered, it would bind an EntityManager for every path, the
  namespace included, and the PV-13 guard would reject acquisition there. A structural test
  asserts this class is the only production source that imports either OSIV class.
- It is a plain `@Configuration` with `@Bean` methods, like Spring Boot's. It is therefore not part
  of `@WebMvcTest` or `@DataJpaTest` slices, exactly like the registration it replaces.

For every request outside the namespace nothing changes. Spring MVC evaluates the path once when it
builds the handler chain and, on a match, adds the inner interceptor itself
(`AbstractHandlerMapping.getHandlerExecutionChain` → `MappedInterceptor.getInterceptor()`). That is
the standard `WebRequestHandlerInterceptorAdapter` around the standard interceptor, the same objects
Spring Boot's registration puts there. No custom code runs on an existing endpoint.

For a request inside the namespace the interceptor is simply not in the chain. No EntityManager is
opened for it, so nothing is unbound, suspended or closed on its behalf.

### Route matching

`MappedInterceptor.matches` evaluates the exclusion as a `PathPattern` on the parsed request path
that the handler mappings also use (the path within the application). It never reads the HTTP
method. Each row below was observed, in `FronteraAdquisicionWebConfigTest` or in a throwaway
raw-socket probe against the whole application on embedded Tomcat (scratch copy, not committed):

| Request target | Outcome |
|---|---|
| `/adquisicion`, `/adquisicion/`, `/adquisicion/a/b`, with any method | Excluded |
| `/adquisicionx/…`, `/adquisicion.json`, `/ADQUISICION/…`, `/x/adquisicion/…` | Ordinary: whole segments, case-sensitive, anchored at the root |
| `/%61dquisicion/sonda/…`, `/adquisicion/%73onda/…` (percent-encoded letters) | The same handler as the plain path, and excluded: nothing bound, transport reached |
| `/%73onda-ordinaria/…` | The ordinary handler, with OSIV: rejected by the guard |
| `;v=1`, `//`, `/./`, `/../`, `%2e%2e` or `%252F` in the path | Refused before the filter that follows the security chain (Spring Security's firewall); 401 |
| `%2F`, `%5C`, `\` or `%00` in the path | Refused by Tomcat; 400 |
| A trailing slash on a mapped namespace route, `GET /adquisicion`, `GET /adquisicion/index.html` | No handler: the same 401 as an unmapped ordinary path |
| `GET`, `PUT`, `PATCH`, `DELETE`, `HEAD` on the POST-only probe route | No handler method runs; 401. `OPTIONS` gets the framework's own 200 |

The 401 of an unmapped path is the existing behaviour of every path: the error dispatch is itself
authenticated and carries no JWT principal. The probe observed the status and whether its filter
ran; naming the firewall or Tomcat as the refusing component is an inference from those two.

Both matchers decoded every tested path identically, and a disagreement could only fail closed: a
path that reached a namespace handler without matching the exclusion would get OSIV and be
rejected by the PV-13 guard. No production mapping can match a path whose first segment is
`adquisicion`; a test checks every mapping.

Not changed: `IngestaUrlService`, `AuditoriaService`, `AuditQuotaService`, `CapturaUrlConfig`, the
transports, `SecurityConfig`, properties, migrations, dependencies and the frontend have no diff. The
namespace is authenticated by the existing `anyRequest().authenticated()` rule.

## Request, thread and transaction lifecycle

One thread serves the whole request; acquisition is never handed to another thread.

1. **Authenticate and authorize.** The security chain runs first. The JWT filter loads the user in
   its own read-only transaction; when it returns, nothing is bound and no connection is on loan.
   A caller without valid authentication stops here with 401.
2. **Initial database work.** The handler may use repositories and transactional services. Without a
   request-scoped EntityManager each call is self-contained: a transaction gets its own
   EntityManager and returns the connection at completion, and a non-transactional declared query
   uses a temporary EntityManager that is closed after the query.
3. **Resources are released** by their own owners (the transaction manager, the shared EntityManager
   proxy) when each call ends. Nothing has to be released "for" acquisition.
4. **Enter acquisition.** `IngestaUrlService` checks the PV-13 guard on entry and again after the
   advisory quota read, then takes the PV-14 permit and calls the transport. The thread has no
   transaction, no synchronization, no bound resource and no pool loan.
5. **Validate** the response, then the source and provenance (`esRealVerificada()`).
6. **Persistence transaction.** The cross-bean call to `AuditoriaService.crearAuditoriaAnalizada`
   begins it.
7. **Authoritative quota check** under the user row lock, inside that transaction.
8. **Commit or roll back** audit, incidents and creation history together. The response DTO is built
   inside the transaction.

| Resource | Owner | Released |
|---|---|---|
| EntityManager and connection of the JWT user load | `JpaTransactionManager` (Spring Data `findById`) | At that transaction's completion, inside the security chain |
| Request-scoped EntityManager (ordinary routes only) | `OpenEntityManagerInViewInterceptor` | By the interceptor when the request ends; never by acquisition code |
| Request-scoped EntityManager (namespace) | — | Never created |
| Temporary EntityManager of the advisory quota read | Spring's shared EntityManager proxy | After the query |
| PV-14 permit | `ControlConcurrenciaCaptura`, the executing caller | In its `finally`, before extraction and persistence |
| Persistence transaction, its EntityManager and connection | `JpaTransactionManager` via the `AuditoriaService` proxy | At commit or rollback |

## How the boundary will be used later

A future, separately reviewed endpoint block:

- maps its handler under `FronteraAdquisicionWebConfig.PREFIJO`, or changes `RUTAS` on purpose (a
  pinned constant; a test fails when it changes);
- puts no `@Transactional` on the handler or on anything between it and `IngestaUrlService`;
- passes the authenticated principal's `Usuario`, as the existing controllers pass the caller;
- does not hand acquisition to another thread;
- maps `CapturaException` and the other failures to HTTP responses (none is mapped today; an
  unhandled one would go through the error dispatch);
- must not rely on lazy loading in that handler: there is no request-scoped EntityManager;
- adds itself to the structural test that today asserts no production code calls ingestion.

A future worker calls the same `IngestaUrlService` from its own thread, outside any transaction, after
loading what it needs in its own short transactions. A non-web thread never had OSIV; the guard
already holds there.

## Why the PV-13 rejection remains

The exclusion only makes the guard satisfiable. The guard is still what enforces the rule:

- a handler mapped outside the namespace still gets OSIV and is rejected;
- a handler inside the namespace that opens a transaction, binds an EntityManager or a JDBC
  connection, or activates synchronization is rejected;
- if another component ever binds an EntityManager for those routes (a servlet-filter OSIV, a second
  interceptor after a framework upgrade), the result is a rejection, not an acquisition with
  resources held.

## PV-14, PV-15 and ACQ-PROVENANCE-01 on this boundary

None of their code changed. This is how they apply to a request thread in the namespace.

- **PV-14 admission.** Still a non-waiting `tryAcquire` on the calling thread, which here is the
  request thread. That thread owns the permit and releases it in its `finally` when the transport
  has really returned and the response is validated, before extraction and persistence. A
  saturated request is refused at once with `CAPACIDAD_AGOTADA`; nothing queues.
- **PV-15 deadline.** It belongs to the prepared real transport (`TransporteHttpReal`, 20 s code
  default) and starts inside the admitted section, when the transport call begins. It does not
  cover the guards, the quota checks, admission, extraction, analysis or persistence, and it is not
  a timeout of the HTTP request. This block adds no timeout. A request in acquisition occupies its
  Tomcat thread until the transport returns, holding the permit and no pool connection.
- **Cancellation.** `CANCELADA` still means the executing thread observed its own interrupt flag.
  No code links a client disconnect, or anything else about the HTTP request, to that interrupt
  (not measured here), and Scan-linked or external cancellation stays deferred, as in PV-14 and
  PV-15.
- **What these tests show.** The transport is replaced, so no deadline elapses in them. The
  replaced transport throws `PLAZO_AGOTADO` or `CANCELADA`; the reason reaches the handler
  unchanged, nothing is persisted, the thread ends with nothing bound, and the permit is free for
  the next request. The deadline and interruption mechanics stay covered by the unchanged PV-14
  and PV-15 suites.
- **Provenance.** A handler passes a URL string and receives a result; it has no way to create or
  mark a capture. Eligibility is still `CapturaHtml.esRealVerificada()` on the object the transport
  returned, checked after the response is validated and before extraction and persistence. The
  trusted fixture of these tests is the existing `CapturaRealDePrueba`, which lives in test sources
  and is not packaged in the application JAR. A `TransporteLocalDePrueba` capture is refused with
  `CAPTURA_SIMULADA`.

## Tests

All are offline. HTTP goes only to the embedded server on `127.0.0.1`; every capture is synthetic.

`FronteraAdquisicionWebHttpTest` runs the whole application on embedded Tomcat with the real security
chain, real services and repositories, isolated H2 behind HikariCP, capacity 1 and daily limit 1.
Only `TransporteHtml` is replaced. A test-only probe controller maps **one handler method on two
routes**, inside the namespace and on an ordinary route, so the route is the only difference. The
probe lives in test sources, is never component-scanned and exists only in contexts that import it.

- **Boundary:** on the namespace route the thread has nothing bound and no loan at the filter, at
  handler entry, after the handler's own database work, and inside the transport. The transport runs
  on the request thread. All three entries, with and without prior database work.
- **Observed from outside:** while a request is parked inside the transport, the test thread sees 0
  loans and 0 threads waiting for a connection.
- **Ordinary route stays fail-closed:** `EntityManagerHolder` bound, 1 loan after its first query,
  `IllegalStateException` mentioning OSIV, no transport or quota call, no rows; the interceptor
  closes its EntityManager afterwards.
- **The guard is not suspended in the namespace:** read-write and read-only transactions, a bound
  EntityManager, a bound JDBC connection and synchronization without a transaction are each rejected
  for all three entries.
- **Authentication and authorization:** no token, malformed, wrong key, expired, deactivated user and
  stale token version all give 401 without reaching the probe filter; method security gives 403 to a
  non-admin; an unmapped path in the namespace answers like any other unmapped path.
- **Invalid URL:** rejected before quota, admission and transport.
- **Failures:** `PLAZO_AGOTADO`, `CANCELADA`, `TRANSPORTE`, `NO_AUTORIZADA`, `RESPUESTA_NO_VALIDA`
  keep their reason; a runtime failure or a null capture becomes `TRANSPORTE`; invalid status, type,
  URL or empty content persist nothing.
- **Provenance:** a simulated capture can be analysed and is refused with `CAPTURA_SIMULADA`; the
  trusted fixture persists a `CRAWLER` audit owned by the authenticated caller, with incidents and
  `CREACION` history carrying the request IP.
- **Quota and rollback:** advisory check outside a transaction, then the capture, then the locked
  check inside the transaction; quota consumed while the request is in acquisition is refused by the
  locked check; a real history constraint violation rolls back the whole audit; ADMIN keeps its
  quota exemption.
- **Admission:** a second request is refused with `CAPACIDAD_AGOTADA` while the first holds the only
  permit, and the permit is free again after a success and after a failure.
- **Existing endpoints:** `GET /auditorias/{id}` for an admin reading another user's audit still
  returns the owner's name (the lazy read); every production mapping resolves to a handler chain that
  contains the interceptor, and none can serve a path in the namespace.
- **Non-web caller:** the same `auditar` from a plain thread reaches the transport clean and persists.

`FronteraAdquisicionWebWiringTest` runs the whole application with nothing mocked. The only
`TransporteHtml` bean is `TransporteHttpNoDisponible` and nothing of the prepared real composition is
a bean; a request through the namespace passes the guard and is refused with `NO_AUTORIZADA`,
persisting nothing; and the same application with `app.acquisition.url.real-enabled=true` fails to
start with the existing gate message.

`FronteraAdquisicionWebConfigTest` covers the configuration alone: the conditions, the exact
exclusion against near-miss paths, the pinned namespace, and structural guards (no production code
calls ingestion; one owner of the OSIV registration; no property file sets `open-in-view`).

Unchanged suites still own their guarantees: `IngestaUrlTransactionIntegrationTest` (PV-13 guards,
quota and rollback), `ControlConcurrenciaCapturaTest` and `IngestaUrlConcurrencyTest` (PV-14,
including interruption), the PV-15 deadline suites, `CapturaProcedenciaTest` and
`IngestaUrlProcedenciaIntegrationTest`, `CapturaContractTest` and `TransporteBootstrapTest` (fail-closed
wiring), and `SecurityHttpResponseTest` (existing endpoints on real Tomcat).

## Validation

All backend runs were offline (`mvn -o`), JDK 17.0.15, Maven 3.9.12, on 2026-11-16.

| Run | Result | Exit |
|---|---|---|
| Baseline probe, default configuration (untouched `9acde7f` copy) | Observations in the table above; ingestion rejected | 0 |
| Baseline probe, `spring.jpa.open-in-view=false` | Handler threads clean; `GET /auditorias/{id}` broke (`LazyInitializationException`, 401) | 0 |
| Baseline probe, explicit `DELAYED_ACQUISITION_AND_HOLD` | Identical to the default run | 0 |
| Baseline probe, shortcuts (`NOT_SUPPORTED`, `REQUIRES_NEW`, another thread) | As in the alternatives table | 0 |
| Focused, the three new suites in the working tree, before the sixth `FronteraAdquisicionWebConfigTest` case was added | 74 tests (5 + 64 + 5), 0 failures, 0 errors, 0 skipped | 0 |
| Focused, affected regressions (PV-13, PV-14, PV-15, provenance, wiring, MVC and security suites) | Not run on their own; they ran inside the full run below | — |
| Full backend `mvn -o clean test` | 1615 tests in 74 suites, 0 failures, 0 errors, 0 skipped; the new suites at 6 + 64 + 5 = 75 | 0 |
| Mutation A: the exclusion removed | 53 of 75 new tests fail (the namespace gets OSIV and is rejected) | 1 |
| Mutation B: every path excluded (`/**`) | 10 fail (the lazy endpoint, the ordinary-route cases, the mapping and exclusion checks) | 1 |
| Mutation C: the `spring.jpa.open-in-view` condition removed | 1 fails (the property no longer disables OSIV) | 1 |
| Mutation D: the PV-13 guard disabled | 25 fail: 21 fail-closed cases, plus 4 follow-on failures from connections the unguarded flow leaked | 1 |
| Playwright E2E, existing isolated harness (`npm run test:e2e`, run `8012f2dd064941c7a84860d34a61ec4e`) | 6 of 6 passed, `PHASE 1.4 E2E PASSED`; the runner's own audit found 0 temporary containers, volumes and processes | 0 |
| Route-matching probe (scratch copy with one throwaway test, raw sockets) | 1 test, 0 failures; observations in "Route matching" | 0 |

The full run and the mutations used fresh source-only copies of the working tree in a scratch
directory, so javac compiled every class (127 production and 91 test sources) and no IDE-built class
was used. The 239 input files were SHA-256 identical to the working tree before and after the full
run, and again after the E2E run: only this document has changed since the full run. The focused
and full runs overlap and must not be added as distinct coverage. The previous total was 1540; this
block adds 75 tests and changes none.

The exit codes of the full run, the mutations, the E2E run and the route-matching probe were
written to result files next to their logs. The earlier ones were read from the Maven output: the
focused log ends in `BUILD SUCCESS`, and the baseline probe logs are quiet-mode output with no
failure. A first focused run had 15 errors, all from one stubbing mistake in the new HTTP test
(restubbing with `when(...)` ran the default answer with a null argument). The stubs became
`doThrow`/`doAnswer(...).when(...)`; no production code changed.

Mutation D is also direct evidence for the guard. With it disabled, the first leaked pool
connection appeared in the `auditar` case of a handler that had only activated synchronization, and
later tests in that run found it still borrowed.

E2E was run because the change replaces the interceptor registration of every existing endpoint.
The runner is unchanged: temporary MariaDB 11.2, the packaged backend and Chromium, all on loopback,
with no `.env`, no ordinary database and no public website. No frontend source changed, so frontend
unit tests, build and lint were not rerun.

That run packaged the backend from this working tree with javac after the last source change; the
JAR contains `FronteraAdquisicionWebConfig` and no test class. It applied Flyway V1 to V9 to an
empty MariaDB 11.2, and its six flows cover registration and login, a pasted-HTML audit, the joint
audit, logout, an invalid token (401) and admin-only access (403). Its backend log has no
`spring.jpa.open-in-view` warning, as expected. No E2E flow enters the namespace: nothing is mapped
there.

## Limitations and explicit non-guarantees

- **The guard is per thread.** It cannot see a caller that hands acquisition to another thread while
  its own request keeps an OSIV EntityManager and a connection; the probe showed that hop passes the
  guard. Nothing in production does this, and the rule for future callers is above, but it is a
  review rule and a structural test, not an enforcement.
- **Resources that are not thread-bound are invisible.** A caller holding a raw
  `DataSource.getConnection()` passes the guard. Pool loans are a pool-wide number; the tests read
  them with one request in flight.
- **No request timeout or cancellation.** A request in acquisition holds its Tomcat thread and a
  PV-14 permit until the transport returns. The only bound is the PV-15 deadline of the prepared
  transport, and no client-disconnect or Scan-linked cancellation exists.
- **Isolated H2/HikariCP observations** do not certify the production MariaDB driver or pool.
- **The boundary is a path.** A handler mapped outside it is rejected (fail-closed). A handler inside
  it that relies on lazy loading fails with `LazyInitializationException`.
- **It depends on Spring Boot backing off** when the bean exists. A test asserts exactly one
  registration; if both ever existed the namespace would get OSIV again and be rejected.
- Spring Boot's startup warning about `spring.jpa.open-in-view` is no longer logged, because its
  configuration class backs off. OSIV is still enabled for existing endpoints, which still keep a
  connection for the rest of a request after their first query. That is unchanged and out of scope.
- This block adds no endpoint, exception mapping, worker, per-host limit or Scan-linked cancellation,
  and does not change PV-13, PV-14, PV-15 or ACQ-PROVENANCE-01 code.

## Rollback

Delete `FronteraAdquisicionWebConfig`, its three test classes and the probe. Spring Boot's own
registration returns for every path. There is no schema, property, endpoint, dependency or frontend
change, and production keeps `TransporteHttpNoDisponible` either way.

## Deployment blockers

D1, D2 and D9 remain OPEN and R-EGR remains BLOCKED. This block does not activate real URL
acquisition and does not make the crawler production-ready. Do not enable real acquisition because
these tests pass.
