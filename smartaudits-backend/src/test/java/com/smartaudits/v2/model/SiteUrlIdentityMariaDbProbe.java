package com.smartaudits.v2.model;

import com.smartaudits.model.*;
import com.smartaudits.v2.site.SiteUrlIdentity;
import org.flywaydb.core.Flyway;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V10 on real MariaDB 11.2 (docs/F4_1C_SITE_URL_IDENTITY.md, "Increment 1"). Explicit entry only:
 * deliberately outside Surefire's *Test patterns, run by scripts/Validate-SiteUrlIdentityV10.ps1,
 * which owns the container and the three databases named below and removes them afterwards.
 * Every SQL mutation is a synthetic fixture in those databases. The session sql_mode is read and
 * asserted, never changed.
 */
class SiteUrlIdentityMariaDbProbe {
    private static final String USER = "v10_identity_user";
    private static final String TABLE = "site_url_identities";
    private static final String UNIQUE = "uq_site_url_identities_org_origin";
    private static final String FK = "fk_site_url_identities_site";
    private static final String VERSIONS = "1,2,3,4,5,6,7,8,9,10";
    private static final Instant CREATED = Instant.parse("2026-11-24T10:00:00.123456789Z");

    private record Db(String name, String url, String password) { }

    private static String required(String name) {
        return Objects.requireNonNull(System.getenv(name), "Missing " + name);
    }

    private static Db db(String variable, String suffix) {
        String run = required("V10_RUN_ID");
        assertTrue(run.matches("[a-f0-9]{32}"));
        String name = required(variable);
        assertEquals("v10_identity_" + run + "_" + suffix, name);
        String port = required("V10_DB_PORT");
        assertTrue(port.matches("[0-9]{4,5}") && !port.equals("3306"), "owned ephemeral port only");
        return new Db(name, "jdbc:mariadb://127.0.0.1:" + port + "/" + name, required("V10_DB_PASSWORD"));
    }

    private static Connection connect(Db db) throws SQLException {
        Connection c = DriverManager.getConnection(db.url(), USER, db.password());
        assertTrue(c.getMetaData().getDatabaseProductVersion().startsWith("11.2."), c.getMetaData().getDatabaseProductVersion());
        assertEquals(db.name(), c.getCatalog());
        String mode = scalar(c, "SELECT @@SESSION.sql_mode");
        assertTrue(Arrays.asList(mode.split(",")).contains("STRICT_TRANS_TABLES")
                || Arrays.asList(mode.split(",")).contains("STRICT_ALL_TABLES"), "Strict SQL mode required: " + mode);
        return c;
    }

    // ---------------------------------------------------------------- fresh installation

    @Test void freshInstallationSchemaMappingAndConstraints() throws Exception {
        Db db = db("V10_DB_FRESH", "fresh");
        try (Connection c = connect(db)) {
            assertEquals("0", scalar(c, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()"));
            Flyway flyway = Flyway.configure().dataSource(db.url(), USER, db.password()).load();
            assertEquals(10, flyway.migrate().migrationsExecuted);
            assertEquals(VERSIONS, scalar(c, "SELECT GROUP_CONCAT(version ORDER BY installed_rank) FROM flyway_schema_history"));
            assertEquals("10", scalar(c, "SELECT COUNT(*) FROM flyway_schema_history WHERE type='SQL' AND success=1"));
            assertEquals("10|create site url identities|SQL|1|V10__create_site_url_identities.sql", scalar(c,
                    "SELECT CONCAT(version,'|',description,'|',type,'|',success,'|',script) FROM flyway_schema_history WHERE version='10'"));
            assertEquals(0, flyway.migrate().migrationsExecuted);
            assertTrue(flyway.validateWithResult().validationSuccessful, "Flyway validate V1-V10");
            schema(c);
            try (SessionFactory f = factory(db)) {
                constraints(c, f);
            }
        }
        System.out.println("V10 FRESH PASS: Flyway V1-V10 (10 executed, repeat 0, validate OK); exact schema; Hibernate validate; "
                + "JPA insert/read; byte-exact VARBINARY; org-scoped unique; composite FK; 4 CHECKs; immutability");
    }

    private static void schema(Connection c) throws SQLException {
        String table = "FROM information_schema.%s WHERE table_schema=DATABASE() AND table_name='" + TABLE + "'";
        String constraintTable = "FROM information_schema.%s WHERE constraint_schema=DATABASE() AND table_name='" + TABLE + "'";
        assertEquals("site_id:bigint(20):NO:NULL:,organization_id:bigint(20):NO:NULL:,canonical_origin:varbinary(261):NO:NULL:",
                scalar(c, "SELECT GROUP_CONCAT(CONCAT(column_name,':',column_type,':',is_nullable,':',IFNULL(column_default,'NULL'),':',extra) "
                        + "ORDER BY ordinal_position) " + table.formatted("columns")));
        // Binary: no character set, no collation, and the bound is in bytes.
        assertEquals("varbinary|NULL|NULL|261|261", scalar(c, "SELECT CONCAT(data_type,'|',IFNULL(character_set_name,'NULL'),'|',"
                + "IFNULL(collation_name,'NULL'),'|',character_maximum_length,'|',character_octet_length) "
                + table.formatted("columns") + " AND column_name='canonical_origin'"));
        assertEquals("InnoDB|utf8mb4_unicode_520_ci", scalar(c, "SELECT CONCAT(engine,'|',table_collation) " + table.formatted("tables")));
        assertEquals("3", scalar(c, "SELECT COUNT(DISTINCT index_name) " + table.formatted("statistics")));
        for (String[] index : List.of(new String[]{"PRIMARY", "0:site_id"},
                new String[]{UNIQUE, "0:organization_id,canonical_origin"},
                new String[]{"ix_site_url_identities_org_site", "1:organization_id,site_id"})) {
            assertEquals(index[1], scalar(c, "SELECT CONCAT(MAX(non_unique),':',GROUP_CONCAT(column_name ORDER BY seq_in_index)) "
                    + table.formatted("statistics") + " AND index_name='" + index[0] + "'"), index[0]);
        }
        assertEquals("CHECK:ck_site_url_identities_alphabet,CHECK:ck_site_url_identities_grammar,CHECK:ck_site_url_identities_labels,"
                + "CHECK:ck_site_url_identities_scheme_length,FOREIGN KEY:" + FK + ",PRIMARY KEY:PRIMARY,UNIQUE:" + UNIQUE,
                scalar(c, "SELECT GROUP_CONCAT(CONCAT(constraint_type,':',constraint_name) ORDER BY constraint_type,constraint_name) "
                        + table.formatted("table_constraints")));
        assertEquals("RESTRICT|RESTRICT|sites|uq_sites_org_site", scalar(c, "SELECT CONCAT(delete_rule,'|',update_rule,'|',"
                + "referenced_table_name,'|',unique_constraint_name) " + constraintTable.formatted("referential_constraints")
                + " AND constraint_name='" + FK + "'"));
        assertEquals("organization_id:organization_id,site_id:id", scalar(c, "SELECT GROUP_CONCAT(CONCAT(column_name,':',"
                + "referenced_column_name) ORDER BY ordinal_position) " + table.formatted("key_column_usage")
                + " AND constraint_name='" + FK + "' AND referenced_table_name='sites'"));
        assertEquals("1", scalar(c, "SELECT COUNT(DISTINCT referenced_table_name) " + table.formatted("key_column_usage")
                + " AND referenced_table_name IS NOT NULL"));
        assertEquals("4", scalar(c, "SELECT COUNT(*) " + constraintTable.formatted("check_constraints")));
        assertEquals("0", scalar(c, "SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE()"));
        assertEquals("0", scalar(c, "SELECT COUNT(*) FROM information_schema.views WHERE table_schema=DATABASE()"));
        assertEquals("0", scalar(c, "SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema=DATABASE()"));
        // sites is not altered: it still has exactly its V6 indexes and constraints.
        assertEquals("CHECK:ck_sites_status,FOREIGN KEY:fk_sites_created_by,FOREIGN KEY:fk_sites_organization,"
                + "PRIMARY KEY:PRIMARY,UNIQUE:uq_sites_org_site", scalar(c, "SELECT GROUP_CONCAT(CONCAT(constraint_type,':',constraint_name) "
                + "ORDER BY constraint_type,constraint_name) FROM information_schema.table_constraints WHERE table_schema=DATABASE() "
                + "AND table_name='sites'"));
        assertEquals("3", scalar(c, "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics "
                + "WHERE table_schema=DATABASE() AND table_name='sites'"));
    }

    private static void constraints(Connection c, SessionFactory f) throws Exception {
        user(c, 810001L);
        user(c, 810002L);
        long orgA = organization(c, "V10 A");
        long orgB = organization(c, "V10 B");

        // 1. JPA: a Site persisted through the mapping, then its identity from an equivalent spelling.
        long jpaSite;
        try (Session s = f.openSession()) {
            var tx = s.beginTransaction();
            Site site = new Site(s.getReference(Organization.class, orgA), "JPA", "  HTTPS://Jpa.Example.COM:443/  ",
                    s.getReference(Usuario.class, 810001L), CREATED);
            s.persist(site);
            s.flush();
            s.persist(new SiteUrlIdentityRecord(site, identity("HTTPS://Jpa.Example.COM:443/")));
            tx.commit();
            jpaSite = site.getId();
        }
        stored(c, jpaSite, orgA, "https://jpa.example.com");
        assertEquals(hex("  HTTPS://Jpa.Example.COM:443/  "), scalar(c, "SELECT HEX(base_url) FROM sites WHERE id=" + jpaSite),
                "the literal base_url is kept as received");
        try (Session s = f.openSession()) {
            SiteUrlIdentityRecord row = s.find(SiteUrlIdentityRecord.class, jpaSite);
            assertEquals("https://jpa.example.com", row.getCanonicalOrigin());
            assertEquals(orgA, row.getOrganizationId());
        }

        // 2. Same organization, another Site, another spelling of the same identity: refused by the unique key.
        long duplicate = site(c, orgA, "Duplicate", "https://JPA.example.com", "ACTIVE");
        try (Session s = f.openSession()) {
            var tx = s.beginTransaction();
            s.persist(new SiteUrlIdentityRecord(s.find(Site.class, duplicate), identity("https://JPA.example.com")));
            RuntimeException failure = assertThrows(RuntimeException.class, tx::commit);
            SQLException cause = sqlCause(failure);
            assertEquals(1062, cause.getErrorCode(), cause.getMessage());
            assertTrue(cause.getMessage().contains(UNIQUE), cause.getMessage());
            if (tx.isActive()) tx.rollback();
        }
        rejectedBy(c, () -> identityRow(c, orgA, duplicate, ascii("https://jpa.example.com")), 1062, UNIQUE);
        assertEquals("0", scalar(c, "SELECT COUNT(*) FROM " + TABLE + " WHERE site_id=" + duplicate));

        // 3. Another organization may hold the same identity; no cross-tenant deduplication.
        long other = site(c, orgB, "Other tenant", "https://jpa.example.com", "ACTIVE");
        identityRow(c, orgB, other, ascii("https://jpa.example.com"));
        assertEquals("2", scalar(c, "SELECT COUNT(*) FROM " + TABLE + " WHERE canonical_origin='https://jpa.example.com'"));

        // 4. One identity per Site.
        rejectedBy(c, () -> identityRow(c, orgA, jpaSite, ascii("https://second.example.com")), 1062, "PRIMARY");

        // 5. Tenant integrity through the composite foreign key.
        long siteA = site(c, orgA, "Tenant A", "https://tenant.example.com", "ACTIVE");
        rejectedBy(c, () -> identityRow(c, orgB, siteA, ascii("https://tenant.example.com")), 1452, FK);
        rejectedBy(c, () -> identityRow(c, orgA, Long.MAX_VALUE, ascii("https://missing.example.com")), 1452, FK);
        rejectedBy(c, () -> identityRow(c, Long.MAX_VALUE, siteA, ascii("https://missing.example.com")), 1452, FK);
        identityRow(c, orgA, siteA, ascii("https://tenant.example.com"));
        rejectedBy(c, () -> execute(c, "UPDATE " + TABLE + " SET organization_id=" + orgB + " WHERE site_id=" + siteA), 1452, FK);
        rejectedBy(c, () -> execute(c, "DELETE FROM sites WHERE id=" + siteA), 1451, FK);
        rejectedBy(c, () -> execute(c, "UPDATE sites SET organization_id=" + orgB + " WHERE id=" + siteA), 1451, FK);
        rejectedBy(c, () -> execute(c, "UPDATE sites SET id=9223372036854775806 WHERE id=" + siteA), 1451, FK);
        // Without the identity row the same parent changes succeed, so the 1451 above came from V10.
        c.setAutoCommit(false);
        try {
            execute(c, "DELETE FROM " + TABLE + " WHERE site_id=" + siteA);
            execute(c, "UPDATE sites SET organization_id=" + orgB + " WHERE id=" + siteA);
            execute(c, "DELETE FROM sites WHERE id=" + siteA);
        } finally {
            c.rollback();
            c.setAutoCommit(true);
        }
        stored(c, siteA, orgA, "https://tenant.example.com");

        // 6. An ARCHIVED Site keeps its identity reserved (SI-2): no status in the key.
        long archived = site(c, orgA, "Archived", "https://archived.example.com", "ARCHIVED");
        identityRow(c, orgA, archived, ascii("https://archived.example.com"));
        long reuse = site(c, orgA, "Reuse", "https://archived.example.com", "ACTIVE");
        rejectedBy(c, () -> identityRow(c, orgA, reuse, ascii("https://archived.example.com")), 1062, UNIQUE);

        // 7. Every canonical form the parser can emit is stored byte for byte, also at 260/261 bytes.
        for (String form : SiteUrlIdentityV10SchemaTest.ACCEPTED) {
            assertEquals(form, identity(form).canonicalForm());
            long id = site(c, orgA, "Accepted", form, "ACTIVE");
            identityRow(c, orgA, id, ascii(form));
            stored(c, id, orgA, form);
        }
        // Names refused only by the forbidden-name catalog are structurally valid: the schema is catalog-independent.
        for (String form : SiteUrlIdentityV10SchemaTest.CATALOG_ONLY) {
            long id = site(c, orgB, "Catalog only", form, "ACTIVE");
            identityRow(c, orgB, id, ascii(form));
            stored(c, id, orgB, form);
        }

        // 8. Non-canonical forms are refused by the named CHECK, in declaration order.
        long probe = site(c, orgA, "Check probe", "literal", "ACTIVE");
        int checks = 0;
        for (var rejected : SiteUrlIdentityV10SchemaTest.REJECTED.entrySet()) {
            byte[] bytes = rejected.getKey().getBytes(StandardCharsets.UTF_8);
            rejectedBy(c, () -> identityRow(c, orgA, probe, bytes), 4025, rejected.getValue());
            checks++;
        }
        for (byte[] raw : List.of(new byte[]{'h', 't', 't', 'p', ':', '/', '/', 'a', '.', 'b', (byte) 0xFF},
                new byte[]{'h', 't', 't', 'p', ':', '/', '/', 'a', '.', (byte) 0x80, 'b'}, new byte[]{0})) {
            rejectedBy(c, () -> identityRow(c, orgA, probe, raw), 4025, "ck_site_url_identities_alphabet");
            checks++;
        }
        assertEquals("0", scalar(c, "SELECT COUNT(*) FROM " + TABLE + " WHERE site_id=" + probe));
        // 262 bytes exceed the column: strict mode refuses it before any CHECK.
        String tooLong = "https://" + SiteUrlIdentityV10SchemaTest.host(254);
        assertEquals(262, tooLong.length());
        SQLException overflow = assertThrows(SQLException.class, () -> identityRow(c, orgA, probe, ascii(tooLong)));
        assertEquals(1406, overflow.getErrorCode(), overflow.getMessage());
        for (String column : List.of("site_id", "organization_id", "canonical_origin")) {
            String values = "site_id".equals(column) ? "NULL," + orgA + ",'https://null.example.com'"
                    : "organization_id".equals(column) ? probe + ",NULL,'https://null.example.com'" : probe + "," + orgA + ",NULL";
            SQLException nulls = assertThrows(SQLException.class, () -> execute(c,
                    "INSERT INTO " + TABLE + "(site_id,organization_id,canonical_origin) VALUES (" + values + ")"));
            assertEquals(1048, nulls.getErrorCode(), column);
        }

        // 9. Binary comparison: no padding, no case folding, no trailing-space or NUL equivalence.
        String where = "SELECT COUNT(*) FROM " + TABLE + " WHERE organization_id=" + orgA + " AND canonical_origin=";
        assertEquals("1", scalar(c, where + "'https://tenant.example.com'"));
        assertEquals("1", scalar(c, where + "CAST('https://tenant.example.com' AS BINARY)"));
        assertEquals("0", scalar(c, where + "'https://tenant.example.com '"));
        assertEquals("0", scalar(c, where + "'HTTPS://TENANT.EXAMPLE.COM'"));
        assertEquals("0", scalar(c, where + "'https://tenant.example.com/'"));
        assertEquals("0", scalar(c, where + "CONCAT('https://tenant.example.com',CHAR(0))"));

        // 10. The mapping is immutable: a dirty managed record is never written back.
        String before = scalar(c, "SELECT CONCAT(organization_id,'|',HEX(canonical_origin)) FROM " + TABLE + " WHERE site_id=" + jpaSite);
        try (Session s = f.openSession()) {
            var tx = s.beginTransaction();
            SiteUrlIdentityRecord row = s.find(SiteUrlIdentityRecord.class, jpaSite);
            set(row, "canonicalOrigin", ascii("https://changed.example.com"));
            set(row, "organizationId", orgB);
            tx.commit();
        }
        assertEquals(before, scalar(c, "SELECT CONCAT(organization_id,'|',HEX(canonical_origin)) FROM " + TABLE + " WHERE site_id=" + jpaSite));

        // 11. Legacy Sites without an identity row stay valid; nothing is backfilled.
        assertTrue(Long.parseLong(scalar(c, "SELECT COUNT(*) FROM sites s LEFT JOIN " + TABLE + " i ON i.site_id=s.id WHERE i.site_id IS NULL")) >= 3);
        System.out.println("V10 CONSTRAINTS PASS: " + SiteUrlIdentityV10SchemaTest.ACCEPTED.size() + " accepted + "
                + SiteUrlIdentityV10SchemaTest.CATALOG_ONLY.size() + " catalog-only stored byte-exact; " + checks
                + " CHECK rejections with exact constraint; 1062 unique/PK; 1452/1451 composite FK; 1406 at 262 bytes; 1048 NULL; "
                + "ARCHIVED reserved; binary compare; immutable mapping");
    }

    // ---------------------------------------------------------------- populated V9 -> V10

    @Test void populatedV9UpgradeAddsOnlyTheEmptyTable() throws Exception {
        Db db = db("V10_DB_UPGRADE", "upgrade");
        try (Connection c = connect(db)) {
            assertEquals("0", scalar(c, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()"));
            assertEquals(9, Flyway.configure().dataSource(db.url(), USER, db.password()).target("9").load().migrate().migrationsExecuted);
            assertEquals("1,2,3,4,5,6,7,8,9", scalar(c, "SELECT GROUP_CONCAT(version ORDER BY installed_rank) FROM flyway_schema_history"));
            assertEquals("0", countTable(c, TABLE));
            user(c, 820001L);
            user(c, 820002L);
            long org = organization(c, "Populated V9");
            long shared = organization(c, "Shared V9");
            execute(c, "INSERT INTO organization_memberships(organization_id,user_id,role,status,created_at,updated_at,row_version) VALUES "
                    + "(" + org + ",820001,'OWNER','ACTIVE','2024-02-29 23:58:57.987654','2024-03-31 02:30:00.123456',2),"
                    + "(" + shared + ",820002,'OWNER','REVOKED','2024-03-31 02:30:00.123456','2024-03-31 02:30:00.123456',7)");
            // Legacy literals: duplicated, non-canonical, empty, not a URL, archived. None is ever repaired.
            long first = site(c, org, "Dup 1", "https://dup.example.com", "ACTIVE");
            long second = site(c, org, "Dup 2", "https://dup.example.com", "ACTIVE");
            site(c, org, "Spelling", "  HTTPS://Legacy.Example.COM:443/  ", "ACTIVE");
            site(c, org, "Empty", "", "ACTIVE");
            site(c, shared, "Not a URL", "not a url", "ARCHIVED");
            List<String> tables = tables(c);
            Map<String, List<String>> data = snapshot(c, tables);
            List<String> oldHistory = rows(c, "SELECT JSON_ARRAY(installed_rank,version,description,type,script,checksum,success) "
                    + "FROM flyway_schema_history ORDER BY installed_rank");

            Flyway flyway = Flyway.configure().dataSource(db.url(), USER, db.password()).load();
            assertEquals(1, flyway.migrate().migrationsExecuted);
            assertEquals(VERSIONS, scalar(c, "SELECT GROUP_CONCAT(version ORDER BY installed_rank) FROM flyway_schema_history"));
            List<String> newHistory = rows(c, "SELECT JSON_ARRAY(installed_rank,version,description,type,script,checksum,success) "
                    + "FROM flyway_schema_history ORDER BY installed_rank");
            assertEquals(oldHistory, newHistory.subList(0, oldHistory.size()), "historical Flyway rows changed");
            assertEquals(oldHistory.size() + 1, newHistory.size());
            assertEquals("10|create site url identities|SQL|1", scalar(c,
                    "SELECT CONCAT(version,'|',description,'|',type,'|',success) FROM flyway_schema_history WHERE version='10'"));
            assertEquals("1", countTable(c, TABLE));
            assertEquals("0", scalar(c, "SELECT COUNT(*) FROM " + TABLE), "no backfill from legacy base_url");
            List<String> after = tables(c);
            assertEquals(tables.size() + 1, after.size());
            assertTrue(after.containsAll(tables) && after.contains(TABLE));
            assertEquals(data, snapshot(c, tables), "every previous table, its DDL and its rows are unchanged");
            schema(c);
            assertEquals(0, flyway.migrate().migrationsExecuted);
            assertTrue(flyway.validateWithResult().validationSuccessful);
            try (SessionFactory ignored = factory(db)) { /* Hibernate validate on the upgraded schema. */ }
            // A legacy Site gets an identity only by an explicit write (SI-4); the duplicate literal then conflicts.
            identityRow(c, org, first, ascii("https://dup.example.com"));
            rejectedBy(c, () -> identityRow(c, org, second, ascii("https://dup.example.com")), 1062, UNIQUE);
            assertEquals(hex("https://dup.example.com"), scalar(c, "SELECT HEX(base_url) FROM sites WHERE id=" + second));
        }
        System.out.println("V10 UPGRADE PASS: populated V9 -> V10; 1 migration; historical rows unchanged; empty table (no backfill); "
                + "previous tables byte-identical; exact schema; repeat 0; validate OK; Hibernate validate; explicit legacy attach only");
    }

    // ---------------------------------------------------------------- concurrency

    /**
     * Review finding MEDIUM-2: a sleep followed by "the future is not done" cannot tell a statement
     * that waits inside MariaDB from a worker that was never scheduled. Every blocking claim below is
     * therefore read from MariaDB itself by a separate observer account that holds only the global
     * PROCESS privilege (it cannot read any table): INNODB_TRX gives the waiter's server thread, its
     * LOCK WAIT state and its statement; INNODB_LOCK_WAITS names the transaction that blocks it; and
     * INNODB_LOCKS names the index of both locks. The waiter and blocker are identified by
     * CONNECTION_ID() of their own JDBC handles, read before the race starts. A directed negative
     * control shows that a deliberately delayed worker satisfies the old oracle and fails this one.
     */
    @Test void concurrentEqualIdentitiesCollideOnTheUniqueKey() throws Exception {
        Db db = db("V10_DB_RACE", "race");
        ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "v10-race-writer-b");
            thread.setDaemon(true);
            return thread;
        });
        List<String> evidence = new ArrayList<>();
        Throwable primary = null;
        try (Connection setup = connect(db); Connection a = connect(db); Connection b = connect(db); Connection observer = observer(db)) {
            try {
                assertEquals(10, Flyway.configure().dataSource(db.url(), USER, db.password()).load().migrate().migrationsExecuted);
                observerIsIsolated(observer, db);
                long aThread = threadId(a);
                long bThread = threadId(b);
                assertNotEquals(aThread, bThread);
                user(setup, 830001L);
                long org = organization(setup, "Race");
                long otherOrg = organization(setup, "Race other");
                a.setAutoCommit(false);
                b.setAutoCommit(false);

                // 0. Negative control: B is deliberately delayed and never sends SQL while A holds the key.
                byte[] control = ascii(identity("https://control.example.com").canonicalForm());
                long controlA = site(setup, org, "Control A", "https://control.example.com", "ACTIVE");
                identityRow(a, org, controlA, control);
                CountDownLatch release = new CountDownLatch(1);
                Future<?> delayed = executor.submit(() -> {
                    assertTrue(release.await(30, TimeUnit.SECONDS), "control worker was never released");
                    return null; // returns without touching the database
                });
                Thread.sleep(1500);
                assertFalse(delayed.isDone(), "the old oracle (sleep, then not done) is satisfied by a worker that never ran SQL");
                assertEquals(Optional.empty(), lockWait(observer, bThread, aThread, 3000),
                        "the database-observed proof must reject a worker that never reached MariaDB");
                assertEquals(Optional.empty(), transactionOf(observer, bThread), "the delayed worker has no InnoDB transaction");
                release.countDown();
                delayed.get(10, TimeUnit.SECONDS);
                a.rollback();
                assertEquals("0", scalar(setup, "SELECT COUNT(*) FROM " + TABLE));
                evidence.add("negative control: delayed worker not done after 1.5 s (old oracle passes); no INNODB lock wait "
                        + "and no transaction for its thread " + bThread + " within 3 s (new oracle fails, as intended)");

                // 1. Same organization, A commits: B waited on A inside InnoDB, then gets the duplicate key.
                byte[] x = ascii(identity("https://EXAMPLE.com/").canonicalForm());
                byte[] xSpelled = ascii(identity("https://example.com:443").canonicalForm());
                assertArrayEquals(x, xSpelled, "two spellings, one byte string");
                long siteX = site(setup, org, "X", "https://EXAMPLE.com/", "ACTIVE");
                long siteY = site(setup, org, "Y", "https://example.com:443", "ACTIVE");
                identityRow(a, org, siteX, x);
                assertEquals("RUNNING", transactionOf(observer, aThread).orElseThrow(
                        () -> new AssertionError("the observer cannot see A's open transaction: " + innodbState(observer))));
                Future<?> committedRace = executor.submit(() -> { identityRow(b, org, siteY, xSpelled); return null; });
                Optional<LockWait> observedFirst = lockWait(observer, bThread, aThread, 15000);
                if (observedFirst.isEmpty()) throw new AssertionError("B never waited on A inside MariaDB; " + diagnose(committedRace, observer));
                LockWait first = observedFirst.get();
                assertLockWaitOnTheIdentityKey(first, db);
                assertFalse(committedRace.isDone(), "B is still waiting when A commits");
                a.commit();
                ExecutionException lost = assertThrows(ExecutionException.class, () -> committedRace.get(15, TimeUnit.SECONDS));
                SQLException duplicate = sqlCause(lost);
                assertEquals(1062, duplicate.getErrorCode(), duplicate.getMessage());
                assertTrue(duplicate.getMessage().contains("`" + UNIQUE + "`") || duplicate.getMessage().contains("'" + UNIQUE + "'"),
                        duplicate.getMessage());
                b.rollback();
                assertEquals(Optional.empty(), transactionOf(observer, aThread));
                assertEquals(Optional.empty(), transactionOf(observer, bThread));
                assertEquals(siteX + "|" + hex("https://example.com"), scalar(setup, "SELECT GROUP_CONCAT(CONCAT(site_id,'|',HEX(canonical_origin))) FROM "
                        + TABLE + " WHERE organization_id=" + org));
                assertEquals("0", scalar(setup, "SELECT COUNT(*) FROM " + TABLE + " WHERE site_id=" + siteY), "no partial row for the loser");
                noOrphans(setup);
                evidence.add("commit: " + first + "; after COMMIT B got 1062 on " + UNIQUE + "; one row (site " + siteX + ")");

                // 2. Same organization, A rolls back: B waited on A inside InnoDB, then succeeds.
                byte[] z = ascii(identity("HTTPS://Race-Two.Example.COM").canonicalForm());
                byte[] zSpelled = ascii(identity("https://race-two.example.com/").canonicalForm());
                assertArrayEquals(z, zSpelled);
                long siteZ = site(setup, org, "Z", "HTTPS://Race-Two.Example.COM", "ACTIVE");
                long siteW = site(setup, org, "W", "https://race-two.example.com/", "ACTIVE");
                identityRow(a, org, siteZ, z);
                Future<?> rolledBackRace = executor.submit(() -> { identityRow(b, org, siteW, zSpelled); return null; });
                Optional<LockWait> observedSecond = lockWait(observer, bThread, aThread, 15000);
                if (observedSecond.isEmpty()) throw new AssertionError("B never waited on A inside MariaDB; " + diagnose(rolledBackRace, observer));
                LockWait second = observedSecond.get();
                assertLockWaitOnTheIdentityKey(second, db);
                assertFalse(rolledBackRace.isDone(), "B is still waiting when A rolls back");
                a.rollback();
                rolledBackRace.get(15, TimeUnit.SECONDS);
                b.commit();
                assertEquals(Optional.empty(), transactionOf(observer, aThread));
                assertEquals(Optional.empty(), transactionOf(observer, bThread));
                assertEquals(siteW + "|" + org, scalar(setup, "SELECT GROUP_CONCAT(CONCAT(site_id,'|',organization_id)) FROM " + TABLE
                        + " WHERE canonical_origin='https://race-two.example.com'"));
                assertEquals("0", scalar(setup, "SELECT COUNT(*) FROM " + TABLE + " WHERE site_id=" + siteZ), "nothing left by the rolled-back writer");
                noOrphans(setup);
                evidence.add("rollback: " + second + "; after ROLLBACK B succeeded; one row (site " + siteW + ")");

                // 3. The same fresh identity in another organization never waits, even while A is uncommitted.
                byte[] tenant = ascii(identity("HTTPS://Tenant-Race.Example.ORG/").canonicalForm());
                byte[] tenantSpelled = ascii(identity("https://tenant-race.example.org:443").canonicalForm());
                assertArrayEquals(tenant, tenantSpelled);
                assertEquals("0", scalar(setup, "SELECT COUNT(*) FROM " + TABLE + " WHERE canonical_origin='https://tenant-race.example.org'"));
                long siteA = site(setup, org, "Tenant A", "HTTPS://Tenant-Race.Example.ORG/", "ACTIVE");
                long siteB = site(setup, otherOrg, "Tenant B", "https://tenant-race.example.org:443", "ACTIVE");
                identityRow(a, org, siteA, tenant);
                Future<?> otherTenant = executor.submit(() -> { identityRow(b, otherOrg, siteB, tenantSpelled); return null; });
                otherTenant.get(10, TimeUnit.SECONDS);
                assertEquals("RUNNING", transactionOf(observer, aThread).orElseThrow(), "A is still uncommitted");
                assertEquals("RUNNING", transactionOf(observer, bThread).orElseThrow(), "B finished its insert without waiting");
                assertEquals(Optional.empty(), lockWait(observer, bThread, aThread, 0));
                assertEquals("0", observe(observer, "SELECT COUNT(*) FROM information_schema.INNODB_LOCK_WAITS"), "no lock wait at all");
                assertEquals("0", scalar(setup, "SELECT COUNT(*) FROM " + TABLE + " WHERE canonical_origin='https://tenant-race.example.org'"),
                        "neither row is visible before its commit");
                a.commit();
                b.commit();
                assertEquals(org + ":" + siteA + "," + otherOrg + ":" + siteB, scalar(setup, "SELECT GROUP_CONCAT(CONCAT(organization_id,':',site_id) "
                        + "ORDER BY organization_id) FROM " + TABLE + " WHERE canonical_origin='https://tenant-race.example.org'"));
                noOrphans(setup);
                evidence.add("cross-tenant: same fresh identity https://tenant-race.example.org, A uncommitted in organization " + org
                        + ", B committed-ready in organization " + otherOrg + " within 10 s, INNODB_LOCK_WAITS empty; two rows");

                // 4. Controlled lock-wait timeout, independent of the observer: 1205 can only come from an InnoDB lock wait.
                byte[] timed = ascii(identity("https://timeout.example.com").canonicalForm());
                long siteP = site(setup, org, "P", "https://timeout.example.com", "ACTIVE");
                long siteQ = site(setup, org, "Q", "HTTPS://TIMEOUT.example.com/", "ACTIVE");
                identityRow(a, org, siteP, timed);
                execute(b, "SET SESSION innodb_lock_wait_timeout=1");
                try {
                    long started = System.nanoTime();
                    SQLException timeout = assertThrows(SQLException.class, () -> identityRow(b, org, siteQ, timed));
                    long waitedMillis = (System.nanoTime() - started) / 1_000_000;
                    assertEquals(1205, timeout.getErrorCode(), timeout.getMessage());
                    assertTrue(waitedMillis >= 900 && waitedMillis < 10_000, "waited " + waitedMillis + " ms");
                    evidence.add("lock timeout: B got 1205 after " + waitedMillis + " ms with innodb_lock_wait_timeout=1 on its own session");
                } finally {
                    execute(b, "SET SESSION innodb_lock_wait_timeout=DEFAULT");
                }
                b.rollback();
                a.rollback();
                assertEquals("0", scalar(setup, "SELECT COUNT(*) FROM " + TABLE + " WHERE canonical_origin='https://timeout.example.com'"));
                assertEquals("0", observe(observer, "SELECT COUNT(*) FROM information_schema.INNODB_TRX WHERE trx_mysql_thread_id IN ("
                        + aThread + "," + bThread + ")"));
                noOrphans(setup);
            } catch (Throwable failure) {
                primary = failure;
                throw failure;
            } finally {
                // Never commit here: an unfinished scenario is rolled back on its own handles.
                List<SQLException> cleanup = new ArrayList<>();
                for (Connection c : List.of(a, b)) {
                    try {
                        if (!c.getAutoCommit()) c.rollback();
                    } catch (SQLException failure) {
                        cleanup.add(failure);
                    }
                }
                if (!cleanup.isEmpty()) {
                    if (primary != null) {
                        for (SQLException failure : cleanup) primary.addSuppressed(failure);
                    } else {
                        SQLException first = cleanup.remove(0);
                        for (SQLException failure : cleanup) first.addSuppressed(failure);
                        primary = first;
                        throw first;
                    }
                }
            }
        } finally {
            executor.shutdownNow();
            boolean stopped = executor.awaitTermination(10, TimeUnit.SECONDS);
            if (!stopped) {
                AssertionError alive = new AssertionError("race worker still alive after 10 s");
                if (primary != null) primary.addSuppressed(alive); else throw alive;
            }
        }
        for (String line : evidence) System.out.println("V10 CONCURRENCY OBSERVED: " + line);
        System.out.println("V10 CONCURRENCY PASS: waits proved by INNODB_TRX/INNODB_LOCK_WAITS/INNODB_LOCKS (waiter and blocker by "
                + "CONNECTION_ID, index " + UNIQUE + "); 1062 after commit; success after rollback; same identity in another tenant "
                + "never waits; 1205 directed timeout; delayed-worker negative control rejected; no partial or orphan rows");
    }

    /** What MariaDB reports about one waiting statement: never inferred from Java timing. */
    private record LockWait(String waiterTrx, String blockerTrx, String waiterState, String waiterQuery, String lockTable,
                            String lockIndex, String requestedMode, String blockingMode) {
        @Override public String toString() {
            return "waiter trx " + waiterTrx + " " + waiterState + " on " + lockTable + " index " + lockIndex + " mode " + requestedMode
                    + ", blocked by trx " + blockerTrx + " holding mode " + blockingMode;
        }
    }

    /** Bounded polling of the InnoDB lock views for waiter -> blocker, identified by server thread ids. */
    private static Optional<LockWait> lockWait(Connection observer, long waiterThread, long blockerThread, long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        String sql = "SELECT r.trx_id, bt.trx_id, r.trx_state, r.trx_query, l.lock_table, l.lock_index, l.lock_mode, bl.lock_mode "
                + "FROM information_schema.INNODB_LOCK_WAITS w "
                + "JOIN information_schema.INNODB_TRX r ON r.trx_id = w.requesting_trx_id "
                + "JOIN information_schema.INNODB_TRX bt ON bt.trx_id = w.blocking_trx_id "
                + "JOIN information_schema.INNODB_LOCKS l ON l.lock_id = w.requested_lock_id "
                + "JOIN information_schema.INNODB_LOCKS bl ON bl.lock_id = w.blocking_lock_id "
                + "WHERE r.trx_mysql_thread_id = ? AND bt.trx_mysql_thread_id = ?";
        while (true) {
            awaitFreshInnodbViews();
            try (PreparedStatement p = observer.prepareStatement(sql)) {
                p.setQueryTimeout(10);
                p.setLong(1, waiterThread);
                p.setLong(2, blockerThread);
                try (ResultSet r = p.executeQuery()) {
                    if (r.next()) {
                        return Optional.of(new LockWait(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5),
                                r.getString(6), r.getString(7), r.getString(8)));
                    }
                }
            } finally {
                markInnodbRead();
            }
            if (System.nanoTime() >= deadline) return Optional.empty();
        }
    }

    /**
     * MariaDB serves INNODB_TRX, INNODB_LOCK_WAITS and INNODB_LOCKS from a cache that is refreshed only
     * after it has gone unread for 100 ms (trx_i_s cache, CACHE_MIN_IDLE_TIME_US). Reading faster
     * freezes it: an earlier version of this probe polled every 50 ms and kept seeing the snapshot
     * taken before writer B started. Every observation therefore waits 250 ms after the previous one.
     */
    private static long lastInnodbRead = System.nanoTime() - 1_000_000_000L;

    private static void awaitFreshInnodbViews() throws InterruptedException {
        long remaining = lastInnodbRead + 250_000_000L - System.nanoTime();
        if (remaining > 0) Thread.sleep(remaining / 1_000_000L + 1);
    }

    private static void markInnodbRead() {
        lastInnodbRead = System.nanoTime();
    }

    /** One fresh read of an InnoDB lock view. */
    private static String observe(Connection observer, String sql) throws Exception {
        awaitFreshInnodbViews();
        try {
            return scalar(observer, sql);
        } finally {
            markInnodbRead();
        }
    }

    private static void assertLockWaitOnTheIdentityKey(LockWait wait, Db db) {
        assertEquals("LOCK WAIT", wait.waiterState(), wait.toString());
        // Connector/J may prefix SET STATEMENT max_statement_time=... FOR (query timeout); the INSERT itself must be there.
        assertTrue(wait.waiterQuery() != null && wait.waiterQuery().contains("INSERT INTO " + TABLE + "("), "waiter statement: " + wait.waiterQuery());
        assertEquals("`" + db.name() + "`.`" + TABLE + "`", wait.lockTable(), wait.toString());
        assertEquals(UNIQUE, wait.lockIndex(), "the wait is on the organization-scoped identity key: " + wait);
        assertEquals("S", wait.requestedMode(), "duplicate-key check: " + wait);
        assertEquals("X", wait.blockingMode(), "the uncommitted insert: " + wait);
        assertNotEquals(wait.waiterTrx(), wait.blockerTrx());
    }

    /** Failure evidence only: what B's statement did, and what InnoDB reported at that moment. */
    private static String diagnose(Future<?> writer, Connection observer) {
        String outcome;
        if (!writer.isDone()) {
            outcome = "B still running";
        } else {
            try {
                writer.get();
                outcome = "B completed without error";
            } catch (ExecutionException failure) {
                SQLException sql = null;
                for (Throwable t = failure; t != null; t = t.getCause()) if (t instanceof SQLException e) { sql = e; break; }
                outcome = sql != null ? "B failed with " + sql.getErrorCode() + ": " + sql.getMessage() : "B failed: " + failure.getCause();
            } catch (Exception other) {
                outcome = "B outcome unknown: " + other;
            }
        }
        StringBuilder stack = new StringBuilder();
        for (var entry : Thread.getAllStackTraces().entrySet()) {
            if (!entry.getKey().getName().equals("v10-race-writer-b")) continue;
            stack.append(entry.getKey().getState());
            StackTraceElement[] frames = entry.getValue();
            for (int i = 0; i < Math.min(frames.length, 12); i++) stack.append(" <- ").append(frames[i]);
        }
        return outcome + "; writer thread: " + stack + "; InnoDB: " + innodbState(observer);
    }

    private static String innodbState(Connection observer) {
        try {
            awaitFreshInnodbViews();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "unavailable: interrupted";
        }
        try {
            return "trx=" + rows(observer, "SELECT CONCAT(trx_id,'/',trx_state,'/',trx_mysql_thread_id,'/',IFNULL(LEFT(trx_query,90),'-')) "
                    + "FROM information_schema.INNODB_TRX") + " waits=" + rows(observer, "SELECT CONCAT(requesting_trx_id,'<-',blocking_trx_id,"
                    + "' ',requested_lock_id,' ',blocking_lock_id) FROM information_schema.INNODB_LOCK_WAITS") + " locks=" + rows(observer,
                    "SELECT CONCAT(lock_id,' ',lock_trx_id,' ',lock_mode,' ',lock_type,' ',IFNULL(lock_table,'-'),' ',IFNULL(lock_index,'-')) "
                    + "FROM information_schema.INNODB_LOCKS") + " processes=" + rows(observer, "SELECT CONCAT(id,'/',command,'/',"
                    + "IFNULL(state,'-'),'/',time_ms,'/',IFNULL(LEFT(info,90),'-')) FROM information_schema.PROCESSLIST "
                    + "WHERE user='v10_identity_user'");
        } catch (SQLException failure) {
            return "unavailable: " + failure.getErrorCode() + " " + failure.getMessage();
        } finally {
            markInnodbRead();
        }
    }

    /** The InnoDB transaction state of one server thread, if it has a started transaction. */
    private static Optional<String> transactionOf(Connection observer, long thread) throws Exception {
        awaitFreshInnodbViews();
        try (PreparedStatement p = observer.prepareStatement("SELECT trx_state FROM information_schema.INNODB_TRX WHERE trx_mysql_thread_id = ?")) {
            p.setLong(1, thread);
            try (ResultSet r = p.executeQuery()) {
                return r.next() ? Optional.of(r.getString(1)) : Optional.empty();
            }
        } finally {
            markInnodbRead();
        }
    }

    private static long threadId(Connection c) throws SQLException {
        return Long.parseLong(scalar(c, "SELECT CONNECTION_ID()"));
    }

    private static void noOrphans(Connection c) throws SQLException {
        assertEquals("0", scalar(c, "SELECT COUNT(*) FROM " + TABLE + " i LEFT JOIN sites s ON s.organization_id=i.organization_id "
                + "AND s.id=i.site_id WHERE s.id IS NULL"), "identity rows without their Site");
    }

    /** Observation-only account: global PROCESS and nothing else, so it can read lock views but no data. */
    private static Connection observer(Db db) throws SQLException {
        String user = required("V10_OBSERVER_USER");
        assertEquals("v10_identity_observer", user);
        Connection c = DriverManager.getConnection(db.url().substring(0, db.url().lastIndexOf('/') + 1), user, required("V10_OBSERVER_PASSWORD"));
        assertTrue(c.getMetaData().getDatabaseProductVersion().startsWith("11.2."));
        return c;
    }

    private static void observerIsIsolated(Connection observer, Db db) throws SQLException {
        List<String> grants = rows(observer, "SHOW GRANTS");
        assertEquals(1, grants.size(), grants.toString());
        assertTrue(grants.get(0).startsWith("GRANT PROCESS ON *.* TO `v10_identity_observer`@`%`"), "observer grants: PROCESS only");
        SQLException denied = assertThrows(SQLException.class, () -> scalar(observer, "SELECT COUNT(*) FROM `" + db.name() + "`." + TABLE));
        assertEquals(1142, denied.getErrorCode(), denied.getMessage());
    }

    // ---------------------------------------------------------------- helpers

    private interface SqlAction { void run() throws Exception; }

    private static SessionFactory factory(Db db) {
        Configuration cfg = new Configuration();
        for (Class<?> entity : List.of(SiteUrlIdentityRecord.class, Site.class, OrganizationMembership.class, Organization.class,
                Usuario.class, Rol.class, Auditoria.class, Incidencia.class, HistorialAuditoria.class, HistorialAccionAdmin.class)) {
            cfg.addAnnotatedClass(entity);
        }
        cfg.setProperty("hibernate.connection.url", db.url());
        cfg.setProperty("hibernate.connection.username", USER);
        cfg.setProperty("hibernate.connection.password", db.password());
        cfg.setProperty("hibernate.connection.driver_class", "org.mariadb.jdbc.Driver");
        cfg.setProperty("hibernate.hbm2ddl.auto", "validate");
        cfg.setProperty("hibernate.show_sql", "false");
        return cfg.buildSessionFactory();
    }

    private static SiteUrlIdentity identity(String text) {
        return SiteUrlIdentityRecordTest.identity(text);
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static String hex(String value) {
        return HexFormat.of().withUpperCase().formatHex(value.getBytes(StandardCharsets.UTF_8));
    }

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

    private static List<String> rows(Connection c, String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            while (r.next()) rows.add(r.getString(1));
        }
        return rows;
    }

    private static String countTable(Connection c, String name) throws SQLException {
        return scalar(c, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='" + name + "'");
    }

    /** Exact error code and the exact constraint named in the message, so another key cannot satisfy the proof. */
    private static void rejectedBy(Connection c, SqlAction action, int code, String constraint) throws SQLException {
        SQLException error = assertThrows(SQLException.class, action::run);
        assertEquals(code, error.getErrorCode(), error.getMessage());
        assertTrue(error.getMessage().contains("`" + constraint + "`") || error.getMessage().contains("'" + constraint + "'")
                || error.getMessage().contains("." + constraint + "'"), constraint + " <- " + error.getMessage());
        if (!c.getAutoCommit()) c.rollback();
    }

    private static SQLException sqlCause(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) if (t instanceof SQLException sql) return sql;
        throw new AssertionError("No SQLException in " + failure, failure);
    }

    private static void user(Connection c, long id) throws SQLException {
        execute(c, "INSERT INTO usuarios(id,email,fecha_registro,nombre,password,role,activo,protegido,token_version,row_version) "
                + "VALUES (" + id + ",'v10-" + id + "@identity.invalid','2001-02-03 04:05:06.123456',"
                + "'V10 fixture','unused','CLIENTE',b'1',b'0',0,0)");
    }

    private static long organization(Connection c, String name) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("INSERT INTO organizations(name,personal_owner_user_id,created_at,updated_at,row_version) "
                + "VALUES (?,NULL,'2024-03-31 02:30:00.123456','2024-03-31 02:30:00.123456',0)", Statement.RETURN_GENERATED_KEYS)) {
            p.setString(1, name);
            assertEquals(1, p.executeUpdate());
            try (ResultSet keys = p.getGeneratedKeys()) { assertTrue(keys.next()); return keys.getLong(1); }
        }
    }

    private static long site(Connection c, long organization, String name, String baseUrl, String status) throws SQLException {
        long creator = Long.parseLong(scalar(c, "SELECT MIN(id) FROM usuarios"));
        try (PreparedStatement p = c.prepareStatement("INSERT INTO sites(organization_id,name,base_url,status,created_by_user_id,"
                + "created_at,updated_at,row_version) VALUES (?,?,?,?,?,'2024-03-31 02:30:00.123456','2024-03-31 02:30:00.123456',0)",
                Statement.RETURN_GENERATED_KEYS)) {
            p.setLong(1, organization);
            p.setString(2, name);
            p.setString(3, baseUrl);
            p.setString(4, status);
            p.setLong(5, creator);
            assertEquals(1, p.executeUpdate());
            try (ResultSet keys = p.getGeneratedKeys()) { assertTrue(keys.next()); return keys.getLong(1); }
        }
    }

    /** Bound bytes: the probe controls every byte; HEX checks prove what was stored. */
    private static void identityRow(Connection c, long organization, long site, byte[] origin) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("INSERT INTO " + TABLE + "(site_id,organization_id,canonical_origin) VALUES (?,?,?)")) {
            p.setQueryTimeout(30); // bounded even if a scenario never releases its lock
            p.setLong(1, site);
            p.setLong(2, organization);
            p.setBytes(3, origin);
            assertEquals(1, p.executeUpdate());
        }
    }

    private static void stored(Connection c, long site, long organization, String form) throws SQLException {
        assertEquals(organization + "|" + hex(form) + "|" + form.length(), scalar(c, "SELECT CONCAT(organization_id,'|',"
                + "HEX(canonical_origin),'|',OCTET_LENGTH(canonical_origin)) FROM " + TABLE + " WHERE site_id=" + site));
        try (PreparedStatement p = c.prepareStatement("SELECT canonical_origin FROM " + TABLE + " WHERE site_id=?")) {
            p.setLong(1, site);
            try (ResultSet r = p.executeQuery()) {
                assertTrue(r.next());
                assertArrayEquals(ascii(form), r.getBytes(1));
            }
        }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static List<String> tables(Connection c) throws SQLException {
        return rows(c, "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() "
                + "AND table_name<>'flyway_schema_history' ORDER BY table_name");
    }

    private static Map<String, List<String>> snapshot(Connection c, List<String> tables) throws SQLException {
        Map<String, List<String>> result = new TreeMap<>();
        for (String table : tables) {
            List<String> rows = new ArrayList<>();
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SHOW CREATE TABLE `" + table + "`")) {
                assertTrue(r.next());
                rows.add(r.getString(2).replaceAll("AUTO_INCREMENT=\\d+\\s*", ""));
            }
            String columns = scalar(c, "SELECT GROUP_CONCAT(CONCAT('`',COLUMN_NAME,'`') ORDER BY ORDINAL_POSITION) "
                    + "FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='" + table + "'");
            rows.addAll(SiteUrlIdentityMariaDbProbe.rows(c, "SELECT JSON_ARRAY(" + columns + ") FROM `" + table + "` ORDER BY 1"));
            result.put(table, rows);
        }
        return result;
    }
}
