package com.smartaudits.v2.model;

import com.smartaudits.v2.site.SiteUrlIdentity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Persisted Site URL identity (V10, docs/F4_1C_SITE_URL_IDENTITY.md, "Increment 1"): one row per
 * identified Site, keyed by the Site and unique per (organization, canonical origin).
 *
 * <p>Mapping only. It is built from a Site that already has an id and from an identity that
 * {@link SiteUrlIdentity#parse(String)} has already accepted; it never parses, repairs or resolves
 * anything, and the stored form is never parsed again. The organization is always taken from the
 * Site, and the composite foreign key {@code (organization_id, site_id) -> sites(organization_id,
 * id)} makes a row of another tenant impossible in the database too. Every column is immutable.
 * No service, repository, controller or endpoint uses it yet, and it grants no permission to
 * acquire anything.
 */
@Entity
@Immutable
@Table(name = "site_url_identities",
        uniqueConstraints = @UniqueConstraint(name = "uq_site_url_identities_org_origin",
                columnNames = {"organization_id", "canonical_origin"}),
        indexes = @Index(name = "ix_site_url_identities_org_site", columnList = "organization_id, site_id"))
public class SiteUrlIdentityRecord {
    @Id
    @Column(name = "site_id", nullable = false, updatable = false)
    private Long siteId;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private Long organizationId;

    /** ASCII bytes of the canonical form, compared byte by byte by the database. */
    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "canonical_origin", nullable = false, updatable = false,
            length = SiteUrlIdentity.MAX_CANONICAL_LENGTH, columnDefinition = "VARBINARY(261)")
    private byte[] canonicalOrigin;

    protected SiteUrlIdentityRecord() { }

    /** The Site must already be persisted; its organization is the tenant of the identity. */
    public SiteUrlIdentityRecord(Site site, SiteUrlIdentity identity) {
        Objects.requireNonNull(site, "site must not be null");
        Objects.requireNonNull(identity, "identity must not be null");
        this.siteId = Objects.requireNonNull(site.getId(), "site must already have an id");
        this.organizationId = Objects.requireNonNull(site.getOrganization().getId(),
                "the organization of the site must already have an id");
        this.canonicalOrigin = identity.canonicalForm().getBytes(StandardCharsets.US_ASCII);
    }

    public Long getSiteId() { return siteId; }
    public Long getOrganizationId() { return organizationId; }

    /** The stored canonical form as text; compare it as a string, never by parsing it again. */
    public String getCanonicalOrigin() { return new String(canonicalOrigin, StandardCharsets.US_ASCII); }
}
