# PV-13 — Acquisition transaction boundary

Date: 2026-11-02. Base: cleaned `public-main` at
`e357ac7e7784a520b03bd88e4083223c265a8630`.
Branch: `feature/pv13-acquisition-transaction-boundary`. Uncommitted, for review.

## Defect and scope

`IngestaUrlService.auditar` used to wrap capture in `@Transactional`.
`capturar` and `analizar` could also acquire from an outer transaction. A slow
capture would occupy database transaction scope, contrary to ADR-0002 §17.
Quota was checked only after capture. The ordinary history path catches failures,
so it was also insufficient for a guarantee of atomic CRAWLER creation history.

This milestone changes the internal boundary only. Real acquisition remains
disabled. No endpoint, schema, engine, score or frontend contract changes.

## Invocation, persistence and proxies

All three public entry points check `isActualTransactionActive()` before argument
validation, quota work or transport invocation. Read-only transactions are also
rejected. They additionally reject transaction synchronization and thread-bound
`EntityManagerHolder`/`ConnectionHolder` resources, including an OSIV context with
no active transaction. Nothing is suspended, unbound, flushed or closed on behalf
of the caller. The explicit checks apply to self-invocation and direct instances,
without depending on an acquisition proxy.

The audit flow is:

1. Reject an unsafe invocation context and validate document type/URL syntax.
2. Run the advisory, non-locking CLIENTE quota check; ADMIN skips the query.
3. Recheck the resource boundary and invoke the capture transport.
4. Validate requested URL, status, HTML type and real provenance; extract/validate text.
5. Call the injected `AuditoriaService.crearAuditoriaAnalizada` bean across its
   Spring transactional proxy.
6. Inside that transaction, repeat the authoritative quota check under the
   existing pessimistic user lock, then save audit, incidents and creation history.

Both existing `crearAuditoriaAnalizada` overloads retain `@Transactional`. Its
internal overload call is covered by the transaction already started by the
external bean call; it does not need self-invocation to start a second transaction.
For CRAWLER only, `HistorialService.registrarCreacionObligatoria` propagates creation
history failures. The outer persistence transaction rolls back. Existing
manual/HTML/joint history handling is preserved.

## Quota and resource lifetime

`AuditQuotaService.precomprobarPuedeCrear` shares the existing date/count/limit
calculation but does not lock, write or reserve quota. The actual method is the
declared `AuditoriaRepository.countByUsuarioIdAndFechaCreacionGreaterThanEqual`,
with no method/interface `@Transactional` annotation. In the inspected Spring Data
JPA 3.2.1 configuration it has **no transaction attribute**, unlike inherited
CRUD `count()`, whose default configuration is read-only. An isolated probe of the
real, unspied repository proxy confirmed both metadata results and observed
`isActualTransactionActive() == false` during execution of the declared query.
Consequently, the review's proposed description of a short read-only Spring Data
transaction does not apply to this count method. No quota implementation change
was made to manufacture that behavior.

Without an outer transaction, synchronization or OSIV-bound context, Spring's
shared EntityManager creates a temporary EntityManager for the scalar query and
closes it after execution. This is different from transaction completion and is
not by itself proof of physical JDBC release. Tests with the project's versions
and isolated H2/Hikari verify empty thread resource bindings and zero borrowed
connections before capture; these observations do not certify production
pool/driver behavior. Outer transactions and OSIV holders are rejected, not
suspended or reused. See [Spring Data's transactionality documentation](https://docs.spring.io/spring-data/jpa/reference/jpa/transactions.html)
for the declared-query versus inherited-CRUD distinction.

The pre-check is advisory: quota can be consumed during capture. The final locked
check still rejects that race and persists no URL audit. It also protects manual
and joint writers through the unchanged user-lock protocol. Captures that do not
request persistence (`capturar`/`analizar`) have no user/quota contract.

## Tests

`IngestaUrlTransactionIntegrationTest` uses real Spring persistence proxies and
isolated H2/Hikari, with a mocked transport and explicit committed fixture setup
and FK-ordered cleanup. Its `NOT_SUPPORTED` annotation disables the default
`@DataJpaTest` transaction **in tests only**. Production does not use suspension.

The suite covers all three entries under read-write/read-only transactions,
OSIV-like holders before/after a query, a bound JDBC connection and synchronization
without a transaction. Rejections assert zero transport/quota/persistence calls.
Allowed calls assert transaction absence, empty thread resource bindings and zero
active Hikari loans at capture. It covers successful CRAWLER persistence, final URL,
incidents and CREACION history, runtime write failure, history failure, a real H2
history constraint violation, zero writes on pre-persistence errors, CLIENTE
pre-rejection, ADMIN exemption and quota consumed between the two checks.

`CapturaRealDePrueba` is test-only code in the capture package and uses the existing
package-private provenance factory. Its HTTP_REAL value is synthetic: no DNS or
HTTP occurs, and this test does not establish deployment-level acquisition safety.
No production provenance constructor was changed.

`IngestaUrlIntegrationTest` now also runs outside a test transaction and explicitly
cleans its isolated fixtures. Existing HTML/joint parity assertions remain.
`AuditQuotaServiceTest` adds non-locking pre-check, ADMIN and authoritative recheck
cases. Existing bootstrap and loopback-ingestion tests only adapt dependency wiring.

## Historical implementation validation

Commands run from `smartaudits-backend` on this working diff:

```powershell
mvn -o --batch-mode '-Dtest=IngestaUrlTransactionIntegrationTest,IngestaUrlIntegrationTest,AuditQuotaServiceTest,IngestaConTransporteRealTest,TransporteBootstrapTest,CapturaContractTest' test
mvn -o --batch-mode test
```

The corrected focused run passed 106 tests: 0 failures, 0 errors, 0 skipped.
This includes 37 new boundary/integration cases. The first development run had
8 errors in test stubbing (null matcher arguments and calling an abstract
repository method as a real Mockito method); these were corrected before the
passing run. No production workaround was introduced for those errors.

The full backend run passed **1,386 tests: 0 failures, 0 errors, 0 skipped**
(2026-11-02 21:41:42 Europe/Madrid). Focused tests are included in that total;
the two runs must not be added together as distinct coverage.

Historical E2E: **6 passed (53.2s), 0 failed, 0 skipped**, using the unchanged
`npm.cmd run test:e2e` runner, Chromium, a temporary MariaDB 11.2 and real backend.
It verified frozen source/packaged V1–V9 migrations, empty-schema startup and
cleanup: 0 temporary containers, volumes and processes; no connection to the
ordinary database. Registration/login, manual audit, pasted HTML, joint audit/PDF,
logout, invalid-token and role enforcement regressions passed.

The first E2E attempt from the ordinary checkout was blocked before browser
execution: an already-running Java process held the normal target JAR open and
Spring Boot could not rename it for repackaging. Offline packaging retries inside
and outside the sandbox reproduced that file-lock error. No process was stopped
or restarted. The generated JAR in the ordinary target directory was not
successfully repackaged; rebuild it after its existing holder releases it.

To complete E2E without changing the runner or stopping that process, 291 backend
and frontend source/configuration files were copied and individually verified by
SHA-256 under the ignored directory
`smartaudits-frontend/.e2e-runtime/pv13-source-validation/`. No `.env`, Git history
or ordinary build output was copied. A junction reused the installed node_modules.
The runner was executed from that copy's `smartaudits-frontend` directory, using
its own build output. Successful run ID: `34f1cd45ac35438a8f7a14e0b6ce521a`.
Its logs are inside that frontend's `.e2e-runtime/<run-id>/` directory.

**Cleanup safety:** that old copy contains a `node_modules` junction pointing to
the real frontend dependencies. Do not recursively delete the copy or delete the
original node_modules. This correction task does not traverse, copy or clean that
directory; cleanup requires separate approval.

`git diff --check` passed. Transport implementation/configuration, frontend source,
CI and migrations have no diff. Standalone frontend unit/build/lint checks were
not run because no frontend source changed; the real frontend was exercised by
E2E. The separate v2 MariaDB migration harness and hosted CI were not run.

Local ignored logs: `smartaudits-backend/target/pv13-focused.log` and
`smartaudits-backend/target/pv13-backend.log`; Surefire XML/text reports are under
`target/surefire-reports` (61 full-suite XML reports). These are working-diff results, not results for a new
commit or a hosted CI run. No commit has been created.

## Final pre-commit correction and validation

The correction changes only the quota Javadoc, the two transport/PV-13 documents,
and one new test method in `IngestaUrlTransactionIntegrationTest`. The other eight
files in the reviewed PV-13 patch retain their starting SHA-256 hashes. Executable
quota code and all production transaction/resource guards are unchanged.

`resourcesIntroducedByTheQuotaPrecheckAreRejectedBeforeCapture` begins resource-free,
calls the real advisory quota implementation through its spy, verifies resource
release, and then binds a test EntityManagerHolder before returning. The second
guard rejects acquisition, with zero transport calls or audit/history persistence.
The injected holder is unbound and closed in `finally`. The boundary suite now has
38 executed cases; the focused run passed **107 tests, 0 failures/errors/skips**.

Final validation uses this new backend-only copy, separate from the old E2E copy:

```text
C:\Users\jbrio\AppData\Local\Temp\smartaudits-pv13-final-52a87dad1b2b4756aab92aa100a36a82\final\smartaudits-backend
```

Its 217 backend inputs (`pom.xml`, main/test Java and resources) were individually
SHA-256 verified against the final working tree. The two corrected documents were
also copied. No existing target/classes, credentials, private `.env`, frontend or
node_modules were copied; source traversal rejects reparse points. The ordinary
checkout's locked target and unrelated Java processes are not used or cleaned.

The parent directory contains `backend-input-sha256.tsv` (217 sorted input hashes),
SHA-256 `C1508062B8E3752C9F75B353F9918A2F7317FFC2174205C0AB713C4DFC356718`.
It also contains `validation-input-sha256.tsv`, `focused-final.log` and
`clean-test-final.log`. The validation record itself is finalized after execution;
Java/build/resource inputs remain unchanged throughout.

Commands, from the isolated backend directory:

```powershell
mvn -o --batch-mode '-Dtest=IngestaUrlTransactionIntegrationTest,IngestaUrlIntegrationTest,AuditQuotaServiceTest,IngestaConTransporteRealTest,TransporteBootstrapTest,CapturaContractTest' test
mvn -o --batch-mode clean test
```

Both logs confirm javac compilation of **120 production files and 76 test files**.
The clean log records deletion only of the isolated copy's target, followed by
fresh main/test compilation. The resolved test classpath contains Spring Data
JPA/Commons 3.2.1 and Spring ORM 6.1.2.

The clean full run passed **1,387 tests across 61 Surefire suites: 0 failures,
0 errors, 0 skipped**. The added post-quota boundary case passed in both the
107-test focused run and the clean full run. These are overlapping runs and
must not be added as distinct coverage. Final input hashes are rechecked against
the original checkout after execution; `final-input-sha256.tsv` also captures
the finalized documentation copies. No implementation file changed after javac.

Final checks: `git diff --check` passed, including whitespace checks for new files;
transport/activation, DNS/TLS/SSRF, migrations, frontend and CI have no diff.
All three entry guards, the post-quota guard, authoritative quota, atomic CRAWLER
history, historical audit compatibility and manual/HTML/joint regressions passed.
The index stays empty; HEAD, public-main and origin/main remain at the baseline.

The transaction-behavior probe is retained separately at the validation root,
outside `final/`: `quota-transaction-probe.log` and the exploratory backend's
`QuotaTransactionProbeTest.java`. The unspied repository probe passed 1 test and
recorded: declared count attribute `null`, query execution `active=false,
readOnly=false`, inherited `count()` attribute `PROPAGATION_REQUIRED,...,readOnly`.
An initial exploratory attempt could not inspect a Mockito wrapper; the corrected
probe uses the real unspied repository. It is excluded from the final source copy
and final suite counts. The review's short-read-only-transaction wording therefore
needs factual reconciliation; no annotation was added just to make it true.

E2E was **not rerun**: the 6-pass result above is historical. This correction adds
one test and changes documentation only, so it does not require new frontend/E2E
execution. The separate v2 MariaDB harness, hosted CI and deployment egress
validation remain outside this run. The old E2E junction warning above still applies;
that copy has not been traversed, copied or cleaned.

## Remaining activation blockers and limitations

- **OSIV integration:** global OSIV configuration is unchanged and remains enabled
  by Spring Boot's default in a web context. Such a caller is rejected, even if
  its holder has no connection. A future endpoint/worker must deliberately enter
  acquisition without that context or retained database resources. Tests do not
  certify production Hibernate/pool behavior, arbitrary unbound resources held by
  a caller, another thread or another database, or every transaction manager.
- **Provenance constructor:** the public canonical constructor of the
  `ProcedenciaCaptura` record can manufacture HTTP_REAL internally. The
  package-private factory is not a capability/security boundary. Closing that
  risk remains required before activation; tests do not make it safe.
- **D1/D2/D9 OPEN; R-EGR BLOCKED:** approved-IP pinning, configured DNS, effective
  TLS trust/identity and deployment egress/routing/NAT64/tunnels/interception still
  need authorized deployment validation. Synthetic and loopback fixtures do not
  close those gates.
- **Resource budgets:** acquisition-wide DNS/redirect deadline, global/per-host
  concurrency and Scan-linked cancellation remain outstanding.
- **Persistence architecture:** legacy CRAWLER audit persistence is not a complete
  tenant-scoped Scan/Page/Capture/Evidence pipeline. Phase 4 is not complete.

`CapturaUrlConfig` still creates only `TransporteHttpNoDisponible`.
`app.acquisition.url.real-enabled=true` still fails startup. Bootstrap preparation
does not activate any transport. Real acquisition requires a separately reviewed
wiring change after the blockers are closed; never enable it based on this test
fixture or milestone alone.

## Review and rollback

Review the resource guard, cross-bean persistence transaction, advisory/final quota
ordering and CRAWLER-only strict history path together. No migration or historical
rewrite is needed. Reverting the bounded changes restores the previous behavior;
keep acquisition disabled if reverting because the original defect would return.
Public-main history, backup branches and the Claude worktree are unchanged.
