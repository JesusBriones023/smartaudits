CREATE TABLE organization_memberships (
    id BIGINT NOT NULL AUTO_INCREMENT,
    organization_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_organization_memberships_org_user (organization_id, user_id),
    KEY ix_organization_memberships_user (user_id),
    CONSTRAINT fk_organization_memberships_organization
        FOREIGN KEY (organization_id) REFERENCES organizations (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_organization_memberships_user
        FOREIGN KEY (user_id) REFERENCES usuarios (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_organization_memberships_role CHECK (BINARY role IN ('OWNER')),
    CONSTRAINT ck_organization_memberships_status CHECK (BINARY status IN ('ACTIVE','REVOKED'))
) ENGINE=InnoDB
  DEFAULT CHARACTER SET=utf8mb4
  COLLATE=utf8mb4_unicode_520_ci;
