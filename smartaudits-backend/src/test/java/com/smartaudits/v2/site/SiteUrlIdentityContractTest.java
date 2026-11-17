package com.smartaudits.v2.site;

import com.smartaudits.v2.acquisition.destination.DestinationDecision;
import com.smartaudits.v2.acquisition.destination.DestinationPolicy;
import com.smartaudits.v2.acquisition.destination.DestinationUrl;
import com.smartaudits.v2.acquisition.destination.DnsAnswer;
import com.smartaudits.v2.acquisition.destination.HostResolver;
import com.smartaudits.v2.acquisition.destination.RejectionCode;
import com.smartaudits.v2.acquisition.destination.UrlParseResult;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.smartaudits.v2.site.SiteUrlIdentityMatrixTest.accepted;
import static com.smartaudits.v2.site.SiteUrlIdentityMatrixTest.assertDestinationRejection;
import static com.smartaudits.v2.site.SiteUrlIdentityMatrixTest.assertSiteRejection;
import static com.smartaudits.v2.site.SiteUrlIdentityMatrixTest.ch;
import static com.smartaudits.v2.site.SiteUrlIdentityMatrixTest.host;
import static com.smartaudits.v2.site.SiteUrlIdentityMatrixTest.rejected;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract of the value object of docs/F4_1C_SITE_URL_IDENTITY.md: idempotent and stable canonical
 * form, round trip, equality, immutability, no public construction, redacted diagnostics, no use of
 * the default locale, and the separation between a Site identity, the acquisition destination and a
 * destination approval.
 */
class SiteUrlIdentityContractTest {
    private static final String SECRET = "S3CR3T-T0K3N";

    /** Every accepted spelling used by the matrix. */
    private static List<String> acceptedInputs() {
        List<String> inputs = new ArrayList<>();
        SiteUrlIdentityMatrixTest.acceptedSpellings().forEach(a -> inputs.add((String) a.get()[1]));
        SiteUrlIdentityMatrixTest.equivalenceClasses().forEach(inputs::addAll);
        inputs.addAll(SiteUrlIdentityMatrixTest.knownDistinct());
        return inputs;
    }

    /** Every rejected text used by the matrix. */
    private static List<String> rejectedInputs() {
        List<String> inputs = new ArrayList<>();
        SiteUrlIdentityMatrixTest.destinationRejections().forEach(a -> inputs.add((String) a.get()[1]));
        SiteUrlIdentityMatrixTest.siteRejections().forEach(a -> inputs.add((String) a.get()[1]));
        return inputs;
    }

    @Test void normalizationIsIdempotentAndTheCanonicalFormRoundTrips() {
        assertTrue(acceptedInputs().size() > 80);
        for (String input : acceptedInputs()) {
            SiteUrlIdentity first = accepted(input);
            String canonical = first.canonicalForm();
            SiteUrlIdentity again = accepted(canonical);
            assertEquals(first, again, input);
            assertEquals(first.hashCode(), again.hashCode(), input);
            assertEquals(canonical, again.canonicalForm(), input);
            assertEquals(first.scheme(), again.scheme(), input);
            assertEquals(first.host(), again.host(), input);
            assertEquals(canonical, accepted(again.canonicalForm()).canonicalForm(), input);
            // The canonical form is one of the accepted spellings of itself: the bare one.
            assertEquals(canonical, first.scheme() + "://" + first.host(), input);
        }
    }

    @Test void theCanonicalFormIsStableAsciiLowercaseAndBounded() {
        Pattern label = Pattern.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?");
        for (String input : acceptedInputs()) {
            String canonical = accepted(input).canonicalForm();
            for (int i = 0; i < 3; i++) {
                assertEquals(canonical, accepted(input).canonicalForm(), input);
            }
            assertTrue(canonical.startsWith("https://") || canonical.startsWith("http://"), canonical);
            String host = canonical.substring(canonical.indexOf("://") + 3);
            String[] labels = host.split("[.]", -1);
            assertTrue(labels.length >= 2, canonical);
            for (String each : labels) {
                assertTrue(label.matcher(each).matches(), canonical);
            }
            assertTrue(canonical.length() <= SiteUrlIdentity.MAX_CANONICAL_LENGTH, canonical);
            assertEquals(canonical.length(), canonical.getBytes(StandardCharsets.UTF_8).length, "ASCII: characters are bytes");
            assertEquals(canonical, canonical.toLowerCase(Locale.ROOT), canonical);
            for (String never : List.of("/", "?", "#", "@", "%", " ", ":")) {
                assertFalse(host.contains(never), canonical);
            }
        }
        assertEquals("https://example.com", accepted("HTTPS://EXAMPLE.COM:443/").canonicalForm());
        assertEquals("http://www.example.com", accepted("http://WWW.example.com:80").canonicalForm());
    }

    @Test void theLengthBoundIsExact() {
        assertEquals(261, SiteUrlIdentity.MAX_CANONICAL_LENGTH);
        assertEquals(253, host(253).length());
        assertEquals(261, accepted("https://" + host(253)).canonicalForm().length());
        assertEquals(260, accepted("http://" + host(253)).canonicalForm().length());
        String longestSpelling = "https://" + host(253) + ":443/";
        assertEquals(266, longestSpelling.length());
        assertEquals(261, accepted(longestSpelling).canonicalForm().length());
        assertDestinationRejection(RejectionCode.HOST_FORMAT, "https://" + host(254), "one character over the host limit");
        // Overflow: a text over 2048 characters is refused before anything else, whatever it contains.
        assertDestinationRejection(RejectionCode.URL_TOO_LONG, "https://example.com/" + "a".repeat(2029), "2049 characters");
        assertDestinationRejection(RejectionCode.URL_TOO_LONG, "x".repeat(100_000), "very long text");
        assertDestinationRejection(RejectionCode.URL_TOO_LONG, "https://example.com" + " ".repeat(2048), "long and invalid");
    }

    @Test void equalityAndHashCodeFollowTheCanonicalForm() {
        SiteUrlIdentity a = accepted("https://example.com");
        SiteUrlIdentity b = accepted("HTTPS://EXAMPLE.COM:443/");
        SiteUrlIdentity c = accepted("http://example.com");
        assertNotSame(a, b);
        assertEquals(a, a);
        assertEquals(a, b);
        assertEquals(b, a);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals(a.canonicalForm().hashCode(), a.hashCode());
        assertNotEquals(a, c);
        assertNotEquals(c, a);
        assertNotEquals(null, a);
        assertFalse(a.equals(null));
        assertFalse(a.equals(a.canonicalForm()), "an identity is not its text");
        assertFalse(a.equals(new Object()));
        Map<SiteUrlIdentity, String> byIdentity = new HashMap<>();
        byIdentity.put(a, "first");
        assertEquals("first", byIdentity.get(b));
        assertNull(byIdentity.get(c));
        // Equal exactly when the canonical forms are equal, over every accepted spelling.
        List<SiteUrlIdentity> all = acceptedInputs().stream().map(SiteUrlIdentityMatrixTest::accepted).toList();
        Set<String> forms = all.stream().map(SiteUrlIdentity::canonicalForm).collect(Collectors.toSet());
        assertEquals(forms.size(), new HashSet<>(all).size());
        for (SiteUrlIdentity x : all) {
            for (SiteUrlIdentity y : all) {
                assertEquals(x.canonicalForm().equals(y.canonicalForm()), x.equals(y));
                if (x.equals(y)) {
                    assertEquals(x.hashCode(), y.hashCode());
                }
            }
        }
    }

    @Test void nullIsAContractErrorWithAConstantMessage() {
        NullPointerException e = assertThrows(NullPointerException.class, () -> SiteUrlIdentity.parse(null));
        assertEquals("url must not be null", e.getMessage());
    }

    @Test void invalidTextNeverThrows() {
        for (String input : rejectedInputs()) {
            assertDoesNotThrow(() -> SiteUrlIdentity.parse(input), input);
        }
        for (String input : List.of(":", "/", "?", "#", "%", "@", "[", "]", "://", "http://", "https://:", "https://#",
                "https://?", "https:///", "https://example.com:#", "https://example.com/%", ch(0xD800), ch(0xDC00) + ch(0xD800),
                "https://example.com" + ch(0xD800), ch(0) + ch(0) + ch(0))) {
            assertTrue(SiteUrlIdentity.parse(input) instanceof SiteUrlIdentityResult.Rejected, input);
        }
    }

    @Test void aRejectionCarriesTheDestinationCodeExactlyForDestinationSyntax() {
        Set<RejectionCode> syntaxCodes = EnumSet.of(RejectionCode.URL_FORMAT, RejectionCode.URL_TOO_LONG,
                RejectionCode.URL_CHARACTERS, RejectionCode.SCHEME, RejectionCode.USERINFO, RejectionCode.HOST_FORMAT,
                RejectionCode.HOST_FORBIDDEN, RejectionCode.PORT, RejectionCode.IP_LITERAL, RejectionCode.IDN_UNSUPPORTED,
                RejectionCode.PERCENT_ENCODING, RejectionCode.DOT_SEGMENT);
        Set<RejectionCode> seen = EnumSet.noneOf(RejectionCode.class);
        Set<SiteUrlRejection> reasons = EnumSet.noneOf(SiteUrlRejection.class);
        for (String input : rejectedInputs()) {
            SiteUrlIdentityResult.Rejected rejection = rejected(input);
            reasons.add(rejection.reason());
            assertEquals(rejection.reason() == SiteUrlRejection.DESTINATION_SYNTAX, rejection.destinationRejection().isPresent(), input);
            rejection.destinationRejection().ifPresent(destination -> {
                // Exactly what the destination parser answers for the same text: nothing is reinterpreted.
                UrlParseResult.Rejected direct = (UrlParseResult.Rejected) DestinationUrl.parse(input);
                assertEquals(direct.code(), destination.code(), input);
                assertEquals(direct.catalogRevision(), destination.catalogRevision(), input);
                assertFalse(destination.catalogRevision().isEmpty());
                seen.add(destination.code());
            });
        }
        // The corpus exercises all twelve syntax codes and no DNS or address code can ever appear offline.
        assertEquals(syntaxCodes, seen);
        assertEquals(EnumSet.of(SiteUrlRejection.DESTINATION_SYNTAX, SiteUrlRejection.PATH, SiteUrlRejection.QUERY,
                SiteUrlRejection.FRAGMENT), reasons);
    }

    @Test void aTextIsAcceptedOnlyIfTheDestinationProfileAcceptsIt() {
        for (String input : acceptedInputs()) {
            assertTrue(DestinationUrl.parse(input) instanceof UrlParseResult.Parsed, input);
        }
        for (String input : rejectedInputs()) {
            SiteUrlIdentityResult.Rejected rejection = rejected(input);
            boolean destinationAccepts = DestinationUrl.parse(input) instanceof UrlParseResult.Parsed;
            assertEquals(destinationAccepts, rejection.reason() != SiteUrlRejection.DESTINATION_SYNTAX, input);
        }
    }

    @Test void diagnosticsNeverRevealTheTextTheHostOrSecrets() {
        String label = SECRET.toLowerCase(Locale.ROOT);
        List<String> inputs = List.of(
                "https://" + label + ".example.com",
                "https://example.com/" + SECRET,
                "https://example.com/?token=" + SECRET,
                "https://example.com/#" + SECRET,
                "https://user:" + SECRET + "@example.com",
                "https://example.com:" + SECRET,
                "ftp://" + label + ".example.com",
                "https://" + label + ".example.com/" + SECRET + " x",
                "https://" + label + ".example.com/" + SECRET + "?" + SECRET + "#" + SECRET);
        for (String input : inputs) {
            SiteUrlIdentityResult result = SiteUrlIdentity.parse(input);
            List<String> texts = new ArrayList<>(List.of(result.toString()));
            if (result instanceof SiteUrlIdentityResult.Accepted ok) {
                texts.add(ok.identity().toString());
                assertTrue(ok.identity().canonicalForm().contains(label), "only the explicit accessor reveals the host");
            } else {
                texts.add(((SiteUrlIdentityResult.Rejected) result).reason().toString());
            }
            for (String text : texts) {
                assertFalse(text.toUpperCase(Locale.ROOT).contains(SECRET), text);
                assertFalse(text.contains("example"), text);
            }
        }
        assertEquals("SiteUrlIdentity[https]", accepted("https://example.com").toString());
        assertEquals("SiteUrlIdentity[http]", accepted("HTTP://example.com").toString());
        assertEquals("Accepted[SiteUrlIdentity[https]]", SiteUrlIdentity.parse("https://example.com/").toString());
        assertEquals("Rejected[PATH]", SiteUrlIdentity.parse("https://example.com/a").toString());
        assertEquals("Rejected[QUERY]", SiteUrlIdentity.parse("https://example.com/?a").toString());
        assertEquals("Rejected[FRAGMENT]", SiteUrlIdentity.parse("https://example.com/#a").toString());
        assertEquals("Rejected[DESTINATION_SYNTAX, " + DestinationUrl.parse("https://example.com:8443") + "]",
                SiteUrlIdentity.parse("https://example.com:8443").toString());
        assertTrue(SiteUrlIdentity.parse("https://example.com:8443").toString().contains("PORT"));
    }

    @Test void theResultIsSealedWithTwoFinalVariantsAndFiveStableReasons() {
        assertTrue(SiteUrlIdentityResult.class.isSealed());
        assertEquals(Set.of(SiteUrlIdentityResult.Accepted.class, SiteUrlIdentityResult.Rejected.class),
                Set.of(SiteUrlIdentityResult.class.getPermittedSubclasses()));
        assertEquals(List.of("DESTINATION_SYNTAX", "PATH", "QUERY", "FRAGMENT", "NOT_AN_ORIGIN"),
                Arrays.stream(SiteUrlRejection.values()).map(Enum::name).toList());
    }

    @Test void typesAreFinalImmutableAndHaveNoPublicConstructionPath() {
        for (Class<?> type : List.of(SiteUrlIdentity.class, SiteUrlIdentityResult.Accepted.class,
                SiteUrlIdentityResult.Rejected.class, SiteUrlRejection.class)) {
            assertFalse(type.isRecord(), type + " is a record");
            assertFalse(Serializable.class.isAssignableFrom(type) && !type.isEnum(), type + " is Serializable");
            if (!type.isEnum()) {
                assertTrue(Modifier.isFinal(type.getModifiers()), type + " is not final");
            }
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                assertFalse(Modifier.isPublic(constructor.getModifiers()) || Modifier.isProtected(constructor.getModifiers()),
                        type + " has an accessible constructor");
            }
            for (Field field : type.getDeclaredFields()) {
                if (!field.isSynthetic()) {
                    assertTrue(Modifier.isFinal(field.getModifiers()), type.getSimpleName() + "." + field.getName());
                    if (!type.isEnum() && !Modifier.isStatic(field.getModifiers())) {
                        assertTrue(Modifier.isPrivate(field.getModifiers()), type.getSimpleName() + "." + field.getName());
                    }
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                assertFalse(method.getName().startsWith("set") && Modifier.isPublic(method.getModifiers()),
                        type.getSimpleName() + "." + method.getName());
            }
        }
        // parse is the only public way to obtain an identity, and the only public static member besides the bound.
        Set<String> publicStatic = Arrays.stream(SiteUrlIdentity.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers()))
                .map(Method::getName).collect(Collectors.toSet());
        assertEquals(Set.of("parse"), publicStatic);
        Set<String> publicInstance = Arrays.stream(SiteUrlIdentity.class.getDeclaredMethods())
                .filter(m -> !m.isSynthetic() && Modifier.isPublic(m.getModifiers()) && !Modifier.isStatic(m.getModifiers()))
                .map(Method::getName).collect(Collectors.toSet());
        assertEquals(Set.of("scheme", "host", "canonicalForm", "equals", "hashCode", "toString"), publicInstance);
        Set<String> publicFields = Arrays.stream(SiteUrlIdentity.class.getDeclaredFields())
                .filter(f -> Modifier.isPublic(f.getModifiers())).map(Field::getName).collect(Collectors.toSet());
        assertEquals(Set.of("MAX_CANONICAL_LENGTH"), publicFields);
        for (Method method : SiteUrlIdentityResult.Accepted.class.getMethods()) {
            if (method.getReturnType() == SiteUrlIdentity.class) {
                assertFalse(Modifier.isStatic(method.getModifiers()), "public static source of an identity: " + method.getName());
            }
        }
    }

    /**
     * The JVM default locale as a whole: the general default and the DISPLAY and FORMAT defaults,
     * which {@code Locale.setDefault(Locale)} also overwrites. Restoring only the general default
     * would lose distinct category defaults. This is global JVM state: a test that changes it must
     * not run concurrently with other tests unless it is isolated.
     */
    private record DefaultLocales(Locale general, Locale display, Locale format) {
        static DefaultLocales capture() {
            return new DefaultLocales(Locale.getDefault(), Locale.getDefault(Locale.Category.DISPLAY),
                    Locale.getDefault(Locale.Category.FORMAT));
        }

        /** The general default first, because setting it resets both categories; then each category. */
        void restore() {
            Locale.setDefault(general);
            Locale.setDefault(Locale.Category.DISPLAY, display);
            Locale.setDefault(Locale.Category.FORMAT, format);
        }
    }

    @Test void theLocaleRestorationKeepsDistinctCategoryDefaults() {
        DefaultLocales original = DefaultLocales.capture();
        try {
            new DefaultLocales(Locale.US, Locale.FRANCE, Locale.GERMANY).restore();
            DefaultLocales mixed = DefaultLocales.capture();
            assertEquals(new DefaultLocales(Locale.US, Locale.FRANCE, Locale.GERMANY), mixed);
            Locale.setDefault(new Locale("tr", "TR"));
            assertEquals(new Locale("tr", "TR"), Locale.getDefault(Locale.Category.FORMAT), "setDefault really overwrites FORMAT");
            mixed.restore();
            assertEquals(new DefaultLocales(Locale.US, Locale.FRANCE, Locale.GERMANY), DefaultLocales.capture());
        } finally {
            original.restore();
        }
        assertEquals(original, DefaultLocales.capture());
    }

    @Test void resultsDoNotDependOnTheDefaultLocale() {
        Locale turkish = new Locale("tr", "TR");
        assertNotEquals("title", "TITLE".toLowerCase(turkish), "the Turkish mapping really differs: the test has teeth");
        DefaultLocales original = DefaultLocales.capture();
        try {
            for (Locale locale : List.of(turkish, new Locale("az", "AZ"), new Locale("lt", "LT"), new Locale("el", "GR"),
                    Locale.ROOT, Locale.GERMANY, Locale.JAPAN)) {
                Locale.setDefault(locale);
                String where = locale.toString();
                assertEquals("https://title.example.com", accepted("HTTPS://TITLE.EXAMPLE.COM/").canonicalForm(), where);
                assertEquals("http://info.example.com", accepted("HTTP://INFO.EXAMPLE.COM:80").canonicalForm(), where);
                assertEquals("https://iii.example.com", accepted("https://III.example.com").canonicalForm(), where);
                assertEquals(accepted("https://TITLE.example.com"), accepted("https://title.example.com"), where);
                assertEquals("title.example.com", accepted("https://TITLE.example.com").host(), where);
                assertEquals("https", accepted("HTTPS://title.example.com").scheme(), where);
                // Neither the dotless i nor the dotted capital I is ever folded into an ASCII letter.
                assertDestinationRejection(RejectionCode.IDN_UNSUPPORTED, "https://t" + ch(0x131) + "tle.example.com", where);
                assertDestinationRejection(RejectionCode.IDN_UNSUPPORTED, "https://T" + ch(0x130) + "TLE.example.com", where);
                assertSiteRejection(SiteUrlRejection.PATH, "https://example.com/INDEX", where);
                assertTrue(SiteUrlIdentity.isOriginSpelling("HTTPS://TITLE.EXAMPLE.COM", "https://title.example.com", "443"), where);
            }
        } finally {
            original.restore();
        }
        assertEquals(original, DefaultLocales.capture(), "all three default locales restored");
    }

    @Test void theSpellingRuleAcceptsOnlyTheFourSpellingsAndFailsClosed() {
        String origin = "https://example.com";
        for (String spelling : List.of(origin, origin + "/", origin + ":443", origin + ":443/", "HTTPS://EXAMPLE.COM",
                "hTtPs://ExAmPlE.cOm:443/")) {
            assertTrue(SiteUrlIdentity.isOriginSpelling(spelling, origin, "443"), spelling);
        }
        for (String other : List.of(origin + ":8443", origin + ":8443/", origin + ":80", origin + ":4430", origin + ":44",
                origin + ":443:443", origin + ":443//", origin + ":443/a", origin + ":", origin + ":/", origin + "//",
                origin + "/a", origin + "?", origin + "/?", origin + "#", origin + "/#", origin + " ", origin + ".",
                origin + "x", origin + "/ ", "https://example.co", "https://example.co/", "", "https", "http://example.com",
                "https://www.example.com", "https://example.org", "https:/example.com", " " + origin,
                "https://e" + ch(0x445) + "ample.com", "https://example" + ch(0xFF0E) + "com",
                "https://e" + ch(0x212A) + "ample.com", "http" + ch(0x17F) + "://example.com")) {
            assertFalse(SiteUrlIdentity.isOriginSpelling(other, origin, "443"), other);
        }
        // A port is accepted only when it is the default this contract knows for the scheme.
        assertTrue(SiteUrlIdentity.isOriginSpelling("http://example.com:80", "http://example.com", "80"));
        assertFalse(SiteUrlIdentity.isOriginSpelling("http://example.com:443", "http://example.com", "80"));
        assertFalse(SiteUrlIdentity.isOriginSpelling(origin, origin, null), "an unknown scheme has no spelling");
        assertFalse(SiteUrlIdentity.isOriginSpelling(origin + ":443", origin, null));
        assertEquals("443", SiteUrlIdentity.defaultPort("https"));
        assertEquals("80", SiteUrlIdentity.defaultPort("http"));
        for (String unknown : List.of("ftp", "ws", "wss", "HTTPS", "HTTP", "", "httpx", "http ")) {
            assertNull(SiteUrlIdentity.defaultPort(unknown), unknown);
        }
    }

    @Test void aSiteIdentityIsNotTheAcquisitionDestinationNorAPageUrl() {
        SiteUrlIdentity site = accepted("https://example.com/");
        // The first acquisition URL of a future Scan is derived by the destination parser: a different text.
        DestinationUrl root = ((UrlParseResult.Parsed) DestinationUrl.parse(site.canonicalForm())).url();
        assertEquals("https://example.com/", root.canonicalForm());
        assertNotEquals(site.canonicalForm(), root.canonicalForm());
        assertEquals(site.scheme(), root.scheme());
        assertEquals(site.host(), root.host().name());
        // Pages of one origin are valid destinations, different from each other, and none is a Site identity.
        List<String> pages = List.of("https://example.com/a", "https://example.com/b", "https://example.com/a/",
                "https://example.com/?p=1", "https://example.com/?p=2");
        Set<DestinationUrl> destinations = new HashSet<>();
        for (String page : pages) {
            destinations.add(((UrlParseResult.Parsed) DestinationUrl.parse(page)).url());
            assertTrue(SiteUrlIdentity.parse(page) instanceof SiteUrlIdentityResult.Rejected, page);
        }
        assertEquals(pages.size(), destinations.size());
        // A fragment is dropped from a destination but refused in a Site identity: the two contracts differ.
        assertTrue(DestinationUrl.parse("https://example.com/#x") instanceof UrlParseResult.Parsed);
        assertSiteRejection(SiteUrlRejection.FRAGMENT, "https://example.com/#x", "fragment");
        // No public member of an accepted identity hands out a destination, a host object or a decision.
        for (Class<?> type : List.of(SiteUrlIdentity.class, SiteUrlIdentityResult.Accepted.class)) {
            for (Method method : type.getMethods()) {
                List<Class<?>> exposed = new ArrayList<>(List.of(method.getParameterTypes()));
                exposed.add(method.getReturnType());
                for (Class<?> each : exposed) {
                    assertFalse(each.getName().startsWith("com.smartaudits.v2.acquisition"), type.getSimpleName() + "." + method.getName());
                }
            }
        }
    }

    @Test void anAcceptedIdentityIsNeverADestinationApproval() {
        SiteUrlIdentity site = accepted("https://example.com");
        AtomicInteger calls = new AtomicInteger();
        Map<String, byte[]> answers = Map.of(
                "loopback", new byte[]{127, 0, 0, 1},
                "private", new byte[]{10, 0, 0, 1},
                "metadata", new byte[]{(byte) 169, (byte) 254, (byte) 169, (byte) 254},
                "public", new byte[]{8, 8, 8, 8});
        Map<String, RejectionCode> refusals = new HashMap<>();
        for (Map.Entry<String, byte[]> answer : answers.entrySet()) {
            HostResolver resolver = host -> {
                calls.incrementAndGet();
                return DnsAnswer.of(DnsAnswer.FamilyAnswer.addresses(List.of(answer.getValue())), DnsAnswer.FamilyAnswer.nodata());
            };
            // The same accepted identity, evaluated as a destination, is approved or refused by DNS and addresses alone.
            DestinationDecision decision = DestinationPolicy.using(resolver).evaluate(site.canonicalForm());
            if (decision instanceof DestinationDecision.Rejected refused) {
                refusals.put(answer.getKey(), refused.code());
            }
        }
        assertEquals(4, calls.get());
        assertEquals(Map.of("loopback", RejectionCode.ADDRESS_FORBIDDEN, "private", RejectionCode.ADDRESS_FORBIDDEN,
                "metadata", RejectionCode.ADDRESS_FORBIDDEN), refusals);
        HostResolver failing = host -> {
            calls.incrementAndGet();
            throw new IllegalStateException("resolver down");
        };
        DestinationDecision unresolved = DestinationPolicy.using(failing).evaluate(site.canonicalForm());
        assertEquals(RejectionCode.DNS_FAILURE, ((DestinationDecision.Rejected) unresolved).code());
        // Obtaining the identity itself needs no resolver at all, before or after those evaluations.
        int before = calls.get();
        assertEquals(site, accepted("HTTPS://EXAMPLE.COM:443/"));
        assertEquals(before, calls.get());
    }
}
