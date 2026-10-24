package com.smartaudits.v2.acquisition.destination;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static com.smartaudits.v2.acquisition.destination.FakeResolver.*;
import static com.smartaudits.v2.acquisition.destination.RejectionCode.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Bypass attempts against the destination policy (ADR-0002 sections 10, 11, 13, 15 and 19): hidden
 * forbidden addresses, embedded IPv4 forms, special ranges, aliasing and mutation of answer data,
 * approvals without the policy, leaking diagnostics and forbidden APIs.
 */
class DestinationPolicyAdversarialTest {
    static final String URL = "https://www.example.com/";
    static final byte[] P4A = v4("8.8.8.8");
    static final byte[] P6 = v6("2a00:1450::1");
    static final String SECRET = "S3CR3T-T0K3N";

    static RejectionCode code(DnsAnswer answer) {
        DestinationDecision decision = DestinationPolicy.using(FakeResolver.answering(answer)).evaluate(URL);
        assertTrue(decision instanceof DestinationDecision.Rejected, "expected a rejection, got " + decision);
        return ((DestinationDecision.Rejected) decision).code();
    }

    @Test void aForbiddenAddressAnywhereInALongListRejectsTheWholeAnswer() {
        byte[][] last = repeat(P4A, 20);
        last[19] = v4("127.0.0.1");
        assertEquals(ADDRESS_MIXED, code(answer(addresses(last), nodata())));
        byte[][] first = repeat(P4A, 20);
        first[0] = v4("192.168.1.1");
        assertEquals(ADDRESS_MIXED, code(answer(addresses(first), nodata())));
        byte[][] alternating = new byte[16][];
        for (int i = 0; i < 16; i++) {
            alternating[i] = i % 2 == 0 ? v4("8.8." + i + ".8") : v4("10.0.0." + i);
        }
        assertEquals(ADDRESS_MIXED, code(answer(addresses(alternating), nodata())));
        assertEquals(ADDRESS_MIXED, code(answer(addresses(P4A), addresses(P6, P6, v6("fe80::1")))));
        assertEquals(ADDRESS_MIXED, code(answer(addresses(v4("1.1.1.1"), v4("8.8.4.4")), addresses(P6, v6("fc00::1")))));
    }

    /** Every address below is forbidden by the frozen catalog; alone it gives ADDRESS_FORBIDDEN, next to P4a ADDRESS_MIXED. */
    @ParameterizedTest
    @ValueSource(strings = {"::ffff:808:808", "::808:808", "64:ff9b::808:808", "64:ff9b:1::1", "2002:808:808::1",
            "2001:0:808:808::1", "2001:db8::1", "3fff::1", "::1", "::", "ff02::1", "fe80::1", "fec0::1", "fc00::1",
            "fd00:ec2::254", "100::1", "5f00::1", "2620:4f:8000::1", "3000::1", "4000::1"})
    void specialAndUnallocatedIpv6AreNeverAdmissible(String text) {
        assertEquals(ADDRESS_FORBIDDEN, code(answer(nodata(), addresses(v6(text)))));
        assertEquals(ADDRESS_MIXED, code(answer(addresses(P4A), addresses(v6(text)))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.0.1", "100.64.0.1", "100.100.100.200",
            "169.254.169.254", "168.63.129.16", "192.0.2.1", "198.51.100.1", "203.0.113.1", "198.18.0.1", "192.0.0.9",
            "192.88.99.1", "224.0.0.1", "239.255.255.255", "240.0.0.1", "255.255.255.255", "0.0.0.0"})
    void specialIpv4AreNeverAdmissible(String text) {
        assertEquals(ADDRESS_FORBIDDEN, code(answer(addresses(v4(text)), nodata())));
        assertEquals(ADDRESS_MIXED, code(answer(addresses(P4A, v4(text)), addresses(P6))));
    }

    @Test void swappedFamiliesAreInvalidNotUnwrapped() {
        assertEquals(DNS_INVALID, code(answer(addresses(v6("::ffff:808:808")), nodata())));
        assertEquals(DNS_INVALID, code(answer(nodata(), addresses(P4A))));
        assertEquals(DNS_INVALID, code(answer(addresses(P6), addresses(P4A))));
    }

    @Test void mutatingResolverDataAfterBuildingTheAnswerChangesNothing() {
        byte[] record = v4("8.8.8.8");
        List<byte[]> records = new ArrayList<>(List.of(record));
        List<String> cnames = new ArrayList<>(List.of("b.example.com"));
        DnsAnswer answer = answer(DnsAnswer.FamilyAnswer.of(DnsAnswer.Status.ADDRESSES, cnames, records),
                nodata(List.of("b.example.com")));
        record[0] = 127;
        records.add(v4("10.0.0.1"));
        cnames.set(0, "db.internal");
        DestinationDecision decision = DestinationPolicy.using(FakeResolver.answering(answer)).evaluate(URL);
        DestinationDecision.Approved approved = (DestinationDecision.Approved) decision;
        assertEquals(List.of(IpAddress.ipv4(v4("8.8.8.8"))), approved.addresses());
        byte[] exposed = answer.a().records().get(0);
        exposed[0] = 10;
        assertArrayEquals(v4("8.8.8.8"), answer.a().records().get(0));
        assertThrows(UnsupportedOperationException.class, () -> answer.a().records().add(new byte[4]));
        assertThrows(UnsupportedOperationException.class, () -> answer.a().cnameChain().add("x.example.com"));
        assertThrows(UnsupportedOperationException.class, () -> approved.addresses().add(IpAddress.ipv4(P4A)));
    }

    @Test void aResolverMutatingItsArraysAfterReturningChangesNothing() {
        byte[] shared = v4("8.8.8.8");
        HostResolver sneaky = new HostResolver() {
            @Override
            public DnsAnswer resolve(ValidatedHost host) {
                DnsAnswer answer = answer(addresses(shared), nodata());
                shared[0] = 10;
                return answer;
            }
        };
        assertTrue(DestinationPolicy.using(sneaky).evaluate(URL) instanceof DestinationDecision.Approved);
    }

    @Test void reusingTheSameAnswerObjectIsDeterministic() {
        DnsAnswer mixed = answer(addresses(P4A, v4("10.0.0.1")), nodata());
        DnsAnswer good = answer(addresses(P4A), addresses(P6));
        FakeResolver resolver = FakeResolver.answering(mixed, good, mixed, good);
        DestinationPolicy policy = DestinationPolicy.using(resolver);
        for (int i = 0; i < 2; i++) {
            assertEquals(ADDRESS_MIXED, ((DestinationDecision.Rejected) policy.evaluate(URL)).code());
            assertEquals(List.of(IpAddress.ipv4(P4A), IpAddress.ipv6(P6)),
                    ((DestinationDecision.Approved) policy.evaluate(URL)).addresses());
        }
        assertEquals(4, resolver.calls());
    }

    @Test void answersWithNullsAreBuiltWithoutException() {
        assertDoesNotThrow(() -> DnsAnswer.of(null, null));
        assertDoesNotThrow(() -> DnsAnswer.FamilyAnswer.of(null, null, null));
        DnsAnswer.FamilyAnswer withNulls = DnsAnswer.FamilyAnswer.of(DnsAnswer.Status.ADDRESSES,
                Arrays.asList(null, "b.example.com"), Arrays.asList(null, P4A, new byte[7]));
        assertEquals(Arrays.asList(null, "b.example.com"), withNulls.cnameChain());
        assertNull(withNulls.records().get(0));
        assertEquals(7, withNulls.records().get(2).length);
        assertEquals(DNS_INVALID, code(DnsAnswer.of(withNulls, nodata())));
        assertNull(DnsAnswer.FamilyAnswer.of(DnsAnswer.Status.NODATA, null, null).records());
    }

    @Test void approvalsCannotBeBuiltOutsideThePolicy() {
        for (Class<?> type : List.of(DestinationDecision.Approved.class, DestinationDecision.Rejected.class,
                DestinationPolicy.class, DnsAnswer.class, DnsAnswer.FamilyAnswer.class)) {
            assertFalse(type.isRecord(), type.toString());
            assertFalse(Serializable.class.isAssignableFrom(type), type.toString());
            assertTrue(Modifier.isFinal(type.getModifiers()), type.toString());
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                assertFalse(Modifier.isPublic(constructor.getModifiers()) || Modifier.isProtected(constructor.getModifiers()),
                        type + " constructor");
            }
            for (Field field : type.getDeclaredFields()) {
                assertTrue(Modifier.isFinal(field.getModifiers()), type.getSimpleName() + "." + field.getName());
            }
        }
        assertTrue(DestinationDecision.class.isSealed());
        assertEquals(Set.of(DestinationDecision.Approved.class, DestinationDecision.Rejected.class),
                Set.of(DestinationDecision.class.getPermittedSubclasses()));
        List<Class<?>> packageTypes = List.of(AddressCatalog.class, AddressPolicy.class, CatalogEntry.class, DestinationUrl.class,
                DestinationPolicy.class, DnsAnswer.class, DnsAnswer.FamilyAnswer.class, IpAddress.class, IpPrefix.class,
                UrlParseResult.Parsed.class, UrlParseResult.Rejected.class, ValidatedHost.class,
                DestinationDecision.Approved.class, DestinationDecision.Rejected.class);
        for (Class<?> type : packageTypes) {
            for (Method method : type.getMethods()) {
                assertNotEquals(DestinationDecision.Approved.class, method.getReturnType(),
                        "public method returning an approval: " + type.getSimpleName() + "." + method.getName());
            }
        }
    }

    @Test void diagnosticsNeverRevealHostsAddressesOrResolverMessages() {
        FakeResolver throwing = FakeResolver.answering(new IllegalStateException(SECRET + " 10.0.0.1 db.internal"));
        String url = "https://" + SECRET.toLowerCase(java.util.Locale.ROOT) + ".example.com/p?token=" + SECRET + "#" + SECRET;
        List<String> texts = new ArrayList<>();
        texts.add(DestinationPolicy.using(throwing).evaluate(url).toString());
        DnsAnswer answer = answer(addresses(chain(SECRET.toLowerCase(java.util.Locale.ROOT) + ".cdn.example.net"), v4("8.8.8.8"), v4("169.254.169.254")),
                nodata(chain(SECRET.toLowerCase(java.util.Locale.ROOT) + ".cdn.example.net")));
        texts.add(answer.toString());
        texts.add(answer.a().toString());
        DestinationPolicy policy = DestinationPolicy.using(FakeResolver.answering(answer, answer(addresses(v4("8.8.8.8")), nodata())));
        texts.add(policy.evaluate(url).toString());
        DestinationDecision approved = policy.evaluate(url);
        texts.add(approved.toString());
        texts.add(policy.toString());
        for (String text : texts) {
            String upper = text.toUpperCase(java.util.Locale.ROOT);
            assertFalse(upper.contains(SECRET), text);
            assertFalse(text.contains("example") || text.contains("8.8.8.8") || text.contains("169.254") || text.contains("10.0.0.1"), text);
        }
        assertEquals("Rejected[DNS_FAILURE, IANA-SNAPSHOT-02]", texts.get(0));
        assertEquals("Rejected[ADDRESS_MIXED, IANA-SNAPSHOT-02]", texts.get(3));
        assertEquals("Approved[https:443, 1 addresses, IANA-SNAPSHOT-02]", texts.get(4));
        assertEquals("DnsAnswer[A=ADDRESSES, AAAA=NODATA]", texts.get(1));
    }

    @Test void newSourcesUseNoNetworkResolutionOrForbiddenApi() throws IOException {
        Path dir = Paths.get("src", "main", "java", "com", "smartaudits", "v2", "acquisition", "destination");
        List<String> forbidden = List.of("InetAddress", "Inet4Address", "Inet6Address", "Socket", "HttpClient",
                "URLConnection", "java.net.URL", "javax.naming", "InitialDirContext", "DnsContext", "dnsjava",
                "org.xbill", "getByName", "getAllByName", "java.io.", "java.nio.file", "Logger", "System.out",
                "System.err", "printStackTrace", "getMessage", "getCause", "initCause", "equalsIgnoreCase",
                "toLowerCase", "toUpperCase", "Executor", "CompletableFuture", "ConcurrentHashMap", "static Map",
                "Serializable", "Thread.sleep");
        List<String> violations = new ArrayList<>();
        for (String name : List.of("HostResolver.java", "DnsAnswer.java", "DestinationDecision.java", "DestinationPolicy.java")) {
            String code = DestinationPackageStaticTest.stripComments(Files.readString(dir.resolve(name), StandardCharsets.UTF_8));
            for (String token : forbidden) {
                if (code.contains(token)) {
                    violations.add(name + ": " + token);
                }
            }
        }
        assertEquals(List.of(), violations);
        String dnsAnswer = DestinationPackageStaticTest.stripComments(Files.readString(dir.resolve("DnsAnswer.java"), StandardCharsets.UTF_8));
        assertFalse(dnsAnswer.contains("List.copyOf"), "DnsAnswer must keep nulls: no List.copyOf on answer data");
    }

    static List<String> chain(String... names) {
        return Arrays.asList(names);
    }
}
