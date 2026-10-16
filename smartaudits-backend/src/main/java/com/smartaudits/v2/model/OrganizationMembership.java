package com.smartaudits.v2.model;

import com.smartaudits.model.Usuario;
import com.smartaudits.v2.time.UtcMicros;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;

/** Structural membership only. Creation does not authorize the caller.
 * Revocation/reactivation require a future transactional policy; no lifecycle
 * mutator is exposed here. The organization/user pair is permanent.
 */
@Entity
@Table(name = "organization_memberships", uniqueConstraints =
        @UniqueConstraint(name = "uq_organization_memberships_org_user", columnNames = {"organization_id", "user_id"}),
        indexes = @Index(name = "ix_organization_memberships_user", columnList = "user_id"))
public class OrganizationMembership {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_organization_memberships_organization"))
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_organization_memberships_user"))
    private Usuario user;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "role", nullable = false, updatable = false, length = 16)
    private MembershipRole role;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 16)
    private MembershipStatus status;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "updated_at", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant updatedAt;

    @Version
    @Column(name = "row_version", nullable = false, columnDefinition = "BIGINT DEFAULT 0")
    private Long rowVersion;

    protected OrganizationMembership() { }

    /** Time comes from the caller's injected UTC Clock, never an implicit clock. */
    public OrganizationMembership(Organization organization, Usuario user, Instant createdAt) {
        this.organization = Objects.requireNonNull(organization, "organization must not be null");
        this.user = Objects.requireNonNull(user, "user must not be null");
        this.createdAt = UtcMicros.truncate(createdAt);
        this.updatedAt = this.createdAt;
        this.role = MembershipRole.OWNER;
        this.status = MembershipStatus.ACTIVE;
    }

    @PrePersist
    protected void normalizeInsert() {
        createdAt = UtcMicros.truncate(createdAt);
        updatedAt = UtcMicros.truncate(updatedAt);
    }

    @PreUpdate
    protected void normalizeUpdate() {
        updatedAt = UtcMicros.truncate(updatedAt);
    }

    public Long getId() { return id; }
    public Organization getOrganization() { return organization; }
    public Usuario getUser() { return user; }
    public MembershipRole getRole() { return role; }
    public MembershipStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getRowVersion() { return rowVersion; }
}
