package com.smartaudits.v2.model;

import com.smartaudits.model.Role;
import com.smartaudits.model.Usuario;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Service behaviour on H2 without any outer test transaction, so every commit is real. MariaDB-only
 * guarantees (generated active UNIQUE, CHECK, composite FK) live in ScanMariaDbProbe; nothing here simulates
 * them. Lock waits are proven by H2 itself (INFORMATION_SCHEMA.SESSIONS: BLOCKED by the holder's session while
 * executing the Audit lock request), never inferred from elapsed time. */
@DataJpaTest(showSql = false, properties = {
        "spring.config.location=optional:classpath:/v8-scan-service-no-config.properties",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
        "spring.sql.init.mode=never",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "logging.level.root=WARN", "logging.level.org.springframework=WARN"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(AuditScanService.class)
class AuditScanServiceTest {
    private static final Instant T0 = Instant.parse("2024-02-29T23:58:57.987654321Z");

    @Autowired EntityManagerFactory emf;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired AuditScanService springService;
    @Autowired DataSource dataSource;

    private TransactionTemplate tx;
    private MutableClock clock;
    private AuditScanService service;
    private long owner, ownerB, revoked, inactive, admin, orgA, orgB, siteA, siteArchived, siteB;

    static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(long micros) { now = now.plusNanos(micros * 1000); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private EntityManager em() { return SharedEntityManagerCreator.createSharedEntityManager(emf); }

    private long user(String name, Role role, boolean active) {
        return tx.execute(s -> {
            Usuario u = new Usuario();
            u.setNombre(name);
            u.setEmail(name + "@v8.invalid");
            u.setPassword("unused");
            u.setRole(role);
            u.setActivo(active);
            u.setProtegido(false);
            u.setFechaRegistro(LocalDateTime.of(2001, 2, 3, 4, 5, 6));
            em().persist(u);
            em().flush();
            return u.getId();
        });
    }

    @BeforeEach void fixtures() {
        tx = new TransactionTemplate(transactionManager);
        clock = new MutableClock(T0);
        service = new AuditScanService(em(), transactionManager, clock);
        owner = user("owner", Role.CLIENTE, true);
        ownerB = user("owner-b", Role.CLIENTE, true);
        revoked = user("revoked", Role.CLIENTE, true);
        inactive = user("inactive", Role.CLIENTE, false);
        admin = user("admin", Role.ADMIN, true);
        tx.executeWithoutResult(s -> {
            EntityManager em = em();
            Organization a = new Organization("Org A", null, T0);
            Organization b = new Organization("Org B", null, T0);
            em.persist(a);
            em.persist(b);
            em.flush();
            orgA = a.getId();
            orgB = b.getId();
            for (long[] m : new long[][]{{orgA, owner}, {orgB, ownerB}, {orgA, revoked}, {orgA, inactive}}) {
                em.persist(new OrganizationMembership(em.getReference(Organization.class, m[0]), em.getReference(Usuario.class, m[1]), T0));
            }
            Site active = new Site(a, "Active", "https://a.invalid/", em.getReference(Usuario.class, owner), T0);
            Site archived = new Site(a, "Archived", "https://archived.invalid/", em.getReference(Usuario.class, owner), T0);
            Site foreign = new Site(b, "Foreign", "https://b.invalid/", em.getReference(Usuario.class, ownerB), T0);
            em.persist(active);
            em.persist(archived);
            em.persist(foreign);
            em.flush();
            siteA = active.getId();
            siteArchived = archived.getId();
            siteB = foreign.getId();
            em.createNativeQuery("UPDATE organization_memberships SET status='REVOKED' WHERE user_id=" + revoked).executeUpdate();
            em.createNativeQuery("UPDATE sites SET status='ARCHIVED' WHERE id=" + siteArchived).executeUpdate();
        });
    }

    /** Rows are removed even if the session check fails, and no H2 session may outlive a test. While a worker thread is
     * still running it may still use the rows: they are then left in place and the test fails, its cleanup not
     * validated. */
    @AfterEach void cleanup() throws Exception {
        if (Workers.unfinished()) {
            throw new AssertionError("Worker threads still running: rows left in place, cleanup not validated");
        }
        Throwable failure = null;
        try {
            tx.executeWithoutResult(s -> {
                for (String table : List.of("scans", "audits", "sites", "organization_memberships", "organizations", "usuarios")) {
                    em().createNativeQuery("DELETE FROM " + table).executeUpdate();
                }
            });
        } catch (Throwable deleteFailure) {
            failure = add(failure, deleteFailure);
        }
        try {
            assertNoOtherSessions();
        } catch (Throwable leak) {
            failure = add(failure, leak);
        }
        if (failure != null) throw rethrow(failure);
    }

    private long count(String table) {
        return tx.execute(s -> ((Number) em().createNativeQuery("SELECT COUNT(*) FROM " + table).getSingleResult()).longValue());
    }

    private String scanRow(long scanId) {
        return tx.execute(s -> {
            Object[] r = (Object[]) em().createNativeQuery(
                    "SELECT status, started_at IS NULL, finished_at IS NULL, attempt_number FROM scans WHERE id=" + scanId).getSingleResult();
            return r[0] + "|started=" + !((Boolean) r[1]) + "|finished=" + !((Boolean) r[2]) + "|attempt=" + r[3];
        });
    }

    /** Executor-side transitions do not exist as a service yet (Phase 4): drive the entity in a committed transaction. */
    private void transition(long scanId, Consumer<Scan> action) {
        tx.executeWithoutResult(s -> {
            Scan scan = em().find(Scan.class, scanId);
            action.accept(scan);
            em().flush();
        });
    }

    private Instant later() { clock.advance(1); return clock.instant(); }

    @Test void createAuditCommitsTheAuditAndItsFirstScanTogether() {
        AuditScanService.AuditCreation created = service.createAudit(orgA, siteA, owner);
        assertTrue(created.auditId() > 0 && created.scanId() > 0);
        assertEquals(1, created.attemptNumber());
        assertEquals(ScanStatus.PENDING, created.status());
        assertEquals(1, count("audits"));
        assertEquals(1, count("scans"));
        tx.executeWithoutResult(s -> {
            Object[] row = (Object[]) em().createNativeQuery("SELECT s.audit_id, s.organization_id, s.created_by_user_id, "
                    + "a.site_id FROM scans s JOIN audits a ON a.id=s.audit_id WHERE s.id=" + created.scanId()).getSingleResult();
            assertEquals(created.auditId(), ((Number) row[0]).longValue());
            assertEquals(orgA, ((Number) row[1]).longValue());
            assertEquals(owner, ((Number) row[2]).longValue());
            assertEquals(siteA, ((Number) row[3]).longValue());
            Audit audit = em().find(Audit.class, created.auditId());
            Scan scan = em().find(Scan.class, created.scanId());
            assertEquals(Instant.parse("2024-02-29T23:58:57.987654Z"), audit.getCreatedAt());
            assertEquals(audit.getCreatedAt(), scan.getCreatedAt());
            assertEquals(0L, scan.getRowVersion());
        });
        assertEquals("PENDING|started=false|finished=false|attempt=1", scanRow(created.scanId()));
    }

    @Test void createAuditRejectsUnauthorizedActorsAsNotFoundWithoutWriting() {
        for (long actor : new long[]{admin, revoked, inactive, ownerB, Long.MAX_VALUE}) {
            assertThrows(EntityNotFoundException.class, () -> service.createAudit(orgA, siteA, actor), "actor " + actor);
        }
        assertThrows(EntityNotFoundException.class, () -> service.createAudit(orgA, siteB, owner), "foreign Site");
        assertThrows(EntityNotFoundException.class, () -> service.createAudit(orgB, siteB, owner), "not a member of B");
        assertThrows(EntityNotFoundException.class, () -> service.createAudit(orgA, Long.MAX_VALUE, owner), "missing Site");
        assertThrows(EntityNotFoundException.class, () -> service.createAudit(Long.MAX_VALUE, siteA, owner), "missing organization");
        assertEquals(0, count("audits"));
        assertEquals(0, count("scans"));
    }

    @Test void createAuditRejectsArchivedSites() {
        assertThrows(IllegalStateException.class, () -> service.createAudit(orgA, siteArchived, owner));
        assertEquals(0, count("audits"));
        assertEquals(0, count("scans"));
    }

    /** Test-only H2 trigger: fails the INSERT of the armed actor's Scans after checking, in the same transaction, that
     * the Audit row was already written. The message carries a per-test marker; SQLSTATE 45000 and code 1644 as on MariaDB. */
    public static final class InjectedFirstScanFailure implements org.h2.api.Trigger {
        private static volatile long actor = Long.MIN_VALUE;
        private static volatile String marker = "";
        private int auditColumn;
        private int organizationColumn;
        private int creatorColumn;
        private int attemptColumn;

        static void arm(long armedActor, String armedMarker) {
            marker = armedMarker;
            actor = armedActor;
        }

        static void disarm() {
            actor = Long.MIN_VALUE;
        }

        @Override public void init(Connection conn, String schema, String trigger, String table, boolean before, int type) throws SQLException {
            Map<String, Integer> positions = new HashMap<>();
            try (PreparedStatement p = conn.prepareStatement(
                    "SELECT COLUMN_NAME, ORDINAL_POSITION FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=?")) {
                p.setString(1, schema);
                p.setString(2, table);
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) positions.put(r.getString(1), r.getInt(2) - 1);
                }
            }
            auditColumn = positions.get("AUDIT_ID");
            organizationColumn = positions.get("ORGANIZATION_ID");
            creatorColumn = positions.get("CREATED_BY_USER_ID");
            attemptColumn = positions.get("ATTEMPT_NUMBER");
        }

        @Override public void fire(Connection conn, Object[] oldRow, Object[] newRow) throws SQLException {
            if (((Number) newRow[creatorColumn]).longValue() != actor) return;
            long auditId = ((Number) newRow[auditColumn]).longValue();
            try (PreparedStatement p = conn.prepareStatement("SELECT COUNT(*) FROM audits WHERE id=? AND organization_id=? AND created_by_user_id=?")) {
                p.setLong(1, auditId);
                p.setLong(2, ((Number) newRow[organizationColumn]).longValue());
                p.setLong(3, actor);
                try (ResultSet r = p.executeQuery()) {
                    assertTrue(r.next());
                    String detail = r.getLong(1) == 1 ? " audit=" + auditId + " attempt=" + newRow[attemptColumn] : " audit-missing";
                    throw new SQLException(marker + detail, "45000", 1644);
                }
            }
        }
    }

    /** Every SQLException behind the failure must be the injected one (marker, SQLSTATE 45000, code 1644) and name
     * attempt 1 of an Audit present in the same transaction; authorization, state or transaction failures are rejected. */
    private static long injectedFirstScanFailure(Throwable error, String marker) {
        assertFalse(error instanceof TransactionException, "Transaction infrastructure failure instead of the injected INSERT failure: " + error);
        assertFalse(error instanceof EntityNotFoundException || error instanceof IllegalStateException, "Rejected before persisting: " + error);
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<SQLException> chain = new ArrayList<>();
        for (Throwable t = error; t != null && seen.add(t); t = t.getCause()) {
            if (t instanceof SQLException sql) chain.add(sql);
        }
        assertFalse(chain.isEmpty(), "No SQLException behind the failure: " + error);
        for (SQLException sql : chain) {
            assertTrue(sql.getMessage() != null && sql.getMessage().contains(marker), "Unexpected SQL failure: " + sql);
            assertEquals("45000", sql.getSQLState(), sql.toString());
            assertEquals(1644, sql.getErrorCode(), sql.toString());
        }
        Matcher m = Pattern.compile(Pattern.quote(marker) + " audit=([0-9]+) attempt=([0-9]+)").matcher(chain.get(0).getMessage());
        assertTrue(m.find(), "The trigger did not find the Audit in the same transaction: " + chain.get(0).getMessage());
        assertEquals("1", m.group(2), "The failed INSERT must be the first Scan");
        return Long.parseLong(m.group(1));
    }

    @Test void failedFirstScanInsertRollsBackTheAudit() throws Exception {
        String marker = "v8-h2-scan-fault-" + UUID.randomUUID();
        InjectedFirstScanFailure.arm(owner, marker);
        long failedAudit;
        Throwable primary = null;
        try {
            tx.executeWithoutResult(s -> em().createNativeQuery("CREATE TRIGGER v8_first_scan_fault BEFORE INSERT ON scans FOR EACH ROW CALL '"
                    + InjectedFirstScanFailure.class.getName() + "'").executeUpdate());
            RuntimeException error = assertThrows(RuntimeException.class, () -> service.createAudit(orgA, siteA, owner));
            failedAudit = injectedFirstScanFailure(error, marker);
        } catch (Throwable failure) {
            primary = failure;
            throw failure;
        } finally {
            InjectedFirstScanFailure.disarm();
            try {
                tx.executeWithoutResult(s -> em().createNativeQuery("DROP TRIGGER IF EXISTS v8_first_scan_fault").executeUpdate());
                assertEquals("0", observe("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TRIGGERS WHERE TRIGGER_NAME='V8_FIRST_SCAN_FAULT'"));
            } catch (Throwable cleanup) {
                if (primary == null) throw cleanup;
                primary.addSuppressed(cleanup);
            }
        }
        assertEquals("0", observe("SELECT COUNT(*) FROM audits"), "The Audit must not survive its failed first Scan");
        assertEquals("0", observe("SELECT COUNT(*) FROM scans"));
        // Positive control, same actor, injection removed: the pair commits and an independent connection sees it.
        AuditScanService.AuditCreation recovered = service.createAudit(orgA, siteA, owner);
        assertTrue(recovered.auditId() > failedAudit, "The failed attempt had consumed an Audit identity");
        assertEquals(owner + "|1|PENDING|" + owner, observe("SELECT CAST(a.created_by_user_id AS VARCHAR) || '|' || "
                + "CAST(s.attempt_number AS VARCHAR) || '|' || s.status || '|' || CAST(s.created_by_user_id AS VARCHAR) "
                + "FROM audits a JOIN scans s ON s.organization_id=a.organization_id AND s.audit_id=a.id WHERE a.id=" + recovered.auditId()));
        assertEquals("1", observe("SELECT COUNT(*) FROM audits"));
        assertEquals("1", observe("SELECT COUNT(*) FROM scans"));
    }

    @Test void retryFollowsAdrRulesAndNumbersAttemptsByAttemptNumber() {
        AuditScanService.AuditCreation created = service.createAudit(orgA, siteA, owner);
        assertThrows(IllegalStateException.class, () -> service.retryScan(orgA, created.auditId(), owner), "PENDING is active");
        transition(created.scanId(), scan -> scan.start(later()));
        assertThrows(IllegalStateException.class, () -> service.retryScan(orgA, created.auditId(), owner), "RUNNING is active");
        transition(created.scanId(), scan -> scan.fail(later()));
        AuditScanService.ScanOutcome second = service.retryScan(orgA, created.auditId(), owner);
        assertEquals(2, second.attemptNumber());
        assertEquals(ScanStatus.PENDING, second.status());
        assertEquals(created.auditId(), second.auditId());
        AuditScanService.ScanOutcome cancelled = service.cancelScan(orgA, second.scanId(), owner);
        assertEquals(ScanStatus.CANCELLED, cancelled.status());
        AuditScanService.ScanOutcome third = service.retryScan(orgA, created.auditId(), owner);
        assertEquals(3, third.attemptNumber());
        transition(third.scanId(), scan -> { scan.start(later()); scan.succeed(later()); });
        assertThrows(IllegalStateException.class, () -> service.retryScan(orgA, created.auditId(), owner), "SUCCEEDED Audit");
        assertEquals(3, count("scans"));
        assertEquals(1, count("audits"));
    }

    @Test void retryRejectsArchivedSitesAndAttemptOverflow() {
        AuditScanService.AuditCreation created = service.createAudit(orgA, siteA, owner);
        transition(created.scanId(), scan -> { scan.start(later()); scan.fail(later()); });
        tx.executeWithoutResult(s -> em().createNativeQuery("UPDATE sites SET status='ARCHIVED' WHERE id=" + siteA).executeUpdate());
        assertThrows(IllegalStateException.class, () -> service.retryScan(orgA, created.auditId(), owner));
        tx.executeWithoutResult(s -> em().createNativeQuery("UPDATE sites SET status='ACTIVE' WHERE id=" + siteA).executeUpdate());
        tx.executeWithoutResult(s -> em().createNativeQuery("UPDATE scans SET attempt_number=2147483647 WHERE id=" + created.scanId()).executeUpdate());
        assertThrows(IllegalStateException.class, () -> service.retryScan(orgA, created.auditId(), owner), "INT overflow");
        assertEquals(1, count("scans"));
    }

    @Test void retryAndCancelAreTenantScopedAndAuthorizedEveryCall() {
        AuditScanService.AuditCreation created = service.createAudit(orgA, siteA, owner);
        AuditScanService.AuditCreation foreign = service.createAudit(orgB, siteB, ownerB);
        transition(created.scanId(), scan -> { scan.start(later()); scan.fail(later()); });
        for (long actor : new long[]{admin, revoked, inactive, ownerB}) {
            assertThrows(EntityNotFoundException.class, () -> service.retryScan(orgA, created.auditId(), actor));
            assertThrows(EntityNotFoundException.class, () -> service.cancelScan(orgA, foreign.scanId(), actor));
        }
        assertThrows(EntityNotFoundException.class, () -> service.retryScan(orgB, created.auditId(), ownerB), "foreign Audit id");
        assertThrows(EntityNotFoundException.class, () -> service.retryScan(orgA, foreign.auditId(), owner), "Audit of B through A");
        assertThrows(EntityNotFoundException.class, () -> service.cancelScan(orgA, foreign.scanId(), owner), "Scan of B through A");
        assertThrows(EntityNotFoundException.class, () -> service.cancelScan(orgB, foreign.scanId(), owner), "not a member of B");
        assertEquals("PENDING|started=false|finished=false|attempt=1", scanRow(foreign.scanId()));
        // Revocation takes effect on the next call.
        tx.executeWithoutResult(s -> em().createNativeQuery("UPDATE organization_memberships SET status='REVOKED' WHERE user_id=" + owner).executeUpdate());
        assertThrows(EntityNotFoundException.class, () -> service.retryScan(orgA, created.auditId(), owner));
        assertEquals(2, count("scans"), "No Scan was added by rejected calls");
    }

    @Test void cancelHonoursTheStateMachineIncludingArchivedSites() {
        AuditScanService.AuditCreation pending = service.createAudit(orgA, siteA, owner);
        AuditScanService.ScanOutcome cancelled = service.cancelScan(orgA, pending.scanId(), owner);
        assertEquals(ScanStatus.CANCELLED, cancelled.status());
        assertEquals("CANCELLED|started=false|finished=true|attempt=1", scanRow(pending.scanId()));
        assertThrows(IllegalStateException.class, () -> service.cancelScan(orgA, pending.scanId(), owner), "second cancel");
        assertEquals("CANCELLED|started=false|finished=true|attempt=1", scanRow(pending.scanId()));
        AuditScanService.AuditCreation running = service.createAudit(orgA, siteA, owner);
        transition(running.scanId(), scan -> scan.start(later()));
        tx.executeWithoutResult(s -> em().createNativeQuery("UPDATE sites SET status='ARCHIVED' WHERE id=" + siteA).executeUpdate());
        clock.advance(5);
        assertEquals(ScanStatus.CANCELLED, service.cancelScan(orgA, running.scanId(), owner).status(), "cancel allowed on ARCHIVED Site");
        assertEquals("CANCELLED|started=true|finished=true|attempt=1", scanRow(running.scanId()));
        tx.executeWithoutResult(s -> em().createNativeQuery("UPDATE sites SET status='ACTIVE' WHERE id=" + siteA).executeUpdate());
        AuditScanService.AuditCreation succeeded = service.createAudit(orgA, siteA, owner);
        transition(succeeded.scanId(), scan -> { scan.start(later()); scan.succeed(later()); });
        assertThrows(IllegalStateException.class, () -> service.cancelScan(orgA, succeeded.scanId(), owner), "terminal");
        assertEquals("SUCCEEDED|started=true|finished=true|attempt=1", scanRow(succeeded.scanId()));
    }

    @Test void refusesToJoinAnOuterTransaction() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> tx.executeWithoutResult(s -> service.createAudit(orgA, siteA, owner)));
        assertTrue(refused.getMessage().contains("outside any transaction"));
        assertEquals(0, count("audits"));
        assertEquals(0, count("scans"));
    }

    /** Open EntityManager in View binds an EntityManager to the thread without a transaction. */
    @Test void worksUnderOpenEntityManagerInViewWithoutTrustingCachedState() throws Exception {
        AuditScanService.AuditCreation created = service.createAudit(orgA, siteA, owner);
        EntityManager bound = emf.createEntityManager();
        TransactionSynchronizationManager.bindResource(emf, new EntityManagerHolder(bound));
        try {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            Scan cached = bound.find(Scan.class, created.scanId());
            assertEquals(ScanStatus.PENDING, cached.getStatus());
            // Another thread (no bound context) starts the Scan; the bound context still caches PENDING.
            onOwnThread(new Workers(1), () -> {
                transition(created.scanId(), scan -> scan.start(T0.plusSeconds(1)));
                return null;
            }, 30_000);
            assertEquals(ScanStatus.PENDING, cached.getStatus(), "Bound context is stale by construction");
            clock.advance(2_000_000);
            assertEquals(ScanStatus.CANCELLED, service.cancelScan(orgA, created.scanId(), owner).status());
            assertEquals("CANCELLED|started=true|finished=true|attempt=1", scanRow(created.scanId()),
                    "The cancellation used the refreshed RUNNING state, not the cached PENDING");
            // Unflushed caller changes in the bound context are refused, never committed by the service.
            Organization organization = bound.find(Organization.class, orgA);
            organization.rename("Dirty caller change", T0.plusSeconds(9));
            IllegalStateException dirty = assertThrows(IllegalStateException.class, () -> service.createAudit(orgA, siteA, owner));
            assertTrue(dirty.getMessage().contains("unflushed changes"));
            assertEquals("Org A", tx.execute(s -> (String) em().createNativeQuery("SELECT name FROM organizations WHERE id=" + orgA).getSingleResult()));
            assertEquals(1, count("audits"));
            // After a rolled-back operation the pre-bound context is cleared, so a failed context is not reused.
            assertFalse(bound.contains(organization), "Spring must clear the pre-bound EntityManager on rollback");
            Organization reloaded = bound.find(Organization.class, orgA);
            assertThrows(EntityNotFoundException.class, () -> service.createAudit(orgA, siteA, admin));
            assertFalse(bound.contains(reloaded));
            assertEquals(1, count("audits"));
        } finally {
            TransactionSynchronizationManager.unbindResource(emf);
            bound.close();
        }
    }

    /** Reads one value through an independent connection, outside the service and the shared EntityManager. */
    private String observe(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            assertTrue(r.next(), sql);
            String value = r.getString(1);
            assertFalse(r.next(), sql);
            return value;
        }
    }

    private String attempts(long auditId) throws SQLException {
        return observe("SELECT LISTAGG(CAST(attempt_number AS VARCHAR) || ':' || status, ',') WITHIN GROUP (ORDER BY attempt_number) "
                + "FROM scans WHERE audit_id=" + auditId);
    }

    private static long value(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            assertTrue(r.next(), sql);
            long value = r.getLong(1);
            assertFalse(r.next(), sql);
            return value;
        }
    }

    /** The first failure stays primary; every later one becomes suppressed on it (null adds nothing). */
    private static Throwable add(Throwable failure, Throwable next) {
        if (next == null) return failure;
        if (failure == null) return next;
        if (next != failure) failure.addSuppressed(next);
        return failure;
    }

    /** Throws an Error as is and returns an Exception to throw as is; anything else is wrapped. */
    private static Exception rethrow(Throwable failure) {
        if (failure instanceof Error error) throw error;
        if (failure instanceof Exception exception) return exception;
        return new IllegalStateException(failure);
    }

    /** Service outcome as text; only the outcomes the service defines are accepted. */
    private static String outcome(Callable<?> call) {
        try {
            call.call();
            return "OK";
        } catch (EntityNotFoundException notFound) {
            return "NOT_FOUND";
        } catch (IllegalStateException rejected) {
            return "REJECTED:" + rejected.getMessage();
        } catch (Exception other) {
            throw new AssertionError("Unexpected failure", other);
        }
    }

    /** Worker threads of one test, as daemon threads (one that cannot be stopped never keeps the JVM alive). close() is
     * safe against an interrupt of the thread that calls it: shutdown() and a bounded wait (grace); if workers are still
     * running, or that wait is interrupted, shutdownNow() (tasks never started are cancelled) and a second bounded wait
     * that keeps waiting through further interrupts until its deadline; the interrupt status is restored before close()
     * returns. A worker that needed shutdownNow(), an interrupt of the closing thread, every task failure and a worker
     * still alive at the end are all reported, the first one primary (as suppressed when another failure is already
     * propagating): close() never passes after an incomplete stop. While workers are still alive (unfinished()),
     * cleanup() leaves the rows in place and fails. Thread.stop() is never used. */
    private static final class Workers implements AutoCloseable {
        private static final Set<Workers> UNFINISHED = ConcurrentHashMap.newKeySet();
        private final ExecutorService pool;
        private final long graceMillis;
        private final long stopMillis;
        private final List<Future<?>> tasks = new ArrayList<>();

        Workers(int threads) { this(threads, 10_000, 30_000); }

        Workers(int threads, long graceMillis, long stopMillis) {
            this.pool = Executors.newFixedThreadPool(threads, task -> {
                Thread thread = new Thread(task, "v8-test-worker");
                thread.setDaemon(true);
                return thread;
            });
            this.graceMillis = graceMillis;
            this.stopMillis = stopMillis;
            UNFINISHED.add(this);
        }

        /** Whether any worker thread created by these tests may still be running. */
        static boolean unfinished() {
            UNFINISHED.removeIf(workers -> workers.pool.isTerminated());
            return !UNFINISHED.isEmpty();
        }

        synchronized <T> Future<T> submit(Callable<T> task) {
            Future<T> future = pool.submit(task);
            tasks.add(future);
            return future;
        }

        boolean isTerminated() { return pool.isTerminated(); }

        /** Bounded wait, after close(), until every worker thread has ended. */
        boolean awaitStopped(long millis) throws InterruptedException {
            return pool.awaitTermination(millis, TimeUnit.MILLISECONDS);
        }

        @Override public void close() throws Exception {
            Throwable failure = null;
            boolean interrupted = false;
            pool.shutdown();
            try {
                if (!pool.awaitTermination(graceMillis, TimeUnit.MILLISECONDS)) {
                    failure = add(failure, new AssertionError("Workers still running after " + graceMillis + " ms; interrupted (shutdownNow)"));
                }
            } catch (InterruptedException closing) {
                interrupted = true;
                failure = add(failure, new AssertionError("Closing thread interrupted while waiting for the workers; they are "
                        + "interrupted (shutdownNow)", closing));
            }
            if (!pool.isTerminated()) {
                for (Runnable neverStarted : pool.shutdownNow()) {
                    if (neverStarted instanceof Future<?> queued) queued.cancel(false);
                }
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(stopMillis);
                long left;
                while (!pool.isTerminated() && (left = deadline - System.nanoTime()) > 0) {
                    try {
                        pool.awaitTermination(left, TimeUnit.NANOSECONDS);
                    } catch (InterruptedException again) {
                        interrupted = true;
                        failure = add(failure, new AssertionError("Closing thread interrupted after shutdownNow; kept waiting for "
                                + "the workers until the deadline", again));
                    }
                }
            }
            if (Thread.interrupted()) { // An interrupt that a wait reported by returning with the status set again.
                interrupted = true;
                failure = add(failure, new AssertionError("Closing thread interrupted while closing the workers"));
            }
            boolean stopped = pool.isTerminated();
            List<Future<?>> submitted;
            synchronized (this) {
                submitted = new ArrayList<>(tasks);
            }
            int running = 0;
            for (Future<?> task : submitted) {
                if (!task.isDone()) {
                    running++;
                    continue;
                }
                try {
                    task.get();
                } catch (ExecutionException taskFailure) {
                    failure = add(failure, taskFailure.getCause());
                } catch (CancellationException cancelled) {
                    failure = add(failure, cancelled);
                } catch (InterruptedException closing) { // get() of a completed task does not wait; kept all the same.
                    interrupted = true;
                    failure = add(failure, closing);
                }
            }
            if (!stopped) {
                failure = add(failure, new AssertionError("Workers still running after shutdownNow and " + stopMillis + " ms ("
                        + running + " task(s) unfinished): cleanup is not validated; the rows they may use are left in place"));
            }
            if (interrupted) Thread.currentThread().interrupt();
            if (failure != null) throw rethrow(failure);
        }
    }

    @FunctionalInterface private interface Step { void run() throws Exception; }

    /** Runs a step on a thread of its own, the closing thread, so that a test can interrupt it while it closes workers;
     * keeps what the step threw and whether that thread's interrupt status was set when the step returned. */
    private static final class Closer {
        private final Thread thread;
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile Throwable thrown;
        private volatile boolean interruptedAfter;

        Closer(Step step) {
            thread = new Thread(() -> {
                try {
                    step.run();
                } catch (Throwable failure) {
                    thrown = failure;
                } finally {
                    interruptedAfter = Thread.currentThread().isInterrupted();
                    done.countDown();
                }
            }, "v8-test-closer");
            thread.setDaemon(true);
            thread.start();
        }

        /** Bounded wait until the closing thread is parked in a timed wait: within close() the only ones are the pool's
         * awaitTermination calls. */
        void awaitTimedWait() {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (thread.getState() != Thread.State.TIMED_WAITING) {
                assertTrue(thread.isAlive(), "The closing thread ended before waiting");
                assertTrue(System.nanoTime() < deadline, "The closing thread never waited: " + thread.getState());
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
        }

        void interrupt() { thread.interrupt(); }

        /** What the step threw, once it has returned (bounded by the waits of close() itself). */
        Throwable result() throws InterruptedException {
            assertTrue(done.await(60, TimeUnit.SECONDS), "The closing thread returned within its bounded waits");
            return thrown;
        }

        boolean interruptedAfter() { return interruptedAfter; }
    }

    /** Runs one step on a thread of `workers` (listed in Workers.unfinished() until it ends) and waits for its result
     * within waitMillis. Closing the workers then waits for that thread, bounded, whatever happened: a timeout, an
     * interrupt (InterruptedException) or the step's own failure is thrown with the close failures suppressed on it,
     * and a thread still alive afterwards keeps cleanup() from touching the rows. */
    private static <T> T onOwnThread(Workers workers, Callable<T> step, long waitMillis) throws Exception {
        try (workers) {
            return workers.submit(step).get(waitMillis, TimeUnit.MILLISECONDS);
        }
    }

    /** A task that ignores interrupts, counting each one, until it is released. */
    private static String stubborn(CountDownLatch release, CountDownLatch interrupted) {
        while (true) {
            try {
                release.await();
                return "finished after release";
            } catch (InterruptedException ignoredUntilReleased) {
                interrupted.countDown();
            }
        }
    }

    /** Wraps a connection so that the named methods (rollback, close, commit) fail without reaching H2: the simulated
     * failures of the release tests. Everything else is delegated unchanged. */
    private static Connection faulty(Connection real, Set<String> faults) {
        return (Connection) Proxy.newProxyInstance(AuditScanServiceTest.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (faults.contains(method.getName())) throw new SQLException("injected " + method.getName() + " failure");
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /** An independent connection whose open transaction holds the Audit row lock. Release never commits: a pending
     * transaction is rolled back, then the connection is closed physically (H2 2.2.224 rolls back a pending transaction
     * on close; its abort(Executor) is an empty method and is never relied upon). The release counts only once H2 no
     * longer lists the session in INFORMATION_SCHEMA.SESSIONS; otherwise ABORT_SESSION, from an independent connection,
     * is the fallback. Every failure on the way is kept and thrown; a failed release can be retried by closing again. */
    private final class AuditLock implements AutoCloseable {
        private final Connection tx;
        private final long sessionId;
        private boolean inTransaction;
        private boolean uncertain;
        private boolean released;

        AuditLock(long auditId) throws Exception {
            this(auditId, Set.of());
        }

        /** faults: connection methods that fail without reaching H2 (see faulty). */
        AuditLock(long auditId, Set<String> faults) throws Exception {
            Connection opened = dataSource.getConnection();
            long id;
            try {
                id = value(opened, "SELECT SESSION_ID()");
            } catch (Throwable failure) {
                try {
                    opened.close();
                } catch (Throwable closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw failure;
            }
            sessionId = id;
            tx = faults.isEmpty() ? opened : faulty(opened, faults);
            try {
                tx.setAutoCommit(false);
                inTransaction = true;
                assertEquals(auditId, value(tx, "SELECT id FROM audits WHERE id=" + auditId + " FOR UPDATE"));
            } catch (Throwable failure) {
                try {
                    close();
                } catch (Throwable releaseFailure) {
                    failure.addSuppressed(releaseFailure);
                }
                throw failure;
            }
        }

        long sessionId() { return sessionId; }

        /** Positive evidence from H2 itself: `expected` sessions are BLOCKED by this session while executing the
         * service's Audit lock request; polled until seen, within a bounded deadline. */
        void awaitWaiters(int expected) throws SQLException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (true) {
                long waiting = Long.parseLong(observe("SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS WHERE BLOCKER_ID=" + sessionId
                        + " AND SESSION_STATE='BLOCKED' AND LOWER(EXECUTING_STATEMENT) LIKE '%from audits%for update%'"));
                assertTrue(waiting <= expected, "More waiters than participants: " + waiting);
                if (waiting == expected) return;
                assertTrue(System.nanoTime() < deadline, "No session observed waiting on the Audit lock");
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
            }
        }

        int update(String sql) throws SQLException {
            try (Statement s = tx.createStatement()) { return s.executeUpdate(sql); }
        }

        /** Confirmed COMMIT, then the release; an uncertain COMMIT is never retried and the release closes physically. */
        void commit() throws Exception {
            try {
                tx.commit();
            } catch (Throwable failure) {
                uncertain = true;
                throw new SQLException("COMMIT outcome uncertain; the holder is closed, never retried", failure);
            }
            inTransaction = false;
            close();
        }

        private boolean sessionAlive() throws SQLException {
            return !"0".equals(observe("SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS WHERE SESSION_ID=" + sessionId));
        }

        @Override public void close() throws Exception {
            if (released) return;
            Throwable failure = null;
            if (inTransaction && !uncertain) {
                try {
                    tx.rollback();
                    inTransaction = false;
                } catch (Throwable rollbackFailure) {
                    uncertain = true;
                    failure = add(failure, rollbackFailure);
                }
            }
            try {
                tx.close(); // Physical close: H2 rolls back a pending transaction, never commits it.
            } catch (Throwable closeFailure) {
                failure = add(failure, closeFailure);
            }
            boolean alive = true;
            try {
                alive = sessionAlive();
            } catch (Throwable checkFailure) {
                failure = add(failure, checkFailure);
            }
            if (alive) {
                try {
                    observe("SELECT ABORT_SESSION(" + sessionId + ")"); // TRUE: closed now; FALSE: no such session.
                    alive = sessionAlive();
                } catch (Throwable abortFailure) {
                    failure = add(failure, abortFailure);
                }
            }
            if (alive) {
                failure = add(failure, new AssertionError("H2 session " + sessionId + " is still open after its release"));
            } else {
                released = true;
            }
            if (failure != null) throw rethrow(failure);
        }
    }

    /** H2 reports the cancel BLOCKED by the holder while executing the Audit lock request; nothing is written until
     * the holder commits, then the cancel completes. */
    @Test void cancelWaitsOnTheAuditRowLockUntilItIsReleased() throws Exception {
        AuditScanService.AuditCreation created = service.createAudit(orgA, siteA, owner);
        transition(created.scanId(), scan -> scan.start(later()));
        clock.advance(2_000_000);
        String result;
        try (Workers workers = new Workers(1); AuditLock lock = new AuditLock(created.auditId())) {
            Future<String> cancel = workers.submit(() -> outcome(() -> service.cancelScan(orgA, created.scanId(), owner)));
            lock.awaitWaiters(1);
            assertEquals("RUNNING|started=true|finished=false|attempt=1", scanRow(created.scanId()), "Nothing is written while it waits");
            lock.commit();
            result = cancel.get(10, TimeUnit.SECONDS);
        }
        assertEquals("OK", result);
        assertEquals("CANCELLED|started=true|finished=true|attempt=1", scanRow(created.scanId()));
    }

    /** Both retries are observed BLOCKED on the Audit lock; after the COMMIT exactly one creates attempt 2. */
    @Test void twoRetriesWaitingOnTheAuditLockCreateExactlyOneAttempt() throws Exception {
        AuditScanService.AuditCreation created = service.createAudit(orgA, siteA, owner);
        transition(created.scanId(), scan -> { scan.start(later()); scan.fail(later()); });
        clock.advance(2_000_000);
        List<String> results;
        try (Workers workers = new Workers(2); AuditLock lock = new AuditLock(created.auditId())) {
            Future<String> first = workers.submit(() -> outcome(() -> service.retryScan(orgA, created.auditId(), owner)));
            lock.awaitWaiters(1);
            Future<String> second = workers.submit(() -> outcome(() -> service.retryScan(orgA, created.auditId(), owner)));
            lock.awaitWaiters(2);
            assertEquals("1:FAILED", attempts(created.auditId()), "Nothing is written while both wait");
            lock.commit();
            results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, results.stream().filter("OK"::equals).count(), results.toString());
        assertEquals(1, results.stream().filter(r -> r.startsWith("REJECTED:") && r.contains("active Scan")).count(), results.toString());
        assertEquals("1:FAILED,2:PENDING", attempts(created.auditId()));
    }

    /** The retry waits on the Audit lock while the holder cancels the RUNNING Scan; reading after the lock it sees the
     * cancellation (RUNNING would have been rejected) and creates attempt 2. */
    @Test void retryWaitingOnTheAuditLockReadsTheAttemptsCommittedMeanwhile() throws Exception {
        AuditScanService.AuditCreation created = service.createAudit(orgA, siteA, owner);
        transition(created.scanId(), scan -> scan.start(later()));
        clock.advance(2_000_000);
        String result;
        try (Workers workers = new Workers(1); AuditLock lock = new AuditLock(created.auditId())) {
            Future<String> retry = workers.submit(() -> outcome(() -> service.retryScan(orgA, created.auditId(), owner)));
            lock.awaitWaiters(1);
            assertEquals(1, lock.update("UPDATE scans SET status='CANCELLED', finished_at=updated_at, row_version=row_version+1 "
                    + "WHERE id=" + created.scanId() + " AND status='RUNNING'"));
            lock.commit();
            result = retry.get(10, TimeUnit.SECONDS);
        }
        assertEquals("OK", result);
        assertEquals("1:CANCELLED,2:PENDING", attempts(created.auditId()));
    }

    /** The actor passed authorization and then waits on the Audit lock while its Membership is revoked and committed:
     * the authorization repeated after the lock rejects it, for retry and for cancel, and nothing is written. */
    @Test void retryAndCancelRevalidateAuthorizationAfterWaitingForTheAuditLock() throws Exception {
        String revoke = "UPDATE organization_memberships SET status='REVOKED' WHERE organization_id=" + orgA + " AND user_id=" + owner;
        AuditScanService.AuditCreation failed = service.createAudit(orgA, siteA, owner);
        transition(failed.scanId(), scan -> { scan.start(later()); scan.fail(later()); });
        clock.advance(2_000_000);
        String retried;
        try (Workers workers = new Workers(1); AuditLock lock = new AuditLock(failed.auditId())) {
            Future<String> retry = workers.submit(() -> outcome(() -> service.retryScan(orgA, failed.auditId(), owner)));
            lock.awaitWaiters(1);
            assertEquals(1, lock.update(revoke));
            lock.commit();
            retried = retry.get(10, TimeUnit.SECONDS);
        }
        assertEquals("NOT_FOUND", retried);
        assertEquals("1:FAILED", attempts(failed.auditId()));
        tx.executeWithoutResult(s -> em().createNativeQuery("UPDATE organization_memberships SET status='ACTIVE' WHERE organization_id="
                + orgA + " AND user_id=" + owner).executeUpdate());
        AuditScanService.AuditCreation running = service.createAudit(orgA, siteA, owner);
        transition(running.scanId(), scan -> scan.start(later()));
        clock.advance(2_000_000);
        String cancelled;
        try (Workers workers = new Workers(1); AuditLock lock = new AuditLock(running.auditId())) {
            Future<String> cancel = workers.submit(() -> outcome(() -> service.cancelScan(orgA, running.scanId(), owner)));
            lock.awaitWaiters(1);
            assertEquals(1, lock.update(revoke));
            lock.commit();
            cancelled = cancel.get(10, TimeUnit.SECONDS);
        }
        assertEquals("NOT_FOUND", cancelled);
        assertEquals("RUNNING|started=true|finished=false|attempt=1", scanRow(running.scanId()));
    }

    /** No H2 session other than the one asking may remain open (each check opens and closes its own). */
    private void assertNoOtherSessions() throws SQLException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        String others = observe("SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS WHERE SESSION_ID <> SESSION_ID()");
        while (!"0".equals(others) && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            others = observe("SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS WHERE SESSION_ID <> SESSION_ID()");
        }
        assertEquals("0", others, "H2 sessions left open by the test");
    }

    /** No trace of a holder remains: its H2 session is gone, no other session is open and its row lock is free (a new
     * holder locks the same Audit at once and is itself released cleanly). */
    private void assertHolderGone(long session, long auditId) throws Exception {
        assertEquals("0", observe("SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS WHERE SESSION_ID=" + session), "Holder session still open");
        assertNoOtherSessions();
        try (AuditLock again = new AuditLock(auditId)) {
            assertNotEquals(session, again.sessionId());
        }
        assertNoOtherSessions();
    }

    private static String cancelUpdate(long scanId) {
        return "UPDATE scans SET status='CANCELLED', finished_at=updated_at, row_version=row_version+1 WHERE id=" + scanId
                + " AND status='RUNNING'";
    }

    /** A test failing after its holder locked the Audit and changed a Scan keeps its own error untouched (a clean
     * release adds nothing): the uncommitted change is rolled back, the H2 session is gone and the row lock is free. */
    @Test void holderFailingAfterLockingKeepsTheErrorAndReleasesEverything() throws Exception {
        AuditScanService.AuditCreation created = service.createAudit(orgA, siteA, owner);
        transition(created.scanId(), scan -> scan.start(later()));
        AssertionError original = new AssertionError("simulated failure after locking");
        long[] session = new long[1];
        Throwable thrown = assertThrows(Throwable.class, () -> {
            try (AuditLock lock = new AuditLock(created.auditId())) {
                session[0] = lock.sessionId();
                assertEquals(1, lock.update(cancelUpdate(created.scanId())));
                throw original;
            }
        });
        assertSame(original, thrown);
        assertEquals(0, thrown.getSuppressed().length, "A clean release adds nothing");
        assertEquals("RUNNING|started=true|finished=false|attempt=1", scanRow(created.scanId()), "The uncommitted change was rolled back");
        assertHolderGone(session[0], created.auditId());
    }

    /** A statement failing inside the holder's transaction is the error that surfaces; the release rolls back and closes. */
    @Test void holderStatementFailingInsideItsTransactionReleasesTheSession() throws Exception {
        AuditScanService.AuditCreation created = service.createAudit(orgA, siteA, owner);
        long[] session = new long[1];
        Throwable thrown = assertThrows(Throwable.class, () -> {
            try (AuditLock lock = new AuditLock(created.auditId())) {
                session[0] = lock.sessionId();
                lock.update("UPDATE scans SET no_such_column=1 WHERE id=" + created.scanId());
            }
        });
        SQLException sql = assertInstanceOf(SQLException.class, thrown);
        assertEquals(42122, sql.getErrorCode(), "H2 column-not-found, not a release failure: " + sql);
        assertEquals(0, thrown.getSuppressed().length);
        assertEquals("PENDING|started=false|finished=false|attempt=1", scanRow(created.scanId()));
        assertHolderGone(session[0], created.auditId());
    }

    /** The holder's ROLLBACK fails: that failure is kept as suppressed on the test's own error, never claimed as a
     * rollback, and the physical close still rolls the change back without committing it. */
    @Test void failedRollbackIsKeptAndThePhysicalCloseStillRollsBack() throws Exception {
        AuditScanService.AuditCreation created = service.createAudit(orgA, siteA, owner);
        transition(created.scanId(), scan -> scan.start(later()));
        AssertionError original = new AssertionError("simulated failure with a failing rollback");
        long[] session = new long[1];
        Throwable thrown = assertThrows(Throwable.class, () -> {
            try (AuditLock lock = new AuditLock(created.auditId(), Set.of("rollback"))) {
                session[0] = lock.sessionId();
                assertEquals(1, lock.update(cancelUpdate(created.scanId())));
                throw original;
            }
        });
        assertSame(original, thrown);
        assertEquals(1, thrown.getSuppressed().length, Arrays.toString(thrown.getSuppressed()));
        assertEquals("injected rollback failure", thrown.getSuppressed()[0].getMessage());
        assertEquals("RUNNING|started=true|finished=false|attempt=1", scanRow(created.scanId()), "Closed without committing");
        assertHolderGone(session[0], created.auditId());
    }

    /** ROLLBACK and close() both fail: both failures are kept (close as suppressed of rollback, on the test's error),
     * and ABORT_SESSION from an independent connection frees the session and its lock without committing anything. */
    @Test void failedRollbackAndCloseFallBackToAbortSessionWithoutCommitting() throws Exception {
        AuditScanService.AuditCreation created = service.createAudit(orgA, siteA, owner);
        transition(created.scanId(), scan -> scan.start(later()));
        AssertionError original = new AssertionError("simulated failure with failing rollback and close");
        long[] session = new long[1];
        Throwable thrown = assertThrows(Throwable.class, () -> {
            try (AuditLock lock = new AuditLock(created.auditId(), Set.of("rollback", "close"))) {
                session[0] = lock.sessionId();
                assertEquals(1, lock.update(cancelUpdate(created.scanId())));
                throw original;
            }
        });
        assertSame(original, thrown);
        assertEquals(1, thrown.getSuppressed().length, Arrays.toString(thrown.getSuppressed()));
        Throwable release = thrown.getSuppressed()[0];
        assertEquals("injected rollback failure", release.getMessage());
        assertEquals(1, release.getSuppressed().length, Arrays.toString(release.getSuppressed()));
        assertEquals("injected close failure", release.getSuppressed()[0].getMessage());
        assertEquals("RUNNING|started=true|finished=false|attempt=1", scanRow(created.scanId()), "Aborted without committing");
        assertHolderGone(session[0], created.auditId());
    }

    /** With no other error in flight, a failed close() after a confirmed COMMIT is thrown, even though ABORT_SESSION
     * then freed the session: a release failure is never turned into success. */
    @Test void releaseFailureIsThrownEvenWhenAbortSessionFreedTheSession() throws Exception {
        AuditScanService.AuditCreation created = service.createAudit(orgA, siteA, owner);
        transition(created.scanId(), scan -> scan.start(later()));
        long[] session = new long[1];
        Throwable thrown = assertThrows(Throwable.class, () -> {
            try (AuditLock lock = new AuditLock(created.auditId(), Set.of("close"))) {
                session[0] = lock.sessionId();
                assertEquals(1, lock.update(cancelUpdate(created.scanId())));
                lock.commit();
            }
        });
        assertEquals("injected close failure", thrown.getMessage());
        assertEquals("CANCELLED|started=true|finished=true|attempt=1", scanRow(created.scanId()), "The confirmed COMMIT stands");
        assertHolderGone(session[0], created.auditId());
    }

    /** The test fails while a worker waits on the holder's lock: the holder is released first (rollback, close), the
     * waiting cancel then finishes within the workers' grace period, and nothing remains open or locked. */
    @Test void failingWhileAWorkerWaitsLeavesNoSessionLockOrRunningWorker() throws Exception {
        AuditScanService.AuditCreation created = service.createAudit(orgA, siteA, owner);
        transition(created.scanId(), scan -> scan.start(later()));
        clock.advance(2_000_000);
        AssertionError original = new AssertionError("simulated failure while a worker waits");
        AtomicReference<Future<String>> cancel = new AtomicReference<>();
        long[] session = new long[1];
        Throwable thrown = assertThrows(Throwable.class, () -> {
            try (Workers workers = new Workers(1); AuditLock lock = new AuditLock(created.auditId())) {
                session[0] = lock.sessionId();
                cancel.set(workers.submit(() -> outcome(() -> service.cancelScan(orgA, created.scanId(), owner))));
                lock.awaitWaiters(1);
                throw original;
            }
        });
        assertSame(original, thrown);
        assertEquals(0, thrown.getSuppressed().length, Arrays.toString(thrown.getSuppressed()));
        assertTrue(cancel.get().isDone(), "The worker finished before cleanup returned");
        assertEquals("OK", cancel.get().get(), "Once the holder rolled back, the waiting cancel completed normally");
        assertEquals("CANCELLED|started=true|finished=true|attempt=1", scanRow(created.scanId()));
        assertHolderGone(session[0], created.auditId());
    }

    /** C: a worker still running after the grace period is interrupted by shutdownNow() and ends; that is reported,
     * never hidden, and the closing thread, which nobody interrupted, returns with a clear interrupt status. */
    @Test void stuckWorkerIsInterruptedAndReportedNotHidden() throws Exception {
        CountDownLatch never = new CountDownLatch(1);
        Workers workers = new Workers(1, 200, 10_000);
        Future<Boolean> stuck = workers.submit(() -> never.await(1, TimeUnit.HOURS));
        Closer closer = new Closer(workers::close);
        Throwable thrown = closer.result();
        assertNotNull(thrown, "A worker that needed shutdownNow() fails close()");
        assertTrue(thrown.getMessage().contains("still running after 200 ms; interrupted"), thrown.toString());
        assertTrue(stuck.isDone());
        assertTrue(workers.isTerminated());
        assertTrue(Arrays.stream(thrown.getSuppressed()).anyMatch(InterruptedException.class::isInstance), Arrays.toString(thrown.getSuppressed()));
        assertFalse(closer.interruptedAfter(), "Nobody interrupted the closing thread");
        assertFalse(Workers.unfinished());
        assertNoOtherSessions();
    }

    /** A: the closing thread is interrupted inside the first awaitTermination. close() records the interrupt, stops the
     * workers with shutdownNow() (the worker sees the interrupt and ends), waits for them, fails, and returns with the
     * interrupt status of the closing thread restored. */
    @Test void closingThreadInterruptedDuringTheFirstWaitStillStopsTheWorkers() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch workerInterrupted = new CountDownLatch(1);
        CountDownLatch never = new CountDownLatch(1);
        Workers workers = new Workers(1, 60_000, 10_000);
        Future<Boolean> task = workers.submit(() -> {
            started.countDown();
            try {
                return never.await(1, TimeUnit.HOURS);
            } catch (InterruptedException stop) {
                workerInterrupted.countDown();
                throw stop;
            }
        });
        assertTrue(started.await(10, TimeUnit.SECONDS));
        Closer closer = new Closer(workers::close);
        closer.awaitTimedWait();
        closer.interrupt();
        Throwable thrown = closer.result();
        assertNotNull(thrown, "An interrupted close() never passes");
        assertTrue(thrown.getMessage().contains("Closing thread interrupted while waiting for the workers"), thrown.toString());
        assertInstanceOf(InterruptedException.class, thrown.getCause());
        assertEquals(0, workerInterrupted.getCount(), "shutdownNow() was requested and reached the worker");
        assertTrue(task.isDone());
        assertTrue(workers.isTerminated());
        assertTrue(Arrays.stream(thrown.getSuppressed()).anyMatch(InterruptedException.class::isInstance), "The task's own failure is kept: "
                + Arrays.toString(thrown.getSuppressed()));
        assertTrue(closer.interruptedAfter(), "The interrupt status of the closing thread is restored");
        assertFalse(Workers.unfinished());
        assertNoOtherSessions();
    }

    /** B: the worker ignores interrupts until released, so after the grace period close() calls shutdownNow() and waits
     * again; the closing thread is interrupted inside that second wait. close() records it, keeps waiting (bounded) until
     * the worker ends, fails, and returns with the interrupt status restored. */
    @Test void closingThreadInterruptedAfterShutdownNowKeepsWaitingForTheWorkers() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch workerInterrupted = new CountDownLatch(1);
        Workers workers = new Workers(1, 100, 60_000);
        Future<String> task = workers.submit(() -> stubborn(release, workerInterrupted));
        Closer closer = new Closer(workers::close);
        try {
            assertTrue(workerInterrupted.await(10, TimeUnit.SECONDS), "shutdownNow() reached the worker");
            closer.awaitTimedWait(); // The first wait ended before shutdownNow(): this is the second one.
            closer.interrupt();
        } finally {
            release.countDown();
        }
        Throwable thrown = closer.result();
        assertNotNull(thrown);
        assertTrue(thrown.getMessage().contains("still running after 100 ms; interrupted"), thrown.toString());
        assertTrue(Arrays.stream(thrown.getSuppressed()).anyMatch(s -> String.valueOf(s.getMessage()).contains("Closing thread interrupted")),
                "The interrupt of the closing thread is reported: " + Arrays.toString(thrown.getSuppressed()));
        assertEquals("finished after release", task.get(), "close() waited for the worker instead of abandoning it");
        assertTrue(workers.isTerminated());
        assertTrue(closer.interruptedAfter(), "The interrupt status of the closing thread is restored");
        assertFalse(Workers.unfinished());
        assertNoOtherSessions();
    }

    /** D: a worker that ignores interrupts outlives the bounded wait after shutdownNow(): close() fails declaring the
     * cleanup not validated, the workers stay unfinished and cleanup() then refuses to touch the rows; only once the
     * worker is released does it end, and cleanup runs again. */
    @Test void workerOutlivingTheStopDeadlineFailsAndLeavesTheRowsInPlace() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch workerInterrupted = new CountDownLatch(1);
        Workers workers = new Workers(1, 100, 200);
        Future<String> task = workers.submit(() -> stubborn(release, workerInterrupted));
        try {
            Closer closer = new Closer(workers::close);
            Throwable thrown = closer.result();
            assertNotNull(thrown);
            assertTrue(thrown.getMessage().contains("still running after 100 ms; interrupted"), thrown.toString());
            assertTrue(Arrays.stream(thrown.getSuppressed()).anyMatch(s -> String.valueOf(s.getMessage()).contains("cleanup is not validated")),
                    Arrays.toString(thrown.getSuppressed()));
            assertEquals(0, workerInterrupted.getCount(), "shutdownNow() was requested");
            assertFalse(task.isDone());
            assertFalse(workers.isTerminated());
            assertFalse(closer.interruptedAfter());
            assertTrue(Workers.unfinished());
            AssertionError refused = assertThrows(AssertionError.class, this::cleanup);
            assertTrue(refused.getMessage().contains("rows left in place"), refused.toString());
            assertEquals(2, count("organizations"), "Nothing was deleted while the worker could still use the rows");
        } finally {
            release.countDown();
        }
        assertTrue(workers.awaitStopped(10_000), "Released, the worker ends");
        assertEquals("finished after release", task.get());
        assertFalse(Workers.unfinished());
        assertNoOtherSessions();
    }

    /** E: a worker fails while close() waits for it: that very exception is what close() throws. */
    @Test void workerFailingBeforeItEndsIsThrownByClose() throws Exception {
        CountDownLatch proceed = new CountDownLatch(1);
        IllegalStateException failure = new IllegalStateException("worker failure before finishing");
        Workers workers = new Workers(1, 10_000, 10_000);
        Future<Object> task = workers.submit(() -> {
            proceed.await();
            throw failure;
        });
        Closer closer = new Closer(workers::close);
        try {
            closer.awaitTimedWait();
        } finally {
            proceed.countDown();
        }
        assertSame(failure, closer.result());
        assertEquals(0, failure.getSuppressed().length, Arrays.toString(failure.getSuppressed()));
        assertTrue(task.isDone());
        assertTrue(workers.isTerminated());
        assertFalse(closer.interruptedAfter());
        assertFalse(Workers.unfinished());
        assertNoOtherSessions();
    }

    /** F: the test's own error is in flight when its workers close, and the closing thread is interrupted meanwhile: the
     * original error stays primary with the workers' failure (interrupt, interrupted task) suppressed on it, the worker
     * ends and the interrupt status is restored. */
    @Test void earlierFailureStaysPrimaryWhenClosingTheWorkersIsInterrupted() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch never = new CountDownLatch(1);
        AssertionError original = new AssertionError("simulated test failure while a worker runs");
        Workers workers = new Workers(1, 60_000, 10_000);
        Future<Boolean> task = workers.submit(() -> {
            started.countDown();
            return never.await(1, TimeUnit.HOURS);
        });
        assertTrue(started.await(10, TimeUnit.SECONDS));
        Closer closer = new Closer(() -> {
            try (workers) {
                throw original;
            }
        });
        closer.awaitTimedWait();
        closer.interrupt();
        assertSame(original, closer.result());
        assertEquals(1, original.getSuppressed().length, Arrays.toString(original.getSuppressed()));
        Throwable close = original.getSuppressed()[0];
        assertTrue(close.getMessage().contains("Closing thread interrupted while waiting for the workers"), close.toString());
        assertTrue(Arrays.stream(close.getSuppressed()).anyMatch(InterruptedException.class::isInstance), Arrays.toString(close.getSuppressed()));
        assertTrue(task.isDone());
        assertTrue(workers.isTerminated());
        assertTrue(closer.interruptedAfter(), "The interrupt status of the closing thread is restored");
        assertFalse(Workers.unfinished());
        assertNoOtherSessions();
    }

    /** The OSIV test's step on its own thread (onOwnThread): A, it ends normally and nothing stays registered; B, the
     * waiting thread is interrupted, the InterruptedException stays primary and the step is still stopped and waited
     * for; C, the wait times out while the step keeps running: it stays registered and cleanup() refuses to touch the
     * rows; D, once released it ends and leaves the registry; E, the original error (timeout, interrupt) keeps the close
     * failures as suppressed. */
    @Test void osivStepOnItsOwnThreadIsWaitedForAndNeverOutlivesCleanupUnnoticed() throws Exception {
        Workers normal = new Workers(1, 10_000, 10_000);
        assertEquals("done", onOwnThread(normal, () -> "done", 10_000));
        assertTrue(normal.isTerminated(), "A: the thread ended before onOwnThread returned");
        assertFalse(Workers.unfinished());

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch never = new CountDownLatch(1);
        Workers interruptible = new Workers(1, 100, 10_000);
        Closer waiting = new Closer(() -> onOwnThread(interruptible, () -> {
            started.countDown();
            return never.await(1, TimeUnit.HOURS);
        }, 60_000));
        assertTrue(started.await(10, TimeUnit.SECONDS));
        waiting.awaitTimedWait();
        waiting.interrupt();
        Throwable interrupted = waiting.result();
        assertInstanceOf(InterruptedException.class, interrupted, "B: the interrupt is the primary error, never swallowed");
        assertTrue(Arrays.stream(interrupted.getSuppressed()).anyMatch(s -> String.valueOf(s.getMessage()).contains("still running after 100 ms")),
                "E: the close failure is kept as suppressed: " + Arrays.toString(interrupted.getSuppressed()));
        assertTrue(interruptible.isTerminated(), "B: the step was stopped and waited for");
        assertFalse(Workers.unfinished());

        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch stepInterrupted = new CountDownLatch(1);
        Workers stuck = new Workers(1, 100, 200);
        try {
            TimeoutException timedOut = assertThrows(TimeoutException.class, () -> onOwnThread(stuck, () -> stubborn(release, stepInterrupted), 100));
            assertTrue(Arrays.stream(timedOut.getSuppressed()).anyMatch(s -> String.valueOf(s.getMessage()).contains("still running after 100 ms")),
                    "E: " + Arrays.toString(timedOut.getSuppressed()));
            assertTrue(Arrays.stream(timedOut.getSuppressed()).anyMatch(s -> Arrays.stream(s.getSuppressed())
                    .anyMatch(n -> String.valueOf(n.getMessage()).contains("cleanup is not validated"))), "C: " + Arrays.toString(timedOut.getSuppressed()));
            assertEquals(0, stepInterrupted.getCount(), "C: shutdownNow() was requested");
            assertFalse(stuck.isTerminated());
            assertTrue(Workers.unfinished(), "C: the step still running stays registered");
            AssertionError refused = assertThrows(AssertionError.class, this::cleanup);
            assertTrue(refused.getMessage().contains("rows left in place"), refused.toString());
            assertEquals(2, count("organizations"), "C: nothing was deleted while the step could still use the rows");
        } finally {
            release.countDown();
        }
        assertTrue(stuck.awaitStopped(10_000), "D: released, the step ends");
        assertFalse(Workers.unfinished(), "D: and leaves the registry");
        assertNoOtherSessions();
    }

    @Test void springWiresTheServiceWithTheApplicationTransactionManager() throws Exception {
        AuditScanService.AuditCreation created = springService.createAudit(orgA, siteA, owner);
        assertEquals(1, created.attemptNumber());
        assertEquals(1, count("audits"));
        assertEquals(10, AuditScanService.TIMEOUT_SECONDS);
        // The transaction boundary contract: one READ_COMMITTED, finite, writable transaction on the application manager.
        var field = AuditScanService.class.getDeclaredField("transactions");
        field.setAccessible(true);
        TransactionTemplate template = (TransactionTemplate) field.get(springService);
        assertSame(transactionManager, template.getTransactionManager());
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRED, template.getPropagationBehavior());
        assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED, template.getIsolationLevel());
        assertEquals(AuditScanService.TIMEOUT_SECONDS, template.getTimeout());
        assertFalse(template.isReadOnly());
    }
}
