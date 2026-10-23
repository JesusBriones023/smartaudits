package com.smartaudits.v2.acquisition.destination;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Canonical serialization of the catalog, exactly as specified by
 * docs/iana/IANA-SNAPSHOT-02/SERIALIZATION.md (format smartaudits-destination-catalog-candidate/1):
 * UTF-8, LF, header lines, eleven TAB-separated columns, field-level escaping of every column and an
 * additional attribute-level escaping of attribute values (so the attrs column is escaped twice).
 * Used to prove that the Java constants reproduce the frozen hash; it performs no I/O.
 */
final class CatalogSerialization {
    static final List<String> COLUMNS = List.of("kind", "layer", "family", "network", "prefix_len", "network_hex",
            "origin", "source_id", "record_ref", "label", "attrs");

    private static final char BACKSLASH = (char) 92;
    private static final char TAB = (char) 9;
    private static final char LF = (char) 10;
    private static final char CR = (char) 13;

    private CatalogSerialization() { }

    /** Level F: backslash first, then TAB, LF and CR; nothing else is escaped. */
    static String escapeField(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == BACKSLASH) {
                out.append(BACKSLASH).append(BACKSLASH);
            } else if (c == TAB) {
                out.append(BACKSLASH).append('t');
            } else if (c == LF) {
                out.append(BACKSLASH).append('n');
            } else if (c == CR) {
                out.append(BACKSLASH).append('r');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Level A, for attribute values only: the level-F rules, then {@code ;} and {@code =}. */
    static String escapeAttributeValue(String value) {
        String fieldEscaped = escapeField(value);
        StringBuilder out = new StringBuilder(fieldEscaped.length());
        for (int i = 0; i < fieldEscaped.length(); i++) {
            char c = fieldEscaped.charAt(i);
            if (c == ';' || c == '=') {
                out.append(BACKSLASH);
            }
            out.append(c);
        }
        return out.toString();
    }

    /** {@code key=value} pairs joined by {@code ;} in ascending key order, or {@code -} for the empty set. */
    static String encodeAttributes(Map<String, String> attributes) {
        if (attributes.isEmpty()) {
            return "-";
        }
        List<String> keys = new ArrayList<>(attributes.keySet());
        keys.sort(Comparator.naturalOrder());
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            if (out.length() > 0) {
                out.append(';');
            }
            out.append(key).append('=').append(escapeAttributeValue(attributes.get(key)));
        }
        return out.toString();
    }

    static String serialize(AddressCatalog catalog) {
        StringBuilder out = new StringBuilder(64 * 1024);
        line(out, "#format" + TAB + catalog.formatId());
        line(out, "#file" + TAB + catalog.fileName());
        line(out, "#policy" + TAB + catalog.policy());
        List<AddressCatalog.Source> sources = new ArrayList<>(catalog.sources());
        sources.sort(Comparator.comparing(AddressCatalog.Source::id));
        for (AddressCatalog.Source source : sources) {
            line(out, "#source" + TAB + source.id() + TAB + "updated=" + source.updated() + TAB + "sha256="
                    + source.sha256() + TAB + source.url());
        }
        line(out, "#columns" + TAB + String.join(String.valueOf(TAB), COLUMNS));
        for (CatalogEntry entry : catalog.entries()) {
            List<String> fields = new ArrayList<>(COLUMNS.size());
            for (String scalar : entry.scalarFields()) {
                fields.add(escapeField(scalar));
            }
            fields.add(escapeField(encodeAttributes(entry.attributes())));
            line(out, String.join(String.valueOf(TAB), fields));
        }
        return out.toString();
    }

    static byte[] canonicalBytes(AddressCatalog catalog) {
        return serialize(catalog).getBytes(StandardCharsets.UTF_8);
    }

    static String sha256(AddressCatalog catalog) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonicalBytes(catalog));
            return HexFormat.of().withUpperCase().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static void line(StringBuilder out, String line) {
        out.append(line).append(LF);
    }
}
