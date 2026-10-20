package com.smartaudits.v2.model;

import com.smartaudits.model.*;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.OptimisticLockException;
import org.flywaydb.core.Flyway;
import org.hibernate.Hibernate;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.StaleStateException;
import org.hibernate.Transaction;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.JDBCException;
import org.junit.jupiter.api.Test;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit harness -Dtest entry only: deliberately outside Surefire's *Test patterns.
 * From V8 an Audit may be committed only together with its first Scan: every committed Audit
 * below comes from AuditScanService, and the probe asserts that no committed Audit lacks a Scan.
 * Each mode owns its fixtures (users with ids proven unused, organizations it created and every
 * row hanging from them) and removes them in one transaction on success and on any failure; the
 * cleanup is verified and its failure is never hidden. A transaction is ended only by a confirmed
 * COMMIT or ROLLBACK and a release never commits. Connections are owned by their JDBC handles: every
 * connection the probe opens, itself, through Hibernate or through Flyway, is registered with its
 * server id before use, released only through its own handle by its owner, and counts as released
 * only once the server stops listing its session; release errors that Spring or Hibernate would only
 * log are recorded and fail the probe, and no session of the probe may remain when a mode ends.
 * Server ids serve observation only: the probe never acts on a session by its id, never touches a
 * session it did not open, and leaves whatever it cannot release to the harness, which removes the
 * isolated server, failing instead of passing. PROCESSLIST only coordinates (a locking statement reached
 * the server and is still executing); it never identifies the lock holder. That the service's
 * PESSIMISTIC_WRITE waits on a row lock held by another transaction is proven by the directed
 * ER_LOCK_WAIT_TIMEOUT (1205) scenarios and by durable outcomes. The session sql_mode is read and
 * asserted, never changed. Synthetic SQL is used only for rejection checks, terminal fixtures, lock
 * holders and rolled-back acceptance checks.
 */
class ScanMariaDbProbe {
    private static final String[] LEGACY = {"usuarios", "roles", "usuarios_roles", "auditorias", "incidencias",
            "resultados", "historial_auditorias", "historial_acciones_admin"};
    private static final String[] PARENTS = {"organizations", "organization_memberships", "sites", "audits", "scans"};
    private static final String VERSIONS = "1,2,3,4,5,6,7,8,9000";
    private static final Instant CREATED = Instant.parse("2024-02-29T23:58:57.987654321Z");
    private static final Instant BASE = Instant.parse("2024-03-02T00:00:00Z");
    private static final String T0 = "'2024-03-31 02:30:00.123456'";
    private static final String T1 = "'2024-03-31 02:30:01.000000'";
    private static final String TBEFORE = "'2024-03-31 02:29:59.000000'";
    private static final String ORPHANS = "SELECT COUNT(*) FROM audits a WHERE NOT EXISTS "
            + "(SELECT 1 FROM scans s WHERE s.organization_id=a.organization_id AND s.audit_id=a.id)";
    private static final String TRIGGER = "f3_scan_failure_probe";
    private static final DateTimeFormatter SQL_TIME = DateTimeFormatter.ofPattern("''yyyy-MM-dd HH:mm:ss.SSSSSS''").withZone(ZoneOffset.UTC);
    private static final long WAIT_SECONDS = 10;
    /** Bounded wait for workers to stop once interrupted (shutdownNow). */
    private static final long STOP_SECONDS = 30;
    /** innodb_lock_wait_timeout of the directed lock-wait scenarios only: well below the service's 10 s timeout. */
    private static final int LOCK_WAIT_SECONDS = 2;

    @Test void isolatedScanContract() throws Exception {
        String url = required("F3_DB_URL");
        String database = required("F3_DB_NAME");
        assertTrue(database.matches("f3_harness_[a-f0-9]{32}"));
        assertTrue(url.matches("jdbc:mariadb://127\\.0\\.0\\.1:[0-9]+/" + database));
        assertFalse(url.contains(":3306/"));
        assertTrue(required("F3_SCAN_HARNESS_RUN_ID").matches("[a-f0-9]{32}"));
        assertEquals(VERSIONS, required("F3_EXPECTED_VERSIONS"));
        String user = required("F3_DB_USER");
        assertEquals("f3_harness_user", user);
        String zone = required("F3_TIMEZONE");
        assertTrue(Set.of("Asia/Kathmandu", "Europe/Madrid").contains(zone));
        assertEquals(zone, ZoneId.systemDefault().getId());
        Db db = new Db(url, user, required("F3_DB_PASSWORD"), MARIADB);
        try (Owned observer = Owned.open(db, "observer")) {
            Connection c = observer.connection();
            assertTrue(c.getMetaData().getDatabaseProductVersion().startsWith("11.2."));
            assertEquals(database, c.getCatalog());
            strict(c);
            String globalMode = scalar(c, "SELECT @@GLOBAL.sql_mode");
            Throwable primary = null;
            try {
                switch (required("F3_SCAN_MODE")) {
                    case "contracts" -> contracts(c, db);
                    case "tenant" -> tenant(c, db);
                    case "service" -> service(c, db);
                    case "concurrency" -> concurrency(c, db);
                    case "upgrade" -> upgrade(c, db);
                    case "seed" -> seed(c, db);
                    default -> fail("Unknown Scan probe mode");
                }
                assertEquals("0", scalar(c, ORPHANS), "A committed Audit lacks its Scan");
                assertEquals(globalMode, scalar(c, "SELECT @@GLOBAL.sql_mode"), "Global sql_mode changed");
            } catch (Throwable failure) {
                primary = failure;
                throw failure;
            } finally {
                try {
                    noProbeSessionLeft(observer, db);
                } catch (Throwable leak) {
                    report(primary, leak);
                }
            }
        }
    }

    // ---------------------------------------------------------------- infrastructure

    /** Read-only server observation. MariaDB: CONNECTION_ID() and PROCESSLIST; without the PROCESS privilege the probe
     * user sees the sessions of that user, which are not necessarily the probe's. Ids serve only to confirm that a
     * release happened, to check isolation preconditions and to report: no session is ever acted upon by its id, and
     * nothing here tells which lock a session waits on. */
    private interface Server {
        long id(Connection connection) throws SQLException;

        /** The subset of these ids the server still lists. */
        Set<Long> alive(Connection observer, Collection<Long> ids) throws SQLException;

        /** Every other session of the observer's user the server lists, whoever opened it. */
        Set<Long> visible(Connection observer) throws SQLException;
    }

    private static final Server MARIADB = new Server() {
        @Override public long id(Connection connection) throws SQLException {
            return Long.parseLong(scalar(connection, "SELECT CONNECTION_ID()"));
        }

        @Override public Set<Long> alive(Connection observer, Collection<Long> ids) throws SQLException {
            return ids.isEmpty() ? new TreeSet<>()
                    : longs(observer, "SELECT ID FROM information_schema.PROCESSLIST WHERE ID IN (" + join(ids) + ")");
        }

        @Override public Set<Long> visible(Connection observer) throws SQLException {
            return longs(observer, "SELECT ID FROM information_schema.PROCESSLIST WHERE ID <> CONNECTION_ID() "
                    + "AND USER = SUBSTRING_INDEX(CURRENT_USER(), '@', 1)");
        }
    };

    /** The harness-owned isolated schema, its read-only server observation and the ownership registry of this run.
     * Every connection of the probe is opened by connect() and released by its owner through its own handle. */
    private record Db(String url, String user, String password, Server server, Sessions sessions) {
        Db(String url, String user, String password, Server server) {
            this(url, user, password, server, new Sessions());
        }

        /** Opens a connection of the probe and registers its JDBC handle, with its server id, before anyone uses it. If
         * the id cannot be read, the handle is aborted on this thread and the failure is thrown. */
        Connection connect(String purpose) throws SQLException {
            Connection connection = DriverManager.getConnection(url, user, password);
            long id;
            try {
                id = server.id(connection);
            } catch (Throwable failure) {
                Throwable abortFailure = releaseHandle(this, connection, false);
                if (abortFailure != null) failure.addSuppressed(abortFailure);
                throw failure;
            }
            sessions.opened(connection, id, purpose);
            return connection;
        }

        @Override public String toString() { return "Db[" + url + "]"; }
    }

    /** Ownership registry of one probe run: every connection the probe opens (itself, through Hibernate or through
     * Flyway), keyed by its JDBC handle, with its server id and the latest step of its release. Ownership is the handle:
     * the probe closes or aborts only these handles, each through its owner; the ids serve observation and diagnostics
     * only. Thread-safe. */
    private static final class Sessions {
        private static final class Entry {
            private final long id;
            private final String purpose;
            private String state = "open";

            Entry(long id, String purpose) {
                this.id = id;
                this.purpose = purpose;
            }
        }

        private final Map<Connection, Entry> byHandle = new IdentityHashMap<>();
        private final Map<Long, Entry> byId = new LinkedHashMap<>();

        synchronized void opened(Connection handle, long id, String purpose) {
            Entry entry = new Entry(id, purpose);
            byHandle.put(handle, entry);
            byId.put(id, entry);
        }

        synchronized long id(Connection handle) {
            return Objects.requireNonNull(byHandle.get(handle), "Not a connection of the probe").id;
        }

        /** Records the latest release step of a handle; a handle the probe did not open is ignored. */
        synchronized void state(Connection handle, String state) {
            Entry entry = byHandle.get(handle);
            if (entry != null) entry.state = state;
        }

        synchronized void confirmedGone(Collection<Long> ids) {
            for (long id : ids) {
                Entry entry = byId.get(id);
                if (entry != null) entry.state += ", gone (server confirmed)";
            }
        }

        synchronized Set<Long> ids() { return new TreeSet<>(byId.keySet()); }

        synchronized String describe(Collection<Long> ids) {
            StringJoiner text = new StringJoiner(", ", "[", "]");
            for (long id : ids) {
                Entry entry = byId.get(id);
                text.add(id + (entry == null ? " (not opened by the probe)" : " (" + entry.purpose + ": " + entry.state + ")"));
            }
            return text.toString();
        }
    }

    private static String required(String name) {
        return Objects.requireNonNull(System.getenv(name), "Missing " + name);
    }

    private static void strict(Connection c) throws SQLException {
        String mode = scalar(c, "SELECT @@SESSION.sql_mode");
        assertTrue(Arrays.asList(mode.split(",")).contains("STRICT_TRANS_TABLES")
                || Arrays.asList(mode.split(",")).contains("STRICT_ALL_TABLES"), "Strict SQL mode required: " + mode);
    }

    // ---------------------------------------------------------------- failures are collected, never lost

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

    /** A cleanup failure never hides the primary error and never disappears: attached to it, or thrown on its own. */
    private static void report(Throwable primary, Throwable cleanupFailure) throws Exception {
        if (primary != null) {
            primary.addSuppressed(cleanupFailure);
        } else {
            throw rethrow(cleanupFailure);
        }
    }

    // ---------------------------------------------------------------- releases with server evidence

    /** Releases a JDBC handle the caller owns, on the caller's thread: close() when allowed (its transaction ended with a
     * confirmed COMMIT or ROLLBACK, or autocommit is on), otherwise, or if close() failed, abort(). No other thread is
     * using the handle then, so Connector/J 3.2.0's abort() sends COM_QUIT and closes the socket: its KILL branch runs
     * only when another thread holds the connection in the middle of a statement, which the probe never lets happen.
     * The handle is never used again. Returns the failures, close() first with abort() suppressed on it, or null. */
    private static Throwable releaseHandle(Db db, Connection handle, boolean closeAllowed) {
        Throwable failure = null;
        if (closeAllowed) {
            try {
                handle.close();
                db.sessions().state(handle, "closed");
                return null;
            } catch (Throwable closeFailure) {
                failure = closeFailure;
            }
        }
        try {
            handle.abort(Runnable::run);
            db.sessions().state(handle, failure == null ? "aborted" : "close() failed, aborted");
        } catch (Throwable abortFailure) {
            failure = add(failure, abortFailure);
            db.sessions().state(handle, "release failed");
        }
        return failure;
    }

    private static Set<Long> pollAlive(Db db, Connection observer, Collection<Long> ids) throws SQLException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (true) {
            Set<Long> alive = db.server().alive(observer, ids);
            if (alive.isEmpty() || System.nanoTime() > deadline) return alive;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
    }

    /** Read-only release evidence: waits (bounded) until the server no longer lists any of these sessions. It never acts
     * on a session: one still listed, or a check that cannot be completed, fails the release and is left to the harness,
     * which removes the isolated server. The first failure stays primary; the failures of the check's own connection
     * are suppressed on it. */
    private static void awaitGone(Db db, Collection<Long> ids) throws Exception {
        if (ids.isEmpty()) return;
        Connection observer;
        try {
            observer = db.connect("release check");
        } catch (Throwable connectFailure) {
            throw new AssertionError("Release of sessions " + ids + " could not be verified: no connection for the check", connectFailure);
        }
        Throwable failure = null;
        try {
            Set<Long> alive = pollAlive(db, observer, ids);
            if (alive.isEmpty()) {
                db.sessions().confirmedGone(ids);
            } else {
                failure = new AssertionError("Server sessions still open after their release, left untouched: " + db.sessions().describe(alive));
            }
        } catch (Throwable checkFailure) {
            failure = new AssertionError("Release of sessions " + ids + " could not be verified", checkFailure);
        }
        failure = add(failure, releaseHandle(db, observer, true));
        if (failure != null) throw rethrow(failure);
    }

    /** When a mode ends, no session the probe opened, other than the observer asking, may still be listed: a survivor
     * fails the mode and is left untouched for the harness. Sessions of the same user that the probe did not open are
     * never touched; their presence is only reported, because it does not invalidate this mode's durable results (the
     * directed lock-wait scenarios fail on it explicitly while they run). */
    private static void noProbeSessionLeft(Owned observer, Db db) throws Exception {
        Connection c = observer.connection();
        Set<Long> mine = db.sessions().ids();
        mine.remove(observer.id());
        Set<Long> alive = pollAlive(db, c, mine);
        Set<Long> unknown = db.server().visible(c);
        unknown.removeAll(db.sessions().ids());
        if (!unknown.isEmpty()) {
            System.out.println("SCAN PROBE NOTE: sessions of the probe user not opened by the probe, left untouched: " + unknown);
        }
        if (!alive.isEmpty()) {
            throw new AssertionError("Sessions opened by the probe still open when the mode ended, left untouched for the harness: "
                    + db.sessions().describe(alive));
        }
    }

    /** A connection the probe opens and owns by its JDBC handle. A transaction it begins ends only by a confirmed COMMIT
     * or ROLLBACK and its release never commits: after a confirmed end the handle is closed; when the end is uncertain,
     * or close() fails, it is aborted on the owning thread (see releaseHandle). The handle is released once and never
     * used again, even when its release failed. The release counts only once the server no longer lists the session
     * (a read-only check). Every failure on the way is kept and thrown, the first one primary; calling close() again
     * only repeats the server check. */
    private static final class Owned implements AutoCloseable {
        private final Db db;
        private final Connection connection;
        private final long id;
        private boolean inTransaction;
        private boolean uncertain;
        private boolean handleReleased;
        private boolean released;

        private Owned(Db db, Connection connection, long id) {
            this.db = db;
            this.connection = connection;
            this.id = id;
        }

        static Owned open(Db db, String purpose) throws SQLException {
            Connection connection = db.connect(purpose);
            return new Owned(db, connection, db.sessions().id(connection));
        }

        private void usable() {
            if (handleReleased) throw new IllegalStateException("Connection " + id + " was already released and is never reused");
        }

        Connection connection() {
            usable();
            return connection;
        }

        long id() { return id; }

        void begin() throws SQLException {
            usable();
            connection.setAutoCommit(false);
            inTransaction = true;
        }

        void commit() throws SQLException {
            usable();
            try {
                connection.commit();
            } catch (Throwable failure) {
                uncertain = true;
                throw new SQLException("COMMIT outcome uncertain; the connection is aborted, never reused", failure);
            }
            inTransaction = false;
        }

        void rollback() throws SQLException {
            usable();
            try {
                connection.rollback();
            } catch (Throwable failure) {
                uncertain = true;
                throw failure;
            }
            inTransaction = false;
        }

        @Override public void close() throws Exception {
            if (released) return;
            Throwable failure = null;
            if (!handleReleased) {
                if (inTransaction && !uncertain) {
                    try {
                        rollback();
                    } catch (Throwable rollbackFailure) {
                        failure = add(failure, rollbackFailure);
                    }
                }
                failure = add(failure, releaseHandle(db, connection, !inTransaction));
                handleReleased = true;
            }
            try {
                awaitGone(db, List.of(id));
                released = true;
            } catch (Throwable unreleased) {
                failure = add(failure, unreleased);
            }
            if (failure != null) throw rethrow(failure);
        }
    }

    /** One transaction on a dedicated connection with a verified release: COMMIT (commit=true and the work completed)
     * or ROLLBACK, both confirmed; otherwise the release rolls it back or aborts it, never commits. */
    private static void transaction(Db db, boolean commit, SqlWork work) throws Exception {
        try (Owned tx = Owned.open(db, "transaction")) {
            tx.begin();
            work.run(tx.connection());
            if (commit) {
                tx.commit();
            } else {
                tx.rollback();
            }
        }
    }

    /** Every connection the test provider hands out, from acquisition to release. Thread-safe. Release errors are
     * recorded here with their causes and suppressed exceptions, because Spring and Hibernate may only log them;
     * verify() fails the probe if any was recorded or any connection is still handed out. Releasing again a connection
     * the provider already finished with, or one the probe discarded on purpose, is a valid no-op, never an error. */
    private static final class Ledger {
        private final Db db;
        private final Object lock = new Object();
        private final Map<Connection, Long> outstanding = new IdentityHashMap<>();
        private final Map<Connection, Long> finished = new IdentityHashMap<>();
        private final Set<Connection> discarded = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<Long> ids = new LinkedHashSet<>();
        private final List<Throwable> errors = new ArrayList<>();

        Ledger(Db db) { this.db = db; }

        Db db() { return db; }

        void handedOut(Connection connection, long id) {
            synchronized (lock) {
                outstanding.put(connection, id);
                ids.add(id);
            }
        }

        /** Takes the connection out of the outstanding set; null when it is not outstanding. */
        Long claim(Connection connection) {
            synchronized (lock) { return outstanding.remove(connection); }
        }

        boolean wasFinished(Connection connection) {
            synchronized (lock) { return finished.containsKey(connection); }
        }

        void finished(Connection connection, long id) {
            synchronized (lock) { finished.put(connection, id); }
        }

        void discarded(Connection connection) {
            synchronized (lock) { discarded.add(connection); }
        }

        boolean wasDiscarded(Connection connection) {
            synchronized (lock) { return discarded.contains(connection); }
        }

        void record(Throwable error) {
            synchronized (lock) { errors.add(error); }
        }

        void record(String context, Throwable cause) {
            record(new SQLException(context, cause));
        }

        Set<Long> ids() {
            synchronized (lock) { return new LinkedHashSet<>(ids); }
        }

        /** Connections never released are reported together with every recorded error. One is aborted (never committed)
         * only when no worker thread of this run can still be using it, so that nobody else holds the handle; while a
         * worker is still running it is left open, untouched, for the harness. */
        void verify() {
            Map<Connection, Long> pending;
            List<Throwable> found;
            synchronized (lock) {
                pending = new IdentityHashMap<>(outstanding);
                outstanding.clear();
                found = new ArrayList<>(errors);
            }
            boolean workersRunning = !pending.isEmpty() && Workers.unfinished();
            for (var connection : pending.entrySet()) {
                SQLException leak = new SQLException("Connection " + connection.getValue() + " was handed out and never released");
                if (workersRunning) {
                    leak.addSuppressed(new AssertionError("Left open, not aborted: a worker thread of this run is still running and may use it"));
                    db.sessions().state(connection.getKey(), "never released, left open (a worker is still running)");
                } else {
                    Throwable abortFailure = releaseHandle(db, connection.getKey(), false);
                    if (abortFailure != null) leak.addSuppressed(abortFailure);
                }
                found.add(leak);
            }
            if (found.isEmpty()) return;
            AssertionError failure = new AssertionError("Test connection release failed: " + found.size() + " problem(s); never released: "
                    + pending.values());
            found.forEach(failure::addSuppressed);
            throw failure;
        }
    }

    /** Test-only connection provider: one physical connection per acquisition, never pooled, each registered in the
     * ledger with its server id (sessionSetup, if any, runs first and only on this provider's connections). Release never
     * commits: an open connection with autocommit on (Hibernate restores it after every COMMIT or ROLLBACK) is closed;
     * one returned inside a transaction, or whose state cannot be read, is aborted and reported. If close() fails,
     * abort() is the documented fallback and both errors are kept. Releases run on the thread that returns the
     * connection, the only one using it (see releaseHandle). Hibernate's built-in pool would call setAutoCommit(true)
     * instead, which commits an abandoned transaction. */
    private static final class SafeConnections implements ConnectionProvider {
        private final Db db;
        private final Ledger ledger;
        private final String sessionSetup;

        SafeConnections(Db db, Ledger ledger, String sessionSetup) {
            this.db = db;
            this.ledger = ledger;
            this.sessionSetup = sessionSetup;
        }

        Ledger ledger() { return ledger; }

        @Override public Connection getConnection() throws SQLException {
            Connection connection;
            try {
                connection = db.connect("hibernate");
            } catch (Throwable failure) {
                ledger.record("Connection acquisition failed", failure);
                throw failure;
            }
            try {
                if (sessionSetup != null) execute(connection, sessionSetup);
                ledger.handedOut(connection, db.sessions().id(connection));
                return connection;
            } catch (Throwable failure) {
                Throwable abortFailure = releaseHandle(db, connection, false);
                if (abortFailure != null) failure.addSuppressed(abortFailure);
                ledger.record("Connection setup failed; aborted", failure);
                throw failure;
            }
        }

        @Override public void closeConnection(Connection connection) {
            Long id = ledger.claim(connection);
            if (id == null) {
                if (!ledger.wasFinished(connection)) {
                    ledger.record(new SQLException("Release of a connection this provider did not hand out"));
                }
                return;
            }
            String name = "connection " + id;
            Boolean closed = null;
            try {
                closed = connection.isClosed();
            } catch (Throwable inspectFailure) {
                ledger.record("isClosed() failed on " + name, inspectFailure);
            }
            if (Boolean.TRUE.equals(closed)) {
                if (!ledger.wasDiscarded(connection)) {
                    ledger.record(new SQLException(name + " was already closed when released, not by this probe"));
                }
                ledger.finished(connection, id);
                return;
            }
            Boolean autoCommit = null;
            if (closed != null) {
                try {
                    autoCommit = connection.getAutoCommit();
                } catch (Throwable inspectFailure) {
                    ledger.record("getAutoCommit() failed on " + name, inspectFailure);
                }
            }
            if (Boolean.TRUE.equals(autoCommit)) {
                Throwable releaseFailure = releaseHandle(db, connection, true);
                if (releaseFailure != null) ledger.record("close() failed on " + name + "; abort() attempted instead", releaseFailure);
                ledger.finished(connection, id);
                return;
            }
            SQLException unsafe = new SQLException(name + (autoCommit == null ? " was released with an unreadable state"
                    : " was released inside an open transaction") + "; aborted, never committed");
            Throwable abortFailure = releaseHandle(db, connection, false);
            if (abortFailure != null) unsafe.addSuppressed(abortFailure);
            ledger.record(unsafe);
            ledger.finished(connection, id);
        }

        @Override public boolean supportsAggressiveRelease() { return false; }
        @Override public boolean isUnwrappableAs(Class<?> type) { return type.isInstance(this); }
        @Override public <T> T unwrap(Class<T> type) { return type.cast(this); }
    }

    private static SessionFactory factory(SafeConnections connections) {
        Configuration cfg = new Configuration();
        for (Class<?> entity : List.of(Scan.class, Audit.class, Site.class, OrganizationMembership.class, Organization.class,
                Usuario.class, Rol.class, Auditoria.class, Incidencia.class, HistorialAuditoria.class, HistorialAccionAdmin.class)) {
            cfg.addAnnotatedClass(entity);
        }
        cfg.getProperties().put(AvailableSettings.CONNECTION_PROVIDER, connections);
        // Same handling mode Spring Boot's HibernateJpaVendorAdapter sets: without it HibernateJpaDialect
        // refuses READ_COMMITTED (InvalidIsolationLevelException) and the service could not run here.
        cfg.setProperty(AvailableSettings.CONNECTION_HANDLING, "DELAYED_ACQUISITION_AND_HOLD");
        cfg.setProperty("hibernate.hbm2ddl.auto", "validate");
        cfg.setProperty("hibernate.show_sql", "false");
        return cfg.buildSessionFactory();
    }

    /** A SessionFactory over SafeConnections. Closing it closes the factory, fails if any connection release failed or
     * any connection was never released (even when Spring or Hibernate only logged it), and confirms on the server that
     * none of the sessions it handed out remains. sessionSetup applies only to this factory's own connections. */
    private static final class Hib implements AutoCloseable {
        private final Db db;
        private final Ledger ledger;
        private final SessionFactory factory;

        Hib(Db db, String sessionSetup) throws Exception {
            this.db = db;
            this.ledger = new Ledger(db);
            SessionFactory built;
            try {
                built = factory(new SafeConnections(db, ledger, sessionSetup));
            } catch (Throwable failure) {
                try {
                    verifyReleases();
                } catch (Throwable cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
            factory = built;
        }

        SessionFactory f() { return factory; }

        private void verifyReleases() throws Exception {
            Throwable failure = null;
            try {
                ledger.verify();
            } catch (Throwable leak) {
                failure = add(failure, leak);
            }
            try {
                awaitGone(db, ledger.ids());
            } catch (Throwable unreleased) {
                failure = add(failure, unreleased);
            }
            if (failure != null) throw rethrow(failure);
        }

        @Override public void close() throws Exception {
            Throwable failure = null;
            try {
                factory.close();
            } catch (Throwable closeFailure) {
                failure = add(failure, closeFailure);
            }
            try {
                verifyReleases();
            } catch (Throwable releaseFailure) {
                failure = add(failure, releaseFailure);
            }
            if (failure != null) throw rethrow(failure);
        }
    }

    /** Flyway's connections are the probe's too: opened through Db.connect, so they are registered and must be gone when
     * the mode ends; Flyway itself closes them. */
    private static DataSource flywaySource(Db db) {
        return new DataSource() {
            @Override public Connection getConnection() throws SQLException { return db.connect("flyway"); }
            @Override public Connection getConnection(String user, String password) throws SQLException {
                throw new SQLFeatureNotSupportedException("Credentials come from the harness");
            }
            @Override public PrintWriter getLogWriter() { return null; }
            @Override public void setLogWriter(PrintWriter out) { }
            @Override public void setLoginTimeout(int seconds) { }
            @Override public int getLoginTimeout() { return 0; }
            @Override public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
                throw new SQLFeatureNotSupportedException();
            }
            @Override public <T> T unwrap(Class<T> type) throws SQLException { throw new SQLException("Not a wrapper"); }
            @Override public boolean isWrapperFor(Class<?> type) { return false; }
        };
    }

    private static Ledger ledgerOf(SessionFactory f) {
        ConnectionProvider provider = f.unwrap(SessionFactoryImplementor.class).getServiceRegistry().getService(ConnectionProvider.class);
        return Objects.requireNonNull(provider, "No connection provider").unwrap(SafeConnections.class).ledger();
    }

    /** The production service over the real JPA transaction manager stack (Hibernate dialect, shared EntityManager). */
    private record Stack(AuditScanService service, TransactionTemplate outer, MutableClock clock) { }

    private static Stack stack(SessionFactory f) throws Exception {
        JpaTransactionManager tm = new JpaTransactionManager(f);
        tm.setJpaDialect(new HibernateJpaDialect());
        tm.afterPropertiesSet();
        MutableClock clock = new MutableClock(CREATED);
        AuditScanService service = new AuditScanService(SharedEntityManagerCreator.createSharedEntityManager(f), tm, clock);
        // The service's own template must really run READ_COMMITTED with its finite timeout on the MariaDB driver.
        var field = AuditScanService.class.getDeclaredField("transactions");
        field.setAccessible(true);
        TransactionTemplate own = (TransactionTemplate) field.get(service);
        assertEquals(AuditScanService.TIMEOUT_SECONDS, own.getTimeout());
        // Typed explicitly: javac rejects the inline poly expression as ambiguous between assertEquals(int, Integer) and
        // assertEquals(long, Long) (Eclipse's compiler accepts it).
        Integer isolation = own.execute(s -> SharedEntityManagerCreator.createSharedEntityManager(f).unwrap(Session.class)
                .doReturningWork(Connection::getTransactionIsolation));
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, isolation);
        return new Stack(service, new TransactionTemplate(tm), clock);
    }

    static final class MutableClock extends Clock {
        private volatile Instant now;
        MutableClock(Instant now) { this.now = now; }
        void set(Instant instant) { now = instant; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    // ---------------------------------------------------------------- transaction and cleanup protocol

    @FunctionalInterface private interface SqlWork { void run(Connection tx) throws Exception; }

    @FunctionalInterface private interface SessionWork { void run(Session session) throws Exception; }

    /** Aborts the session's physical connection (never committing it) on the session's own thread and tells the ledger,
     * so Hibernate's later release of that closed connection is the expected no-op; a failure is kept on the owner and
     * recorded in the ledger. */
    private static void discardSession(Session session, Throwable owner) {
        try {
            Ledger ledger = ledgerOf(session.getSessionFactory());
            Throwable[] discardFailure = new Throwable[1];
            try {
                session.doWork(connection -> {
                    ledger.discarded(connection);
                    discardFailure[0] = releaseHandle(ledger.db(), connection, false);
                });
            } catch (Throwable workFailure) {
                discardFailure[0] = add(discardFailure[0], workFailure);
            }
            if (discardFailure[0] != null) {
                ledger.record("Discarding a session connection failed", discardFailure[0]);
                throw discardFailure[0];
            }
        } catch (Throwable failure) {
            owner.addSuppressed(failure);
        }
    }

    private static void endSession(Session session, Transaction tx, Throwable primary) throws Exception {
        if (!tx.isActive()) {
            return;
        }
        try {
            tx.rollback();
        } catch (Throwable rollbackFailure) {
            discardSession(session, rollbackFailure);
            report(primary, rollbackFailure);
        }
    }

    /** One Hibernate transaction on its own session: committed only when commit=true and the work completed,
     * otherwise rolled back explicitly before the session closes; an uncertain end aborts the connection. */
    private static void inSession(SessionFactory f, boolean commit, SessionWork work) throws Exception {
        try (Session session = f.openSession()) {
            Transaction tx = session.beginTransaction();
            try {
                work.run(session);
            } catch (Throwable failure) {
                endSession(session, tx, failure);
                throw failure;
            }
            if (!commit) {
                endSession(session, tx, null);
                return;
            }
            try {
                tx.commit();
            } catch (Throwable failure) {
                discardSession(session, failure);
                throw failure;
            }
        }
    }

    private static void transition(SessionFactory f, long scanId, Consumer<Scan> action) throws Exception {
        inSession(f, true, s -> action.accept(s.find(Scan.class, scanId)));
    }

    private static boolean exists(Connection c, String table) throws SQLException {
        return "1".equals(scalar(c, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='" + table + "'"));
    }

    private static String join(Collection<Long> ids) {
        return String.join(",", ids.stream().map(String::valueOf).toList());
    }

    /** Fixtures one mode owns: users inserted with ids proven unused beforehand and organizations it created. Every
     * other row it creates hangs from those organizations, so nothing pre-existing is ever selected for removal. */
    private static final class Fixtures {
        private final Db db;
        private final Map<String, List<String>> legacyBefore;
        private final Map<String, List<String>> parentsBefore;
        private final Set<Long> users = new LinkedHashSet<>();
        private final Set<Long> organizations = new LinkedHashSet<>();

        Fixtures(Db db, Map<String, List<String>> legacyBefore, Map<String, List<String>> parentsBefore) {
            this.db = db;
            this.legacyBefore = legacyBefore;
            this.parentsBefore = parentsBefore;
        }

        long user(Connection c, long id, String role, boolean active) throws SQLException {
            assertEquals("0", scalar(c, "SELECT COUNT(*) FROM usuarios WHERE id=" + id), "User id " + id + " exists and is not owned by this mode");
            identity(c, id, role, active);
            users.add(id);
            return id;
        }

        long organization(Connection c, String name) throws SQLException {
            return adopt(ScanMariaDbProbe.organization(c, name));
        }

        /** An organization this mode has just inserted by other means (multi-row INSERT). */
        long adopt(long id) {
            organizations.add(id);
            return id;
        }

        /** One transaction removes children, organizations and users together; then the result is verified. */
        void remove(Connection observer) throws Exception {
            if (!organizations.isEmpty() || !users.isEmpty()) {
                String orgs = join(organizations);
                String ids = join(users);
                transaction(db, true, tx -> {
                    if (!organizations.isEmpty()) {
                        for (String table : List.of("scans", "audits", "sites", "organization_memberships")) {
                            if (exists(tx, table)) {
                                execute(tx, "DELETE FROM " + table + " WHERE organization_id IN (" + orgs + ")");
                            }
                        }
                        execute(tx, "DELETE FROM organizations WHERE id IN (" + orgs + ")");
                    }
                    if (!users.isEmpty()) {
                        execute(tx, "DELETE FROM usuarios WHERE id IN (" + ids + ")");
                    }
                });
            }
            verify(observer);
        }

        private void verify(Connection observer) throws SQLException {
            if (!organizations.isEmpty()) {
                String orgs = join(organizations);
                assertEquals("0", scalar(observer, "SELECT COUNT(*) FROM organizations WHERE id IN (" + orgs + ")"), "Owned organizations remain");
                for (String table : List.of("scans", "audits", "sites", "organization_memberships")) {
                    if (exists(observer, table)) {
                        assertEquals("0", scalar(observer, "SELECT COUNT(*) FROM " + table + " WHERE organization_id IN (" + orgs + ")"),
                                "Owned rows remain in " + table);
                    }
                }
            }
            if (!users.isEmpty()) {
                assertEquals("0", scalar(observer, "SELECT COUNT(*) FROM usuarios WHERE id IN (" + join(users) + ")"), "Owned users remain");
            }
            if (exists(observer, "audits") && exists(observer, "scans")) {
                assertEquals("0", scalar(observer, ORPHANS), "Cleanup left an Audit without its Scan");
            }
            if (legacyBefore != null) {
                assertEquals(legacyBefore, snapshot(observer, LEGACY), "Legacy tables differ after cleanup");
            }
            if (parentsBefore != null) {
                assertEquals(parentsBefore, snapshot(observer, PARENTS), "2.0 tables differ after cleanup");
            }
        }
    }

    @FunctionalInterface private interface Body { void run(Fixtures fixtures) throws Exception; }

    /** Runs a mode body that owns its fixtures. Cleanup runs on success and on any SQLException, RuntimeException or
     * AssertionError, after the body has closed its factories, threads and holders; it must succeed and be verified.
     * While a worker thread of this run is still running it may still use the fixtures: they are then left in place
     * and the mode fails, its cleanup not validated (the harness removes the isolated server). */
    private static void owned(Connection c, Db db, boolean snapshots, Body body) throws Exception {
        Fixtures fixtures = new Fixtures(db, snapshots ? snapshot(c, LEGACY) : null, snapshots ? snapshot(c, PARENTS) : null);
        Throwable primary = null;
        try {
            body.run(fixtures);
        } catch (Throwable failure) {
            primary = failure;
            throw failure;
        } finally {
            if (Workers.unfinished()) {
                report(primary, new AssertionError("Fixtures left in place: worker threads of this run are still running and may "
                        + "use them; cleanup not validated, left to the harness"));
            } else {
                try {
                    fixtures.remove(c);
                } catch (Throwable cleanupFailure) {
                    report(primary, cleanupFailure);
                }
            }
        }
    }

    /** Drops the temporary trigger whatever happened, falling back to a fresh connection, and verifies it is gone. */
    private static void dropTrigger(Connection c, Db db, Throwable primary) throws Exception {
        try {
            dropAndVerifyTrigger(c);
        } catch (Throwable first) {
            // Even when the fresh connection drops it, the observer failure is still reported.
            Throwable failure = first;
            try (Owned fresh = Owned.open(db, "trigger cleanup")) {
                dropAndVerifyTrigger(fresh.connection());
            } catch (Throwable second) {
                second.addSuppressed(first);
                failure = second;
            }
            report(primary, failure);
        }
    }

    private static void dropAndVerifyTrigger(Connection c) throws SQLException {
        execute(c, "DROP TRIGGER IF EXISTS " + TRIGGER);
        assertEquals("0", triggers(c), "Temporary trigger remains");
    }

    // ---------------------------------------------------------------- SQL helpers

    private static void execute(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) { s.execute(sql); }
    }

    private static String scalar(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            assertTrue(r.next(), sql);
            String result = r.getString(1);
            assertFalse(r.next(), sql);
            return result;
        }
    }

    private static Set<Long> longs(Connection c, String sql) throws SQLException {
        Set<Long> values = new TreeSet<>();
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            while (r.next()) values.add(r.getLong(1));
        }
        return values;
    }

    private static long connectionId(Connection c) throws SQLException {
        return Long.parseLong(scalar(c, "SELECT CONNECTION_ID()"));
    }

    private static String sqlTime(Instant instant) {
        return SQL_TIME.format(instant);
    }

    private static String triggers(Connection c) throws SQLException {
        return scalar(c, "SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE()");
    }

    private static String totals(Connection c) throws SQLException {
        return scalar(c, "SELECT CONCAT((SELECT COUNT(*) FROM audits),'|',(SELECT COUNT(*) FROM scans),'|',(" + ORPHANS + "))");
    }

    private static void rejected(Connection c, String sql, int code) {
        SQLException error = assertThrows(SQLException.class, () -> execute(c, sql));
        assertEquals(code, error.getErrorCode(), error.getMessage());
    }

    /** FK and CHECK failures name the constraint between backticks. */
    private static void rejectedBy(Connection c, String sql, int code, String constraint) {
        SQLException error = assertThrows(SQLException.class, () -> execute(c, sql));
        assertEquals(code, error.getErrorCode(), error.getMessage());
        assertTrue(error.getMessage().contains("`" + constraint + "`"), error.getMessage());
    }

    /** Duplicate-key failures name the key between single quotes. */
    private static void duplicate(Connection c, String sql, String key) {
        SQLException error = assertThrows(SQLException.class, () -> execute(c, sql));
        assertEquals(1062, error.getErrorCode(), error.getMessage());
        assertTrue(error.getMessage().contains("'" + key + "'"), error.getMessage());
    }

    private static long generated(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            assertEquals(1, s.executeUpdate(sql, Statement.RETURN_GENERATED_KEYS));
            try (ResultSet keys = s.getGeneratedKeys()) { assertTrue(keys.next()); return keys.getLong(1); }
        }
    }

    private static void identity(Connection c, long id, String role, boolean active) throws SQLException {
        execute(c, "INSERT INTO usuarios(id,email,fecha_registro,nombre,password,role,activo,protegido,token_version,row_version) "
                + "VALUES (" + id + ",'scan-" + id + "@f3.invalid','2001-02-03 04:05:06.123456','Scan fixture','unused','"
                + role + "'," + (active ? "b'1'" : "b'0'") + ",b'0',0,0)");
    }

    private static long organization(Connection c, String name) throws SQLException {
        return generated(c, "INSERT INTO organizations(name,personal_owner_user_id,created_at,updated_at,row_version) VALUES ('"
                + name + "',NULL," + T0 + "," + T0 + ",0)");
    }

    private static void member(Connection c, long organization, long user, String status) throws SQLException {
        execute(c, "INSERT INTO organization_memberships(organization_id,user_id,role,status,created_at,updated_at,row_version) VALUES ("
                + organization + "," + user + ",'OWNER','" + status + "'," + T0 + "," + T0 + ",0)");
    }

    private static long site(Connection c, long organization, long creator, String status) throws SQLException {
        return generated(c, "INSERT INTO sites(organization_id,name,base_url,status,created_by_user_id,created_at,updated_at,row_version) "
                + "VALUES (" + organization + ",'Scan parent site','https://scan-parent.invalid/','" + status + "'," + creator + ","
                + T0 + "," + T0 + ",0)");
    }

    private static String scan(Object organization, Object audit, Object attempt, String status, Object creator,
                               String created, String updated, String started, String finished) {
        return "INSERT INTO scans(organization_id,audit_id,attempt_number,status,created_by_user_id,created_at,updated_at,"
                + "started_at,finished_at,row_version) VALUES (" + organization + "," + audit + "," + attempt + "," + status + ","
                + creator + "," + created + "," + updated + "," + started + "," + finished + ",0)";
    }

    private static String row(Connection c, long scanId) throws SQLException {
        return scalar(c, "SELECT CONCAT(status,'|',IF(started_at IS NULL,'-','S'),'|',IF(finished_at IS NULL,'-','F'),'|',"
                + "attempt_number,'|',IFNULL(active_audit_id,'NULL'),'|',row_version) FROM scans WHERE id=" + scanId);
    }

    private static String attempts(Connection c, long auditId) throws SQLException {
        return scalar(c, "SELECT GROUP_CONCAT(CONCAT(attempt_number,':',status) ORDER BY attempt_number) FROM scans WHERE audit_id=" + auditId);
    }

    private static void assertConflict(RuntimeException e) {
        assertTrue(e instanceof OptimisticLockException || e instanceof StaleStateException, e.getClass().getName());
    }

    private static String where(String view, String schemaColumn) {
        return " FROM information_schema." + view + " WHERE " + schemaColumn + "=DATABASE() AND table_name='scans'";
    }

    // ---------------------------------------------------------------- schema

    private static void schema(Connection c) throws SQLException {
        String columns = where("columns", "table_schema");
        assertEquals("id:bigint(20):NO:NULL:auto_increment,organization_id:bigint(20):NO:NULL:,audit_id:bigint(20):NO:NULL:,"
                + "attempt_number:int(11):NO:NULL:,status:varchar(16):NO:NULL:,created_by_user_id:bigint(20):NO:NULL:,"
                + "created_at:datetime(6):NO:NULL:,updated_at:datetime(6):NO:NULL:,started_at:datetime(6):YES:NULL:,"
                + "finished_at:datetime(6):YES:NULL:,row_version:bigint(20):NO:0:", scalar(c, "SELECT GROUP_CONCAT(CONCAT(column_name,':',"
                + "column_type,':',is_nullable,':',IFNULL(column_default,'NULL'),':',extra) ORDER BY ordinal_position)"
                + columns + " AND ordinal_position <= 11"));
        assertEquals("12", scalar(c, "SELECT COUNT(*)" + columns));
        assertEquals("active_audit_id|12|bigint(20)|ALWAYS|1", scalar(c, "SELECT CONCAT(column_name,'|',ordinal_position,'|',"
                + "column_type,'|',is_generated,'|',(extra IN ('STORED GENERATED','PERSISTENT GENERATED')))" + columns
                + " AND ordinal_position = 12"));
        String expression = scalar(c, "SELECT LOWER(generation_expression)" + columns + " AND column_name='active_audit_id'");
        assertTrue(expression.contains("pending") && expression.contains("running") && expression.contains("audit_id"), expression);
        assertEquals("status:utf8mb4:utf8mb4_unicode_520_ci:16", scalar(c, "SELECT GROUP_CONCAT(CONCAT(column_name,':',"
                + "character_set_name,':',collation_name,':',character_maximum_length))" + columns + " AND character_set_name IS NOT NULL"));
        assertEquals("4", scalar(c, "SELECT COUNT(*)" + columns + " AND data_type='datetime' AND datetime_precision=6"));
        assertEquals("InnoDB|utf8mb4_unicode_520_ci", scalar(c, "SELECT CONCAT(engine,'|',table_collation)" + where("tables", "table_schema")));
        String statistics = where("statistics", "table_schema");
        assertEquals("5", scalar(c, "SELECT COUNT(DISTINCT index_name)" + statistics));
        Map<String, String> indexes = Map.of("PRIMARY", "0:id", "uq_scans_audit_attempt", "0:audit_id,attempt_number",
                "uq_scans_one_active", "0:active_audit_id", "ix_scans_org_audit", "1:organization_id,audit_id",
                "ix_scans_created_by", "1:created_by_user_id");
        for (var index : indexes.entrySet()) {
            assertEquals(index.getValue(), scalar(c, "SELECT CONCAT(MAX(non_unique),':',GROUP_CONCAT(column_name ORDER BY seq_in_index))"
                    + statistics + " AND index_name='" + index.getKey() + "'"), index.getKey());
        }
        assertEquals("CHECK:ck_scans_attempt,CHECK:ck_scans_chronology,CHECK:ck_scans_lifecycle,CHECK:ck_scans_status,"
                + "FOREIGN KEY:fk_scans_audit,FOREIGN KEY:fk_scans_created_by,FOREIGN KEY:fk_scans_organization,PRIMARY KEY:PRIMARY,"
                + "UNIQUE:uq_scans_audit_attempt,UNIQUE:uq_scans_one_active", scalar(c, "SELECT GROUP_CONCAT(CONCAT(constraint_type,':',"
                + "constraint_name) ORDER BY constraint_type,constraint_name)" + where("table_constraints", "table_schema")));
        assertEquals("fk_scans_audit:RESTRICT:RESTRICT:audits,fk_scans_created_by:RESTRICT:RESTRICT:usuarios,"
                + "fk_scans_organization:RESTRICT:RESTRICT:organizations", scalar(c, "SELECT GROUP_CONCAT(CONCAT(constraint_name,':',"
                + "delete_rule,':',update_rule,':',referenced_table_name) ORDER BY constraint_name)"
                + where("referential_constraints", "constraint_schema")));
        assertEquals("fk_scans_audit:organization_id:audits:organization_id,fk_scans_audit:audit_id:audits:id,"
                + "fk_scans_created_by:created_by_user_id:usuarios:id,fk_scans_organization:organization_id:organizations:id",
                scalar(c, "SELECT GROUP_CONCAT(CONCAT(constraint_name,':',column_name,':',referenced_table_name,':',referenced_column_name) "
                        + "ORDER BY constraint_name,ordinal_position)" + where("key_column_usage", "table_schema")
                        + " AND referenced_table_name IS NOT NULL"));
        assertEquals("ck_scans_attempt,ck_scans_chronology,ck_scans_lifecycle,ck_scans_status", scalar(c,
                "SELECT GROUP_CONCAT(constraint_name ORDER BY constraint_name)" + where("check_constraints", "constraint_schema")));
        assertEquals("0", triggers(c));
    }

    // ---------------------------------------------------------------- contracts

    private static void contracts(Connection c, Db db) throws Exception {
        assertEquals("0", scalar(c, "SELECT COUNT(*) FROM scans"));
        assertEquals("0", scalar(c, "SELECT COUNT(*) FROM audits"));
        schema(c);
        owned(c, db, true, fx -> {
            fx.user(c, 800901L, "CLIENTE", true);
            fx.user(c, 800902L, "CLIENTE", true);
            fx.user(c, 800903L, "CLIENTE", true);
            long orgA = fx.organization(c, "Scan org A");
            long orgB = fx.organization(c, "Scan org B");
            member(c, orgA, 800901L, "ACTIVE");
            member(c, orgA, 800902L, "ACTIVE");
            member(c, orgB, 800903L, "ACTIVE");
            long siteA = site(c, orgA, 800901L, "ACTIVE");
            try (Hib hib = new Hib(db, null)) {
                SessionFactory f = hib.f();
                Stack stack = stack(f);
                var first = stack.service().createAudit(orgA, siteA, 800901L);
                long auditA = first.auditId();
                assertEquals("1|1|0", totals(c), "The observer sees the Audit and its Scan committed together");
                assertEquals("2024-02-29 23:58:57.987654|2024-02-29 23:58:57.987654|2024-02-29 23:58:57.987654", scalar(c,
                        "SELECT CONCAT(DATE_FORMAT(a.created_at,'%Y-%m-%d %H:%i:%s.%f'),'|',DATE_FORMAT(s.created_at,'%Y-%m-%d %H:%i:%s.%f'),'|',"
                                + "DATE_FORMAT(s.updated_at,'%Y-%m-%d %H:%i:%s.%f')) FROM scans s JOIN audits a ON a.id=s.audit_id WHERE s.id=" + first.scanId()));
                assertEquals("PENDING|-|-|1|" + auditA + "|0", row(c, first.scanId()), "Active Scans expose active_audit_id");
                inSession(f, false, s -> {
                    Scan loaded = s.find(Scan.class, first.scanId());
                    assertFalse(Hibernate.isInitialized(loaded.getAudit()), "Audit must load lazily");
                    assertFalse(Hibernate.isInitialized(loaded.getOrganization()), "Organization must load lazily");
                    assertFalse(Hibernate.isInitialized(loaded.getCreatedBy()), "createdBy must load lazily");
                    assertEquals(Instant.parse("2024-02-29T23:58:57.987654Z"), loaded.getCreatedAt());
                    assertEquals(auditA, loaded.getAudit().getId());
                });
                // Second Audit whose only Scan is CANCELLED: no active Scan, used for the SQL contract inserts.
                var second = stack.service().createAudit(orgA, siteA, 800901L);
                long auditB = second.auditId();
                stack.service().cancelScan(orgA, second.scanId(), 800902L);
                assertEquals("CANCELLED|-|F|1|NULL|1", row(c, second.scanId()), "Terminal Scans leave active_audit_id NULL");
                // SQL backstops, independent of the service.
                duplicate(c, scan(orgA, auditA, 2, "'PENDING'", 800901L, T0, T0, "NULL", "NULL"), "uq_scans_one_active");
                duplicate(c, scan(orgA, auditA, 2, "'RUNNING'", 800901L, T0, T0, T0, "NULL"), "uq_scans_one_active");
                duplicate(c, scan(orgA, auditB, 1, "'CANCELLED'", 800901L, T0, T0, "NULL", T0), "uq_scans_audit_attempt");
                rejectedBy(c, scan(orgB, auditA, 9, "'CANCELLED'", 800901L, T0, T0, "NULL", T0), 1452, "fk_scans_audit");
                rejectedBy(c, scan(orgA, Long.MAX_VALUE, 9, "'CANCELLED'", 800901L, T0, T0, "NULL", T0), 1452, "fk_scans_audit");
                rejectedBy(c, scan(orgA, auditB, 9, "'CANCELLED'", Long.MAX_VALUE, T0, T0, "NULL", T0), 1452, "fk_scans_created_by");
                String[] values = {Long.toString(orgA), Long.toString(auditB), "9", "'CANCELLED'", "800901", T0, T0, "NULL", T0, "0"};
                for (int i : new int[]{0, 1, 2, 3, 4, 5, 6, 9}) {
                    String[] missing = values.clone();
                    missing[i] = "NULL";
                    rejected(c, "INSERT INTO scans(organization_id,audit_id,attempt_number,status,created_by_user_id,created_at,updated_at,"
                            + "started_at,finished_at,row_version) VALUES (" + String.join(",", missing) + ")", 1048);
                }
                for (String status : List.of("'pending'", "'Pending'", "'PENDING '", "' PENDING'", "'DONE'", "''", "'CANCELED'", "'FA\u0130LED'")) {
                    rejected(c, scan(orgA, auditB, 9, status, 800901L, T0, T0, T0, T0), 4025);
                }
                rejectedBy(c, scan(orgA, auditB, 0, "'CANCELLED'", 800901L, T0, T0, "NULL", T0), 4025, "ck_scans_attempt");
                rejectedBy(c, scan(orgA, auditB, -1, "'CANCELLED'", 800901L, T0, T0, "NULL", T0), 4025, "ck_scans_attempt");
                // Lifecycle-only violations (chronology valid).
                for (String[] v : List.of(
                        new String[]{"'PENDING'", T0, T0, T0, "NULL"}, new String[]{"'PENDING'", T0, T0, "NULL", T0},
                        new String[]{"'RUNNING'", T0, T0, "NULL", "NULL"}, new String[]{"'RUNNING'", T0, T1, T0, T1},
                        new String[]{"'SUCCEEDED'", T0, T0, T0, "NULL"}, new String[]{"'FAILED'", T0, T0, "NULL", T0},
                        new String[]{"'CANCELLED'", T0, T0, T0, "NULL"})) {
                    rejectedBy(c, scan(orgA, auditB, 10, v[0], 800901L, v[1], v[2], v[3], v[4]), 4025, "ck_scans_lifecycle");
                }
                // Chronology-only violations (lifecycle valid), including the corrected updated_at rules.
                for (String[] v : List.of(
                        new String[]{"'PENDING'", T0, TBEFORE, "NULL", "NULL"}, new String[]{"'RUNNING'", T0, T0, TBEFORE, "NULL"},
                        new String[]{"'RUNNING'", T0, T0, T1, "NULL"}, new String[]{"'SUCCEEDED'", T0, T1, T1, T0},
                        new String[]{"'CANCELLED'", T0, T0, "NULL", T1})) {
                    rejectedBy(c, scan(orgA, auditB, 11, v[0], 800901L, v[1], v[2], v[3], v[4]), 4025, "ck_scans_chronology");
                }
                // Accepted shapes on a dedicated connection, always rolled back: equal microsecond instants and both CANCELLED origins.
                transaction(db, false, tx -> {
                    execute(tx, scan(orgA, auditB, 20, "'SUCCEEDED'", 800901L, T0, T0, T0, T0));
                    execute(tx, scan(orgA, auditB, 21, "'CANCELLED'", 800901L, T0, T1, "NULL", T1));
                    execute(tx, scan(orgA, auditB, 22, "'CANCELLED'", 800901L, T0, T1, T0, T1));
                    execute(tx, scan(orgA, auditB, 23, "'FAILED'", 800901L, T0, T1, T0, T1));
                    assertEquals("5", scalar(tx, "SELECT COUNT(*) FROM scans WHERE audit_id=" + auditB));
                });
                assertEquals("1", scalar(c, "SELECT COUNT(*) FROM scans WHERE audit_id=" + auditB));
                rejectedBy(c, "DELETE FROM audits WHERE id=" + auditA, 1451, "fk_scans_audit");
                rejectedBy(c, "UPDATE audits SET id=9223372036854775807 WHERE id=" + auditA, 1451, "fk_scans_audit");
                rejected(c, "DELETE FROM organizations WHERE id=" + orgA, 1451);
                // Scan authored by 800902 only: after its membership row is removed only fk_scans_created_by references it.
                var retried = stack.service().retryScan(orgA, auditB, 800902L);
                assertEquals(2, retried.attemptNumber());
                execute(c, "DELETE FROM organization_memberships WHERE user_id=800902");
                rejectedBy(c, "DELETE FROM usuarios WHERE id=800902", 1451, "fk_scans_created_by");
                rejectedBy(c, "UPDATE usuarios SET id=800904 WHERE id=800902", 1451, "fk_scans_created_by");
                // Optimistic locking between sessions: the stale copy loses after the other session committed.
                inSession(f, false, stale -> {
                    Scan b = stale.find(Scan.class, retried.scanId());
                    inSession(f, true, fresh -> fresh.find(Scan.class, retried.scanId()).start(Instant.parse("2024-03-01T00:00:00.000001Z")));
                    b.cancel(Instant.parse("2024-03-01T00:00:00.000002Z"));
                    assertConflict(assertThrows(RuntimeException.class, stale::flush));
                });
                assertEquals("RUNNING|S|-|2|" + auditB + "|1", row(c, retried.scanId()));
                // Identity is immutable: organization, audit, attempt, creator and creation never change on update.
                inSession(f, true, s -> {
                    Scan scan = s.find(Scan.class, retried.scanId());
                    ScanTest.set(scan, "organization", s.getReference(Organization.class, orgB));
                    ScanTest.set(scan, "audit", s.getReference(Audit.class, auditA));
                    ScanTest.set(scan, "attemptNumber", 77);
                    ScanTest.set(scan, "createdBy", s.getReference(Usuario.class, 800901L));
                    ScanTest.set(scan, "createdAt", Instant.parse("2030-01-01T00:00:00Z"));
                    scan.succeed(Instant.parse("2024-03-01T00:00:00.000003999Z"));
                    s.flush();
                    assertEquals(Instant.parse("2024-03-01T00:00:00.000003Z"), scan.getUpdatedAt());
                });
                assertEquals(orgA + "|" + auditB + "|2|800902|SUCCEEDED|2", scalar(c, "SELECT CONCAT(organization_id,'|',audit_id,'|',"
                        + "attempt_number,'|',created_by_user_id,'|',status,'|',row_version) FROM scans WHERE id=" + retried.scanId()));
                assertEquals("2024-02-29 23:58:57.987654", scalar(c, "SELECT DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s.%f') FROM scans WHERE id="
                        + retried.scanId()));
                assertEquals("0", scalar(c, ORPHANS));
            }
        });
        System.out.println("SCAN CONTRACTS PASS: " + ZoneId.systemDefault() + "; 12 columns incl. STORED generated/5 indexes/3 FK RESTRICT/"
                + "4 CHECK/one active UNIQUE/attempt UNIQUE/NOT NULL/lifecycle+chronology/UTC micros/lazy/version between sessions/"
                + "immutable identity; Audits only with Scans; owned fixtures removed in one verified transaction");
    }

    // ---------------------------------------------------------------- tenant

    private static void tenant(Connection c, Db db) throws Exception {
        owned(c, db, true, fx -> {
            fx.user(c, 801001L, "CLIENTE", true);
            fx.user(c, 801002L, "CLIENTE", true);
            long orgA = fx.organization(c, "Scan tenant A");
            long orgB = fx.organization(c, "Scan tenant B");
            member(c, orgA, 801001L, "ACTIVE");
            member(c, orgB, 801002L, "ACTIVE");
            long siteA = site(c, orgA, 801001L, "ACTIVE");
            long siteB = site(c, orgB, 801002L, "ACTIVE");
            try (Hib hib = new Hib(db, null)) {
                SessionFactory f = hib.f();
                Stack stack = stack(f);
                var a = stack.service().createAudit(orgA, siteA, 801001L);
                var b = stack.service().createAudit(orgB, siteB, 801002L);
                stack.service().cancelScan(orgA, a.scanId(), 801001L);
                rejectedBy(c, scan(orgB, a.auditId(), 5, "'CANCELLED'", 801002L, T0, T0, "NULL", T0), 1452, "fk_scans_audit");
                rejectedBy(c, scan(orgA, b.auditId(), 5, "'CANCELLED'", 801001L, T0, T0, "NULL", T0), 1452, "fk_scans_audit");
                rejectedBy(c, "UPDATE scans SET organization_id=" + orgB + " WHERE id=" + a.scanId(), 1452, "fk_scans_audit");
                // A fresh attempt number keeps uq_scans_audit_attempt out of the way, so only the tenant FK can reject it.
                rejectedBy(c, "UPDATE scans SET audit_id=" + b.auditId() + ",attempt_number=99 WHERE id=" + a.scanId(), 1452, "fk_scans_audit");
                assertThrows(EntityNotFoundException.class, () -> stack.service().retryScan(orgB, a.auditId(), 801002L));
                assertThrows(EntityNotFoundException.class, () -> stack.service().retryScan(orgA, b.auditId(), 801001L));
                assertThrows(EntityNotFoundException.class, () -> stack.service().cancelScan(orgA, b.scanId(), 801001L));
                assertThrows(EntityNotFoundException.class, () -> stack.service().cancelScan(orgB, b.scanId(), 801001L));
                assertThrows(EntityNotFoundException.class, () -> stack.service().createAudit(orgA, siteB, 801001L));
                assertEquals("PENDING|-|-|1|" + b.auditId() + "|0", row(c, b.scanId()), "Foreign Scan untouched");
                assertEquals("2|2|0", totals(c));
            }
        });
        System.out.println("SCAN TENANT FK PASS: (organization_id, audit_id) -> audits(organization_id, id); cross-tenant insert/update 1452; "
                + "service retry/cancel/create across tenants rejected as not found; owned fixtures removed and verified");
    }

    // ---------------------------------------------------------------- service

    /** The single injected trigger failure: every SQLException in the cause chain must carry this run's marker,
     * SQLSTATE 45000 and errno 1644, and name attempt 1 of an Audit the trigger saw in the same transaction.
     * Authorization, state, transaction, timeout or connection failures are rejected, never accepted as evidence. */
    private static long injectedFirstScanFailure(Throwable error, String marker) {
        assertFalse(error instanceof TransactionException, "Transaction infrastructure failure instead of the injected INSERT failure: " + error);
        assertFalse(error instanceof EntityNotFoundException || error instanceof IllegalStateException, "Rejected before persisting: " + error);
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<SQLException> chain = new ArrayList<>();
        for (Throwable t = error; t != null && seen.add(t); t = t.getCause()) {
            if (t instanceof SQLException sql) {
                for (SQLException s = sql; s != null; s = s.getNextException()) {
                    if (!chain.contains(s)) chain.add(s);
                }
            }
        }
        assertFalse(chain.isEmpty(), "No SQLException behind the failure: " + error);
        for (SQLException s : chain) {
            assertTrue(s.getMessage() != null && s.getMessage().contains(marker), "Unexpected SQL failure: " + s);
            assertEquals("45000", s.getSQLState(), s.toString());
            assertEquals(1644, s.getErrorCode(), s.toString());
        }
        Matcher m = Pattern.compile(Pattern.quote(marker) + " audit=([0-9]+) attempt=([0-9]+)").matcher(chain.get(0).getMessage());
        assertTrue(m.find(), "The trigger did not find the Audit in the same transaction: " + chain.get(0).getMessage());
        assertEquals("1", m.group(2), "The failed INSERT must be the first Scan");
        return Long.parseLong(m.group(1));
    }

    /** Fires only for the first Scan INSERTs of actor 801199 and reports whether its Audit row already exists in the
     * same transaction; the message is unique to this run. Created on the isolated schema only, never in Flyway. */
    private static String failureTrigger(String marker) {
        return "CREATE TRIGGER " + TRIGGER + " BEFORE INSERT ON scans FOR EACH ROW BEGIN "
                + "DECLARE fault VARCHAR(128); "
                + "IF NEW.created_by_user_id = 801199 THEN "
                + "IF (SELECT COUNT(*) FROM audits a WHERE a.id = NEW.audit_id AND a.organization_id = NEW.organization_id "
                + "AND a.created_by_user_id = 801199) = 1 THEN "
                + "SET fault = CONCAT('" + marker + " audit=', NEW.audit_id, ' attempt=', NEW.attempt_number); "
                + "ELSE SET fault = '" + marker + " audit-missing'; END IF; "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = fault, MYSQL_ERRNO = 1644; "
                + "END IF; END";
    }

    private static void service(Connection c, Db db) throws Exception {
        String marker = "f3-scan-fault-" + required("F3_SCAN_HARNESS_RUN_ID");
        owned(c, db, true, fx -> {
            fx.user(c, 801101L, "CLIENTE", true);  // owner
            fx.user(c, 801102L, "ADMIN", true);    // legacy ADMIN without membership
            fx.user(c, 801103L, "CLIENTE", true);  // revoked owner
            fx.user(c, 801104L, "CLIENTE", false); // inactive owner
            fx.user(c, 801199L, "CLIENTE", true);  // owner whose first Scan insert is made to fail
            long org = fx.organization(c, "Scan service");
            member(c, org, 801101L, "ACTIVE");
            member(c, org, 801103L, "REVOKED");
            member(c, org, 801104L, "ACTIVE");
            member(c, org, 801199L, "ACTIVE");
            long site = site(c, org, 801101L, "ACTIVE");
            long archived = site(c, org, 801101L, "ARCHIVED");
            try (Hib hib = new Hib(db, null)) {
                SessionFactory f = hib.f();
                Stack stack = stack(f);
                AuditScanService service = stack.service();
                var created = service.createAudit(org, site, 801101L);
                assertEquals(new AuditScanService.AuditCreation(created.auditId(), created.scanId(), 1, ScanStatus.PENDING), created);
                assertEquals("1|1|0", totals(c));
                for (long actor : new long[]{801102L, 801103L, 801104L, Long.MAX_VALUE}) {
                    assertThrows(EntityNotFoundException.class, () -> service.createAudit(org, site, actor), "actor " + actor);
                }
                assertThrows(IllegalStateException.class, () -> service.createAudit(org, archived, 801101L));
                IllegalStateException outer = assertThrows(IllegalStateException.class,
                        () -> stack.outer().executeWithoutResult(s -> service.createAudit(org, site, 801101L)));
                assertTrue(outer.getMessage().contains("outside any transaction"));
                assertEquals("1|1|0", totals(c), "Rejected calls wrote nothing");
                // The Audit INSERT happens, then the first Scan INSERT fails inside the same transaction: both roll back.
                assertEquals("0", triggers(c));
                long failedAudit;
                Throwable primary = null;
                try {
                    execute(c, failureTrigger(marker));
                    RuntimeException error = assertThrows(RuntimeException.class, () -> service.createAudit(org, site, 801199L));
                    failedAudit = injectedFirstScanFailure(error, marker);
                } catch (Throwable failure) {
                    primary = failure;
                    throw failure;
                } finally {
                    dropTrigger(c, db, primary);
                }
                assertEquals("1|1|0", totals(c), "No part of the failed creation may persist");
                assertEquals("0|0", scalar(c, "SELECT CONCAT((SELECT COUNT(*) FROM audits WHERE id=" + failedAudit
                        + " OR created_by_user_id=801199),'|',(SELECT COUNT(*) FROM scans WHERE audit_id=" + failedAudit
                        + " OR created_by_user_id=801199))"), "The rolled-back Audit and Scan are absent");
                // Positive control, same actor, injection removed: the pair commits and the independent observer sees it.
                var recovered = service.createAudit(org, site, 801199L);
                assertTrue(recovered.auditId() > failedAudit, "The failed attempt had consumed an Audit identity");
                assertEquals("801199|1|PENDING|801199", scalar(c, "SELECT CONCAT(a.created_by_user_id,'|',s.attempt_number,'|',s.status,'|',"
                        + "s.created_by_user_id) FROM audits a JOIN scans s ON s.organization_id=a.organization_id AND s.audit_id=a.id "
                        + "WHERE a.id=" + recovered.auditId() + " AND s.id=" + recovered.scanId()));
                assertEquals("2|2|0", totals(c));
                // Retry rules with committed data.
                assertThrows(IllegalStateException.class, () -> service.retryScan(org, created.auditId(), 801101L), "active");
                transition(f, created.scanId(), s -> s.start(Instant.parse("2024-03-01T00:00:01Z")));
                transition(f, created.scanId(), s -> s.fail(Instant.parse("2024-03-01T00:00:02Z")));
                stack.clock().set(Instant.parse("2024-03-01T00:00:03Z"));
                var retry = service.retryScan(org, created.auditId(), 801101L);
                assertEquals(2, retry.attemptNumber());
                transition(f, retry.scanId(), s -> { s.start(Instant.parse("2024-03-01T00:00:04Z")); s.succeed(Instant.parse("2024-03-01T00:00:05Z")); });
                assertThrows(IllegalStateException.class, () -> service.retryScan(org, created.auditId(), 801101L), "SUCCEEDED");
                // Cancellation works on an ARCHIVED Site; revocation applies to the next call.
                var toCancel = service.createAudit(org, site, 801101L);
                execute(c, "UPDATE sites SET status='ARCHIVED' WHERE id=" + site);
                assertEquals(ScanStatus.CANCELLED, service.cancelScan(org, toCancel.scanId(), 801101L).status());
                assertThrows(IllegalStateException.class, () -> service.cancelScan(org, toCancel.scanId(), 801101L), "second cancel");
                assertThrows(IllegalStateException.class, () -> service.retryScan(org, toCancel.auditId(), 801101L), "ARCHIVED Site");
                execute(c, "UPDATE sites SET status='ACTIVE' WHERE id=" + site);
                execute(c, "UPDATE organization_memberships SET status='REVOKED' WHERE user_id=801101");
                assertThrows(EntityNotFoundException.class, () -> service.retryScan(org, toCancel.auditId(), 801101L), "revoked owner");
                assertEquals("3|4|0", totals(c));
            }
        });
        System.out.println("SCAN SERVICE PASS: atomic Audit + first Scan commit; injected first-Scan INSERT failure (SQLSTATE 45000/1644, "
                + "run marker, Audit seen in the same transaction) rolls back the Audit; same actor then commits; ADMIN/revoked/inactive "
                + "rejected; ARCHIVED Site; outer transaction refused; retry/cancel rules; revocation on next call; trigger dropped and verified");
    }

    // ---------------------------------------------------------------- concurrency

    private enum Result { OK, REJECTED, CONFLICT, NOT_FOUND }

    private record Outcome(Result result, String detail) { }

    /** Classifies only the outcomes the service defines; timeouts, deadlocks, connection or rollback failures fail the probe. */
    private static Outcome attempt(Callable<?> action) {
        try {
            action.call();
            return new Outcome(Result.OK, "");
        } catch (EntityNotFoundException notFound) {
            return new Outcome(Result.NOT_FOUND, String.valueOf(notFound.getMessage()));
        } catch (IllegalStateException rejected) {
            return new Outcome(Result.REJECTED, String.valueOf(rejected.getMessage()));
        } catch (OptimisticLockException | StaleStateException conflict) {
            return new Outcome(Result.CONFLICT, conflict.getClass().getSimpleName());
        } catch (Exception other) {
            throw new AssertionError("Unexpected concurrency failure", other);
        }
    }

    /** Worker threads of one scenario, as daemon threads (one that cannot be stopped never keeps the JVM alive). close()
     * is safe against an interrupt of the thread that calls it: shutdown() and a bounded wait; if workers are still
     * running, or that wait is interrupted, shutdownNow() (tasks never started are cancelled) and a second bounded wait
     * that keeps waiting through further interrupts until its deadline; the interrupt status is restored before close()
     * returns. A worker that needed shutdownNow(), an interrupt of the closing thread, every task failure and a worker
     * still alive at the end are all reported, the first one primary (as suppressed when another failure is already
     * propagating): close() never passes after an incomplete stop. While workers of this run are still alive
     * (unfinished()), no fixture cleanup runs and no connection a worker may use is aborted: both are left to the
     * harness, failing. Thread.stop() is never used. */
    private static final class Workers implements AutoCloseable {
        private static final Set<Workers> UNFINISHED = ConcurrentHashMap.newKeySet();
        private final ExecutorService pool;
        private final long graceMillis;
        private final long stopMillis;
        private final List<Future<?>> tasks = new ArrayList<>();

        Workers(int threads) { this(threads, TimeUnit.SECONDS.toMillis(WAIT_SECONDS), TimeUnit.SECONDS.toMillis(STOP_SECONDS)); }

        Workers(int threads, long graceMillis, long stopMillis) {
            this.pool = Executors.newFixedThreadPool(threads, task -> {
                Thread thread = new Thread(task, "scan-probe-worker");
                thread.setDaemon(true);
                return thread;
            });
            this.graceMillis = graceMillis;
            this.stopMillis = stopMillis;
            UNFINISHED.add(this);
        }

        /** Whether any worker thread of this run may still be running. */
        static boolean unfinished() {
            UNFINISHED.removeIf(workers -> workers.pool.isTerminated());
            return !UNFINISHED.isEmpty();
        }

        synchronized <T> Future<T> submit(Callable<T> task) {
            Future<T> future = pool.submit(task);
            tasks.add(future);
            return future;
        }

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
                        + running + " task(s) unfinished): cleanup is not validated; fixtures and connections they may use "
                        + "are left to the harness"));
            }
            if (interrupted) Thread.currentThread().interrupt();
            if (failure != null) throw rethrow(failure);
        }
    }

    /** A lock holder on its own verified-release connection: its transaction keeps the row locks until a confirmed
     * COMMIT or ROLLBACK. Closing it without one rolls back (or aborts), never commits, and confirms on the server that
     * its session is gone; a failed release is reported and can be retried by closing again. */
    private static final class Holder implements AutoCloseable {
        private final Owned owned;

        Holder(Db db) throws Exception {
            Owned opened = Owned.open(db, "lock holder");
            try {
                opened.begin();
            } catch (Throwable failure) {
                try {
                    opened.close();
                } catch (Throwable cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
            owned = opened;
        }

        long connectionId() { return owned.id(); }

        void lockAudit(long organization, long audit) throws SQLException {
            assertEquals(Long.toString(audit), scalar(owned.connection(), "SELECT id FROM audits WHERE id=" + audit
                    + " AND organization_id=" + organization + " FOR UPDATE"));
        }

        int update(String sql) throws SQLException {
            try (Statement s = owned.connection().createStatement()) { return s.executeUpdate(sql); }
        }

        void commit() throws Exception {
            owned.commit();
            owned.close();
        }

        void rollback() throws Exception {
            owned.rollback();
            owned.close();
        }

        @Override public void close() throws Exception {
            owned.close();
        }
    }

    /** Coordination only. Waits (bounded) until `expected` other connections of this probe, not `blocker`, have the
     * named statement in flight at the server: the same connection seen on two polls with a growing TIME_MS, so the
     * statement reached the engine and is still executing. Only sessions the probe opened count (each is registered
     * before it sends any statement); others of the same user are ignored and only reported. It does not show which
     * lock the statement waits for, nor that `blocker` holds it: TIME_MS measures the command, not a lock wait, and
     * INNODB_LOCK_WAITS would need the PROCESS privilege. That the service's lock statement waits on a row held by
     * another transaction is proven by the directed ER_LOCK_WAIT_TIMEOUT scenarios; each scenario below also proves its
     * ordering through durable outcomes. */
    private static Set<Long> awaitInFlight(Race race, long blocker, int expected, String... fragments) throws SQLException {
        StringBuilder query = new StringBuilder("SELECT ID, TIME_MS FROM information_schema.PROCESSLIST WHERE DB=DATABASE() "
                + "AND COMMAND='Query' AND ID NOT IN (" + blocker + ",CONNECTION_ID())");
        for (String fragment : fragments) {
            query.append(" AND INFO LIKE '%").append(fragment).append("%'");
        }
        Map<Long, Double> first = new HashMap<>();
        Set<Long> confirmed = new TreeSet<>();
        Set<Long> foreign = new TreeSet<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (true) {
            Map<Long, Double> seen = new HashMap<>();
            try (Statement s = race.observer().createStatement(); ResultSet rows = s.executeQuery(query.toString())) {
                while (rows.next()) seen.put(rows.getLong(1), rows.getDouble(2));
            }
            Set<Long> mine = race.db().sessions().ids();
            Map<Long, Double> now = new HashMap<>();
            for (var statement : seen.entrySet()) {
                if (mine.contains(statement.getKey())) {
                    now.put(statement.getKey(), statement.getValue());
                } else {
                    foreign.add(statement.getKey());
                }
            }
            assertTrue(now.size() <= expected, "More statements in flight than participants: " + now + "; not the probe's: " + foreign);
            first.keySet().retainAll(now.keySet());
            confirmed.retainAll(now.keySet());
            for (var statement : now.entrySet()) {
                Double earlier = first.putIfAbsent(statement.getKey(), statement.getValue());
                if (earlier != null && statement.getValue() > earlier) confirmed.add(statement.getKey());
            }
            if (confirmed.size() == expected) return confirmed;
            assertTrue(System.nanoTime() < deadline, "No statement observed in flight for " + Arrays.toString(fragments) + ": " + now);
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
    }

    /** Shared state of the concurrency scenarios; each scenario has its own Audit and time window. */
    private record Race(Connection observer, Db db, SessionFactory f, AuditScanService service, MutableClock clock, long org, long site) {
        Instant at(int scenario, int second) { return BASE.plusSeconds(100L * scenario + second); }

        AuditScanService.AuditCreation audit(int scenario, long actor) {
            clock.set(at(scenario, 0));
            return service.createAudit(org, site, actor);
        }
    }

    private static <T> T result(Future<T> future) throws Exception {
        return future.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    /** Two retries start while another transaction holds the Audit row; both are seen in flight at the lock statement
     * (coordination) and nothing is written. After the holder's COMMIT exactly one creates attempt 2 and the other is
     * rejected because it sees that attempt active: it read the attempts only after the first one committed. Without
     * serialization both would read FAILED and race on the UNIQUE keys, which the probe rejects as unexpected. */
    private static void twoRetriesWaitOnTheAuditLock(Race r, long actor) throws Exception {
        var audit = r.audit(1, actor);
        transition(r.f(), audit.scanId(), s -> { s.start(r.at(1, 1)); s.fail(r.at(1, 2)); });
        r.clock().set(r.at(1, 3));
        List<Outcome> outcomes;
        try (Workers workers = new Workers(2); Holder holder = new Holder(r.db())) {
            holder.lockAudit(r.org(), audit.auditId());
            Future<Outcome> first = workers.submit(() -> attempt(() -> r.service().retryScan(r.org(), audit.auditId(), actor)));
            awaitInFlight(r, holder.connectionId(), 1, "from audits", "for update");
            Future<Outcome> second = workers.submit(() -> attempt(() -> r.service().retryScan(r.org(), audit.auditId(), actor)));
            awaitInFlight(r, holder.connectionId(), 2, "from audits", "for update");
            assertEquals("1:FAILED", attempts(r.observer(), audit.auditId()), "Nothing is written while both are in flight");
            holder.commit();
            outcomes = List.of(result(first), result(second));
        }
        assertEquals(1, outcomes.stream().filter(o -> o.result() == Result.OK).count(), outcomes.toString());
        assertEquals(1, outcomes.stream().filter(o -> o.result() == Result.REJECTED && o.detail().contains("active Scan")).count(), outcomes.toString());
        assertEquals("1:FAILED,2:PENDING", attempts(r.observer(), audit.auditId()));
    }

    /** The retry is in flight at its lock statement before another transaction cancels the RUNNING Scan; it still sees
     * the cancellation and creates attempt 2, so it read the attempts after that COMMIT (RUNNING would be rejected). */
    private static void retryReadsTheCancellationCommittedWhileItWaited(Race r, long actor) throws Exception {
        var audit = r.audit(2, actor);
        transition(r.f(), audit.scanId(), s -> s.start(r.at(2, 1)));
        r.clock().set(r.at(2, 3));
        Outcome outcome;
        try (Workers workers = new Workers(1); Holder holder = new Holder(r.db())) {
            holder.lockAudit(r.org(), audit.auditId());
            Future<Outcome> retry = workers.submit(() -> attempt(() -> r.service().retryScan(r.org(), audit.auditId(), actor)));
            awaitInFlight(r, holder.connectionId(), 1, "from audits", "for update");
            assertEquals(1, holder.update("UPDATE scans SET status='CANCELLED',finished_at=" + sqlTime(r.at(2, 2)) + ",updated_at="
                    + sqlTime(r.at(2, 2)) + ",row_version=row_version+1 WHERE id=" + audit.scanId() + " AND status='RUNNING'"));
            holder.commit();
            outcome = result(retry);
        }
        assertEquals(Result.OK, outcome.result(), outcome.toString());
        assertEquals("1:CANCELLED,2:PENDING", attempts(r.observer(), audit.auditId()));
    }

    /** The cancel is in flight at its lock statement before another transaction completes the Scan; it then sees
     * SUCCEEDED and is rejected without changing anything, so it loaded the Scan after that COMMIT. */
    private static void cancelSeesTheCompletionCommittedWhileItWaited(Race r, long actor) throws Exception {
        var audit = r.audit(3, actor);
        transition(r.f(), audit.scanId(), s -> s.start(r.at(3, 1)));
        r.clock().set(r.at(3, 3));
        Outcome outcome;
        try (Workers workers = new Workers(1); Holder holder = new Holder(r.db())) {
            holder.lockAudit(r.org(), audit.auditId());
            Future<Outcome> cancel = workers.submit(() -> attempt(() -> r.service().cancelScan(r.org(), audit.scanId(), actor)));
            awaitInFlight(r, holder.connectionId(), 1, "from audits", "for update");
            assertEquals(1, holder.update("UPDATE scans SET status='SUCCEEDED',finished_at=" + sqlTime(r.at(3, 2)) + ",updated_at="
                    + sqlTime(r.at(3, 2)) + ",row_version=row_version+1 WHERE id=" + audit.scanId() + " AND status='RUNNING'"));
            holder.commit();
            outcome = result(cancel);
        }
        assertEquals(Result.REJECTED, outcome.result(), outcome.toString());
        assertTrue(outcome.detail().contains("SUCCEEDED"), outcome.toString());
        assertEquals("SUCCEEDED|S|F|1|NULL|2", row(r.observer(), audit.scanId()));
    }

    /** Cancel and retry are both in flight at the lock statement while another transaction holds the Audit row; the
     * order in which InnoDB grants it is not ours to choose, so the outcome must match exactly one serial order.
     * Returns the order observed (information only, never counted as evidence). */
    private static String retryAndCancelBothWaitOnTheAuditLock(Race r, long actor) throws Exception {
        var audit = r.audit(4, actor);
        transition(r.f(), audit.scanId(), s -> s.start(r.at(4, 1)));
        r.clock().set(r.at(4, 3));
        Outcome cancelled;
        Outcome retried;
        try (Workers workers = new Workers(2); Holder holder = new Holder(r.db())) {
            holder.lockAudit(r.org(), audit.auditId());
            Future<Outcome> cancel = workers.submit(() -> attempt(() -> r.service().cancelScan(r.org(), audit.scanId(), actor)));
            awaitInFlight(r, holder.connectionId(), 1, "from audits", "for update");
            Future<Outcome> retry = workers.submit(() -> attempt(() -> r.service().retryScan(r.org(), audit.auditId(), actor)));
            awaitInFlight(r, holder.connectionId(), 2, "from audits", "for update");
            holder.commit();
            cancelled = result(cancel);
            retried = result(retry);
        }
        assertEquals(Result.OK, cancelled.result(), "A RUNNING Scan is cancellable in either order: " + cancelled);
        if (retried.result() == Result.OK) {
            assertEquals("1:CANCELLED,2:PENDING", attempts(r.observer(), audit.auditId()), "cancel then retry");
            return "cancel->retry";
        }
        assertEquals(Result.REJECTED, retried.result(), retried.toString());
        assertTrue(retried.detail().contains("active Scan"), retried.toString());
        assertEquals("1:CANCELLED", attempts(r.observer(), audit.auditId()), "retry (rejected: RUNNING active) then cancel");
        return "retry->cancel";
    }

    /** A worker's start is flushed but uncommitted; the cancel, which read the committed PENDING version, has its UPDATE
     * of that Scan row in flight (coordination). When the start commits, the cancel loses the version check: CONFLICT
     * and the durable RUNNING row show its UPDATE was evaluated against the committed start, not the version it read. */
    private static void cancelWaitsOnTheStartAndLosesTheVersionCheck(Race r, long actor) throws Exception {
        var audit = r.audit(5, actor);
        r.clock().set(r.at(5, 3));
        AtomicReference<Future<Outcome>> cancel = new AtomicReference<>();
        Outcome outcome;
        try (Workers workers = new Workers(1)) {
            inSession(r.f(), true, worker -> {
                worker.find(Scan.class, audit.scanId()).start(r.at(5, 1));
                worker.flush(); // The UPDATE now holds the Scan row until this transaction ends.
                long workerConnection = worker.doReturningWork(ScanMariaDbProbe::connectionId);
                cancel.set(workers.submit(() -> attempt(() -> r.service().cancelScan(r.org(), audit.scanId(), actor))));
                awaitInFlight(r, workerConnection, 1, "update scans", "row_version");
                assertEquals("PENDING|-|-|1|" + audit.auditId() + "|0", row(r.observer(), audit.scanId()), "The start is not committed yet");
            });
            outcome = result(cancel.get());
        }
        assertEquals(Result.CONFLICT, outcome.result(), outcome.toString());
        assertEquals("RUNNING|S|-|1|" + audit.auditId() + "|1", row(r.observer(), audit.scanId()));
    }

    /** Serial: the cancel commits while a worker holds a stale PENDING copy; the worker's start then loses. */
    private static void staleStartLosesToACommittedCancel(Race r, long actor) throws Exception {
        var audit = r.audit(6, actor);
        r.clock().set(r.at(6, 3));
        inSession(r.f(), false, worker -> {
            Scan stale = worker.find(Scan.class, audit.scanId());
            assertEquals(ScanStatus.CANCELLED, r.service().cancelScan(r.org(), audit.scanId(), actor).status());
            stale.start(r.at(6, 4));
            assertConflict(assertThrows(RuntimeException.class, worker::flush));
        });
        assertEquals("CANCELLED|-|F|1|NULL|1", row(r.observer(), audit.scanId()));
    }

    /** Serial: a committed start followed by a cancel. */
    private static void cancelAfterACommittedStart(Race r, long actor) throws Exception {
        var audit = r.audit(7, actor);
        transition(r.f(), audit.scanId(), s -> s.start(r.at(7, 1)));
        r.clock().set(r.at(7, 3));
        assertEquals(ScanStatus.CANCELLED, r.service().cancelScan(r.org(), audit.scanId(), actor).status());
        assertEquals("CANCELLED|S|F|1|NULL|2", row(r.observer(), audit.scanId()));
    }

    /** Two sessions with the same Scan version: the second one to write always loses. */
    private static void sameVersionSessionsConflict(Race r, long actor) throws Exception {
        var audit = r.audit(8, actor);
        inSession(r.f(), false, second -> {
            Scan b = second.find(Scan.class, audit.scanId());
            inSession(r.f(), true, first -> first.find(Scan.class, audit.scanId()).start(r.at(8, 1)));
            b.start(r.at(8, 1));
            assertConflict(assertThrows(RuntimeException.class, second::flush));
        });
        assertEquals("RUNNING|S|-|1|" + audit.auditId() + "|1", row(r.observer(), audit.scanId()));
    }

    private static String revoke(long organization, long user) {
        return "UPDATE organization_memberships SET status='REVOKED',updated_at='2024-04-01 00:00:00.000000',row_version=row_version+1 "
                + "WHERE organization_id=" + organization + " AND user_id=" + user + " AND status='ACTIVE'";
    }

    private static String membership(Race r, long user) throws SQLException {
        return scalar(r.observer(), "SELECT status FROM organization_memberships WHERE organization_id=" + r.org() + " AND user_id=" + user);
    }

    /** The actor's lock statement is in flight, so its first authorization already passed with the Membership ACTIVE;
     * only then is the Membership revoked and committed. NOT_FOUND can therefore come only from the authorization
     * repeated after the lock: a call that had not waited would have found the Membership still ACTIVE and succeeded,
     * which this scenario rejects. Nothing is written. */
    private static void retryRevalidatesAuthorizationAfterWaiting(Race r, long owner, long actor) throws Exception {
        var audit = r.audit(9, owner);
        transition(r.f(), audit.scanId(), s -> { s.start(r.at(9, 1)); s.fail(r.at(9, 2)); });
        r.clock().set(r.at(9, 3));
        Outcome outcome;
        try (Workers workers = new Workers(1); Holder holder = new Holder(r.db())) {
            holder.lockAudit(r.org(), audit.auditId());
            Future<Outcome> retry = workers.submit(() -> attempt(() -> r.service().retryScan(r.org(), audit.auditId(), actor)));
            awaitInFlight(r, holder.connectionId(), 1, "from audits", "for update");
            assertEquals("ACTIVE", membership(r, actor), "Still ACTIVE when the lock statement is in flight");
            assertEquals(1, holder.update(revoke(r.org(), actor)));
            holder.commit();
            outcome = result(retry);
        }
        assertEquals(Result.NOT_FOUND, outcome.result(), outcome.toString());
        assertEquals("1:FAILED", attempts(r.observer(), audit.auditId()));
        assertEquals("REVOKED", membership(r, actor));
    }

    private static void cancelRevalidatesAuthorizationAfterWaiting(Race r, long owner, long actor) throws Exception {
        var audit = r.audit(10, owner);
        transition(r.f(), audit.scanId(), s -> s.start(r.at(10, 1)));
        r.clock().set(r.at(10, 3));
        Outcome outcome;
        try (Workers workers = new Workers(1); Holder holder = new Holder(r.db())) {
            holder.lockAudit(r.org(), audit.auditId());
            Future<Outcome> cancel = workers.submit(() -> attempt(() -> r.service().cancelScan(r.org(), audit.scanId(), actor)));
            awaitInFlight(r, holder.connectionId(), 1, "from audits", "for update");
            assertEquals("ACTIVE", membership(r, actor), "Still ACTIVE when the lock statement is in flight");
            assertEquals(1, holder.update(revoke(r.org(), actor)));
            holder.commit();
            outcome = result(cancel);
        }
        assertEquals(Result.NOT_FOUND, outcome.result(), outcome.toString());
        assertEquals("RUNNING|S|-|1|" + audit.auditId() + "|1", row(r.observer(), audit.scanId()));
        assertEquals("REVOKED", membership(r, actor));
    }

    /** Hibernate 6.4.1 keeps the statement of a query that failed while executing only in its message.
     * DeferredResultSetAccess calls SqlExceptionHelper.convert(e, "JDBC exception executing SQL [" + sql + "]"); that
     * overload sets getSQL() to "n/a" and hands message + " [" + e.getMessage() + "]" to the dialect's converter, so the
     * JDBCException's getErrorMessage() is exactly
     * "JDBC exception executing SQL [" + sql + "] [" + getSQLException().getMessage() + "]". */
    private static final String EXECUTING_SQL_PREFIX = "JDBC exception executing SQL [";
    /** The SQL Hibernate 6.4.1 renders with MariaDBDialect (11.2) for the service's lockAudit query, captured offline:
     * select a1_0.id,a1_0.created_at,...,a1_0.updated_at from audits a1_0 where a1_0.id=? and a1_0.organization_id=?
     * for update. Matched on its normalized form, so only case, whitespace and the generated alias may vary; another
     * table, a join, another predicate or a missing FOR UPDATE is not the Audit lock. */
    private static final Pattern AUDIT_LOCK_SQL = Pattern.compile(
            "select (\\w+)\\.\\w+(?:,\\1\\.\\w+)* from audits \\1 where \\1\\.id=\\? and \\1\\.organization_id=\\? for update");

    private record LockStatement(String sql, String source) { }

    /** A JDBCException message read as Hibernate's executing-SQL form: claimed (it starts with the fixed prefix), and
     * then either the statement or the reason it does not match the form exactly. */
    private record ExecutingSql(boolean claimed, String sql, String problem) { }

    /** Separates the executing-SQL message by its exact structure, never by searching brackets: the fixed prefix, the
     * statement, and the diagnostic suffix, which must be "] [" + the message of this JDBCException's own SQLException
     * + "]". The split is therefore unique; a message that claims the form without matching it is malformed. */
    private static ExecutingSql executingSql(JDBCException jdbc) {
        String message = jdbc.getErrorMessage();
        if (message == null || !message.startsWith(EXECUTING_SQL_PREFIX)) return new ExecutingSql(false, null, null);
        SQLException cause = jdbc.getSQLException();
        if (cause == null) return new ExecutingSql(true, null, "no SQLException to check its diagnostic suffix against");
        String suffix = "] [" + cause.getMessage() + "]";
        if (message.length() < EXECUTING_SQL_PREFIX.length() + suffix.length() || !message.endsWith(suffix)) {
            return new ExecutingSql(true, null, "it does not end with \"] [<message of its own SQLException>]\"");
        }
        String sql = message.substring(EXECUTING_SQL_PREFIX.length(), message.length() - suffix.length());
        if (sql.isBlank()) return new ExecutingSql(true, null, "empty statement");
        return new ExecutingSql(true, sql, null);
    }

    /** Lower case, single spaces, no spaces around commas or equals signs. */
    private static String normalizedSql(String sql) {
        return sql.strip().replaceAll("\\s+", " ").replaceAll(" ?([,=]) ?", "$1").toLowerCase(Locale.ROOT);
    }

    /** For diagnostics only: quoted literals masked and length bounded. */
    private static String sanitized(String text) {
        if (text == null) return "null";
        String masked = text.replaceAll("'(?:[^'\\\\]|\\\\.)*'", "'?'");
        return masked.length() > 400 ? masked.substring(0, 400) + "..." : masked;
    }

    /** Sanitized description of an exception chain: types, MariaDB errno, SQLSTATE and message, and for Hibernate's
     * JDBCException its getSQL() value and whether its message has the executing-SQL form. */
    private static String chainDiagnostics(Throwable error) {
        StringJoiner text = new StringJoiner(" <- ", "chain: ", "");
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = error; t != null && seen.add(t); t = t.getCause()) {
            StringBuilder item = new StringBuilder(t.getClass().getName());
            if (t instanceof JDBCException jdbc) {
                ExecutingSql form = executingSql(jdbc);
                item.append(" [getSQL=").append(sanitized(jdbc.getSQL())).append(", message form=")
                        .append(!form.claimed() ? "other" : form.sql() != null ? "executing-SQL" : "malformed executing-SQL (" + form.problem() + ")")
                        .append(']');
            }
            if (t instanceof SQLException sql) {
                item.append(" [errno=").append(sql.getErrorCode()).append(", SQLSTATE=").append(sql.getSQLState())
                        .append(", message=").append(sanitized(sql.getMessage())).append(", next=").append(sql.getNextException() != null).append(']');
            }
            text.add(item);
        }
        return text.toString();
    }

    /** The statement behind the timed-out operation, taken only from Hibernate's own representations: getSQL() when it
     * holds a statement; when it is null, blank or "n/a", the exact executing-SQL form of that JDBCException's own
     * message (executingSql). A JDBCException with neither, a message that claims the executing-SQL form without
     * matching it (even next to a valid getSQL()), two representations that disagree, or a statement that is not the
     * service's Audit lock fails; no statement is ever assumed. */
    private static LockStatement lockStatementOf(Throwable error, String diagnostics) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<LockStatement> found = new ArrayList<>();
        for (Throwable t = error; t != null && seen.add(t); t = t.getCause()) {
            if (!(t instanceof JDBCException jdbc)) continue;
            String explicit = jdbc.getSQL();
            boolean hasExplicit = explicit != null && !explicit.isBlank() && !"n/a".equals(explicit.strip());
            ExecutingSql structured = executingSql(jdbc);
            if (structured.claimed() && structured.sql() == null) {
                fail("Malformed executing-SQL message in " + jdbc.getClass().getName() + ": " + structured.problem() + "; " + diagnostics);
            }
            String fromMessage = structured.sql();
            if (hasExplicit && fromMessage != null && !normalizedSql(explicit).equals(normalizedSql(fromMessage))) {
                fail("Contradictory statements in " + jdbc.getClass().getName() + ": getSQL and message differ; " + diagnostics);
            }
            if (hasExplicit) {
                found.add(new LockStatement(explicit, "getSQL"));
            } else if (fromMessage != null) {
                found.add(new LockStatement(fromMessage, "message"));
            } else {
                fail("No identifiable statement in " + jdbc.getClass().getName() + " (getSQL=" + sanitized(explicit)
                        + ", message not in Hibernate's executing-SQL form); " + diagnostics);
            }
        }
        if (found.isEmpty()) fail("Hibernate reported no JDBCException for the timed-out operation; " + diagnostics);
        String statement = normalizedSql(found.get(0).sql());
        for (LockStatement other : found) {
            if (!statement.equals(normalizedSql(other.sql()))) fail("Contradictory statements across the exception chain; " + diagnostics);
        }
        if (!AUDIT_LOCK_SQL.matcher(statement).matches()) {
            fail("The wait must be the Audit lock statement (tenant-scoped SELECT FROM audits ... FOR UPDATE), got ["
                    + sanitized(found.get(0).sql()) + "] from " + found.get(0).source() + "; " + diagnostics);
        }
        return found.get(0);
    }

    /** The single failure accepted from a directed lock-wait scenario: InnoDB's ER_LOCK_WAIT_TIMEOUT (errno 1205,
     * SQLSTATE HY000) raised on the service's PESSIMISTIC_WRITE statement for the audits row. Every SQLException behind
     * the failure (causes and next exceptions) must be that error and the statement Hibernate reports must be the Audit
     * lock (lockStatementOf); authorization, state, transaction, max_statement_time (1969), deadlock (1213) or connection
     * failures are rejected. Every rejection carries the sanitized chain. */
    private static void assertLockWaitTimeout(Throwable error) {
        String diagnostics = chainDiagnostics(error);
        assertFalse(error instanceof TransactionException, "Transaction infrastructure failure instead of the lock wait timeout; " + diagnostics);
        assertFalse(error instanceof EntityNotFoundException || error instanceof IllegalStateException, "Rejected before locking; " + diagnostics);
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<SQLException> chain = new ArrayList<>();
        for (Throwable t = error; t != null && seen.add(t); t = t.getCause()) {
            if (t instanceof SQLException sql) {
                for (SQLException s = sql; s != null; s = s.getNextException()) {
                    if (!chain.contains(s)) chain.add(s);
                }
            }
        }
        assertFalse(chain.isEmpty(), "No SQLException behind the failure; " + diagnostics);
        for (SQLException s : chain) {
            assertEquals(1205, s.getErrorCode(), "MariaDB errno; " + diagnostics);
            assertEquals("HY000", s.getSQLState(), "SQLSTATE; " + diagnostics);
            assertTrue(String.valueOf(s.getMessage()).contains("Lock wait timeout"), "Lock wait timeout message; " + diagnostics);
        }
        LockStatement statement = lockStatementOf(error, diagnostics);
        System.out.println("SCAN LOCK WAIT EVIDENCE: errno 1205, SQLSTATE HY000 on the Audit lock statement (from "
                + statement.source() + "); " + diagnostics);
    }

    /** Isolation precondition of the directed lock-wait scenarios, checked read-only and bounded: besides the observer,
     * the holder must be the only session of the probe's user that the server lists, so that no other transaction can
     * own a competing lock. Nothing is ever closed to obtain it: a session the probe did not open, or one of its own
     * still open, fails the scenario explicitly and is left untouched. */
    private static void onlyHolderBesidesObserver(Connection c, Db db, long holder) throws SQLException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        Set<Long> visible = db.server().visible(c);
        while (!visible.equals(Set.of(holder)) && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            visible = db.server().visible(c);
        }
        if (visible.equals(Set.of(holder))) return;
        Set<Long> mine = db.sessions().ids();
        Set<Long> unknown = new TreeSet<>(visible);
        unknown.removeAll(mine);
        Set<Long> own = new TreeSet<>(visible);
        own.retainAll(mine);
        own.remove(holder);
        fail("Isolation precondition not met, nothing was closed: holder " + holder + (visible.contains(holder) ? " listed" : " not listed")
                + "; sessions not opened by the probe " + unknown + "; other sessions of the probe " + db.sessions().describe(own));
    }

    /** Directed proof on the real service, independent of PROCESSLIST: while another transaction holds the Audit row
     * with SELECT ... FOR UPDATE, uncommitted, the service's PESSIMISTIC_WRITE on that row waits until InnoDB gives up
     * with ER_LOCK_WAIT_TIMEOUT (1205). The short innodb_lock_wait_timeout is a session setting of this scenario's own
     * connections only (its own SessionFactory), well below the service's 10 s timeout; the global value, the observer
     * and the holder keep the server default. Before the call, the holder is the only session of the probe's user the
     * server lists besides the observer (checked read-only; any other session fails the scenario and is left
     * untouched), so it is the only possible owner of the lock. The timed-out call writes nothing; after the holder
     * rolls back, the same call by the same actor succeeds and the observer sees the durable result. */
    private static void serviceWaitsOnTheHeldAuditRowUntilLockWaitTimeout(Connection c, Db db, long org, long site, long actor,
                                                                          boolean retry) throws Exception {
        Instant base = BASE.plusSeconds(100L * (retry ? 12 : 11));
        String global = scalar(c, "SELECT @@GLOBAL.innodb_lock_wait_timeout");
        String observerTimeout = scalar(c, "SELECT @@SESSION.innodb_lock_wait_timeout");
        assertTrue(Integer.parseInt(observerTimeout) > LOCK_WAIT_SECONDS, "The server default must exceed the scenario timeout");
        try (Hib lockWait = new Hib(db, "SET SESSION innodb_lock_wait_timeout=" + LOCK_WAIT_SECONDS)) {
            SessionFactory f = lockWait.f();
            Stack stack = stack(f);
            inSession(f, false, s -> assertEquals(String.valueOf(LOCK_WAIT_SECONDS),
                    s.doReturningWork(connection -> scalar(connection, "SELECT @@SESSION.innodb_lock_wait_timeout")),
                    "Connections of this scenario carry the short timeout"));
            stack.clock().set(base);
            var audit = stack.service().createAudit(org, site, actor);
            if (retry) {
                transition(f, audit.scanId(), s -> { s.start(base.plusSeconds(1)); s.fail(base.plusSeconds(2)); });
            } else {
                transition(f, audit.scanId(), s -> s.start(base.plusSeconds(1)));
            }
            stack.clock().set(base.plusSeconds(3));
            String before = retry ? attempts(c, audit.auditId()) : row(c, audit.scanId());
            try (Holder holder = new Holder(db)) {
                holder.lockAudit(org, audit.auditId());
                onlyHolderBesidesObserver(c, db, holder.connectionId());
                RuntimeException timedOut = assertThrows(RuntimeException.class, () -> {
                    if (retry) {
                        stack.service().retryScan(org, audit.auditId(), actor);
                    } else {
                        stack.service().cancelScan(org, audit.scanId(), actor);
                    }
                });
                assertLockWaitTimeout(timedOut);
                assertEquals(before, retry ? attempts(c, audit.auditId()) : row(c, audit.scanId()), "The timed-out call wrote nothing");
                holder.rollback();
            }
            if (retry) {
                assertEquals(2, stack.service().retryScan(org, audit.auditId(), actor).attemptNumber());
                assertEquals("1:FAILED,2:PENDING", attempts(c, audit.auditId()));
            } else {
                assertEquals(ScanStatus.CANCELLED, stack.service().cancelScan(org, audit.scanId(), actor).status());
                assertEquals("CANCELLED|S|F|1|NULL|2", row(c, audit.scanId()));
            }
        }
        assertEquals(global, scalar(c, "SELECT @@GLOBAL.innodb_lock_wait_timeout"), "Global innodb_lock_wait_timeout unchanged");
        assertEquals(observerTimeout, scalar(c, "SELECT @@SESSION.innodb_lock_wait_timeout"), "The observer kept its own value");
    }

    private static void concurrency(Connection c, Db db) throws Exception {
        String[] order = new String[1];
        owned(c, db, true, fx -> {
            long owner = fx.user(c, 801201L, "CLIENTE", true);
            long retryActor = fx.user(c, 801202L, "CLIENTE", true);
            long cancelActor = fx.user(c, 801203L, "CLIENTE", true);
            long org = fx.organization(c, "Scan concurrency");
            for (long id : new long[]{owner, retryActor, cancelActor}) member(c, org, id, "ACTIVE");
            long site = site(c, org, owner, "ACTIVE");
            try (Hib hib = new Hib(db, null)) {
                SessionFactory f = hib.f();
                Stack stack = stack(f);
                Race r = new Race(c, db, f, stack.service(), stack.clock(), org, site);
                twoRetriesWaitOnTheAuditLock(r, owner);
                retryReadsTheCancellationCommittedWhileItWaited(r, owner);
                cancelSeesTheCompletionCommittedWhileItWaited(r, owner);
                order[0] = retryAndCancelBothWaitOnTheAuditLock(r, owner);
                cancelWaitsOnTheStartAndLosesTheVersionCheck(r, owner);
                staleStartLosesToACommittedCancel(r, owner);
                cancelAfterACommittedStart(r, owner);
                sameVersionSessionsConflict(r, owner);
                retryRevalidatesAuthorizationAfterWaiting(r, owner, retryActor);
                cancelRevalidatesAuthorizationAfterWaiting(r, owner, cancelActor);
            }
            serviceWaitsOnTheHeldAuditRowUntilLockWaitTimeout(c, db, org, site, owner, false);
            serviceWaitsOnTheHeldAuditRowUntilLockWaitTimeout(c, db, org, site, owner, true);
            assertEquals("0", scalar(c, "SELECT COUNT(*) FROM (SELECT audit_id FROM scans WHERE status IN ('PENDING','RUNNING') "
                    + "GROUP BY audit_id HAVING COUNT(*) > 1) t"), "Never two active Scans for one Audit");
            assertEquals("0", scalar(c, ORPHANS));
        });
        System.out.println("SCAN CONCURRENCY OBSERVED: retry+cancel in flight together resolved as " + order[0] + " (information only)");
        System.out.println("SCAN CONCURRENCY PASS: directed lock waits -> cancel and retry fail with ER_LOCK_WAIT_TIMEOUT 1205 on the "
                + "held Audit row, write nothing, then succeed once it is released; PROCESSLIST used only to coordinate; two retries -> "
                + "exactly 1 attempt; retry reads a cancellation committed meanwhile; cancel sees SUCCEEDED; retry+cancel -> one serial "
                + "order; cancel loses the version check to an uncommitted start; stale start loses; same-version sessions conflict; "
                + "Membership revoked after the lock statement started -> retry and cancel not found; durable state checked; releases "
                + "verified; owned fixtures removed");
    }

    // ---------------------------------------------------------------- upgrade

    /** Ownership: the schema is created empty for this mode by the harness and dropped with its container; the rows
     * below are also removed and verified here, on success and on failure, once their tables exist. */
    private static void upgrade(Connection c, Db db) throws Exception {
        assertEquals("0", scalar(c, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()"));
        owned(c, db, false, fx -> {
            Flyway.configure().dataSource(flywaySource(db)).target("7").load().migrate();
            assertEquals("1,2,3,4,5,6,7", scalar(c, "SELECT GROUP_CONCAT(version ORDER BY installed_rank) FROM flyway_schema_history"));
            assertEquals("0", scalar(c, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='scans'"));
            fx.user(c, 801301L, "CLIENTE", true);
            fx.user(c, 801302L, "CLIENTE", true);
            execute(c, "INSERT INTO organizations(name,personal_owner_user_id,created_at,updated_at,row_version) VALUES "
                    + "('Populated V7',801301,'2024-02-29 23:58:57.987654'," + T0 + ",4),('Shared V7',NULL," + T0 + "," + T0 + ",0)");
            long populated = fx.adopt(Long.parseLong(scalar(c, "SELECT id FROM organizations WHERE personal_owner_user_id=801301")));
            long shared = fx.adopt(Long.parseLong(scalar(c, "SELECT id FROM organizations WHERE personal_owner_user_id IS NULL")));
            execute(c, "INSERT INTO organization_memberships(organization_id,user_id,role,status,created_at,updated_at,row_version) VALUES "
                    + "(" + populated + ",801301,'OWNER','ACTIVE'," + T0 + "," + T0 + ",2),(" + shared + ",801302,'OWNER','REVOKED'," + T0 + "," + T0 + ",7)");
            execute(c, "INSERT INTO sites(organization_id,name,base_url,status,created_by_user_id,created_at,updated_at,row_version) VALUES "
                    + "(" + populated + ",'Populated site','https://populated.invalid/','ACTIVE',801301," + T0 + "," + T0 + ",3),"
                    + "(" + shared + ",'Archived site','https://populated.invalid/','ARCHIVED',801302," + T0 + "," + T0 + ",5)");
            assertEquals("0", scalar(c, "SELECT COUNT(*) FROM audits"), "V7 never holds Audits (no orphan Audit before V8)");
            var legacy = snapshot(c, LEGACY);
            var previous = snapshot(c, "organizations", "organization_memberships", "sites", "audits");
            var oldHistory = snapshot(c, "flyway_schema_history").get("flyway_schema_history");
            Flyway flyway = Flyway.configure().dataSource(flywaySource(db)).target("8").load();
            assertEquals(1, flyway.migrate().migrationsExecuted);
            assertEquals("1,2,3,4,5,6,7,8", scalar(c, "SELECT GROUP_CONCAT(version ORDER BY installed_rank) FROM flyway_schema_history"));
            assertEquals("8|create scans|SQL|1", scalar(c, "SELECT CONCAT(version,'|',description,'|',type,'|',success) "
                    + "FROM flyway_schema_history WHERE version='8'"));
            var newHistory = snapshot(c, "flyway_schema_history").get("flyway_schema_history");
            assertEquals(oldHistory.size() + 1, newHistory.size());
            assertTrue(newHistory.containsAll(oldHistory), "Historical Flyway rows changed");
            assertEquals("0|0", scalar(c, "SELECT CONCAT((SELECT COUNT(*) FROM scans),'|',(SELECT COUNT(*) FROM audits))"), "No backfill");
            assertEquals(legacy, snapshot(c, LEGACY));
            assertEquals(previous, snapshot(c, "organizations", "organization_memberships", "sites", "audits"));
            var preserved = snapshot(c, "organizations", "organization_memberships", "sites", "audits", "scans", "flyway_schema_history");
            assertEquals(0, flyway.migrate().migrationsExecuted);
            assertEquals(preserved, snapshot(c, "organizations", "organization_memberships", "sites", "audits", "scans", "flyway_schema_history"));
            try (Hib validate = new Hib(db, null)) { /* Hibernate validate on the real V8 schema; releases verified. */ }
        });
        System.out.println("SCAN UPGRADE PASS: populated V7 -> V8; one migration; scans/audits empty (no backfill); "
                + "organizations/memberships/sites identical; exact history 1,2,3,4,5,6,7,8; repeat migrate=0; Hibernate validate; "
                + "owned rows removed and verified");
    }

    // ---------------------------------------------------------------- seed

    /** Ownership by precondition: audits and scans are empty when the seed starts, so on failure every Audit and Scan
     * of the sentinel organization is this seed's and is removed in one transaction. On success the sentinels stay. */
    private static void seed(Connection c, Db db) throws Exception {
        assertEquals("0|0", scalar(c, "SELECT CONCAT((SELECT COUNT(*) FROM audits),'|',(SELECT COUNT(*) FROM scans))"));
        assertEquals("1", scalar(c, "SELECT COUNT(*) FROM organizations"));
        long org = Long.parseLong(scalar(c, "SELECT id FROM organizations"));
        long site = Long.parseLong(scalar(c, "SELECT id FROM sites WHERE status='ACTIVE'"));
        try {
            try (Hib hib = new Hib(db, null)) {
                SessionFactory f = hib.f();
                Stack stack = stack(f);
                // The REVOKED owner sentinel (also the legacy ADMIN) is not authorized and writes nothing.
                assertThrows(EntityNotFoundException.class, () -> stack.service().createAudit(org, site, 900002L));
                var pending = stack.service().createAudit(org, site, 900001L);
                var retried = stack.service().createAudit(org, site, 900001L);
                transition(f, retried.scanId(), s -> { s.start(Instant.parse("2024-03-01T00:00:01Z")); s.fail(Instant.parse("2024-03-01T00:00:02Z")); });
                stack.clock().set(Instant.parse("2024-03-01T00:00:03Z"));
                var second = stack.service().retryScan(org, retried.auditId(), 900001L);
                assertEquals(2, second.attemptNumber());
                assertNotEquals(pending.auditId(), retried.auditId());
            }
            assertEquals("2|3|0|FAILED,PENDING,PENDING", scalar(c, "SELECT CONCAT((SELECT COUNT(*) FROM audits),'|',(SELECT COUNT(*) FROM scans),'|',("
                    + ORPHANS + "),'|',(SELECT GROUP_CONCAT(status ORDER BY status) FROM scans))"));
        } catch (Throwable failure) {
            try {
                transaction(db, true, tx -> {
                    execute(tx, "DELETE FROM scans WHERE organization_id=" + org);
                    execute(tx, "DELETE FROM audits WHERE organization_id=" + org);
                });
                assertEquals("0|0", scalar(c, "SELECT CONCAT((SELECT COUNT(*) FROM audits),'|',(SELECT COUNT(*) FROM scans))"),
                        "Seed cleanup left rows");
            } catch (Throwable cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        System.out.println("SCAN SENTINELS PASS: 2 Audits created by the service with their Scans (PENDING; FAILED + retry PENDING); "
                + "no Audit without Scan; UTC micros");
    }

    private static Map<String, List<String>> snapshot(Connection c, String... tables) throws SQLException {
        Map<String, List<String>> result = new TreeMap<>();
        for (String table : tables) {
            List<String> rows = new ArrayList<>();
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SHOW CREATE TABLE " + table)) {
                assertTrue(r.next());
                rows.add(r.getString(2).replaceAll("AUTO_INCREMENT=\\d+\\s*", ""));
            }
            String columns = scalar(c, "SELECT GROUP_CONCAT(CONCAT('`',COLUMN_NAME,'`') ORDER BY ORDINAL_POSITION) "
                    + "FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='" + table + "'");
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT JSON_ARRAY(" + columns + ") FROM " + table + " ORDER BY 1")) {
                while (r.next()) rows.add(r.getString(1));
            }
            result.put(table, rows);
        }
        return result;
    }
}
