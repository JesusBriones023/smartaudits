package com.smartaudits.v2.acquisition.destination;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integrity of the frozen catalog (ADR-0002 sections 12 and 19): the serialization rebuilt from the
 * Java constants reproduces the approved SHA-256, and the entries are complete, canonical, unique
 * and ordered. Expectations are literal values from the approved snapshot, never derived from the
 * production code. Classification is tested separately (AddressPolicyClassificationTest).
 */
class AddressCatalogIntegrityTest {
    private static final String FROZEN_SHA256 = "75987A6CB1B520F172B454E7E1D8646ECDA08A95908B7C4D392ADFFC9CDF6F6D";
    private static final String ADR_POLICY = "ADR-0002 sha256:E77382356F026F946E855B3FDDDA10006ED06A7DA52CA2D57DCB99AB9146C717";
    private static final String BS = String.valueOf((char) 92);
    private static final String T = String.valueOf((char) 9);
    private static final AddressCatalog CATALOG = AddressCatalog.snapshot();

    @Test void serializationRebuiltFromJavaConstantsHasTheFrozenSha256() throws Exception {
        byte[] bytes = CatalogSerialization.canonicalBytes(CATALOG);
        String digest = HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        assertEquals(FROZEN_SHA256, digest);
        assertEquals(FROZEN_SHA256, CatalogSerialization.sha256(CATALOG));
        assertEquals(FROZEN_SHA256, AddressCatalog.CANONICAL_SHA256);
        assertEquals(FROZEN_SHA256, CATALOG.canonicalSha256());
        assertEquals(55420, bytes.length);
    }

    @Test void serializationIsDeterministicUtf8LfWithOneFinalNewline() {
        String first = CatalogSerialization.serialize(CATALOG);
        assertEquals(first, CatalogSerialization.serialize(CATALOG));
        assertEquals(first, CatalogSerialization.serialize(AddressCatalog.snapshot()));
        assertTrue(first.endsWith("\n"));
        assertFalse(first.endsWith("\n\n"));
        assertFalse(first.contains("\r"));
        assertTrue(first.chars().allMatch(c -> c >= 9 && c < 127));
        assertEquals(371, first.split("\n", -1).length - 1);
    }

    @Test void headerLinesAreExact() {
        List<String> lines = List.of(CatalogSerialization.serialize(CATALOG).split("\n"));
        assertEquals("#format" + T + "smartaudits-destination-catalog-candidate/1", lines.get(0));
        assertEquals("#file" + T + "catalog.tsv", lines.get(1));
        assertEquals("#policy" + T + ADR_POLICY, lines.get(2));
        assertEquals("#source" + T + "IANA-IPV4-SPACE" + T + "updated=2025-10-10" + T
                + "sha256=013BF87DF7B24401AB6143BCAD83BD61EEAF0F9C976D2CF1AEDCDD5795865222" + T
                + "https://www.iana.org/assignments/ipv4-address-space/ipv4-address-space.xml", lines.get(3));
        assertEquals("#source" + T + "IANA-IPV4-SPECIAL" + T + "updated=2025-10-09" + T
                + "sha256=37F334466C842387A8AEE8D1AE7A6D5B2208EF27EB8964072538B73EE2F5B21D" + T
                + "https://www.iana.org/assignments/iana-ipv4-special-registry/iana-ipv4-special-registry.xml", lines.get(4));
        assertEquals("#source" + T + "IANA-IPV6-SPECIAL" + T + "updated=2025-10-09" + T
                + "sha256=660ACF0201B462B8D820605BA1CB20C4ED216ACD883EE4A55A17453D73D4C3AA" + T
                + "https://www.iana.org/assignments/iana-ipv6-special-registry/iana-ipv6-special-registry.xml", lines.get(5));
        assertEquals("#source" + T + "IANA-IPV6-UNICAST" + T + "updated=2025-10-10" + T
                + "sha256=22F9A545E020EA6B9ADEA9EA0276F3157E32C838682459A45BFE7760DE0AEFC3" + T
                + "https://www.iana.org/assignments/ipv6-unicast-address-assignments/ipv6-unicast-address-assignments.xml",
                lines.get(6));
        assertEquals("#source" + T + "IANA-SUDN" + T + "updated=2026-05-22" + T
                + "sha256=2659DEC6FDB0377BA1F12D7418EB34E38B312E5F739574AEB868E00E1BCDF1E4" + T
                + "https://www.iana.org/assignments/special-use-domain-names/special-use-domain-names.xml", lines.get(7));
        assertEquals("#columns" + T + "kind" + T + "layer" + T + "family" + T + "network" + T + "prefix_len" + T
                + "network_hex" + T + "origin" + T + "source_id" + T + "record_ref" + T + "label" + T + "attrs", lines.get(8));
    }

    @Test void firstLastAndDoublyEscapedRowsAreExact() {
        List<String> lines = List.of(CatalogSerialization.serialize(CATALOG).split("\n"));
        assertEquals("ADDR" + T + "NEGATIVE" + T + "4" + T + "0.0.0.0" + T + "8" + T + "00000000" + T + "IANA" + T
                + "IANA-IPV4-SPECIAL" + T + "iana-ipv4-special-registry-1#1" + T + "\"This network\"" + T
                + "destination=False;forwardable=False;global=False;raw=0.0.0.0/8;reserved=True;source=True;spec=rfc791",
                lines.get(9));
        assertEquals("NAME" + T + "NEGATIVE" + T + "-" + T + "test" + T + "-" + T + "-" + T + "IANA" + T + "IANA-SUDN" + T
                + "special-use-domain#42" + T + "test." + T
                + "classification=ADR-0002 s11 forbidden-names table;raw=test.;spec=rfc6761", lines.get(370));
        List<String> escaped = lines.stream().filter(l -> l.contains(BS)).collect(Collectors.toList());
        assertEquals(List.of(
                "ADDR" + T + "NEGATIVE" + T + "4" + T + "224.0.0.0" + T + "4" + T + "e0000000" + T + "PRODUCT" + T
                        + "ADR-0002" + T + "product-addr#1 ADR-0002 s11 minimo IPv4" + T + "Multicast" + T
                        + "note=Not in IPv4 special-purpose registry" + BS + BS
                        + "; IPv4 Address Space lists 224-239/8 as RESERVED (Multicast);policy=" + ADR_POLICY
                        + ";review=pending-astra",
                "ADDR" + T + "NEGATIVE" + T + "6" + T + "::" + T + "96" + T + "00000000000000000000000000000000" + T
                        + "PRODUCT" + T + "ADR-0002" + T + "product-addr#3 ADR-0002 s11 minimo IPv6" + T
                        + "IPv4-compatible (deprecated)" + T + "note=Product rule" + BS + BS
                        + "; not in IPv6 special-purpose registry;policy=" + ADR_POLICY + ";review=pending-astra"),
                escaped);
        String all = CatalogSerialization.serialize(CATALOG);
        assertEquals(4, all.chars().filter(c -> c == 92).count());
    }

    /** Normative examples of SERIALIZATION.md (EX01-EX17, PF01-PF05), checked by their exact hex bytes. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "EX01|6e6f74653d4d756c746963617374|6e6f7465|4d756c746963617374",
            "EX02|6e6f74653d615c5c3b62|6e6f7465|613b62",
            "EX03|6e6f74653d785c5c3d31|6e6f7465|783d31",
            "EX04|6e6f74653d433a5c5c5c5c64|6e6f7465|433a5c64",
            "EX05|6e6f74653d615c5c7462|6e6f7465|610962",
            "EX06|6e6f74653d615c5c6e62|6e6f7465|610a62",
            "EX07|6e6f74653d615c5c7262|6e6f7465|610d62",
            "EX08|6e6f74653d5c5c5c5c5c5c3b|6e6f7465|5c3b",
            "EX09|6e6f74653d5c5c5c5c74|6e6f7465|5c74",
            "EX10|6e6f74653d5c5c3b5c5c3d|6e6f7465|3b3d",
            "EX11|6e6f74653d5c5c3b5c5c3b|6e6f7465|3b3b",
            "EX12|6e6f74653d|6e6f7465|",
            "EX13|6e6f74653d5c5c725c5c6e5c5c5c5c5c5c3d|6e6f7465|0d0a5c3d",
    })
    void singleAttributeNormativeExamples(String id, String expectedHex, String keyHex, String valueHex) {
        Map<String, String> attributes = Map.of(utf8(keyHex), utf8(valueHex == null ? "" : valueHex));
        String field = CatalogSerialization.escapeField(CatalogSerialization.encodeAttributes(attributes));
        assertEquals(expectedHex, HexFormat.of().formatHex(field.getBytes(StandardCharsets.UTF_8)), id);
    }

    @Test void multiAttributeEmptyAndRealNormativeExamples() {
        Map<String, String> ex14 = new LinkedHashMap<>();
        ex14.put("b", "x");
        ex14.put("a", BS);
        assertEquals("613d5c5c5c5c3b623d78", fieldHex(ex14));
        Map<String, String> ex15 = new TreeMap<>(Map.of("note", "a;b", "review", "x=y"));
        assertEquals("6e6f74653d615c5c3b623b7265766965773d785c5c3d79", fieldHex(ex15));
        assertEquals("2d", fieldHex(Map.of()));
        assertEquals("6e6f74653d4e6f7420696e2049507634207370656369616c2d707572706f73652072656769737472795c5c3b20495076342041646472657373205370616365206c69737473203232342d3233392f3820617320524553455256454420284d756c74696361737429",
                fieldHex(Map.of("note", "Not in IPv4 special-purpose registry; IPv4 Address Space lists 224-239/8 as RESERVED (Multicast)")));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "PF01|613b623d63|613b623d63",
            "PF02|615c5c62|615c62",
            "PF03|615c74625c6e635c72|6109620a630d",
            "PF04|5c5c74|5c74",
            "PF05|2d|2d",
    })
    void singleLevelFieldNormativeExamples(String id, String expectedHex, String valueHex) {
        String field = CatalogSerialization.escapeField(utf8(valueHex));
        assertEquals(expectedHex, HexFormat.of().formatHex(field.getBytes(StandardCharsets.UTF_8)), id);
    }

    @Test void entryCountsBySourceKindLayerAndOriginMatchTheSnapshot() {
        List<CatalogEntry> entries = CATALOG.entries();
        assertEquals(362, entries.size());
        Map<String, Long> counts = entries.stream().collect(Collectors.groupingBy(
                e -> e.kind() + "/" + e.layer() + "/" + e.origin(), TreeMap::new, Collectors.counting()));
        assertEquals(Map.of("ADDR/POSITIVE/IANA", 255L, "ADDR/NEGATIVE/IANA", 51L, "ADDR/NEGATIVE/PRODUCT", 5L,
                "NAME/NEGATIVE/IANA", 39L, "NAME/NEGATIVE/PRODUCT", 9L, "NAME/NOT_FORBIDDEN/IANA", 3L), counts);
        assertEquals(221, CATALOG.positivePrefixes().stream().filter(p -> p.family() == IpFamily.IPV4).count());
        assertEquals(34, CATALOG.positivePrefixes().stream().filter(p -> p.family() == IpFamily.IPV6).count());
        assertEquals(28, CATALOG.negativePrefixes().stream().filter(p -> p.family() == IpFamily.IPV4).count());
        assertEquals(28, CATALOG.negativePrefixes().stream().filter(p -> p.family() == IpFamily.IPV6).count());
        assertEquals(List.of("IANA-IPV4-SPACE", "IANA-IPV4-SPECIAL", "IANA-IPV6-SPECIAL", "IANA-IPV6-UNICAST", "IANA-SUDN"),
                CATALOG.sources().stream().map(AddressCatalog.Source::id).collect(Collectors.toList()));
        assertEquals("IANA-SNAPSHOT-02", CATALOG.revision());
        assertEquals("IANA-SNAPSHOT-02", AddressCatalog.REVISION);
        assertEquals("2026-10-21", AddressCatalog.FROZEN_ON);
        assertEquals("20261021T135838Z-a273b428", AddressCatalog.SNAPSHOT_RUN);
    }

    @Test void everyAddressEntryIsCanonicalAndConsistentWithItsFrozenText() {
        for (CatalogEntry entry : CATALOG.entries()) {
            if (entry.kind() != CatalogEntry.Kind.ADDR) {
                assertTrue(entry.prefix().isEmpty());
                assertEquals(entry.network(), entry.name().orElseThrow());
                continue;
            }
            IpPrefix prefix = entry.prefix().orElseThrow();
            byte[] network = prefix.networkBytes();
            List<String> fields = entry.scalarFields();
            int bits = network.length * 8;
            assertEquals(prefix.family() == IpFamily.IPV4 ? "4" : "6", fields.get(2), entry.recordRef());
            assertEquals(Integer.toString(prefix.length()), fields.get(4), entry.recordRef());
            assertTrue(prefix.length() >= 0 && prefix.length() <= bits, entry.recordRef());
            assertEquals(HexFormat.of().formatHex(network), fields.get(5), entry.recordRef());
            BigInteger value = new BigInteger(1, network);
            BigInteger hostMask = BigInteger.ONE.shiftLeft(bits - prefix.length()).subtract(BigInteger.ONE);
            assertEquals(BigInteger.ZERO, value.and(hostMask), "host bits set: " + entry.recordRef());
            assertEquals(TestAddresses.canonicalText(network), entry.network(), entry.recordRef());
            assertTrue(entry.name().isEmpty());
        }
    }

    @Test void noDuplicatePrefixWithinALayerAndNoDuplicateNameWithinALayer() {
        Set<String> seen = new HashSet<>();
        for (CatalogEntry e : CATALOG.entries()) {
            String key = e.layer() + " " + e.kind() + " " + e.network() + "/" + e.prefix().map(IpPrefix::length).orElse(-1);
            assertTrue(seen.add(key), "duplicate " + key);
        }
        assertEquals(255, new HashSet<>(CATALOG.positivePrefixes()).size());
        assertEquals(56, new HashSet<>(CATALOG.negativePrefixes()).size());
    }

    @Test void entriesFollowTheDocumentedTotalOrder() {
        Comparator<CatalogEntry> order = Comparator
                .comparingInt((CatalogEntry e) -> e.kind() == CatalogEntry.Kind.ADDR ? 0 : 1)
                .thenComparingInt(e -> e.prefix().map(p -> p.family() == IpFamily.IPV4 ? 4 : 6).orElse(0))
                .thenComparing(e -> e.prefix().map(IpPrefix::networkBytes).orElse(new byte[0]), AddressCatalogIntegrityTest::unsigned)
                .thenComparingInt(e -> e.prefix().map(IpPrefix::length).orElse(0))
                .thenComparing(e -> e.kind() == CatalogEntry.Kind.NAME ? e.network() : "")
                .thenComparing(e -> e.layer().name())
                .thenComparing(e -> e.origin().name())
                .thenComparing(CatalogEntry::sourceId)
                .thenComparing(CatalogEntry::recordRef);
        List<CatalogEntry> entries = CATALOG.entries();
        for (int i = 1; i < entries.size(); i++) {
            assertTrue(order.compare(entries.get(i - 1), entries.get(i)) < 0, "order broken at entry " + i);
        }
    }

    @ParameterizedTest(name = "{0}/{1}")
    @CsvSource({
            "0.0.0.0,8", "10.0.0.0,8", "100.64.0.0,10", "127.0.0.0,8", "169.254.0.0,16", "172.16.0.0,12",
            "192.0.0.0,24", "192.0.2.0,24", "192.168.0.0,16", "198.18.0.0,15", "198.51.100.0,24", "203.0.113.0,24",
            "224.0.0.0,4", "240.0.0.0,4", "255.255.255.255,32", "168.63.129.16,32",
            "::,128", "::1,128", "::,96", "::ffff:0.0.0.0,96", "64:ff9b::,96", "64:ff9b:1::,48", "100::,64",
            "2001::,23", "2001:db8::,32", "2002::,16", "3fff::,20", "fc00::,7", "fe80::,10", "fec0::,10", "ff00::,8",
    })
    void everyAdrMandatoryPrefixIsANegativeEntry(String network, int length) {
        assertTrue(CATALOG.entries().stream().anyMatch(e -> e.kind() == CatalogEntry.Kind.ADDR
                && e.layer() == CatalogEntry.Layer.NEGATIVE && e.network().equals(network)
                && e.prefix().orElseThrow().length() == length), network + "/" + length);
    }

    @ParameterizedTest(name = "{0}/{1}")
    @CsvSource({
            "192.31.196.0,24,AS112-v4", "192.52.193.0,24,AMT", "192.88.99.0,24,Deprecated (6to4 Relay Anycast)",
            "192.175.48.0,24,Direct Delegation AS112 Service", "2620:4f:8000::,48,Direct Delegation AS112 Service",
            "5f00::,16,Segment Routing (SRv6) SIDs", "100:0:0:1::,64,Dummy IPv6 Prefix",
    })
    void theSevenDocumentallyConfirmedEntriesArePresentFromIana(String network, int length, String label) {
        List<CatalogEntry> hits = CATALOG.entries().stream().filter(e -> e.kind() == CatalogEntry.Kind.ADDR
                && e.network().equals(network) && e.prefix().orElseThrow().length() == length).collect(Collectors.toList());
        assertEquals(1, hits.size(), network);
        assertEquals(CatalogEntry.Layer.NEGATIVE, hits.get(0).layer());
        assertEquals(CatalogEntry.Origin.IANA, hits.get(0).origin());
        assertEquals(label, hits.get(0).label());
    }

    @Test void productRulesCarryPolicyAndPendingReview() {
        List<CatalogEntry> product = CATALOG.entries().stream()
                .filter(e -> e.origin() == CatalogEntry.Origin.PRODUCT).collect(Collectors.toList());
        assertEquals(List.of("168.63.129.16/32", "224.0.0.0/4", "::/96", "fec0::/10", "ff00::/8"),
                product.stream().filter(e -> e.kind() == CatalogEntry.Kind.ADDR)
                        .map(e -> e.network() + "/" + e.prefix().orElseThrow().length()).sorted().collect(Collectors.toList()));
        assertEquals(List.of("arpa", "corp", "home", "internal", "intranet", "lan", "localdomain", "mail", "private"),
                product.stream().filter(e -> e.kind() == CatalogEntry.Kind.NAME).map(CatalogEntry::network).sorted()
                        .collect(Collectors.toList()));
        for (CatalogEntry e : product) {
            assertEquals("ADR-0002", e.sourceId());
            assertEquals(ADR_POLICY, e.attributes().get("policy"));
            assertEquals("pending-astra", e.attributes().get("review"));
        }
    }

    @Test void ianaEntriesKeepTheirSourceRecordAndRawText() {
        for (CatalogEntry e : CATALOG.entries()) {
            if (e.origin() == CatalogEntry.Origin.IANA) {
                assertTrue(e.attributes().containsKey("raw"), e.recordRef());
                assertTrue(e.recordRef().matches("[a-z0-9-]+#[1-9][0-9]*"), e.recordRef());
                assertTrue(Set.of("IANA-IPV4-SPACE", "IANA-IPV4-SPECIAL", "IANA-IPV6-SPECIAL", "IANA-IPV6-UNICAST",
                        "IANA-SUDN").contains(e.sourceId()), e.recordRef());
            }
        }
        CatalogEntry deprecatedName = CATALOG.entries().stream().filter(e -> e.network().equals("eap-noob.arpa"))
                .findFirst().orElseThrow();
        assertEquals("DEPRECATED", deprecatedName.attributes().get("status"));
        assertEquals(CatalogEntry.Layer.NEGATIVE, deprecatedName.layer());
        assertEquals(List.of("example.com", "example.net", "example.org"), CATALOG.entries().stream()
                .filter(e -> e.layer() == CatalogEntry.Layer.NOT_FORBIDDEN).map(CatalogEntry::network).collect(Collectors.toList()));
    }

    @Test void catalogCollectionsAndArraysAreImmutable() {
        assertThrows(UnsupportedOperationException.class, () -> CATALOG.entries().add(CATALOG.entries().get(0)));
        assertThrows(UnsupportedOperationException.class, () -> CATALOG.entries().clear());
        assertThrows(UnsupportedOperationException.class, () -> CATALOG.positivePrefixes().remove(0));
        assertThrows(UnsupportedOperationException.class, () -> CATALOG.negativePrefixes().remove(0));
        assertThrows(UnsupportedOperationException.class, () -> CATALOG.forbiddenNames().add("example.com"));
        assertThrows(UnsupportedOperationException.class, () -> CATALOG.sources().remove(0));
        assertThrows(UnsupportedOperationException.class, () -> CATALOG.entries().get(0).attributes().put("raw", "x"));
        IpPrefix prefix = CATALOG.negativePrefixes().get(0);
        byte[] network = prefix.networkBytes();
        network[0] = (byte) 0x7f;
        assertEquals(0, prefix.networkBytes()[0]);
        assertEquals(FROZEN_SHA256, CatalogSerialization.sha256(CATALOG));
    }

    @Test void factoryRejectsMalformedEntries() {
        assertDoesNotThrow(() -> CatalogEntry.fromFields("ADDR", "NEGATIVE", "4", "10.0.0.0", "8", "0a000000", "IANA",
                "IANA-IPV4-SPECIAL", "r#1", "L", "raw", "x"));
        assertThrows(IllegalArgumentException.class, () -> CatalogEntry.fromFields("ADDR", "NEGATIVE", "4", "10.0.0.1",
                "8", "0a000001", "IANA", "IANA-IPV4-SPECIAL", "r#1", "L"));
        assertThrows(IllegalArgumentException.class, () -> CatalogEntry.fromFields("ADDR", "NEGATIVE", "4", "10.0.0.0",
                "33", "0a000000", "IANA", "IANA-IPV4-SPECIAL", "r#1", "L"));
        assertThrows(IllegalArgumentException.class, () -> CatalogEntry.fromFields("ADDR", "NEGATIVE", "4", "10.0.0.0",
                "08", "0a000000", "IANA", "IANA-IPV4-SPECIAL", "r#1", "L"));
        assertThrows(IllegalArgumentException.class, () -> CatalogEntry.fromFields("ADDR", "NEGATIVE", "4", "10.0.0.0",
                "8", "0A000000", "IANA", "IANA-IPV4-SPECIAL", "r#1", "L"));
        assertThrows(IllegalArgumentException.class, () -> CatalogEntry.fromFields("ADDR", "NOT_FORBIDDEN", "4",
                "10.0.0.0", "8", "0a000000", "IANA", "IANA-IPV4-SPECIAL", "r#1", "L"));
        assertThrows(IllegalArgumentException.class, () -> CatalogEntry.fromFields("NAME", "POSITIVE", "-", "x", "-",
                "-", "IANA", "IANA-SUDN", "r#1", "L"));
        assertThrows(IllegalArgumentException.class, () -> CatalogEntry.fromFields("NAME", "NEGATIVE", "-", "Bad.Name",
                "-", "-", "IANA", "IANA-SUDN", "r#1", "L"));
        assertThrows(IllegalArgumentException.class, () -> CatalogEntry.fromFields("ADDR", "NEGATIVE", "4", "10.0.0.0",
                "8", "0a000000", "PRODUCT", "IANA-IPV4-SPECIAL", "r#1", "L"));
        assertThrows(IllegalArgumentException.class, () -> CatalogEntry.fromFields("ADDR", "NEGATIVE", "4", "10.0.0.0",
                "8", "0a000000", "IANA", "IANA-IPV4-SPECIAL", "r#1", "L", "b", "1", "a", "2"));
        assertThrows(IllegalArgumentException.class, () -> CatalogEntry.fromFields("ADDR", "NEGATIVE", "4", "10.0.0.0",
                "8", "0a000000", "IANA", "IANA-IPV4-SPECIAL", "r#1", "L", "a"));
        assertThrows(NullPointerException.class, () -> CatalogEntry.fromFields(null, "NEGATIVE", "4", "10.0.0.0", "8",
                "0a000000", "IANA", "IANA-IPV4-SPECIAL", "r#1", "L"));
    }

    private static String fieldHex(Map<String, String> attributes) {
        String field = CatalogSerialization.escapeField(CatalogSerialization.encodeAttributes(attributes));
        return HexFormat.of().formatHex(field.getBytes(StandardCharsets.UTF_8));
    }

    private static String utf8(String hex) {
        return new String(HexFormat.of().parseHex(hex), StandardCharsets.UTF_8);
    }

    private static int unsigned(byte[] a, byte[] b) {
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            int c = Integer.compare(a[i] & 0xFF, b[i] & 0xFF);
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a.length, b.length);
    }
}
