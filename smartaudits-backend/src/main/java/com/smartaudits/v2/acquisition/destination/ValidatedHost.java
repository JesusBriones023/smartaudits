package com.smartaudits.v2.acquisition.destination;

/**
 * A host that passed every host rule of ADR-0002 (rules 7-18): a canonical ASCII DNS name, lowercase,
 * of at least two LDH labels, not an IP literal, not IDN and not a forbidden name. It has no public
 * constructor and is obtained only from an accepted {@link DestinationUrl}; it is the only input the
 * future resolver may receive. {@link #toString()} never reveals the name (section 15).
 */
public final class ValidatedHost {
    private final String name;

    ValidatedHost(String name) {
        this.name = name;
    }

    /** The canonical host name. Explicit accessor for the future transport; never logged. */
    public String name() {
        return name;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ValidatedHost that && name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return "ValidatedHost[redacted]";
    }
}
