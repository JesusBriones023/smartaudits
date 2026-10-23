package com.smartaudits.v2.acquisition.destination;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The frozen, immutable destination catalog of ADR-0002 (sections 11 and 12): the approved snapshot
 * IANA-SNAPSHOT-02, compiled into Java constants. Nothing is read, downloaded or resolved at runtime.
 * The tests rebuild the canonical serialization from these constants and compare its SHA-256 with
 * {@link #CANONICAL_SHA256}.
 */
public final class AddressCatalog {
    /** Readable revision identifier, with its freeze sequence (02). */
    public static final String REVISION = "IANA-SNAPSHOT-02";
    /** Freeze date of the approved snapshot. */
    public static final String FROZEN_ON = "2026-10-21";
    /** Identifier of the approved snapshot run. */
    public static final String SNAPSHOT_RUN = "20261021T135838Z-a273b428";
    /** SHA-256 (uppercase hex) of the approved canonical serialization, catalog.tsv. */
    public static final String CANONICAL_SHA256 = "75987A6CB1B520F172B454E7E1D8646ECDA08A95908B7C4D392ADFFC9CDF6F6D";

    private static final Pattern CANONICAL_HOST = Pattern.compile("[a-z0-9-]+([.][a-z0-9-]+)*");
    private static final AddressCatalog SNAPSHOT = new AddressCatalog(AddressCatalogData.sources(),
            AddressCatalogData.entries());

    /** Provenance of one registry source of the snapshot (a {@code #source} header line). */
    public static final class Source {
        private final String id;
        private final String updated;
        private final String sha256;
        private final String url;

        Source(String id, String updated, String sha256, String url) {
            this.id = Objects.requireNonNull(id, "id must not be null");
            this.updated = Objects.requireNonNull(updated, "updated must not be null");
            this.sha256 = Objects.requireNonNull(sha256, "sha256 must not be null");
            this.url = Objects.requireNonNull(url, "url must not be null");
        }

        public String id() {
            return id;
        }

        /** Last update date published by the registry. */
        public String updated() {
            return updated;
        }

        /** SHA-256 (uppercase hex) of the raw registry file. */
        public String sha256() {
            return sha256;
        }

        /** Official registry URL. */
        public String url() {
            return url;
        }

        @Override
        public String toString() {
            return "Source[" + id + "]";
        }
    }

    private final List<Source> sources;
    private final List<CatalogEntry> entries;
    private final List<IpPrefix> positivePrefixes;
    private final List<IpPrefix> negativePrefixes;
    private final List<String> forbiddenNames;

    private AddressCatalog(List<Source> sources, List<CatalogEntry> entries) {
        this.sources = List.copyOf(sources);
        this.entries = List.copyOf(entries);
        List<IpPrefix> positive = new ArrayList<>();
        List<IpPrefix> negative = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (CatalogEntry entry : this.entries) {
            if (entry.kind() == CatalogEntry.Kind.ADDR) {
                IpPrefix prefix = entry.prefix().orElseThrow();
                (entry.layer() == CatalogEntry.Layer.POSITIVE ? positive : negative).add(prefix);
            } else if (entry.layer() == CatalogEntry.Layer.NEGATIVE) {
                names.add(entry.network());
            }
        }
        this.positivePrefixes = List.copyOf(positive);
        this.negativePrefixes = List.copyOf(negative);
        this.forbiddenNames = List.copyOf(names);
    }

    /** The approved snapshot. It is the only catalog instance. */
    public static AddressCatalog snapshot() {
        return SNAPSHOT;
    }

    public String revision() {
        return REVISION;
    }

    public String canonicalSha256() {
        return CANONICAL_SHA256;
    }

    /** The five registry sources, in serialization order. */
    public List<Source> sources() {
        return sources;
    }

    /** All 362 entries, in snapshot order. */
    public List<CatalogEntry> entries() {
        return entries;
    }

    /** Layer 1: prefixes assigned for global unicast (IPv4 /8 ALLOCATED or LEGACY, IPv6 RIR assignments). */
    public List<IpPrefix> positivePrefixes() {
        return positivePrefixes;
    }

    /** Layer 2: every special-purpose registry prefix plus the product rules. */
    public List<IpPrefix> negativePrefixes() {
        return negativePrefixes;
    }

    /** Distinct forbidden names (layer NEGATIVE), in snapshot order. */
    public List<String> forbiddenNames() {
        return forbiddenNames;
    }

    /**
     * Rule 18 of ADR-0002: a host is forbidden if it equals a forbidden name or is a subdomain of
     * one, compared by whole labels (never by text suffix). The argument must already be a canonical
     * host (lowercase ASCII letters, digits and hyphens, non-empty dot-separated labels), as produced
     * by the future URL parser; anything else is a contract error.
     */
    boolean isForbiddenName(String canonicalHost) {
        Objects.requireNonNull(canonicalHost, "host must not be null");
        if (!CANONICAL_HOST.matcher(canonicalHost).matches()) {
            throw new IllegalArgumentException("host must be canonical");
        }
        for (String name : forbiddenNames) {
            if (canonicalHost.equals(name)) {
                return true;
            }
            int boundary = canonicalHost.length() - name.length() - 1;
            if (boundary > 0 && canonicalHost.charAt(boundary) == '.' && canonicalHost.endsWith(name)) {
                return true;
            }
        }
        return false;
    }

    String formatId() {
        return AddressCatalogData.FORMAT_ID;
    }

    String fileName() {
        return AddressCatalogData.FILE_NAME;
    }

    String policy() {
        return AddressCatalogData.POLICY;
    }

    @Override
    public String toString() {
        return "AddressCatalog[" + REVISION + "]";
    }
}
