package com.smartaudits.v2.model;

import com.smartaudits.model.Usuario;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class SiteTest {
    private static final Instant NANOS = Instant.parse("2024-02-29T23:58:57.987654321Z");
    private static final Instant MICROS = Instant.parse("2024-02-29T23:58:57.987654Z");
    private final Organization organization = new Organization("Example", null, NANOS);
    private final Usuario creator = new Usuario();

    @Test void createsActiveSiteWithNormalizedEqualTimes() {
        Site site = new Site(organization, "Name", "https://example.invalid/", creator, NANOS);
        assertSame(organization, site.getOrganization());
        assertSame(creator, site.getCreatedBy());
        assertEquals(SiteStatus.ACTIVE, site.getStatus());
        assertEquals(MICROS, site.getCreatedAt());
        assertEquals(site.getCreatedAt(), site.getUpdatedAt());
        assertNull(site.getId());
        assertNull(site.getRowVersion());
    }

    @Test void rejectsEachMissingCreationInput() {
        assertThrows(NullPointerException.class, () -> new Site(null, "Name", "https://a.invalid/", creator, NANOS));
        assertThrows(NullPointerException.class, () -> new Site(organization, null, "https://a.invalid/", creator, NANOS));
        assertThrows(NullPointerException.class, () -> new Site(organization, "Name", null, creator, NANOS));
        assertThrows(NullPointerException.class, () -> new Site(organization, "Name", "https://a.invalid/", null, NANOS));
        assertThrows(NullPointerException.class, () -> new Site(organization, "Name", "https://a.invalid/", creator, null));
    }

    @Test void keepsNameAndBaseUrlInTheirOwnFields() {
        Site site = new Site(organization, "Only the name", "https://only-the-url.invalid/", creator, NANOS);
        assertEquals("Only the name", site.getName());
        assertEquals("https://only-the-url.invalid/", site.getBaseUrl());
    }

    @Test void preservesLiteralTextWithoutNormalization() {
        String nfc = "Caf\u00e9";
        String nfd = "Cafe\u0301";
        assertNotEquals(nfc, nfd);
        assertEquals(nfc, Normalizer.normalize(nfd, Normalizer.Form.NFC));
        List<String[]> cases = List.of(
                new String[]{"  Spaced name \t", "  HTTPS://Ejemplo.ES:443/Ruta/../%7Euser?q=1&Q=2#Frag  "},
                new String[]{nfc + " \ud83c\udf33", "https://ejemplo.invalid/" + nfc + "?x=\u00d1#\ud83d\udd12"},
                new String[]{nfd + " \ud83c\udf33", "https://ejemplo.invalid/" + nfd + "?x=N\u0303#\ud83d\udd12"},
                new String[]{"", ""},
                new String[]{" ", "\u00a0"});
        for (String[] input : cases) {
            Site site = new Site(organization, input[0], input[1], creator, NANOS);
            // Same reference: no trim, case folding, Unicode normalization or URL rewriting.
            assertSame(input[0], site.getName());
            assertSame(input[1], site.getBaseUrl());
        }
        assertEquals(nfd, new Site(organization, nfd, nfd, creator, NANOS).getName());
        assertNotEquals(nfc, new Site(organization, nfd, nfd, creator, NANOS).getBaseUrl());
    }

    @Test void identicalUrlsAreNotAnEntityIdentity() {
        Site first = new Site(organization, "First", "https://same.invalid/", creator, NANOS);
        Site second = new Site(organization, "Second", "https://same.invalid/", creator, NANOS);
        assertNotEquals(first, second);
        assertEquals(first.getBaseUrl(), second.getBaseUrl());
    }

    @Test void truncatesCreationToMicrosecondsWithoutRounding() {
        Site site = new Site(organization, "Name", "https://a.invalid/", creator,
                Instant.parse("2024-12-31T23:59:59.999999999Z"));
        assertEquals(Instant.parse("2024-12-31T23:59:59.999999Z"), site.getCreatedAt());
        assertEquals(site.getCreatedAt(), site.getUpdatedAt());
    }

    @Test void exposesOnlyApprovedConstructorAndGetters() throws Exception {
        Set<String> publicMethods = Arrays.stream(Site.class.getDeclaredMethods())
                .filter(m -> !m.isSynthetic() && Modifier.isPublic(m.getModifiers()))
                .map(Method::getName).collect(Collectors.toSet());
        assertEquals(Set.of("getId", "getOrganization", "getName", "getBaseUrl", "getStatus", "getCreatedBy",
                "getCreatedAt", "getUpdatedAt", "getRowVersion"), publicMethods);
        Set<String> otherMethods = Arrays.stream(Site.class.getDeclaredMethods())
                .filter(m -> !m.isSynthetic() && !Modifier.isPublic(m.getModifiers()))
                .map(m -> Modifier.toString(m.getModifiers()) + " " + m.getName()).collect(Collectors.toSet());
        assertEquals(Set.of("protected normalizeInsert", "protected normalizeUpdate"), otherMethods);
        assertEquals(1, Site.class.getConstructors().length);
        assertArrayEquals(new Class<?>[]{Organization.class, String.class, String.class, Usuario.class, Instant.class},
                Site.class.getConstructors()[0].getParameterTypes());
        assertTrue(Modifier.isProtected(Site.class.getDeclaredConstructor().getModifiers()));
        assertEquals(2, Site.class.getDeclaredConstructors().length);
        assertFalse(Modifier.isFinal(Site.class.getModifiers()), "LAZY proxies require a non-final entity");
    }

    @Test void hasNoMutatorsOrPublicState() {
        for (Method method : Site.class.getDeclaredMethods()) {
            String name = method.getName();
            assertFalse(name.startsWith("set") || name.startsWith("archive") || name.startsWith("reactivate")
                    || name.startsWith("delete") || name.startsWith("change") || name.startsWith("rename")
                    || name.startsWith("update"), "Unexpected mutator: " + name);
        }
        for (Field field : Site.class.getDeclaredFields()) {
            if (field.isSynthetic()) continue;
            assertTrue(Modifier.isPrivate(field.getModifiers()), field.getName());
            assertFalse(Collection.class.isAssignableFrom(field.getType()), "No child collections: " + field.getName());
        }
    }

    @Test void associationsAreLazyMandatoryImmutableAndWithoutCascade() throws Exception {
        Map<String, String[]> joins = Map.of(
                "organization", new String[]{"organization_id", "fk_sites_organization"},
                "createdBy", new String[]{"created_by_user_id", "fk_sites_created_by"});
        for (var expected : joins.entrySet()) {
            Field field = Site.class.getDeclaredField(expected.getKey());
            ManyToOne relation = field.getAnnotation(ManyToOne.class);
            assertNotNull(relation);
            assertEquals(FetchType.LAZY, relation.fetch());
            assertFalse(relation.optional());
            assertEquals(0, relation.cascade().length);
            JoinColumn join = field.getAnnotation(JoinColumn.class);
            assertEquals(expected.getValue()[0], join.name());
            assertEquals(expected.getValue()[1], join.foreignKey().name());
            assertFalse(join.nullable());
            assertFalse(join.updatable());
            assertTrue(join.insertable());
            assertNull(field.getAnnotation(OneToMany.class));
            assertNull(field.getAnnotation(OneToOne.class));
        }
        assertEquals(Organization.class, Site.class.getDeclaredField("organization").getType());
        assertEquals(Usuario.class, Site.class.getDeclaredField("createdBy").getType());
    }

    @Test void tableUniqueConstraintAndIndexUseExplicitApprovedNames() {
        Table table = Site.class.getAnnotation(Table.class);
        assertNotNull(Site.class.getAnnotation(Entity.class));
        assertEquals("sites", table.name());
        assertEquals(1, table.uniqueConstraints().length);
        assertEquals("uq_sites_org_site", table.uniqueConstraints()[0].name());
        assertArrayEquals(new String[]{"organization_id", "id"}, table.uniqueConstraints()[0].columnNames());
        assertEquals(1, table.indexes().length);
        assertEquals("ix_sites_created_by", table.indexes()[0].name());
        assertEquals("created_by_user_id", table.indexes()[0].columnList());
        assertFalse(table.indexes()[0].unique());
    }

    @Test void everyPersistentFieldNamesItsSqlColumn() throws Exception {
        Map<String, String> columns = new LinkedHashMap<>();
        for (Field field : Site.class.getDeclaredFields()) {
            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) continue;
            Column column = field.getAnnotation(Column.class);
            JoinColumn join = field.getAnnotation(JoinColumn.class);
            assertTrue(column != null ^ join != null, field.getName());
            columns.put(field.getName(), column != null ? column.name() : join.name());
        }
        assertEquals(Map.of("id", "id", "organization", "organization_id", "name", "name", "baseUrl", "base_url",
                "status", "status", "createdBy", "created_by_user_id", "createdAt", "created_at",
                "updatedAt", "updated_at", "rowVersion", "row_version"), columns);
        Field id = Site.class.getDeclaredField("id");
        assertEquals(Long.class, id.getType());
        assertNotNull(id.getAnnotation(Id.class));
        assertEquals(GenerationType.IDENTITY, id.getAnnotation(GeneratedValue.class).strategy());
    }

    @Test void textColumnsKeepLengthsAndOnlyNameIsUpdatable() throws Exception {
        Column name = Site.class.getDeclaredField("name").getAnnotation(Column.class);
        assertEquals(255, name.length());
        assertFalse(name.nullable());
        assertTrue(name.updatable());
        Column baseUrl = Site.class.getDeclaredField("baseUrl").getAnnotation(Column.class);
        assertEquals(2048, baseUrl.length());
        assertFalse(baseUrl.nullable());
        assertFalse(baseUrl.updatable());
        assertEquals(String.class, Site.class.getDeclaredField("baseUrl").getType());
    }

    @Test void statusUsesOnlyApprovedValuesAsExplicitVarchar() throws Exception {
        assertArrayEquals(new SiteStatus[]{SiteStatus.ACTIVE, SiteStatus.ARCHIVED}, SiteStatus.values());
        assertEquals(List.of("ACTIVE", "ARCHIVED"), Arrays.stream(SiteStatus.values()).map(Enum::name).toList());
        Field field = Site.class.getDeclaredField("status");
        assertEquals(SiteStatus.class, field.getType());
        assertEquals(EnumType.STRING, field.getAnnotation(Enumerated.class).value());
        assertEquals(SqlTypes.VARCHAR, field.getAnnotation(JdbcTypeCode.class).value());
        Column column = field.getAnnotation(Column.class);
        assertEquals(16, column.length());
        assertFalse(column.nullable());
        assertEquals("", column.columnDefinition());
    }

    @Test void timeAndVersionMappingRetainTheV2Contract() throws Exception {
        for (String name : List.of("createdAt", "updatedAt")) {
            Field field = Site.class.getDeclaredField(name);
            assertEquals(Instant.class, field.getType());
            assertEquals(SqlTypes.TIMESTAMP_UTC, field.getAnnotation(JdbcTypeCode.class).value());
            assertEquals("DATETIME(6)", field.getAnnotation(Column.class).columnDefinition());
            assertFalse(field.getAnnotation(Column.class).nullable());
        }
        assertFalse(Site.class.getDeclaredField("createdAt").getAnnotation(Column.class).updatable());
        assertTrue(Site.class.getDeclaredField("updatedAt").getAnnotation(Column.class).updatable());
        Field version = Site.class.getDeclaredField("rowVersion");
        assertEquals(Long.class, version.getType());
        assertNotNull(version.getAnnotation(Version.class));
        assertFalse(version.getAnnotation(Column.class).nullable());
        assertEquals("BIGINT DEFAULT 0", version.getAnnotation(Column.class).columnDefinition());
    }

    @Test void callbacksNormalizeHydratedNanosWithoutChangingCreationOnUpdate() throws Exception {
        assertNotNull(Site.class.getDeclaredMethod("normalizeInsert").getAnnotation(PrePersist.class));
        assertNotNull(Site.class.getDeclaredMethod("normalizeUpdate").getAnnotation(PreUpdate.class));
        Site site = new Site(organization, "Name", "https://a.invalid/", creator, NANOS);
        set(site, "createdAt", NANOS);
        set(site, "updatedAt", Instant.parse("2024-03-31T02:30:00.999999999Z"));
        site.normalizeInsert();
        assertEquals(MICROS, site.getCreatedAt());
        assertEquals(Instant.parse("2024-03-31T02:30:00.999999Z"), site.getUpdatedAt());
        set(site, "createdAt", NANOS);
        set(site, "updatedAt", Instant.parse("2024-10-27T01:30:00.123456789Z"));
        site.normalizeUpdate();
        assertEquals(NANOS, site.getCreatedAt(), "@PreUpdate must not rewrite the immutable creation time");
        assertEquals(Instant.parse("2024-10-27T01:30:00.123456Z"), site.getUpdatedAt());
    }

    static void set(Site site, String name, Object value) throws Exception {
        Field field = Site.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(site, value);
    }
}
