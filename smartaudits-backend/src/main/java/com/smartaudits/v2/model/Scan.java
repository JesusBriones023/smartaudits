package com.smartaudits.v2.model;

import com.smartaudits.model.Usuario;
import com.smartaudits.v2.time.UtcMicros;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** One concrete attempt to execute an Audit (ADR-0001 section 8). Scans are created only by
 * the package-internal factories used by AuditScanService and change state only through the
 * ADR transitions; terminal states are immutable. V8_create_scans is the authority for the
 * generated active_audit_id column and its UNIQUE key, which this mapping deliberately omits:
 * the database rejects a second non-terminal Scan for the same Audit even if a caller bypasses
 * the service. The SQL composite FK (organization_id, audit_id) -> audits(organization_id, id)
 * guards tenancy; this mapping joins Audit by audit_id only. No failure code, sanitized detail,
 * execution or recovery exists before Phase 4.
 */
@Entity
@Table(name = "scans", uniqueConstraints =
        @UniqueConstraint(name = "uq_scans_audit_attempt", columnNames = {"audit_id", "attempt_number"}),
        indexes = {
                @Index(name = "ix_scans_org_audit", columnList = "organization_id, audit_id"),
                @Index(name = "ix_scans_created_by", columnList = "created_by_user_id")})
public class Scan {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_scans_organization"))
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "audit_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_scans_audit"))
    private Audit audit;

    @Column(name = "attempt_number", nullable = false, updatable = false)
    private int attemptNumber;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 16)
    private ScanStatus status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_scans_created_by"))
    private Usuario createdBy;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "updated_at", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant updatedAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "started_at", columnDefinition = "DATETIME(6)")
    private Instant startedAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "finished_at", columnDefinition = "DATETIME(6)")
    private Instant finishedAt;

    @Version
    @Column(name = "row_version", nullable = false, columnDefinition = "BIGINT DEFAULT 0")
    private Long rowVersion;

    /** JPA only. */
    protected Scan() { }

    private Scan(Audit audit, int attemptNumber, Usuario createdBy, Instant createdAt) {
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.organization = Objects.requireNonNull(audit.getOrganization(), "audit organization must not be null");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy must not be null");
        this.createdAt = UtcMicros.truncate(createdAt);
        this.updatedAt = this.createdAt;
        this.attemptNumber = attemptNumber;
        this.status = ScanStatus.PENDING;
    }

    /** First attempt of a new Audit; only Audit.startWithFirstScan calls it. */
    static Scan first(Audit audit, Usuario createdBy, Instant createdAt) {
        return new Scan(audit, 1, createdBy, createdAt);
    }

    /** Retry attempt; the caller must hold the Audit lock and derive the number via nextAttemptAfter. */
    static Scan retry(Audit audit, int attemptNumber, Usuario createdBy, Instant createdAt) {
        if (attemptNumber < 2) {
            throw new IllegalArgumentException("A retry must use attempt number 2 or higher");
        }
        return new Scan(audit, attemptNumber, createdBy, createdAt);
    }

    /** Current state of one committed attempt, read from the database after the Audit lock. */
    record Attempt(int number, ScanStatus status) {
        Attempt {
            Objects.requireNonNull(status, "status must not be null");
        }
    }

    /** Next attempt number per ADR-0001, decided by attempt_number only (never by timestamps):
     * rejects any active Scan, any SUCCEEDED Scan and any latest attempt that is not FAILED or
     * CANCELLED, and refuses INT overflow. Committed numbers are never reused. */
    static int nextAttemptAfter(List<Attempt> attempts) {
        Objects.requireNonNull(attempts, "attempts must not be null");
        if (attempts.isEmpty()) {
            throw new IllegalStateException("An Audit without Scans violates the Audit + first Scan invariant");
        }
        Attempt latest = null;
        for (Attempt attempt : attempts) {
            if (attempt.status().isActive()) {
                throw new IllegalStateException("The Audit already has an active Scan");
            }
            if (attempt.status() == ScanStatus.SUCCEEDED) {
                throw new IllegalStateException("A SUCCEEDED Audit cannot be retried");
            }
            if (latest == null || attempt.number() > latest.number()) {
                latest = attempt;
            }
        }
        if (latest.status() != ScanStatus.FAILED && latest.status() != ScanStatus.CANCELLED) {
            throw new IllegalStateException("Only a FAILED or CANCELLED latest attempt can be retried");
        }
        if (latest.number() == Integer.MAX_VALUE) {
            throw new IllegalStateException("Attempt number overflow");
        }
        return latest.number() + 1;
    }

    /** PENDING -> RUNNING. Preconditions are validated before any field changes. */
    void start(Instant now) {
        Instant at = nextInstant(now);
        requireStatus(ScanStatus.PENDING, "start");
        status = ScanStatus.RUNNING;
        startedAt = at;
        updatedAt = at;
    }

    /** RUNNING -> SUCCEEDED. */
    void succeed(Instant now) {
        finishFromRunning(now, ScanStatus.SUCCEEDED, "succeed");
    }

    /** RUNNING -> FAILED. */
    void fail(Instant now) {
        finishFromRunning(now, ScanStatus.FAILED, "fail");
    }

    /** PENDING or RUNNING -> CANCELLED; a second cancellation is rejected without changes. */
    void cancel(Instant now) {
        Instant at = nextInstant(now);
        if (!status.isActive()) {
            throw new IllegalStateException("Cannot cancel a " + status + " Scan");
        }
        status = ScanStatus.CANCELLED;
        finishedAt = at;
        updatedAt = at;
    }

    private void finishFromRunning(Instant now, ScanStatus target, String transition) {
        Instant at = nextInstant(now);
        requireStatus(ScanStatus.RUNNING, transition);
        status = target;
        finishedAt = at;
        updatedAt = at;
    }

    private void requireStatus(ScanStatus expected, String transition) {
        if (status != expected) {
            throw new IllegalStateException("Cannot " + transition + " a " + status + " Scan");
        }
    }

    /** Microsecond UTC instant that never moves back before the last recorded change. */
    private Instant nextInstant(Instant now) {
        Instant at = UtcMicros.truncate(now);
        if (at.isBefore(updatedAt)) {
            throw new IllegalArgumentException("Scan time must not move backwards");
        }
        return at;
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
    public Audit getAudit() { return audit; }
    public int getAttemptNumber() { return attemptNumber; }
    public ScanStatus getStatus() { return status; }
    public Usuario getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public Long getRowVersion() { return rowVersion; }
}
