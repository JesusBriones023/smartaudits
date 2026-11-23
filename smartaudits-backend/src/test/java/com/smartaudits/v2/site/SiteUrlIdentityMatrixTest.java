package com.smartaudits.v2.site;

import com.smartaudits.v2.acquisition.destination.RejectionCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import static com.smartaudits.v2.acquisition.destination.RejectionCode.*;
import static com.smartaudits.v2.site.SiteUrlRejection.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * The equivalence matrix of docs/F4_1C_SITE_URL_IDENTITY.md, rows A to T, written from the contract:
 * every accepted spelling with its canonical form, every equivalence class, the identities that must
 * stay distinct, and every rejected category with its exact reason. Every rejection asserts its
 * reason and, for the destination profile, the exact code of ADR-0002; no exception counts as a pass.
 */
class SiteUrlIdentityMatrixTest {
    static final String BS = String.valueOf((char) 92);

    static String ch(int codePoint) {
        return new String(Character.toChars(codePoint));
    }

    static String repeat(char c, int n) {
        return String.valueOf(c).repeat(n);
    }

    /** A valid host of exactly {@code length} characters. */
    static String host(int length) {
        String fixed = repeat('a', 63) + "." + repeat('b', 63) + "." + repeat('c', 63) + ".";
        return fixed + repeat('d', length - fixed.length() - ".com".length()) + ".com";
    }

    static SiteUrlIdentity accepted(String input) {
        SiteUrlIdentityResult result = SiteUrlIdentity.parse(input);
        assertTrue(result instanceof SiteUrlIdentityResult.Accepted, "expected acceptance, got " + result);
        return ((SiteUrlIdentityResult.Accepted) result).identity();
    }

    static SiteUrlIdentityResult.Rejected rejected(String input) {
        SiteUrlIdentityResult result = SiteUrlIdentity.parse(input);
        assertTrue(result instanceof SiteUrlIdentityResult.Rejected, "expected a rejection, got " + result);
        return (SiteUrlIdentityResult.Rejected) result;
    }

    /** Asserts a rejection by the destination profile with exactly this ADR-0002 code. */
    static void assertDestinationRejection(RejectionCode expected, String input, String description) {
        SiteUrlIdentityResult.Rejected rejection = rejected(input);
        assertEquals(DESTINATION_SYNTAX, rejection.reason(), description);
        assertEquals(expected, rejection.destinationRejection().orElseThrow().code(), description);
    }

    /** Asserts a Site-level rejection: the destination profile accepted the text. */
    static void assertSiteRejection(SiteUrlRejection expected, String input, String description) {
        SiteUrlIdentityResult.Rejected rejection = rejected(input);
        assertEquals(expected, rejection.reason(), description);
        assertTrue(rejection.destinationRejection().isEmpty(), description);
    }

    // ---- Accepted spellings (rows A, B, C, E, F, H, T) --------------------------------------

    static Stream<Arguments> acceptedSpellings() {
        return Stream.of(
                arguments("A https", "https://example.com", "https://example.com"),
                arguments("A http", "http://example.com", "http://example.com"),
                arguments("A scheme in capitals", "HTTPS://example.com", "https://example.com"),
                arguments("A scheme in mixed case", "hTtP://example.com", "http://example.com"),
                arguments("B host in capitals", "https://EXAMPLE.COM", "https://example.com"),
                arguments("B host in mixed case", "https://Example.Com", "https://example.com"),
                arguments("C explicit https default port", "https://example.com:443", "https://example.com"),
                arguments("C explicit http default port", "http://example.com:80", "http://example.com"),
                arguments("C default port and root slash", "https://example.com:443/", "https://example.com"),
                arguments("E www is kept", "https://www.example.com", "https://www.example.com"),
                arguments("E WWW in capitals is kept", "https://WWW.example.com/", "https://www.example.com"),
                arguments("F subdomain", "https://shop.example.com", "https://shop.example.com"),
                arguments("F nested subdomain", "https://a.b.example.com", "https://a.b.example.com"),
                arguments("F numeric label that is not the last", "https://123.example.com", "https://123.example.com"),
                arguments("F hyphenated label", "https://sub-1.example.com", "https://sub-1.example.com"),
                arguments("H root slash", "https://example.com/", "https://example.com"),
                arguments("H root slash on http", "http://example.com/", "http://example.com"),
                arguments("two short labels", "https://e.co", "https://e.co"),
                arguments("name that only looks local", "https://localhost.com", "https://localhost.com"),
                arguments("last label that only looks numeric", "https://example.0xg", "https://example.0xg"),
                arguments("T longest host", "https://" + host(253), "https://" + host(253)),
                arguments("T longest accepted spelling", "HTTPS://" + host(253) + ":443/", "https://" + host(253)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptedSpellings")
    void everyAcceptedSpellingHasItsCanonicalForm(String row, String input, String canonical) {
        SiteUrlIdentity identity = accepted(input);
        assertEquals(canonical, identity.canonicalForm(), row);
        assertEquals(canonical.substring(0, canonical.indexOf(':')), identity.scheme(), row);
        assertEquals(canonical.substring(canonical.indexOf("://") + 3), identity.host(), row);
    }

    // ---- Equivalent spellings (rows A, B, C, H) ---------------------------------------------

    /** Each class lists every spelling of one identity; the first is its canonical form. */
    static List<List<String>> equivalenceClasses() {
        return List.of(
                List.of("https://example.com", "https://example.com/", "https://example.com:443", "https://example.com:443/",
                        "HTTPS://example.com", "https://EXAMPLE.COM", "https://Example.Com/", "hTtPs://eXaMpLe.CoM:443/"),
                List.of("http://example.com", "http://example.com/", "http://example.com:80", "http://example.com:80/",
                        "HTTP://EXAMPLE.COM", "Http://Example.COM:80/"),
                List.of("https://www.example.com", "https://www.example.com/", "https://WWW.EXAMPLE.COM:443",
                        "HTTPS://Www.Example.Com:443/"),
                List.of("http://www.example.com", "http://www.example.com:80/", "HTTP://WWW.EXAMPLE.COM"),
                List.of("https://shop.example.com", "https://SHOP.example.com/", "https://shop.example.com:443"),
                List.of("https://example.org", "https://example.org/", "HTTPS://EXAMPLE.ORG:443/"));
    }

    @Test void equivalentSpellingsProduceIdenticalIdentities() {
        for (List<String> spellings : equivalenceClasses()) {
            SiteUrlIdentity first = accepted(spellings.get(0));
            assertEquals(spellings.get(0), first.canonicalForm());
            Set<SiteUrlIdentity> distinct = new HashSet<>();
            for (String spelling : spellings) {
                SiteUrlIdentity identity = accepted(spelling);
                assertEquals(first, identity, spelling);
                assertEquals(identity, first, spelling);
                assertEquals(first.hashCode(), identity.hashCode(), spelling);
                assertEquals(first.canonicalForm(), identity.canonicalForm(), spelling);
                distinct.add(identity);
            }
            assertEquals(1, distinct.size(), spellings.get(0));
        }
    }

    @Test void theEquivalenceClassesAreDifferentIdentities() {
        Set<SiteUrlIdentity> identities = new HashSet<>();
        Set<String> canonicalForms = new HashSet<>();
        for (List<String> spellings : equivalenceClasses()) {
            identities.add(accepted(spellings.get(0)));
            canonicalForms.add(accepted(spellings.get(0)).canonicalForm());
        }
        assertEquals(equivalenceClasses().size(), identities.size());
        assertEquals(equivalenceClasses().size(), canonicalForms.size());
    }

    // ---- Identities that must never be merged (rows A, E, F and adversarial look-alikes) ----

    /** Canonical forms that are different Sites by contract. No heuristic may ever merge two of them. */
    static List<String> knownDistinct() {
        return List.of(
                "http://example.com", "https://example.com",
                "http://www.example.com", "https://www.example.com",
                "https://shop.example.com", "https://www.shop.example.com", "https://shop.www.example.com",
                "https://blog.example.com", "https://m.example.com", "https://www2.example.com",
                "https://wwwexample.com", "https://www-example.com", "https://ww.example.com",
                "https://example.org", "https://example.net", "https://example.co", "https://example.com.co",
                "https://example.com.example.org", "https://com.example.org", "https://examplecom.org",
                "https://ex-ample.com", "https://exa.mple.com", "https://examp1e.com", "https://exampie.com",
                "https://rnexample.com", "https://mexample.com", "https://examplle.com", "https://exarnple.com",
                "https://0example.com", "https://oexample.com", "https://s.example.com", "https://ttps.example.com",
                "http://s.example.com", "http://shop.example.com");
    }

    @Test void knownDistinctIdentitiesAreNeverCollapsed() {
        List<String> forms = knownDistinct();
        List<SiteUrlIdentity> identities = new ArrayList<>();
        for (String form : forms) {
            SiteUrlIdentity identity = accepted(form);
            assertEquals(form, identity.canonicalForm(), "a canonical form is its own canonical form");
            identities.add(identity);
        }
        for (int i = 0; i < identities.size(); i++) {
            for (int j = 0; j < identities.size(); j++) {
                if (i != j) {
                    assertNotEquals(identities.get(i), identities.get(j), forms.get(i) + " vs " + forms.get(j));
                    assertNotEquals(identities.get(i).canonicalForm(), identities.get(j).canonicalForm());
                }
            }
        }
        assertEquals(forms.size(), new HashSet<>(identities).size());
    }

    @Test void schemeWwwAndSubdomainsEachKeepTheirOwnIdentity() {
        assertNotEquals(accepted("http://example.com"), accepted("https://example.com"));
        assertNotEquals(accepted("http://example.com:80/"), accepted("https://example.com:443/"));
        assertNotEquals(accepted("https://example.com"), accepted("https://www.example.com"));
        assertNotEquals(accepted("https://EXAMPLE.com"), accepted("https://WWW.example.com"));
        assertNotEquals(accepted("https://shop.example.com"), accepted("https://example.com"));
        assertNotEquals(accepted("https://shop.example.com"), accepted("https://blog.example.com"));
        assertEquals("www.example.com", accepted("https://www.example.com").host());
        assertEquals("example.com", accepted("https://example.com").host());
        assertEquals("http", accepted("HTTP://example.com").scheme());
        assertEquals("https", accepted("HTTPS://example.com").scheme());
    }

    // ---- Rejected by the destination profile (rows D, G, M, N, O, P, Q, R, S, T) -------------

    static Stream<Arguments> destinationRejections() {
        return Stream.of(
                arguments("D non default port", "https://example.com:8443", PORT),
                arguments("D non default port on http", "http://example.com:8080", PORT),
                arguments("D http port on https", "https://example.com:80", PORT),
                arguments("D https port on http", "http://example.com:443", PORT),
                arguments("D empty port", "https://example.com:", PORT),
                arguments("D default port with leading zero", "https://example.com:0443", PORT),
                arguments("D port over 65535", "https://example.com:65536", PORT),
                arguments("D two ports", "https://example.com:443:443", HOST_FORMAT),
                arguments("G trailing host dot", "https://example.com.", HOST_FORMAT),
                arguments("G trailing host dot and slash", "https://example.com./", HOST_FORMAT),
                arguments("G trailing host dot in capitals", "https://EXAMPLE.COM.", HOST_FORMAT),
                arguments("M escape in host", "https://ex%61mple.com", PERCENT_ENCODING),
                arguments("M escaped slash in host", "https://example.com%2F", PERCENT_ENCODING),
                arguments("M malformed escape in path", "https://example.com/%zz", PERCENT_ENCODING),
                arguments("M truncated escape in path", "https://example.com/%4", PERCENT_ENCODING),
                arguments("M malformed escape in query", "https://example.com/?q=%zz", PERCENT_ENCODING),
                arguments("M malformed escape in fragment", "https://example.com/#%zz", PERCENT_ENCODING),
                arguments("M escaped NUL", "https://example.com/%00", URL_CHARACTERS),
                arguments("M escaped backslash", "https://example.com/%5C", URL_CHARACTERS),
                arguments("M escaped dot segment", "https://example.com/%2e", DOT_SEGMENT),
                arguments("M escaped dot dot segment", "https://example.com/%2E%2E", DOT_SEGMENT),
                arguments("N U-label", "https://b" + ch(0xFC) + "cher.example.com", IDN_UNSUPPORTED),
                arguments("N U-label with n tilde", "https://espa" + ch(0xF1) + "a.example.com", IDN_UNSUPPORTED),
                arguments("N A-label", "https://xn--bcher-kva.example.com", IDN_UNSUPPORTED),
                arguments("N A-label in capitals", "https://XN--BCHER-KVA.example.com", IDN_UNSUPPORTED),
                arguments("N A-label as last label", "https://example.xn--p1ai", IDN_UNSUPPORTED),
                arguments("N reserved LDH label", "https://ab--cd.example.com", IDN_UNSUPPORTED),
                arguments("O IPv4 loopback", "https://127.0.0.1", IP_LITERAL),
                arguments("O public IPv4", "https://8.8.8.8", IP_LITERAL),
                arguments("O bracketed IPv6 loopback", "https://[::1]", IP_LITERAL),
                arguments("O bracketed public IPv6", "https://[2001:db8::1]", IP_LITERAL),
                arguments("O integer IPv4", "https://2130706433", IP_LITERAL),
                arguments("O hexadecimal IPv4", "https://0x7f000001", IP_LITERAL),
                arguments("O shortened IPv4", "https://127.1", IP_LITERAL),
                arguments("O numeric last label", "https://example.123", IP_LITERAL),
                arguments("P empty", "", URL_FORMAT),
                arguments("P no scheme", "example.com", URL_FORMAT),
                arguments("P no scheme with www", "www.example.com", URL_FORMAT),
                arguments("P scheme relative", "//example.com", URL_FORMAT),
                arguments("P opaque", "https:example.com", URL_FORMAT),
                arguments("P single slash", "https:/example.com", URL_FORMAT),
                arguments("P no host", "https://", HOST_FORMAT),
                arguments("P empty authority", "https:///", HOST_FORMAT),
                arguments("P host and port without scheme", "example.com:443", SCHEME),
                arguments("P ftp", "ftp://example.com", SCHEME),
                arguments("P file", "file:///etc/passwd", SCHEME),
                arguments("P javascript", "javascript:alert(1)", SCHEME),
                arguments("P websocket", "wss://example.com", SCHEME),
                arguments("P mailto", "mailto:info@example.com", SCHEME),
                arguments("P underscore in host", "https://exa_mple.com", HOST_FORMAT),
                arguments("P wildcard", "https://*.example.com", HOST_FORMAT),
                arguments("P empty label", "https://example..com", HOST_FORMAT),
                arguments("P leading hyphen", "https://-example.com", HOST_FORMAT),
                arguments("P single label", "https://example", HOST_FORBIDDEN),
                arguments("P localhost", "https://localhost", HOST_FORBIDDEN),
                arguments("P local network name", "https://printer.local", HOST_FORBIDDEN),
                arguments("P reserved test name", "https://site.test", HOST_FORBIDDEN),
                arguments("P internal name", "https://db.internal", HOST_FORBIDDEN),
                arguments("Q user", "https://user@example.com", USERINFO),
                arguments("Q user and password", "https://user:pass@example.com", USERINFO),
                arguments("Q empty userinfo", "https://@example.com", USERINFO),
                arguments("Q host confusion", "https://example.com@evil.example.org", USERINFO),
                arguments("R fullwidth letter in host", "https://" + ch(0xFF45) + "xample.com", IDN_UNSUPPORTED),
                arguments("R cyrillic letter in host", "https://ex" + ch(0x430) + "mple.com", IDN_UNSUPPORTED),
                arguments("R zero width space in host", "https://exa" + ch(0x200B) + "mple.com", IDN_UNSUPPORTED),
                arguments("R soft hyphen in host", "https://exa" + ch(0xAD) + "mple.com", IDN_UNSUPPORTED),
                arguments("R no-break space in host", "https://exa" + ch(0xA0) + "mple.com", IDN_UNSUPPORTED),
                arguments("R right-to-left override in host", "https://exa" + ch(0x202E) + "mple.com", IDN_UNSUPPORTED),
                arguments("R ideographic full stop", "https://example" + ch(0x3002) + "com", IDN_UNSUPPORTED),
                arguments("R fullwidth full stop", "https://example" + ch(0xFF0E) + "com", IDN_UNSUPPORTED),
                arguments("R halfwidth ideographic full stop", "https://example" + ch(0xFF61) + "com", IDN_UNSUPPORTED),
                arguments("R kelvin sign for k", "https://" + ch(0x212A) + "ey.example.com", IDN_UNSUPPORTED),
                arguments("R dotless i", "https://t" + ch(0x131) + "tle.example.com", IDN_UNSUPPORTED),
                arguments("R precomposed accent", "https://caf" + ch(0xE9) + ".example.com", IDN_UNSUPPORTED),
                arguments("R combining accent", "https://cafe" + ch(0x301) + ".example.com", IDN_UNSUPPORTED),
                arguments("R astral letter in host", "https://" + ch(0x1D41A) + ".example.com", IDN_UNSUPPORTED),
                arguments("R fullwidth slash after host", "https://example.com" + ch(0xFF0F), IDN_UNSUPPORTED),
                arguments("R ideographic space after host", "https://example.com" + ch(0x3000), IDN_UNSUPPORTED),
                arguments("R long s in scheme", "http" + ch(0x17F) + "://example.com", URL_FORMAT),
                arguments("R fullwidth colon", "https" + ch(0xFF1A) + "//example.com", URL_FORMAT),
                arguments("R byte order mark first", ch(0xFEFF) + "https://example.com", URL_FORMAT),
                arguments("R leading space", " https://example.com", URL_CHARACTERS),
                arguments("R trailing space", "https://example.com ", URL_CHARACTERS),
                arguments("R trailing tab", "https://example.com" + ch(9), URL_CHARACTERS),
                arguments("R trailing line feed", "https://example.com" + ch(10), URL_CHARACTERS),
                arguments("R trailing carriage return", "https://example.com" + ch(13), URL_CHARACTERS),
                arguments("R NUL", "https://example.com" + ch(0), URL_CHARACTERS),
                arguments("R accent in path", "https://example.com/caf" + ch(0xE9), URL_CHARACTERS),
                arguments("R ideographic space in path", "https://example.com/" + ch(0x3000), URL_CHARACTERS),
                arguments("S dot segment", "https://example.com/.", DOT_SEGMENT),
                arguments("S dot dot segment", "https://example.com/..", DOT_SEGMENT),
                arguments("S dot segment and slash", "https://example.com/./", DOT_SEGMENT),
                arguments("S dot dot inside", "https://example.com/a/../", DOT_SEGMENT),
                arguments("S three slashes before host", "https:///example.com", HOST_FORMAT),
                arguments("S backslashes for slashes", "https:" + BS + BS + "example.com", URL_CHARACTERS),
                arguments("S final backslash", "https://example.com" + BS, URL_CHARACTERS),
                arguments("T 2049 characters", "https://example.com/" + repeat('a', 2029), URL_TOO_LONG),
                arguments("T host of 254 characters", "https://" + host(254), HOST_FORMAT),
                arguments("T label of 64 characters", "https://" + repeat('a', 64) + ".example.com", HOST_FORMAT));
    }

    @ParameterizedTest(name = "{0} -> {2}")
    @MethodSource("destinationRejections")
    void everyCategoryTheDestinationProfileRejects(String row, String input, RejectionCode expected) {
        assertDestinationRejection(expected, input, row);
    }

    // ---- Rejected at the Site level: not a bare origin (rows I, J, K, L, M, S, T) ------------

    static Stream<Arguments> siteRejections() {
        return Stream.of(
                arguments("I path", "https://example.com/tienda", PATH),
                arguments("I file", "https://example.com/index.html", PATH),
                arguments("I deep path", "https://example.com/a/b/c", PATH),
                arguments("I path in capitals", "https://example.com/Tienda", PATH),
                arguments("I user directory", "https://example.com/~user", PATH),
                arguments("I path parameter only", "https://example.com/;", PATH),
                arguments("I path after the default port", "https://example.com:443/tienda", PATH),
                arguments("J path with final slash", "https://example.com/tienda/", PATH),
                arguments("J short path with final slash", "https://example.com/a/", PATH),
                arguments("K query without path", "https://example.com?a=1", QUERY),
                arguments("K query", "https://example.com/?a=1", QUERY),
                arguments("K empty query", "https://example.com/?", QUERY),
                arguments("K empty query without path", "https://example.com?", QUERY),
                arguments("K two parameters", "https://example.com/?b=2&a=1", QUERY),
                arguments("K the same parameters reordered", "https://example.com/?a=1&b=2", QUERY),
                arguments("K tracking parameters", "https://example.com/?utm_source=x&gclid=y", QUERY),
                arguments("K query after the default port", "https://example.com:443?x=1", QUERY),
                arguments("L empty fragment without path", "https://example.com#", FRAGMENT),
                arguments("L empty fragment", "https://example.com/#", FRAGMENT),
                arguments("L fragment", "https://example.com/#top", FRAGMENT),
                arguments("L fragment that looks like a route", "https://example.com#/tienda", FRAGMENT),
                arguments("L fragment after the default port", "https://example.com:443/#x", FRAGMENT),
                arguments("L fragment with a question mark", "https://example.com/#a?b", FRAGMENT),
                arguments("M escaped slash as path", "https://example.com/%2F", PATH),
                arguments("M escaped letter as path", "https://example.com/%41", PATH),
                arguments("M escaped tilde as path", "https://example.com/%7E", PATH),
                arguments("M escaped space as path", "https://example.com/%20", PATH),
                arguments("S repeated slash", "https://example.com//", PATH),
                arguments("S three slashes", "https://example.com///", PATH),
                arguments("S repeated slash after the default port", "https://example.com:443//", PATH),
                arguments("S three dots are not a dot segment", "https://example.com/...", PATH),
                arguments("T 2048 characters of path", "https://example.com/" + repeat('a', 2028), PATH),
                arguments("first reason: path before query and fragment", "https://example.com/a?b#c", PATH),
                arguments("first reason: path before fragment", "https://example.com/a#c", PATH),
                arguments("first reason: query before fragment", "https://example.com/?b#c", QUERY),
                arguments("first reason: empty query before fragment", "https://example.com?#", QUERY));
    }

    @ParameterizedTest(name = "{0} -> {2}")
    @MethodSource("siteRejections")
    void everyTextThatIsNotABareOriginIsRejectedAndNeverStripped(String row, String input, SiteUrlRejection expected) {
        assertSiteRejection(expected, input, row);
    }

    @Test void theDestinationProfileDecidesBeforeTheSiteLevel() {
        assertDestinationRejection(PORT, "https://example.com:8443/a?b#c", "port before path");
        assertDestinationRejection(SCHEME, "ftp://example.com/a?b#c", "scheme before path");
        assertDestinationRejection(USERINFO, "https://user@example.com/a", "userinfo before path");
        assertDestinationRejection(IP_LITERAL, "https://127.0.0.1/a?b", "literal before path");
        assertDestinationRejection(URL_CHARACTERS, "https://example.com/a b", "space before path");
        assertDestinationRejection(DOT_SEGMENT, "https://example.com/a/../?b#c", "dot segment before path");
    }

    @Test void variantsThatWouldCollideIfAnythingWereStrippedAreAllRefused() {
        // Each of these would become https://example.com under some stripping rule. None is accepted.
        for (String input : List.of("https://example.com/index.html", "https://example.com/?", "https://example.com/#",
                "https://example.com//", "https://example.com/%2F", "https://example.com.", "https://example.com:443:443",
                "https://example.com:0443", "https://example.com/.", " https://example.com", "https://example.com ",
                "https://user@example.com", "example.com", "//example.com", "https://example.com/?utm_source=x")) {
            assertTrue(SiteUrlIdentity.parse(input) instanceof SiteUrlIdentityResult.Rejected, input);
        }
    }

    @Test void literalLegacyBaseUrlsAreNeverRepairedIntoAnIdentity() {
        // Texts of the kind sites.base_url may hold today (ADR-0001 section 7, SiteTest): kept as given.
        for (String literal : List.of("", " ", ch(0xA0), "  HTTPS://Ejemplo.ES:443/Ruta/../%7Euser?q=1&Q=2#Frag  ",
                "HTTPS://Ejemplo.ES:443/Ruta/../%7Euser?q=1&Q=2#Frag", "https://ejemplo.invalid/", "https://example.invalid/",
                "https://same.invalid/", "https://ejemplo.invalid/Caf" + ch(0xE9) + "?x=" + ch(0xD1), "ejemplo.es",
                "www.ejemplo.es/", " https://ejemplo.es", "https://ejemplo.es/Ruta")) {
            assertTrue(SiteUrlIdentity.parse(literal) instanceof SiteUrlIdentityResult.Rejected, literal);
        }
        // A literal that already is a bare origin is identified exactly as written: nothing is guessed.
        assertEquals("https://ejemplo.es", accepted("HTTPS://Ejemplo.ES:443/").canonicalForm());
    }

    // ---- The accepted language, generated from its definition --------------------------------

    private static final List<String> SCHEMES = List.of("http", "https", "HTTP", "HTTPS", "Http", "hTTpS");
    private static final List<String> HOSTS = List.of("example.com", "EXAMPLE.COM", "Www.Example.Com", "a.b-c.example.net",
            "TITLE.example.org", "1.example.com");

    /** What follows scheme://host, with {@code P} standing for the default port, and the expected outcome. */
    private static final List<Object[]> TAILS = List.of(
            new Object[]{"", null}, new Object[]{"/", null}, new Object[]{":P", null}, new Object[]{":P/", null},
            new Object[]{"//", PATH}, new Object[]{"/a", PATH}, new Object[]{"/A/", PATH}, new Object[]{"/index.html", PATH},
            new Object[]{":P//", PATH}, new Object[]{":P/a", PATH}, new Object[]{"/;", PATH}, new Object[]{"/%20", PATH},
            new Object[]{"/a?b#c", PATH}, new Object[]{"/a#c", PATH},
            new Object[]{"?", QUERY}, new Object[]{"/?", QUERY}, new Object[]{"?a=1", QUERY}, new Object[]{":P?x", QUERY},
            new Object[]{":P/?x", QUERY}, new Object[]{"/?a#b", QUERY}, new Object[]{"?#", QUERY},
            new Object[]{"#", FRAGMENT}, new Object[]{"/#", FRAGMENT}, new Object[]{"#a", FRAGMENT}, new Object[]{":P#", FRAGMENT},
            new Object[]{":P/#/x", FRAGMENT}, new Object[]{"/#?", FRAGMENT},
            new Object[]{":Q", PORT}, new Object[]{":Q/", PORT}, new Object[]{":8443", PORT}, new Object[]{":", PORT},
            new Object[]{":/", PORT}, new Object[]{":0P", PORT}, new Object[]{":P0", PORT}, new Object[]{":P:P", HOST_FORMAT},
            new Object[]{".", HOST_FORMAT}, new Object[]{"./", HOST_FORMAT}, new Object[]{" ", URL_CHARACTERS},
            new Object[]{"/ ", URL_CHARACTERS}, new Object[]{"/.", DOT_SEGMENT}, new Object[]{"/%zz", PERCENT_ENCODING},
            new Object[]{"@example.org", USERINFO});

    @Test void theAcceptedLanguageIsExactlyTheFourOriginSpellings() {
        int acceptedCount = 0;
        int rejectedCount = 0;
        for (String scheme : SCHEMES) {
            String lowerScheme = scheme.toLowerCase(Locale.ROOT);
            String port = lowerScheme.equals("https") ? "443" : "80";
            String otherPort = lowerScheme.equals("https") ? "80" : "443";
            for (String host : HOSTS) {
                String canonical = lowerScheme + "://" + host.toLowerCase(Locale.ROOT);
                for (Object[] tail : TAILS) {
                    String input = scheme + "://" + host + ((String) tail[0]).replace("P", port).replace("Q", otherPort);
                    if (tail[1] == null) {
                        assertEquals(canonical, accepted(input).canonicalForm(), input);
                        acceptedCount++;
                    } else if (tail[1] instanceof SiteUrlRejection reason) {
                        assertSiteRejection(reason, input, input);
                        rejectedCount++;
                    } else {
                        assertDestinationRejection((RejectionCode) tail[1], input, input);
                        rejectedCount++;
                    }
                }
            }
        }
        assertEquals(SCHEMES.size() * HOSTS.size() * 4, acceptedCount);
        assertEquals(SCHEMES.size() * HOSTS.size() * (TAILS.size() - 4), rejectedCount);
    }

    @Test void anAcceptedTextDiffersFromItsCanonicalFormOnlyByCaseDefaultPortAndRootSlash() {
        List<String> inputs = new ArrayList<>();
        acceptedSpellings().forEach(a -> inputs.add((String) a.get()[1]));
        equivalenceClasses().forEach(inputs::addAll);
        for (String input : inputs) {
            SiteUrlIdentity identity = accepted(input);
            String canonical = identity.canonicalForm();
            String port = identity.scheme().equals("https") ? "443" : "80";
            String lower = input.toLowerCase(Locale.ROOT);
            assertTrue(Set.of(canonical, canonical + "/", canonical + ":" + port, canonical + ":" + port + "/").contains(lower),
                    input);
        }
    }
}
