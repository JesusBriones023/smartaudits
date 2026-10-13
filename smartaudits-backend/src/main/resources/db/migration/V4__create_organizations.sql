CREATE TABLE organizations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL,
    personal_owner_user_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_organizations_personal_owner (personal_owner_user_id),
    CONSTRAINT fk_organizations_personal_owner
        FOREIGN KEY (personal_owner_user_id) REFERENCES usuarios (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB
  DEFAULT CHARACTER SET=utf8mb4
  COLLATE=utf8mb4_unicode_520_ci;
