package com.smartaudits.v2.model;

import com.smartaudits.model.Usuario;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ScanTest {
    private static final Instant NANOS = Instant.parse("2024-02-29T23:58:57.987654321Z");
    private static final Instant MICROS = Instant.parse("2024-02-29T23:58:57.987654Z");
    private static final Instant T1 = Instant.parse("2024-03-31T02:30:00.123456789Z");
    private static final Instant T2 = Instant.parse("2024-10-27T01:30:00.999999999Z");

    static Organization organization(long id) throws Exception {
        Organization organization = new Organization("Org " + id, null, NANOS);
        Field field = Organization.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(organization, id);
        return organization;
    }

    static Usuario user(long id) {
        Usuario user = new Usuario();
        user.setId(id);
        return user;
    }

    static Site site(Organization organization) {
        return new Site(organization, "Site", "https://site.invalid/", user(9L), NANOS);
    }

    static Scan pending() throws Exception {
        Organization organization = organization(7L);
        return Audit.startWithFirstScan(organization, site(organization), user(1L), NANOS).firstScan();
    }

    private static List<Object> state(Scan scan) {
        return Arrays.asList(scan.getStatus(), scan.getStartedAt(), scan.getFinishedAt(), scan.getUpdatedAt(), scan.getCreatedAt());
    }

    @Test void firstScanIsPendingAttemptOneSharingTheAuditInstant() throws Exception {
        Organization organization = organization(7L);
        Usuario creator = user(1L);
        Audit.AuditStart start = Audit.startWithFirstScan(organization, site(organization), creator, NANOS);
        Scan scan = start.firstScan();
        assertSame(start.audit(), scan.getAudit());
        assertSame(organization, scan.getOrganization());
        assertSame(creator, scan.getCreatedBy());
        assertEquals(1, scan.getAttemptNumber());
        assertEquals(ScanStatus.PENDING, scan.getStatus());
        assertEquals(MICROS, scan.getCreatedAt());
        assertEquals(MICROS, scan.getUpdatedAt());
        assertEquals(start.audit().getCreatedAt(), scan.getCreatedAt());
        assertNull(scan.getStartedAt());
        assertNull(scan.getFinishedAt());
        assertNull(scan.getId());
        assertNull(scan.getRowVersion());
    }

    @Test void retryFactoryRequiresAttemptTwoOrHigher() throws Exception {
        Scan first = pending();
        Scan retry = Scan.retry(first.getAudit(), 2, user(2L), T1);
        assertEquals(2, retry.getAttemptNumber());
        assertEquals(ScanStatus.PENDING, retry.getStatus());
        assertSame(first.getOrganization(), retry.getOrganization());
        assertEquals(Instant.parse("2024-03-31T02:30:00.123456Z"), retry.getCreatedAt());
        assertThrows(IllegalArgumentException.class, () -> Scan.retry(first.getAudit(), 1, user(2L), T1));
        assertThrows(IllegalArgumentException.class, () -> Scan.retry(first.getAudit(), 0, user(2L), T1));
        assertThrows(NullPointerException.class, () -> Scan.retry(null, 2, user(2L), T1));
        assertThrows(NullPointerException.class, () -> Scan.retry(first.getAudit(), 2, null, T1));
        assertThrows(NullPointerException.class, () -> Scan.retry(first.getAudit(), 2, user(2L), null));
    }

    private static Scan.Attempt a(int number, ScanStatus status) { return new Scan.Attempt(number, status); }

    @Test void nextAttemptFollowsAdrRetryRulesByAttemptNumber() {
        assertEquals(2, Scan.nextAttemptAfter(List.of(a(1, ScanStatus.FAILED))));
        assertEquals(2, Scan.nextAttemptAfter(List.of(a(1, ScanStatus.CANCELLED))));
        assertEquals(3, Scan.nextAttemptAfter(List.of(a(1, ScanStatus.FAILED), a(2, ScanStatus.CANCELLED))));
        // Decided by attempt_number, not by list order or timestamps.
        assertEquals(4, Scan.nextAttemptAfter(List.of(a(3, ScanStatus.FAILED), a(1, ScanStatus.CANCELLED), a(2, ScanStatus.FAILED))));
        assertEquals(Integer.MAX_VALUE, Scan.nextAttemptAfter(List.of(a(Integer.MAX_VALUE - 1, ScanStatus.FAILED))));
        for (List<Scan.Attempt> rejected : List.of(
                List.<Scan.Attempt>of(),
                List.of(a(1, ScanStatus.PENDING)),
                List.of(a(1, ScanStatus.RUNNING)),
                List.of(a(1, ScanStatus.SUCCEEDED)),
                List.of(a(1, ScanStatus.FAILED), a(2, ScanStatus.PENDING)),
                List.of(a(1, ScanStatus.FAILED), a(2, ScanStatus.RUNNING)),
                List.of(a(1, ScanStatus.RUNNING), a(2, ScanStatus.FAILED)), // an active attempt need not be the latest
                List.of(a(1, ScanStatus.FAILED), a(2, ScanStatus.SUCCEEDED)),
                List.of(a(2, ScanStatus.FAILED), a(1, ScanStatus.SUCCEEDED)),
                List.of(a(Integer.MAX_VALUE, ScanStatus.FAILED)))) {
            assertThrows(IllegalStateException.class, () -> Scan.nextAttemptAfter(rejected), rejected.toString());
        }
        assertThrows(NullPointerException.class, () -> Scan.nextAttemptAfter(null));
        assertThrows(NullPointerException.class, () -> new Scan.Attempt(1, null));
    }

    /** Every state reached through valid transitions, with the transitions the ADR allows from it. */
    private static Map<String, Scan> states() throws Exception {
        Map<String, Scan> states = new LinkedHashMap<>();
        states.put("PENDING", pending());
        Scan running = pending(); running.start(T1); states.put("RUNNING", running);
        Scan succeeded = pending(); succeeded.start(T1); succeeded.succeed(T1); states.put("SUCCEEDED", succeeded);
        Scan failed = pending(); failed.start(T1); failed.fail(T1); states.put("FAILED", failed);
        Scan cancelledPending = pending(); cancelledPending.cancel(T1); states.put("CANCELLED_FROM_PENDING", cancelledPending);
        Scan cancelledRunning = pending(); cancelledRunning.start(T1); cancelledRunning.cancel(T1); states.put("CANCELLED_FROM_RUNNING", cancelledRunning);
        return states;
    }

    @Test void onlyAdrTransitionsSucceedAndInvalidOnesChangeNothing() throws Exception {
        Map<String, Consumer<Scan>> transitions = new LinkedHashMap<>();
        transitions.put("start", s -> s.start(T2));
        transitions.put("succeed", s -> s.succeed(T2));
        transitions.put("fail", s -> s.fail(T2));
        transitions.put("cancel", s -> s.cancel(T2));
        Map<String, Set<String>> allowed = Map.of(
                "PENDING", Set.of("start", "cancel"),
                "RUNNING", Set.of("succeed", "fail", "cancel"),
                "SUCCEEDED", Set.of(), "FAILED", Set.of(),
                "CANCELLED_FROM_PENDING", Set.of(), "CANCELLED_FROM_RUNNING", Set.of());
        Map<String, ScanStatus> targets = Map.of("start", ScanStatus.RUNNING, "succeed", ScanStatus.SUCCEEDED,
                "fail", ScanStatus.FAILED, "cancel", ScanStatus.CANCELLED);
        int checked = 0;
        for (String from : allowed.keySet()) {
            for (var transition : transitions.entrySet()) {
                Scan scan = states().get(from);
                List<Object> before = state(scan);
                if (allowed.get(from).contains(transition.getKey())) {
                    transition.getValue().accept(scan);
                    assertEquals(targets.get(transition.getKey()), scan.getStatus(), from + " -> " + transition.getKey());
                    assertEquals(Instant.parse("2024-10-27T01:30:00.999999Z"), scan.getUpdatedAt());
                } else {
                    assertThrows(IllegalStateException.class, () -> transition.getValue().accept(scan), from + " -> " + transition.getKey());
                    assertEquals(before, state(scan), "Rejected transition changed the Scan: " + from + " -> " + transition.getKey());
                }
                assertChronology(scan);
                checked++;
            }
        }
        assertEquals(24, checked);
    }

    /** Mirrors ck_scans_lifecycle and ck_scans_chronology from V8. */
    private static void assertChronology(Scan s) {
        switch (s.getStatus()) {
            case PENDING -> { assertNull(s.getStartedAt()); assertNull(s.getFinishedAt()); }
            case RUNNING -> { assertNotNull(s.getStartedAt()); assertNull(s.getFinishedAt()); }
            case SUCCEEDED, FAILED -> { assertNotNull(s.getStartedAt()); assertNotNull(s.getFinishedAt()); }
            case CANCELLED -> assertNotNull(s.getFinishedAt());
        }
        assertFalse(s.getUpdatedAt().isBefore(s.getCreatedAt()));
        if (s.getStartedAt() != null) assertFalse(s.getStartedAt().isBefore(s.getCreatedAt()));
        Instant floor = s.getStartedAt() != null ? s.getStartedAt() : s.getCreatedAt();
        if (s.getFinishedAt() != null) assertFalse(s.getFinishedAt().isBefore(floor));
        Instant latest = s.getFinishedAt() != null ? s.getFinishedAt() : floor;
        assertFalse(s.getUpdatedAt().isBefore(latest));
    }

    @Test void transitionsRecordTruncatedTimesAndAllowEqualInstants() throws Exception {
        Scan scan = pending();
        scan.start(MICROS); // Equal to creation at microsecond precision.
        assertEquals(MICROS, scan.getStartedAt());
        assertEquals(MICROS, scan.getUpdatedAt());
        scan.succeed(Instant.parse("2024-02-29T23:58:57.987654999Z")); // Truncates to the same microsecond.
        assertEquals(MICROS, scan.getFinishedAt());
        assertEquals(MICROS, scan.getUpdatedAt());
        Scan cancelled = pending();
        cancelled.cancel(Instant.parse("2024-12-31T23:59:59.999999999Z"));
        assertEquals(Instant.parse("2024-12-31T23:59:59.999999Z"), cancelled.getFinishedAt());
        assertNull(cancelled.getStartedAt(), "Cancelling a PENDING Scan never invents a start");
        Scan cancelledRunning = pending();
        cancelledRunning.start(T1);
        cancelledRunning.cancel(T2);
        assertEquals(Instant.parse("2024-03-31T02:30:00.123456Z"), cancelledRunning.getStartedAt());
        assertEquals(Instant.parse("2024-10-27T01:30:00.999999Z"), cancelledRunning.getFinishedAt());
    }

    @Test void timeCannotMoveBackwardsAndRejectionsChangeNothing() throws Exception {
        Scan scan = pending();
        scan.start(T2);
        List<Object> before = state(scan);
        for (Consumer<Scan> transition : List.<Consumer<Scan>>of(s -> s.succeed(T1), s -> s.fail(T1), s -> s.cancel(T1))) {
            assertThrows(IllegalArgumentException.class, () -> transition.accept(scan));
            assertEquals(before, state(scan));
        }
        Scan fresh = pending();
        List<Object> pendingState = state(fresh);
        assertThrows(IllegalArgumentException.class, () -> fresh.start(Instant.parse("2024-02-29T23:58:57.987653999Z")));
        assertEquals(pendingState, state(fresh));
        assertThrows(NullPointerException.class, () -> fresh.start(null));
        assertEquals(pendingState, state(fresh));
    }

    @Test void secondCancellationIsRejectedWithoutChanges() throws Exception {
        Scan scan = pending();
        scan.cancel(T1);
        List<Object> before = state(scan);
        assertThrows(IllegalStateException.class, () -> scan.cancel(T2));
        assertEquals(before, state(scan));
    }

    @Test void statusValuesAndActivity() {
        assertArrayEquals(new ScanStatus[]{ScanStatus.PENDING, ScanStatus.RUNNING, ScanStatus.SUCCEEDED,
                ScanStatus.FAILED, ScanStatus.CANCELLED}, ScanStatus.values());
        assertEquals(Set.of(ScanStatus.PENDING, ScanStatus.RUNNING),
                Arrays.stream(ScanStatus.values()).filter(ScanStatus::isActive).collect(Collectors.toSet()));
    }

    @Test void exposesOnlyGettersAndPackageInternalLifecycle() throws Exception {
        assertEquals(0, Scan.class.getConstructors().length);
        Set<String> constructors = Arrays.stream(Scan.class.getDeclaredConstructors())
                .map(c -> Modifier.toString(c.getModifiers()) + "/" + c.getParameterCount()).collect(Collectors.toSet());
        assertEquals(Set.of("protected/0", "private/4"), constructors);
        Map<String, String> methods = Arrays.stream(Scan.class.getDeclaredMethods()).filter(m -> !m.isSynthetic())
                .collect(Collectors.toMap(Method::getName, m -> Modifier.toString(m.getModifiers()), (x, y) -> x + "|" + y));
        Set<String> getters = Set.of("getId", "getOrganization", "getAudit", "getAttemptNumber", "getStatus", "getCreatedBy",
                "getCreatedAt", "getUpdatedAt", "getStartedAt", "getFinishedAt", "getRowVersion");
        for (String getter : getters) assertEquals("public", methods.get(getter), getter);
        assertEquals(getters, methods.entrySet().stream().filter(e -> e.getValue().contains("public"))
                .map(Map.Entry::getKey).collect(Collectors.toSet()), "Only getters are public");
        for (String factory : List.of("first", "retry", "nextAttemptAfter")) assertEquals("static", methods.get(factory), factory);
        for (String transition : List.of("start", "succeed", "fail", "cancel")) assertEquals("", methods.get(transition), transition);
        assertEquals("protected", methods.get("normalizeInsert"));
        assertEquals("protected", methods.get("normalizeUpdate"));
        assertTrue(methods.keySet().stream().noneMatch(n -> n.startsWith("set")), "No setters");
        Class<?> attempt = Scan.Attempt.class;
        assertTrue(attempt.isRecord());
        assertFalse(Modifier.isPublic(attempt.getModifiers()));
        assertFalse(Modifier.isFinal(Scan.class.getModifiers()), "LAZY proxies require a non-final entity");
    }

    @Test void fieldsNameTheirColumnsWithoutTheGeneratedActiveColumnOrCollections() throws Exception {
        Map<String, String> columns = new LinkedHashMap<>();
        for (Field field : Scan.class.getDeclaredFields()) {
            if (field.isSynthetic()) continue;
            assertTrue(Modifier.isPrivate(field.getModifiers()), field.getName());
            assertFalse(Collection.class.isAssignableFrom(field.getType()), field.getName());
            Column column = field.getAnnotation(Column.class);
            JoinColumn join = field.getAnnotation(JoinColumn.class);
            assertTrue(column != null ^ join != null, field.getName());
            columns.put(field.getName(), column != null ? column.name() : join.name());
        }
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("id", "id"); expected.put("organization", "organization_id"); expected.put("audit", "audit_id");
        expected.put("attemptNumber", "attempt_number"); expected.put("status", "status"); expected.put("createdBy", "created_by_user_id");
        expected.put("createdAt", "created_at"); expected.put("updatedAt", "updated_at"); expected.put("startedAt", "started_at");
        expected.put("finishedAt", "finished_at"); expected.put("rowVersion", "row_version");
        assertEquals(expected, columns, "active_audit_id is generated by V8 and must not be a writable property");
        Field id = Scan.class.getDeclaredField("id");
        assertEquals(Long.class, id.getType());
        assertEquals(GenerationType.IDENTITY, id.getAnnotation(GeneratedValue.class).strategy());
        Field attempt = Scan.class.getDeclaredField("attemptNumber");
        assertEquals(int.class, attempt.getType());
        assertFalse(attempt.getAnnotation(Column.class).updatable());
        assertFalse(attempt.getAnnotation(Column.class).nullable());
    }

    @Test void associationsAreLazyMandatoryImmutableAndWithoutCascade() throws Exception {
        Map<String, Object[]> joins = Map.of(
                "organization", new Object[]{"organization_id", "fk_scans_organization", Organization.class},
                "audit", new Object[]{"audit_id", "fk_scans_audit", Audit.class},
                "createdBy", new Object[]{"created_by_user_id", "fk_scans_created_by", Usuario.class});
        for (var expected : joins.entrySet()) {
            Field field = Scan.class.getDeclaredField(expected.getKey());
            assertEquals(expected.getValue()[2], field.getType());
            ManyToOne relation = field.getAnnotation(ManyToOne.class);
            assertEquals(FetchType.LAZY, relation.fetch());
            assertFalse(relation.optional());
            assertEquals(0, relation.cascade().length);
            JoinColumn join = field.getAnnotation(JoinColumn.class);
            assertEquals(expected.getValue()[0], join.name());
            assertEquals(expected.getValue()[1], join.foreignKey().name());
            assertFalse(join.nullable());
            assertFalse(join.updatable());
            assertTrue(join.insertable());
            assertNull(field.getAnnotation(JoinColumns.class));
        }
    }

    @Test void tableConstraintIndexesStatusAndTimeMappings() throws Exception {
        Table table = Scan.class.getAnnotation(Table.class);
        assertNotNull(Scan.class.getAnnotation(Entity.class));
        assertEquals("scans", table.name());
        assertEquals(1, table.uniqueConstraints().length);
        assertEquals("uq_scans_audit_attempt", table.uniqueConstraints()[0].name());
        assertArrayEquals(new String[]{"audit_id", "attempt_number"}, table.uniqueConstraints()[0].columnNames());
        assertEquals(Map.of("ix_scans_org_audit", "organization_id, audit_id", "ix_scans_created_by", "created_by_user_id"),
                Arrays.stream(table.indexes()).collect(Collectors.toMap(Index::name, Index::columnList)));
        Field status = Scan.class.getDeclaredField("status");
        assertEquals(EnumType.STRING, status.getAnnotation(Enumerated.class).value());
        assertEquals(SqlTypes.VARCHAR, status.getAnnotation(JdbcTypeCode.class).value());
        assertEquals(16, status.getAnnotation(Column.class).length());
        assertFalse(status.getAnnotation(Column.class).nullable());
        for (String name : List.of("createdAt", "updatedAt", "startedAt", "finishedAt")) {
            Field field = Scan.class.getDeclaredField(name);
            assertEquals(Instant.class, field.getType());
            assertEquals(SqlTypes.TIMESTAMP_UTC, field.getAnnotation(JdbcTypeCode.class).value());
            assertEquals("DATETIME(6)", field.getAnnotation(Column.class).columnDefinition());
        }
        assertFalse(Scan.class.getDeclaredField("createdAt").getAnnotation(Column.class).updatable());
        assertFalse(Scan.class.getDeclaredField("createdAt").getAnnotation(Column.class).nullable());
        assertFalse(Scan.class.getDeclaredField("updatedAt").getAnnotation(Column.class).nullable());
        assertTrue(Scan.class.getDeclaredField("startedAt").getAnnotation(Column.class).nullable());
        assertTrue(Scan.class.getDeclaredField("finishedAt").getAnnotation(Column.class).nullable());
        Field version = Scan.class.getDeclaredField("rowVersion");
        assertEquals(Long.class, version.getType());
        assertNotNull(version.getAnnotation(Version.class));
        assertEquals("BIGINT DEFAULT 0", version.getAnnotation(Column.class).columnDefinition());
    }

    @Test void callbacksNormalizeHydratedNanosWithoutRewritingCreationOnUpdate() throws Exception {
        assertNotNull(Scan.class.getDeclaredMethod("normalizeInsert").getAnnotation(PrePersist.class));
        assertNotNull(Scan.class.getDeclaredMethod("normalizeUpdate").getAnnotation(PreUpdate.class));
        Scan scan = pending();
        set(scan, "createdAt", NANOS);
        set(scan, "updatedAt", T2);
        scan.normalizeInsert();
        assertEquals(MICROS, scan.getCreatedAt());
        assertEquals(Instant.parse("2024-10-27T01:30:00.999999Z"), scan.getUpdatedAt());
        set(scan, "createdAt", NANOS);
        set(scan, "updatedAt", T1);
        scan.normalizeUpdate();
        assertEquals(NANOS, scan.getCreatedAt());
        assertEquals(Instant.parse("2024-03-31T02:30:00.123456Z"), scan.getUpdatedAt());
    }

    /** Test-only hydration; production changes Scans only through the ADR transitions. */
    static void set(Scan scan, String name, Object value) throws Exception {
        Field field = Scan.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(scan, value);
    }

    /** Test-only instance through the protected JPA constructor. */
    static Scan blank() throws Exception {
        Constructor<Scan> constructor = Scan.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }
}
