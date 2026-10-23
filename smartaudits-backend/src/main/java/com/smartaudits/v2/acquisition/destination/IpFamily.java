package com.smartaudits.v2.acquisition.destination;

/** Address family of an {@link IpAddress} or {@link IpPrefix}, with its exact byte length. */
public enum IpFamily {
    IPV4(4, "4"),
    IPV6(16, "6");

    private final int byteLength;
    private final String catalogCode;

    IpFamily(int byteLength, String catalogCode) {
        this.byteLength = byteLength;
        this.catalogCode = catalogCode;
    }

    public int byteLength() {
        return byteLength;
    }

    public int bitLength() {
        return byteLength * 8;
    }

    /** Value of the {@code family} column of the catalog serialization. */
    String catalogCode() {
        return catalogCode;
    }

    static IpFamily fromCatalogCode(String code) {
        for (IpFamily family : values()) {
            if (family.catalogCode.equals(code)) {
                return family;
            }
        }
        throw new IllegalArgumentException("unknown address family code");
    }
}
