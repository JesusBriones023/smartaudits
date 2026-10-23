package com.smartaudits.v2.acquisition.destination;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rule 18 of ADR-0002 on canonical hosts: equal to a forbidden name or a subdomain of one, compared
 * by whole labels, never by text suffix. The URL parser (rules 1-17) is not part of this block.
 */
class ForbiddenNameTest {
    private static final AddressCatalog CATALOG = AddressCatalog.snapshot();

    @Test void forbiddenNamesAreExactlyTheFortyEightSnapshotNames() {
        assertEquals(List.of("10.in-addr.arpa", "16.172.in-addr.arpa", "168.192.in-addr.arpa", "17.172.in-addr.arpa",
                "170.0.0.192.in-addr.arpa", "171.0.0.192.in-addr.arpa", "18.172.in-addr.arpa", "19.172.in-addr.arpa",
                "20.172.in-addr.arpa", "21.172.in-addr.arpa", "22.172.in-addr.arpa", "23.172.in-addr.arpa",
                "24.172.in-addr.arpa", "25.172.in-addr.arpa", "254.169.in-addr.arpa", "26.172.in-addr.arpa",
                "27.172.in-addr.arpa", "28.172.in-addr.arpa", "29.172.in-addr.arpa", "30.172.in-addr.arpa",
                "31.172.in-addr.arpa", "6tisch.arpa", "8.e.f.ip6.arpa", "9.e.f.ip6.arpa", "a.e.f.ip6.arpa", "alt",
                "arpa", "b.e.f.ip6.arpa", "corp", "eap-noob.arpa", "eap.arpa", "example", "home", "home.arpa",
                "internal", "intranet", "invalid", "ipv4only.arpa", "lan", "local", "localdomain", "localhost", "mail",
                "onion", "private", "resolver.arpa", "service.arpa", "test"),
                CATALOG.forbiddenNames().stream().sorted().collect(Collectors.toList()));
        assertFalse(CATALOG.forbiddenNames().contains("example.com"));
    }

    /** ADR-0002 rows for rules 17 and 18, plus the whole-TLD and product rules. */
    @ParameterizedTest
    @ValueSource(strings = {"localhost", "intranet", "example", "app.localhost", "printer.local",
            "metadata.google.internal", "router.home.arpa", "home.arpa", "x.arpa", "a.b.c.arpa",
            "1.0.0.127.in-addr.arpa", "a.test", "deep.sub.invalid", "hidden.onion", "x.alt", "nas.home", "dc.corp",
            "smtp.mail", "pc.lan", "wiki.intranet", "db.private", "host.localdomain", "foo.example"})
    void forbiddenHostsAndTheirSubdomains(String host) {
        assertTrue(CATALOG.isForbiddenName(host), host);
    }

    /** Label boundaries: a forbidden name inside another label or in a non-final position does not match. */
    @ParameterizedTest
    @ValueSource(strings = {"mylocalhost.com", "localhost.com", "notlocal", "local.example.com",
            "localhost.example.com", "xlocalhost", "localhostx", "arpa.example.com", "notarpa", "myarpa.net",
            "corp.example.com", "intranet-portal.com", "x-localhost.com", "home.example.org", "example.com",
            "example.net", "example.org", "www.example.com", "sub.example.com", "testing", "test-site.com",
            "internals.com", "onionsoup.org"})
    void namesThatOnlyShareATextSuffixOrPrefixAreNotForbidden(String host) {
        assertFalse(CATALOG.isForbiddenName(host), host);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ".localhost", "localhost.", "a..localhost", "LOCALHOST", "Printer.local",
            "local host", "local_host", "app.localhost:443", "*.local", "caf\u00e9.local"})
    void nonCanonicalHostsAreContractErrors(String host) {
        assertThrows(IllegalArgumentException.class, () -> CATALOG.isForbiddenName(host));
    }

    @Test void nullHostIsAContractError() {
        NullPointerException e = assertThrows(NullPointerException.class, () -> CATALOG.isForbiddenName(null));
        assertEquals("host must not be null", e.getMessage());
    }
}
