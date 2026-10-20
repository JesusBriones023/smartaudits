package com.smartaudits.v2.model;

import com.smartaudits.model.*;
import jakarta.persistence.OptimisticLockException;
import org.flywaydb.core.Flyway;
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
 * All SQL mutations below are synthetic fixtures, never product lifecycle operations.
 */
class OrganizationMembershipMariaDbProbe {
    private static final String[] LEGACY = {"usuarios", "roles", "usuarios_roles", "auditorias", "incidencias",
            "resultados", "historial_auditorias", "historial_acciones_admin"};
    private static final String VERSIONS = "1,2,3,4,5,6,7,8,9000";
    private static final Instant CREATED = Instant.parse("2024-02-29T23:58:57.987654321Z");
    private static final Instant UPDATED = Instant.parse("2024-03-31T02:30:00.123456789Z");

    @Test void isolatedMembershipContract() throws Exception {
        String url = required("F3_DB_URL");
        String database = required("F3_DB_NAME");
        assertTrue(database.matches("f3_harness_[a-f0-9]{32}"));
        assertTrue(url.matches("jdbc:mariadb://127\\.0\\.0\\.1:[0-9]+/" + database));
        assertFalse(url.contains(":3306/"));
        assertTrue(required("F3_MEMBERSHIP_HARNESS_RUN_ID").matches("[a-f0-9]{32}"));
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
            switch (required("F3_MEMBERSHIP_MODE")) {
                case "contracts" -> contracts(c, url, user, password);
                case "seed" -> seed(c, url, user, password);
                case "upgrade" -> upgrade(c, url, user, password);
                default -> fail("Unknown Membership probe mode");
            }
        }
    }

    private static String required(String name) {
        return Objects.requireNonNull(System.getenv(name), "Missing " + name);
    }

    private static SessionFactory factory(String url, String user, String password) {
        Configuration cfg = new Configuration();
        for (Class<?> entity : List.of(OrganizationMembership.class, Organization.class, Usuario.class, Rol.class,
                Auditoria.class, Incidencia.class, HistorialAuditoria.class, HistorialAccionAdmin.class)) {
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

    private static String insert(long organization, long user, String role, String status) {
        // All values are fixed synthetic fixtures, escaped independently of the connection charset.
        return "INSERT INTO organization_memberships(organization_id,user_id,role,status,created_at,updated_at,row_version) VALUES ("
                + organization + "," + user + ",'" + role.replace("'", "''") + "','" + status.replace("'", "''")
                + "','2024-03-31 02:30:00.123456','2024-03-31 02:30:00.123456',0)";
    }

    private static void identity(Connection c, long id) throws SQLException {
        execute(c, "INSERT INTO usuarios(id,email,fecha_registro,nombre,password,role,activo,protegido,token_version,row_version) "
                + "VALUES (" + id + ",'membership-" + id + "@f3.invalid','2001-02-03 04:05:06.123456',"
                + "'Membership fixture','unused','CLIENTE',b'1',b'0',0,0)");
    }

    private static void schema(Connection c) throws SQLException {
        assertEquals("id,organization_id,user_id,role,status,created_at,updated_at,row_version", scalar(c,
                "SELECT GROUP_CONCAT(column_name ORDER BY ordinal_position) FROM information_schema.columns "
                        + "WHERE table_schema=DATABASE() AND table_name='organization_memberships'"));
        assertEquals("0", scalar(c, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() "
                + "AND table_name='organization_memberships' AND is_nullable<>'NO'"));
        assertEquals("4", scalar(c, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() "
                + "AND table_name='organization_memberships' AND column_name IN ('id','organization_id','user_id','row_version') "
                + "AND column_type='bigint(20)'"));
        assertEquals("auto_increment", scalar(c, "SELECT extra FROM information_schema.columns WHERE table_schema=DATABASE() "
                + "AND table_name='organization_memberships' AND column_name='id'"));
        assertEquals("0", scalar(c, "SELECT column_default FROM information_schema.columns WHERE table_schema=DATABASE() "
                + "AND table_name='organization_memberships' AND column_name='row_version'"));
        assertEquals("2", scalar(c, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() "
                + "AND table_name='organization_memberships' AND column_name IN ('role','status') AND data_type='varchar' "
                + "AND character_maximum_length=16 AND character_set_name='utf8mb4' AND collation_name='utf8mb4_unicode_520_ci' "
                + "AND column_default IS NULL"));
        assertEquals("2", scalar(c, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() "
                + "AND table_name='organization_memberships' AND column_name IN ('created_at','updated_at') "
                + "AND data_type='datetime' AND datetime_precision=6 AND column_default IS NULL"));
        assertEquals("InnoDB|utf8mb4_unicode_520_ci", scalar(c, "SELECT CONCAT(engine,'|',table_collation) "
                + "FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='organization_memberships'"));
        assertEquals("3", scalar(c, "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics "
                + "WHERE table_schema=DATABASE() AND table_name='organization_memberships'"));
        assertEquals("organization_id,user_id", scalar(c, "SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) "
                + "FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='organization_memberships' "
                + "AND index_name='uq_organization_memberships_org_user' AND non_unique=0"));
        assertEquals("user_id", scalar(c, "SELECT column_name FROM information_schema.statistics WHERE table_schema=DATABASE() "
                + "AND table_name='organization_memberships' AND index_name='ix_organization_memberships_user'"));
        assertEquals("2", scalar(c, "SELECT COUNT(*) FROM information_schema.referential_constraints "
                + "WHERE constraint_schema=DATABASE() AND table_name='organization_memberships' "
                + "AND delete_rule='RESTRICT' AND update_rule='RESTRICT'"));
        assertEquals("organization_id:organizations:id,user_id:usuarios:id", scalar(c,
                "SELECT GROUP_CONCAT(CONCAT(column_name,':',referenced_table_name,':',referenced_column_name) ORDER BY column_name) "
                        + "FROM information_schema.key_column_usage WHERE table_schema=DATABASE() "
                        + "AND table_name='organization_memberships' AND referenced_table_name IS NOT NULL"));
    }

    private static void contracts(Connection c, String url, String user, String password) throws Exception {
        assertEquals("0", scalar(c, "SELECT COUNT(*) FROM organization_memberships"));
        schema(c);
        Map<String, List<String>> legacy = snapshot(c, LEGACY);
        Map<String, List<String>> organizations = snapshot(c, "organizations");
        identity(c, 800101L);
        identity(c, 800102L);
        try (SessionFactory f = factory(url, user, password)) {
            long firstOrg;
            long secondOrg;
            try (Session s = f.openSession()) {
                var tx = s.beginTransaction();
                Organization one = new Organization("Membership uno", null, CREATED);
                Organization two = new Organization("Membership dos", null, CREATED);
                s.persist(one);
                s.persist(two);
                tx.commit();
                firstOrg = one.getId();
                secondOrg = two.getId();
            }
            Map<String, List<String>> parents = snapshot(c, "usuarios", "organizations");
            long membershipId;
            try (Session s = f.openSession()) {
                var tx = s.beginTransaction();
                var row = new OrganizationMembership(s.getReference(Organization.class, firstOrg),
                        s.getReference(Usuario.class, 800101L), CREATED);
                OrganizationMembershipTest.set(row, "createdAt", CREATED);
                OrganizationMembershipTest.set(row, "updatedAt", CREATED);
                s.persist(row);
                s.flush();
                assertTrue(row.getId() > 0);
                assertEquals(0L, row.getRowVersion());
                assertEquals(Instant.parse("2024-02-29T23:58:57.987654Z"), row.getCreatedAt());
                assertEquals(row.getCreatedAt(), row.getUpdatedAt());
                membershipId = row.getId();
                tx.commit();
            }
            assertEquals("2024-02-29 23:58:57.987654|2024-02-29 23:58:57.987654|0", time(c, membershipId));
            execute(c, insert(firstOrg, 800102L, "OWNER", "ACTIVE"));
            execute(c, insert(secondOrg, 800101L, "OWNER", "REVOKED"));
            assertEquals("2", scalar(c, "SELECT COUNT(*) FROM organization_memberships WHERE organization_id=" + firstOrg
                    + " AND role='OWNER' AND status='ACTIVE'"));
            assertEquals("2", scalar(c, "SELECT COUNT(*) FROM organization_memberships WHERE user_id=800101"));
            long rawId = Long.parseLong(scalar(c, "SELECT id FROM organization_memberships WHERE organization_id=" + secondOrg));
            try (Session s = f.openSession()) {
                var row = s.find(OrganizationMembership.class, rawId);
                assertEquals(MembershipRole.OWNER, row.getRole());
                assertEquals(MembershipStatus.REVOKED, row.getStatus());
                assertEquals(Instant.parse("2024-03-31T02:30:00.123456Z"), row.getCreatedAt());
                assertEquals(row.getCreatedAt(), row.getUpdatedAt());
                assertEquals(secondOrg, row.getOrganization().getId());
                assertEquals(800101L, row.getUser().getId());
            }
            rejected(c, insert(firstOrg, 800101L, "OWNER", "ACTIVE"), 1062);
            rejected(c, insert(secondOrg, 800101L, "OWNER", "ACTIVE"), 1062); // REVOKED keeps its permanent pair.
            rejected(c, insert(Long.MAX_VALUE, 800102L, "OWNER", "ACTIVE"), 1452);
            rejected(c, insert(secondOrg, Long.MAX_VALUE, "OWNER", "ACTIVE"), 1452);
            String[] values = {Long.toString(secondOrg), "800102", "'OWNER'", "'ACTIVE'",
                    "'2024-03-31 02:30:00.123456'", "'2024-03-31 02:30:00.123456'", "0"};
            for (int i = 0; i < values.length; i++) {
                String[] missing = values.clone();
                missing[i] = "NULL";
                rejected(c, "INSERT INTO organization_memberships(organization_id,user_id,role,status,created_at,updated_at,row_version) VALUES ("
                        + String.join(",", missing) + ")", 1048);
            }
            for (String role : List.of("owner", "Owner", "\u00d3WNER", "OWNER ", " OWNER", "MEMBER", "", "\uff2fWNER", "OWNER\u00a0")) {
                rejected(c, insert(secondOrg, 800102L, role, "ACTIVE"), 4025);
            }
            for (String status : List.of("active", "Active", "revoked", "REVOKED ", " ACTIVE", "ACTIVE ", "INVITED", "", "\u00c1CTIVE", "ACT\u0130VE", "ACTIVE\u00a0")) {
                rejected(c, insert(secondOrg, 800102L, "OWNER", status), 4025);
            }
            rejected(c, "DELETE FROM organizations WHERE id=" + firstOrg, 1451);
            rejected(c, "UPDATE organizations SET id=9223372036854775807 WHERE id=" + firstOrg, 1451);
            rejected(c, "DELETE FROM usuarios WHERE id=800101", 1451);
            rejected(c, "UPDATE usuarios SET id=800103 WHERE id=800101", 1451);
            try (Session first = f.openSession(); Session stale = f.openSession()) {
                var t1 = first.beginTransaction();
                var t2 = stale.beginTransaction();
                var a = first.find(OrganizationMembership.class, membershipId);
                var b = stale.find(OrganizationMembership.class, membershipId);
                // Test-only hydration simulates a dirty entity; no product revoke API exists.
                OrganizationMembershipTest.set(a, "status", MembershipStatus.REVOKED);
                OrganizationMembershipTest.set(a, "updatedAt", UPDATED);
                OrganizationMembershipTest.set(a, "createdAt", UPDATED);
                OrganizationMembershipTest.set(a, "organization", first.getReference(Organization.class, secondOrg));
                OrganizationMembershipTest.set(a, "user", first.getReference(Usuario.class, 800102L));
                t1.commit();
                assertEquals(1L, a.getRowVersion());
                assertEquals(Instant.parse("2024-03-31T02:30:00.123456Z"), a.getUpdatedAt());
                OrganizationMembershipTest.set(b, "status", MembershipStatus.REVOKED);
                OrganizationMembershipTest.set(b, "updatedAt", UPDATED);
                RuntimeException conflict = assertThrows(RuntimeException.class, t2::commit);
                assertTrue(conflict instanceof OptimisticLockException || conflict instanceof StaleStateException,
                        conflict.getClass().getName());
                if (t2.isActive()) t2.rollback();
            }
            assertEquals("2024-02-29 23:58:57.987654|2024-03-31 02:30:00.123456|1", time(c, membershipId));
            assertEquals(firstOrg + "|800101|OWNER|REVOKED", scalar(c,
                    "SELECT CONCAT(organization_id,'|',user_id,'|',role,'|',status) FROM organization_memberships WHERE id=" + membershipId));
            try (Session s = f.openSession()) {
                var row = s.find(OrganizationMembership.class, membershipId);
                assertEquals(Instant.parse("2024-03-31T02:30:00.123456Z"), row.getUpdatedAt());
                var tx = s.beginTransaction();
                s.remove(row); // Synthetic cleanup also proves absence of cascade REMOVE to either parent.
                tx.commit();
            }
            assertEquals(parents, snapshot(c, "usuarios", "organizations"));
            execute(c, "DELETE FROM organization_memberships WHERE organization_id IN (" + firstOrg + "," + secondOrg + ")");
            execute(c, "DELETE FROM organizations WHERE id IN (" + firstOrg + "," + secondOrg + ")");
        }
        execute(c, "DELETE FROM usuarios WHERE id IN (800101,800102)");
        assertEquals("0", scalar(c, "SELECT COUNT(*) FROM organization_memberships"));
        assertEquals(legacy, snapshot(c, LEGACY));
        assertEquals(organizations, snapshot(c, "organizations"));
        System.out.println("MEMBERSHIP CONTRACTS PASS: " + ZoneId.systemDefault()
                + "; PK/NOT NULL/FK/UNIQUE/RESTRICT/binary enums/ACTIVE/REVOKED/no cascade/UTC micros/version/immutable identity; fixtures removed");
    }

    private static String time(Connection c, long id) throws SQLException {
        return scalar(c, "SELECT CONCAT(DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s.%f'),'|',"
                + "DATE_FORMAT(updated_at,'%Y-%m-%d %H:%i:%s.%f'),'|',row_version) FROM organization_memberships WHERE id=" + id);
    }

    private static void seed(Connection c, String url, String user, String password) throws SQLException {
        assertEquals("0", scalar(c, "SELECT COUNT(*) FROM organization_memberships"));
        assertEquals("1", scalar(c, "SELECT COUNT(*) FROM organizations"));
        long orgId = Long.parseLong(scalar(c, "SELECT id FROM organizations"));
        try (SessionFactory f = factory(url, user, password); Session s = f.openSession()) {
            var tx = s.beginTransaction();
            s.persist(new OrganizationMembership(s.getReference(Organization.class, orgId),
                    s.getReference(Usuario.class, 900001L), CREATED));
            tx.commit();
        }
        execute(c, insert(orgId, 900002L, "OWNER", "REVOKED"));
        assertEquals("ACTIVE,REVOKED", scalar(c, "SELECT GROUP_CONCAT(status ORDER BY status) FROM organization_memberships"));
        System.out.println("MEMBERSHIP SENTINELS PASS: OWNER ACTIVE + OWNER REVOKED; UTC micros; version=0");
    }

    private static void upgrade(Connection c, String url, String user, String password) throws SQLException {
        assertEquals("0", scalar(c, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()"));
        Flyway.configure().dataSource(url, user, password).target("4").load().migrate();
        assertEquals("1,2,3,4", scalar(c, "SELECT GROUP_CONCAT(version ORDER BY installed_rank) FROM flyway_schema_history"));
        assertEquals("0", scalar(c, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='organization_memberships'"));
        identity(c, 800201L);
        execute(c, "INSERT INTO organizations(name,personal_owner_user_id,created_at,updated_at,row_version) VALUES "
                + "('Populated V4',800201,'2024-02-29 23:58:57.987654','2024-03-31 02:30:00.123456',3),"
                + "('Shared V4',NULL,'2024-03-31 02:30:00.123456','2024-03-31 02:30:00.123456',0)");
        var legacy = snapshot(c, LEGACY);
        var organizations = snapshot(c, "organizations");
        var oldHistory = snapshot(c, "flyway_schema_history").get("flyway_schema_history");
        Flyway flyway = Flyway.configure().dataSource(url, user, password).target("5").load();
        assertEquals(1, flyway.migrate().migrationsExecuted);
        assertEquals("1,2,3,4,5", scalar(c, "SELECT GROUP_CONCAT(version ORDER BY installed_rank) FROM flyway_schema_history"));
        assertEquals("0", scalar(c, "SELECT COUNT(*) FROM organization_memberships"));
        assertEquals(legacy, snapshot(c, LEGACY));
        assertEquals(organizations, snapshot(c, "organizations"));
        assertTrue(snapshot(c, "flyway_schema_history").get("flyway_schema_history").containsAll(oldHistory));
        var preserved = snapshot(c, "organizations", "organization_memberships", "flyway_schema_history");
        assertEquals(0, flyway.migrate().migrationsExecuted);
        assertEquals("0", scalar(c, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('sites','audits','scans')"));
        assertEquals(preserved, snapshot(c, "organizations", "organization_memberships", "flyway_schema_history"));
        assertEquals(legacy, snapshot(c, LEGACY));
        try (SessionFactory ignored = factory(url, user, password)) { /* Hibernate validate on the real V5 schema. */ }
        System.out.println("MEMBERSHIP UPGRADE PASS: populated V4 -> V5; no backfill; exact history 1,2,3,4,5; repeat migrate=0; Hibernate validate");
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
