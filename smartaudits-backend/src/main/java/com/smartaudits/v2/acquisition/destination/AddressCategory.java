package com.smartaudits.v2.acquisition.destination;

/** Result of classifying one address against the two-layer catalog (ADR-0002 section 11). */
public enum AddressCategory {
    /** Inside a layer-1 prefix and inside no layer-2 prefix. Means only "not excluded by the catalog". */
    ADMISSIBLE,
    /** Inside a layer-2 prefix (special-purpose registry or product rule); always wins over layer 1. */
    FORBIDDEN_SPECIAL_USE,
    /** Outside every layer-1 prefix (conservative failure for unassigned or reserved space). */
    FORBIDDEN_UNALLOCATED;

    public boolean isAdmissible() {
        return this == ADMISSIBLE;
    }
}
