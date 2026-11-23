package com.smartaudits.v2.site;

import com.smartaudits.v2.acquisition.destination.UrlParseResult;

import java.util.Objects;
import java.util.Optional;

/**
 * Result of analysing an untrusted text as a Site identity: either an accepted
 * {@link SiteUrlIdentity} or a rejection that keeps nothing of the text. Invalid input never
 * throws; only a {@code null} argument is a contract error.
 */
public sealed interface SiteUrlIdentityResult permits SiteUrlIdentityResult.Accepted, SiteUrlIdentityResult.Rejected {

    /** The text is a bare origin of the destination profile. Syntax only: no DNS, no approval. */
    final class Accepted implements SiteUrlIdentityResult {
        private final SiteUrlIdentity identity;

        Accepted(SiteUrlIdentity identity) {
            this.identity = Objects.requireNonNull(identity, "identity must not be null");
        }

        public SiteUrlIdentity identity() {
            return identity;
        }

        @Override
        public String toString() {
            return "Accepted[" + identity + "]";
        }
    }

    /** The reason and, only for {@link SiteUrlRejection#DESTINATION_SYNTAX}, the rejection of the parser. */
    final class Rejected implements SiteUrlIdentityResult {
        private final SiteUrlRejection reason;
        private final UrlParseResult.Rejected destination;

        /** The destination profile rejected the text; its code and catalog revision are kept as they are. */
        Rejected(UrlParseResult.Rejected destination) {
            this.reason = SiteUrlRejection.DESTINATION_SYNTAX;
            this.destination = Objects.requireNonNull(destination, "destination rejection must not be null");
        }

        /** The destination profile accepted the text, but it is not a bare origin. */
        Rejected(SiteUrlRejection reason) {
            this.reason = Objects.requireNonNull(reason, "reason must not be null");
            this.destination = null;
        }

        public SiteUrlRejection reason() {
            return reason;
        }

        /** Present exactly when the reason is {@link SiteUrlRejection#DESTINATION_SYNTAX}. */
        public Optional<UrlParseResult.Rejected> destinationRejection() {
            return Optional.ofNullable(destination);
        }

        @Override
        public String toString() {
            return destination == null ? "Rejected[" + reason + "]" : "Rejected[" + reason + ", " + destination + "]";
        }
    }
}
