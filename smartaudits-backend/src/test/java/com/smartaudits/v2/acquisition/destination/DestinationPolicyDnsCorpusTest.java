package com.smartaudits.v2.acquisition.destination;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.smartaudits.v2.acquisition.destination.FakeResolver.*;
import static com.smartaudits.v2.acquisition.destination.RejectionCode.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * ADR-0002 section 10, "Corpus DNS (normativo)" and "Errores simultaneos", written literally, plus the
 * resolver-call contract of section 19 (spy). P4a, P4b and P6 are admissible addresses of the frozen
 * snapshot (8.8.8.8, 1.1.1.1 and 2a00:1450::1); every answer comes from a fake resolver.
 */
class DestinationPolicyDnsCorpusTest {
    static final String HOST = "a.example.com";
    static final String URL = "https://" + HOST + "/";
    static final byte[] P4A = v4("8.8.8.8");
    static final byte[] P4B = v4("1.1.1.1");
    static final byte[] P6 = v6("2a00:1450::1");

    static DestinationDecision evaluate(Object outcome) {
        FakeResolver resolver = FakeResolver.answering(outcome);
        DestinationDecision decision = DestinationPolicy.using(resolver).evaluate(URL);
        assertEquals(1, resolver.calls(), "the resolver must be called exactly once");
        assertEquals(List.of(HOST), resolver.hosts());
        return decision;
    }

    static RejectionCode rejection(Object outcome) {
        DestinationDecision decision = evaluate(outcome);
        assertTrue(decision instanceof DestinationDecision.Rejected, "expected a rejection, got " + decision);
        DestinationDecision.Rejected rejected = (DestinationDecision.Rejected) decision;
        assertEquals("IANA-SNAPSHOT-02", rejected.catalogRevision());
        return rejected.code();
    }

    static List<String> approved(Object outcome) {
        DestinationDecision decision = evaluate(outcome);
        assertTrue(decision instanceof DestinationDecision.Approved, "expected an approval, got " + decision);
        DestinationDecision.Approved approved = (DestinationDecision.Approved) decision;
        assertEquals("IANA-SNAPSHOT-02", approved.catalogRevision());
        assertEquals(HOST, approved.host().name());
        assertEquals("https", approved.scheme());
        assertEquals(443, approved.port());
        assertEquals(URL, approved.url().canonicalForm());
        return approved.addresses().stream().map(a -> TestAddresses.canonicalText(a.bytes())).collect(Collectors.toList());
    }

    static List<String> chain(String... names) {
        return Arrays.asList(names);
    }

    static List<String> hops(int n) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            out.add("h" + i + ".example.com");
        }
        return out;
    }

    // ---- approved rows ---------------------------------------------------------------------

    @Test void p4aWithAaaaNodataIsApproved() {
        assertEquals(List.of("8.8.8.8"), approved(answer(addresses(P4A), nodata())));
    }

    @Test void aNodataWithP6IsApproved() {
        assertEquals(List.of("2a00:1450::1"), approved(answer(nodata(), addresses(P6))));
    }

    @Test void duplicatesAreRemovedAndIpv4ComesFirst() {
        assertEquals(List.of("8.8.8.8", "2a00:1450::1"), approved(answer(addresses(P4A, P4A), addresses(P6))));
    }

    @Test void eightCnameHopsInBothFamiliesAreApproved() {
        assertEquals(List.of("8.8.8.8", "1.1.1.1"), approved(answer(addresses(hops(8), P4A, P4B), nodata(hops(8)))));
    }

    @Test void thirtyTwoRecordsEqualToP4aAreApprovedAsOne() {
        assertEquals(List.of("8.8.8.8"), approved(answer(addresses(repeat(P4A, 32)), nodata())));
    }

    // ---- address rows ----------------------------------------------------------------------

    @Test void privateIpv4IsForbidden() {
        assertEquals(ADDRESS_FORBIDDEN, rejection(answer(addresses(v4("10.0.0.1")), nodata())));
    }

    @Test void cloudMetadataInBothFamiliesIsForbidden() {
        assertEquals(ADDRESS_FORBIDDEN, rejection(answer(addresses(v4("169.254.169.254")), addresses(v6("fd00:ec2::254")))));
    }

    @Test void admissibleAndPrivateIpv4InBothOrdersIsMixed() {
        assertEquals(ADDRESS_MIXED, rejection(answer(addresses(P4A, v4("10.0.0.1")), nodata())));
        assertEquals(ADDRESS_MIXED, rejection(answer(addresses(v4("10.0.0.1"), P4A), nodata())));
    }

    @Test void admissibleIpv4WithMappedLoopbackInAaaaIsMixed() {
        assertEquals(ADDRESS_MIXED, rejection(answer(addresses(P4A), addresses(v6("::ffff:7f00:1")))));
    }

    // ---- DNS rows --------------------------------------------------------------------------

    @Test void failureInAnyFamilyIsDnsFailureWithoutFallback() {
        assertEquals(DNS_FAILURE, rejection(answer(addresses(P4A), failure())));
        assertEquals(DNS_FAILURE, rejection(answer(failure(), nodata())));
        assertEquals(DNS_FAILURE, rejection(answer(failure(), addresses(P6))));
    }

    @Test void resolverExceptionIsDnsFailure() {
        assertEquals(DNS_FAILURE, rejection(new IllegalStateException("resolver broke")));
    }

    @Test void thirtyThreeRecordsAreDnsLimitEvenWhenAllEqual() {
        assertEquals(DNS_LIMIT, rejection(answer(addresses(repeat(P4A, 20)), addresses(repeat(P6, 13)))));
        assertEquals(DNS_LIMIT, rejection(answer(addresses(repeat(P4A, 33)), nodata())));
    }

    @Test void nineCnameHopsAreDnsLimit() {
        assertEquals(DNS_LIMIT, rejection(answer(addresses(hops(9), P4A), nodata(hops(9)))));
        assertEquals(DNS_LIMIT, rejection(answer(addresses(hops(9), P4A), nodata(hops(8)))));
    }

    /**
     * Regression for finding F1 of the 4.1-A-03 review: the rule-27 sum must not overflow. Two family
     * sizes whose int sum wraps to a negative value are still over the limit of 32. Checked on the
     * arithmetic directly, without materializing lists of a billion entries.
     */
    @Test void recordLimitSumNeverOverflows() {
        assertTrue(DestinationPolicy.exceedsAddressRecordLimit(1073741824, 1073741824));
        assertTrue(DestinationPolicy.exceedsAddressRecordLimit(Integer.MAX_VALUE, 1));
        assertTrue(DestinationPolicy.exceedsAddressRecordLimit(1, Integer.MAX_VALUE));
        assertTrue(DestinationPolicy.exceedsAddressRecordLimit(Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertTrue(DestinationPolicy.exceedsAddressRecordLimit(2147483615, 33));
        assertTrue(DestinationPolicy.exceedsAddressRecordLimit(16, 17));
        assertTrue(DestinationPolicy.exceedsAddressRecordLimit(0, 33));
        assertFalse(DestinationPolicy.exceedsAddressRecordLimit(32, 0));
        assertFalse(DestinationPolicy.exceedsAddressRecordLimit(16, 16));
        assertFalse(DestinationPolicy.exceedsAddressRecordLimit(0, 0));
    }

    @Test void recordsOfTheWrongLengthAreDnsInvalid() {
        assertEquals(DNS_INVALID, rejection(answer(addresses(P6), nodata())));
        assertEquals(DNS_INVALID, rejection(answer(nodata(), addresses(P4A))));
        assertEquals(DNS_INVALID, rejection(answer(addresses(new byte[3]), nodata())));
        assertEquals(DNS_INVALID, rejection(answer(addresses(new byte[0]), nodata())));
    }

    @Test void addressesStatusWithoutRecordsIsDnsInvalid() {
        assertEquals(DNS_INVALID, rejection(answer(addresses(), nodata())));
        assertEquals(DNS_INVALID, rejection(answer(addresses(P4A), addresses())));
    }

    @Test void nxdomainCombinedWithAnotherStatusIsDnsInvalid() {
        assertEquals(DNS_INVALID, rejection(answer(nxdomain(), addresses(P6))));
        assertEquals(DNS_INVALID, rejection(answer(nxdomain(), nodata())));
        assertEquals(DNS_INVALID, rejection(answer(addresses(P4A), nxdomain())));
    }

    @Test void nodataOrNxdomainWithRecordsIsDnsInvalid() {
        assertEquals(DNS_INVALID, rejection(answer(DnsAnswer.FamilyAnswer.of(DnsAnswer.Status.NODATA, List.of(), list(P4A)), nodata())));
        assertEquals(DNS_INVALID, rejection(answer(DnsAnswer.FamilyAnswer.of(DnsAnswer.Status.NXDOMAIN, List.of(), list(P4A)), nxdomain())));
    }

    /** Each cycle and its acyclic control differ only in the repeated name; all names are valid and allowed. */
    static Stream<Arguments> cnameCyclesAndControls() {
        return Stream.of(
                arguments(chain("b.example.com", "a.example.com"), true),
                arguments(chain("b.example.com", "c.example.com"), false),
                arguments(chain("b.example.com", "c.example.com", "b.example.com"), true),
                arguments(chain("b.example.com", "c.example.com", "d.example.com"), false),
                arguments(chain("b.example.com", "A.EXAMPLE.COM"), true),
                arguments(chain("b.example.com", "C.EXAMPLE.COM"), false));
    }

    @ParameterizedTest(name = "{0} cycle={1}")
    @MethodSource("cnameCyclesAndControls")
    void cnameCyclesAreDnsInvalidAndTheirControlsAreApproved(List<String> cnames, boolean cycle) {
        DnsAnswer answer = answer(addresses(cnames, P4A), nodata(cnames));
        if (cycle) {
            assertEquals(DNS_INVALID, rejection(answer));
        } else {
            assertEquals(List.of("8.8.8.8"), approved(answer));
        }
    }

    @Test void cnameNamesBreakingHostRulesAreDnsInvalid() {
        for (String bad : new String[]{"b.example.com.", "db.internal", "printer.local", "x.localhost", "intranet",
                "exa_mple.com", "-a.example.com", "a..example.com", "xn--bcher-kva.example", "1.2.3.4", "",
                "b.example.com/x", "b.example.com:443", "user@b.example.com", "b%2eexample.com",
                "b" + (char) 0xFC + ".example", "foo.example"}) {
            List<String> cnames = chain(bad);
            assertEquals(DNS_INVALID, rejection(answer(addresses(cnames, P4A), nodata(cnames))), bad);
        }
    }

    @Test void differentChainsBetweenFamiliesAreDnsInvalid() {
        assertEquals(DNS_INVALID, rejection(answer(addresses(chain("b.example.com"), P4A), nodata(chain("c.example.com")))));
        assertEquals(DNS_INVALID, rejection(answer(addresses(chain("b.example.com"), P4A), nodata())));
        assertEquals(List.of("8.8.8.8"), approved(answer(addresses(chain("B.example.com"), P4A), nodata(chain("b.EXAMPLE.com")))));
    }

    @Test void resolverReturningNullIsDnsInvalid() {
        assertEquals(DNS_INVALID, rejection(null));
    }

    @Test void emptyAnswersAreDnsEmpty() {
        assertEquals(DNS_EMPTY, rejection(answer(nodata(), nodata())));
        assertEquals(DNS_EMPTY, rejection(answer(nxdomain(), nxdomain())));
        assertEquals(DNS_EMPTY, rejection(answer(nodata(chain("b.example.com")), nodata(chain("b.example.com")))));
    }

    // ---- simultaneous errors ---------------------------------------------------------------

    @Test void failurePrecedesLimit() {
        assertEquals(DNS_FAILURE, rejection(answer(addresses(repeat(P4A, 33)), failure())));
    }

    @Test void limitPrecedesInvalid() {
        byte[][] records = repeat(P4A, 33);
        records[7] = P6;
        assertEquals(DNS_LIMIT, rejection(answer(addresses(records), nodata())));
        List<String> nineWithCycle = chain("b.example.com", "c.example.com", "b.example.com", "d.example.com",
                "e.example.com", "f.example.com", "g.example.com", "h.example.com", "i.example.com");
        assertEquals(DNS_LIMIT, rejection(answer(addresses(nineWithCycle, P4A), nodata(nineWithCycle))));
    }

    @Test void invalidPrecedesAddressClassification() {
        assertEquals(DNS_INVALID, rejection(answer(addresses(P4A, v4("10.0.0.1"), new byte[5]), nodata())));
        assertEquals(DNS_INVALID, rejection(answer(DnsAnswer.FamilyAnswer.of(DnsAnswer.Status.ADDRESSES, List.of(),
                Arrays.asList(P4A, null)), nodata())));
    }

    @Test void absentFamiliesListsAndStatusesAreDnsInvalidWithoutException() {
        assertEquals(DNS_INVALID, rejection(answer(null, nodata())));
        assertEquals(DNS_INVALID, rejection(answer(addresses(P4A), null)));
        assertEquals(DNS_INVALID, rejection(answer(DnsAnswer.FamilyAnswer.of(null, List.of(), list(P4A)), nodata())));
        assertEquals(DNS_INVALID, rejection(answer(DnsAnswer.FamilyAnswer.of(DnsAnswer.Status.ADDRESSES, null, list(P4A)), nodata())));
        assertEquals(DNS_INVALID, rejection(answer(DnsAnswer.FamilyAnswer.of(DnsAnswer.Status.ADDRESSES, List.of(), null), nodata())));
        assertEquals(DNS_INVALID, rejection(answer(addresses(Arrays.asList("b.example.com", null), P4A), nodata(Arrays.asList("b.example.com", null)))));
    }

    @Test void absentListCountsAsZeroRecordsForTheLimit() {
        assertEquals(DNS_INVALID, rejection(answer(addresses(repeat(P4A, 32)),
                DnsAnswer.FamilyAnswer.of(DnsAnswer.Status.NODATA, List.of(), null))));
    }

    // ---- resolver call contract (spy) --------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"", "https://localhost/", "https://127.0.0.1/", "ftp://example.com/", "https://example/",
            "https://user@example.com/", "https://example.com:8080/", "https://[::1]/", "https://example.com/../",
            "https://printer.local/", "https://metadata.google.internal/"})
    void syntaxRejectionsNeverInvokeTheResolver(String url) {
        FakeResolver spy = FakeResolver.answering(answer(addresses(P4A), nodata()));
        DestinationDecision decision = DestinationPolicy.using(spy).evaluate(url);
        assertTrue(decision instanceof DestinationDecision.Rejected);
        assertEquals(0, spy.calls());
    }

    @Test void theResolverReceivesTheCanonicalHostOncePerEvaluation() {
        FakeResolver spy = FakeResolver.answering(answer(addresses(P4A), nodata()));
        DestinationPolicy policy = DestinationPolicy.using(spy);
        policy.evaluate("HTTPS://WWW.Example.COM:443/Path?q#f");
        policy.evaluate("http://www.example.com/other");
        assertEquals(List.of("www.example.com", "www.example.com"), spy.hosts());
    }

    @Test void changingAnswersAreEvaluatedIndependentlyWithOneQueryEach() {
        FakeResolver changing = FakeResolver.answering(answer(addresses(P4A), nodata()),
                answer(addresses(v4("127.0.0.1")), nodata()), answer(addresses(P4A), addresses(v6("::1"))),
                answer(failure(), nodata()), answer(nodata(), addresses(P6)));
        DestinationPolicy policy = DestinationPolicy.using(changing);
        assertTrue(policy.evaluate(URL) instanceof DestinationDecision.Approved);
        assertEquals(ADDRESS_FORBIDDEN, ((DestinationDecision.Rejected) policy.evaluate(URL)).code());
        assertEquals(ADDRESS_MIXED, ((DestinationDecision.Rejected) policy.evaluate(URL)).code());
        assertEquals(DNS_FAILURE, ((DestinationDecision.Rejected) policy.evaluate(URL)).code());
        assertTrue(policy.evaluate(URL) instanceof DestinationDecision.Approved);
        assertEquals(5, changing.calls());
    }

    @Test void interruptedResolverIsDnsFailureAndRestoresTheInterruptFlag() {
        FakeResolver interrupted = FakeResolver.answering(new InterruptedException("stop"));
        try {
            DestinationDecision decision = DestinationPolicy.using(interrupted).evaluate(URL);
            assertEquals(DNS_FAILURE, ((DestinationDecision.Rejected) decision).code());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test void documentationNamesGetNoException() {
        FakeResolver spy = FakeResolver.answering(answer(addresses(P4A), nodata()));
        assertEquals(HOST_FORBIDDEN, ((DestinationDecision.Rejected) DestinationPolicy.using(spy).evaluate("https://example/")).code());
        assertEquals(0, spy.calls());
        assertTrue(DestinationPolicy.using(spy).evaluate("https://example.com/") instanceof DestinationDecision.Approved);
        FakeResolver empty = FakeResolver.answering(answer(nodata(), nodata()));
        assertEquals(DNS_EMPTY, ((DestinationDecision.Rejected) DestinationPolicy.using(empty).evaluate("https://example.com/")).code());
    }

    @Test void parsedUrlsAreEvaluatedDirectlyAndNullsAreContractErrors() {
        DestinationUrl url = ((UrlParseResult.Parsed) DestinationUrl.parse("http://example.org/x")).url();
        FakeResolver spy = FakeResolver.answering(answer(addresses(P4B), addresses(P6)));
        DestinationDecision.Approved approved = (DestinationDecision.Approved) DestinationPolicy.using(spy).evaluate(url);
        assertSame(url, approved.url());
        assertEquals("http", approved.scheme());
        assertEquals(80, approved.port());
        assertEquals(List.of(IpAddress.ipv4(P4B), IpAddress.ipv6(P6)), approved.addresses());
        assertThrows(NullPointerException.class, () -> DestinationPolicy.using(null));
        assertThrows(NullPointerException.class, () -> DestinationPolicy.using(spy).evaluate((String) null));
        assertThrows(NullPointerException.class, () -> DestinationPolicy.using(spy).evaluate((DestinationUrl) null));
    }
}
