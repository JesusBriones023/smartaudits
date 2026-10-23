package com.smartaudits.v2.acquisition.destination;

import java.util.List;
import java.util.Objects;

/**
 * Pure, stateless address classification by explicit binary prefixes (ADR-0002 section 11).
 * Layer 2 (forbidden) is checked first and always prohibits; otherwise the address must be inside a
 * layer-1 (assigned) prefix; everything else is forbidden. Embedded IPv4 forms are never unwrapped.
 */
public final class AddressPolicy {
    private final String catalogRevision;
    private final List<IpPrefix> positivePrefixes;
    private final List<IpPrefix> negativePrefixes;

    public AddressPolicy(AddressCatalog catalog) {
        Objects.requireNonNull(catalog, "catalog must not be null");
        this.catalogRevision = catalog.revision();
        this.positivePrefixes = catalog.positivePrefixes();
        this.negativePrefixes = catalog.negativePrefixes();
    }

    public AddressCategory classify(IpAddress address) {
        Objects.requireNonNull(address, "address must not be null");
        for (IpPrefix prefix : negativePrefixes) {
            if (prefix.contains(address)) {
                return AddressCategory.FORBIDDEN_SPECIAL_USE;
            }
        }
        for (IpPrefix prefix : positivePrefixes) {
            if (prefix.contains(address)) {
                return AddressCategory.ADMISSIBLE;
            }
        }
        return AddressCategory.FORBIDDEN_UNALLOCATED;
    }

    public String catalogRevision() {
        return catalogRevision;
    }

    @Override
    public String toString() {
        return "AddressPolicy[" + catalogRevision + "]";
    }
}
