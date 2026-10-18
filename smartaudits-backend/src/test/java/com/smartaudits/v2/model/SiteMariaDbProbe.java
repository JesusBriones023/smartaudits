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

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit harness -Dtest entry only: deliberately outside Surefire's *Test patterns.
 * All SQL mutations below are synthetic fixtures in the harness-owned schema, never product
 * lifecycle operations. The session sql_mode is read and asserted, never changed.
 */
class SiteMariaDbProbe {
    private static final String[] LEGACY = {"usuarios", "roles", "usuarios_roles", "auditorias", "incidencias",
            "resultados", "historial_auditorias", "historial_acciones_admin"};
    private static final String VERSIONS = "1,2,3,4,5,6,7,9000";
    private static final String CHILD = "f3_site_child_probe";
    private static final String CHILD_FK = "fk_f3_site_child_probe_site";
    private static final Instant CREATED = Instant.parse("2024-02-29T23:58:57.987654321Z");
    private static final Instant UPDATED = Instant.parse("2024-03-31T02:30:00.123456789Z");
    private static final String ROCKET = "\ud83d\ude80";
    private static final String LITERAL_NAME = "  Caf\u00e9 / Cafe\u0301 \ud83c\udf33\t";
    private static final String LITERAL_URL = "  HTTPS://Ejemplo.ES:443/Ruta/../%7Euser?q=\u00d1&Q=N\u0303#Fragmento \ud83d\udd12  ";

    @Test void isolatedSiteContract() throws Exception {
        String url = required("F3_DB_URL");
        String database = required("F3_DB_NAME");
        assertTrue(database.matches("f3_harness_[a-f0-9]{32}"));
        assertTrue(url.matches("jdbc:mariadb://127\\.0\\.0\\.1:[0-9]+/" + database));
        assertFalse(url.contains(":3306/"));
        assertTrue(required("F3_SITE_HARNESS_RUN_ID").matches("[a-f0-9]{32}"));
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
            switch (required("F3_SITE_MODE")) {
                case "contracts" -> contracts(c, url, user, password);
                case "tenant" -> tenant(c);
                case "seed" -> seed(c, url, user, password);
                case "upgrade" -> upgrade(c, url, user, password);
                default -> fail("Unknown Site probe mode");
            }
            assertEquals(globalMode, scalar(c, "SELECT @@GLOBAL.sql_mode"), "Global sql_mode changed");
        }
    }

    private static String required(String name) {
        return Objects.requireNonNull(System.getenv(name), "Missing " + name);
    }

    /** 1406 length proofs require strict mode: verified per session, never configured here. */
    private static void strict(Connection c) throws SQLException {
        String mode = scalar(c, "SELECT @@SESSION.sql_mode");
        assertTrue(Arrays.asList(mode.split(",")).contains("STRICT_TRANS_TABLES")
                || Arrays.asList(mode.split(",")).contains("STRICT_ALL_TABLES"), "Strict SQL mode required: " + mode);
    }

    private static SessionFactory factory(String url, String user, String password) {
        Configuration cfg = new Configuration();
        for (Class<?> entity : List.of(Site.class, OrganizationMembership.class, Organization.class, Usuario.class,
                Rol.class, Auditoria.class, Incidencia.class, HistorialAuditoria.class, HistorialAccionAdmin.class)) {
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

    private static String hex(String value) {
        return HexFormat.of().withUpperCase().formatHex(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void identity(Connection c, long id) throws SQLException {
        execute(c, "INSERT INTO usuarios(id,email,fecha_registro,nombre,password,role,activo,protegido,token_version,row_version) "
                + "VALUES (" + id + ",'site-" + id + "@f3.invalid','2001-02-03 04:05:06.123456',"
                + "'Site fixture','unused','CLIENTE',b'1',b'0',0,0)");
    }

    private static long organization(Connection c, String name) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("INSERT INTO organizations(name,personal_owner_user_id,created_at,updated_at,row_version) "
                + "VALUES (?,NULL,'2024-03-31 02:30:00.123456','2024-03-31 02:30:00.123456',0)", Statement.RETURN_GENERATED_KEYS)) {
            p.setString(1, name);
            assertEquals(1, p.executeUpdate());
            try (ResultSet keys = p.getGeneratedKeys()) { assertTrue(keys.next()); return keys.getLong(1); }
        }
    }

    /** Bound parameters keep fixture bytes independent of SQL escaping; HEX checks prove storage. */
    private static long insert(Connection c, long organization, String name, String baseUrl, String status, long creator) throws SQLException {
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

    private static void rejectedInsert(Connection c, int code, long organization, String name, String baseUrl, String status, long creator) {
        SQLException error = assertThrows(SQLException.class, () -> insert(c, organization, name, baseUrl, status, creator));
        assertEquals(code, error.getErrorCode(), error.getMessage());
    }

    /** Raw stored bytes, character count and JDBC text must all equal the original Java string. */
    private static void stored(Connection c, long id, String name, String baseUrl) throws SQLException {
        assertEquals(hex(name) + "|" + hex(baseUrl) + "|" + name.codePointCount(0, name.length()) + "|"
                        + baseUrl.codePointCount(0, baseUrl.length()), scalar(c,
                "SELECT CONCAT(HEX(name),'|',HEX(base_url),'|',CHAR_LENGTH(name),'|',CHAR_LENGTH(base_url)) FROM sites WHERE id=" + id));
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT name,base_url FROM sites WHERE id=" + id)) {
            assertTrue(r.next());
            assertEquals(name, r.getString(1));
            assertEquals(baseUrl, r.getString(2));
        }
    }

    private static String time(Connection c, long id) throws SQLException {
        return scalar(c, "SELECT CONCAT(DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s.%f'),'|',"
                + "DATE_FORMAT(updated_at,'%Y-%m-%d %H:%i:%s.%f'),'|',row_version) FROM sites WHERE id=" + id);
    }

    private static String where(String view) {
        return "FROM information_schema." + view + " WHERE table_schema=DATABASE() AND table_name='sites'";
    }

    private static void schema(Connection c) throws SQLException {
        assertEquals("id,organization_id,name,base_url,status,created_by_user_id,created_at,updated_at,row_version",
                scalar(c, "SELECT GROUP_CONCAT(column_name ORDER BY ordinal_position) " + where("columns")));
        assertEquals("id:bigint(20):NO:NULL:auto_increment,organization_id:bigint(20):NO:NULL:,name:varchar(255):NO:NULL:,"
                + "base_url:varchar(2048):NO:NULL:,status:varchar(16):NO:NULL:,created_by_user_id:bigint(20):NO:NULL:,"
                + "created_at:datetime(6):NO:NULL:,updated_at:datetime(6):NO:NULL:,row_version:bigint(20):NO:0:",
                scalar(c, "SELECT GROUP_CONCAT(CONCAT(column_name,':',column_type,':',is_nullable,':',IFNULL(column_default,'NULL'),':',extra) "
                        + "ORDER BY ordinal_position) " + where("columns")));
        assertEquals("name:utf8mb4:utf8mb4_unicode_520_ci:255,base_url:utf8mb4:utf8mb4_unicode_520_ci:2048,"
                + "status:utf8mb4:utf8mb4_unicode_520_ci:16", scalar(c, "SELECT GROUP_CONCAT(CONCAT(column_name,':',"
                + "character_set_name,':',collation_name,':',character_maximum_length) ORDER BY ordinal_position) "
                + where("columns") + " AND character_set_name IS NOT NULL"));
        assertEquals("2", scalar(c, "SELECT COUNT(*) " + where("columns")
                + " AND column_name IN ('created_at','updated_at') AND data_type='datetime' AND datetime_precision=6"));
        assertEquals("InnoDB|utf8mb4_unicode_520_ci", scalar(c, "SELECT CONCAT(engine,'|',table_collation) " + where("tables")));
        assertEquals("3", scalar(c, "SELECT COUNT(DISTINCT index_name) " + where("statistics")));
        assertEquals("0:id", scalar(c, "SELECT CONCAT(MAX(non_unique),':',GROUP_CONCAT(column_name ORDER BY seq_in_index)) "
                + where("statistics") + " AND index_name='PRIMARY'"));
        assertEquals("0:organization_id,id", scalar(c, "SELECT CONCAT(MAX(non_unique),':',GROUP_CONCAT(column_name ORDER BY seq_in_index)) "
                + where("statistics") + " AND index_name='uq_sites_org_site'"));
        assertEquals("1:created_by_user_id", scalar(c, "SELECT CONCAT(MAX(non_unique),':',GROUP_CONCAT(column_name ORDER BY seq_in_index)) "
                + where("statistics") + " AND index_name='ix_sites_created_by'"));
        assertEquals("CHECK:ck_sites_status,FOREIGN KEY:fk_sites_created_by,FOREIGN KEY:fk_sites_organization,"
                + "PRIMARY KEY:PRIMARY,UNIQUE:uq_sites_org_site", scalar(c, "SELECT GROUP_CONCAT(CONCAT(constraint_type,':',constraint_name) "
                + "ORDER BY constraint_type,constraint_name) " + where("table_constraints")));
        assertEquals("RESTRICT|RESTRICT|organizations", scalar(c, "SELECT CONCAT(delete_rule,'|',update_rule,'|',referenced_table_name) "
                + where("referential_constraints").replace("table_schema", "constraint_schema") + " AND constraint_name='fk_sites_organization'"));
        assertEquals("RESTRICT|RESTRICT|usuarios", scalar(c, "SELECT CONCAT(delete_rule,'|',update_rule,'|',referenced_table_name) "
                + where("referential_constraints").replace("table_schema", "constraint_schema") + " AND constraint_name='fk_sites_created_by'"));
        assertEquals("fk_sites_created_by:created_by_user_id:usuarios:id,fk_sites_organization:organization_id:organizations:id",
                scalar(c, "SELECT GROUP_CONCAT(CONCAT(constraint_name,':',column_name,':',referenced_table_name,':',referenced_column_name) "
                        + "ORDER BY constraint_name) " + where("key_column_usage") + " AND referenced_table_name IS NOT NULL"));
        assertEquals("ck_sites_status|1", scalar(c, "SELECT CONCAT(GROUP_CONCAT(constraint_name),'|',SUM(LOCATE('binary',LOWER(check_clause))>0)) "
                + where("check_constraints").replace("table_schema", "constraint_schema")));
        assertEquals("0", scalar(c, "SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE()"));
    }

    private static void contracts(Connection c, String url, String user, String password) throws Exception {
        assertEquals("0", scalar(c, "SELECT COUNT(*) FROM sites"));
        schema(c);
        Map<String, List<String>> legacy = snapshot(c, LEGACY);
        Map<String, List<String>> previous = snapshot(c, "organizations", "organization_memberships");
        identity(c, 800301L);
        identity(c, 800302L);
        try (SessionFactory f = factory(url, user, password)) {
            long firstOrg;
            long secondOrg;
            try (Session s = f.openSession()) {
                s.doWork(SiteMariaDbProbe::strict);
                var tx = s.beginTransaction();
                Organization one = new Organization("Site uno", null, CREATED);
                Organization two = new Organization("Site dos", null, CREATED);
                s.persist(one);
                s.persist(two);
                tx.commit();
                firstOrg = one.getId();
                secondOrg = two.getId();
            }
            Map<String, List<String>> parents = snapshot(c, "usuarios", "organizations");
            long siteId;
            try (Session s = f.openSession()) {
                var tx = s.beginTransaction();
                Site site = new Site(s.getReference(Organization.class, firstOrg), LITERAL_NAME, LITERAL_URL,
                        s.getReference(Usuario.class, 800301L), CREATED);
                // Exercise the JPA callbacks themselves, even if a future hydrator supplied nanos.
                SiteTest.set(site, "createdAt", CREATED);
                SiteTest.set(site, "updatedAt", CREATED);
                s.persist(site);
                s.flush();
                assertTrue(site.getId() > 0);
                assertEquals(0L, site.getRowVersion());
                assertEquals(Instant.parse("2024-02-29T23:58:57.987654Z"), site.getCreatedAt());
                assertEquals(site.getCreatedAt(), site.getUpdatedAt());
                siteId = site.getId();
                tx.commit();
            }
            assertEquals("2024-02-29 23:58:57.987654|2024-02-29 23:58:57.987654|0", time(c, siteId));
            stored(c, siteId, LITERAL_NAME, LITERAL_URL);
            try (Session s = f.openSession()) {
                Site row = s.find(Site.class, siteId);
                assertFalse(Hibernate.isInitialized(row.getOrganization()), "Organization must load lazily");
                assertFalse(Hibernate.isInitialized(row.getCreatedBy()), "createdBy must load lazily");
                assertEquals(LITERAL_NAME, row.getName());
                assertEquals(LITERAL_URL, row.getBaseUrl());
                assertEquals(SiteStatus.ACTIVE, row.getStatus());
                assertEquals(firstOrg, row.getOrganization().getId());
                assertEquals(800301L, row.getCreatedBy().getId());
                assertEquals(Instant.parse("2024-02-29T23:58:57.987654Z"), row.getCreatedAt());
            }
            // Synthetic ARCHIVED row: no product archive API exists. Raw UTC has an independent JPA oracle.
            long archived = insert(c, secondOrg, "Archived", "https://archived.invalid/", "ARCHIVED", 800302L);
            try (Session s = f.openSession()) {
                Site row = s.find(Site.class, archived);
                assertEquals(SiteStatus.ARCHIVED, row.getStatus());
                assertEquals(Instant.parse("2024-03-31T02:30:00.123456Z"), row.getCreatedAt());
                assertEquals(row.getCreatedAt(), row.getUpdatedAt());
                assertEquals(secondOrg, row.getOrganization().getId());
            }
            // Literal URLs are not identities: duplicates within and across organizations are accepted.
            long sameOrg = insert(c, firstOrg, "Duplicate", LITERAL_URL, "ACTIVE", 800302L);
            long otherOrg = insert(c, secondOrg, "Other tenant", LITERAL_URL, "ARCHIVED", 800301L);
            assertEquals("3", scalar(c, "SELECT COUNT(*) FROM sites WHERE HEX(base_url)='" + hex(LITERAL_URL) + "'"));
            stored(c, sameOrg, "Duplicate", LITERAL_URL);
            stored(c, otherOrg, "Other tenant", LITERAL_URL);
            long lower = insert(c, firstOrg, "Variant", "https://ejemplo.es", "ACTIVE", 800301L);
            long upper = insert(c, firstOrg, "Variant", "HTTPS://EJEMPLO.ES ", "ACTIVE", 800301L);
            stored(c, lower, "Variant", "https://ejemplo.es");
            stored(c, upper, "Variant", "HTTPS://EJEMPLO.ES ");
            long empty = insert(c, secondOrg, "", "", "ACTIVE", 800302L); // Structural only; services must reject blanks.
            stored(c, empty, "", "");
            // Length limits count characters, also for four-byte code points (session strict mode verified).
            String prefix = "https://e.invalid/";
            for (String name : List.of("x".repeat(255), ROCKET.repeat(255))) stored(c, insert(c, firstOrg, name, prefix, "ACTIVE", 800301L), name, prefix);
            for (String name : List.of("x".repeat(256), ROCKET.repeat(256))) rejectedInsert(c, 1406, firstOrg, name, prefix, "ACTIVE", 800301L);
            List<String> maxUrls = List.of(prefix + "a".repeat(2048 - prefix.length()), prefix + ROCKET.repeat(2048 - prefix.length()));
            for (String baseUrl : maxUrls) {
                assertEquals(2048, baseUrl.codePointCount(0, baseUrl.length()));
                long id = insert(c, firstOrg, "Max URL", baseUrl, "ACTIVE", 800301L);
                stored(c, id, "Max URL", baseUrl);
                try (Session s = f.openSession()) { assertEquals(baseUrl, s.find(Site.class, id).getBaseUrl()); }
            }
            for (String baseUrl : maxUrls) rejectedInsert(c, 1406, firstOrg, "Too long", baseUrl + "b", "ACTIVE", 800301L);
            String[] values = {Long.toString(firstOrg), "'Null'", "'https://null.invalid/'", "'ACTIVE'", "800301",
                    "'2024-03-31 02:30:00.123456'", "'2024-03-31 02:30:00.123456'", "0"};
            for (int i = 0; i < values.length; i++) {
                String[] missing = values.clone();
                missing[i] = "NULL";
                rejected(c, "INSERT INTO sites(organization_id,name,base_url,status,created_by_user_id,created_at,updated_at,row_version) VALUES ("
                        + String.join(",", missing) + ")", 1048);
            }
            for (String status : List.of("active", "Active", "archived", "Archived", "ACTIVE ", " ACTIVE", "ARCHIVED ",
                    "DELETED", "", "\u00c1CTIVE", "ACT\u0130VE", "ARCH\u0130VED", "ACTIVE\u00a0", "\uff21CTIVE", "ARCHIVED\u200b")) {
                rejectedInsert(c, 4025, firstOrg, "Invalid status", prefix, status, 800301L);
            }
            rejectedInsert(c, 1452, Long.MAX_VALUE, "Missing organization", prefix, "ACTIVE", 800301L);
            rejectedInsert(c, 1452, firstOrg, "Missing creator", prefix, "ACTIVE", Long.MAX_VALUE);
            rejectedBy(c, "DELETE FROM organizations WHERE id=" + firstOrg, 1451, "fk_sites_organization");
            rejectedBy(c, "UPDATE organizations SET id=9223372036854775807 WHERE id=" + firstOrg, 1451, "fk_sites_organization");
            rejectedBy(c, "DELETE FROM usuarios WHERE id=800301", 1451, "fk_sites_created_by");
            rejectedBy(c, "UPDATE usuarios SET id=800303 WHERE id=800301", 1451, "fk_sites_created_by");
            try (Session first = f.openSession(); Session stale = f.openSession()) {
                var t1 = first.beginTransaction();
                var t2 = stale.beginTransaction();
                Site a = first.find(Site.class, siteId);
                Site b = stale.find(Site.class, siteId);
                // Test-only hydration simulates a dirty entity; no product rename/archive API exists.
                SiteTest.set(a, "name", "Renamed fixture");
                SiteTest.set(a, "status", SiteStatus.ARCHIVED);
                SiteTest.set(a, "updatedAt", UPDATED);
                SiteTest.set(a, "createdAt", UPDATED);
                SiteTest.set(a, "baseUrl", "https://changed.invalid/");
                SiteTest.set(a, "organization", first.getReference(Organization.class, secondOrg));
                SiteTest.set(a, "createdBy", first.getReference(Usuario.class, 800302L));
                t1.commit();
                assertEquals(1L, a.getRowVersion());
                assertEquals(Instant.parse("2024-03-31T02:30:00.123456Z"), a.getUpdatedAt(),
                        "@PreUpdate must normalize the managed entity in memory, independently of JDBC");
                SiteTest.set(b, "name", "Stale write");
                SiteTest.set(b, "updatedAt", UPDATED);
                RuntimeException conflict = assertThrows(RuntimeException.class, t2::commit);
                assertTrue(conflict instanceof OptimisticLockException || conflict instanceof StaleStateException,
                        conflict.getClass().getName());
                if (t2.isActive()) t2.rollback();
            }
            assertEquals("2024-02-29 23:58:57.987654|2024-03-31 02:30:00.123456|1", time(c, siteId));
            assertEquals(firstOrg + "|800301|ARCHIVED|" + hex(LITERAL_URL) + "|" + hex("Renamed fixture"), scalar(c,
                    "SELECT CONCAT(organization_id,'|',created_by_user_id,'|',status,'|',HEX(base_url),'|',HEX(name)) FROM sites WHERE id=" + siteId));
            try (Session s = f.openSession()) {
                Site row = s.find(Site.class, siteId);
                assertEquals(Instant.parse("2024-03-31T02:30:00.123456Z"), row.getUpdatedAt());
                assertEquals(LITERAL_URL, row.getBaseUrl());
                var tx = s.beginTransaction();
                s.remove(row); // Synthetic cleanup also proves absence of cascade REMOVE to either parent.
                tx.commit();
            }
            assertEquals(parents, snapshot(c, "usuarios", "organizations"));
            execute(c, "DELETE FROM sites WHERE organization_id IN (" + firstOrg + "," + secondOrg + ")");
            execute(c, "DELETE FROM organizations WHERE id IN (" + firstOrg + "," + secondOrg + ")");
        }
        execute(c, "DELETE FROM usuarios WHERE id IN (800301,800302)");
        assertEquals("0", scalar(c, "SELECT COUNT(*) FROM sites"));
        assertEquals(legacy, snapshot(c, LEGACY));
        assertEquals(previous, snapshot(c, "organizations", "organization_memberships"));
        System.out.println("SITE CONTRACTS PASS: " + ZoneId.systemDefault()
                + "; 9 columns/3 indexes/2 FK RESTRICT/binary CHECK/ACTIVE/ARCHIVED/strict 255+2048 chars/literal URL HEX/"
                + "duplicate URLs/no cascade/UTC micros/version/immutable identity+baseUrl; fixtures removed");
    }

    private static String child(long organization, long site) {
        return "INSERT INTO " + CHILD + "(organization_id,site_id) VALUES (" + organization + "," + site + ")";
    }

    private static String countTable(Connection c, String name) throws SQLException {
        return scalar(c, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='" + name + "'");
    }

    /** Drops only this probe's own synthetic table, never another object. */
    private static void dropChild(Connection c) throws SQLException {
        if (!c.getAutoCommit()) {
            c.rollback();
            c.setAutoCommit(true);
        }
        if ("1".equals(countTable(c, CHILD))) {
            execute(c, "DELETE FROM " + CHILD);
            execute(c, "DROP TABLE " + CHILD);
        }
    }

    /** Real composite-FK proof of the V7 pattern audits(organization_id, site_id) -> sites(organization_id, id). */
    private static void tenant(Connection c) throws SQLException {
        assertEquals("0", scalar(c, "SELECT COUNT(*) FROM sites"));
        assertEquals("0", countTable(c, CHILD), "Residual synthetic child table");
        Map<String, List<String>> legacy = snapshot(c, LEGACY);
        Map<String, List<String>> previous = snapshot(c, "organizations", "organization_memberships");
        identity(c, 800401L);
        long orgA = organization(c, "Tenant A");
        long orgB = organization(c, "Tenant B");
        long siteA = insert(c, orgA, "Site A", "https://tenant.invalid/", "ACTIVE", 800401L);
        long siteB = insert(c, orgB, "Site B", "https://tenant.invalid/", "ACTIVE", 800401L);
        Throwable primary = null;
        try {
            execute(c, "CREATE TABLE " + CHILD + " (organization_id BIGINT NOT NULL, site_id BIGINT NOT NULL, "
                    + "CONSTRAINT " + CHILD_FK + " FOREIGN KEY (organization_id, site_id) REFERENCES sites (organization_id, id) "
                    + "ON DELETE RESTRICT ON UPDATE RESTRICT) ENGINE=InnoDB");
            assertEquals("organization_id:organization_id,site_id:id", scalar(c, "SELECT GROUP_CONCAT(CONCAT(column_name,':',"
                    + "referenced_column_name) ORDER BY ordinal_position) FROM information_schema.key_column_usage "
                    + "WHERE table_schema=DATABASE() AND table_name='" + CHILD + "' AND referenced_table_name='sites'"));
            execute(c, child(orgA, siteA));
            rejectedBy(c, child(orgB, siteA), 1452, CHILD_FK);
            rejectedBy(c, child(orgA, siteB), 1452, CHILD_FK);
            rejectedBy(c, "UPDATE " + CHILD + " SET organization_id=" + orgB, 1452, CHILD_FK);
            rejectedBy(c, "UPDATE sites SET organization_id=" + orgB + " WHERE id=" + siteA, 1451, CHILD_FK);
            rejectedBy(c, "DELETE FROM sites WHERE id=" + siteA, 1451, CHILD_FK);
            rejectedBy(c, "UPDATE organizations SET id=9223372036854775807 WHERE id=" + orgA, 1451, "fk_sites_organization");
            assertEquals(orgA + "|" + siteA, scalar(c, "SELECT CONCAT(organization_id,'|',site_id) FROM " + CHILD));
            assertEquals(Long.toString(orgA), scalar(c, "SELECT organization_id FROM sites WHERE id=" + siteA));
            execute(c, "DELETE FROM " + CHILD);
            assertEquals("0", scalar(c, "SELECT COUNT(*) FROM " + CHILD));
            // Without the child row the same tenant change succeeds, so the 1451 above came from the composite FK.
            c.setAutoCommit(false);
            execute(c, "UPDATE sites SET organization_id=" + orgB + " WHERE id=" + siteA);
            assertEquals(Long.toString(orgB), scalar(c, "SELECT organization_id FROM sites WHERE id=" + siteA));
            c.rollback();
            c.setAutoCommit(true);
            assertEquals(Long.toString(orgA), scalar(c, "SELECT organization_id FROM sites WHERE id=" + siteA));
        } catch (Throwable failure) {
            primary = failure;
            throw failure;
        } finally {
            try {
                dropChild(c);
            } catch (SQLException cleanup) {
                if (primary == null) throw cleanup;
                primary.addSuppressed(cleanup);
            }
        }
        assertEquals("0", countTable(c, CHILD), "Synthetic child table was not removed");
        execute(c, "DELETE FROM sites WHERE id IN (" + siteA + "," + siteB + ")");
        execute(c, "DELETE FROM organizations WHERE id IN (" + orgA + "," + orgB + ")");
        execute(c, "DELETE FROM usuarios WHERE id=800401");
        assertEquals("0", scalar(c, "SELECT COUNT(*) FROM sites"));
        assertEquals(legacy, snapshot(c, LEGACY));
        assertEquals(previous, snapshot(c, "organizations", "organization_memberships"));
        System.out.println("SITE TENANT FK PASS: (organization_id, site_id) -> sites(organization_id, id); same tenant accepted; "
                + "cross-tenant 1452; parent tenant change/delete 1451; child rows and own table removed");
    }

    private static void seed(Connection c, String url, String user, String password) throws SQLException {
        assertEquals("0", scalar(c, "SELECT COUNT(*) FROM sites"));
        assertEquals("1", scalar(c, "SELECT COUNT(*) FROM organizations"));
        long orgId = Long.parseLong(scalar(c, "SELECT id FROM organizations"));
        try (SessionFactory f = factory(url, user, password); Session s = f.openSession()) {
            var tx = s.beginTransaction();
            Site site = new Site(s.getReference(Organization.class, orgId), "F3 Site sentinel",
                    "https://sentinel.f3.invalid/", s.getReference(Usuario.class, 900001L), CREATED);
            s.persist(site);
            tx.commit();
            assertEquals(0L, site.getRowVersion());
        }
        insert(c, orgId, "F3 archived Site sentinel", "https://sentinel.f3.invalid/", "ARCHIVED", 900002L);
        assertEquals("ACTIVE,ARCHIVED", scalar(c, "SELECT GROUP_CONCAT(status ORDER BY status) FROM sites"));
        assertEquals("2024-02-29 23:58:57.987654|2024-02-29 23:58:57.987654|0", scalar(c,
                "SELECT CONCAT(DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s.%f'),'|',DATE_FORMAT(updated_at,'%Y-%m-%d %H:%i:%s.%f'),'|',row_version) "
                        + "FROM sites WHERE status='ACTIVE'"));
        System.out.println("SITE SENTINELS PASS: ACTIVE (JPA, creator 900001) + ARCHIVED (synthetic SQL, creator 900002); same URL; UTC micros; version=0");
    }

    private static void upgrade(Connection c, String url, String user, String password) throws SQLException {
        assertEquals("0", scalar(c, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()"));
        Flyway.configure().dataSource(url, user, password).target("5").load().migrate();
        assertEquals("1,2,3,4,5", scalar(c, "SELECT GROUP_CONCAT(version ORDER BY installed_rank) FROM flyway_schema_history"));
        assertEquals("0", countTable(c, "sites"));
        identity(c, 800501L);
        identity(c, 800502L);
        execute(c, "INSERT INTO organizations(name,personal_owner_user_id,created_at,updated_at,row_version) VALUES "
                + "('Populated V5',800501,'2024-02-29 23:58:57.987654','2024-03-31 02:30:00.123456',4),"
                + "('Shared V5',NULL,'2024-03-31 02:30:00.123456','2024-03-31 02:30:00.123456',0)");
        long populated = Long.parseLong(scalar(c, "SELECT id FROM organizations WHERE personal_owner_user_id=800501"));
        long shared = Long.parseLong(scalar(c, "SELECT id FROM organizations WHERE personal_owner_user_id IS NULL"));
        execute(c, "INSERT INTO organization_memberships(organization_id,user_id,role,status,created_at,updated_at,row_version) VALUES "
                + "(" + populated + ",800501,'OWNER','ACTIVE','2024-02-29 23:58:57.987654','2024-03-31 02:30:00.123456',2),"
                + "(" + shared + ",800502,'OWNER','REVOKED','2024-03-31 02:30:00.123456','2024-03-31 02:30:00.123456',7)");
        var legacy = snapshot(c, LEGACY);
        var previous = snapshot(c, "organizations", "organization_memberships");
        var oldHistory = snapshot(c, "flyway_schema_history").get("flyway_schema_history");
        Flyway flyway = Flyway.configure().dataSource(url, user, password).target("6").load();
        assertEquals(1, flyway.migrate().migrationsExecuted);
        assertEquals("1,2,3,4,5,6", scalar(c, "SELECT GROUP_CONCAT(version ORDER BY installed_rank) FROM flyway_schema_history"));
        assertEquals("6|create sites|SQL|1", scalar(c, "SELECT CONCAT(version,'|',description,'|',type,'|',success) "
                + "FROM flyway_schema_history WHERE version='6'"));
        var newHistory = snapshot(c, "flyway_schema_history").get("flyway_schema_history");
        assertEquals(oldHistory.size() + 1, newHistory.size());
        assertTrue(newHistory.containsAll(oldHistory), "Historical Flyway rows changed");
        assertEquals("1", countTable(c, "sites"));
        assertEquals("0", countTable(c, "audits"), "Target 6 must not apply V7");
        assertEquals("0", scalar(c, "SELECT COUNT(*) FROM sites")); // No backfill from organizations or memberships.
        assertEquals(legacy, snapshot(c, LEGACY));
        assertEquals(previous, snapshot(c, "organizations", "organization_memberships"));
        var preserved = snapshot(c, "organizations", "organization_memberships", "sites", "flyway_schema_history");
        assertEquals(0, flyway.migrate().migrationsExecuted);
        assertEquals(preserved, snapshot(c, "organizations", "organization_memberships", "sites", "flyway_schema_history"));
        assertEquals(legacy, snapshot(c, LEGACY));
        try (SessionFactory ignored = factory(url, user, password)) { /* Hibernate validate on the real V6 schema. */ }
        System.out.println("SITE UPGRADE PASS: populated V5 -> V6; one migration; sites empty (no backfill); "
                + "organizations/memberships identical; exact history 1,2,3,4,5,6; repeat migrate=0; Hibernate validate");
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
