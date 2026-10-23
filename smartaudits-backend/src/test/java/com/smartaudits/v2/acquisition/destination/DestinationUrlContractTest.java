package com.smartaudits.v2.acquisition.destination;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contracts of the URL block (ADR-0002 sections 5, 13 and 15): immutability, no public construction
 * of validated types, sealed results, redacted diagnostics with fake secrets, and a static scan of
 * the new sources for forbidden APIs (no resolution, no network, no locale-dependent case mapping).
 */
class DestinationUrlContractTest {
    private static final String SECRET = "S3CR3T-T0K3N";
    private static final List<Class<?>> NEW_TYPES = List.of(DestinationUrl.class, ValidatedHost.class,
            UrlParseResult.class, UrlParseResult.Parsed.class, UrlParseResult.Rejected.class, RejectionCode.class,
            DestinationUrlParser.class);
    private static final List<String> NEW_SOURCES = List.of("DestinationUrl.java", "ValidatedHost.java",
            "UrlParseResult.java", "RejectionCode.java", "DestinationUrlParser.java");
    private static final List<String> FORBIDDEN = List.of(
            "java.net.URL", "URLConnection", "URLDecoder", "URLEncoder", "java.net.IDN", "IDN.", "InetAddress",
            "Inet4Address", "Inet6Address", "Socket", "HttpClient", "equalsIgnoreCase", "toLowerCase", "toUpperCase",
            "normalize(", "resolve(", "toURL(", "toASCIIString", "getHost(", "getPort(", "Normalizer", "java.io.",
            "java.nio.file", "getResource", "System.getenv", "System.getProperty", "Logger", "System.out",
            "System.err", "printStackTrace", "getMessage", "initCause", "AddressPolicy", "Serializable");

    @Test void exactlyEighteenStableRejectionCodes() {
        assertEquals(List.of("URL_FORMAT", "URL_TOO_LONG", "URL_CHARACTERS", "SCHEME", "USERINFO", "HOST_FORMAT",
                "HOST_FORBIDDEN", "PORT", "IP_LITERAL", "IDN_UNSUPPORTED", "PERCENT_ENCODING", "DOT_SEGMENT",
                "DNS_FAILURE", "DNS_EMPTY", "DNS_INVALID", "DNS_LIMIT", "ADDRESS_FORBIDDEN", "ADDRESS_MIXED"),
                List.of(RejectionCode.values()).stream().map(Enum::name).collect(java.util.stream.Collectors.toList()));
    }

    @Test void parseResultIsSealedWithExactlyTwoFinalVariants() {
        assertTrue(UrlParseResult.class.isSealed());
        assertEquals(Set.of(UrlParseResult.Parsed.class, UrlParseResult.Rejected.class),
                Set.of(UrlParseResult.class.getPermittedSubclasses()));
        assertTrue(Modifier.isFinal(UrlParseResult.Parsed.class.getModifiers()));
        assertTrue(Modifier.isFinal(UrlParseResult.Rejected.class.getModifiers()));
    }

    @Test void validatedTypesHaveNoPublicConstructionPathAndAreImmutable() {
        for (Class<?> type : NEW_TYPES) {
            assertFalse(type.isRecord(), type + " is a record");
            assertFalse(Serializable.class.isAssignableFrom(type) && !type.isEnum(), type + " is Serializable");
            if (!type.isInterface() && !type.isEnum()) {
                assertTrue(Modifier.isFinal(type.getModifiers()), type + " is not final");
            }
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                assertFalse(Modifier.isPublic(constructor.getModifiers()), type + " has a public constructor");
            }
            for (Field field : type.getDeclaredFields()) {
                if (!field.isSynthetic()) {
                    assertTrue(Modifier.isFinal(field.getModifiers()), type.getSimpleName() + "." + field.getName());
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                assertFalse(method.getName().startsWith("set") && Modifier.isPublic(method.getModifiers()),
                        type.getSimpleName() + "." + method.getName());
            }
        }
        for (Method method : DestinationUrl.class.getMethods()) {
            boolean returnsValidated = method.getReturnType() == DestinationUrl.class
                    || method.getReturnType() == ValidatedHost.class;
            if (Modifier.isStatic(method.getModifiers())) {
                assertFalse(returnsValidated, "public static factory of a validated type: " + method.getName());
            }
        }
    }

    @Test void theOnlyEntryPointIsParseAndAValidatedHostComesOnlyFromAnAcceptedUrl() throws Exception {
        Method parse = DestinationUrl.class.getMethod("parse", String.class);
        assertEquals(UrlParseResult.class, parse.getReturnType());
        UrlParseResult result = DestinationUrl.parse("https://Example.COM/");
        ValidatedHost host = ((UrlParseResult.Parsed) result).url().host();
        assertEquals("example.com", host.name());
        assertEquals(host, ((UrlParseResult.Parsed) DestinationUrl.parse("https://example.com:443/x")).url().host());
    }

    @Test void diagnosticsNeverRevealInputHostPathQueryUserinfoOrFragment() {
        List<String> inputs = List.of(
                "https://user:" + SECRET + "@example.com/",
                "https://example.com/" + SECRET + "?token=" + SECRET + "#" + SECRET,
                "https://" + SECRET.toLowerCase(java.util.Locale.ROOT) + ".example.com/p?k=" + SECRET,
                "https://example.com:" + SECRET + "/",
                "https://example.com/" + SECRET + " x",
                "ftp://" + SECRET + "@example.com/",
                "https://example.com/%zz" + SECRET,
                "https://[fe80::1%25" + SECRET + "]/");
        for (String input : inputs) {
            UrlParseResult result = DestinationUrl.parse(input);
            List<String> texts = new ArrayList<>(List.of(result.toString()));
            if (result instanceof UrlParseResult.Parsed parsed) {
                texts.add(parsed.url().toString());
                texts.add(parsed.url().host().toString());
            }
            for (String text : texts) {
                assertFalse(text.toUpperCase(java.util.Locale.ROOT).contains(SECRET), text);
                assertFalse(text.contains("example"), text);
                assertFalse(text.contains("fe80"), text);
            }
        }
        DestinationUrl url = ((UrlParseResult.Parsed) DestinationUrl.parse("https://example.com/a?b")).url();
        assertEquals("DestinationUrl[https:443]", url.toString());
        assertEquals("ValidatedHost[redacted]", url.host().toString());
        assertEquals("Parsed[DestinationUrl[https:443]]", DestinationUrl.parse("https://example.com/").toString());
        assertEquals("Rejected[PORT, IANA-SNAPSHOT-02]", DestinationUrl.parse("http://example.com:8080/").toString());
        assertEquals("DestinationUrl[http:80]", ((UrlParseResult.Parsed) DestinationUrl.parse("http://example.com")).url().toString());
    }

    /** ADR-0002 section 5: the tests must pin that java.net.URI (Java 17) tells empty from absent query/fragment. */
    @Test void javaNetUriDistinguishesEmptyFromAbsentComponents() throws Exception {
        assertEquals("", new java.net.URI("https://example.com/p?").getRawQuery());
        assertNull(new java.net.URI("https://example.com/p").getRawQuery());
        assertEquals("", new java.net.URI("https://example.com/p#").getRawFragment());
        assertNull(new java.net.URI("https://example.com/p").getRawFragment());
        assertEquals("", new java.net.URI("https://example.com").getRawPath());
        assertEquals("example.0xg", new java.net.URI("https://example.0xg/").getRawAuthority());
        assertNull(new java.net.URI("https://exa_mple.com/").getHost());
    }

    @Test void parsingIsDeterministicAndHasNoSharedMutableState() {
        for (int i = 0; i < 3; i++) {
            assertEquals("https://example.com/a?b", ((UrlParseResult.Parsed) DestinationUrl.parse("HTTPS://EXAMPLE.com/a?b#c"))
                    .url().canonicalForm());
            assertEquals(RejectionCode.HOST_FORBIDDEN, ((UrlParseResult.Rejected) DestinationUrl.parse("https://localhost/")).code());
        }
    }

    @Test void newSourcesUseNoForbiddenApiAndNeverClassifyLiterals() throws IOException {
        Path dir = Paths.get("src", "main", "java", "com", "smartaudits", "v2", "acquisition", "destination");
        List<String> violations = new ArrayList<>();
        for (String name : NEW_SOURCES) {
            String code = DestinationPackageStaticTest.stripComments(Files.readString(dir.resolve(name), StandardCharsets.UTF_8));
            for (String token : FORBIDDEN) {
                if (code.contains(token)) {
                    violations.add(name + ": " + token);
                }
            }
        }
        assertEquals(List.of(), violations);
        String parser = Files.readString(dir.resolve("DestinationUrlParser.java"), StandardCharsets.UTF_8);
        assertTrue(parser.contains("import java.net.URI;"));
        assertTrue(parser.contains("AddressCatalog.snapshot().isForbiddenName(host)"));
    }
}
