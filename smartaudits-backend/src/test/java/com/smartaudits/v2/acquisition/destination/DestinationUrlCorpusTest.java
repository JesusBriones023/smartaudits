package com.smartaudits.v2.acquisition.destination;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static com.smartaudits.v2.acquisition.destination.RejectionCode.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Normative corpora of ADR-0002 sections 5 and 6, written literally (rejection corpus, multiple
 * violations, canonicalization corpus), plus exact limits and adversarial cases. Every rejection
 * asserts its exact code; no exception counts as a pass. Expectations come from the ADR text,
 * never from the parser output.
 */
class DestinationUrlCorpusTest {
    static final String BS = String.valueOf((char) 92);

    static String ch(int codePoint) {
        return String.valueOf((char) codePoint);
    }

    static String repeat(char c, int n) {
        return String.valueOf(c).repeat(n);
    }

    static RejectionCode rejection(String input) {
        UrlParseResult result = DestinationUrl.parse(input);
        assertTrue(result instanceof UrlParseResult.Rejected, "expected a rejection, got " + result);
        UrlParseResult.Rejected rejected = (UrlParseResult.Rejected) result;
        assertEquals("IANA-SNAPSHOT-02", rejected.catalogRevision());
        return rejected.code();
    }

    static DestinationUrl accepted(String input) {
        UrlParseResult result = DestinationUrl.parse(input);
        assertTrue(result instanceof UrlParseResult.Parsed, "expected acceptance, got " + result);
        return ((UrlParseResult.Parsed) result).url();
    }

    /** ADR-0002 section 5, "Corpus de rechazo (normativo)", every row. */
    static Stream<Arguments> rejectionCorpus() {
        return Stream.of(
                arguments("URL of 2049 characters", "https://example.com/" + repeat('a', 2029), URL_TOO_LONG),
                arguments("space in path", "https://example.com/a b", URL_CHARACTERS),
                arguments("leading space", " https://example.com", URL_CHARACTERS),
                arguments("U+0009 in host", "https://exa" + ch(9) + "mple.com/", URL_CHARACTERS),
                arguments("U+000A in host", "https://exa" + ch(10) + "mple.com/", URL_CHARACTERS),
                arguments("backslash before @", "https://example.com" + BS + "@evil.example/", URL_CHARACTERS),
                arguments("empty string", "", URL_FORMAT),
                arguments("no scheme", "example.com", URL_FORMAT),
                arguments("scheme-relative", "//example.com/", URL_FORMAT),
                arguments("relative path", "/ruta", URL_FORMAT),
                arguments("scheme starting with digit", "1http://example.com/", URL_FORMAT),
                arguments("ftp", "ftp://example.com/", SCHEME),
                arguments("file", "file:///etc/passwd", SCHEME),
                arguments("javascript", "javascript:alert(1)", SCHEME),
                arguments("wss", "wss://example.com/", SCHEME),
                arguments("localhost read as scheme", "localhost:8080", SCHEME),
                arguments("opaque https", "https:example.com", URL_FORMAT),
                arguments("single slash", "https:/example.com", URL_FORMAT),
                arguments("user and password", "https://user:pass@example.com/", USERINFO),
                arguments("empty userinfo", "https://@example.com/", USERINFO),
                arguments("host confusion with @", "https://example.com@evil.example/", USERINFO),
                arguments("bracketed loopback", "https://[::1]/", IP_LITERAL),
                arguments("bracketed mapped", "https://[::ffff:127.0.0.1]/", IP_LITERAL),
                arguments("bracketed zone id", "https://[fe80::1%25eth0]/", IP_LITERAL),
                arguments("U-label", "https://b" + ch(0xFC) + "cher.example/", IDN_UNSUPPORTED),
                arguments("ideographic full stop", "https://example" + ch(0x3002) + "com/", IDN_UNSUPPORTED),
                arguments("zero width space in host", "https://exa" + ch(0x200B) + "mple.com/", IDN_UNSUPPORTED),
                arguments("soft hyphen in host", "https://exa" + ch(0xAD) + "mple.com/", IDN_UNSUPPORTED),
                arguments("percent in host", "https://ex%61mple.com/", PERCENT_ENCODING),
                arguments("nothing after //", "https://", HOST_FORMAT),
                arguments("empty authority", "https:///ruta", HOST_FORMAT),
                arguments("empty host with port", "https://:443/", HOST_FORMAT),
                arguments("unbracketed IPv6", "https://::1/", HOST_FORMAT),
                arguments("canonical IPv4", "https://127.0.0.1/", IP_LITERAL),
                arguments("shortened IPv4", "https://127.1/", IP_LITERAL),
                arguments("integer IPv4", "https://2130706433/", IP_LITERAL),
                arguments("octal IPv4", "https://0177.0.0.1/", IP_LITERAL),
                arguments("hexadecimal IPv4", "https://0x7f000001/", IP_LITERAL),
                arguments("IPv4 with final dot", "https://1.2.3.4./", IP_LITERAL),
                arguments("hex final label", "https://example.0x1f/", IP_LITERAL),
                arguments("underscore", "https://exa_mple.com/", HOST_FORMAT),
                arguments("wildcard", "https://*.example.com/", HOST_FORMAT),
                arguments("final dot", "https://example.com./", HOST_FORMAT),
                arguments("host of 254 characters", "https://" + host(254) + "/", HOST_FORMAT),
                arguments("empty label", "https://a..example.com/", HOST_FORMAT),
                arguments("leading hyphen", "https://-a.example.com/", HOST_FORMAT),
                arguments("label of 64 characters", "https://" + repeat('a', 64) + ".example.com/", HOST_FORMAT),
                arguments("A-label", "https://xn--bcher-kva.example/", IDN_UNSUPPORTED),
                arguments("R-LDH label", "https://ab--cd.example/", IDN_UNSUPPORTED),
                arguments("localhost", "https://localhost/", HOST_FORBIDDEN),
                arguments("intranet", "https://intranet/", HOST_FORBIDDEN),
                arguments("example, single label", "https://example/", HOST_FORBIDDEN),
                arguments("subdomain of localhost", "https://app.localhost/", HOST_FORBIDDEN),
                arguments("mDNS", "https://printer.local/", HOST_FORBIDDEN),
                arguments("cloud metadata name", "https://metadata.google.internal/", HOST_FORBIDDEN),
                arguments("home.arpa", "https://router.home.arpa/", HOST_FORBIDDEN),
                arguments("non default port", "https://example.com:8080/", PORT),
                arguments("https port on http", "http://example.com:443/", PORT),
                arguments("http port on https", "https://example.com:80/", PORT),
                arguments("empty port", "https://example.com:/", PORT),
                arguments("leading zero port", "https://example.com:0443/", PORT),
                arguments("signed port", "https://example.com:+443/", PORT),
                arguments("port over 65535", "https://example.com:99999/", PORT),
                arguments("non ASCII in path", "https://example.com/caf" + ch(0xE9), URL_CHARACTERS),
                arguments("brackets in query", "https://example.com/?a[0]=1", URL_CHARACTERS),
                arguments("second # in fragment", "https://example.com/#a#b", URL_CHARACTERS),
                arguments("bad escape zz", "https://example.com/%zz", PERCENT_ENCODING),
                arguments("truncated escape", "https://example.com/a%2", PERCENT_ENCODING),
                arguments("bad escape in fragment", "https://example.com/#%G0", PERCENT_ENCODING),
                arguments("CRLF escapes", "https://example.com/%0d%0aX:y", URL_CHARACTERS),
                arguments("NUL escape in query", "https://example.com/?q=%00", URL_CHARACTERS),
                arguments("escaped backslash", "https://example.com/%5c..%5cx", URL_CHARACTERS),
                arguments("dot segment", "https://example.com/./a", DOT_SEGMENT),
                arguments("dot dot segment", "https://example.com/a/../b", DOT_SEGMENT),
                arguments("escaped dot dot", "https://example.com/a/%2E%2e/b", DOT_SEGMENT),
                arguments("final dot dot", "https://example.com/a/..", DOT_SEGMENT),
                arguments("dot dot with path parameter", "https://example.com/..;/admin", DOT_SEGMENT),
                arguments("2048 input, empty path, query: canonical 2049", "https://example.com?" + repeat('q', 2028),
                        URL_TOO_LONG));
    }

    @ParameterizedTest(name = "{0} -> {2}")
    @MethodSource("rejectionCorpus")
    void normativeRejectionCorpus(String description, String input, RejectionCode expected) {
        assertEquals(expected, rejection(input), description);
    }

    /** ADR-0002 section 5, "Violaciones multiples": the earliest rule decides. */
    static Stream<Arguments> multipleViolations() {
        return Stream.of(
                arguments("ftp://user@127.0.0.1:21/../", SCHEME),
                arguments("https://user@[::1]:8080/../", USERINFO),
                arguments("https://EXAMPLE.com:8080/a b", URL_CHARACTERS),
                arguments("https://127.0.0.1:8080/", IP_LITERAL),
                arguments("https://localhost:8080/", HOST_FORBIDDEN),
                arguments("https://b" + ch(0xFC) + "cher.example:8080/", IDN_UNSUPPORTED),
                arguments("https://example.com:8080/../", PORT),
                arguments("https://example.com/../%5c", URL_CHARACTERS),
                arguments("javascript:" + repeat('a', 2038), URL_TOO_LONG));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("multipleViolations")
    void precedenceOnMultipleViolations(String input, RejectionCode expected) {
        assertEquals(expected, rejection(input));
    }

    /** ADR-0002 section 6, "Corpus de canonizacion (normativo)": input, canonical form. */
    static Stream<Arguments> canonicalizationCorpus() {
        return Stream.of(
                arguments("https://example.com", "https://example.com/"),
                arguments("HTTPS://EXAMPLE.COM/Path", "https://example.com/Path"),
                arguments("https://example.com:443/", "https://example.com/"),
                arguments("http://example.com:80/a", "http://example.com/a"),
                arguments("https://example.com:443?x=1", "https://example.com/?x=1"),
                arguments("https://example.com/A/b//c/", "https://example.com/A/b//c/"),
                arguments("https://example.com/p?b=2&a=1&a=1&c=&d", "https://example.com/p?b=2&a=1&a=1&c=&d"),
                arguments("https://example.com/p?", "https://example.com/p?"),
                arguments("https://example.com/p", "https://example.com/p"),
                arguments("https://example.com/a+b?q=a+b%20c", "https://example.com/a+b?q=a+b%20c"),
                arguments("https://example.com/%7euser/%7E/%2F?x=%41", "https://example.com/%7euser/%7E/%2F?x=%41"),
                arguments("https://example.com/caf%C3%A9", "https://example.com/caf%C3%A9"),
                arguments("https://example.com/%E2%80%AE", "https://example.com/%E2%80%AE"),
                arguments("https://example.com/p#sec", "https://example.com/p"),
                arguments("https://example.com/p?a=1#x?y", "https://example.com/p?a=1"),
                arguments("https://example.com#", "https://example.com/"),
                arguments("https://www.example.com/", "https://www.example.com/"),
                arguments("http://example.com/", "http://example.com/"),
                arguments("https://example.com/?utm_source=x&gclid=y", "https://example.com/?utm_source=x&gclid=y"),
                arguments("https://example.com/a;b=c/..d/e..", "https://example.com/a;b=c/..d/e.."),
                arguments("https://example.com/p?next=../x", "https://example.com/p?next=../x"),
                arguments("https://123.example.com/", "https://123.example.com/"),
                arguments("https://sub-1.a.example.com/x/", "https://sub-1.a.example.com/x/"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("canonicalizationCorpus")
    void normativeCanonicalizationCorpusIsIdempotent(String input, String canonical) {
        DestinationUrl url = accepted(input);
        assertEquals(canonical, url.canonicalForm());
        DestinationUrl again = accepted(url.canonicalForm());
        assertEquals(canonical, again.canonicalForm());
        assertEquals(url, again);
        assertEquals(url.hashCode(), again.hashCode());
    }

    @Test void httpAndHttpsAreDistinctDestinationsAndQueryAbsenceDiffersFromEmptyQuery() {
        assertNotEquals(accepted("http://example.com/"), accepted("https://example.com/"));
        assertNotEquals(accepted("https://example.com/p"), accepted("https://example.com/p?"));
        assertEquals("", accepted("https://example.com/p?").query().orElseThrow());
        assertTrue(accepted("https://example.com/p").query().isEmpty());
        assertTrue(accepted("https://example.com/p#").query().isEmpty());
    }

    @Test void canonicalComponentsAreExposedThroughExplicitAccessors() {
        DestinationUrl url = accepted("HTTPS://Sub.EXAMPLE.com:443/A%2fB?Q=%41&b#frag");
        assertEquals("https", url.scheme());
        assertEquals("sub.example.com", url.host().name());
        assertEquals(443, url.port());
        assertEquals("/A%2fB", url.path());
        assertEquals("Q=%41&b", url.query().orElseThrow());
        assertEquals("https://sub.example.com/A%2fB?Q=%41&b", url.canonicalForm());
        DestinationUrl http = accepted("http://example.com");
        assertEquals(80, http.port());
        assertEquals("/", http.path());
    }

    @Test void exactLengthLimits() {
        String base = "https://example.com/";
        assertEquals(2048, accepted(base + repeat('a', 2048 - base.length())).canonicalForm().length());
        assertEquals(URL_TOO_LONG, rejection(base + repeat('a', 2049 - base.length())));
        assertEquals(URL_TOO_LONG, rejection("https://example.com?" + repeat('q', 2028)));
        assertEquals(2048, accepted("https://example.com?" + repeat('q', 2027)).canonicalForm().length());
        String withPort = "https://example.com:443?" + repeat('q', 2048 - 24);
        assertEquals(2048, withPort.length());
        assertEquals(2045, accepted(withPort).canonicalForm().length());
        assertEquals(253, host(253).length());
        assertEquals(host(253), accepted("https://" + host(253) + "/").host().name());
        assertEquals(HOST_FORMAT, rejection("https://" + host(254) + "/"));
        accepted("https://" + repeat('a', 63) + ".example.com/");
        assertEquals(HOST_FORMAT, rejection("https://" + repeat('a', 64) + ".example.com/"));
    }

    @Test void portLimitsAreCheckedTextuallyAgainstTheDefaultPort() {
        accepted("https://example.com:443/");
        accepted("http://example.com:80/");
        for (String port : new String[]{"65535", "65536", "443443", "4430", "00443", "080", "-443", "4.4", "0x1bb",
                "1", "0", "8443", "1234567"}) {
            assertEquals(PORT, rejection("https://example.com:" + port + "/"), port);
        }
        assertEquals(PORT, rejection("http://example.com:8080/"));
        assertEquals(PORT, rejection("http://example.com:080/"));
        assertEquals(HOST_FORMAT, rejection("https://example.com:443:443/"));
    }

    /** Adversarial and near-boundary cases derived from the rules of section 5. */
    static Stream<Arguments> adversarial() {
        return Stream.of(
                arguments("https:", URL_FORMAT),
                arguments("https:/", URL_FORMAT),
                arguments("https", URL_FORMAT),
                arguments(":", URL_FORMAT),
                arguments(":https://example.com/", URL_FORMAT),
                arguments("ht/tp://example.com/", URL_FORMAT),
                arguments("ht?tp://example.com/", URL_FORMAT),
                arguments("http" + ch(0x17F) + "://example.com/", URL_FORMAT),
                arguments("httpx://example.com/", SCHEME),
                arguments("HTTPs+x://example.com/", SCHEME),
                arguments("https:" + BS + BS + "example.com/", URL_CHARACTERS),
                arguments("https://example.com/" + ch(0x7F), URL_CHARACTERS),
                arguments("https://example.com/" + ch(0), URL_CHARACTERS),
                arguments("https://example.com/" + ch(0x3000), URL_CHARACTERS),
                arguments("https://exa" + ch(0xA0) + "mple.com/", IDN_UNSUPPORTED),
                arguments("https://ex" + ch(0x430) + "mple.com/", IDN_UNSUPPORTED),
                arguments("https://example" + ch(0xFF0E) + "com/", IDN_UNSUPPORTED),
                arguments("https://example" + ch(0xFF61) + "com/", IDN_UNSUPPORTED),
                arguments("https://" + ch(0x212A) + "ey.example/", IDN_UNSUPPORTED),
                arguments("https://user@example.com:8080/", USERINFO),
                arguments("https://[example.com]/", IP_LITERAL),
                arguments("https://example.com]/", HOST_FORMAT),
                arguments("https://ex%2Eample.com/", PERCENT_ENCODING),
                arguments("https://example.com%2F/", PERCENT_ENCODING),
                arguments("https://0x/", IP_LITERAL),
                arguments("https://example.0X1F/", IP_LITERAL),
                arguments("https://example.123/", IP_LITERAL),
                arguments("https://example.123./", IP_LITERAL),
                arguments("https://0300.0250.0.1/", IP_LITERAL),
                arguments("https://192.168.1.1:443/", IP_LITERAL),
                arguments("https://169.254.169.254/latest/meta-data/", IP_LITERAL),
                arguments("https://EXAMPLE.COM./", HOST_FORMAT),
                arguments("https://example..com/", HOST_FORMAT),
                arguments(".https://example.com/", URL_FORMAT),
                arguments("https://.example.com/", HOST_FORMAT),
                arguments("https://a-.example.com/", HOST_FORMAT),
                arguments("https://XN--abc.example/", IDN_UNSUPPORTED),
                arguments("https://a.xn--p1ai/", IDN_UNSUPPORTED),
                arguments("https://APP.LOCALHOST/", HOST_FORBIDDEN),
                arguments("https://localhost.localdomain/", HOST_FORBIDDEN),
                arguments("https://a.b.home.arpa/", HOST_FORBIDDEN),
                arguments("https://1.0.0.127.in-addr.arpa/", HOST_FORBIDDEN),
                arguments("https://db.internal/", HOST_FORBIDDEN),
                arguments("https://foo.example/", HOST_FORBIDDEN),
                arguments("https://a.b-c.example/", HOST_FORBIDDEN),
                arguments("https://exa/", HOST_FORBIDDEN),
                arguments("https://example.com/?a#b#c", URL_CHARACTERS),
                arguments("https://example.com/|", URL_CHARACTERS),
                arguments("https://example.com/^", URL_CHARACTERS),
                arguments("https://example.com/`", URL_CHARACTERS),
                arguments("https://example.com/{x}", URL_CHARACTERS),
                arguments("https://example.com/\"", URL_CHARACTERS),
                arguments("https://example.com/<x>", URL_CHARACTERS),
                arguments("https://example.com/a?b]", URL_CHARACTERS),
                arguments("https://example.com/%", PERCENT_ENCODING),
                arguments("https://example.com/%a", PERCENT_ENCODING),
                arguments("https://example.com/?x=%G1", PERCENT_ENCODING),
                arguments("https://example.com/%7f", URL_CHARACTERS),
                arguments("https://example.com/%7F", URL_CHARACTERS),
                arguments("https://example.com/?a=%5C", URL_CHARACTERS),
                arguments("https://example.com/#%1f", URL_CHARACTERS),
                arguments("https://example.com/%2e/a", DOT_SEGMENT),
                arguments("https://example.com/.%2E;x/", DOT_SEGMENT),
                arguments("https://example.com/a/.;p", DOT_SEGMENT),
                arguments("https://example.com/..", DOT_SEGMENT),
                arguments("https://example.com/%2E%2E", DOT_SEGMENT));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("adversarial")
    void adversarialRejections(String input, RejectionCode expected) {
        assertEquals(expected, rejection(input), input);
    }

    /** Near-boundary inputs that the rules do not reject; they still need DNS and address checks later. */
    static Stream<Arguments> nearBoundaryAccepted() {
        return Stream.of(
                arguments("https://mylocalhost.com/", "https://mylocalhost.com/"),
                arguments("https://localhost.com/", "https://localhost.com/"),
                arguments("https://example.com/", "https://example.com/"),
                arguments("https://example.0xg/", "https://example.0xg/"),
                arguments("https://example.1a/", "https://example.1a/"),
                arguments("https://a.b-c.example.net/", "https://a.b-c.example.net/"),
                arguments("https://example.com/...", "https://example.com/..."),
                arguments("https://example.com/.a/a.", "https://example.com/.a/a."),
                arguments("https://example.com/%2e%2ex", "https://example.com/%2e%2ex"),
                arguments("https://example.com/?%2e%2e", "https://example.com/?%2e%2e"),
                arguments("https://example.com/#/../x", "https://example.com/"),
                arguments("https://example.com/%20", "https://example.com/%20"),
                arguments("https://example.com/%5b", "https://example.com/%5b"),
                arguments("https://example.com/a?b?c", "https://example.com/a?b?c"),
                arguments("https://example.com/~!$&'()*+,;=:@", "https://example.com/~!$&'()*+,;=:@"),
                arguments("HtTpS://ExAmPlE.CoM", "https://example.com/"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nearBoundaryAccepted")
    void nearBoundaryInputsAreAcceptedSyntacticallyOnly(String input, String canonical) {
        assertEquals(canonical, accepted(input).canonicalForm());
    }

    @Test void nullInputIsAContractErrorWithAConstantMessage() {
        NullPointerException e = assertThrows(NullPointerException.class, () -> DestinationUrl.parse(null));
        assertEquals("url must not be null", e.getMessage());
    }

    /** A valid host of exactly {@code length} characters: three 63-character labels, a filler label and "com". */
    static String host(int length) {
        String fixed = repeat('a', 63) + "." + repeat('b', 63) + "." + repeat('c', 63) + ".";
        int filler = length - fixed.length() - ".com".length();
        return fixed + repeat('d', filler) + ".com";
    }
}
