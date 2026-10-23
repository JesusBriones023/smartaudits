package com.smartaudits.v2.acquisition.destination;

import java.util.Objects;

/**
 * Result of analysing an untrusted URL text (ADR-0002 section 13): either an accepted
 * {@link DestinationUrl} or a rejection carrying only its code and the catalog revision.
 * Invalid input never throws; only a {@code null} argument is a contract error.
 */
public sealed interface UrlParseResult permits UrlParseResult.Parsed, UrlParseResult.Rejected {

    /** The URL passed rules 1-25. It is syntactically valid only: no DNS, no destination approval. */
    final class Parsed implements UrlParseResult {
        private final DestinationUrl url;

        Parsed(DestinationUrl url) {
            this.url = Objects.requireNonNull(url, "url must not be null");
        }

        public DestinationUrl url() {
            return url;
        }

        @Override
        public String toString() {
            return "Parsed[" + url + "]";
        }
    }

    /** The first violated rule decided the code; nothing of the input is kept. */
    final class Rejected implements UrlParseResult {
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
