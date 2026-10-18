package com.smartaudits.v2.model;

import com.smartaudits.model.*;
import jakarta.persistence.OptimisticLockException;
import org.flywaydb.core.Flyway;
import org.hibernate.Hibernate;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.StaleStateException;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;

import java.sql.*;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit harness -Dtest entry only: deliberately outside Surefire's *Test patterns.
 * ADR-0001 forbids orphan Audits as test data: every Audit row below is a transient fixture
 * inside a Hibernate transaction that always ends in ROLLBACK, is proven invisible to an
 * independent connection while open, and is proven absent afterwards. No Audit is committed.
 * The session sql_mode is read and asserted, never changed.
 */
class AuditMariaDbProbe {
    private static final String[] LEGACY = {"usuarios", "roles", "usuarios_roles", "auditorias", "incidencias",
            "resultados", "historial_auditorias", "historial_acciones_admin"};
    private static final String[] PARENTS = {"organizations", "organization_memberships", "sites"};
    private static final String VERSIONS = "1,2,3,4,5,6,7,9000";
    private static final Instant CREATED = Instant.parse("2024-02-29T23:58:57.987654321Z");
    private static final Instant UPDATED = Instant.parse("2024-03-31T02:30:00.123456789Z");
    private static final String RAW_TIME = "'2024-03-31 02:30:00.123456'";

    @FunctionalInterface
    private interface Fixture { void run(Session session, Connection tx) throws Exception; }

    @Test void isolatedAuditContract() throws Exception {
        String url = required("F3_DB_URL");
        String database = required("F3_DB_NAME");
        assertTrue(database.matches("f3_harness_[a-f0-9]{32}"));
        assertTrue(url.matches("jdbc:mariadb://127\\.0\\.0\\.1:[0-9]+/" + database));
        assertFalse(url.contains(":3306/"));
        assertTrue(required("F3_AUDIT_HARNESS_RUN_ID").matches("[a-f0-9]{32}"));
        assertEquals(VERSIONS, required("F3_EXPECTED_VERSIONS"));
        String user = required("F3_DB_USER");
        assertEquals("f3_harness_user", user);
        String zone = required("F3_TIMEZONE");
        assertTrue(Set.of("Asia/Kathmandu", "Europe/Madrid").contains(zone));
        assertEquals(zone, ZoneId.systemDefault().getId());
        String password = required("F3_DB_PASSWORD");
        try (Connection c = DriverManager.getConnection(url, user, password)) {
            assertTrue(c.getMetaData().getDatabaseProductVersion().startsWith("11.2."));
            assertEquals(database, c.getCatalog());
            strict(c);
            String globalMode = scalar(c, "SELECT @@GLOBAL.sql_mode");
            switch (required("F3_AUDIT_MODE")) {
                case "contracts" -> contracts(c, url, user, password);
                case "tenant" -> tenant(c, url, user, password);
                case "upgrade" -> upgrade(c, url, user, password);
                default -> fail("Unknown Audit probe mode");
            }
            assertEquals(globalMode, scalar(c, "SELECT @@GLOBAL.sql_mode"), "Global sql_mode changed");
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

    private static SessionFactory factory(String url, String user, String password) {
        Configuration cfg = new Configuration();
        for (Class<?> entity : List.of(Audit.class, Site.class, OrganizationMembership.class, Organization.class,
                Usuario.class, Rol.class, Auditoria.class, Incidencia.class, HistorialAuditoria.class, HistorialAccionAdmin.class)) {
            cfg.addAnnotatedClass(entity);
        }
        cfg.setProperty("hibernate.connection.url", url);
        cfg.setProperty("hibernate.connection.username", user);
        cfg.setProperty("hibernate.connection.password", password);
        cfg.setProperty("hibernate.connection.driver_class", "org.mariadb.jdbc.Driver");
        cfg.setProperty("hibernate.hbm2ddl.auto", "validate");
        cfg.setProperty("hibernate.show_sql", "false");
        return cfg.buildSessionFactory();
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

    private static void rejected(Connection c, String sql, int code) {
        SQLException error = assertThrows(SQLException.class, () -> execute(c, sql));
        assertEquals(code, error.getErrorCode(), error.getMessage());
    }

    /** Also identifies the exact constraint, so another FK cannot satisfy the proof. */
    private static void rejectedBy(Connection c, String sql, int code, String constraint) {
        SQLException error = assertThrows(SQLException.class, () -> execute(c, sql));
        assertEquals(code, error.getErrorCode(), error.getMessage());
        assertTrue(error.getMessage().contains("`" + constraint + "`"), error.getMessage());
    }

    private static long generated(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            assertEquals(1, s.executeUpdate(sql, Statement.RETURN_GENERATED_KEYS));
            try (ResultSet keys = s.getGeneratedKeys()) { assertTrue(keys.next()); return keys.getLong(1); }
        }
    }

    private static void identity(Connection c, long id) throws SQLException {
        execute(c, "INSERT INTO usuarios(id,email,fecha_registro,nombre,password,role,activo,protegido,token_version,row_version) "
                + "VALUES (" + id + ",'audit-" + id + "@f3.invalid','2001-02-03 04:05:06.123456',"
                + "'Audit fixture','unused','CLIENTE',b'1',b'0',0,0)");
    }

    private static long organization(Connection c, String name) throws SQLException {
        return generated(c, "INSERT INTO organizations(name,personal_owner_user_id,created_at,updated_at,row_version) VALUES ('"
                + name + "',NULL," + RAW_TIME + "," + RAW_TIME + ",0)");
    }

    private static long site(Connection c, long organization, long creator) throws SQLException {
        return generated(c, "INSERT INTO sites(organization_id,name,base_url,status,created_by_user_id,created_at,updated_at,row_version) "
                + "VALUES (" + organization + ",'Audit parent site','https://audit-parent.invalid/','ACTIVE'," + creator + ","
                + RAW_TIME + "," + RAW_TIME + ",0)");
    }

    private static String audit(Object organization, Object site, Object creator) {
        return "INSERT INTO audits(organization_id,site_id,created_by_user_id,created_at,updated_at,row_version) VALUES ("
                + organization + "," + site + "," + creator + "," + RAW_TIME + "," + RAW_TIME + ",0)";
    }

    private static String count(String table) { return "SELECT COUNT(*) FROM " + table; }

    private static String time(Connection c, long id) throws SQLException {
        return scalar(c, "SELECT CONCAT(DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s.%f'),'|',"
                + "DATE_FORMAT(updated_at,'%Y-%m-%d %H:%i:%s.%f'),'|',row_version) FROM audits WHERE id=" + id);
    }

    /** The only way this probe touches audits: one Hibernate transaction that is always rolled back. */
    private static void rolledBack(SessionFactory f, Connection observer, Fixture fixture) throws Exception {
        try (Session s = f.openSession()) {
            var tx = s.beginTransaction();
            try {
                Connection[] held = new Connection[1];
                s.doWork(connection -> held[0] = connection);
                assertFalse(held[0].getAutoCommit(), "Audit fixtures require an open transaction");
                fixture.run(s, held[0]);
            } finally {
                // Never commit: no orphan Audit may become durable test data. There is no commit path at all.
                if (tx.isActive()) tx.rollback();
            }
        }
        assertEquals("0", scalar(observer, count("audits")), "An Audit fixture survived the rollback");
    }

    private static void schema(Connection c) throws SQLException {
        String where = " FROM information_schema.%s WHERE %s=DATABASE() AND table_name='audits'";
        String columns = String.format(where, "columns", "table_schema");
        String statistics = String.format(where, "statistics", "table_schema");
        assertEquals("id:bigint(20):NO:NULL:auto_increment,organization_id:bigint(20):NO:NULL:,site_id:bigint(20):NO:NULL:,"
                + "created_by_user_id:bigint(20):NO:NULL:,created_at:datetime(6):NO:NULL:,updated_at:datetime(6):NO:NULL:,"
                + "row_version:bigint(20):NO:0:", scalar(c, "SELECT GROUP_CONCAT(CONCAT(column_name,':',column_type,':',is_nullable,':',"
                + "IFNULL(column_default,'NULL'),':',extra) ORDER BY ordinal_position)" + columns));
        assertEquals("0", scalar(c, "SELECT COUNT(*)" + columns + " AND character_set_name IS NOT NULL"), "No text columns");
        assertEquals("2", scalar(c, "SELECT COUNT(*)" + columns
                + " AND column_name IN ('created_at','updated_at') AND data_type='datetime' AND datetime_precision=6"));
        assertEquals("InnoDB|utf8mb4_unicode_520_ci", scalar(c, "SELECT CONCAT(engine,'|',table_collation)"
                + String.format(where, "tables", "table_schema")));
        assertEquals("4", scalar(c, "SELECT COUNT(DISTINCT index_name)" + statistics));
        Map<String, String> indexes = Map.of("PRIMARY", "0:id", "uq_audits_org_audit", "0:organization_id,id",
                "ix_audits_org_site", "1:organization_id,site_id", "ix_audits_created_by", "1:created_by_user_id");
        for (var index : indexes.entrySet()) {
            assertEquals(index.getValue(), scalar(c, "SELECT CONCAT(MAX(non_unique),':',GROUP_CONCAT(column_name ORDER BY seq_in_index))"
                    + statistics + " AND index_name='" + index.getKey() + "'"), index.getKey());
        }
        assertEquals("FOREIGN KEY:fk_audits_created_by,FOREIGN KEY:fk_audits_organization,FOREIGN KEY:fk_audits_site,"
                + "PRIMARY KEY:PRIMARY,UNIQUE:uq_audits_org_audit", scalar(c, "SELECT GROUP_CONCAT(CONCAT(constraint_type,':',constraint_name) "
                + "ORDER BY constraint_type,constraint_name)" + String.format(where, "table_constraints", "table_schema")));
        assertEquals("fk_audits_created_by:RESTRICT:RESTRICT:usuarios,fk_audits_organization:RESTRICT:RESTRICT:organizations,"
                + "fk_audits_site:RESTRICT:RESTRICT:sites", scalar(c, "SELECT GROUP_CONCAT(CONCAT(constraint_name,':',delete_rule,':',"
                + "update_rule,':',referenced_table_name) ORDER BY constraint_name)" + String.format(where, "referential_constraints", "constraint_schema")));
        assertEquals("fk_audits_created_by:created_by_user_id:usuarios:id,fk_audits_organization:organization_id:organizations:id,"
                + "fk_audits_site:organization_id:sites:organization_id,fk_audits_site:site_id:sites:id", scalar(c,
                "SELECT GROUP_CONCAT(CONCAT(constraint_name,':',column_name,':',referenced_table_name,':',referenced_column_name) "
                        + "ORDER BY constraint_name,ordinal_position)" + String.format(where, "key_column_usage", "table_schema")
                        + " AND referenced_table_name IS NOT NULL"));
        assertEquals("0", scalar(c, "SELECT COUNT(*)" + String.format(where, "check_constraints", "constraint_schema")), "No CHECK: Audit has no status");
        assertEquals("0", scalar(c, "SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE()"));
    }

    private static void contracts(Connection c, String url, String user, String password) throws Exception {
        assertEquals("0", scalar(c, count("audits")));
        schema(c);
        Map<String, List<String>> legacy = snapshot(c, LEGACY);
        Map<String, List<String>> parents = snapshot(c, PARENTS);
        try (SessionFactory f = factory(url, user, password)) {
            rolledBack(f, c, (s, tx) -> {
                strict(tx);
                identity(tx, 800601L); // Site creator.
                identity(tx, 800602L); // Audit creator, distinct so each user FK is isolated.
                long org = organization(tx, "Audit org");
                long otherOrg = organization(tx, "Audit other org");
                long site = site(tx, org, 800601L);
                long otherSite = site(tx, otherOrg, 800601L);
                Audit audit = AuditTest.newAudit(s.getReference(Organization.class, org), s.getReference(Site.class, site),
                        s.getReference(Usuario.class, 800602L), CREATED);
                s.persist(audit);
                s.flush();
                long auditId = audit.getId();
                assertTrue(auditId > 0);
                assertEquals(0L, audit.getRowVersion());
                assertEquals(Instant.parse("2024-02-29T23:58:57.987654Z"), audit.getCreatedAt(), "@PrePersist must truncate");
                assertEquals(audit.getCreatedAt(), audit.getUpdatedAt());
                assertEquals("2024-02-29 23:58:57.987654|2024-02-29 23:58:57.987654|0", time(tx, auditId));
                assertEquals("0", scalar(c, count("audits")), "Uncommitted Audit fixture visible outside its transaction");
                s.clear();
                Audit loaded = s.find(Audit.class, auditId);
                assertFalse(Hibernate.isInitialized(loaded.getOrganization()), "Organization must load lazily");
                assertFalse(Hibernate.isInitialized(loaded.getSite()), "Site must load lazily");
                assertFalse(Hibernate.isInitialized(loaded.getCreatedBy()), "createdBy must load lazily");
                assertEquals(org, loaded.getOrganization().getId());
                assertEquals(site, loaded.getSite().getId());
                assertEquals(800602L, loaded.getCreatedBy().getId());
                long raw = generated(tx, audit(org, site, 800602L));
                Audit fromSql = s.find(Audit.class, raw);
                assertEquals(Instant.parse("2024-03-31T02:30:00.123456Z"), fromSql.getCreatedAt(), "Raw UTC must read back exactly");
                assertEquals(fromSql.getCreatedAt(), fromSql.getUpdatedAt());
                String[] values = {Long.toString(org), Long.toString(site), "800602", RAW_TIME, RAW_TIME, "0"};
                for (int i = 0; i < values.length; i++) {
                    String[] missing = values.clone();
                    missing[i] = "NULL";
                    rejected(tx, "INSERT INTO audits(organization_id,site_id,created_by_user_id,created_at,updated_at,row_version) VALUES ("
                            + String.join(",", missing) + ")", 1048);
                }
                rejectedBy(tx, audit(org, Long.MAX_VALUE, 800602L), 1452, "fk_audits_site");
                rejectedBy(tx, audit(org, site, Long.MAX_VALUE), 1452, "fk_audits_created_by");
                rejected(tx, audit(Long.MAX_VALUE, site, 800602L), 1452); // Both organization FKs fail; either may report.
                rejectedBy(tx, "DELETE FROM sites WHERE id=" + site, 1451, "fk_audits_site");
                rejectedBy(tx, "UPDATE sites SET id=9223372036854775807 WHERE id=" + site, 1451, "fk_audits_site");
                rejectedBy(tx, "DELETE FROM usuarios WHERE id=800602", 1451, "fk_audits_created_by");
                rejectedBy(tx, "UPDATE usuarios SET id=800603 WHERE id=800602", 1451, "fk_audits_created_by");
                rejected(tx, "DELETE FROM organizations WHERE id=" + org, 1451);
                rejected(tx, "UPDATE organizations SET id=9223372036854775807 WHERE id=" + org, 1451);
                // Identity is immutable: only updated_at and the version change; organization/site/creator/created_at stay.
                AuditTest.set(loaded, "organization", s.getReference(Organization.class, otherOrg));
                AuditTest.set(loaded, "site", s.getReference(Site.class, otherSite));
                AuditTest.set(loaded, "createdBy", s.getReference(Usuario.class, 800601L));
                AuditTest.set(loaded, "createdAt", UPDATED);
                AuditTest.set(loaded, "updatedAt", UPDATED);
                s.flush();
                assertEquals(1L, loaded.getRowVersion());
                assertEquals(Instant.parse("2024-03-31T02:30:00.123456Z"), loaded.getUpdatedAt(), "@PreUpdate must truncate in memory");
                assertEquals(org + "|" + site + "|800602|2024-02-29 23:58:57.987654|2024-03-31 02:30:00.123456|1", scalar(tx,
                        "SELECT CONCAT(organization_id,'|',site_id,'|',created_by_user_id,'|',DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s.%f'),'|',"
                                + "DATE_FORMAT(updated_at,'%Y-%m-%d %H:%i:%s.%f'),'|',row_version) FROM audits WHERE id=" + auditId));
                // No cascade: removing an Audit leaves every parent row in place.
                String parentCounts = scalar(tx, "SELECT CONCAT((" + count("organizations") + "),'|',(" + count("sites") + "),'|',("
                        + count("usuarios") + "))");
                s.remove(fromSql);
                s.flush();
                assertEquals(parentCounts, scalar(tx, "SELECT CONCAT((" + count("organizations") + "),'|',(" + count("sites") + "),'|',("
                        + count("usuarios") + "))"));
                // Optimistic version check without concurrency: a version advanced underneath the managed entity is stale.
                execute(tx, "UPDATE audits SET row_version=row_version+1 WHERE id=" + auditId);
                AuditTest.set(loaded, "updatedAt", CREATED);
                RuntimeException conflict = assertThrows(RuntimeException.class, s::flush);
                assertTrue(conflict instanceof OptimisticLockException || conflict instanceof StaleStateException,
                        conflict.getClass().getName());
            });
        }
        assertEquals(legacy, snapshot(c, LEGACY));
        assertEquals(parents, snapshot(c, PARENTS));
        System.out.println("AUDIT CONTRACTS PASS: " + ZoneId.systemDefault()
                + "; 7 columns/4 indexes/3 FK RESTRICT/no CHECK/NOT NULL/UTC micros/lazy/immutable identity/no cascade/"
                + "version check; every Audit fixture rolled back, none committed");
    }

    private static void tenant(Connection c, String url, String user, String password) throws Exception {
        assertEquals("0", scalar(c, count("audits")));
        Map<String, List<String>> legacy = snapshot(c, LEGACY);
        Map<String, List<String>> parents = snapshot(c, PARENTS);
        try (SessionFactory f = factory(url, user, password)) {
            rolledBack(f, c, (s, tx) -> {
                identity(tx, 800701L);
                long orgA = organization(tx, "Audit tenant A");
                long orgB = organization(tx, "Audit tenant B");
                long siteA = site(tx, orgA, 800701L);
                long siteB = site(tx, orgB, 800701L);
                long auditA = generated(tx, audit(orgA, siteA, 800701L));
                rejectedBy(tx, audit(orgB, siteA, 800701L), 1452, "fk_audits_site");
                rejectedBy(tx, audit(orgA, siteB, 800701L), 1452, "fk_audits_site");
                rejectedBy(tx, "UPDATE audits SET site_id=" + siteB + " WHERE id=" + auditA, 1452, "fk_audits_site");
                rejectedBy(tx, "UPDATE audits SET organization_id=" + orgB + " WHERE id=" + auditA, 1452, "fk_audits_site");
                rejectedBy(tx, "UPDATE sites SET organization_id=" + orgB + " WHERE id=" + siteA, 1451, "fk_audits_site");
                rejectedBy(tx, "DELETE FROM sites WHERE id=" + siteA, 1451, "fk_audits_site");
                assertEquals(orgA + "|" + siteA, scalar(tx, "SELECT CONCAT(organization_id,'|',site_id) FROM audits WHERE id=" + auditA));
                assertEquals("0", scalar(c, count("audits")), "Uncommitted Audit fixture visible outside its transaction");
                // Without the Audit the same tenant change is accepted, so the 1451 above came from fk_audits_site.
                execute(tx, "DELETE FROM audits WHERE id=" + auditA);
                execute(tx, "UPDATE sites SET organization_id=" + orgB + " WHERE id=" + siteA);
                assertEquals(Long.toString(orgB), scalar(tx, "SELECT organization_id FROM sites WHERE id=" + siteA));
            });
        }
        assertEquals(legacy, snapshot(c, LEGACY));
        assertEquals(parents, snapshot(c, PARENTS));
        System.out.println("AUDIT TENANT FK PASS: (organization_id, site_id) -> sites(organization_id, id); same tenant accepted; "
                + "cross-tenant insert/update 1452; Site tenant change/delete 1451; every Audit fixture rolled back");
    }

    private static void upgrade(Connection c, String url, String user, String password) throws SQLException {
        assertEquals("0", scalar(c, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()"));
        Flyway.configure().dataSource(url, user, password).target("6").load().migrate();
        assertEquals("1,2,3,4,5,6", scalar(c, "SELECT GROUP_CONCAT(version ORDER BY installed_rank) FROM flyway_schema_history"));
        assertEquals("0", scalar(c, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='audits'"));
        identity(c, 800801L);
        identity(c, 800802L);
        execute(c, "INSERT INTO organizations(name,personal_owner_user_id,created_at,updated_at,row_version) VALUES "
                + "('Populated V6',800801,'2024-02-29 23:58:57.987654','2024-03-31 02:30:00.123456',4),"
                + "('Shared V6',NULL," + RAW_TIME + "," + RAW_TIME + ",0)");
        long populated = Long.parseLong(scalar(c, "SELECT id FROM organizations WHERE personal_owner_user_id=800801"));
        long shared = Long.parseLong(scalar(c, "SELECT id FROM organizations WHERE personal_owner_user_id IS NULL"));
        execute(c, "INSERT INTO organization_memberships(organization_id,user_id,role,status,created_at,updated_at,row_version) VALUES "
                + "(" + populated + ",800801,'OWNER','ACTIVE','2024-02-29 23:58:57.987654'," + RAW_TIME + ",2),"
                + "(" + shared + ",800802,'OWNER','REVOKED'," + RAW_TIME + "," + RAW_TIME + ",7)");
        execute(c, "INSERT INTO sites(organization_id,name,base_url,status,created_by_user_id,created_at,updated_at,row_version) VALUES "
                + "(" + populated + ",'Populated site','https://populated.invalid/','ACTIVE',800801,'2024-02-29 23:58:57.987654'," + RAW_TIME + ",3),"
                + "(" + shared + ",'Archived site','https://populated.invalid/','ARCHIVED',800802," + RAW_TIME + "," + RAW_TIME + ",5)");
        var legacy = snapshot(c, LEGACY);
        var previous = snapshot(c, PARENTS);
        var oldHistory = snapshot(c, "flyway_schema_history").get("flyway_schema_history");
        Flyway flyway = Flyway.configure().dataSource(url, user, password).target("7").load();
        assertEquals(1, flyway.migrate().migrationsExecuted);
        assertEquals("1,2,3,4,5,6,7", scalar(c, "SELECT GROUP_CONCAT(version ORDER BY installed_rank) FROM flyway_schema_history"));
        assertEquals("7|create audits|SQL|1", scalar(c, "SELECT CONCAT(version,'|',description,'|',type,'|',success) "
                + "FROM flyway_schema_history WHERE version='7'"));
        var newHistory = snapshot(c, "flyway_schema_history").get("flyway_schema_history");
        assertEquals(oldHistory.size() + 1, newHistory.size());
        assertTrue(newHistory.containsAll(oldHistory), "Historical Flyway rows changed");
        assertEquals("0", scalar(c, count("audits")), "V7 must not backfill Audits from Sites or legacy auditorias");
        assertEquals(legacy, snapshot(c, LEGACY));
        assertEquals(previous, snapshot(c, PARENTS));
        var preserved = snapshot(c, "organizations", "organization_memberships", "sites", "audits", "flyway_schema_history");
        assertEquals(0, flyway.migrate().migrationsExecuted);
        assertEquals(preserved, snapshot(c, "organizations", "organization_memberships", "sites", "audits", "flyway_schema_history"));
        assertEquals(legacy, snapshot(c, LEGACY));
        try (SessionFactory ignored = factory(url, user, password)) { /* Hibernate validate on the real V7 schema. */ }
        System.out.println("AUDIT UPGRADE PASS: populated V6 -> V7; one migration; audits empty (no backfill); "
                + "organizations/memberships/sites identical; exact history 1,2,3,4,5,6,7; repeat migrate=0; Hibernate validate");
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
