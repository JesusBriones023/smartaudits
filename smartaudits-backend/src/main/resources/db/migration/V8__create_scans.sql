CREATE TABLE scans (
    id BIGINT NOT NULL AUTO_INCREMENT,
    organization_id BIGINT NOT NULL,
    audit_id BIGINT NOT NULL,
    attempt_number INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_by_user_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    started_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    active_audit_id BIGINT GENERATED ALWAYS AS
        (CASE WHEN BINARY status IN ('PENDING','RUNNING') THEN audit_id ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uq_scans_audit_attempt (audit_id, attempt_number),
    UNIQUE KEY uq_scans_one_active (active_audit_id),
    KEY ix_scans_org_audit (organization_id, audit_id),
    KEY ix_scans_created_by (created_by_user_id),
    CONSTRAINT fk_scans_organization
        FOREIGN KEY (organization_id) REFERENCES organizations (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_scans_audit
        FOREIGN KEY (organization_id, audit_id) REFERENCES audits (organization_id, id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_scans_created_by
        FOREIGN KEY (created_by_user_id) REFERENCES usuarios (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_scans_status
        CHECK (BINARY status IN ('PENDING','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
    CONSTRAINT ck_scans_attempt CHECK (attempt_number >= 1),
    CONSTRAINT ck_scans_lifecycle CHECK (
        (BINARY status = 'PENDING' AND started_at IS NULL AND finished_at IS NULL)
        OR (BINARY status = 'RUNNING' AND started_at IS NOT NULL AND finished_at IS NULL)
        OR (BINARY status IN ('SUCCEEDED','FAILED') AND started_at IS NOT NULL AND finished_at IS NOT NULL)
        OR (BINARY status = 'CANCELLED' AND finished_at IS NOT NULL)),
    CONSTRAINT ck_scans_chronology CHECK (
        updated_at >= created_at
        AND (started_at IS NULL OR started_at >= created_at)
        AND (finished_at IS NULL OR finished_at >= COALESCE(started_at, created_at))
        AND updated_at >= COALESCE(finished_at, started_at, created_at))
) ENGINE=InnoDB
  DEFAULT CHARACTER SET=utf8mb4
  COLLATE=utf8mb4_unicode_520_ci;
