package com.smartaudits.v2.model;

import com.smartaudits.model.Usuario;
import com.smartaudits.v2.time.UtcMicros;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** Structural logical evaluation request only. An Audit must never exist without its first
 * Scan, so V7 exposes no creation API: the atomic Audit + first Scan factory arrives with V8.
 * Audit has no own status (derived from its Scans) and no current_scan_id. createdBy records
 * the actor only and grants no access. The SQL composite FK (organization_id, site_id) ->
 * sites(organization_id, id) is the authoritative tenant guard; this mapping joins Site by
 * site_id only, so JPA-generated DDL (tests only) cannot express that composite constraint.
 */
@Entity
@Table(name = "audits", uniqueConstraints =
        @UniqueConstraint(name = "uq_audits_org_audit", columnNames = {"organization_id", "id"}),
        indexes = {
                @Index(name = "ix_audits_org_site", columnList = "organization_id, site_id"),
                @Index(name = "ix_audits_created_by", columnList = "created_by_user_id")})
public class Audit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_audits_organization"))
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "site_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_audits_site"))
    private Site site;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_audits_created_by"))
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

    /** JPA only. No public constructor or factory: creating an Audit requires its first Scan (V8). */
    protected Audit() { }

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
    public Site getSite() { return site; }
    public Usuario getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getRowVersion() { return rowVersion; }
}
