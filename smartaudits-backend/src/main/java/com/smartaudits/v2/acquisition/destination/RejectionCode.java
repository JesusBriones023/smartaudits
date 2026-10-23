package com.smartaudits.v2.acquisition.destination;

/**
 * The eighteen stable rejection codes of ADR-0002 section 14. Renaming or reinterpreting a code
 * requires amending the ADR. The URL parser produces the syntax codes; the DNS and address codes
 * belong to the destination policy (later blocks of 4.1-A).
 */
public enum RejectionCode {
    URL_FORMAT,
    URL_TOO_LONG,
    URL_CHARACTERS,
    SCHEME,
    USERINFO,
    HOST_FORMAT,
    HOST_FORBIDDEN,
    PORT,
    IP_LITERAL,
    IDN_UNSUPPORTED,
    PERCENT_ENCODING,
    DOT_SEGMENT,
    DNS_FAILURE,
    DNS_EMPTY,
    DNS_INVALID,
    DNS_LIMIT,
    ADDRESS_FORBIDDEN,
    ADDRESS_MIXED
}
