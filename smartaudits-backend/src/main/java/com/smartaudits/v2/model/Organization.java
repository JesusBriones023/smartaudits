package com.smartaudits.v2.model;

import com.smartaudits.model.Usuario;
import com.smartaudits.v2.time.UtcMicros;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;

/** Organizational root only. A personal owner is an idempotency key, never authorization.
 * Human names use the migration's Unicode collation. Future services must validate
 * nonblank names; a SQL TRIM CHECK would not define robust Unicode blank semantics.
 */
@Entity
@Table(name = "organizations", uniqueConstraints =
        @UniqueConstraint(name = "uq_organizations_personal_owner", columnNames = "personal_owner_user_id"))
public class Organization {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "personal_owner_user_id",
            foreignKey = @ForeignKey(name = "fk_organizations_personal_owner"))
    private Usuario personalOwner;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "updated_at", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant updatedAt;

    @Version
    @Column(name = "row_version", nullable = false, columnDefinition = "BIGINT DEFAULT 0")
    private Long rowVersion;

    protected Organization() { }

    /** The caller supplies time from its injected UTC Clock; no implicit system clock. */
    public Organization(String name, Usuario personalOwner, Instant createdAt) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.personalOwner = personalOwner;
        this.createdAt = UtcMicros.truncate(createdAt);
        this.updatedAt = this.createdAt;
    }

    public void rename(String name, Instant changedAt) {
        String nextName = Objects.requireNonNull(name, "name must not be null");
        Instant nextTime = UtcMicros.truncate(changedAt);
        this.name = nextName;
        this.updatedAt = nextTime;
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
    public String getName() { return name; }
    public Usuario getPersonalOwner() { return personalOwner; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getRowVersion() { return rowVersion; }
}
