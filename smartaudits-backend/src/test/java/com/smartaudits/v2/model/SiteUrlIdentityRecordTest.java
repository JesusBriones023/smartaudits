package com.smartaudits.v2.model;

import com.smartaudits.model.Usuario;
import com.smartaudits.v2.site.SiteUrlIdentity;
import com.smartaudits.v2.site.SiteUrlIdentityResult;
import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Mapping contract of V10 (docs/F4_1C_SITE_URL_IDENTITY.md, "Increment 1"). No database here. */
class SiteUrlIdentityRecordTest {
    private static final Instant NOW = Instant.parse("2026-11-24T10:00:00.123456Z");

    static SiteUrlIdentity identity(String text) {
        return ((SiteUrlIdentityResult.Accepted) SiteUrlIdentity.parse(text)).identity();
    }

    static void setId(Object entity, long id) throws Exception {
        Field field = entity.getClass().getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }

    private static Site site(long organizationId, long siteId) throws Exception {
        Organization organization = new Organization("Example", null, NOW);
        setId(organization, organizationId);
        Site site = new Site(organization, "Name", "  HTTPS://Example.COM:443/  ", new Usuario(), NOW);
        setId(site, siteId);
        return site;
    }

    @Test void takesTheSiteTheTenantOfTheSiteAndTheCanonicalForm() throws Exception {
        SiteUrlIdentityRecord record = new SiteUrlIdentityRecord(site(7L, 42L), identity("HTTPS://Example.COM:443/"));
        assertEquals(42L, record.getSiteId());
        assertEquals(7L, record.getOrganizationId());
        assertEquals("https://example.com", record.getCanonicalOrigin());
    }

    @Test void theLiteralBaseUrlIsNeitherReadNorRepaired() throws Exception {
        Site site = site(7L, 42L);
        SiteUrlIdentityRecord record = new SiteUrlIdentityRecord(site, identity("http://other.example.org"));
        assertEquals("http://other.example.org", record.getCanonicalOrigin());
        assertEquals("  HTTPS://Example.COM:443/  ", site.getBaseUrl());
    }

    @Test void storesExactlyTheAsciiBytesOfTheCanonicalForm() throws Exception {
        String longest = "https://" + String.join(".", "a".repeat(63), "b".repeat(63), "c".repeat(63), "d".repeat(61));
        assertEquals(SiteUrlIdentity.MAX_CANONICAL_LENGTH, longest.length());
        for (String text : List.of("http://a.b", "https://www.example.com", "HTTP://1a-b.C0M:80", longest)) {
            SiteUrlIdentity identity = identity(text);
            SiteUrlIdentityRecord record = new SiteUrlIdentityRecord(site(1L, 2L), identity);
            byte[] stored = (byte[]) field("canonicalOrigin").get(record);
            assertArrayEquals(identity.canonicalForm().getBytes(java.nio.charset.StandardCharsets.US_ASCII), stored, text);
            assertEquals(identity.canonicalForm().length(), stored.length, "one byte per character, no padding");
            assertEquals(identity.canonicalForm(), record.getCanonicalOrigin());
        }
    }

    @Test void equivalentSpellingsGiveIdenticalBytesAndDistinctIdentitiesDistinctBytes() throws Exception {
        List<String> spellings = List.of("https://example.com", "HTTPS://EXAMPLE.COM", "https://example.com/",
                "https://example.com:443", "https://Example.Com:443/");
        Set<String> stored = new HashSet<>();
        for (String text : spellings) stored.add(new SiteUrlIdentityRecord(site(1L, 2L), identity(text)).getCanonicalOrigin());
        assertEquals(Set.of("https://example.com"), stored);
        Set<String> distinct = new HashSet<>();
        for (String text : List.of("https://example.com", "http://example.com", "https://www.example.com", "https://shop.example.com"))
            distinct.add(new SiteUrlIdentityRecord(site(1L, 2L), identity(text)).getCanonicalOrigin());
        assertEquals(4, distinct.size());
    }

    @Test void rejectsMissingInputsAndUnpersistedParents() throws Exception {
        SiteUrlIdentity identity = identity("https://example.com");
        assertThrows(NullPointerException.class, () -> new SiteUrlIdentityRecord(null, identity));
        assertThrows(NullPointerException.class, () -> new SiteUrlIdentityRecord(site(1L, 2L), null));
        Organization organization = new Organization("Example", null, NOW);
        Site transientSite = new Site(organization, "Name", "https://example.com", new Usuario(), NOW);
        assertThrows(NullPointerException.class, () -> new SiteUrlIdentityRecord(transientSite, identity), "Site without id");
        setId(transientSite, 5L);
        assertThrows(NullPointerException.class, () -> new SiteUrlIdentityRecord(transientSite, identity), "organization without id");
    }

    @Test void exposesOnlyTheApprovedConstructorAndGetters() throws Exception {
        Set<String> publicMethods = Arrays.stream(SiteUrlIdentityRecord.class.getDeclaredMethods())
                .filter(m -> !m.isSynthetic() && Modifier.isPublic(m.getModifiers()))
                .map(Method::getName).collect(Collectors.toSet());
        assertEquals(Set.of("getSiteId", "getOrganizationId", "getCanonicalOrigin"), publicMethods);
        assertEquals(Set.of(), Arrays.stream(SiteUrlIdentityRecord.class.getDeclaredMethods())
                .filter(m -> !m.isSynthetic() && !Modifier.isPublic(m.getModifiers())).map(Method::getName).collect(Collectors.toSet()));
        assertEquals(1, SiteUrlIdentityRecord.class.getConstructors().length);
        assertArrayEquals(new Class<?>[]{Site.class, SiteUrlIdentity.class}, SiteUrlIdentityRecord.class.getConstructors()[0].getParameterTypes());
        assertTrue(Modifier.isProtected(SiteUrlIdentityRecord.class.getDeclaredConstructor().getModifiers()));
        assertEquals(2, SiteUrlIdentityRecord.class.getDeclaredConstructors().length);
        for (Field field : SiteUrlIdentityRecord.class.getDeclaredFields()) {
            if (field.isSynthetic()) continue;
            assertTrue(Modifier.isPrivate(field.getModifiers()), field.getName());
            assertFalse(Modifier.isStatic(field.getModifiers()), field.getName());
        }
    }

    @Test void theStoredBytesAreNeverExposed() throws Exception {
        SiteUrlIdentityRecord record = new SiteUrlIdentityRecord(site(1L, 2L), identity("https://example.com"));
        String first = record.getCanonicalOrigin();
        assertNotSame(first, record.getCanonicalOrigin(), "a new String per call; the array never leaves the entity");
        for (Method method : SiteUrlIdentityRecord.class.getMethods()) assertNotEquals(byte[].class, method.getReturnType(), method.getName());
    }

    @Test void tableKeysAndIndexUseTheApprovedNames() {
        assertNotNull(SiteUrlIdentityRecord.class.getAnnotation(Entity.class));
        assertNotNull(SiteUrlIdentityRecord.class.getAnnotation(Immutable.class), "rows are never updated");
        Table table = SiteUrlIdentityRecord.class.getAnnotation(Table.class);
        assertEquals("site_url_identities", table.name());
        assertEquals(1, table.uniqueConstraints().length);
        assertEquals("uq_site_url_identities_org_origin", table.uniqueConstraints()[0].name());
        assertArrayEquals(new String[]{"organization_id", "canonical_origin"}, table.uniqueConstraints()[0].columnNames(),
                "uniqueness is per organization (this is not query authorization)");
        assertEquals(1, table.indexes().length);
        assertEquals("ix_site_url_identities_org_site", table.indexes()[0].name());
        assertEquals("organization_id, site_id", table.indexes()[0].columnList());
        assertFalse(table.indexes()[0].unique());
    }

    @Test void everyColumnIsDeclaredMandatoryAndImmutableWithoutAssociations() throws Exception {
        Map<String, String> columns = new LinkedHashMap<>();
        for (Field field : SiteUrlIdentityRecord.class.getDeclaredFields()) {
            if (field.isSynthetic()) continue;
            Column column = field.getAnnotation(Column.class);
            assertNotNull(column, field.getName());
            assertFalse(column.nullable(), field.getName());
            assertFalse(column.updatable(), field.getName());
            assertTrue(column.insertable(), field.getName());
            for (Class<? extends java.lang.annotation.Annotation> relation : List.of(ManyToOne.class, OneToOne.class,
                    OneToMany.class, ManyToMany.class, JoinColumn.class, GeneratedValue.class, Version.class, Convert.class)) {
                assertNull(field.getAnnotation(relation), field.getName() + " " + relation.getSimpleName());
            }
            columns.put(field.getName(), column.name());
        }
        assertEquals(Map.of("siteId", "site_id", "organizationId", "organization_id", "canonicalOrigin", "canonical_origin"), columns);
        assertNotNull(field("siteId").getAnnotation(Id.class), "the Site id is the primary key, assigned, never generated");
        assertEquals(Long.class, field("siteId").getType());
        assertEquals(Long.class, field("organizationId").getType());
    }

    @Test void theCanonicalOriginIsExplicitlyBinaryAndBounded() throws Exception {
        Field origin = field("canonicalOrigin");
        assertEquals(byte[].class, origin.getType());
        assertEquals(SqlTypes.VARBINARY, origin.getAnnotation(JdbcTypeCode.class).value());
        Column column = origin.getAnnotation(Column.class);
        assertEquals("VARBINARY(261)", column.columnDefinition());
        assertEquals(SiteUrlIdentity.MAX_CANONICAL_LENGTH, column.length());
        assertEquals(261, column.length());
    }

    @Test void theSiteMappingIsUnchanged() throws Exception {
        // Increment 1 adds a dependent table; Site gains no field, association or method.
        assertEquals(Set.of("id", "organization", "name", "baseUrl", "status", "createdBy", "createdAt", "updatedAt", "rowVersion"),
                Arrays.stream(Site.class.getDeclaredFields()).filter(f -> !f.isSynthetic()).map(Field::getName).collect(Collectors.toSet()));
    }

    private static Field field(String name) throws Exception {
        Field field = SiteUrlIdentityRecord.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
