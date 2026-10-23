package com.smartaudits.v2.acquisition.destination;

import java.util.Optional;

/**
 * Canonical acquisition destination (ADR-0002 sections 4, 6 and 13): lowercase scheme, validated
 * host, effective port derived from the scheme, non-empty raw path, raw query absent or present, and
 * no fragment. Only {@link #parse(String)} creates it. Equality is the canonical form and means only
 * "same acquisition destination", never same Site or same Page (section 7). {@link #toString()}
 * reveals only the scheme and the effective port (section 15).
 */
public final class DestinationUrl {
    private final String scheme;
    private final ValidatedHost host;
    private final int port;
    private final String path;
    private final String query;
    private final String canonical;

    DestinationUrl(String scheme, ValidatedHost host, String path, String query) {
        this.scheme = scheme;
        this.host = host;
        this.port = scheme.equals("https") ? 443 : 80;
        this.path = path;
        this.query = query;
        this.canonical = scheme + "://" + host.name() + path + (query == null ? "" : "?" + query);
    }

    /**
     * Analyses an untrusted URL text with rules 1-25 of ADR-0002 section 5, in their fixed order.
     * The text is evaluated as is (no trim, repair or decoding). Never resolves, connects or logs.
     */
    public static UrlParseResult parse(String input) {
        return DestinationUrlParser.parse(input);
    }

    /** {@code http} or {@code https}, lowercase. */
    public String scheme() {
        return scheme;
    }

    public ValidatedHost host() {
        return host;
    }

    /** Effective port: 80 for http, 443 for https. Explicit ports are only accepted when equal to it. */
    public int port() {
        return port;
    }

    /** Raw path exactly as received (escapes and case preserved), or {@code /} if it was empty. */
    public String path() {
        return path;
    }

    /** Raw query without {@code ?}; empty if the input ended in {@code ?}; absent if there was no {@code ?}. */
    public Optional<String> query() {
        return Optional.ofNullable(query);
    }

    /** The canonical form (section 6). Contains the host, path and query: never log it. */
    public String canonicalForm() {
        return canonical;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DestinationUrl that && canonical.equals(that.canonical);
    }

    @Override
    public int hashCode() {
        return canonical.hashCode();
    }

    @Override
    public String toString() {
        return "DestinationUrl[" + scheme + ":" + port + "]";
    }
}
