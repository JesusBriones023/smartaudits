package com.smartaudits.v2.model;

import com.smartaudits.model.Usuario;
import com.smartaudits.v2.time.UtcMicros;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;

/** Structural registered web target only. baseUrl is the literal text supplied, never a
 * canonical identity nor permission to visit it: no normalization, deduplication, DNS,
 * redirects, SSRF policy or crawling here (deferred to F4). Functional validation of
 * names/URLs, including blank and dangerous values, belongs to a future service.
 * createdBy records the actor only; it grants no access. Archiving requires a future
 * transactional policy; no lifecycle mutator is exposed here.
 */
@Entity
@Table(name = "sites", uniqueConstraints =
        @UniqueConstraint(name = "uq_sites_org_site", columnNames = {"organization_id", "id"}),
        indexes = @Index(name = "ix_sites_created_by", columnList = "created_by_user_id"))
public class Site {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_sites_organization"))
    private Organization organization;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "base_url", nullable = false, updatable = false, length = 2048)
    private String baseUrl;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 16)
    private SiteStatus status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_sites_created_by"))
    private Usuario createdBy;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "updated_at", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant updatedAt;

    @Version
    @Column(name = "row_version", nullable = false, columnDefinition = "BIGINT DEFAULT 0")
    private Long rowVersion;

    protected Site() { }

    /** Time comes from the caller's injected UTC Clock, never an implicit clock. */
    public Site(Organization organization, String name, String baseUrl, Usuario createdBy, Instant createdAt) {
        this.organization = Objects.requireNonNull(organization, "organization must not be null");
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy must not be null");
        this.createdAt = UtcMicros.truncate(createdAt);
        this.updatedAt = this.createdAt;
        this.status = SiteStatus.ACTIVE;
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
    public String getName() { return name; }
    public String getBaseUrl() { return baseUrl; }
    public SiteStatus getStatus() { return status; }
    public Usuario getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getRowVersion() { return rowVersion; }
}
