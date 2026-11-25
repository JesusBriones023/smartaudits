package com.smartaudits.v2.model;

import com.smartaudits.v2.acquisition.destination.RejectionCode;
import com.smartaudits.v2.site.SiteUrlIdentity;
import com.smartaudits.v2.site.SiteUrlIdentityResult;
import com.smartaudits.v2.site.SiteUrlRejection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Offline contract of V10 (docs/F4_1C_SITE_URL_IDENTITY.md, "Increment 1"). The four CHECK
 * predicates are reproduced here with java.util.regex as an oracle and compared with the parser:
 * the database accepts exactly the canonical forms the parser can produce, except names refused
 * only by the forbidden-name catalog (rule 18), which the schema deliberately does not encode.
 * The real MariaDB behaviour of the same cases is proved by SiteUrlIdentityMariaDbProbe.
 */
class SiteUrlIdentityV10SchemaTest {
    static final Path MIGRATION = Paths.get("src", "main", "resources", "db", "migration", "V10__create_site_url_identities.sql");
    static final String ALPHABET = "[^a-z0-9.:/-]";
    static final String GRAMMAR = "^https?://[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?([.][a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$";
    static final String RLDH = "[/.][a-z0-9][a-z0-9-]--";
    static final String NUMERIC = "[.]([0-9]+|0x[0-9a-f]*)$";
    static final List<String> CHECKS = List.of("ck_site_url_identities_alphabet", "ck_site_url_identities_scheme_length",
            "ck_site_url_identities_grammar", "ck_site_url_identities_labels");
    private static final Pattern ALPHABET_P = Pattern.compile(ALPHABET);
    private static final Pattern GRAMMAR_P = Pattern.compile(GRAMMAR);
    private static final Pattern RLDH_P = Pattern.compile(RLDH);
    private static final Pattern NUMERIC_P = Pattern.compile(NUMERIC);

    static String label(char c, int length) { return String.valueOf(c).repeat(length); }
    /** Host of exactly {@code length} characters: labels of 63 joined by dots, ending in a letter label. */
    static String host(int length) {
        StringBuilder host = new StringBuilder();
        char c = 'a';
        while (host.length() < length) {
            if (host.length() > 0) host.append('.');
            int remaining = length - host.length();
            int size = remaining > 63 ? (remaining == 64 ? 62 : 63) : remaining;
            host.append(label(c++, size));
        }
        assertEquals(length, host.length());
        return host.toString();
    }

    /** Parser-accepted canonical forms that V10 must store. */
    static final List<String> ACCEPTED = List.of(
            "http://a.b", "https://a.b", "https://example.com", "https://www.example.com", "http://shop.example.co.uk",
            "https://1a.com", "https://123.com.es", "https://a-b.c-d", "https://0x.com", "https://a.0xg", "https://a--b.com",
            "https://ab-c--d.com", "https://" + label('a', 63) + ".com", "http://" + host(253), "https://" + host(253));
    /** Refused only by the forbidden-name catalog: the schema is catalog-independent and stores them. */
    static final List<String> CATALOG_ONLY = List.of("https://shop.local", "https://a.test", "https://www.example",
            "https://x.localhost", "https://b.onion", "https://10.in-addr.arpa");
    /** Non-canonical forms and the first V10 CHECK, in declaration order, that refuses each one. */
    static final Map<String, String> REJECTED = rejected();

    private static Map<String, String> rejected() {
        String alphabet = CHECKS.get(0), scheme = CHECKS.get(1), grammar = CHECKS.get(2), labels = CHECKS.get(3);
        Map<String, String> cases = new LinkedHashMap<>();
        for (String text : List.of("https://Example.com", "HTTPS://example.com", "HTTP://a.b", "https://EXAMPLE.COM",
                "https://example.com?q", "https://example.com#f", " https://example.com", "https://example.com ",
                "https://example.com\n", "https://example.com\u0000", "https://a.b\t", "https://ex\u00e1mple.com",
                "https://[::1]", "https://user@example.com", "https://a_b.com", "https://%41.com")) cases.put(text, alphabet);
        for (String text : List.of("", "ftp://example.com", "http:/example.com", "https:example.com", "https:/a.b",
                "http://" + host(254))) cases.put(text, scheme);
        for (String text : List.of("https://example.com/", "http://a.b/", "https://example.com:443", "https://a.b:",
                "https://example.com/path", "https://", "https://example", "https://.example.com", "https://example..com",
                "https://example.com.", "https://-a.com", "https://a-.com", "https://" + label('a', 64) + ".com")) cases.put(text, grammar);
        for (String text : List.of("https://xn--bcher-kva.com", "https://ab--c.com", "https://www.ab--c.com", "https://a---a.com",
                "https://example.123", "https://a.0x1f", "https://a.0x", "https://127.0.0.1")) cases.put(text, labels);
        return Collections.unmodifiableMap(cases);
    }

    /** The CHECK that refuses {@code text} first, or {@code null} when all four accept it. MariaDB REGEXP is a search. */
    static String firstFailingCheck(String text) {
        if (ALPHABET_P.matcher(text).find()) return CHECKS.get(0);
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        boolean scheme = (text.startsWith("http://") && bytes <= 260) || (text.startsWith("https://") && bytes <= 261);
        if (!scheme) return CHECKS.get(1);
        if (!GRAMMAR_P.matcher(text).find()) return CHECKS.get(2);
        if (RLDH_P.matcher(text).find() || NUMERIC_P.matcher(text).find()) return CHECKS.get(3);
        return null;
    }

    private static String migration() throws IOException {
        // A Windows checkout may use CRLF; Flyway and the frozen-hash scripts also compare LF text.
        return Files.readString(MIGRATION, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test void theMigrationIsOneAdditiveCreateTableWithTheApprovedShape() throws IOException {
        String sql = migration();
        assertTrue(sql.chars().allMatch(c -> c == 10 || (c >= 32 && c < 127)), "ASCII only");
        assertTrue(sql.startsWith("CREATE TABLE site_url_identities ("));
        assertEquals(1, count(sql, "CREATE"), "one statement");
        assertEquals(1, count(sql, ";"), "one statement");
        assertTrue(sql.strip().endsWith("ENGINE=InnoDB\n  DEFAULT CHARACTER SET=utf8mb4\n  COLLATE=utf8mb4_unicode_520_ci;"));
        for (String forbidden : List.of("ALTER", "DROP", "INSERT", "RENAME", "TRUNCATE", "CASCADE", "SET NULL", "SET DEFAULT",
                "TRIGGER", "SELECT", "AUTO_INCREMENT", "GENERATED", "DEFAULT CURRENT", "CHARSET", "/*", "#")) {
            assertFalse(sql.contains(forbidden), forbidden);
        }
        assertEquals(1, count(sql, "COLLATE"), "only the table default collation; no column collation");
        assertEquals(2, count(sql, "DELETE") + count(sql, "UPDATE"), "only ON DELETE / ON UPDATE");
        for (String fragment : List.of("site_id BIGINT NOT NULL,", "organization_id BIGINT NOT NULL,",
                "canonical_origin VARBINARY(261) NOT NULL,", "PRIMARY KEY (site_id),",
                "UNIQUE KEY uq_site_url_identities_org_origin (organization_id, canonical_origin),",
                "KEY ix_site_url_identities_org_site (organization_id, site_id),",
                "CONSTRAINT fk_site_url_identities_site\n        FOREIGN KEY (organization_id, site_id) REFERENCES sites (organization_id, id)\n"
                        + "        ON DELETE RESTRICT ON UPDATE RESTRICT,")) {
            assertTrue(sql.contains(fragment), fragment);
        }
        assertEquals(4, count(sql, "CHECK ("));
        for (String name : CHECKS) assertEquals(1, count(sql, "CONSTRAINT " + name + "\n"), name);
        assertEquals(1, count(sql, "REFERENCES"), "only the composite reference to sites");
    }

    @Test void theOracleUsesExactlyTheMigrationPatterns() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("CHECK (canonical_origin NOT REGEXP '" + ALPHABET + "')"));
        assertTrue(sql.contains("CHECK ((canonical_origin LIKE 'http://%' AND OCTET_LENGTH(canonical_origin) <= 260)\n"
                + "            OR (canonical_origin LIKE 'https://%' AND OCTET_LENGTH(canonical_origin) <= 261))"));
        assertTrue(sql.contains("CHECK (canonical_origin REGEXP '" + GRAMMAR + "')"));
        assertTrue(sql.contains("CHECK (canonical_origin NOT REGEXP '" + RLDH + "'\n            AND canonical_origin NOT REGEXP '" + NUMERIC + "')"));
        assertFalse(sql.contains("\\"), "no escape: the harness lexer and MariaDB see the same pattern");
        assertTrue(ALPHABET.indexOf('\n') < 0 && !ALPHABET.contains("\\"));
    }

    @Test void everyAcceptedFormIsStoredAndEveryNonCanonicalFormRefusedByTheExpectedCheck() {
        for (String text : ACCEPTED) {
            SiteUrlIdentityResult parsed = SiteUrlIdentity.parse(text);
            assertInstanceOf(SiteUrlIdentityResult.Accepted.class, parsed, text);
            assertEquals(text, ((SiteUrlIdentityResult.Accepted) parsed).identity().canonicalForm(), "the fixtures are canonical");
            assertNull(firstFailingCheck(text), text);
        }
        assertEquals(260, ACCEPTED.get(ACCEPTED.size() - 2).length());
        assertEquals(261, ACCEPTED.get(ACCEPTED.size() - 1).length());
        for (var rejected : REJECTED.entrySet()) {
            assertEquals(rejected.getValue(), firstFailingCheck(rejected.getKey()), rejected.getKey());
            // None of them is a canonical form: either the parser refuses it, or it canonicalizes to another text.
            SiteUrlIdentityResult parsed = SiteUrlIdentity.parse(rejected.getKey());
            if (parsed instanceof SiteUrlIdentityResult.Accepted accepted) {
                assertNotEquals(rejected.getKey(), accepted.identity().canonicalForm(), rejected.getKey());
            }
        }
        for (String text : CATALOG_ONLY) {
            assertNull(firstFailingCheck(text), text);
            assertEquals(RejectionCode.HOST_FORBIDDEN, destinationCode(SiteUrlIdentity.parse(text)), text);
        }
    }

    /**
     * Every host over {a, 0, x, -, .} up to seven characters (https) or five (http), so labels, dots,
     * hyphens, R-LDH, numeric and 0x last labels all occur. No forbidden catalog name can be spelled
     * with these characters, so the parser and the four CHECKs must agree on every text.
     */
    @Test void theChecksAndTheParserAgreeOnEveryGeneratedHost() {
        char[] alphabet = {'a', '0', 'x', '-', '.'};
        int checked = 0;
        int accepted = 0;
        for (String scheme : List.of("https://", "http://")) {
            int max = scheme.equals("https://") ? 7 : 5;
            List<String> hosts = new ArrayList<>(List.of(""));
            for (int length = 1; length <= max; length++) {
                List<String> next = new ArrayList<>();
                for (String prefix : hosts) for (char c : alphabet) next.add(prefix + c);
                hosts = next;
                for (String host : hosts) {
                    String text = scheme + host;
                    boolean parser = SiteUrlIdentity.parse(text) instanceof SiteUrlIdentityResult.Accepted;
                    boolean database = firstFailingCheck(text) == null;
                    assertEquals(parser, database, text);
                    if (parser) {
                        accepted++;
                        assertEquals(text, ((SiteUrlIdentityResult.Accepted) SiteUrlIdentity.parse(text)).identity().canonicalForm());
                    }
                    checked++;
                }
            }
        }
        assertEquals(97_655 + 3_905, checked);
        assertTrue(accepted > 1_000, "the corpus must contain many accepted hosts: " + accepted);
    }

    @Test void theChecksAndTheParserAgreeAtEveryLengthBound() {
        for (String scheme : List.of("http://", "https://")) {
            for (int length = 240; length <= 256; length++) {
                String text = scheme + host(length);
                boolean parser = SiteUrlIdentity.parse(text) instanceof SiteUrlIdentityResult.Accepted;
                assertEquals(length <= 253, parser, text);
                assertEquals(parser, firstFailingCheck(text) == null, text);
            }
            for (int size = 61; size <= 65; size++) {
                String text = scheme + label('q', size) + ".com";
                boolean parser = SiteUrlIdentity.parse(text) instanceof SiteUrlIdentityResult.Accepted;
                assertEquals(size <= 63, parser, text);
                assertEquals(parser, firstFailingCheck(text) == null, text);
            }
        }
    }

    private static RejectionCode destinationCode(SiteUrlIdentityResult result) {
        SiteUrlIdentityResult.Rejected rejected = assertInstanceOf(SiteUrlIdentityResult.Rejected.class, result);
        assertEquals(SiteUrlRejection.DESTINATION_SYNTAX, rejected.reason());
        return rejected.destinationRejection().orElseThrow().code();
    }

    private static int count(String text, String token) {
        int count = 0;
        for (int i = text.indexOf(token); i >= 0; i = text.indexOf(token, i + token.length())) count++;
        return count;
    }
}
