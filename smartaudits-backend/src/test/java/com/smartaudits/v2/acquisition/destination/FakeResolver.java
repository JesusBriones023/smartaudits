package com.smartaudits.v2.acquisition.destination;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Test-only resolver: deterministic, no network. It records every call and the host it received,
 * and replays scripted outcomes in order (the last one repeats). An outcome is a {@link DnsAnswer},
 * {@code null}, or an exception to throw.
 */
final class FakeResolver implements HostResolver {
    private final List<Object> outcomes;
    private final List<String> hosts = new ArrayList<>();

    private FakeResolver(List<Object> outcomes) {
        this.outcomes = outcomes;
    }

    static FakeResolver answering(Object... outcomes) {
        return new FakeResolver(Arrays.asList(outcomes));
    }

    @Override
    public DnsAnswer resolve(ValidatedHost host) throws InterruptedException {
        hosts.add(host.name());
        Object outcome = outcomes.get(Math.min(hosts.size() - 1, outcomes.size() - 1));
        if (outcome instanceof InterruptedException e) {
            throw e;
        }
        if (outcome instanceof RuntimeException e) {
            throw e;
        }
        return (DnsAnswer) outcome;
    }

    int calls() {
        return hosts.size();
    }

    List<String> hosts() {
        return Collections.unmodifiableList(hosts);
    }

    // ---- answer builders -------------------------------------------------------------------

    static byte[] v4(String text) {
        return TestAddresses.ipv4Bytes(text);
    }

    static byte[] v6(String text) {
        return TestAddresses.ipv6Bytes(text);
    }

    static List<byte[]> list(byte[]... records) {
        return Arrays.asList(records);
    }

    static DnsAnswer.FamilyAnswer addresses(byte[]... records) {
        return DnsAnswer.FamilyAnswer.of(DnsAnswer.Status.ADDRESSES, List.of(), list(records));
    }

    static DnsAnswer.FamilyAnswer addresses(List<String> chain, byte[]... records) {
        return DnsAnswer.FamilyAnswer.of(DnsAnswer.Status.ADDRESSES, chain, list(records));
    }

    static DnsAnswer.FamilyAnswer nodata() {
        return DnsAnswer.FamilyAnswer.of(DnsAnswer.Status.NODATA, List.of(), List.of());
    }

    static DnsAnswer.FamilyAnswer nodata(List<String> chain) {
        return DnsAnswer.FamilyAnswer.of(DnsAnswer.Status.NODATA, chain, List.of());
    }

    static DnsAnswer.FamilyAnswer nxdomain() {
        return DnsAnswer.FamilyAnswer.of(DnsAnswer.Status.NXDOMAIN, List.of(), List.of());
    }

    static DnsAnswer.FamilyAnswer failure() {
        return DnsAnswer.FamilyAnswer.of(DnsAnswer.Status.FAILURE, List.of(), List.of());
    }

    static DnsAnswer answer(DnsAnswer.FamilyAnswer a, DnsAnswer.FamilyAnswer aaaa) {
        return DnsAnswer.of(a, aaaa);
    }

    static byte[][] repeat(byte[] record, int times) {
        byte[][] out = new byte[times][];
        Arrays.fill(out, record);
        return out;
    }
}
