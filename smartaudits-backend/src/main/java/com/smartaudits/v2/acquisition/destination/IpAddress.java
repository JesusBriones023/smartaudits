package com.smartaudits.v2.acquisition.destination;

import java.util.Arrays;
import java.util.Objects;

/**
 * An IPv4 or IPv6 address as raw bytes (exactly 4 or 16). Built only from bytes: no text parsing,
 * no {@code InetAddress} and no name resolution. The bytes are copied on the way in and out, and
 * {@link #toString()} never reveals them (ADR-0002 sections 13 and 15).
 */
public final class IpAddress {
    private final IpFamily family;
    private final byte[] bytes;

    private IpAddress(IpFamily family, byte[] bytes) {
        this.family = family;
        this.bytes = bytes;
    }

    public static IpAddress of(IpFamily family, byte[] bytes) {
        Objects.requireNonNull(family, "family must not be null");
        Objects.requireNonNull(bytes, "bytes must not be null");
        if (bytes.length != family.byteLength()) {
            throw new IllegalArgumentException("address length does not match its family");
        }
        return new IpAddress(family, bytes.clone());
    }

    public static IpAddress ipv4(byte[] bytes) {
        return of(IpFamily.IPV4, bytes);
    }

    public static IpAddress ipv6(byte[] bytes) {
        return of(IpFamily.IPV6, bytes);
    }

    public IpFamily family() {
        return family;
    }

    /** A fresh copy of the address bytes, in network order. */
    public byte[] bytes() {
        return bytes.clone();
    }

    /** Unsigned value (0-255) of one byte, for prefix matching without copying. */
    int unsignedByte(int index) {
        return bytes[index] & 0xFF;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof IpAddress that && family == that.family && Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
        return 31 * family.hashCode() + Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "IpAddress[" + family + "]";
    }
}
