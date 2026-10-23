package com.smartaudits.v2.acquisition.destination;

import java.util.Arrays;
import java.util.Objects;

/**
 * A canonical binary prefix (network bytes with every host bit zero, plus a prefix length).
 * Membership compares unsigned bytes under the prefix mask; no {@code InetAddress} helper is used
 * (ADR-0002 section 11).
 */
public final class IpPrefix {
    private final IpFamily family;
    private final byte[] network;
    private final int length;

    private IpPrefix(IpFamily family, byte[] network, int length) {
        this.family = family;
        this.network = network;
        this.length = length;
    }

    /** Rejects lengths outside 0..bits, wrong byte counts and networks with host bits set. */
    static IpPrefix of(IpFamily family, byte[] network, int length) {
        Objects.requireNonNull(family, "family must not be null");
        Objects.requireNonNull(network, "network must not be null");
        if (network.length != family.byteLength()) {
            throw new IllegalArgumentException("network length does not match its family");
        }
        if (length < 0 || length > family.bitLength()) {
            throw new IllegalArgumentException("prefix length out of range");
        }
        byte[] copy = network.clone();
        for (int bit = length; bit < family.bitLength(); bit++) {
            if ((copy[bit / 8] & (0x80 >>> (bit % 8))) != 0) {
                throw new IllegalArgumentException("prefix network has host bits set");
            }
        }
        return new IpPrefix(family, copy, length);
    }

    public IpFamily family() {
        return family;
    }

    public byte[] networkBytes() {
        return network.clone();
    }

    public int length() {
        return length;
    }

    /** True only for an address of the same family whose first {@link #length()} bits equal the network. */
    public boolean contains(IpAddress address) {
        Objects.requireNonNull(address, "address must not be null");
        if (address.family() != family) {
            return false;
        }
        int fullBytes = length / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (address.unsignedByte(i) != (network[i] & 0xFF)) {
                return false;
            }
        }
        int remainingBits = length % 8;
        if (remainingBits == 0) {
            return true;
        }
        int mask = (0xFF << (8 - remainingBits)) & 0xFF;
        return (address.unsignedByte(fullBytes) & mask) == (network[fullBytes] & 0xFF);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof IpPrefix that && family == that.family && length == that.length
                && Arrays.equals(network, that.network);
    }

    @Override
    public int hashCode() {
        return (31 * family.hashCode() + Arrays.hashCode(network)) * 31 + length;
    }

    @Override
    public String toString() {
        return "IpPrefix[" + family + "/" + length + "]";
    }
}
