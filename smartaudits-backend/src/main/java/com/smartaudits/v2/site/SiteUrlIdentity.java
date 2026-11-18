package com.smartaudits.v2.site;

import com.smartaudits.v2.acquisition.destination.DestinationUrl;
import com.smartaudits.v2.acquisition.destination.UrlParseResult;

import java.util.Objects;

/**
 * Offline identity of a registered Site (F4.1-C, docs/F4_1C_SITE_URL_IDENTITY.md): the web origin
 * exactly as the text names it, {@code scheme://host} in ASCII lowercase. Two texts name the same
 * Site only when both are accepted and their canonical forms are equal.
 *
 * <p>A text is accepted only when the destination profile accepts it (ADR-0002 rules 1-25) and it
 * is spelled as a bare origin: scheme, host, optionally the default port of the scheme and
 * optionally one final {@code /}. Nothing is ever stripped, decoded, guessed or resolved: a path,
 * a query or a fragment (even empty) is rejected, never removed. Only three protocol equivalences
 * are applied: ASCII case of scheme and host, the explicit default port and the root slash.
 * http and https, {@code www} and the bare name, and every subdomain stay distinct.
 *
 * <p>It is an identity, not a permission: it says nothing about DNS, addresses, TLS or whether the
 * Site may be acquired, and every future acquisition still needs the complete destination policy.
 * It carries no tenant: uniqueness is always the pair of organization and canonical form.
 * Only {@link #parse(String)} creates it. {@link #toString()} never reveals the host.
 */
public final class SiteUrlIdentity {
    /** Longest canonical form: {@code https://} plus a host of 253 characters. ASCII, so also bytes. */
    public static final int MAX_CANONICAL_LENGTH = 261;

    private final String scheme;
    private final String host;
    private final String canonical;

    private SiteUrlIdentity(String scheme, String host) {
        this.scheme = scheme;
        this.host = host;
        this.canonical = scheme + "://" + host;
    }

    /**
     * Analyses an untrusted text as is (no trim, repair or decoding). Never resolves, connects or
     * logs. An invalid text is a typed rejection; only {@code null} is a contract error.
     */
    public static SiteUrlIdentityResult parse(String input) {
        Objects.requireNonNull(input, "url must not be null");
        UrlParseResult parsed = DestinationUrl.parse(input);
        if (parsed instanceof UrlParseResult.Rejected rejected) {
            return new SiteUrlIdentityResult.Rejected(rejected);
        }
        DestinationUrl url = ((UrlParseResult.Parsed) parsed).url();
        SiteUrlIdentity identity = new SiteUrlIdentity(url.scheme(), url.host().name());
        if (isOriginSpelling(input, identity.canonical, defaultPort(identity.scheme))) {
            return new SiteUrlIdentityResult.Accepted(identity);
        }
        // Not a bare origin. The order (path, query, fragment) only chooses the reason reported.
        if (!url.path().equals("/")) {
            return new SiteUrlIdentityResult.Rejected(SiteUrlRejection.PATH);
        }
        if (url.query().isPresent()) {
            return new SiteUrlIdentityResult.Rejected(SiteUrlRejection.QUERY);
        }
        if (input.indexOf('#') >= 0) {
            return new SiteUrlIdentityResult.Rejected(SiteUrlRejection.FRAGMENT);
        }
        // Unreachable with the current destination profile. Kept so that some widenings of that
        // profile, such as more port forms, are refused here instead of accepted silently. It does
        // not cover everything: host grammar and length rules are inherited from the parser, so
        // relaxing them (for example, admitting xn-- labels) would widen Site identity directly.
        // Such parser changes need a coordinated Site identity review and regression tests.
        return new SiteUrlIdentityResult.Rejected(SiteUrlRejection.NOT_AN_ORIGIN);
    }

    /** The default port written in decimal, or {@code null} for a scheme this contract does not know. */
    static String defaultPort(String scheme) {
        if (scheme.equals("https")) {
            return "443";
        }
        return scheme.equals("http") ? "80" : null;
    }

    /**
     * The whole acceptance rule of the Site level: {@code text} is {@code origin}, comparing only
     * A-Z with a-z, optionally followed by {@code :defaultPort} and then optionally by one {@code /}.
     * {@code origin} is already ASCII lowercase. Never uses a locale.
     */
    static boolean isOriginSpelling(String text, String origin, String defaultPort) {
        if (defaultPort == null || text.length() < origin.length()) {
            return false;
        }
        for (int i = 0; i < origin.length(); i++) {
            char c = text.charAt(i);
            char folded = c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c;
            if (folded != origin.charAt(i)) {
                return false;
            }
        }
        String tail = text.substring(origin.length());
        if (tail.startsWith(":" + defaultPort)) {
            tail = tail.substring(defaultPort.length() + 1);
        }
        return tail.isEmpty() || tail.equals("/");
    }

    /** {@code http} or {@code https}, lowercase. */
    public String scheme() {
        return scheme;
    }

    /** The host name in ASCII lowercase, exactly the labels written: no label is added or removed. */
    public String host() {
        return host;
    }

    /**
     * {@code scheme://host}: no port, no path, no final slash. ASCII, at most
     * {@link #MAX_CANONICAL_LENGTH} characters, compared byte by byte. Tenant data: never log it.
     */
    public String canonicalForm() {
        return canonical;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SiteUrlIdentity that && canonical.equals(that.canonical);
    }

    @Override
    public int hashCode() {
        return canonical.hashCode();
    }

    @Override
    public String toString() {
        return "SiteUrlIdentity[" + scheme + "]";
    }
}
