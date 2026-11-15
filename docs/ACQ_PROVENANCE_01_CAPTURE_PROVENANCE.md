# ACQ-PROVENANCE-01 — Trusted capture provenance and persistence eligibility

Baseline: clean `main` at `706eb712bb5bf54c0daf5c4e722b337221b75a13` (PR #20, PV-15, merged).
Implementation branch: `feature/acq-provenance-01`. Uncommitted, for independent review.

This is internal hardening of an in-process trust boundary. Production URL acquisition stays
disabled: `CapturaUrlConfig` still registers only `TransporteHttpNoDisponible`, and
`app.acquisition.url.real-enabled=true` still fails startup. No externally reachable path to
the defect existed, because no production transport bean can return a capture.

## Defect closed

PV-13, PV-14 and `TRANSPORT-BOOTSTRAP.md` listed it as an open pre-activation risk.

- `ProcedenciaCaptura` was a public `record`. A public record always has a public canonical
  constructor, so `new ProcedenciaCaptura(Origen.HTTP_REAL, "x", "rev")` compiled anywhere. The
  package-private `real(...)` factory therefore restricted nothing.
- The public `CapturaHtml` constructor accepted any provenance. Even an unforgeable label could
  have been copied from a genuine capture onto unrelated HTML.
- `IngestaUrlService.auditar` decided persistence with `captura.procedencia().esReal()`, which
  only compares an enum on that label.

Before the change, a probe test ran against an untouched `git archive` copy of the baseline (in a
scratch directory, not committed). It used a `TransporteHtml` bean declared in
`com.smartaudits.service.url`, outside the capture package. The bean returned caller-written HTML
with a self-built `HTTP_REAL` label, and `auditar` persisted a `CRAWLER` audit (1 test, 0 failures).

### Data flow and who can create provenance

`TransporteHtml.capturar` → `CapturaHtml` (with its `ProcedenciaCaptura`) →
`IngestaUrlService.capturar` (admission, re-validation of URL/status/type) → `auditar` eligibility
check → `AuditoriaService.crearAuditoriaAnalizada` (transaction with the locked quota check).

| Source | Before | After |
|---|---|---|
| `TransporteHttpReal`, every hop's peer observed on the socket | real, via package factory | real, via `CapturaHtml.real` (only production caller) |
| `TransporteHttpReal` through a test connector (declared peer) | simulated | simulated (unchanged) |
| `CapturaRealDePrueba` (test sources, capture package) | real, via package factory | real, via `CapturaHtml.real`: the authorized isolated-test path |
| `TransporteLocalDePrueba` and other simulated fixtures | simulated | simulated (unchanged) |
| Any caller: `new ProcedenciaCaptura(HTTP_REAL, …)` | **compiled, granted persistence** | does not compile (no public constructor) |
| Any caller: real label copied/rebuilt onto `new CapturaHtml(…)` | **granted persistence** | `IllegalArgumentException` |
| Any caller: reflection-built `HTTP_REAL` label onto `new CapturaHtml(…)` | **granted persistence** | `IllegalArgumentException` |

`TransporteHttpReal`'s own REAL decision was already sound: `ClienteHttpFijado` is final, and
`peerObservado = true` exists only through the package-private `Conexion.delSocket`, which only
`ConectorDirecto` uses. A test `Conector` can produce only `Conexion.declarada`, which yields a
simulated capture. This block does not change that logic.

## Contract

1. **Eligibility belongs to the capture object.** `CapturaHtml.esRealVerificada()` is the only
   question ingestion asks. It is true only for a capture created by the package-private
   `CapturaHtml.real(...)` factory, which mints its `ProcedenciaCaptura` itself. A real
   provenance object is never passed in from outside.
2. **The public constructor builds simulated captures only.** `new CapturaHtml(…, procedencia)`
   keeps its signature, and every existing simulated caller compiles unchanged, but it throws
   `IllegalArgumentException` for any real provenance. That covers a copied, rebuilt or
   reflection-forged label alike, so metadata alone grants nothing.
3. **The label cannot be built outside the package.** `ProcedenciaCaptura` is now a final class
   with a private constructor. Its only public factory is `simulada(String)`, and the
   package-private `real(...)` is called only by `CapturaHtml.real`. This is defense in depth:
   rule 2 alone would block attachment.
4. **Immutability.** Both classes are final with private final fields, so a real capture can't be
   subclassed to override `esRealVerificada()`, and its HTML can't be replaced.
5. **Single minting site.** In `src/main/java`, only `TransporteHttpReal` references
   `CapturaHtml.real`, and only `CapturaHtml` references `ProcedenciaCaptura.real`. A
   source-scan test enforces this.

No token, key or capability object was introduced. It would have to be held by the same code that
already sits behind the package boundary, and any caller holding one could copy it.

### Alternatives considered

| Option | Verdict |
|---|---|
| Make the record constructor package-private | Impossible: a public record's canonical constructor must be at least as accessible as the record |
| Only convert `ProcedenciaCaptura` to a class with a private constructor | Insufficient: a genuine real label could still be copied onto arbitrary HTML via the public `CapturaHtml` constructor |
| Only make `CapturaHtml` reject a real label | Sufficient for persistence, but a forged `HTTP_REAL` label could still exist and mislead reporting or a future `esReal()` check; combined with option 2 |
| Unforgeable token / capability passed by the transport | Rejected: within one JVM and package it adds nothing beyond package access, and a token object a caller holds can be copied |
| Registry of issued captures (identity set checked by ingestion) | Rejected: global mutable state and lifecycle for no gain over an immutable, factory-only final class |
| JPMS module / sealed package | Out of scope: the application is a Spring Boot classpath jar; not a small change |

Selected: rules 1–5 above, which together are the smallest change that enforces invariants A–H.

### Protection boundary (explicit limits)

- **The boundary is the Java package `com.smartaudits.v2.acquisition.capture`.** Any class in that
  package can call `CapturaHtml.real`. Production sources are kept to the single caller by review
  and by the source-scan test, which is a structural guard, not a proof. Test sources in the
  package (`CapturaRealDePrueba`) use it on purpose and are not packaged into the application jar.
- **Not a defense against code already running in the JVM.** Reflection on the private
  `CapturaHtml` constructor, `setAccessible`, bytecode instrumentation, or a class placed in the
  same package from another jar on the classpath (split package; no sealing or JPMS) can still
  create a "real" capture. Ordinary Java access control is not a sandbox.
- **Same-URL replay of a genuine capture.** A `TransporteHtml` wired by the application could
  return a genuine real capture it obtained earlier for the same URL. Ingestion still rejects it
  for any other URL, because `urlSolicitada` must equal the destination requested. Deciding which
  transport bean is wired remains the job of `CapturaUrlConfig` (currently fail-closed).
- **"Real" means `TransporteHttpReal` with observed peers, nothing more.** The authority of its
  injected resolver, TLS trust and deployment egress is D2, D9 and R-EGR, all still unresolved.
  This block doesn't verify any of them.
- `ProcedenciaCaptura.esReal()` remains as reporting metadata; persistence no longer uses it.

## Compatibility

- `new CapturaHtml(…7 args…)`: same signature. Behavior differs only for a real provenance, which
  outside the package could previously come only from a copy or a forgery. Production code never
  did that; one test did (below).
- `ProcedenciaCaptura`: the public constructor is removed. No production code used it. `origen()`,
  `transporte()`, `revisionCatalogo()`, `esReal()`, `simulada()`, `equals`, `hashCode` and the
  `toString` text are unchanged. `isRecord()` is now false. Nothing serializes these types (no
  controller, Jackson or JPA use).
- New public method `CapturaHtml.esRealVerificada()`; new package-private `CapturaHtml.real(…)`.
- `TransporteHttpReal`: same outputs. It now builds the REAL branch through `CapturaHtml.real`;
  the declared-peer branch and the PV-15 checkpoint order are unchanged (construct, then
  `comprobarPlazo`, then return).
- `IngestaUrlService.auditar`: same order (validate URL → advisory quota → admission/capture →
  re-validation → eligibility → text → transactional persistence); only the eligibility predicate
  changed. The rejection is still `CAPTURA_SIMULADA`.

Test adaptations:

- `CapturaRealDePrueba` now calls `CapturaHtml.real`, and gained an overload with status and
  content type.
- `IngestaUrlTransactionIntegrationTest.invalidCaptureOrContentWritesNothing` used to copy a real
  fixture's provenance onto a rebuilt capture. Left unchanged, it would have kept passing for the
  wrong reason (a `TRANSPORTE` failure inside the transport mock), so it now builds each invalid
  response with the trusted fixture and asserts the exact outcome: `RESPUESTA_NO_VALIDA`, or
  `ContenidoHtmlInvalidoException` for empty text, with no locked quota check.
- `CapturaContractTest.aRealProvenanceCannotBeCreatedOutsideThisPackage` now asserts that no
  public constructor exists, instead of calling the removed one.
- `TransporteHttpRealTest` and `IngestaConTransporteRealTest` also assert
  `esRealVerificada() == false` for declared-peer captures.

## Security regression coverage

`CapturaProcedenciaTest` (unit, capture package, no network):

- a simulated capture is usable offline and is never verified real;
- the trusted factory binds a real provenance to its own content, with value semantics and the
  record-compatible `toString`;
- a real label copied onto other HTML is rejected;
- a genuine real capture rebuilt field by field is rejected, and `CapturaHtml` is final;
- a simulated capture relabelled real is rejected;
- a reflection-forged `HTTP_REAL` label says "real" but cannot be attached;
- reflection audit: both classes are final, non-record and immutable; no public or protected
  member accepts an `Origen` or is named `real`; `ProcedenciaCaptura` has no public constructor;
  `CapturaHtml` has exactly one;
- source scan: single minting site, as in rule 5.

`IngestaUrlProcedenciaIntegrationTest` covers the real persistence decision. It uses a
`@DataJpaTest` with isolated H2 `acq-provenance-01`, real `AuditoriaService`/`AuditQuotaService`
proxies, daily limit 1 and admission capacity 1. The transport mock lives outside the capture
package.

- **Positive control:** a trusted capture persists as `CRAWLER`, owned by the caller, with
  `CREACION` history, the advisory pre-check, the locked check and `bloquearPorId`. A second audit
  is then refused by quota.
- **Arbitrary HTML with a simulated label:** `analizar` works; `auditar` fails with
  `CAPTURA_SIMULADA`.
- **Four forgery forms:** copied provenance, a rebuilt real capture, a relabelled simulated
  capture, and a reflection-forged label. Each fails with `TRANSPORTE`, caused by the provenance
  `IllegalArgumentException`, and `analizar` cannot use them either.
- **Failed acquisition:** `PLAZO_AGOTADO`, `CANCELADA` and `URL_RECHAZADA` pass through as the
  same instance; any other `RuntimeException` becomes `TRANSPORTE` with its cause kept.
- **No side effects on every negative case:** `crearAuditoriaAnalizada`, `verificarPuedeCrear`,
  `bloquearPorId` and `AuditoriaRepository.save` are never called, and the audit, incident and
  history tables stay empty.
- **Follow-up proof:** after every negative case, a trusted capture still persists exactly once.
  With limit 1 and capacity 1, that proves no quota was consumed and no admission permit was kept.

Unchanged suites: `CapturaContractTest.wiringIsFailClosedByDefaultAndRefusesToEnableTheRealTransport`
(fail-closed default; `real-enabled=true` fails startup) and `captureAndIngestionSourcesUseNoNetworkApi`.
The PV-13 transaction, PV-14 admission and PV-15 deadline suites run unchanged apart from the
test adaptations listed above.

## Validation

All runs were offline (`mvn -o`), JDK 17.0.15, on 2026-11-14:

| Run | Result | Exit |
|---|---|---|
| Baseline probe (forged `HTTP_REAL` from `service.url`, untouched 706eb71 copy) | 1 test, 0 failures: audit **persisted** (defect reproduced) | 0 |
| Focused: `CapturaProcedenciaTest` 8, `IngestaUrlProcedenciaIntegrationTest` 10, `CapturaContractTest` 5, `IngestaUrlTransactionIntegrationTest` 40 | 63 tests, 0 failures, 0 errors | 0 |
| Full backend `mvn -o clean test` | 1540 tests, 0 failures, 0 errors, 0 skipped | 0 |
| Mutation A: public-constructor gate disabled | 8 failures (4 unit; 4 forgery forms persisted instead of throwing) | 1 |
| Mutation B: ingestion eligibility check disabled | 1 failure (simulated capture persisted) | 1 |
| Mutation C: `ProcedenciaCaptura` constructor made public | 2 failures (reflection/API audits) | 1 |

The full run includes the unchanged PV-14 suites (`ControlConcurrenciaCapturaTest` 13,
`IngestaUrlConcurrencyTest` 10) and PV-15 suites (`PlazoCapturaTest` 12,
`TransporteHttpRealPlazoTest` 11, `ClienteHttpFijadoPlazoTest` 48, `IngestaUrlPlazoTest` 4,
`ResolvedorDnsDirectoPlazoTest` 6), plus `TransporteBootstrapTest` 60. Mutations ran on scratch
copies, never on the working tree. No frontend or E2E code changed, so neither was rerun.

## Deployment blockers

D1, D2 and D9 remain OPEN and R-EGR remains BLOCKED. This block does not activate real URL
acquisition, does not make the crawler production-ready, and does not resolve OSIV integration,
per-host limits or Scan-linked cancellation. Do not enable real acquisition because these tests
pass.

## Rollback

Revert the ACQ-PROVENANCE-01 changes. There is no schema, property, endpoint, dependency or
frontend change, and production keeps `TransporteHttpNoDisponible` either way.
