CREATE TABLE site_url_identities (
    site_id BIGINT NOT NULL,
    organization_id BIGINT NOT NULL,
    canonical_origin VARBINARY(261) NOT NULL,
    PRIMARY KEY (site_id),
    UNIQUE KEY uq_site_url_identities_org_origin (organization_id, canonical_origin),
    KEY ix_site_url_identities_org_site (organization_id, site_id),
    CONSTRAINT fk_site_url_identities_site
        FOREIGN KEY (organization_id, site_id) REFERENCES sites (organization_id, id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_site_url_identities_alphabet
        CHECK (canonical_origin NOT REGEXP '[^a-z0-9.:/-]'),
    CONSTRAINT ck_site_url_identities_scheme_length
        CHECK ((canonical_origin LIKE 'http://%' AND OCTET_LENGTH(canonical_origin) <= 260)
            OR (canonical_origin LIKE 'https://%' AND OCTET_LENGTH(canonical_origin) <= 261)),
    CONSTRAINT ck_site_url_identities_grammar
        CHECK (canonical_origin REGEXP '^https?://[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?([.][a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$'),
    CONSTRAINT ck_site_url_identities_labels
        CHECK (canonical_origin NOT REGEXP '[/.][a-z0-9][a-z0-9-]--'
            AND canonical_origin NOT REGEXP '[.]([0-9]+|0x[0-9a-f]*)$')
) ENGINE=InnoDB
  DEFAULT CHARACTER SET=utf8mb4
  COLLATE=utf8mb4_unicode_520_ci;
