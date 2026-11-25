package com.smartaudits.v2.model;

import com.smartaudits.model.*;
import com.smartaudits.v2.site.SiteUrlIdentity;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.ZoneId;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V10 sentinels for the F3 rollback harness (scripts/Validate-SmartAudits20Harness.ps1). Explicit
 * -Dtest entry only: deliberately outside Surefire's *Test patterns. It works only in the harness'
 * own isolated schema. "seed" attaches one identity to each Site sentinel through the mapping
 * (ACTIVE and ARCHIVED, so the archived reservation is part of the rollback snapshot). "verify"
 * reads them back byte for byte and proves the reservation inside a transaction that always ends in
 * ROLLBACK, so it changes no row. The session sql_mode is read and asserted, never changed.
 */
class SiteUrlIdentityHarnessProbe {
    private static final String VERSIONS = "1,2,3,4,5,6,7,8,9,10,9000";
    private static final String ACTIVE_TEXT = "HTTPS://Sentinel-F3.Example.COM:443/";
    private static final String ACTIVE_FORM = "https://sentinel-f3.example.com";
    private static final String ARCHIVED_TEXT = "http://archived-f3.example.org/";
    private static final String ARCHIVED_FORM = "http://archived-f3.example.org";
    private static final String UNIQUE = "uq_site_url_identities_org_origin";

    @Test void isolatedSiteIdentitySentinels() throws Exception {
        String url = required("F3_DB_URL");
        String database = required("F3_DB_NAME");
        assertTrue(database.matches("f3_harness_[a-f0-9]{32}"));
        assertTrue(url.matches("jdbc:mariadb://127\\.0\\.0\\.1:[0-9]+/" + database));
        assertFalse(url.contains(":3306/"));
        assertTrue(required("F3_SITE_IDENTITY_HARNESS_RUN_ID").matches("[a-f0-9]{32}"));
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
            String mode = scalar(c, "SELECT @@SESSION.sql_mode");
            assertTrue(Arrays.asList(mode.split(",")).contains("STRICT_TRANS_TABLES"), "Strict SQL mode required: " + mode);
            assertEquals(VERSIONS, scalar(c, "SELECT GROUP_CONCAT(version ORDER BY installed_rank) FROM flyway_schema_history"));
            switch (required("F3_SITE_IDENTITY_MODE")) {
                case "seed" -> seed(c, url, user, password);
                case "verify" -> verify(c, url, user, password);
                default -> fail("Unknown Site identity probe mode");
            }
        }
    }

    private static String required(String name) {
        return Objects.requireNonNull(System.getenv(name), "Missing " + name);
    }

    private static long sentinel(Connection c, String status) throws SQLException {
        return Long.parseLong(scalar(c, "SELECT id FROM sites WHERE status='" + status + "'"));
    }

    private static void seed(Connection c, String url, String user, String password) throws SQLException {
        assertEquals("0", scalar(c, "SELECT COUNT(*) FROM site_url_identities"), "identities are seeded once");
        assertEquals("ACTIVE,ARCHIVED", scalar(c, "SELECT GROUP_CONCAT(status ORDER BY status) FROM sites"), "Site sentinels first");
        long active = sentinel(c, "ACTIVE");
        long archived = sentinel(c, "ARCHIVED");
        try (SessionFactory f = factory(url, user, password); Session s = f.openSession()) {
            var tx = s.beginTransaction();
            s.persist(new SiteUrlIdentityRecord(s.find(Site.class, active), identity(ACTIVE_TEXT)));
            s.persist(new SiteUrlIdentityRecord(s.find(Site.class, archived), identity(ARCHIVED_TEXT)));
            tx.commit();
        }
        expectSentinels(c, active, archived);
        System.out.println("SITE IDENTITY SENTINELS PASS: ACTIVE site " + active + " -> " + ACTIVE_FORM + ", ARCHIVED site " + archived
                + " -> " + ARCHIVED_FORM + " (JPA mapping, byte-exact, same organization as each Site)");
    }

    private static void verify(Connection c, String url, String user, String password) throws Exception {
        long active = sentinel(c, "ACTIVE");
        long archived = sentinel(c, "ARCHIVED");
        expectSentinels(c, active, archived);
        try (SessionFactory f = factory(url, user, password); Session s = f.openSession()) {
            assertEquals(ACTIVE_FORM, s.find(SiteUrlIdentityRecord.class, active).getCanonicalOrigin());
            assertEquals(ARCHIVED_FORM, s.find(SiteUrlIdentityRecord.class, archived).getCanonicalOrigin());
        }
        // The archived reservation still holds: another Site of the same organization cannot take it.
        String before = scalar(c, "SELECT CONCAT(COUNT(*),'|',GROUP_CONCAT(CONCAT(site_id,':',organization_id,':',HEX(canonical_origin)) "
                + "ORDER BY site_id)) FROM site_url_identities");
        long organization = Long.parseLong(scalar(c, "SELECT organization_id FROM sites WHERE id=" + archived));
        long creator = Long.parseLong(scalar(c, "SELECT created_by_user_id FROM sites WHERE id=" + archived));
        c.setAutoCommit(false);
        try {
            long probe;
            try (PreparedStatement p = c.prepareStatement("INSERT INTO sites(organization_id,name,base_url,status,created_by_user_id,"
                    + "created_at,updated_at,row_version) VALUES (?,'Verify only','http://archived-f3.example.org/','ACTIVE',?,"
                    + "'2024-03-31 02:30:00.123456','2024-03-31 02:30:00.123456',0)", Statement.RETURN_GENERATED_KEYS)) {
                p.setLong(1, organization);
                p.setLong(2, creator);
                assertEquals(1, p.executeUpdate());
                try (ResultSet keys = p.getGeneratedKeys()) { assertTrue(keys.next()); probe = keys.getLong(1); }
            }
            try (PreparedStatement p = c.prepareStatement("INSERT INTO site_url_identities(site_id,organization_id,canonical_origin) VALUES (?,?,?)")) {
                p.setLong(1, probe);
                p.setLong(2, organization);
                p.setBytes(3, ARCHIVED_FORM.getBytes(StandardCharsets.US_ASCII));
                SQLException reserved = assertThrows(SQLException.class, p::executeUpdate);
                assertEquals(1062, reserved.getErrorCode(), reserved.getMessage());
                assertTrue(reserved.getMessage().contains(UNIQUE), reserved.getMessage());
            }
        } finally {
            c.rollback();
            c.setAutoCommit(true);
        }
        assertEquals(before, scalar(c, "SELECT CONCAT(COUNT(*),'|',GROUP_CONCAT(CONCAT(site_id,':',organization_id,':',HEX(canonical_origin)) "
                + "ORDER BY site_id)) FROM site_url_identities"));
        assertEquals("ACTIVE,ARCHIVED", scalar(c, "SELECT GROUP_CONCAT(status ORDER BY status) FROM sites"), "verification left no Site");
        System.out.println("SITE IDENTITY VERIFY PASS: 2 identities byte-exact (ordered by site_id), each with its Site's organization; "
                + "ARCHIVED reservation still refuses its identity with 1062 " + UNIQUE + " (rolled back, no row changed)");
    }

    private static void expectSentinels(Connection c, long active, long archived) throws SQLException {
        long organization = Long.parseLong(scalar(c, "SELECT organization_id FROM sites WHERE id=" + active));
        assertEquals(Long.toString(organization), scalar(c, "SELECT organization_id FROM sites WHERE id=" + archived));
        List<Long> ordered = active < archived ? List.of(active, archived) : List.of(archived, active);
        Map<Long, String> forms = Map.of(active, ACTIVE_FORM, archived, ARCHIVED_FORM);
        StringBuilder expected = new StringBuilder();
        for (long site : ordered) {
            if (expected.length() > 0) expected.append(',');
            expected.append(site).append(':').append(organization).append(':').append(hex(forms.get(site))).append(':')
                    .append(forms.get(site).length());
        }
        assertEquals(expected.toString(), scalar(c, "SELECT GROUP_CONCAT(CONCAT(site_id,':',organization_id,':',HEX(canonical_origin),':',"
                + "OCTET_LENGTH(canonical_origin)) ORDER BY site_id) FROM site_url_identities"));
        assertEquals("2", scalar(c, "SELECT COUNT(*) FROM site_url_identities i JOIN sites s ON s.organization_id=i.organization_id "
                + "AND s.id=i.site_id"), "every identity joins its own Site in its own organization");
        assertEquals("ACTIVE:" + ACTIVE_FORM + ",ARCHIVED:" + ARCHIVED_FORM, scalar(c, "SELECT GROUP_CONCAT(CONCAT(s.status,':',"
                + "CAST(i.canonical_origin AS CHAR CHARACTER SET ascii)) ORDER BY s.status) FROM site_url_identities i JOIN sites s ON s.id=i.site_id"));
    }

    private static SiteUrlIdentity identity(String text) {
        return SiteUrlIdentityRecordTest.identity(text);
    }

    private static SessionFactory factory(String url, String user, String password) {
        Configuration cfg = new Configuration();
        for (Class<?> entity : List.of(SiteUrlIdentityRecord.class, Site.class, OrganizationMembership.class, Organization.class,
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

    private static String hex(String value) {
        return HexFormat.of().withUpperCase().formatHex(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static String scalar(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            assertTrue(r.next(), sql);
            String result = r.getString(1);
            assertFalse(r.next(), sql);
            return result;
        }
    }
}
