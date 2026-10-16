package com.smartaudits.v2.model;

import com.smartaudits.model.Usuario;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class OrganizationMembershipTest {
    private static final Instant NANOS = Instant.parse("2024-02-29T23:58:57.987654321Z");
    private final Organization organization = new Organization("Example", null, NANOS);
    private final Usuario user = new Usuario();

    @Test void createsOnlyOwnerActiveWithNormalizedTime() {
        var membership = new OrganizationMembership(organization, user, NANOS);
        assertSame(organization, membership.getOrganization());
        assertSame(user, membership.getUser());
        assertEquals(MembershipRole.OWNER, membership.getRole());
        assertEquals(MembershipStatus.ACTIVE, membership.getStatus());
        assertEquals(Instant.parse("2024-02-29T23:58:57.987654Z"), membership.getCreatedAt());
        assertEquals(membership.getCreatedAt(), membership.getUpdatedAt());
        assertNull(membership.getId());
        assertNull(membership.getRowVersion());
    }

    @Test void rejectsMissingCreationInputs() {
        assertThrows(NullPointerException.class, () -> new OrganizationMembership(null, user, NANOS));
        assertThrows(NullPointerException.class, () -> new OrganizationMembership(organization, null, NANOS));
        assertThrows(NullPointerException.class, () -> new OrganizationMembership(organization, user, null));
    }

    @Test void ownershipDoesNotDependOnPersonalOwnerOrLegacyRole() {
        user.setActivo(false);
        user.setRole(com.smartaudits.model.Role.ADMIN);
        var membership = new OrganizationMembership(organization, user, NANOS);
        assertNull(organization.getPersonalOwner());
        assertEquals(MembershipRole.OWNER, membership.getRole());
        assertEquals(MembershipStatus.ACTIVE, membership.getStatus());
        assertFalse(user.getActivo()); // Persistence does not change legacy identity or authorize access.
    }

    @Test void exposesNoLifecycleOrIdentityMutationApi() {
        Set<String> methods = Arrays.stream(OrganizationMembership.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers())).map(m -> m.getName()).collect(Collectors.toSet());
        assertEquals(Set.of("getId", "getOrganization", "getUser", "getRole", "getStatus",
                "getCreatedAt", "getUpdatedAt", "getRowVersion"), methods);
        assertEquals(1, OrganizationMembership.class.getConstructors().length);
        assertArrayEquals(new Class<?>[]{Organization.class, Usuario.class, Instant.class},
                OrganizationMembership.class.getConstructors()[0].getParameterTypes());
    }

    @Test void associationsCannotCascadeOrChangeIdentity() throws Exception {
        for (String name : Set.of("organization", "user")) {
            var field = OrganizationMembership.class.getDeclaredField(name);
            var relation = field.getAnnotation(ManyToOne.class);
            assertEquals(FetchType.LAZY, relation.fetch());
            assertFalse(relation.optional());
            assertEquals(0, relation.cascade().length);
            var join = field.getAnnotation(JoinColumn.class);
            assertFalse(join.nullable());
            assertFalse(join.updatable());
        }
    }

    @Test void enumsUseOnlyApprovedValuesAndExplicitVarchar() throws Exception {
        assertArrayEquals(new MembershipRole[]{MembershipRole.OWNER}, MembershipRole.values());
        assertArrayEquals(new MembershipStatus[]{MembershipStatus.ACTIVE, MembershipStatus.REVOKED}, MembershipStatus.values());
        for (String name : Set.of("role", "status")) {
            var field = OrganizationMembership.class.getDeclaredField(name);
            assertEquals(EnumType.STRING, field.getAnnotation(Enumerated.class).value());
            assertEquals(SqlTypes.VARCHAR, field.getAnnotation(JdbcTypeCode.class).value());
            assertEquals(16, field.getAnnotation(Column.class).length());
            assertFalse(field.getAnnotation(Column.class).nullable());
        }
    }

    @Test void callbacksNormalizeHydratedNanosWithoutChangingCreationOnUpdate() throws Exception {
        var membership = new OrganizationMembership(organization, user, NANOS);
        set(membership, "createdAt", NANOS);
        set(membership, "updatedAt", NANOS);
        membership.normalizeInsert();
        Instant created = membership.getCreatedAt();
        Instant update = Instant.parse("2024-03-31T02:30:00.999999999Z");
        set(membership, "updatedAt", update);
        membership.normalizeUpdate();
        assertEquals(Instant.parse("2024-02-29T23:58:57.987654Z"), created);
        assertEquals(created, membership.getCreatedAt());
        assertEquals(Instant.parse("2024-03-31T02:30:00.999999Z"), membership.getUpdatedAt());
    }

    @Test void timeAndVersionMappingRetainTheV2Contract() throws Exception {
        for (String name : Set.of("createdAt", "updatedAt")) {
            var field = OrganizationMembership.class.getDeclaredField(name);
            assertEquals(Instant.class, field.getType());
            assertEquals(SqlTypes.TIMESTAMP_UTC, field.getAnnotation(JdbcTypeCode.class).value());
            assertEquals("DATETIME(6)", field.getAnnotation(Column.class).columnDefinition());
        }
        assertFalse(OrganizationMembership.class.getDeclaredField("createdAt").getAnnotation(Column.class).updatable());
        var version = OrganizationMembership.class.getDeclaredField("rowVersion");
        assertEquals(Long.class, version.getType());
        assertNotNull(version.getAnnotation(Version.class));
    }

    static void set(OrganizationMembership row, String name, Object value) throws Exception {
        var field = OrganizationMembership.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(row, value);
    }
}
