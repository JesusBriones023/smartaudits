package com.smartaudits.v2.acquisition.destination;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.stream.Stream;

import static com.smartaudits.v2.acquisition.destination.AddressCategory.ADMISSIBLE;
import static com.smartaudits.v2.acquisition.destination.AddressCategory.FORBIDDEN_SPECIAL_USE;
import static com.smartaudits.v2.acquisition.destination.AddressCategory.FORBIDDEN_UNALLOCATED;
import static com.smartaudits.v2.acquisition.destination.TestAddresses.ip;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Classification against the frozen two-layer catalog (ADR-0002 sections 11 and 19). Every expected
 * category is a literal: the boundary table was computed offline with an independent oracle (Python
 * ipaddress over the approved catalog.tsv), and the probes come from the approved snapshot review.
 */
class AddressPolicyClassificationTest {
    private static final AddressPolicy POLICY = new AddressPolicy(AddressCatalog.snapshot());

    /**
     * First and last address of every ADR-0002 mandatory prefix, of the seven confirmed entries and of
     * the 23 negatives contained in positive prefixes, plus the address just before and just after.
     * Columns: prefix, role, address text, address hex, expected category.
     */
    private static final String[][] BOUNDARIES = {
            {"0.0.0.0/8", "first", "0.0.0.0", "00000000", "SPECIAL"},
            {"0.0.0.0/8", "last", "0.255.255.255", "00ffffff", "SPECIAL"},
            {"0.0.0.0/8", "after", "1.0.0.0", "01000000", "ADMISSIBLE"},
            {"10.0.0.0/8", "first", "10.0.0.0", "0a000000", "SPECIAL"},
            {"10.0.0.0/8", "last", "10.255.255.255", "0affffff", "SPECIAL"},
            {"10.0.0.0/8", "before", "9.255.255.255", "09ffffff", "ADMISSIBLE"},
            {"10.0.0.0/8", "after", "11.0.0.0", "0b000000", "ADMISSIBLE"},
            {"100.64.0.0/10", "first", "100.64.0.0", "64400000", "SPECIAL"},
            {"100.64.0.0/10", "last", "100.127.255.255", "647fffff", "SPECIAL"},
            {"100.64.0.0/10", "before", "100.63.255.255", "643fffff", "ADMISSIBLE"},
            {"100.64.0.0/10", "after", "100.128.0.0", "64800000", "ADMISSIBLE"},
            {"127.0.0.0/8", "first", "127.0.0.0", "7f000000", "SPECIAL"},
            {"127.0.0.0/8", "last", "127.255.255.255", "7fffffff", "SPECIAL"},
            {"127.0.0.0/8", "before", "126.255.255.255", "7effffff", "ADMISSIBLE"},
            {"127.0.0.0/8", "after", "128.0.0.0", "80000000", "ADMISSIBLE"},
            {"169.254.0.0/16", "first", "169.254.0.0", "a9fe0000", "SPECIAL"},
            {"169.254.0.0/16", "last", "169.254.255.255", "a9feffff", "SPECIAL"},
            {"169.254.0.0/16", "before", "169.253.255.255", "a9fdffff", "ADMISSIBLE"},
            {"169.254.0.0/16", "after", "169.255.0.0", "a9ff0000", "ADMISSIBLE"},
            {"172.16.0.0/12", "first", "172.16.0.0", "ac100000", "SPECIAL"},
            {"172.16.0.0/12", "last", "172.31.255.255", "ac1fffff", "SPECIAL"},
            {"172.16.0.0/12", "before", "172.15.255.255", "ac0fffff", "ADMISSIBLE"},
            {"172.16.0.0/12", "after", "172.32.0.0", "ac200000", "ADMISSIBLE"},
            {"192.0.0.0/24", "first", "192.0.0.0", "c0000000", "SPECIAL"},
            {"192.0.0.0/24", "last", "192.0.0.255", "c00000ff", "SPECIAL"},
            {"192.0.0.0/24", "before", "191.255.255.255", "bfffffff", "ADMISSIBLE"},
            {"192.0.0.0/24", "after", "192.0.1.0", "c0000100", "ADMISSIBLE"},
            {"192.0.2.0/24", "first", "192.0.2.0", "c0000200", "SPECIAL"},
            {"192.0.2.0/24", "last", "192.0.2.255", "c00002ff", "SPECIAL"},
            {"192.0.2.0/24", "before", "192.0.1.255", "c00001ff", "ADMISSIBLE"},
            {"192.0.2.0/24", "after", "192.0.3.0", "c0000300", "ADMISSIBLE"},
            {"192.168.0.0/16", "first", "192.168.0.0", "c0a80000", "SPECIAL"},
            {"192.168.0.0/16", "last", "192.168.255.255", "c0a8ffff", "SPECIAL"},
            {"192.168.0.0/16", "before", "192.167.255.255", "c0a7ffff", "ADMISSIBLE"},
            {"192.168.0.0/16", "after", "192.169.0.0", "c0a90000", "ADMISSIBLE"},
            {"198.18.0.0/15", "first", "198.18.0.0", "c6120000", "SPECIAL"},
            {"198.18.0.0/15", "last", "198.19.255.255", "c613ffff", "SPECIAL"},
            {"198.18.0.0/15", "before", "198.17.255.255", "c611ffff", "ADMISSIBLE"},
            {"198.18.0.0/15", "after", "198.20.0.0", "c6140000", "ADMISSIBLE"},
            {"198.51.100.0/24", "first", "198.51.100.0", "c6336400", "SPECIAL"},
            {"198.51.100.0/24", "last", "198.51.100.255", "c63364ff", "SPECIAL"},
            {"198.51.100.0/24", "before", "198.51.99.255", "c63363ff", "ADMISSIBLE"},
            {"198.51.100.0/24", "after", "198.51.101.0", "c6336500", "ADMISSIBLE"},
            {"203.0.113.0/24", "first", "203.0.113.0", "cb007100", "SPECIAL"},
            {"203.0.113.0/24", "last", "203.0.113.255", "cb0071ff", "SPECIAL"},
            {"203.0.113.0/24", "before", "203.0.112.255", "cb0070ff", "ADMISSIBLE"},
            {"203.0.113.0/24", "after", "203.0.114.0", "cb007200", "ADMISSIBLE"},
            {"224.0.0.0/4", "first", "224.0.0.0", "e0000000", "SPECIAL"},
            {"224.0.0.0/4", "last", "239.255.255.255", "efffffff", "SPECIAL"},
            {"224.0.0.0/4", "before", "223.255.255.255", "dfffffff", "ADMISSIBLE"},
            {"224.0.0.0/4", "after", "240.0.0.0", "f0000000", "SPECIAL"},
            {"240.0.0.0/4", "first", "240.0.0.0", "f0000000", "SPECIAL"},
            {"240.0.0.0/4", "last", "255.255.255.255", "ffffffff", "SPECIAL"},
            {"240.0.0.0/4", "before", "239.255.255.255", "efffffff", "SPECIAL"},
            {"255.255.255.255/32", "first", "255.255.255.255", "ffffffff", "SPECIAL"},
            {"255.255.255.255/32", "last", "255.255.255.255", "ffffffff", "SPECIAL"},
            {"255.255.255.255/32", "before", "255.255.255.254", "fffffffe", "SPECIAL"},
            {"168.63.129.16/32", "first", "168.63.129.16", "a83f8110", "SPECIAL"},
            {"168.63.129.16/32", "last", "168.63.129.16", "a83f8110", "SPECIAL"},
            {"168.63.129.16/32", "before", "168.63.129.15", "a83f810f", "ADMISSIBLE"},
            {"168.63.129.16/32", "after", "168.63.129.17", "a83f8111", "ADMISSIBLE"},
            {"::/128", "first", "::", "00000000000000000000000000000000", "SPECIAL"},
            {"::/128", "last", "::", "00000000000000000000000000000000", "SPECIAL"},
            {"::/128", "after", "::1", "00000000000000000000000000000001", "SPECIAL"},
            {"::1/128", "first", "::1", "00000000000000000000000000000001", "SPECIAL"},
            {"::1/128", "last", "::1", "00000000000000000000000000000001", "SPECIAL"},
            {"::1/128", "before", "::", "00000000000000000000000000000000", "SPECIAL"},
            {"::1/128", "after", "::2", "00000000000000000000000000000002", "SPECIAL"},
            {"::/96", "first", "::", "00000000000000000000000000000000", "SPECIAL"},
            {"::/96", "last", "::ffff:ffff", "000000000000000000000000ffffffff", "SPECIAL"},
            {"::/96", "after", "::1:0:0", "00000000000000000000000100000000", "UNALLOCATED"},
            {"::ffff:0.0.0.0/96", "first", "::ffff:0.0.0.0", "00000000000000000000ffff00000000", "SPECIAL"},
            {"::ffff:0.0.0.0/96", "last", "::ffff:255.255.255.255", "00000000000000000000ffffffffffff", "SPECIAL"},
            {"::ffff:0.0.0.0/96", "before", "::fffe:ffff:ffff", "00000000000000000000fffeffffffff", "UNALLOCATED"},
            {"::ffff:0.0.0.0/96", "after", "::1:0:0:0", "00000000000000000001000000000000", "UNALLOCATED"},
            {"64:ff9b::/96", "first", "64:ff9b::", "0064ff9b000000000000000000000000", "SPECIAL"},
            {"64:ff9b::/96", "last", "64:ff9b::ffff:ffff", "0064ff9b0000000000000000ffffffff", "SPECIAL"},
            {"64:ff9b::/96", "before", "64:ff9a:ffff:ffff:ffff:ffff:ffff:ffff", "0064ff9affffffffffffffffffffffff", "UNALLOCATED"},
            {"64:ff9b::/96", "after", "64:ff9b::1:0:0", "0064ff9b000000000000000100000000", "UNALLOCATED"},
            {"64:ff9b:1::/48", "first", "64:ff9b:1::", "0064ff9b000100000000000000000000", "SPECIAL"},
            {"64:ff9b:1::/48", "last", "64:ff9b:1:ffff:ffff:ffff:ffff:ffff", "0064ff9b0001ffffffffffffffffffff", "SPECIAL"},
            {"64:ff9b:1::/48", "before", "64:ff9b:0:ffff:ffff:ffff:ffff:ffff", "0064ff9b0000ffffffffffffffffffff", "UNALLOCATED"},
            {"64:ff9b:1::/48", "after", "64:ff9b:2::", "0064ff9b000200000000000000000000", "UNALLOCATED"},
            {"100::/64", "first", "100::", "01000000000000000000000000000000", "SPECIAL"},
            {"100::/64", "last", "100::ffff:ffff:ffff:ffff", "0100000000000000ffffffffffffffff", "SPECIAL"},
            {"100::/64", "before", "ff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "00ffffffffffffffffffffffffffffff", "UNALLOCATED"},
            {"100::/64", "after", "100:0:0:1::", "01000000000000010000000000000000", "SPECIAL"},
            {"2001::/23", "first", "2001::", "20010000000000000000000000000000", "SPECIAL"},
            {"2001::/23", "last", "2001:1ff:ffff:ffff:ffff:ffff:ffff:ffff", "200101ffffffffffffffffffffffffff", "SPECIAL"},
            {"2001::/23", "before", "2000:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "2000ffffffffffffffffffffffffffff", "UNALLOCATED"},
            {"2001::/23", "after", "2001:200::", "20010200000000000000000000000000", "ADMISSIBLE"},
            {"2001:db8::/32", "first", "2001:db8::", "20010db8000000000000000000000000", "SPECIAL"},
            {"2001:db8::/32", "last", "2001:db8:ffff:ffff:ffff:ffff:ffff:ffff", "20010db8ffffffffffffffffffffffff", "SPECIAL"},
            {"2001:db8::/32", "before", "2001:db7:ffff:ffff:ffff:ffff:ffff:ffff", "20010db7ffffffffffffffffffffffff", "ADMISSIBLE"},
            {"2001:db8::/32", "after", "2001:db9::", "20010db9000000000000000000000000", "ADMISSIBLE"},
            {"2002::/16", "first", "2002::", "20020000000000000000000000000000", "SPECIAL"},
            {"2002::/16", "last", "2002:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "2002ffffffffffffffffffffffffffff", "SPECIAL"},
            {"2002::/16", "before", "2001:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "2001ffffffffffffffffffffffffffff", "UNALLOCATED"},
            {"2002::/16", "after", "2003::", "20030000000000000000000000000000", "ADMISSIBLE"},
            {"3fff::/20", "first", "3fff::", "3fff0000000000000000000000000000", "SPECIAL"},
            {"3fff::/20", "last", "3fff:fff:ffff:ffff:ffff:ffff:ffff:ffff", "3fff0fffffffffffffffffffffffffff", "SPECIAL"},
            {"3fff::/20", "before", "3ffe:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "3ffeffffffffffffffffffffffffffff", "UNALLOCATED"},
            {"3fff::/20", "after", "3fff:1000::", "3fff1000000000000000000000000000", "UNALLOCATED"},
            {"fc00::/7", "first", "fc00::", "fc000000000000000000000000000000", "SPECIAL"},
            {"fc00::/7", "last", "fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "fdffffffffffffffffffffffffffffff", "SPECIAL"},
            {"fc00::/7", "before", "fbff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "fbffffffffffffffffffffffffffffff", "UNALLOCATED"},
            {"fc00::/7", "after", "fe00::", "fe000000000000000000000000000000", "UNALLOCATED"},
            {"fe80::/10", "first", "fe80::", "fe800000000000000000000000000000", "SPECIAL"},
            {"fe80::/10", "last", "febf:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "febfffffffffffffffffffffffffffff", "SPECIAL"},
            {"fe80::/10", "before", "fe7f:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "fe7fffffffffffffffffffffffffffff", "UNALLOCATED"},
            {"fe80::/10", "after", "fec0::", "fec00000000000000000000000000000", "SPECIAL"},
            {"fec0::/10", "first", "fec0::", "fec00000000000000000000000000000", "SPECIAL"},
            {"fec0::/10", "last", "feff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "feffffffffffffffffffffffffffffff", "SPECIAL"},
            {"fec0::/10", "before", "febf:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "febfffffffffffffffffffffffffffff", "SPECIAL"},
            {"fec0::/10", "after", "ff00::", "ff000000000000000000000000000000", "SPECIAL"},
            {"ff00::/8", "first", "ff00::", "ff000000000000000000000000000000", "SPECIAL"},
            {"ff00::/8", "last", "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "ffffffffffffffffffffffffffffffff", "SPECIAL"},
            {"ff00::/8", "before", "feff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "feffffffffffffffffffffffffffffff", "SPECIAL"},
            {"192.31.196.0/24", "first", "192.31.196.0", "c01fc400", "SPECIAL"},
            {"192.31.196.0/24", "last", "192.31.196.255", "c01fc4ff", "SPECIAL"},
            {"192.31.196.0/24", "before", "192.31.195.255", "c01fc3ff", "ADMISSIBLE"},
            {"192.31.196.0/24", "after", "192.31.197.0", "c01fc500", "ADMISSIBLE"},
            {"192.52.193.0/24", "first", "192.52.193.0", "c034c100", "SPECIAL"},
            {"192.52.193.0/24", "last", "192.52.193.255", "c034c1ff", "SPECIAL"},
            {"192.52.193.0/24", "before", "192.52.192.255", "c034c0ff", "ADMISSIBLE"},
            {"192.52.193.0/24", "after", "192.52.194.0", "c034c200", "ADMISSIBLE"},
            {"192.88.99.0/24", "first", "192.88.99.0", "c0586300", "SPECIAL"},
            {"192.88.99.0/24", "last", "192.88.99.255", "c05863ff", "SPECIAL"},
            {"192.88.99.0/24", "before", "192.88.98.255", "c05862ff", "ADMISSIBLE"},
            {"192.88.99.0/24", "after", "192.88.100.0", "c0586400", "ADMISSIBLE"},
            {"192.175.48.0/24", "first", "192.175.48.0", "c0af3000", "SPECIAL"},
            {"192.175.48.0/24", "last", "192.175.48.255", "c0af30ff", "SPECIAL"},
            {"192.175.48.0/24", "before", "192.175.47.255", "c0af2fff", "ADMISSIBLE"},
            {"192.175.48.0/24", "after", "192.175.49.0", "c0af3100", "ADMISSIBLE"},
            {"2620:4f:8000::/48", "first", "2620:4f:8000::", "2620004f800000000000000000000000", "SPECIAL"},
            {"2620:4f:8000::/48", "last", "2620:4f:8000:ffff:ffff:ffff:ffff:ffff", "2620004f8000ffffffffffffffffffff", "SPECIAL"},
            {"2620:4f:8000::/48", "before", "2620:4f:7fff:ffff:ffff:ffff:ffff:ffff", "2620004f7fffffffffffffffffffffff", "ADMISSIBLE"},
            {"2620:4f:8000::/48", "after", "2620:4f:8001::", "2620004f800100000000000000000000", "ADMISSIBLE"},
            {"5f00::/16", "first", "5f00::", "5f000000000000000000000000000000", "SPECIAL"},
            {"5f00::/16", "last", "5f00:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "5f00ffffffffffffffffffffffffffff", "SPECIAL"},
            {"5f00::/16", "before", "5eff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "5effffffffffffffffffffffffffffff", "UNALLOCATED"},
            {"5f00::/16", "after", "5f01::", "5f010000000000000000000000000000", "UNALLOCATED"},
            {"100:0:0:1::/64", "first", "100:0:0:1::", "01000000000000010000000000000000", "SPECIAL"},
            {"100:0:0:1::/64", "last", "100::1:ffff:ffff:ffff:ffff", "0100000000000001ffffffffffffffff", "SPECIAL"},
            {"100:0:0:1::/64", "before", "100::ffff:ffff:ffff:ffff", "0100000000000000ffffffffffffffff", "SPECIAL"},
            {"100:0:0:1::/64", "after", "100:0:0:2::", "01000000000000020000000000000000", "UNALLOCATED"},
            {"192.0.0.0/29", "first", "192.0.0.0", "c0000000", "SPECIAL"},
            {"192.0.0.0/29", "last", "192.0.0.7", "c0000007", "SPECIAL"},
            {"192.0.0.0/29", "before", "191.255.255.255", "bfffffff", "ADMISSIBLE"},
            {"192.0.0.0/29", "after", "192.0.0.8", "c0000008", "SPECIAL"},
            {"192.0.0.8/32", "first", "192.0.0.8", "c0000008", "SPECIAL"},
            {"192.0.0.8/32", "last", "192.0.0.8", "c0000008", "SPECIAL"},
            {"192.0.0.8/32", "before", "192.0.0.7", "c0000007", "SPECIAL"},
            {"192.0.0.8/32", "after", "192.0.0.9", "c0000009", "SPECIAL"},
            {"192.0.0.9/32", "first", "192.0.0.9", "c0000009", "SPECIAL"},
            {"192.0.0.9/32", "last", "192.0.0.9", "c0000009", "SPECIAL"},
            {"192.0.0.9/32", "before", "192.0.0.8", "c0000008", "SPECIAL"},
            {"192.0.0.9/32", "after", "192.0.0.10", "c000000a", "SPECIAL"},
            {"192.0.0.10/32", "first", "192.0.0.10", "c000000a", "SPECIAL"},
            {"192.0.0.10/32", "last", "192.0.0.10", "c000000a", "SPECIAL"},
            {"192.0.0.10/32", "before", "192.0.0.9", "c0000009", "SPECIAL"},
            {"192.0.0.10/32", "after", "192.0.0.11", "c000000b", "SPECIAL"},
            {"192.0.0.170/32", "first", "192.0.0.170", "c00000aa", "SPECIAL"},
            {"192.0.0.170/32", "last", "192.0.0.170", "c00000aa", "SPECIAL"},
            {"192.0.0.170/32", "before", "192.0.0.169", "c00000a9", "SPECIAL"},
            {"192.0.0.170/32", "after", "192.0.0.171", "c00000ab", "SPECIAL"},
            {"192.0.0.171/32", "first", "192.0.0.171", "c00000ab", "SPECIAL"},
            {"192.0.0.171/32", "last", "192.0.0.171", "c00000ab", "SPECIAL"},
            {"192.0.0.171/32", "before", "192.0.0.170", "c00000aa", "SPECIAL"},
            {"192.0.0.171/32", "after", "192.0.0.172", "c00000ac", "SPECIAL"},
            {"192.88.99.2/32", "first", "192.88.99.2", "c0586302", "SPECIAL"},
            {"192.88.99.2/32", "last", "192.88.99.2", "c0586302", "SPECIAL"},
            {"192.88.99.2/32", "before", "192.88.99.1", "c0586301", "SPECIAL"},
            {"192.88.99.2/32", "after", "192.88.99.3", "c0586303", "SPECIAL"},
    };

    static Stream<Arguments> boundaries() {
        return Arrays.stream(BOUNDARIES).map(r -> Arguments.of(r[0], r[1], r[2], r[3], r[4]));
    }

    @ParameterizedTest(name = "{0} {1} {2} -> {4}")
    @MethodSource("boundaries")
    void prefixBoundariesAndNeighbours(String prefix, String role, String text, String hex, String expected) {
        assertEquals(TestAddresses.hex(hex), ip(text), "text and hex disagree");
        assertEquals(category(expected), POLICY.classify(TestAddresses.hex(hex)), prefix + " " + role);
    }

    @Test void boundaryTableCoversFortyFivePrefixes() {
        assertEquals(174, BOUNDARIES.length);
        assertEquals(45, Arrays.stream(BOUNDARIES).map(r -> r[0]).distinct().count());
        assertTrue(Arrays.stream(BOUNDARIES).filter(r -> r[1].equals("first") || r[1].equals("last"))
                .allMatch(r -> r[4].equals("SPECIAL")));
    }

    /** Literal probes of the approved snapshot review (48 forbidden, 18 admissible). */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            // forbidden although already outside the positive perimeter
            "10.0.0.1,SPECIAL", "127.0.0.1,SPECIAL", "0.0.0.0,SPECIAL", "224.0.0.1,SPECIAL",
            "239.255.255.255,SPECIAL", "240.0.0.1,SPECIAL", "255.255.255.255,SPECIAL",
            // effective denials inside the positive perimeter: layer 2 wins
            "172.16.0.0,SPECIAL", "172.16.0.1,SPECIAL", "172.31.255.255,SPECIAL", "192.168.1.1,SPECIAL",
            "100.64.0.1,SPECIAL", "100.100.100.200,SPECIAL", "169.254.169.254,SPECIAL", "168.63.129.16,SPECIAL",
            "192.0.2.1,SPECIAL", "198.51.100.1,SPECIAL", "203.0.113.1,SPECIAL", "198.18.0.1,SPECIAL",
            "192.88.99.1,SPECIAL", "192.31.196.1,SPECIAL", "192.0.0.9,SPECIAL", "2001:db8::1,SPECIAL",
            "2620:4f:8000::1,SPECIAL",
            // IPv6 special space, including IPv4-embedding prefixes, never unwrapped
            "::1,SPECIAL", "::,SPECIAL", "::ffff:7f00:1,SPECIAL", "::7f00:1,SPECIAL", "::ffff:808:808,SPECIAL",
            "::808:808,SPECIAL", "64:ff9b::a00:1,SPECIAL", "64:ff9b::808:808,SPECIAL", "64:ff9b:1::1,SPECIAL",
            "100::1,SPECIAL", "100:0:0:1::1,SPECIAL", "2001::1,SPECIAL", "2001:0:808:808::1,SPECIAL",
            "2002:a00:1::1,SPECIAL", "2002:808:808::1,SPECIAL", "3fff::1,SPECIAL", "5f00::1,SPECIAL",
            "fc00::1,SPECIAL", "fd00:ec2::254,SPECIAL", "fe80::1,SPECIAL", "fec0::1,SPECIAL", "ff02::1,SPECIAL",
            // outside every positive prefix and inside no negative prefix
            "3000::1,UNALLOCATED", "4000::1,UNALLOCATED",
            // admissible: means only "not excluded by the catalog"
            "8.8.8.8,ADMISSIBLE", "1.1.1.1,ADMISSIBLE", "168.63.129.15,ADMISSIBLE", "168.63.129.17,ADMISSIBLE",
            "172.15.255.255,ADMISSIBLE", "172.32.0.0,ADMISSIBLE", "192.0.1.255,ADMISSIBLE",
            "100.63.255.255,ADMISSIBLE", "100.128.0.0,ADMISSIBLE", "192.167.255.255,ADMISSIBLE",
            "198.20.0.0,ADMISSIBLE", "2a00:1450::1,ADMISSIBLE", "2001:200::1,ADMISSIBLE", "2c0f:f000::1,ADMISSIBLE",
            "2620:4f:7fff::1,ADMISSIBLE", "2620:4f:8001::1,ADMISSIBLE", "2001:db9::1,ADMISSIBLE",
            "2001:db7:ffff::1,ADMISSIBLE",
    })
    void snapshotReviewProbes(String address, String expected) {
        assertEquals(category(expected), POLICY.classify(ip(address)), address);
    }

    /** ADR-0002 section 19 illustrative neighbours, each with an explicit expectation. */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "9.255.255.255,ADMISSIBLE", "11.0.0.0,ADMISSIBLE", "100.63.255.255,ADMISSIBLE", "100.128.0.0,ADMISSIBLE",
            "172.15.255.255,ADMISSIBLE", "172.32.0.0,ADMISSIBLE", "198.17.255.255,ADMISSIBLE", "198.20.0.0,ADMISSIBLE",
            "168.63.129.15,ADMISSIBLE", "168.63.129.17,ADMISSIBLE", "223.255.255.255,ADMISSIBLE", "224.0.0.0,SPECIAL",
            "2001:1ff:ffff:ffff:ffff:ffff:ffff:ffff,SPECIAL", "2001:200::,ADMISSIBLE",
    })
    void adrIllustrativeNeighbours(String address, String expected) {
        assertEquals(category(expected), POLICY.classify(ip(address)), address);
    }

    @Test void layerTwoAlwaysWinsOverLayerOneAndOverUnallocated() {
        assertEquals(FORBIDDEN_SPECIAL_USE, POLICY.classify(ip("127.0.0.1")));
        assertEquals(FORBIDDEN_SPECIAL_USE, POLICY.classify(ip("10.255.255.255")));
        assertEquals(FORBIDDEN_SPECIAL_USE, POLICY.classify(ip("172.20.1.2")));
        assertEquals(ADMISSIBLE, POLICY.classify(ip("172.0.0.1")));
        assertEquals(FORBIDDEN_SPECIAL_USE, POLICY.classify(ip("2001:db8:ffff::1")));
        assertEquals(ADMISSIBLE, POLICY.classify(ip("2001:dff:ffff:ffff:ffff:ffff:ffff:ffff")));
        assertEquals(FORBIDDEN_UNALLOCATED, POLICY.classify(ip("2d00::1")));
    }

    @Test void nestedAndOverlappingNegativesForbidTheWholeOuterPrefix() {
        assertEquals(FORBIDDEN_SPECIAL_USE, POLICY.classify(ip("2001::")));
        assertEquals(FORBIDDEN_SPECIAL_USE, POLICY.classify(ip("2001:0:ffff:ffff:ffff:ffff:ffff:ffff")));
        assertEquals(FORBIDDEN_SPECIAL_USE, POLICY.classify(ip("2001:1::")));
        assertEquals(FORBIDDEN_SPECIAL_USE, POLICY.classify(ip("2001:1ff::1")));
        assertEquals(FORBIDDEN_SPECIAL_USE, POLICY.classify(ip("255.255.255.255")));
        assertEquals(FORBIDDEN_SPECIAL_USE, POLICY.classify(ip("240.0.0.0")));
        assertEquals(FORBIDDEN_SPECIAL_USE, POLICY.classify(ip("169.254.169.254")));
        assertEquals(FORBIDDEN_SPECIAL_USE, POLICY.classify(ip("192.0.0.11")));
        assertEquals(FORBIDDEN_SPECIAL_USE, POLICY.classify(ip("192.0.0.255")));
        assertEquals(FORBIDDEN_SPECIAL_USE, POLICY.classify(ip("febf:ffff:ffff:ffff:ffff:ffff:ffff:ffff")));
        assertEquals(FORBIDDEN_SPECIAL_USE, POLICY.classify(ip("fec0::")));
    }

    @Test void embeddedIpv4FormsAreNeverUnwrapped() {
        assertEquals(ADMISSIBLE, POLICY.classify(ip("8.8.8.8")));
        assertEquals(ip("::ffff:808:808"), ip("::ffff:8.8.8.8"));
        assertEquals(FORBIDDEN_SPECIAL_USE, POLICY.classify(ip("::ffff:8.8.8.8")));
        assertEquals(FORBIDDEN_SPECIAL_USE, POLICY.classify(ip("::8.8.8.8")));
        assertEquals(FORBIDDEN_SPECIAL_USE, POLICY.classify(ip("64:ff9b::8.8.8.8")));
        assertEquals(FORBIDDEN_SPECIAL_USE, POLICY.classify(ip("2002:808:808::")));
        assertEquals(FORBIDDEN_SPECIAL_USE, POLICY.classify(ip("2001:0:808:808::")));
        assertNotEquals(ip("8.8.8.8"), ip("::ffff:8.8.8.8"));
    }

    @Test void positivePerimeterEdgesAndConservativeFailureOutsideIt() {
        assertEquals(ADMISSIBLE, POLICY.classify(ip("1.0.0.0")));
        assertEquals(FORBIDDEN_SPECIAL_USE, POLICY.classify(ip("0.255.255.255")));
        assertEquals(ADMISSIBLE, POLICY.classify(ip("128.0.0.0")));
        assertEquals(ADMISSIBLE, POLICY.classify(ip("126.255.255.255")));
        assertEquals(FORBIDDEN_UNALLOCATED, POLICY.classify(ip("2000::")));
        assertEquals(FORBIDDEN_UNALLOCATED, POLICY.classify(ip("3fff:1000::")));
        assertEquals(FORBIDDEN_UNALLOCATED, POLICY.classify(ip("::1:0:0")));
        assertEquals(FORBIDDEN_UNALLOCATED, POLICY.classify(ip("fe00::")));
        assertEquals(ADMISSIBLE, POLICY.classify(ip("2003::")));
    }

    @Test void bytesAtOrAboveEightyHexAreComparedUnsigned() {
        assertEquals(ADMISSIBLE, POLICY.classify(IpAddress.ipv4(new byte[]{(byte) 0x80, 0, 0, 1})));
        assertEquals(ADMISSIBLE, POLICY.classify(IpAddress.ipv4(new byte[]{(byte) 0xDF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF})));
        assertEquals(FORBIDDEN_SPECIAL_USE, POLICY.classify(IpAddress.ipv4(new byte[]{(byte) 0xE0, 0, 0, 0})));
        assertEquals(FORBIDDEN_SPECIAL_USE, POLICY.classify(IpAddress.ipv4(new byte[]{(byte) 0xA9, (byte) 0xFE, 0, 0})));
        assertEquals(ADMISSIBLE, POLICY.classify(IpAddress.ipv4(new byte[]{(byte) 0xA9, (byte) 0xFD, (byte) 0xFF, (byte) 0xFF})));
    }

    @Test void policyRejectsNullAndKeepsItsRevision() {
        assertThrows(NullPointerException.class, () -> POLICY.classify(null));
        assertThrows(NullPointerException.class, () -> new AddressPolicy(null));
        assertEquals("IANA-SNAPSHOT-02", POLICY.catalogRevision());
        assertEquals("AddressPolicy[IANA-SNAPSHOT-02]", POLICY.toString());
        assertTrue(ADMISSIBLE.isAdmissible());
        assertFalse(FORBIDDEN_SPECIAL_USE.isAdmissible());
        assertFalse(FORBIDDEN_UNALLOCATED.isAdmissible());
    }

    private static AddressCategory category(String expected) {
        switch (expected) {
            case "ADMISSIBLE":
                return ADMISSIBLE;
            case "SPECIAL":
                return FORBIDDEN_SPECIAL_USE;
            case "UNALLOCATED":
                return FORBIDDEN_UNALLOCATED;
            default:
                throw new IllegalArgumentException(expected);
        }
    }
}
