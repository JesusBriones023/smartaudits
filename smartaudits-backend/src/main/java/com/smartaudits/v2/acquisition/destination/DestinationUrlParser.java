package com.smartaudits.v2.acquisition.destination;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Rules 1-25 of ADR-0002 section 5, in their fixed order: the first violated rule decides the code
 * and later rules are not evaluated. Rules 3-23 work on the minimal partition by delimiters (scheme,
 * authority, path, query, fragment); when a rule spans several components the order is path, query,
 * fragment. Each rule is linear in the input length, bounded by rule 1. No DNS, no network, no
 * locale-dependent case mapping, no decoding and no exception message of {@link URI} is ever kept.
 */
final class DestinationUrlParser {
    static final int MAX_URL_LENGTH = 2048;
    static final int MAX_HOST_LENGTH = 253;
    static final int MAX_LABEL_LENGTH = 63;

    private static final char BACKSLASH = (char) 92;

    private DestinationUrlParser() { }

    static UrlParseResult parse(String input) {
        Objects.requireNonNull(input, "url must not be null");
        Object outcome = evaluate(input);
        if (outcome instanceof RejectionCode code) {
            return new UrlParseResult.Rejected(code, AddressCatalog.REVISION);
        }
        return new UrlParseResult.Parsed((DestinationUrl) outcome);
    }

    /** Returns the {@link RejectionCode} of the first violated rule, or the accepted {@link DestinationUrl}. */
    private static Object evaluate(String input) {
        // Rule 1: more than 2048 UTF-16 units.
        if (input.length() > MAX_URL_LENGTH) {
            return RejectionCode.URL_TOO_LONG;
        }
        // Rule 2: U+0000-U+0020, U+007F or backslash anywhere.
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c <= 0x20 || c == 0x7F || c == BACKSLASH) {
                return RejectionCode.URL_CHARACTERS;
            }
        }
        // Rule 3: empty, no ':', '/', '?' or '#' before the first ':', or invalid scheme syntax.
        int colon = input.indexOf(':');
        if (input.isEmpty() || colon < 0) {
            return RejectionCode.URL_FORMAT;
        }
        String rawScheme = input.substring(0, colon);
        if (!isSchemeSyntax(rawScheme)) {
            return RejectionCode.URL_FORMAT;
        }
        // Rule 4: scheme other than http or https (ASCII-only comparison, scheme already ASCII).
        String scheme = asciiLower(rawScheme);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return RejectionCode.SCHEME;
        }
        // Rule 5: "scheme:" not followed by exactly "//".
        if (!input.startsWith("//", colon + 1)) {
            return RejectionCode.URL_FORMAT;
        }
        // Minimal partition (RFC 3986 appendix B cutting rule only).
        int authorityStart = colon + 3;
        int authorityEnd = input.length();
        for (int i = authorityStart; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                authorityEnd = i;
                break;
            }
        }
        String authority = input.substring(authorityStart, authorityEnd);
        int hash = input.indexOf('#', authorityEnd);
        int componentsEnd = hash >= 0 ? hash : input.length();
        int question = input.indexOf('?', authorityEnd);
        if (question >= componentsEnd) {
            question = -1;
        }
        String path = input.substring(authorityEnd, question >= 0 ? question : componentsEnd);
        String query = question >= 0 ? input.substring(question + 1, componentsEnd) : null;
        String fragment = hash >= 0 ? input.substring(hash + 1) : null;

        // Rule 6: userinfo, even empty.
        if (authority.indexOf('@') >= 0) {
            return RejectionCode.USERINFO;
        }
        // Rule 7: bracketed IP literal.
        if (authority.startsWith("[")) {
            return RejectionCode.IP_LITERAL;
        }
        // Rule 8: any non-ASCII character in the authority.
        for (int i = 0; i < authority.length(); i++) {
            if (authority.charAt(i) > 0x7F) {
                return RejectionCode.IDN_UNSUPPORTED;
            }
        }
        // Rule 9: percent-encoding in the authority.
        if (authority.indexOf('%') >= 0) {
            return RejectionCode.PERCENT_ENCODING;
        }
        // Rule 10: empty authority, empty host or more than one ':'.
        int portColon = authority.indexOf(':');
        if (authority.isEmpty() || (portColon >= 0 && authority.indexOf(':', portColon + 1) >= 0)) {
            return RejectionCode.HOST_FORMAT;
        }
        String rawHost = portColon >= 0 ? authority.substring(0, portColon) : authority;
        String port = portColon >= 0 ? authority.substring(portColon + 1) : null;
        if (rawHost.isEmpty()) {
            return RejectionCode.HOST_FORMAT;
        }
        // Rule 11: host ends in a number (WHATWG "ends in a number checker").
        if (endsInNumber(rawHost)) {
            return RejectionCode.IP_LITERAL;
        }
        // Rule 12: after ASCII lowercase, only a-z, 0-9, '-' and '.'.
        String host = asciiLower(rawHost);
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.')) {
                return RejectionCode.HOST_FORMAT;
            }
        }
        // Rule 13: final dot.
        if (host.endsWith(".")) {
            return RejectionCode.HOST_FORMAT;
        }
        // Rule 14: more than 253 characters.
        if (host.length() > MAX_HOST_LENGTH) {
            return RejectionCode.HOST_FORMAT;
        }
        // Rule 15: empty label, label over 63 characters, leading or trailing hyphen.
        List<String> labels = splitLabels(host);
        for (String label : labels) {
            if (label.isEmpty() || label.length() > MAX_LABEL_LENGTH || label.startsWith("-") || label.endsWith("-")) {
                return RejectionCode.HOST_FORMAT;
            }
        }
        // Rule 16: A-label or R-LDH label ("--" in positions 3 and 4).
        for (String label : labels) {
            if (label.startsWith("xn--") || (label.length() >= 4 && label.charAt(2) == '-' && label.charAt(3) == '-')) {
                return RejectionCode.IDN_UNSUPPORTED;
            }
        }
        // Rule 17: a single label is a local name.
        if (labels.size() == 1) {
            return RejectionCode.HOST_FORBIDDEN;
        }
        // Rule 18: equal to a forbidden catalog name or a subdomain of one (whole labels).
        if (AddressCatalog.snapshot().isForbiddenName(host)) {
            return RejectionCode.HOST_FORBIDDEN;
        }
        // Rule 19: an explicit port must be the default port of the scheme, written canonically.
        if (port != null && !port.equals(scheme.equals("https") ? "443" : "80")) {
            return RejectionCode.PORT;
        }
        // Rule 20: non-ASCII, or ASCII not allowed in the component (path, query, fragment).
        if (!componentCharactersAllowed(path, false) || (query != null && !componentCharactersAllowed(query, true))
                || (fragment != null && !componentCharactersAllowed(fragment, true))) {
            return RejectionCode.URL_CHARACTERS;
        }
        // Rule 21: '%' not followed by two hexadecimal digits.
        if (!percentSyntaxValid(path) || (query != null && !percentSyntaxValid(query))
                || (fragment != null && !percentSyntaxValid(fragment))) {
            return RejectionCode.PERCENT_ENCODING;
        }
        // Rule 22: escapes %00-%1F, %7F or %5C, any case.
        if (hasDangerousEscape(path) || (query != null && hasDangerousEscape(query))
                || (fragment != null && hasDangerousEscape(fragment))) {
            return RejectionCode.URL_CHARACTERS;
        }
        // Rule 23: dot segments in the path (also as %2e, and before a ';').
        if (hasDotSegment(path)) {
            return RejectionCode.DOT_SEGMENT;
        }
        // Rule 24: java.net.URI as second parser must accept it and agree with the minimal partition.
        if (!agreesWithUri(input, rawScheme, authority, path, query, fragment)) {
            return RejectionCode.URL_FORMAT;
        }
        // Rule 25: canonical form longer than 2048 characters.
        String canonicalPath = path.isEmpty() ? "/" : path;
        int canonicalLength = scheme.length() + 3 + host.length() + canonicalPath.length()
                + (query == null ? 0 : query.length() + 1);
        if (canonicalLength > MAX_URL_LENGTH) {
            return RejectionCode.URL_TOO_LONG;
        }
        return new DestinationUrl(scheme, new ValidatedHost(host), canonicalPath, query);
    }

    /** {@code ALPHA *( ALPHA / DIGIT / "+" / "-" / "." )}, ASCII only. */
    private static boolean isSchemeSyntax(String scheme) {
        if (scheme.isEmpty() || !isAsciiAlpha(scheme.charAt(0))) {
            return false;
        }
        for (int i = 1; i < scheme.length(); i++) {
            char c = scheme.charAt(i);
            if (!(isAsciiAlpha(c) || isDigit(c) || c == '+' || c == '-' || c == '.')) {
                return false;
            }
        }
        return true;
    }

    /** A-Z to a-z only; the caller has already checked that the text is ASCII. */
    private static String asciiLower(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
        }
        return out.toString();
    }

    /**
     * The last label, ignoring one empty final label, is all digits or "0x"/"0X" followed by zero or
     * more hexadecimal digits: canonical, shortened, integer, octal, hexadecimal and mixed IPv4 forms.
     */
    private static boolean endsInNumber(String host) {
        String trimmed = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        String last = trimmed.substring(trimmed.lastIndexOf('.') + 1);
        if (last.isEmpty()) {
            return false;
        }
        boolean allDigits = true;
        for (int i = 0; i < last.length(); i++) {
            allDigits &= isDigit(last.charAt(i));
        }
        if (allDigits) {
            return true;
        }
        if (last.length() >= 2 && last.charAt(0) == '0' && (last.charAt(1) == 'x' || last.charAt(1) == 'X')) {
            for (int i = 2; i < last.length(); i++) {
                if (!isHex(last.charAt(i))) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static List<String> splitLabels(String host) {
        List<String> labels = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= host.length(); i++) {
            if (i == host.length() || host.charAt(i) == '.') {
                labels.add(host.substring(start, i));
                start = i + 1;
            }
        }
        return labels;
    }

    /** RFC 3986 repertoire: unreserved, sub-delims, ':', '@', '/', '%' (checked by rule 21), and '?' in query/fragment. */
    private static boolean componentCharactersAllowed(String component, boolean allowQuestionMark) {
        for (int i = 0; i < component.length(); i++) {
            char c = component.charAt(i);
            boolean allowed = isAsciiAlpha(c) || isDigit(c) || c == '-' || c == '.' || c == '_' || c == '~'
                    || c == '!' || c == '$' || c == '&' || c == '\'' || c == '(' || c == ')' || c == '*' || c == '+'
                    || c == ',' || c == ';' || c == '=' || c == ':' || c == '@' || c == '/' || c == '%'
                    || (allowQuestionMark && c == '?');
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    private static boolean percentSyntaxValid(String component) {
        for (int i = 0; i < component.length(); i++) {
            if (component.charAt(i) == '%') {
                if (i + 2 >= component.length() || !isHex(component.charAt(i + 1)) || !isHex(component.charAt(i + 2))) {
                    return false;
                }
                i += 2;
            }
        }
        return true;
    }

    /** Requires valid percent syntax (rule 21 runs first). */
    private static boolean hasDangerousEscape(String component) {
        for (int i = 0; i < component.length(); i++) {
            if (component.charAt(i) == '%') {
                int value = Character.digit(component.charAt(i + 1), 16) * 16 + Character.digit(component.charAt(i + 2), 16);
                if (value <= 0x1F || value == 0x7F || value == 0x5C) {
                    return true;
                }
                i += 2;
            }
        }
        return false;
    }

    /** A segment is "." or "..", or so is its part before the first ';', reading each %2e/%2E as '.'. */
    private static boolean hasDotSegment(String path) {
        int start = 0;
        for (int i = 0; i <= path.length(); i++) {
            if (i == path.length() || path.charAt(i) == '/') {
                String segment = path.substring(start, i).replace("%2e", ".").replace("%2E", ".");
                int semicolon = segment.indexOf(';');
                String beforeParameters = semicolon >= 0 ? segment.substring(0, semicolon) : segment;
                if (isDotName(segment) || isDotName(beforeParameters)) {
                    return true;
                }
                start = i + 1;
            }
        }
        return false;
    }

    private static boolean isDotName(String segment) {
        return segment.equals(".") || segment.equals("..");
    }

    private static boolean agreesWithUri(String input, String scheme, String authority, String path, String query,
                                         String fragment) {
        URI uri;
        try {
            uri = new URI(input);
        } catch (URISyntaxException rejected) {
            return false;
        }
        return uri.isAbsolute() && !uri.isOpaque()
                && Objects.equals(uri.getScheme(), scheme)
                && Objects.equals(uri.getRawAuthority(), authority)
                && Objects.equals(uri.getRawPath(), path)
                && Objects.equals(uri.getRawQuery(), query)
                && Objects.equals(uri.getRawFragment(), fragment);
    }

    private static boolean isAsciiAlpha(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isHex(char c) {
        return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
