package com.smartaudits.v2.acquisition.destination;

import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * One row of the frozen catalog. It keeps the exact decoded text of the ten scalar columns and the
 * attribute set, so that the canonical serialization can be rebuilt byte for byte, plus the typed
 * view used for classification: a canonical {@link IpPrefix} for address rows, a name for name rows.
 */
public final class CatalogEntry {
    /** Row kind (column {@code kind}). */
    public enum Kind { ADDR, NAME }

    /** Catalog layer (column {@code layer}); {@code NOT_FORBIDDEN} exists only for names. */
    public enum Layer { POSITIVE, NEGATIVE, NOT_FORBIDDEN }

    /** Row origin (column {@code origin}): an IANA registry record or a SmartAudits product rule. */
    public enum Origin { IANA, PRODUCT }

    private static final Pattern DECIMAL = Pattern.compile("0|[1-9][0-9]{0,2}");
    private static final Pattern NAME = Pattern.compile("[a-z0-9]+(-+[a-z0-9]+)*([.][a-z0-9]+(-+[a-z0-9]+)*)*");
    private static final Pattern LOWER_HEX = Pattern.compile("[0-9a-f]+");
    private static final Pattern ATTRIBUTE_KEY = Pattern.compile("[a-z]+");
    private static final String NONE = "-";

    private final Kind kind;
    private final Layer layer;
    private final Origin origin;
    private final String familyText;
    private final String network;
    private final String prefixLengthText;
    private final String networkHex;
    private final String sourceId;
    private final String recordRef;
    private final String label;
    private final SortedMap<String, String> attributes;
    private final IpPrefix prefix;

    private CatalogEntry(Kind kind, Layer layer, Origin origin, String familyText, String network,
                         String prefixLengthText, String networkHex, String sourceId, String recordRef,
                         String label, SortedMap<String, String> attributes, IpPrefix prefix) {
        this.kind = kind;
        this.layer = layer;
        this.origin = origin;
        this.familyText = familyText;
        this.network = network;
        this.prefixLengthText = prefixLengthText;
        this.networkHex = networkHex;
        this.sourceId = sourceId;
        this.recordRef = recordRef;
        this.label = label;
        this.attributes = attributes;
        this.prefix = prefix;
    }

    /**
     * Builds an entry from the decoded columns {@code kind} to {@code label} plus attribute key/value
     * pairs in strictly ascending key order. Used only by the generated catalog data.
     */
    static CatalogEntry fromFields(String kind, String layer, String family, String network, String prefixLength,
                                   String networkHex, String origin, String sourceId, String recordRef,
                                   String label, String... attributePairs) {
        Objects.requireNonNull(attributePairs, "attributes must not be null");
        for (String field : List.of(kind, layer, family, network, prefixLength, networkHex, origin, sourceId,
                recordRef, label)) {
            Objects.requireNonNull(field, "catalog field must not be null");
        }
        Kind parsedKind = Kind.valueOf(kind);
        Layer parsedLayer = Layer.valueOf(layer);
        Origin parsedOrigin = Origin.valueOf(origin);
        if (sourceId.isEmpty() || recordRef.isEmpty() || label.isEmpty()) {
            throw invalid();
        }
        if ((parsedOrigin == Origin.PRODUCT) != sourceId.equals("ADR-0002")) {
            throw invalid();
        }
        IpPrefix parsedPrefix = null;
        if (parsedKind == Kind.ADDR) {
            if (parsedLayer == Layer.NOT_FORBIDDEN || !DECIMAL.matcher(prefixLength).matches()) {
                throw invalid();
            }
            IpFamily ipFamily = IpFamily.fromCatalogCode(family);
            if (networkHex.length() != ipFamily.byteLength() * 2 || !LOWER_HEX.matcher(networkHex).matches()) {
                throw invalid();
            }
            parsedPrefix = IpPrefix.of(ipFamily, HexFormat.of().parseHex(networkHex), Integer.parseInt(prefixLength));
        } else {
            if (parsedLayer == Layer.POSITIVE || !family.equals(NONE) || !prefixLength.equals(NONE)
                    || !networkHex.equals(NONE) || !NAME.matcher(network).matches()) {
                throw invalid();
            }
        }
        if (attributePairs.length % 2 != 0) {
            throw invalid();
        }
        TreeMap<String, String> map = new TreeMap<>();
        String previous = null;
        for (int i = 0; i < attributePairs.length; i += 2) {
            String key = Objects.requireNonNull(attributePairs[i], "attribute key must not be null");
            String value = Objects.requireNonNull(attributePairs[i + 1], "attribute value must not be null");
            if (!ATTRIBUTE_KEY.matcher(key).matches() || (previous != null && key.compareTo(previous) <= 0)) {
                throw invalid();
            }
            map.put(key, value);
            previous = key;
        }
        return new CatalogEntry(parsedKind, parsedLayer, parsedOrigin, family, network, prefixLength, networkHex,
                sourceId, recordRef, label, Collections.unmodifiableSortedMap(map), parsedPrefix);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid catalog entry");
    }

    public Kind kind() {
        return kind;
    }

    public Layer layer() {
        return layer;
    }

    public Origin origin() {
        return origin;
    }

    /** Registry or rule identifier (column {@code source_id}). */
    public String sourceId() {
        return sourceId;
    }

    /** Source record reference (column {@code record_ref}). */
    public String recordRef() {
        return recordRef;
    }

    /** Official name or rule description (column {@code label}). */
    public String label() {
        return label;
    }

    /** Unmodifiable attribute set in ascending key order (column {@code attrs}). */
    public Map<String, String> attributes() {
        return attributes;
    }

    /** Canonical network text as frozen (address rows) or canonical name (name rows). */
    public String network() {
        return network;
    }

    public Optional<IpPrefix> prefix() {
        return Optional.ofNullable(prefix);
    }

    public Optional<String> name() {
        return kind == Kind.NAME ? Optional.of(network) : Optional.empty();
    }

    /** The ten scalar columns, {@code kind} to {@code label}, exactly as frozen (decoded). */
    List<String> scalarFields() {
        return List.of(kind.name(), layer.name(), familyText, network, prefixLengthText, networkHex, origin.name(),
                sourceId, recordRef, label);
    }

    @Override
    public String toString() {
        return "CatalogEntry[" + kind + " " + layer + " " + origin + " " + recordRef + "]";
    }
}
