# PV-15 — Acquisition-wide deadline of the prepared real transport

Baseline: clean `public-main` at `e8273d02b11b42c3f88cb91c58b08bbc4b964eda`.
Implementation branch: `feature/pv15-acquisition-deadline`. Uncommitted, for independent review.

The real transport stays unwired. `CapturaUrlConfig` still registers only
`TransporteHttpNoDisponible`, and `app.acquisition.url.real-enabled=true` still fails startup.
Everything below concerns the prepared composition (`PreparacionTransporte`) and its tests.

## Exact guarantee

Each call of `TransporteHttpReal.capturar(DestinationUrl)` creates exactly one absolute
deadline (`PlazoCaptura`) of `LimitesAdquisicion.total()` when the call begins. It is measured
on a monotonic nanosecond source (`System::nanoTime` in production, a manual source in tests).
Differences of readings are compared, so a wrapping source stays correct. No `Clock` or
`Instant` takes part in a timeout; the injected `Clock` only stamps `CapturaHtml.fechaCaptura`.

The deadline is never restarted or extended: `PlazoCaptura.acotado` only yields an earlier or
equal deadline. It is shared by:

- the A and AAAA exchanges;
- the TCP fallback of an exchange;
- every connection, TLS handshake, request write, header read and body read;
- every redirect hop;
- decompression and charset decoding;
- creating the capture.

Every blocking wait uses the smaller of its own existing limit and the time left:

| Wait | Own limit (unchanged) | Bound after PV-15 |
| --- | --- | --- |
| DNS exchange per family (UDP and TCP fallback) | `app.acquisition.url.dns.timeout` (2 s default) | earlier of the exchange limit and the capture deadline |
| TCP connect | `LimitesHttp.conexion` (5 s) | smaller of that and the connection rest |
| Connection (TLS, write, all reads) | `LimitesHttp.total` (15 s) per connection | earlier of that and the capture deadline |
| Each read | `LimitesHttp.lectura` (5 s) | smaller of that and the connection rest |

A socket timeout is never 0, which the JDK reads as "infinite". A positive rest is rounded up
to whole milliseconds, so a sub-millisecond rest becomes 1 ms. The rounding adds less than 1 ms
to the requested wait, so a timeout caused by the deadline is not signalled before it. That
rounding is not a lateness bound: when a wait actually ends also depends on thread scheduling,
timer granularity and JVM pauses. An expired deadline throws instead of being extended.

At the connection deadline the existing watchdog closes the raw socket. That aborts a blocked
TLS handshake, write or read.

**Starts:** inside the admitted PV-14 capture section, when the transport call begins.

**Excludes:**

- PV-13 resource guards and initial URL parsing in `IngestaUrlService`;
- the advisory quota check and admission (PV-14 never queues);
- `IngestaUrlService.validar`;
- HTML extraction, legal analysis and persistence;
- the HTTP request as a whole.

This is not a hard real-time bound. Elapsed time can exceed the deadline by:

- scheduling, GC or JVM pauses and watchdog latency;
- timer granularity;
- the currently running CPU stage, which is bounded by size, not time (gzip at most 2 MiB in
  and out; decoding at most 2 MiB);
- the tiny DNS TCP query write, which has no timeout of its own.

## Defaults and validation

| Setting | Value |
| --- | --- |
| `LimitesAdquisicion.POR_DEFECTO` | 20 s (code-level default of the prepared transport) |
| `LimitesAdquisicion.MAXIMO` | 60 s; zero, negative, null or above 60 s rejected |

No production property was added. Durations given to `PlazoCaptura` must be positive. Up to
`MAX_NANOS` (`Long.MAX_VALUE / 4` ns, about 73 years) they are converted exactly; a longer
duration is reduced to `MAX_NANOS`. Saturation can therefore only shorten a request, never extend
it, and no arithmetic overflow occurs. Production limits (20 s, at most 60 s) are far below it.

## DNS

`ResolvedorDnsDirecto` also implements `ResolvedorConPlazo`. Through it:

- an exchange ends by the earlier of its own timeout and the capture deadline;
- a family is not started once the deadline has expired (no socket is opened);
- AAAA is not started after an interruption observed during A. The resolver throws
  `InterruptedException` and leaves the flag set.

The operator-selected literal server, the single A and AAAA queries, matching, size limits,
TCP fallback, CNAME handling and every classification are unchanged.

Ordinary `HostResolver.resolve` callers keep the previous per-exchange behaviour and the
documented `DNS_FAILURE` contract.

Defect corrected for every caller: the two bytes of the TCP length prefix used to be read under
one timeout set once, so a late first byte gave the second a full timeout again. Both bytes are
now read like the message, recomputing the timeout before each read.

A plain `HostResolver` given to the transport is adapted without the deadline. It cannot be
bounded or stopped; it is only checked after it returns. The prepared composition uses the
deadline-aware resolver.

The destination package, `DestinationPolicy`, the address catalog and their static tests are
unchanged.

## HTTP, redirects and socket ownership

`ClienteHttpFijado.obtener(url, ip, adquisicion)` derives the connection deadline as the earlier
of `LimitesHttp.total` and the acquisition deadline. The two-argument overload keeps the
previous per-connection behaviour. Pinned IP, no proxy, no DNS, Host, SNI, standard trust,
endpoint identification, `IdentidadTls`, strict framing and size limits are unchanged.

`TransporteHttpReal` builds one `DestinationPolicy` per capture whose resolver delegates to the
`ResolvedorConPlazo` with that capture's deadline. Every hop is still evaluated anew; no
approval is reused, and the hop limit, loop rule and HTTPS-to-HTTP rule are unchanged.

Ownership rules:

- The connected socket is inside the `try`/`finally` before the watchdog is armed. A scheduling
  failure (`RejectedExecutionException` becomes `TRANSPORTE`; an `Error` passes through
  unchanged) still closes it.
- Cleanup keeps the previous graceful order: the TLS layer first (`close_notify`), then the raw
  socket. The raw close is attempted even if the TLS close fails in any way.
- Writing `close_notify` can block on a peer that does not read. The alarm is therefore
  cancelled only after both close attempts: by the connection deadline the watchdog closes the
  raw socket, which ends such a blocked close (it ends no other non-cooperative wait). Before PV-15
  the alarm was cancelled before closing.
- Each alarm captures only its own raw socket, so a late alarm cannot close a later connection.
- Closing twice is harmless.
- An `IOException` from the **TLS-layer** close is expected and ignored, as before. Typical causes
  are a `close_notify` that cannot be written to a socket the watchdog already closed, or a peer
  that went away. The raw close that follows decides whether the connection was released.
- An `IOException` from a **raw-socket** close is never ignored. It may be thrown before
  anything was released, so it means the release is uncertain. That covers the owner's close,
  and also the watchdog's close when it ran before the owner's close completed.
  - After an otherwise successful exchange it fails the call: `TRANSPORTE` "No se pudo cerrar
    la conexión con el servidor", with that `IOException` as cause. No response is returned.
  - The rule is deliberately conservative. It also applies when the socket had in fact been
    released and the exception came afterwards, since the two cases cannot be told apart from
    outside.
  - With a primary failure it is attached to that failure as suppressed, so the primary is the
    one thrown.
- An unchecked failure of a close or of the alarm cancellation is never dropped either:
  - with a primary failure it is attached to it as suppressed;
  - after a successful exchange it is thrown as is, as before.
- Cleanup failures are collected first, in the order met (TLS close, watchdog close, owner's raw
  close, cancellation), each instance once by identity.
- **What propagates is decided next, before anything is linked**, in this order:
  1. A primary `Error` stays the thrown one.
  2. Otherwise the first cleanup `Error`, even when that same instance is already inside the
     primary (for example its cause). It is never filtered out first and never converted into a
     `CapturaException`.
  3. Otherwise the primary failure, whether security (pinning, TLS authentication), deadline,
     framing or size. Ordinary cleanup failures never replace it.
  4. Without a primary, the most serious cleanup failure: an unchecked failure before an
     `IOException`, the first among equals. A raw-close `IOException` there is thrown as
     `TRANSPORTE` "No se pudo cerrar la conexión con el servidor" with it as cause.
- **Then every other distinct failure, and the primary last, is attached individually and
  directly** to the thrown one as suppressed. There is no intermediate grouping, so one failure
  that cannot be attached never takes the others with it. An independent raw-close `IOException`
  is kept even when another cleanup failure has an unattachable relationship to the primary.
- Suppression safety:
  - Every suppression link is made by one helper. It checks reachability through causes and
    suppressed exceptions, by identity, in both directions, with a visited set and no recursion.
  - It skips a failure that is already reachable from the thrown one, so no instance is recorded
    twice. That includes a close that rethrows the primary or one of its causes.
  - It skips a failure from which the thrown one is reachable, because attaching it would close
    a loop. This code therefore adds no cycle to the exception graph.
  - The check terminates on a loop already present in foreign exceptions, but it cannot remove
    such a loop.
- Where attaching is genuinely impossible without a loop:
  - **A cleanup `Error` that is already inside an ordinary primary** (for example its cause) is
    thrown. The primary cannot be attached under it, so the primary is not reachable from what
    propagates, though its own cause link to the `Error` stays.
  - **An ordinary cleanup failure that already contains the primary** (for example wrapping it
    as its cause) cannot be attached under the primary. It is not reported; every other failure
    still is.
- The owner and the watchdog close the raw socket under one per-attempt lock (`CierreCrudo`), so
  a failed watchdog close is handed to the owner.
  - The lock covers only the raw close, which JDK 17 already runs under the socket's own monitor.
  - The TLS close never runs under it, so the watchdog can still end a blocked `close_notify`.
  - The reporting cutoff is the owner's raw-close handoff (`CierreCrudo.cerrar`). It comes after
    the TLS close and before the alarm cancellation, so it is not the instant every cleanup step
    has finished. Cleanup is not one atomic step.
  - A watchdog close failure after that handoff has nobody to report to. An `IOException` is
    dropped; an unchecked failure stays in the task's future, as before.
- No close is retried, and no thread, queue or worker was added. The cancellation still comes
  after both close attempts, also when they fail.

Remaining release uncertainty (not claimed away):

- A successful `close()` is the JDK's statement, not a proof that the operating system released
  the connection.
- For HTTPS, JSSE closes the raw socket itself inside the TLS close (autoClose). JDK 17's
  `SSLSocketImpl.close` ignores an `IOException` from that internal close, and
  `NioSocketImpl` marks itself closed even when releasing the descriptor fails. The explicit raw
  close that follows then returns normally, so such a failure cannot be observed.
  - Changing autoClose or the TLS close would change normal JSSE `close_notify` handling, so it
    was not done.
  - What is detected: a raw close that throws when the owner or the watchdog calls it directly
    (plain HTTP, a raw socket left open by the TLS layer, or the watchdog).
- A second close cannot retry a failed descriptor release, because the JDK socket is then
  already marked closed. That is why there is no retry and no deferred cleanup through the
  watchdog. The JDK's own release when the object is garbage collected is not relied on.

## Watchdog

The single static daemon thread remains, now a `ScheduledThreadPoolExecutor(1)` with
`setRemoveOnCancelPolicy(true)`. A cancelled alarm leaves the queue at once instead of staying
until its delay elapses, so cancelled tasks do not pile up.

A package-private `Vigilante` seam lets tests record and fire alarms deterministically. No
executor, queue or worker was added.

Spring ownership and shutdown of the watchdog are deferred to the future authorized
activation, because the transport has no bean owner today.

## Failure classification

`CapturaException.Motivo.PLAZO_AGOTADO` is appended after the existing values, whose order is
unchanged.

| Situation | Reason |
| --- | --- |
| Admission full | `CAPACIDAD_AGOTADA` (PV-14; no deadline is created) |
| Production transport | `NO_AUTORIZADA` |
| Policy rejection other than `DNS_FAILURE` (forbidden or mixed addresses, `DNS_LIMIT`, `DNS_INVALID`, `DNS_EMPTY`), or an invalid redirect target | `URL_RECHAZADA`, even if the deadline has also expired |
| `DNS_FAILURE` | checkpoint: `CANCELADA` if interrupted, else `PLAZO_AGOTADO` if expired, else `URL_RECHAZADA` |
| Pinning mismatch | `TRANSPORTE`, never replaced by a timing outcome |
| TLS authentication failure: an `SSLPeerUnverifiedException` (no certificate cause needed), a `CertificateException` anywhere in an `SSLException`'s cause chain, or `IdentidadTls` rejecting the certificate | `TRANSPORTE` "La identidad TLS del servidor no es válida para el host autorizado", never replaced by a timing outcome |
| Any other `SSLException` (for example a handshake cut by the watchdog, a protocol or cipher alert, a reset) | treated like other I/O: `PLAZO_AGOTADO` once the acquisition deadline has passed; "Tiempo de espera agotado" at this connection's own limit; otherwise `TRANSPORTE` "Fallo de comunicación o de protocolo TLS con el servidor", which claims nothing about identity |
| Raw-socket close `IOException` after an otherwise successful exchange | `TRANSPORTE` "No se pudo cerrar la conexión con el servidor"; with a primary failure, suppressed on it |
| Framing or size violation | `RESPUESTA_NO_VALIDA` |
| Timeout or watchdog close with the acquisition deadline passed | `PLAZO_AGOTADO` |
| A connection's own timeout before the acquisition deadline | `TRANSPORTE` "Tiempo de espera agotado" (unchanged) |
| Interruption at a checkpoint | `CANCELADA`; the flag is never cleared |

Checkpoints check interruption first, then the deadline. They run:

- before each hop's evaluation;
- after a `DNS_FAILURE`;
- after an approval (the only bound of a resolver that ignores deadlines);
- after decoding, before a capture is returned.

No capture is returned once expiry or interruption has been observed there. HTTP I/O failures
are not reclassified by the interrupt flag, which stays set; PV-14 then keeps the primary
failure. Messages contain no URL, host, IP or token, and no logging was added.

## PV-14 and PV-13

`ControlConcurrenciaCaptura`, `IngestaUrlService`, `CapturaUrlConfig`, `AuditQuotaService` and
`AuditoriaService` are unchanged.

- The permit is still held by the calling thread until the transport really returns. A blocked
  non-cooperative resolver keeps it past the deadline and after `Future.cancel(true)`; capacity
  returns only when it exits.
- `PLAZO_AGOTADO` passes through `IngestaUrlService` unwrapped. A failed capture extracts,
  analyses and persists nothing.
- The PV-13 guards, advisory precheck, locked quota check and CRAWLER history atomicity are
  untouched.

## Tests and evidence

New tests (96 cases):

| Class | Cases | Kind |
| --- | --- | --- |
| `PlazoCapturaTest` | 12 | deterministic: arithmetic, the saturation boundary (counterexample `Duration.ofSeconds(2_305_843_009L)` and neighbours), rounding, wrap, validation, enum order |
| `ResolvedorDnsDirectoPlazoTest` | 6 | 4 deterministic in their assertions (expired, interruption, per-read TCP prefix, and the second family, which accepts either legitimate A outcome; see the fourth round); 2 REAL TIME (silent server, late TCP prefix) |
| `ServidorDnsDePruebaTest` | 15 | deterministic, through the fixture's factory seam (no port exhaustion, no dependence on real ephemeral sequences): TCP bind failure, UDP collisions, exhausted retries with every attempt reported, unexpected failure, the same instance thrown by bind and close, a close `Error` after a failed construction and during a retry, an original `Error` kept; dispersed candidates (a contiguous rejected region left, a rejected number never retried, range, no repeats and spacing of the walk, repeats skipped and a repeating source failing clearly), a thread-start failure closing everything, and `close()` ending a connection blocked in a read and a scripted pause |
| `ClienteHttpFijadoPlazoTest` | 48 | 46 deterministic (including 6 for cleanup `Error` precedence and independent cleanup failures): budgets, watchdog seam, ownership, close order, stale alarm, precedence, unauthenticated peer, TLS diagnostics, exceptional TLS/raw/cancel cleanup, raw-close `IOException` before and after release, raw-close failures under transport, framing, deadline and TLS security failures, TLS-close `IOException` with a good raw close, raw-close `Error`, a watchdog close failing during cleanup, the TLS close blocked until the watchdog fires, the drip teardown with failing closes, and 11 suppression-graph cases (the A/B/A counterexample directly and through a real exchange, reused instances, self-suppression, distinct failures, security, deadline and `Error` primaries, a cleanup `Error`, a raw-close `IOException` without a primary, existing cause and suppressed links), each checked for cycles. 2 REAL TIME: TLS drip; silent server and body drip |
| `TransporteHttpRealPlazoTest` | 11 | 10 deterministic (shared deadline across hops, checkpoints, precedence, interruption); 1 REAL TIME (slow first hop) |
| `IngestaUrlPlazoTest` | 4 | deterministic: PV-14 permit held until real exit (also after `Future.cancel(true)` and while a failing raw close blocks), no persistence |

The raw-close cases use real loopback sockets whose `close()` is overridden to fail in a chosen
way. Each socket is registered for an unconditional real release in the teardown. The cases
that need the watchdog to fire during the TLS close use a simulated TLS layer (Mockito
`SSLSocket`) over the real raw socket. They are contract tests of this class's ordering and
reporting, not evidence of how JSSE itself blocks in `close_notify`.

The REAL TIME cases measure actual socket timeouts over loopback. Their tolerances are wide
regression bounds against the multi-second pre-PV-15 behaviour; they are not
millisecond-accurate deadline guarantees:

| Case | Nominal limit | Accepted elapsed time |
| --- | --- | --- |
| DNS silent server | 400 ms capture deadline | 300 ms to 1,900 ms |
| DNS late TCP length prefix | 2 s exchange limit | 1.5 s to 2.9 s (the corrected defect took about 3.5 s) |
| HTTP TLS drip, silent server, body drip | 700 ms capture deadline | 600 ms to 1,700 ms |
| Slow first redirect hop | 1.5 s capture deadline | 1.4 s to 2.7 s |

Fixture pacing uses sleeps only to simulate slow servers. Coordination uses latches, bounded joins
and `Future.get` with timeouts.

Test teardown attempts every step even when an earlier close fails:

- the TLS drip fixture closes its listener and its accepted socket, interrupts its worker, joins it
  with a 5 s bound and asserts that it terminated;
- `@AfterEach` releases every registered resource the same way.

A failure of the test body stays primary, and cleanup failures are attached to it as suppressed
(a cleanup `Error` over a non-`Error` primary is thrown with the primary attached). A dedicated
test injects failing closes and checks that the worker is still interrupted and joined.

Adapted tests:

- `TransporteBootstrapTest`: the composition now has a per-capture policy, the
  `ResolvedorDnsDirecto` resolver and the 20 s default.
- `ServidorDnsDePrueba`:
  - an optional TCP writer;
  - TCP-first port choice. With the earlier UDP-first choice, one full Windows run had 11 setup
    errors, including 6 pre-PV-15 DNS cases: `BindException: Address already in use` in the
    fixture constructor. Ports still in TIME_WAIT were the working explanation; that was not
    proven;
  - since the fourth round (N3), dispersed fallback candidates; see "DNS test fixture: shared
    port allocation" below;
  - every candidate socket owned from creation (closed before a retry, closed with the original
    failure kept on a constructor failure);
  - a close failure never masks the original: the same instance is never added to itself, and a
    close `Error` over an ordinary failure is thrown with the original attached, as in production.

  No DNS assertion changed.

### DNS test fixture: shared port allocation

This section is test code only; the production resolver is unchanged.

`ServidorDnsDePrueba` needs one loopback number free for both TCP and UDP. Its search is bounded
at 20 candidates:

- **First candidate:** the number the OS picks for a TCP listener (`bind(0)`). This uses the
  OS's own range, whatever it is.
- **Every later candidate:** an explicit number from a stride walk over 49152–65535, the IANA
  dynamic range, which unprivileged loopback binds can use on Windows and Linux. The walk starts
  at a time-derived offset (`System.nanoTime()` modulo 16384, not cryptographically random; tests
  inject fixed seeds) and uses stride 10125, which is odd and close to 16384 divided by the golden
  ratio.
  - It never repeats a number within 16384 draws.
  - Any 19 consecutive draws are at least 553 numbers apart and cover the range evenly (the
    largest gap is 1,473).
  - A rejected contiguous region of up to 552 numbers therefore receives at most one fallback
    candidate. A number already tried is skipped, and a candidate source that only repeats ends
    the search with a clear error instead of looping.
- **Pairing:** each TCP candidate stays bound while UDP binds the same number.
- **Rejection:** a number is rejected on a UDP failure, or on a TCP failure for an explicit
  number. The candidate is closed before the next attempt. A TCP failure on the OS-picked number
  is not a collision and is reported unchanged.
- **Exhaustion:** reported as "no hay puerto libre UDP+TCP", listing every attempted number with
  where it failed (TCP or UDP) and the exception type, with the last exception as cause.
- **Evidence:** when a fallback was needed, the fixture writes one stderr line with the same data
  (numbers and exception types only).
- **Ownership:** every candidate is owned from its creation. If starting the server threads fails,
  the reserved sockets and any thread already started are closed and stopped, and the original
  failure is kept.
- **Shutdown:** `close()` attempts every step even when one fails. It closes both sockets and the
  connection being served (which ends a blocked read), then interrupts the fixture's threads (which
  ends a scripted pause) and joins them with a 5 s bound. A thread still alive is reported.
- **Seam:** the injected TCP, UDP, candidate-number and thread factories keep every case
  deterministic. No test depends on a real ephemeral port sequence or exhausts ports.

Limits: this lowers the risk of correlated rejections; it does not prove why the earlier setup
failures happened, and the search can still exhaust if every candidate is busy. The exhaustion
report then names every number tried. Explicit numbers can collide with ports other programs use,
in which case they are simply rejected. Linux has not run this fixture change.

Mutation checks, run in the validation copy only and restored byte-identical afterwards:

- the old DNS length-prefix read;
- a fresh deadline per hop;
- `setRemoveOnCancelPolicy(false)`;
- no `SSLPeerUnverifiedException` clause;
- close failures caught as `IOException` only;
- the old `>=` saturation boundary;
- failed fixture candidates not closed;
- a raw-close `IOException` ignored again (6 tests failed, the PV-14 permit test among them);
- a watchdog close failure not handed to the owner (exactly the watchdog-during-cleanup test);
- the identity message for every other `SSLException` (4 tests);
- the alarm cancelled before the closes (18 tests);
- self-suppression in the DNS fixture;
- a close `Error` not caught by the DNS fixture;
- a teardown that stops at its first failure;
- the cleanup failures already recorded in the primary not filtered out (5 tests, both A/B/A
  cases among them);
- the cycle check reduced to an identity check (the existing-links test);
- fixture candidates allowed to repeat. The targeted repeat-skipping test failed its assertion.
  The same run also had an allocation-related error in another fixture test; it is not counted as
  a second mutation-caused regression;
- the served connection not closed by `close()` (the blocked-read test; an earlier version of
  that test missed this mutation because its server thread was in an interruptible pause, so it
  was strengthened);
- the fixture threads not interrupted (the scripted-pause test);
- no cleanup on a thread-start failure (1 test).

Each made the corresponding new tests fail. The pre-fix `ClienteHttpFijado` (before the
suppression-graph correction) was also compiled from a clean build against the new tests:

- 9 of the 11 new suppression-graph tests failed and all 31 earlier tests passed.
- Of the 9 failures, 7 are a cycle or a duplicate link. The other 2 reflect the intended
  structure: distinct cleanup failures now sit directly under the chosen one, where some used to
  nest one level deeper.
- The 2 new tests that passed (direct self-suppression; raw-close `IOException` without a primary)
  guard behaviour that already worked.

Executed on Windows 11, Java 17.0.15, Maven 3.9.12 offline, Node 22, in the source-only copy
`C:\Users\jbrio\AppData\Local\Temp\sa-pv15-validation-ab1ef54749a173ae`. It is synchronised from
`git ls-files -co --exclude-standard` (no `.env`, targets, `node_modules` or E2E runtime
directories), and every file was verified identical to the working tree before the final run.

Final candidate, after the fifth review round, which changed only the second-family DNS deadline
test and this document. Fresh, on source verified byte-identical to the working tree:

| Validation | Result |
| --- | --- |
| Focused (19 classes), clean build | 299 passed, 0 failures |
| `ResolvedorDnsDirectoPlazoTest`, 20 separate fresh executions | 20 of 20 passed (6/6 each). Twenty passes do not establish that it can never fail. |
| Mutation: the resolver's "no family after expiry" check disabled (copy only, clean build, restored byte-identical) | the corrected test failed on the fixture's query inventory (`[1, 28]` instead of `[1]`), and so did two other deadline tests |
| Full backend `mvn -o clean package` (javac) | 1,522 passed, 0 failures/errors/skips; JAR built; no fixture fallback or exhaustion |

Frontend, E2E and migration results remain the retained ones listed below; nothing they exercise
changed.

Fourth-round candidate (N1a, N1b and N3). Executed on source verified byte-identical to the
working tree:

| Validation | Result |
| --- | --- |
| Focused (19 classes, as below), clean build, final source | 299 passed, 0 failures (an earlier run before the last test-only edits: 298) |
| DNS fixture users (4 classes, 48 tests), 30 fresh executions in three batches of 10 | batch 1: 8 passed, 2 with one error each in an adapted fixture test (since reworked); batches 2 and 3, on the corrected source: 20 of 20 passed |
| Full backend `mvn -o clean package` (javac), first run | 1,521 passed, 1 assertion failure: the deadline-test race described in the fourth round; BUILD FAILURE |
| Full backend `mvn -o clean package` (javac), after the test-only fix | 1,522 passed, 0 failures/errors/skips (baseline 1,426 + 96); JAR built |

Retained, not re-executed (unchanged since their last pass): frontend `npm ci`, unit tests
(185/185), build, lint and the high-severity audit gate; Playwright E2E (6 passed); the migration
validator (PHASE 0.8 PASSED). Since that pass, the only executable change outside test code is
`ClienteHttpFijado`, which belongs to the prepared transport that production does not wire. No
frontend source, configuration, dependency, migration or E2E harness changed.

Third-round candidate (the suppression-graph correction). The backend rows were executed on
source verified byte-identical to the working tree:

| Validation | Result |
| --- | --- |
| Focused: all acquisition, PV-13 and PV-14 classes, the fixture test and `DestinationPackageStaticTest` (19 classes) | 285 passed, 0 failures |
| Full backend `mvn -o clean package` (javac), first run | 1,505 passed, 3 errors: the N3 DNS fixture setup failure (below), in three `ResolvedorDnsDirectoPlazoTest` cases; BUILD FAILURE |
| Full backend `mvn -o clean package` (javac), second run, same source | 1,508 passed, 0 failures/errors/skips (baseline 1,426 + 82); JAR built |

Retained from the previous pass on the R1–R4 candidate, not re-executed: since then only
`ClienteHttpFijado` (the prepared, unwired transport), its backend test class and these two
documents changed. No frontend source, configuration, dependency, migration or test harness
changed, and production still wires `TransporteHttpNoDisponible` (checked again by the fresh
backend run):

| Validation | Result (retained) |
| --- | --- |
| Frontend `npm ci`, unit, build, lint | 185/185 tests in 16 files; build and lint passed |
| Frontend dependency audit | high-severity gate passed; the 8 existing moderate findings remain |
| Playwright E2E | 6 passed; temporary containers, volumes and processes 0; habitual database and `.env` untouched |
| Migration validator | PHASE 0.8 PASSED |

Third round, recorded as it happened:

- The focused run passed on its first attempt.
- **The first proof against the pre-fix code was invalid** and was discarded. Restoring the file
  with `cp -p` kept its old timestamp, so Maven's incremental compile did not recompile it and the
  tests ran against the fixed classes. It was redone with a clean build (results above).
  - The same restore method was used in the second round's mutation checks. Each of those
    mutations edited the file, which forced a recompile, so the mutant was tested.
  - After the last main-source mutation, the remaining fixture and teardown mutations ran with
    that mutant main class still compiled. Their tests do not use it.
  - All reported suite results come from clean builds.
- **The first clean full backend run had 3 errors**, the N3 setup failure.
  - Three fixture constructions in a row exhausted their 20 attempts within about 0.1 s. Each
    ended with `BindException: Address already in use: bind` from the UDP socket. The fifth
    test's fixture then started normally.
  - The second run, on identical source, passed 1,508/1,508. It ran alongside a read-only sampler
    of dynamic-range UDP endpoints (1,315 samples, about 4 per second). The largest contiguous
    busy block it saw was 9 ports, owned by svchost or by GPU overlay processes. None of the test
    JVMs owned one.
  - This shows no large block during a passing run. It does not show the state at the moments
    of failure, so the cause is still not established.

Fourth round (N1a, N1b and the N3 fixture correction), recorded as it happened:

- The focused run passed on its first attempt: 298 tests.
- **Proofs against the previous code**, each compiled from a clean build, with the source
  restored byte-identical afterwards:
  - The previous `ClienteHttpFijado` failed 7 of the 48 tests in its class:
    - the two N1a cases and the two N1b cases;
    - the three updated tests that now expect each failure attached directly to the thrown one.
  - The two remaining new tests guard behaviour that was already correct.
  - The previous fixture failed both scripted N3 scenarios, with exactly the observed symptom
    ("no hay puerto libre UDP+TCP"). Its earlier fixture tests passed.
- **One mutation was initially missed.** With the served connection left open, the close test
  still passed, because its server thread was in an interruptible pause. A second close test
  holds the thread in a socket read, which only closing the connection ends. It catches that
  mutation, and the first test catches a missing interrupt.
- **Stability, batch 1:** 10 fresh executions of the four fixture-using classes (48 tests each).
  - 8 runs passed.
  - Runs 2 and 8 each had 1 error, in one adapted fixture test. It had been changed to bind every
    candidate to the OS-chosen number, which reintroduced the old correlated behaviour into that
    test. It exhausted on consecutive numbers (50111–50131; 55247–55266), all rejected by UDP.
  - In those same two runs, every real fixture in the two resolver classes had its OS-chosen
    number rejected by UDP. The OS numbers were near-consecutive (50066 onwards; 55201 onwards).
    Each one started on its first or second dispersed candidate, so no real test failed.
  - Run 7 needed one fallback. The adapted test was then reworked onto the simulated environment.
- **Stability, batch 2:** 10 fresh executions on the corrected source, all passed (48/48 each).
  No real fixture needed a fallback.
  - A read-only sampler ran alongside (853 samples). Twice it saw an unrelated desktop
    application (not a JVM) holding a contiguous block of 20 UDP ports on 0.0.0.0. No test JVM
    owned any block.
  - So such blocks do occur on this machine. The batch-1 collisions were rejections observed at
    different moments, over several seconds. They do not show one simultaneously occupied block of
    ports, and they were not sampled, so what held those numbers is unknown.
- Twenty passing runs do not prove the failure cannot recur. What changed is that a rejected
  region is now left on the next candidate, and an exhaustion names every number tried.
- **The first clean full backend run failed one assertion**, in
  `ResolvedorDnsDirectoPlazoTest.theSecondFamilyIsNotStartedOnceTheSharedDeadlineHasExpired`: A was
  `FAILURE`, expected `ADDRESSES`, after 37 ms. No fixture fallback happened in that run. The cause
  is a scheduling race in that PV-15 test, not in the code under test:
  - The resolver sends the UDP query and only then sets its receive timeout from the deadline.
  - The test's script advances the shared manual clock in the fixture's server thread when the A
    query arrives. If that thread runs first, the resolver sees an expired deadline before
    waiting and correctly fails A.
  - A throwaway probe, run in the validation copy only and then deleted, moved the clock past the
    deadline on the k-th clock read (k = 1 to 6). Each k is a separate scenario:
    - k = 2: the deadline had expired before A started; no query was sent.
    - k = 3–4: the jump came after A was sent but before the resolver waited. A was `FAILURE`
      with only the A query sent, the observed signature.
    - k = 5: the jump came after A's answer. A was `ADDRESSES`, and only the A query was sent.
    - k = 1 and k = 6: both families were queried (`consultas=[1, 28]`). At k = 1 the jump hit
      the deadline's own starting read, so it never expired. At k = 6 the jump came after the
      AAAA family had already passed its expiry check and started.

    These observations show that A's outcome depends on where the expiry falls. They are not
    evidence of a production deadline violation: AAAA was queried only when the deadline had not
    expired as that family started.
  - First correction (fourth round): the test's script answered the A query 250 ms late. A sleep
    gives no happens-before relation, so a resolver thread descheduled between sending and setting
    its timeout could still see the jump first.
  - Final correction (fifth round): the sleep was removed. The test now asserts what the contract
    requires rather than one interleaving:
    - the capture deadline (the instance on the test's clock) has expired;
    - A is `ADDRESSES` or `FAILURE`;
    - AAAA is `FAILURE`;
    - the fixture recorded exactly one A query and no AAAA query;
    - through the unchanged policy, `DNS_FAILURE`, with exactly one more A query and still no
      AAAA query.
  - Requiring A to succeed was never part of the contract. Once the shared deadline expires
    during A's exchange, the resolver may either read the answer it was already waiting for or
    refuse to wait at all. The guarantee under test is that the second family is not started
    after expiry. No production hook was needed, and the resolver is unchanged. Successful
    answers remain covered by the other resolver tests.

Initial failures in the second correction round, all recorded:

- **First focused run: 273/274.** The new teardown test expected the two injected close failures
  side by side on the primary. Like production, the helper attaches the accumulated failure,
  which carries the second one. The expectation was corrected; both failures are still asserted.
- **Second focused run: 273/274.** Observed:
  - `ClienteHttpLoopbackTest.theDirectResolverTalksOnlyToTheConfiguredServerAndResolvesNothingElse`
    failed in DNS fixture setup with "no hay puerto libre UDP+TCP". The fixture had exhausted
    its bounded port-selection attempts.
  - The final recorded cause was `java.net.BindException: Address already in use: bind`, from
    creating the UDP socket.
  - The next run, with unchanged code, passed 274/274. The same failure recurred once later, in
    the third round's first full run (see above).

  The cause is **not established**:
  - Considered, unproven hypotheses: another process holding UDP ports at that moment, or
    correlated TCP port numbers. A separate probe afterwards got 40 consecutive TCP numbers for
    40 binds; whether that played a role is unknown.
  - Checked: the production resolver closes its UDP socket with try-with-resources, and the
    fixture closes UDP and TCP together. A dynamic-range UDP snapshot taken later showed no large
    block, but it says nothing about the state at the moment of failure.
  - The fixture's port allocation changed during PV-15 (TCP-first, from the first candidate on),
    so this failure has not been shown to occur at the baseline.

  In the third round it was deferred as **N3**, with the fixture deliberately left unchanged.
  - It recurred once in that round. An external sampler during the next, passing run saw nothing
    unusual, and evidence at the moment of failure was missing.
  - By then it had occurred in 2 of the 8 focused or full backend suites run in this session
    (mutation runs not counted).

  The fourth-round review made N3 commit-blocking, and the fixture was corrected (see "DNS test
  fixture: shared port allocation" above):
  - Against the old fixture, a scripted scenario reproduces exactly the observed symptom: an OS
    handing out consecutive numbers into a busy UDP region, or the same number again.
  - The corrected fixture leaves that region on its next candidate.
  - The cause of the real occurrences is still not established, but the fixture now reports the
    numbers it tried if it ever exhausts. A later investigation should add the UDP and TCP
    endpoints in use (`Get-NetUDPEndpoint`, `Get-NetTCPConnection` or `netstat -ano`) and the
    concurrent processes.
- **One frontend unit run** started only 4 of 16 files: Vitest timed out starting fork workers
  just after `npm ci`. Its 28 tests passed and no assertion failed. The final pass ran all 16
  files (185 tests).

Earlier full runs of the first candidate:

- 1,471 passed in the end;
- one run failed `CapturaContractTest` (a network-API name in the core capture package, fixed in
  `PlazoCaptura`) and the TLS certificate loopback case (an abrupt close order, reverted to the
  graceful order);
- another failed only because of the DNS fixture port setup above.

All of this is local Windows evidence. Linux and the hosted GitHub Actions jobs have not run this
uncommitted change; their results remain UNVERIFIED until they do.

## Non-guarantees and deferred work

Not provided:

- hard real-time termination; the 1 ms rounding is not a lateness bound;
- bounding or stopping a resolver that ignores the deadline (post-check only), or any other
  blocking adapter;
- a timeout on the tiny DNS TCP query write;
- stopping CPU work that is bounded by size, not time (decompression, decoding);
- socket closure on interruption, since classic JDK 17 sockets ignore it (interruption is
  observed at the next checkpoint or when the blocked wait ends);
- reporting a watchdog close failure that happens after the owner's raw-close handoff (nobody
  reads it; an unchecked one stays in that task's future);
- reporting an ordinary cleanup failure that already contains the primary failure (it cannot be
  attached without a cycle), or removing a loop already present in foreign exceptions;
- keeping the primary reachable when the cleanup `Error` thrown in its place is already inside
  the primary (that link would close a loop);
- a proven cause for the intermittent DNS fixture setup failure, or a guarantee that the
  corrected fixture can never exhaust its candidates (N3, below);
- observing a failed descriptor release inside JSSE's own close of the raw socket, and any proof
  that the operating system released a connection whose `close()` returned normally (see
  "Remaining release uncertainty");
- Scan-linked or external cancellation;
- extraction, analysis or persistence budgets;
- per-host or tenant limits;
- watchdog Spring lifecycle;
- an operator property for the deadline.

Real acquisition is not enabled: production still uses `TransporteHttpNoDisponible`.

## Deployment blockers

D1, D2 and D9 remain OPEN and R-EGR remains BLOCKED. An application deadline does not prove DNS
routing, TLS deployment or egress isolation and does not replace them. Real acquisition must
not be enabled because these tests pass.

## Rollback

Revert the PV-15 changes. There is no schema, property, endpoint, dependency or frontend change,
and production keeps `TransporteHttpNoDisponible` either way. Keep real acquisition disabled.
