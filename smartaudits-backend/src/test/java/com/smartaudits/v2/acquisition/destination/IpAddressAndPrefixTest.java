package com.smartaudits.v2.acquisition.destination;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Byte-level value types: exact lengths, defensive copies, redacted toString and unsigned masking. */
class IpAddressAndPrefixTest {

    @Test void addressesRequireTheExactByteLengthOfTheirFamily() {
        assertEquals(IpFamily.IPV4, IpAddress.ipv4(new byte[4]).family());
        assertEquals(IpFamily.IPV6, IpAddress.ipv6(new byte[16]).family());
        assertThrows(IllegalArgumentException.class, () -> IpAddress.ipv4(new byte[16]));
        assertThrows(IllegalArgumentException.class, () -> IpAddress.ipv6(new byte[4]));
        assertThrows(IllegalArgumentException.class, () -> IpAddress.ipv4(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> IpAddress.ipv6(new byte[15]));
        assertThrows(IllegalArgumentException.class, () -> IpAddress.of(IpFamily.IPV4, new byte[5]));
        assertThrows(NullPointerException.class, () -> IpAddress.ipv4(null));
        assertThrows(NullPointerException.class, () -> IpAddress.of(null, new byte[4]));
    }

    @Test void addressBytesAreCopiedOnTheWayInAndOut() {
        byte[] input = {10, 0, 0, 1};
        IpAddress address = IpAddress.ipv4(input);
        input[0] = (byte) 8;
        assertArrayEquals(new byte[]{10, 0, 0, 1}, address.bytes());
        byte[] output = address.bytes();
        output[0] = (byte) 8;
        assertArrayEquals(new byte[]{10, 0, 0, 1}, address.bytes());
        assertNotSame(address.bytes(), address.bytes());
    }

    @Test void equalityIsByFamilyAndBytesAndToStringRevealsOnlyTheFamily() {
        assertEquals(IpAddress.ipv4(new byte[]{1, 2, 3, 4}), IpAddress.ipv4(new byte[]{1, 2, 3, 4}));
        assertEquals(IpAddress.ipv4(new byte[]{1, 2, 3, 4}).hashCode(), IpAddress.ipv4(new byte[]{1, 2, 3, 4}).hashCode());
        assertNotEquals(IpAddress.ipv4(new byte[]{1, 2, 3, 4}), IpAddress.ipv4(new byte[]{1, 2, 3, 5}));
        IpAddress secret = IpAddress.ipv4(new byte[]{(byte) 169, (byte) 254, (byte) 169, (byte) 254});
        assertEquals("IpAddress[IPV4]", secret.toString());
        assertFalse(secret.toString().contains("169"));
        assertEquals("IpAddress[IPV6]", TestAddresses.ip("fd00:ec2::254").toString());
    }

    @Test void prefixesMustBeCanonicalAndInRange() {
        assertDoesNotThrow(() -> IpPrefix.of(IpFamily.IPV4, new byte[]{10, 0, 0, 0}, 8));
        assertDoesNotThrow(() -> IpPrefix.of(IpFamily.IPV4, new byte[4], 0));
        assertDoesNotThrow(() -> IpPrefix.of(IpFamily.IPV6, new byte[16], 128));
        assertThrows(IllegalArgumentException.class, () -> IpPrefix.of(IpFamily.IPV4, new byte[]{10, 0, 0, 1}, 8));
        assertThrows(IllegalArgumentException.class, () -> IpPrefix.of(IpFamily.IPV4, new byte[]{(byte) 172, 31, 0, 0}, 12));
        assertThrows(IllegalArgumentException.class, () -> IpPrefix.of(IpFamily.IPV4, new byte[4], 33));
        assertThrows(IllegalArgumentException.class, () -> IpPrefix.of(IpFamily.IPV4, new byte[4], -1));
        assertThrows(IllegalArgumentException.class, () -> IpPrefix.of(IpFamily.IPV6, new byte[16], 129));
        assertThrows(IllegalArgumentException.class, () -> IpPrefix.of(IpFamily.IPV6, new byte[4], 8));
    }

    @Test void containmentUsesUnsignedBytesAndExactMasks() {
        IpPrefix p172 = IpPrefix.of(IpFamily.IPV4, new byte[]{(byte) 172, 16, 0, 0}, 12);
        assertTrue(p172.contains(TestAddresses.ip("172.16.0.0")));
        assertTrue(p172.contains(TestAddresses.ip("172.31.255.255")));
        assertFalse(p172.contains(TestAddresses.ip("172.15.255.255")));
        assertFalse(p172.contains(TestAddresses.ip("172.32.0.0")));
        IpPrefix p240 = IpPrefix.of(IpFamily.IPV4, new byte[]{(byte) 240, 0, 0, 0}, 4);
        assertTrue(p240.contains(TestAddresses.ip("255.255.255.255")));
        assertFalse(p240.contains(TestAddresses.ip("239.255.255.255")));
        IpPrefix fe80 = IpPrefix.of(IpFamily.IPV6, TestAddresses.ipv6Bytes("fe80::"), 10);
        assertTrue(fe80.contains(TestAddresses.ip("febf:ffff:ffff:ffff:ffff:ffff:ffff:ffff")));
        assertFalse(fe80.contains(TestAddresses.ip("fec0::")));
        IpPrefix host = IpPrefix.of(IpFamily.IPV4, new byte[]{(byte) 168, 63, (byte) 129, 16}, 32);
        assertTrue(host.contains(TestAddresses.ip("168.63.129.16")));
        assertFalse(host.contains(TestAddresses.ip("168.63.129.17")));
        assertFalse(host.contains(TestAddresses.ip("168.63.129.15")));
        IpPrefix all4 = IpPrefix.of(IpFamily.IPV4, new byte[4], 0);
        assertTrue(all4.contains(TestAddresses.ip("255.255.255.255")));
        assertFalse(all4.contains(TestAddresses.ip("::")));
    }

    @Test void containmentNeverMixesFamilies() {
        IpPrefix mapped = IpPrefix.of(IpFamily.IPV6, TestAddresses.ipv6Bytes("::ffff:0:0"), 96);
        assertTrue(mapped.contains(TestAddresses.ip("::ffff:10.0.0.1")));
        assertFalse(mapped.contains(TestAddresses.ip("10.0.0.1")));
        IpPrefix ten = IpPrefix.of(IpFamily.IPV4, new byte[]{10, 0, 0, 0}, 8);
        assertFalse(ten.contains(TestAddresses.ip("::ffff:10.0.0.1")));
        assertThrows(NullPointerException.class, () -> ten.contains(null));
    }

    @Test void prefixNetworkBytesAreCopiedAndToStringHasNoBytes() {
        byte[] network = {10, 0, 0, 0};
        IpPrefix prefix = IpPrefix.of(IpFamily.IPV4, network, 8);
        network[0] = 11;
        assertTrue(prefix.contains(TestAddresses.ip("10.1.2.3")));
        byte[] out = prefix.networkBytes();
        out[0] = 11;
        assertEquals(10, prefix.networkBytes()[0]);
        assertEquals("IpPrefix[IPV4/8]", prefix.toString());
        assertEquals(IpPrefix.of(IpFamily.IPV4, new byte[]{10, 0, 0, 0}, 8), prefix);
        assertNotEquals(IpPrefix.of(IpFamily.IPV4, new byte[]{10, 0, 0, 0}, 9), prefix);
    }

    @Test void testParserAgreesWithKnownByteForms() {
        assertArrayEquals(new byte[]{(byte) 255, (byte) 255, (byte) 255, (byte) 255}, TestAddresses.ipv4Bytes("255.255.255.255"));
        assertEquals(TestAddresses.hex("00000000000000000000ffff7f000001"), TestAddresses.ip("::ffff:127.0.0.1"));
        assertEquals(TestAddresses.hex("20010db8000000000000000000000001"), TestAddresses.ip("2001:db8::1"));
        assertEquals("::ffff:0.0.0.0", TestAddresses.canonicalText(TestAddresses.ipv6Bytes("::ffff:0:0")));
        assertEquals("100:0:0:1::", TestAddresses.canonicalText(TestAddresses.ipv6Bytes("100:0:0:1::")));
        assertThrows(IllegalArgumentException.class, () -> TestAddresses.ipv4Bytes("01.2.3.4"));
        assertThrows(IllegalArgumentException.class, () -> TestAddresses.ipv6Bytes("1::2::3"));
    }
}
