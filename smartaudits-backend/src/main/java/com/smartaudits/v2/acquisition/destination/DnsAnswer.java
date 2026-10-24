package com.smartaudits.v2.acquisition.destination;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Untrusted DNS answer of ADR-0002 section 10: for each family (A and AAAA) a status, the followed
 * CNAME chain (in order, without the queried name) and the address records as raw bytes.
 * Construction never validates and never throws on malformed content: absent families, lists or
 * elements ({@code null}) and records of the wrong length are kept so that the policy rejects them
 * with a code. Copies are deep: every {@code byte[]} is cloned on the way in and out, and lists are
 * copied into unmodifiable lists that keep their {@code null} elements ({@code List.copyOf} is never
 * used on answer data). {@link #toString()} reveals no names or addresses.
 */
public final class DnsAnswer {
    /** Per-family status. FAILURE carries no message or cause. */
    public enum Status {
        /** The family has address records. */
        ADDRESSES,
        /** The name exists but has no record of this family. */
        NODATA,
        /** The name does not exist. */
        NXDOMAIN,
        /** No answer was obtained (timeout, SERVFAIL, REFUSED, network error or equivalent). */
        FAILURE
    }

    /** Untrusted answer of one family; every part may be {@code null} or malformed. */
    public static final class FamilyAnswer {
        private final Status status;
        private final List<String> cnameChain;
        private final List<byte[]> records;

        private FamilyAnswer(Status status, List<String> cnameChain, List<byte[]> records) {
            this.status = status;
            this.cnameChain = cnameChain;
            this.records = records;
        }

        /** Copies the chain and records deeply, keeping {@code null} lists and elements. Never throws. */
        public static FamilyAnswer of(Status status, List<String> cnameChain, List<byte[]> records) {
            return new FamilyAnswer(status, copyNames(cnameChain), copyRecords(records));
        }

        /** Shorthand for a family with records and no CNAME chain. */
        public static FamilyAnswer addresses(List<byte[]> records) {
            return of(Status.ADDRESSES, List.of(), records);
        }

        /** Shorthand for a NODATA family without CNAME chain. */
        public static FamilyAnswer nodata() {
            return of(Status.NODATA, List.of(), List.of());
        }

        public Status status() {
            return status;
        }

        /** Unmodifiable chain, possibly {@code null} or with {@code null} names. */
        public List<String> cnameChain() {
            return cnameChain;
        }

        /** Fresh deep copy of the records, possibly {@code null} or with {@code null} elements. */
        public List<byte[]> records() {
            return copyRecords(records);
        }

        int recordCount() {
            return records == null ? 0 : records.size();
        }

        int cnameCount() {
            return cnameChain == null ? 0 : cnameChain.size();
        }

        /** Internal read-only view without copying; callers never mutate or expose the arrays. */
        List<byte[]> recordsView() {
            return records;
        }

        @Override
        public String toString() {
            return "FamilyAnswer[" + status + ", " + recordCount() + " records, " + cnameCount() + " names]";
        }
    }

    private final FamilyAnswer a;
    private final FamilyAnswer aaaa;

    private DnsAnswer(FamilyAnswer a, FamilyAnswer aaaa) {
        this.a = a;
        this.aaaa = aaaa;
    }

    /** Either family may be {@code null}; the policy then returns DNS_INVALID. Never throws. */
    public static DnsAnswer of(FamilyAnswer a, FamilyAnswer aaaa) {
        return new DnsAnswer(a, aaaa);
    }

    /** The A family (IPv4 records of 4 bytes), possibly {@code null}. */
    public FamilyAnswer a() {
        return a;
    }

    /** The AAAA family (IPv6 records of 16 bytes), possibly {@code null}. */
    public FamilyAnswer aaaa() {
        return aaaa;
    }

    private static List<String> copyNames(List<String> names) {
        if (names == null) {
            return null;
        }
        return Collections.unmodifiableList(new ArrayList<>(names));
    }

    private static List<byte[]> copyRecords(List<byte[]> records) {
        if (records == null) {
            return null;
        }
        List<byte[]> copy = new ArrayList<>(records.size());
        for (byte[] record : records) {
            copy.add(record == null ? null : record.clone());
        }
        return Collections.unmodifiableList(copy);
    }

    @Override
    public String toString() {
        return "DnsAnswer[A=" + (a == null ? "absent" : a.status) + ", AAAA=" + (aaaa == null ? "absent" : aaaa.status) + "]";
    }
}
