package com.smartaudits.v2.acquisition.destination;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Destination policy of ADR-0002 (sections 5, 10, 11 and 13). It orchestrates URL analysis (rules
 * 1-25), exactly one resolver call per evaluation (never after a syntax rejection), validation of the
 * DNS answer (rules 26-29, in order) and classification of every address with {@link AddressPolicy}
 * (rules 30-31). No caches, no retries, no fallback to one family, no network, no logs. An inadmissible
 * address is never dropped: one forbidden address rejects the whole answer.
 */
public final class DestinationPolicy {
    static final int MAX_ADDRESS_RECORDS = 32;
    static final int MAX_CNAME_NAMES = 8;

    private final HostResolver resolver;
    private final AddressPolicy addressPolicy;
    private final String catalogRevision;

    private DestinationPolicy(HostResolver resolver) {
        this.resolver = resolver;
        AddressCatalog catalog = AddressCatalog.snapshot();
        this.addressPolicy = new AddressPolicy(catalog);
        this.catalogRevision = catalog.revision();
    }

    /** A policy over the frozen catalog that uses the given resolver. */
    public static DestinationPolicy using(HostResolver resolver) {
        return new DestinationPolicy(Objects.requireNonNull(resolver, "resolver must not be null"));
    }

    /** Evaluates untrusted URL text. A syntax rejection never invokes the resolver. */
    public DestinationDecision evaluate(String url) {
        Objects.requireNonNull(url, "url must not be null");
        UrlParseResult parsed = DestinationUrl.parse(url);
        if (parsed instanceof UrlParseResult.Rejected rejected) {
            return reject(rejected.code());
        }
        return evaluate(((UrlParseResult.Parsed) parsed).url());
    }

    /** Evaluates an already analysed destination (used by 4.1-B to re-evaluate every redirect). */
    public DestinationDecision evaluate(DestinationUrl url) {
        Objects.requireNonNull(url, "url must not be null");
        DnsAnswer answer;
        try {
            answer = resolver.resolve(url.host());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return reject(RejectionCode.DNS_FAILURE);
        } catch (Exception failure) {
            // Neither the message nor the cause is kept or chained (section 15).
            return reject(RejectionCode.DNS_FAILURE);
        }
        return decide(url, answer);
    }

    private DestinationDecision decide(DestinationUrl url, DnsAnswer answer) {
        DnsAnswer.FamilyAnswer a = answer == null ? null : answer.a();
        DnsAnswer.FamilyAnswer aaaa = answer == null ? null : answer.aaaa();
        // Rule 26: FAILURE in any family; no implicit fallback to the other family.
        if (statusOf(a) == DnsAnswer.Status.FAILURE || statusOf(aaaa) == DnsAnswer.Status.FAILURE) {
            return reject(RejectionCode.DNS_FAILURE);
        }
        // Rule 27: more than 32 address records before deduplication (null or malformed entries count,
        // an absent list counts as zero), or more than 8 CNAME names in any family.
        int aRecords = a == null ? 0 : a.recordCount();
        int aaaaRecords = aaaa == null ? 0 : aaaa.recordCount();
        if (exceedsAddressRecordLimit(aRecords, aaaaRecords) || (a != null && a.cnameCount() > MAX_CNAME_NAMES)
                || (aaaa != null && aaaa.cnameCount() > MAX_CNAME_NAMES)) {
            return reject(RejectionCode.DNS_LIMIT);
        }
        // Rule 28: structurally invalid or contradictory answer.
        if (!structurallyValid(url.host().name(), a, aaaa)) {
            return reject(RejectionCode.DNS_INVALID);
        }
        // Rule 29: both families without addresses.
        DnsAnswer.Status sa = a.status();
        DnsAnswer.Status s6 = aaaa.status();
        if ((sa == DnsAnswer.Status.NODATA && s6 == DnsAnswer.Status.NODATA)
                || (sa == DnsAnswer.Status.NXDOMAIN && s6 == DnsAnswer.Status.NXDOMAIN)) {
            return reject(RejectionCode.DNS_EMPTY);
        }
        // Deduplicate by family and exact bytes; first appearance, IPv4 first, then IPv6.
        List<IpAddress> addresses = new ArrayList<>();
        appendDistinct(addresses, a, IpFamily.IPV4);
        appendDistinct(addresses, aaaa, IpFamily.IPV6);
        // Rules 30-31: every address is classified; none is dropped.
        int admissible = 0;
        for (IpAddress address : addresses) {
            if (addressPolicy.classify(address).isAdmissible()) {
                admissible++;
            }
        }
        if (admissible == 0) {
            return reject(RejectionCode.ADDRESS_FORBIDDEN);
        }
        if (admissible < addresses.size()) {
            return reject(RejectionCode.ADDRESS_MIXED);
        }
        return new DestinationDecision.Approved(url, addresses, catalogRevision);
    }

    /**
     * Rule 27 record limit over both families, before deduplication. The sum is computed in
     * {@code long}: two list sizes can exceed {@link Integer#MAX_VALUE} together, and an {@code int}
     * overflow would turn a DNS_LIMIT into a later code (finding F1 of the 4.1-A-03 review).
     */
    static boolean exceedsAddressRecordLimit(int aRecords, int aaaaRecords) {
        return (long) aRecords + aaaaRecords > MAX_ADDRESS_RECORDS;
    }

    private static DnsAnswer.Status statusOf(DnsAnswer.FamilyAnswer family) {
        return family == null ? null : family.status();
    }

    private static boolean structurallyValid(String host, DnsAnswer.FamilyAnswer a, DnsAnswer.FamilyAnswer aaaa) {
        if (!familyValid(a, IpFamily.IPV4) || !familyValid(aaaa, IpFamily.IPV6)) {
            return false;
        }
        boolean nxA = a.status() == DnsAnswer.Status.NXDOMAIN;
        boolean nx6 = aaaa.status() == DnsAnswer.Status.NXDOMAIN;
        if (nxA != nx6) {
            return false;
        }
        List<String> chainA = canonicalChain(a.cnameChain());
        List<String> chain6 = canonicalChain(aaaa.cnameChain());
        if (chainA == null || chain6 == null || !chainA.equals(chain6)) {
            return false;
        }
        List<String> sequence = new ArrayList<>();
        sequence.add(host);
        sequence.addAll(chainA);
        for (int i = 0; i < sequence.size(); i++) {
            for (int j = i + 1; j < sequence.size(); j++) {
                if (sequence.get(i).equals(sequence.get(j))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean familyValid(DnsAnswer.FamilyAnswer family, IpFamily ipFamily) {
        if (family == null || family.status() == null || family.cnameChain() == null || family.recordsView() == null) {
            return false;
        }
        for (String name : family.cnameChain()) {
            if (name == null) {
                return false;
            }
        }
        for (byte[] record : family.recordsView()) {
            if (record == null || record.length != ipFamily.byteLength()) {
                return false;
            }
        }
        boolean hasRecords = !family.recordsView().isEmpty();
        return family.status() == DnsAnswer.Status.ADDRESSES ? hasRecords : !hasRecords;
    }

    /**
     * Canonical chain (ASCII lowercase), or {@code null} if a name breaks host rules 11-18: the name
     * must use only {@code A-Z a-z 0-9 - .} and the URL parser must accept it unchanged as a host,
     * so the published rules (final dot, labels, IDN, single label, forbidden names) apply exactly.
     */
    private static List<String> canonicalChain(List<String> chain) {
        List<String> canonical = new ArrayList<>(chain.size());
        for (String name : chain) {
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                boolean ldh = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '.';
                if (!ldh) {
                    return null;
                }
            }
            UrlParseResult parsed = DestinationUrl.parse("https://" + name + "/");
            if (!(parsed instanceof UrlParseResult.Parsed accepted)) {
                return null;
            }
            String host = accepted.url().host().name();
            if (host.length() != name.length()) {
                return null;
            }
            canonical.add(host);
        }
        return canonical;
    }

    private static void appendDistinct(List<IpAddress> out, DnsAnswer.FamilyAnswer family, IpFamily ipFamily) {
        List<byte[]> seen = new ArrayList<>();
        for (byte[] record : family.recordsView()) {
            boolean duplicate = false;
            for (byte[] previous : seen) {
                duplicate |= Arrays.equals(previous, record);
            }
            if (!duplicate) {
                seen.add(record);
                out.add(IpAddress.of(ipFamily, record));
            }
        }
    }

    private DestinationDecision reject(RejectionCode code) {
        return new DestinationDecision.Rejected(code, catalogRevision);
    }

    @Override
    public String toString() {
        return "DestinationPolicy[" + catalogRevision + "]";
    }
}
