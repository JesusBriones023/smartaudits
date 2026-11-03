# PV-14 — Synchronous URL capture admission

Baseline: clean `public-main` at `d750a7d62d04cddd93c6f90cd102c59757de2d89`.
Implementation branch: `feature/pv14-capture-admission`. Uncommitted, for independent review.

## Exact guarantee

One `ControlConcurrenciaCaptura` bean bounds active synchronous capture sections per
Spring application instance. It owns a semaphore and uses only nonblocking
`tryAcquire()`. Excess calls fail immediately with `CAPACIDAD_AGOTADA`: no admission
queue, waiting period, worker, executor or scheduler is introduced.

This is not a static counter shared by multiple application contexts or a
distributed limit across deployed instances. It bounds capture sections, not the
entire audit lifecycle, all Java threads, request queues, aggregate heap or legal
analysis. Independently constructed low-level transports are not admission entry
points; the production ingestion path uses the single injected controller.

## Configuration and fail-closed bootstrap

| Property | Default | Accepted values |
| --- | --- | --- |
| `app.acquisition.url.execution.max-concurrent` | `4` | Integer `1` through `32` |

`ConfiguracionEjecucionCaptura` binds the dedicated namespace with unknown fields
rejected. Zero, negatives, values above 32, overflow, malformed/empty values and
unsupported switches such as `unlimited`, `enabled` or `queue-size` fail startup.
There is no bypass, unlimited value or new feature flag. The controller's public
constructor also checks the range.

`CapturaUrlConfig` registers exactly one controller and still registers exactly
the existing `TransporteHttpNoDisponible` transport. It does not call
`PreparacionTransporte` or wire the real resolver/client/transport. Setting
`app.acquisition.url.real-enabled=true` still fails startup, including with valid
DNS and admission settings. Configuration is checked while acquisition is disabled.

## Integration and permit ownership

The common private `IngestaUrlService.capturar(DestinationUrl)` helper owns the
admitted operation. All three public entries share it: `capturar`, `analizar` and
`auditar`. Internal calls do not acquire a second permit.

The sequence remains:

1. Reject active transactions, synchronization and thread-bound JPA/JDBC resources.
2. Validate destination and document type where applicable.
3. For `auditar`, perform the existing advisory quota precheck; it reserves nothing.
4. Recheck the persistence-resource boundary before admission.
5. Attempt admission without waiting, on the calling thread.
6. Execute the transport and validate non-null capture, requested URL, HTTP status
   and HTML content type while the permit is held.
7. Release capacity in the executing call's `finally`.
8. Continue existing provenance checks, extraction, analysis and persistence.

Only the actual executing owner releases capacity. Rejected admission releases
nothing. The controller exposes no transferable permit or public release method.
Returning a result, throwing a runtime exception or unwinding an `Error` runs the
same release. Fatal errors are not converted into ordinary capture failures.

The controller has a package-private semaphore constructor solely for a deterministic
test of interruption at successful admission. Production constructs and owns its
semaphore through the validated integer constructor; the semaphore is never exposed.

## Failure and interruption contract

Existing reasons retain their meaning and order. Two internal reasons are appended:

- `CAPACIDAD_AGOTADA`: admission is full, not a timeout or SSRF rejection. The
  rejected call does not enter transport or start DNS/HTTP.
- `CANCELADA`: the executing caller observes its interrupt flag before transport
  execution or after normal completion. The flag is not cleared.

The controller checks interruption before admission, immediately after admission
inside the release scope, and after normal callback return. A primary callback
exception is preserved instead of being replaced by a secondary interruption
failure. Existing URL, resource and quota validation retains its preceding order.

`Future.cancel(true)` is not completion evidence. If a delegate ignores interruption,
its permit stays occupied until that delegate actually exits. A cooperating delegate
can exit on interruption and then release normally. No artificial termination is used.

This does not guarantee socket closure, remember cancellation when a delegate clears
the flag, or prevent a cancellation arriving after capture return from racing later
persistence. No Scan or client-disconnect cancellation contract is introduced.

## PV-13, quota and persistence

Acquisition remains on the caller thread so no worker hides thread-bound resources.
The original PV-13 guards are unchanged and run before admission. No transaction is
joined, suspended, unbound or closed for a caller.

Capacity is released before extraction, legal analysis and the cross-bean call to
`AuditoriaService.crearAuditoriaAnalizada`. The authoritative quota check remains
inside that persistence transaction under the existing user lock. ADMIN retains
its quota exemption but has no admission exemption. Advisory prechecks still occur
before admission, so this is not a bound on those database queries.

Saturation writes no audit, incident or history row and creates no quota reservation.
The final quota race check and strict CRAWLER creation-history atomicity remain:
audit, incidents and CREACION history commit or roll back together. Scan states,
tenant authorization and historical data are unchanged.

## Test coverage and execution

New tests cover caller-thread execution, capacity reuse, fail-fast saturation,
concurrent peak count, all ingestion entries sharing capacity, primary failures and
HTTP-timeout exceptions, invalid responses, interruption before/after admission,
post-return interruption, and cancellation of a still-running non-cooperative
delegate. Test workers use latches, bounded termination checks and finally cleanup;
no `Thread.sleep` coordinates the new tests.

The existing PV-13 tests additionally verify admission is untouched on resource
rejection, capacity is free during final persistence, and CLIENTE/ADMIN saturation
persists nothing. Bootstrap tests functionally check capacities 1, 4 and 32,
invalid/unsupported configuration, singleton injection and unavailable transport.
Existing extraction, security and rollback assertions are retained.

Validation uses a new source-only temporary repository copy, excluding `.env`,
ordinary targets, node_modules and all old E2E runtime directories. The original
backend JAR and unrelated Java processes are not changed or stopped. Synthetic
HTTP_REAL fixtures are test data, not real acquisitions or deployment evidence.

Executed validation:

| Validation | Actual result |
| --- | --- |
| Initial focused backend suite (eight classes) | 146 passed; 0 failures/errors/skips |
| Strengthened bootstrap + PV-13 integration suite | 100 passed; 0 failures/errors/skips |
| Full backend tests and package | 1,426 passed; 0 failures/errors/skips; package succeeded |
| Frontend unit tests | 185 passed in 16 files |
| Frontend build and lint | Both passed; existing large-bundle warning remains |
| Frontend dependency audit | High-severity gate passed; 8 existing moderate findings remain |
| Migration validator | All isolated empty/legacy/restart/rejection/repair paths and cleanup passed; V1–V9 frozen |
| Unchanged Playwright E2E suite | 6 passed (31.7s); 0 failed/skipped; temporary containers/volumes/processes all 0 |

The backend runs overlap and must not be added as distinct test coverage. The full
backend includes 39 additional parameterized cases compared with the baseline's
1,387 tests. E2E verified manual/pasted/joint HTML, the exact Unicode PDF filename
and PDF integrity/content, history, session cleanup and authorization. The runner
reported no connection to the ordinary database and unchanged `.env` metadata.

Commands executed in the isolated copy (logs are outside Git):

```powershell
# smartaudits-backend
mvn --batch-mode -o '-Dtest=ControlConcurrenciaCapturaTest,IngestaUrlConcurrencyTest,IngestaUrlTransactionIntegrationTest,IngestaUrlIntegrationTest,TransporteBootstrapTest,IngestaConTransporteRealTest,CapturaContractTest,AuditQuotaServiceTest' test
mvn --batch-mode -o '-Dtest=TransporteBootstrapTest,IngestaUrlTransactionIntegrationTest' test
mvn --batch-mode -o package
# smartaudits-frontend
npm.cmd ci --offline=false --cache .npm-cache --registry=https://registry.npmjs.org --no-audit --no-fund
npm.cmd test
npm.cmd run build
npm.cmd run lint
npm.cmd run audit:dependencies -- --offline=false --cache .npm-cache
npm.cmd run test:e2e
# isolated repository root
powershell -NoProfile -File ./scripts/Validate-CiMigrations.ps1
```

The separate audit command retains the existing severity gate; `--no-audit` on
installation does not replace or suppress that check. No dependency version changed.
Validation used Windows, Java 17, Maven 3.9.12, Node 22.17.1, npm 10.9.2 and isolated
MariaDB 11.2. This is local evidence, not a GitHub Actions run of this uncommitted patch.
Playwright 1.63.0 used the installed Chromium browser. The validation copy was
`C:\Users\jbrio\AppData\Local\Temp\sa-pv14-validation-1536670e7bbb4edca19ebb925181914b`.
Backend source/test hashes matched the reviewed working tree after validation.

## Deliberate exclusions and deployment blockers

PV-14 provides no total acquisition deadline, guaranteed interruption of blocked
sockets, forced cancellation, per-host limit, distributed limit, tenant fairness,
Scan worker/cancellation integration, watchdog lifecycle fix or real acquisition
enablement. A stuck non-cooperative delegate can occupy a bounded slot indefinitely.

D1/D2/D9 remain OPEN and R-EGR remains BLOCKED. DNS/IP routing, effective TLS and
deployment egress require separate authorized evidence. Existing OSIV integration
and public-constructor provenance risks also remain. No application limit replaces
deployment egress isolation. Real acquisition must not be enabled because these
tests pass.

## Review and rollback

Review the common-helper scope, semaphore owner/finally invariant, interrupt checks,
singleton configuration and PV-13 regressions together. No migration, endpoint,
dependency, legal-engine, scoring or frontend change is required.

A later application rollback to the reviewed baseline needs no schema reversal.
Keep real acquisition disabled during rollback. No Git history operation, staging,
commit, merge or push is part of this implementation task.
