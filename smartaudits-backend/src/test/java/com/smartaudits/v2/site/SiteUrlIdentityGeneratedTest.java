package com.smartaudits.v2.site;

import com.smartaudits.v2.acquisition.destination.DestinationUrl;
import com.smartaudits.v2.acquisition.destination.UrlParseResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.smartaudits.v2.site.SiteUrlIdentityMatrixTest.ch;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exhaustive bounded checks of docs/F4_1C_SITE_URL_IDENTITY.md against an oracle written from the
 * contract, not from the implementation: a text is a Site identity exactly when the destination
 * profile accepts it and, ignoring ASCII case, it is its own origin, optionally followed by the
 * default port and by one slash. Deterministic: no randomness, clock or network.
 */
class SiteUrlIdentityGeneratedTest {
    private int rejectedCount;
    /** Canonical form to the number of accepted texts that produced it. */
    private final Map<String, Integer> acceptedByForm = new HashMap<>();

    private int acceptedCount() {
        return acceptedByForm.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Checks one text against the contract and counts the outcome. */
    private void check(String input) {
        SiteUrlIdentityResult result = SiteUrlIdentity.parse(input);
        UrlParseResult destination = DestinationUrl.parse(input);
        if (destination instanceof UrlParseResult.Rejected refused) {
            SiteUrlIdentityResult.Rejected rejection = assertInstanceOf(SiteUrlIdentityResult.Rejected.class, result, input);
            assertEquals(SiteUrlRejection.DESTINATION_SYNTAX, rejection.reason(), input);
            assertEquals(refused.code(), rejection.destinationRejection().orElseThrow().code(), input);
            rejectedCount++;
            return;
        }
        DestinationUrl url = ((UrlParseResult.Parsed) destination).url();
        String origin = url.scheme() + "://" + url.host().name();
        String port = url.scheme().equals("https") ? "443" : "80";
        boolean bareOrigin = Set.of(origin, origin + "/", origin + ":" + port, origin + ":" + port + "/")
                .contains(input.toLowerCase(Locale.ROOT));
        if (bareOrigin) {
            SiteUrlIdentityResult.Accepted ok = assertInstanceOf(SiteUrlIdentityResult.Accepted.class, result, input);
            assertEquals(origin, ok.identity().canonicalForm(), input);
            assertEquals(url.scheme(), ok.identity().scheme(), input);
            assertEquals(url.host().name(), ok.identity().host(), input);
            acceptedByForm.merge(origin, 1, Integer::sum);
            return;
        }
        SiteUrlIdentityResult.Rejected rejection = assertInstanceOf(SiteUrlIdentityResult.Rejected.class, result, input);
        assertTrue(rejection.destinationRejection().isEmpty(), input);
        SiteUrlRejection expected = !url.path().equals("/") ? SiteUrlRejection.PATH
                : url.query().isPresent() ? SiteUrlRejection.QUERY
                : SiteUrlRejection.FRAGMENT;
        assertEquals(expected, rejection.reason(), input);
        if (expected == SiteUrlRejection.FRAGMENT) {
            assertTrue(input.contains("#"), "the only remaining reason is a real fragment: " + input);
        }
        rejectedCount++;
    }

    /** Every string over {@code alphabet} up to {@code maxLength}, appended to {@code prefix}. */
    private void checkEveryTail(String prefix, String alphabet, int maxLength) {
        List<String> level = List.of("");
        for (int length = 0; length <= maxLength; length++) {
            List<String> next = new ArrayList<>(level.size() * alphabet.length());
            for (String tail : level) {
                check(prefix + tail);
                if (length < maxLength) {
                    for (int i = 0; i < alphabet.length(); i++) {
                        next.add(tail + alphabet.charAt(i));
                    }
                }
            }
            level = next;
        }
    }

    @Test void everyShortTailAfterAnOriginIsAcceptedOnlyAsOneOfTheFourSpellings() {
        // 111 111 texts for each scheme: every tail of up to five of these ten characters.
        checkEveryTail("https://a.bc", "/?#:43.a%@", 5);
        assertEquals(111_111, acceptedCount() + rejectedCount);
        assertEquals(4, acceptedByForm.get("https://a.bc"), "exactly '', '/', ':443' and ':443/'");
        checkEveryTail("HTTP://A.BC", "/?#:80.a%@", 5);
        assertEquals(222_222, acceptedCount() + rejectedCount);
        assertEquals(4, acceptedByForm.get("http://a.bc"), "exactly '', '/', ':80' and ':80/'");
        // Every other accepted text is a longer host written in full: a different Site, never a.bc.
        for (Map.Entry<String, Integer> accepted : acceptedByForm.entrySet()) {
            assertTrue(accepted.getKey().matches("https?://a[.]bc[a0-9.]*"), accepted.getKey());
            assertTrue(accepted.getValue() <= 4, accepted.getKey() + " has " + accepted.getValue() + " spellings");
        }
        assertTrue(acceptedByForm.containsKey("https://a.bca"));
        assertTrue(acceptedByForm.containsKey("https://a.bc.a"));
        assertTrue(acceptedByForm.size() > 20, "accepted forms: " + acceptedByForm.size());
        assertTrue(rejectedCount > 200_000, "rejected: " + rejectedCount);
    }

    @Test void everySingleEditOfABareOriginStillObeysTheContract() {
        List<String> characters = new ArrayList<>();
        for (char c = 0; c < 128; c++) {
            characters.add(String.valueOf(c));
        }
        for (int codePoint : new int[]{0x85, 0xA0, 0xAD, 0xDF, 0xE9, 0x130, 0x131, 0x17F, 0x301, 0x430, 0x445, 0x200B, 0x200D,
                0x202E, 0x2024, 0x212A, 0x3002, 0x3000, 0xFE52, 0xFEFF, 0xFF0E, 0xFF0F, 0xFF1A, 0xFF45, 0xFF61, 0xD800, 0xDC00,
                0x1D41A}) {
            characters.add(ch(codePoint));
        }
        List<String> spellings = List.of("https://example.com", "https://example.com/", "https://example.com:443",
                "https://example.com:443/", "http://www.example.com", "http://www.example.com:80/", "HTTPS://EXAMPLE.COM:443/");
        int texts = 0;
        for (String spelling : spellings) {
            for (int i = 0; i <= spelling.length(); i++) {
                for (String character : characters) {
                    check(spelling.substring(0, i) + character + spelling.substring(i));
                    texts++;
                    if (i < spelling.length()) {
                        check(spelling.substring(0, i) + character + spelling.substring(i + 1));
                        texts++;
                    }
                }
                if (i < spelling.length()) {
                    check(spelling.substring(0, i) + spelling.substring(i + 1));
                    texts++;
                }
            }
        }
        assertEquals(texts, acceptedCount() + rejectedCount);
        assertTrue(texts > 50_000, "texts checked: " + texts);
        assertTrue(acceptedCount() > 1_000 && rejectedCount > 30_000, acceptedCount() + " accepted, " + rejectedCount + " rejected");
        // Every accepted edit is some host spelled in full: an edit can change the Site, never hide part of the text.
        for (String form : acceptedByForm.keySet()) {
            assertTrue(form.matches("https?://[a-z0-9.-]+"), form);
        }
    }
}
