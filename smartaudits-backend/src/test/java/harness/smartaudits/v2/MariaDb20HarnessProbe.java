package harness.smartaudits.v2;

import com.sun.tools.attach.VirtualMachine;
import com.smartaudits.v2.time.UtcMicros;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.cfg.Configuration;
import org.hibernate.type.SqlTypes;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import javax.management.ObjectName;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit -Dtest only; deliberately outside Surefire's default naming patterns.
 * No Spring application scan, production entity or global timezone setting.
 */
class MariaDb20HarnessProbe {
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing harness variable: " + name);
        return value;
    }

    @Test
    void isolatedHarnessOperation() throws Exception {
        String url = required("F3_DB_URL");
        assertTrue(url.matches("jdbc:mariadb://127\\.0\\.0\\.1:[0-9]+/f3_harness_[a-f0-9]{32}"));
        assertFalse(url.contains(":3306/"));
        String user = required("F3_DB_USER");
        assertEquals("f3_harness_user", user);
        String mode = required("F3_PROBE_MODE");
        if (mode.equals("stop")) {
            stopOwnedApplication();
            return;
        }
        try (Connection connection = DriverManager.getConnection(url, user, required("F3_DB_PASSWORD"))) {
            assertTrue(connection.getMetaData().getDatabaseProductVersion().startsWith("11.2."));
            assertEquals(required("F3_DB_NAME"), connection.getCatalog());
            switch (mode) {
                case "seed" -> seed(connection);
                case "timestamp" -> timestamp(connection, url, user);
                default -> fail("Unknown harness operation");
            }
        }
    }

    private void seed(Connection connection) throws Exception {
        // Same synthetic sentinel convention as Validate-FlywayPhase08.ps1.
        // Its entrypoint cannot be dot-sourced: it provisions resources and runs migrations.
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO roles VALUES (900001,'F3 sentinel CLIENTE','CLIENTE'),"
                    + "(900002,'F3 sentinel ADMIN','ADMIN')");
            try (var insert = connection.prepareStatement("INSERT INTO usuarios "
                    + "(id,email,fecha_registro,nombre,password,role,activo,protegido,token_version,row_version) "
                    + "VALUES (?,?,'2001-02-03 04:05:06.123456',?,?,?,b'1',b'0',0,0)")) {
                insert.setLong(1, 900001);
                insert.setString(2, "sentinel@f3.invalid");
                insert.setString(3, "Synthetic sentinel");
                insert.setString(4, "not-a-real-password");
                insert.setString(5, "CLIENTE");
                insert.executeUpdate();
                insert.setLong(1, 900002);
                insert.setString(2, required("E2E_ADMIN_EMAIL"));
                insert.setString(3, "Administrador E2E");
                insert.setString(4, new BCryptPasswordEncoder(12).encode(required("E2E_PASSWORD")));
                insert.setString(5, "ADMIN");
                insert.executeUpdate();
            }
            statement.executeUpdate("INSERT INTO usuarios_roles VALUES (900001,900001),(900002,900002)");
            statement.executeUpdate("INSERT INTO auditorias "
                    + "(id,fecha_creacion,puntuacion_riesgo,resultado_json,texto_original,tipo_documento,titulo,"
                    + "url_opcional,usuario_id,estado,version_motor,version_reglas,fecha_analisis,tipo_fuente) "
                    + "VALUES (900001,'2001-02-03 04:05:06.123456',71,"
                    + "'{\"resumen\":\"F3 sentinel\",\"puntuacionRiesgo\":19,\"errores\":[]}',"
                    + "'Synthetic legal text only','Política de Privacidad','F3 sentinel audit',NULL,900001,"
                    + "'COMPLETADA','1','1','2001-02-03 04:05:06.123456','MANUAL')");
            statement.executeUpdate("INSERT INTO incidencias "
                    + "(id,categoria,descripcion,evidencia,impacto,recomendacion,severidad,auditoria_id,rule_id,motor,version) "
                    + "VALUES (900001,'F3 synthetic title','Synthetic description','Synthetic excerpt','Synthetic impact',"
                    + "'Synthetic action','ALTA',900001,'R01','LEGAL_TEXT','1')");
            statement.executeUpdate("INSERT INTO resultados VALUES "
                    + "(900001,'2001-02-03 04:05:06.123456',43,'Synthetic legacy recommendations','Synthetic legacy summary',900001)");
            statement.executeUpdate("INSERT INTO historial_auditorias VALUES "
                    + "(900001,'CREACION','2001-02-03 04:05:06.123456','127.0.0.1',900001,900001)");
            statement.executeUpdate("INSERT INTO historial_acciones_admin VALUES "
                    + "(900001,'admin@f3.invalid','Synthetic admin','Synthetic detail','2001-02-03 04:05:06.123456',"
                    + "'sentinel@f3.invalid','Synthetic sentinel','REACTIVAR',900002,900001)");
            statement.executeUpdate("INSERT INTO f3_harness_future_probe VALUES "
                    + "(1,'future-sentinel','2001-02-03 04:05:06.123456')");
            connection.commit();
        } catch (Exception failure) {
            connection.rollback();
            throw failure;
        }
        System.out.println("HARNESS SEED PASS (synthetic data only)");
    }

    private void timestamp(Connection connection, String url, String user) throws Exception {
        String timezone = required("F3_TIMEZONE");
        assertTrue(timezone.equals("Asia/Kathmandu") || timezone.equals("Europe/Madrid"));
        assertEquals(timezone, ZoneId.systemDefault().getId());
        assertNull(System.getProperty("hibernate.jdbc.time_zone"));
        Instant[] inputs = {
                Instant.parse("2024-02-29T23:58:57.987654321Z"),
                Instant.parse("2024-03-31T02:30:00.123456789Z")
        };
        String[] rawExpected = {"2024-02-29 23:58:57.987654", "2024-03-31 02:30:00.123456"};
        String table = "f3_harness_timestamp_probe";
        // Deliberately outside Flyway; the table must not survive this operation.
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + table + " (id BIGINT PRIMARY KEY, observed_at DATETIME(6) NOT NULL)");
        }
        try {
            Configuration configuration = new Configuration().addAnnotatedClass(TimeRow.class);
            configuration.setProperty("hibernate.connection.url", url);
            configuration.setProperty("hibernate.connection.username", user);
            configuration.setProperty("hibernate.connection.password", required("F3_DB_PASSWORD"));
            configuration.setProperty("hibernate.connection.driver_class", "org.mariadb.jdbc.Driver");
            configuration.setProperty("hibernate.dialect", "org.hibernate.dialect.MariaDBDialect");
            configuration.setProperty("hibernate.hbm2ddl.auto", "validate");
            configuration.setProperty("hibernate.show_sql", "false");
            // No hibernate.jdbc.time_zone and no global defaults affecting legacy.
            try (var factory = configuration.buildSessionFactory()) {
                for (int i = 0; i < inputs.length; i++) {
                long id = i + 1L;
                Instant expected = UtcMicros.truncate(inputs[i]);
                assertNotEquals(0, inputs[i].getNano() % 1000, "Fixture must exercise nanosecond input");
                assertEquals(0, expected.getNano() % 1000, "Normalize before persistence");
                assertEquals(i == 0 ? Instant.parse("2024-02-29T23:58:57.987654Z")
                        : Instant.parse("2024-03-31T02:30:00.123456Z"), expected);
                try (var session = factory.openSession()) {
                    var transaction = session.beginTransaction();
                    TimeRow row = new TimeRow();
                    row.id = id;
                    row.observedAt = expected;
                    session.persist(row);
                    transaction.commit();
                }
                try (var query = connection.createStatement();
                     var raw = query.executeQuery("SELECT DATE_FORMAT(observed_at,'%Y-%m-%d %H:%i:%s.%f') FROM " + table + " WHERE id=" + id)) {
                    assertTrue(raw.next());
                    assertEquals(rawExpected[i], raw.getString(1), "raw UTC in " + timezone + " for " + inputs[i]);
                    assertFalse(raw.next());
                }
                try (var session = factory.openSession()) {
                    assertEquals(expected, session.find(TimeRow.class, id).observedAt, "JPA round-trip in " + timezone);
                }
                System.out.println("TIMESTAMP CASE PASS: " + timezone + "; input=" + inputs[i] + "; raw=" + rawExpected[i]);
                }
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("INSERT INTO " + table + " VALUES (3,'2024-03-31 02:30:00.123456')");
                }
                try (var session = factory.openSession()) {
                    assertEquals(Instant.parse("2024-03-31T02:30:00.123456Z"),
                            session.find(TimeRow.class, 3L).observedAt, "Independent raw SQL -> Instant in " + timezone);
                }
                System.out.println("RAW SQL -> INSTANT PASS: " + timezone + "; 2024-03-31T02:30:00.123456Z");
            }
        } finally {
            try (var statement = connection.createStatement()) { statement.execute("DROP TABLE " + table); }
        }
        System.out.println("TIMESTAMP PASS: " + timezone + "; raw UTC; exact microsecond round-trip and SQL -> Java");
    }

    private void stopOwnedApplication() throws Exception {
        // Local JDK attach: no public JMX port, no credentials, no HTTP shutdown endpoint.
        Path jar = Path.of(required("F3_BACKEND_JAR")).toAbsolutePath().normalize();
        Path root = Path.of(required("F3_TEMP_ROOT")).toAbsolutePath().normalize();
        assertTrue(root.getFileName().toString().matches("smartaudits-f3-harness-[a-f0-9]{32}"));
        assertTrue(jar.startsWith(root));
        String address;
        VirtualMachine vm = VirtualMachine.attach(required("F3_BACKEND_PID"));
        try {
            assertTrue(vm.getSystemProperties().getProperty("sun.java.command").startsWith(jar.toString() + " "));
            address = vm.startLocalManagementAgent();
        } finally { vm.detach(); }
        try (var connector = JMXConnectorFactory.connect(new JMXServiceURL(address))) {
            connector.getMBeanServerConnection().invoke(
                    new ObjectName("org.springframework.boot:type=Admin,name=SpringApplication"),
                    "shutdown", new Object[0], new String[0]);
        }
        System.out.println("OWNED BACKEND SHUTDOWN REQUESTED");
    }

    @Entity(name = "HarnessTimeRow")
    @Table(name = "f3_harness_timestamp_probe")
    public static class TimeRow {
        @Id Long id;
        // Explicit native Instant JDBC mapping; no LocalDateTime converter or JVM-zone interpretation.
        @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
        @Column(name = "observed_at", nullable = false, columnDefinition = "DATETIME(6)")
        Instant observedAt;
        public TimeRow() { }
    }

}
