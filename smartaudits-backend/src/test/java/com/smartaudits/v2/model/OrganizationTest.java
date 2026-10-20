package com.smartaudits.v2.model;

import com.smartaudits.model.*;
import jakarta.persistence.OptimisticLockException;
import org.flywaydb.core.Flyway;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.StaleStateException;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.lang.reflect.Field;
import java.sql.*;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class OrganizationTest {
    private static final Instant NANOS = Instant.parse("2024-02-29T23:58:57.987654321Z");
    private static final Instant MICROS = Instant.parse("2024-02-29T23:58:57.987654Z");

    @Test void normalizesCreationBeforePersistence() {
        Organization org = new Organization("Example", null, NANOS);
        assertEquals(MICROS, org.getCreatedAt());
        assertEquals(MICROS, org.getUpdatedAt());
        assertNull(org.getId());
        assertNull(org.getRowVersion());
    }

    @Test void keepsPersonalOwnerOptionalAndSeparateFromAuthorization() {
        Usuario user = new Usuario();
        user.setId(17L);
        assertSame(user, new Organization("Personal", user, NANOS).getPersonalOwner());
        assertNull(new Organization("Shared", null, NANOS).getPersonalOwner());
    }

    @Test void renamePreservesCreationAndTruncatesUpdate() {
        Organization org = new Organization("Before", null, NANOS);
        org.rename("After", Instant.parse("2024-03-31T02:30:00.123456789Z"));
        assertEquals("After", org.getName());
        assertEquals(MICROS, org.getCreatedAt());
        assertEquals(Instant.parse("2024-03-31T02:30:00.123456Z"), org.getUpdatedAt());
    }

    @Test void invalidRenameIsAtomic() {
        Organization org = new Organization("Before", null, NANOS);
        assertThrows(NullPointerException.class, () -> org.rename("After", null));
        assertEquals("Before", org.getName());
        assertEquals(MICROS, org.getUpdatedAt());
    }

    @Test void rejectsMissingRequiredInputs() {
        assertThrows(NullPointerException.class, () -> new Organization(null, null, NANOS));
        assertThrows(NullPointerException.class, () -> new Organization("Name", null, null));
    }

    @Test void callbacksNormalizeValuesBeforeSql() throws Exception {
        Organization org = new Organization("Example", null, NANOS);
        set(org, "createdAt", NANOS);
        set(org, "updatedAt", NANOS);
        org.normalizeInsert();
        assertEquals(MICROS, org.getCreatedAt());
        assertEquals(MICROS, org.getUpdatedAt());
        set(org, "updatedAt", Instant.parse("2024-03-31T02:30:00.123456789Z"));
        org.normalizeUpdate();
        assertEquals(Instant.parse("2024-03-31T02:30:00.123456Z"), org.getUpdatedAt());
    }

    static void set(Organization org, String field, Object value) throws Exception {
        Field f = Organization.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(org, value);
    }
}

/** Explicit -Dtest only: does not match Surefire's normal Test naming patterns.
 * Real MariaDB contracts live in this same file to keep the four-file scope.
 * No H2 substitutes, Spring service, repository or production provisioning.
 */
class OrganizationMariaDbProbe {
    private static final String[] LEGACY = {"usuarios", "roles", "usuarios_roles", "auditorias", "incidencias",
            "resultados", "historial_auditorias", "historial_acciones_admin"};
    private static final Instant CREATED = Instant.parse("2024-02-29T23:58:57.987654321Z");
    private static final Instant UPDATED = Instant.parse("2024-03-31T02:30:00.123456789Z");

    @Test void isolatedOrganizationContract() throws Exception {
        String url = required("F3_DB_URL");
        assertTrue(url.matches("jdbc:mariadb://127\\.0\\.0\\.1:[0-9]+/f3_harness_[a-f0-9]{32}"));
        assertFalse(url.contains(":3306/"));
        String user = required("F3_DB_USER");
        assertEquals("f3_harness_user", user);
        String zone = required("F3_TIMEZONE");
        assertTrue(Set.of("Asia/Kathmandu", "Europe/Madrid").contains(zone));
        assertEquals(zone, ZoneId.systemDefault().getId());
        String password = required("F3_DB_PASSWORD");
        try (Connection c = DriverManager.getConnection(url, user, password)) {
            assertTrue(c.getMetaData().getDatabaseProductVersion().startsWith("11.2."));
            assertEquals(required("F3_DB_NAME"), c.getCatalog());
            switch (required("F3_ORG_MODE")) {
                case "contracts" -> contracts(c, url, user, password);
                case "seed" -> {
                    try (SessionFactory factory = factory(url, user, password)) { seed(factory, 900001L); }
                    System.out.println("ORGANIZATION SENTINEL PASS: generated PK; owner=900001; UTC micros; version=0");
                }
                case "historical" -> historical(c, url, user, password);
                default -> fail("Unknown Organization probe mode");
            }
        }
    }

    private static String required(String key) { return Objects.requireNonNull(System.getenv(key), "Missing " + key); }

    private static SessionFactory factory(String url, String user, String password) {
        Configuration cfg = new Configuration();
        for (Class<?> entity : List.of(Organization.class, Usuario.class, Rol.class, Auditoria.class,
                Incidencia.class, HistorialAuditoria.class, HistorialAccionAdmin.class)) cfg.addAnnotatedClass(entity);
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

    private static void rejected(Connection c, String sql, int error) {
        SQLException failure = assertThrows(SQLException.class, () -> execute(c, sql));
        assertEquals(error, failure.getErrorCode(), failure.getMessage());
    }

    private static String insert(String name, String owner) {
        return "INSERT INTO organizations(name,personal_owner_user_id,created_at,updated_at,row_version) VALUES ("
                + name + "," + owner + ",'2024-03-31 02:30:00.123456','2024-03-31 02:30:00.123456',0)";
    }

    private static void contracts(Connection c, String url, String user, String password) throws Exception {
        assertEquals("0", scalar(c, "SELECT COUNT(*) FROM organizations"));
        Map<String, List<String>> original = snapshot(c, LEGACY);
        assertEquals("utf8mb4_unicode_520_ci", scalar(c,
                "SELECT TABLE_COLLATION FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='organizations'"));
        assertEquals("utf8mb4|utf8mb4_unicode_520_ci|255|NO", scalar(c,
                "SELECT CONCAT(CHARACTER_SET_NAME,'|',COLLATION_NAME,'|',CHARACTER_MAXIMUM_LENGTH,'|',IS_NULLABLE) "
                        + "FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='organizations' AND column_name='name'"));
        assertEquals("2", scalar(c, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() "
                + "AND table_name='organizations' AND column_name IN ('created_at','updated_at') AND DATA_TYPE='datetime' AND DATETIME_PRECISION=6 AND IS_NULLABLE='NO'"));
        assertEquals("RESTRICT|RESTRICT|usuarios", scalar(c,
                "SELECT CONCAT(DELETE_RULE,'|',UPDATE_RULE,'|',REFERENCED_TABLE_NAME) FROM information_schema.referential_constraints "
                        + "WHERE constraint_schema=DATABASE() AND constraint_name='fk_organizations_personal_owner'"));
        assertEquals("2", scalar(c, "SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.statistics "
                + "WHERE table_schema=DATABASE() AND table_name='organizations'"));
        assertEquals("1", scalar(c, "SELECT _utf8mb4'\u00c1' COLLATE utf8mb4_unicode_520_ci = _utf8mb4'a'"));
        // Dedicated synthetic identity has no other FK dependants: rejection below proves this FK.
        execute(c, "INSERT INTO usuarios(id,email,fecha_registro,nombre,password,role,activo,protegido,token_version,row_version) "
                + "VALUES (800002,'org-probe@f3.invalid','2001-02-03 04:05:06.123456','Org probe','unused','CLIENTE',b'1',b'0',0,0)");
        try (SessionFactory f = factory(url, user, password)) {
            long id;
            try (Session s = f.openSession()) {
                var tx = s.beginTransaction();
                Organization org = new Organization("\u00c1rbol \ud83c\udf33", s.getReference(Usuario.class, 800002L), CREATED);
                // Exercise the JPA callbacks themselves, even if a future hydrator supplied nanos.
                OrganizationTest.set(org, "createdAt", CREATED);
                OrganizationTest.set(org, "updatedAt", CREATED);
                s.persist(org);
                s.flush();
                assertEquals(Instant.parse("2024-02-29T23:58:57.987654Z"), org.getCreatedAt());
                assertEquals(0, org.getUpdatedAt().getNano() % 1000);
                assertNotNull(org.getId());
                assertTrue(org.getId() > 0);
                assertEquals(0L, org.getRowVersion());
                id = org.getId();
                tx.commit();
            }
            assertEquals("2024-02-29 23:58:57.987654|2024-02-29 23:58:57.987654", scalar(c,
                    "SELECT CONCAT(DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s.%f'),'|',DATE_FORMAT(updated_at,'%Y-%m-%d %H:%i:%s.%f')) FROM organizations WHERE id=" + id));
            try (Session s = f.openSession()) {
                Organization org = s.find(Organization.class, id);
                assertEquals("\u00c1rbol \ud83c\udf33", org.getName());
                assertEquals(800002L, org.getPersonalOwner().getId());
                assertEquals(Instant.parse("2024-02-29T23:58:57.987654Z"), org.getCreatedAt());
            }
            rejected(c, insert("'Duplicate'", "800002"), 1062);
            rejected(c, insert("'Invalid FK'", "9223372036854775807"), 1452);
            rejected(c, insert("NULL", "NULL"), 1048);
            rejected(c, insert("'" + "x".repeat(256) + "'", "NULL"), 1406);
            rejected(c, "DELETE FROM usuarios WHERE id=800002", 1451);
            rejected(c, "UPDATE usuarios SET id=800003 WHERE id=800002", 1451);
            // UNIQUE permits multiple NULL owners; raw UTC insertion has an independent JPA oracle.
            execute(c, insert("'No owner'", "NULL"));
            execute(c, insert("'No owner'", "NULL"));
            long rawId = Long.parseLong(scalar(c, "SELECT MAX(id) FROM organizations"));
            try (Session s = f.openSession()) {
                Organization raw = s.find(Organization.class, rawId);
                assertNull(raw.getPersonalOwner());
                assertEquals(Instant.parse("2024-03-31T02:30:00.123456Z"), raw.getCreatedAt());
            }
            try (Session first = f.openSession(); Session stale = f.openSession()) {
                var t1 = first.beginTransaction();
                var t2 = stale.beginTransaction();
                Organization a = first.find(Organization.class, id);
                Organization b = stale.find(Organization.class, id);
                a.rename("Updated", UPDATED);
                OrganizationTest.set(a, "updatedAt", UPDATED);
                OrganizationTest.set(a, "createdAt", UPDATED); // updatable=false must preserve the inserted value.
                t1.commit();
                assertEquals(Instant.parse("2024-03-31T02:30:00.123456Z"), a.getUpdatedAt(),
                        "@PreUpdate must normalize the managed entity in memory, independently of JDBC");
                assertEquals(1L, a.getRowVersion());
                b.rename("Stale write", UPDATED);
                RuntimeException conflict = assertThrows(RuntimeException.class, t2::commit);
                assertTrue(conflict instanceof OptimisticLockException || conflict instanceof StaleStateException,
                        conflict.getClass().getName());
                if (t2.isActive()) t2.rollback();
            }
            assertEquals("2024-02-29 23:58:57.987654|2024-03-31 02:30:00.123456|1", scalar(c,
                    "SELECT CONCAT(DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s.%f'),'|',DATE_FORMAT(updated_at,'%Y-%m-%d %H:%i:%s.%f'),'|',row_version) FROM organizations WHERE id=" + id));
            try (Session s = f.openSession()) {
                Organization updated = s.find(Organization.class, id);
                assertEquals(Instant.parse("2024-03-31T02:30:00.123456Z"), updated.getUpdatedAt());
                var tx = s.beginTransaction();
                s.remove(updated);
                tx.commit();
            }
            assertEquals("1", scalar(c, "SELECT COUNT(*) FROM usuarios WHERE id=800002"));
            execute(c, "DELETE FROM organizations"); // Only the two synthetic NULL-owner rows remain.
            execute(c, "DELETE FROM usuarios WHERE id=800002");
        }
        assertEquals(original, snapshot(c, LEGACY), "Organization operations changed legacy rows/structures");
        System.out.println("ORGANIZATION CONTRACTS PASS: " + ZoneId.systemDefault()
                + "; mapping/PK/FK/NULL/UNIQUE/RESTRICT/no cascade/Unicode/DATETIME(6)/nanos/raw UTC/version/immutable creation");
    }

    private static void seed(SessionFactory f, long owner) {
        try (Session s = f.openSession()) {
            var tx = s.beginTransaction();
            Organization row = new Organization("F3 Organization sentinel", s.getReference(Usuario.class, owner), CREATED);
            s.persist(row);
            tx.commit();
            assertEquals(0L, row.getRowVersion());
        }
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

    private static void historical(Connection c, String url, String user, String password) throws Exception {
        String expectedVersions = required("F3_EXPECTED_VERSIONS");
        assertEquals("1,2,3,4,5,6,7,8,9000", expectedVersions,
                "Historical Organization probe requires the exact reviewed V8 version set");
        assertEquals("0", scalar(c, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()"));
        // Isolated reconstruction of a pre-Flyway V1 schema, then explicit baseline adoption.
        ScriptUtils.executeSqlScript(c, new ClassPathResource("db/migration/V1__initial_schema.sql"));
        execute(c, "INSERT INTO usuarios(id,email,fecha_registro,nombre,password,role,activo,protegido,token_version,row_version) "
                + "VALUES (800001,'historical@f3.invalid','2001-02-03 04:05:06.123456','Historical fixture','unused','CLIENTE',b'1',b'0',0,0)");
        Flyway historical = Flyway.configure().dataSource(url, user, password).baselineVersion("1").target("3").load();
        historical.baseline();
        historical.migrate();
        assertEquals("1,2,3", scalar(c, "SELECT GROUP_CONCAT(version ORDER BY installed_rank) FROM flyway_schema_history"));
        Map<String, List<String>> legacy = snapshot(c, LEGACY);
        List<String> oldHistory = snapshot(c, "flyway_schema_history").get("flyway_schema_history");
        Flyway future = Flyway.configure().dataSource(url, user, password).load();
        future.migrate();
        assertEquals(legacy, snapshot(c, LEGACY), "V4 changed the historical fixture");
        assertEquals("0", scalar(c, "SELECT COUNT(*) FROM sites"), "No legacy Site may be backfilled");
        assertEquals("0", scalar(c, "SELECT COUNT(*) FROM audits"), "No legacy auditoria may be backfilled as an Audit");
        assertEquals("0", scalar(c, "SELECT COUNT(*) FROM scans"), "No legacy resultado may be backfilled as a Scan");
        assertEquals(expectedVersions, scalar(c, "SELECT GROUP_CONCAT(version ORDER BY installed_rank) FROM flyway_schema_history"));
        assertEquals(Integer.toString(expectedVersions.split(",").length), scalar(c, "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1"));
        List<String> newHistory = snapshot(c, "flyway_schema_history").get("flyway_schema_history");
        assertTrue(newHistory.containsAll(oldHistory), "Historical Flyway rows changed");
        contracts(c, url, user, password);
        try (SessionFactory f = factory(url, user, password)) { seed(f, 800001L); }
        Map<String, List<String>> preserved = snapshot(c, "organizations", "flyway_schema_history");
        assertEquals(0, future.migrate().migrationsExecuted);
        assertEquals(preserved, snapshot(c, "organizations", "flyway_schema_history"));
        assertEquals(legacy, snapshot(c, LEGACY));
        System.out.println("HISTORICAL V4 PASS: reconstructed V1 + BASELINE 1 -> " + expectedVersions + "; legacy intact; repeat migrate=0");
    }
}
