package com.smartaudits.v2.acquisition.destination;

import java.util.List;
import java.util.Objects;

/**
 * Final decision of ADR-0002 sections 13-15: approved or rejected; there is no {@code boolean allowed}.
 * An approval documents one evaluation: it binds the {@link DestinationUrl}, its host, scheme and
 * effective port, every approved address (deduplicated, IPv4 first, in first-appearance order) and
 * the catalog revision. It authorizes no connection by itself, carries no timestamp or TTL, and must
 * never be serialized, persisted, cached or reused across attempts (4.1-B evaluates anew each time).
 */
public sealed interface DestinationDecision permits DestinationDecision.Approved, DestinationDecision.Rejected {

    /**
     * Approval. Not a record and not Serializable; it has no public constructor and no public factory
     * returns it: only {@link DestinationPolicy}, in this package, creates it.
     */
    final class Approved implements DestinationDecision {
        private final DestinationUrl url;
        private final List<IpAddress> addresses;
        private final String catalogRevision;

        Approved(DestinationUrl url, List<IpAddress> addresses, String catalogRevision) {
            this.url = Objects.requireNonNull(url, "url must not be null");
            this.addresses = List.copyOf(addresses);
            this.catalogRevision = Objects.requireNonNull(catalogRevision, "catalog revision must not be null");
            if (this.addresses.isEmpty()) {
                throw new IllegalArgumentException("an approval needs at least one address");
            }
        }

        public DestinationUrl url() {
            return url;
        }

        public ValidatedHost host() {
            return url.host();
        }

        public String scheme() {
            return url.scheme();
        }

        public int port() {
            return url.port();
        }

        /** Every approved address of the answer, deduplicated, IPv4 first; unmodifiable. */
        public List<IpAddress> addresses() {
            return addresses;
        }

        public String catalogRevision() {
            return catalogRevision;
        }

        @Override
        public String toString() {
            return "Approved[" + url.scheme() + ":" + url.port() + ", " + addresses.size() + " addresses, "
                    + catalogRevision + "]";
        }
    }

    /** Rejection: only the code and the catalog revision, never the input, host or addresses. */
    final class Rejected implements DestinationDecision {
        private final RejectionCode code;
        private final String catalogRevision;

        Rejected(RejectionCode code, String catalogRevision) {
            this.code = Objects.requireNonNull(code, "code must not be null");
            this.catalogRevision = Objects.requireNonNull(catalogRevision, "catalog revision must not be null");
        }

        public RejectionCode code() {
            return code;
        }

        public String catalogRevision() {
            return catalogRevision;
        }

        @Override
        public String toString() {
            return "Rejected[" + code + ", " + catalogRevision + "]";
        }
    }
}
