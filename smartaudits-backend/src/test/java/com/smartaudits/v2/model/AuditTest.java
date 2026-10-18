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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class AuditTest {
    private static final Instant NANOS = Instant.parse("2024-02-29T23:58:57.987654321Z");
    private static final Instant MICROS = Instant.parse("2024-02-29T23:58:57.987654Z");

    @Test void exposesNoPublicConstructorOrCreationApi() throws Exception {
        assertEquals(0, Audit.class.getConstructors().length, "An Audit without its first Scan must not be creatable (V8 factory)");
        Constructor<?>[] declared = Audit.class.getDeclaredConstructors();
        assertEquals(1, declared.length);
        assertTrue(Modifier.isProtected(declared[0].getModifiers()));
        assertEquals(0, declared[0].getParameterCount());
        for (Method method : Audit.class.getDeclaredMethods()) {
            if (method.isSynthetic()) continue;
            assertFalse(Modifier.isStatic(method.getModifiers()), "No static factory: " + method.getName());
            assertFalse(Audit.class.isAssignableFrom(method.getReturnType()), "No method returns an Audit: " + method.getName());
        }
        assertFalse(Modifier.isFinal(Audit.class.getModifiers()), "LAZY proxies require a non-final entity");
    }

    @Test void exposesOnlyGettersAndProtectedCallbacks() {
        Set<String> publicMethods = Arrays.stream(Audit.class.getDeclaredMethods())
                .filter(m -> !m.isSynthetic() && Modifier.isPublic(m.getModifiers()))
                .map(Method::getName).collect(Collectors.toSet());
        assertEquals(Set.of("getId", "getOrganization", "getSite", "getCreatedBy", "getCreatedAt",
                "getUpdatedAt", "getRowVersion"), publicMethods);
        Set<String> otherMethods = Arrays.stream(Audit.class.getDeclaredMethods())
                .filter(m -> !m.isSynthetic() && !Modifier.isPublic(m.getModifiers()))
                .map(m -> Modifier.toString(m.getModifiers()) + " " + m.getName()).collect(Collectors.toSet());
        assertEquals(Set.of("protected normalizeInsert", "protected normalizeUpdate"), otherMethods);
    }

    @Test void hasNoStatusScanReferenceCollectionsOrPublicState() {
        List<String> fields = new ArrayList<>();
        for (Field field : Audit.class.getDeclaredFields()) {
            if (field.isSynthetic()) continue;
            fields.add(field.getName());
            assertTrue(Modifier.isPrivate(field.getModifiers()), field.getName());
            assertFalse(Modifier.isStatic(field.getModifiers()), field.getName());
            assertFalse(field.getType().isEnum(), "Audit has no own status: " + field.getName());
            assertFalse(Collection.class.isAssignableFrom(field.getType()), "No child collections: " + field.getName());
            assertFalse(Map.class.isAssignableFrom(field.getType()), field.getName());
            assertNull(field.getAnnotation(OneToMany.class));
            assertNull(field.getAnnotation(OneToOne.class));
            assertNull(field.getAnnotation(ManyToMany.class));
            assertNull(field.getAnnotation(Enumerated.class));
        }
        assertEquals(List.of("id", "organization", "site", "createdBy", "createdAt", "updatedAt", "rowVersion"), fields);
        for (Method method : Audit.class.getDeclaredMethods()) {
            String name = method.getName().toLowerCase(Locale.ROOT);
            assertFalse(name.contains("status") || name.contains("state") || name.contains("scan"), method.getName());
            assertFalse(name.startsWith("set") || name.startsWith("create") || name.startsWith("start")
                    || name.startsWith("change") || name.startsWith("delete") || name.startsWith("archive")
                    || name.startsWith("update") || name.startsWith("of") || name.startsWith("new"), "Unexpected mutator/factory: " + method.getName());
        }
    }

    @Test void associationsAreLazyMandatoryImmutableAndWithoutCascade() throws Exception {
        Map<String, Object[]> joins = Map.of(
                "organization", new Object[]{"organization_id", "fk_audits_organization", Organization.class},
                "site", new Object[]{"site_id", "fk_audits_site", Site.class},
                "createdBy", new Object[]{"created_by_user_id", "fk_audits_created_by", Usuario.class});
        for (var expected : joins.entrySet()) {
            Field field = Audit.class.getDeclaredField(expected.getKey());
            assertEquals(expected.getValue()[2], field.getType());
            ManyToOne relation = field.getAnnotation(ManyToOne.class);
            assertNotNull(relation);
            assertEquals(FetchType.LAZY, relation.fetch());
            assertFalse(relation.optional());
            assertEquals(0, relation.cascade().length);
            JoinColumn join = field.getAnnotation(JoinColumn.class);
            assertEquals(expected.getValue()[0], join.name());
            assertEquals(expected.getValue()[1], join.foreignKey().name());
            assertEquals("", join.referencedColumnName(), "Site and parents are joined by their primary key");
            assertFalse(join.nullable());
            assertFalse(join.updatable());
            assertTrue(join.insertable());
            assertNull(field.getAnnotation(JoinColumns.class), "Tenant integrity is the SQL composite FK, not a JPA composite join");
        }
    }

    @Test void tableUniqueConstraintAndIndexesUseExplicitApprovedNames() {
        assertNotNull(Audit.class.getAnnotation(Entity.class));
        Table table = Audit.class.getAnnotation(Table.class);
        assertEquals("audits", table.name());
        assertEquals(1, table.uniqueConstraints().length);
        assertEquals("uq_audits_org_audit", table.uniqueConstraints()[0].name());
        assertArrayEquals(new String[]{"organization_id", "id"}, table.uniqueConstraints()[0].columnNames());
        Map<String, String> indexes = Arrays.stream(table.indexes())
                .collect(Collectors.toMap(Index::name, Index::columnList));
        assertEquals(Map.of("ix_audits_org_site", "organization_id, site_id", "ix_audits_created_by", "created_by_user_id"), indexes);
        assertTrue(Arrays.stream(table.indexes()).noneMatch(Index::unique));
    }

    @Test void everyPersistentFieldNamesItsSqlColumn() throws Exception {
        Map<String, String> columns = new LinkedHashMap<>();
        for (Field field : Audit.class.getDeclaredFields()) {
            if (field.isSynthetic()) continue;
            Column column = field.getAnnotation(Column.class);
            JoinColumn join = field.getAnnotation(JoinColumn.class);
            assertTrue(column != null ^ join != null, field.getName());
            columns.put(field.getName(), column != null ? column.name() : join.name());
        }
        assertEquals(Map.of("id", "id", "organization", "organization_id", "site", "site_id",
                "createdBy", "created_by_user_id", "createdAt", "created_at", "updatedAt", "updated_at",
                "rowVersion", "row_version"), columns);
        Field id = Audit.class.getDeclaredField("id");
        assertEquals(Long.class, id.getType());
        assertNotNull(id.getAnnotation(Id.class));
        assertEquals(GenerationType.IDENTITY, id.getAnnotation(GeneratedValue.class).strategy());
        assertFalse(id.getAnnotation(Column.class).updatable());
    }

    @Test void timeAndVersionMappingRetainTheV2Contract() throws Exception {
        for (String name : List.of("createdAt", "updatedAt")) {
            Field field = Audit.class.getDeclaredField(name);
            assertEquals(Instant.class, field.getType());
            assertEquals(SqlTypes.TIMESTAMP_UTC, field.getAnnotation(JdbcTypeCode.class).value());
            assertEquals("DATETIME(6)", field.getAnnotation(Column.class).columnDefinition());
            assertFalse(field.getAnnotation(Column.class).nullable());
        }
        assertFalse(Audit.class.getDeclaredField("createdAt").getAnnotation(Column.class).updatable());
        assertTrue(Audit.class.getDeclaredField("updatedAt").getAnnotation(Column.class).updatable());
        Field version = Audit.class.getDeclaredField("rowVersion");
        assertEquals(Long.class, version.getType());
        assertNotNull(version.getAnnotation(Version.class));
        assertFalse(version.getAnnotation(Column.class).nullable());
        assertEquals("BIGINT DEFAULT 0", version.getAnnotation(Column.class).columnDefinition());
    }

    @Test void callbacksTruncateWithoutRoundingAndNeverRewriteCreationOnUpdate() throws Exception {
        assertNotNull(Audit.class.getDeclaredMethod("normalizeInsert").getAnnotation(PrePersist.class));
        assertNotNull(Audit.class.getDeclaredMethod("normalizeUpdate").getAnnotation(PreUpdate.class));
        Audit audit = newAudit(new Organization("Example", null, NANOS), null, new Usuario(), NANOS);
        set(audit, "updatedAt", Instant.parse("2024-12-31T23:59:59.999999999Z"));
        audit.normalizeInsert();
        assertEquals(MICROS, audit.getCreatedAt());
        assertEquals(Instant.parse("2024-12-31T23:59:59.999999Z"), audit.getUpdatedAt());
        set(audit, "createdAt", NANOS);
        set(audit, "updatedAt", Instant.parse("2024-10-27T01:30:00.123456789Z"));
        audit.normalizeUpdate();
        assertEquals(NANOS, audit.getCreatedAt(), "@PreUpdate must not rewrite the immutable creation time");
        assertEquals(Instant.parse("2024-10-27T01:30:00.123456Z"), audit.getUpdatedAt());
        assertNull(audit.getId());
        assertNull(audit.getRowVersion());
    }

    /** Test-only hydration through the protected JPA constructor; production has no creation path. */
    static Audit newAudit(Organization organization, Site site, Usuario createdBy, Instant createdAt) throws Exception {
        Constructor<Audit> constructor = Audit.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        Audit audit = constructor.newInstance();
        set(audit, "organization", organization);
        set(audit, "site", site);
        set(audit, "createdBy", createdBy);
        set(audit, "createdAt", createdAt);
        set(audit, "updatedAt", createdAt);
        return audit;
    }

    static void set(Audit audit, String name, Object value) throws Exception {
        Field field = Audit.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(audit, value);
    }
}
