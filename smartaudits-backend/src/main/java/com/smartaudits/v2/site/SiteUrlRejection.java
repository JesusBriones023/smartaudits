package com.smartaudits.v2.site;

/**
 * Why a text is not a Site identity. Stable identifiers: renaming or reinterpreting one changes
 * the contract of docs/F4_1C_SITE_URL_IDENTITY.md. The first applicable reason, in this order, is
 * the one reported.
 */
public enum SiteUrlRejection {
    /** The destination profile (ADR-0002 rules 1-25) rejected the text; its own code says why. */
    DESTINATION_SYNTAX,
    /** A path other than the root. It is never stripped: a Site is identified by its origin only. */
    PATH,
    /** A query, even an empty one. */
    QUERY,
    /** A fragment, even an empty one. */
    FRAGMENT,
    /** Accepted by the destination profile but not spelled as a bare origin; fail-closed default. */
    NOT_AN_ORIGIN
}
