package com.smartaudits.v2.acquisition.destination;

/**
 * DNS abstraction of ADR-0002 section 10. It receives only a {@link ValidatedHost}, which can be
 * obtained only from an accepted {@link DestinationUrl}: never free text, IP literals, single-label or
 * forbidden names. One call per evaluation returns the complete answer for both A and AAAA.
 * 4.1-A provides no production implementation; tests use fake resolvers. The real resolver is a
 * decision of 4.1-B (absolute FQDN, no search domains, hosts file, mDNS, LLMNR or NetBIOS, limits,
 * timeouts and cancellation).
 */
public interface HostResolver {

    /**
     * Returns the A and AAAA answer for the host. Any exception, an interruption, a {@code null}
     * result or a FAILURE family never approves a destination (the policy maps them to DNS codes).
     */
    DnsAnswer resolve(ValidatedHost host) throws InterruptedException;
}
