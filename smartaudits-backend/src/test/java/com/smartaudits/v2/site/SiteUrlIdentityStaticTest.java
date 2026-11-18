package com.smartaudits.v2.site;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Structural guards of docs/F4_1C_SITE_URL_IDENTITY.md, not proofs. The identity package is pure
 * Java over the destination parser: no network, name resolution, locale, I/O, persistence, framework
 * or transport, and nothing in production uses it yet. The whole text of each source is scanned,
 * comments included, on purpose: it is stricter.
 */
class SiteUrlIdentityStaticTest {
    private static final Path MAIN = Paths.get("src", "main", "java");
    private static final Path SOURCES = MAIN.resolve(Paths.get("com", "smartaudits", "v2", "site"));
    private static final List<String> EXPECTED_SOURCES = List.of("SiteUrlIdentity.java", "SiteUrlIdentityResult.java",
            "SiteUrlRejection.java");
    private static final Set<String> ALLOWED_IMPORTS = Set.of(
            "com.smartaudits.v2.acquisition.destination.DestinationUrl",
            "com.smartaudits.v2.acquisition.destination.UrlParseResult",
            "java.util.Objects",
            "java.util.Optional");
    private static final List<String> FORBIDDEN = List.of(
            "java.net", "java.io", "java.nio", "java.text", "java.time", "java.sql", "java.security", "java.lang.reflect",
            "javax.", "jakarta.", "org.springframework", "org.hibernate", "com.fasterxml", "lombok",
            "URLConnection", "URLDecoder", "URLEncoder", "InetAddress", "Socket", "HttpClient", "Channel", "IDN",
            "equalsIgnoreCase", "toLowerCase", "toUpperCase", "Locale", "Normalizer", "Collator", "compareTo",
            "getResource", "ClassLoader", "System.", "Runtime", "ProcessBuilder", "Thread", "Serializable", "Logger",
            "printStackTrace", "getMessage", "Random", "Instant", "Clock", "MessageDigest", "Pattern", "regex",
            "DestinationPolicy", "DestinationDecision", "HostResolver", "ValidatedHost", "DnsAnswer", "IpAddress",
            "AddressPolicy", "AddressCatalog", "RejectionCode", "Captura", "Transporte", "capture", "bootstrap",
            "com.smartaudits.model", "com.smartaudits.v2.model", "com.smartaudits.service", "com.smartaudits.config",
            "com.smartaudits.repository", "com.smartaudits.controller", "com.smartaudits.security", "Organization",
            "Repository", "Entity", "@Service", "@Component", "@Configuration", "@Bean", "@Autowired");

    private static List<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(f -> f.toString().endsWith(".java")).sorted().toList();
        }
    }

    @Test void thePackageHasExactlyItsThreeSources() throws IOException {
        assertEquals(EXPECTED_SOURCES, javaFiles(SOURCES).stream().map(f -> f.getFileName().toString()).toList());
    }

    @Test void sourcesImportOnlyTheDestinationParserAndJavaUtil() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(SOURCES)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String text = line.strip();
                if (text.startsWith("import ")) {
                    String imported = text.substring("import ".length(), text.length() - 1).strip();
                    if (!ALLOWED_IMPORTS.contains(imported)) {
                        violations.add(file.getFileName() + ": " + imported);
                    }
                } else if (!text.startsWith("package ") && (text.contains("java.") || text.contains("com.smartaudits"))) {
                    // No fully qualified name may bypass the import list.
                    violations.add(file.getFileName() + ": " + text);
                }
            }
        }
        assertEquals(List.of(), violations);
    }

    @Test void sourcesUseNoNetworkLocaleIoPersistenceFrameworkOrTransportApi() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(SOURCES)) {
            String code = Files.readString(file, StandardCharsets.UTF_8);
            for (String token : FORBIDDEN) {
                if (code.contains(token)) {
                    violations.add(file.getFileName() + ": " + token);
                }
            }
            // ASCII only, so no invisible or look-alike character can hide in the contract.
            assertTrue(code.chars().allMatch(c -> c == 10 || c == 13 || (c >= 32 && c < 127)), file + " is not plain ASCII");
        }
        assertEquals(List.of(), violations);
    }

    @Test void theScanDetectsAForbiddenApiAndAForeignImport() {
        String sample = "import java.net.InetAddress;\nclass X { String s = text.toLowerCase(); }";
        assertTrue(FORBIDDEN.stream().anyMatch(sample::contains));
        assertTrue(sample.contains("toLowerCase") && FORBIDDEN.contains("toLowerCase"));
        assertFalse(ALLOWED_IMPORTS.contains("java.net.InetAddress"));
    }

    /**
     * This block adds the identity only. No service, entity, controller, configuration or transport
     * uses it yet: a future caller must be added here on purpose, with its tenancy and its
     * persistence reviewed (docs/F4_1C_SITE_URL_IDENTITY.md, "Planned persistence").
     */
    @Test void nothingInProductionUsesTheIdentityYet() throws IOException {
        List<Path> all = javaFiles(MAIN);
        assertTrue(all.size() > 100, "the scan must see the production sources");
        List<String> users = new ArrayList<>();
        for (Path file : all) {
            if (file.startsWith(SOURCES)) {
                continue;
            }
            String code = Files.readString(file, StandardCharsets.UTF_8);
            if (code.contains("SiteUrlIdentity") || code.contains("SiteUrlRejection") || code.contains("smartaudits.v2.site")) {
                users.add(file.getFileName().toString());
            }
        }
        assertEquals(List.of(), users);
        try (Stream<Path> resources = Files.walk(Paths.get("src", "main", "resources"))) {
            for (Path resource : resources.filter(Files::isRegularFile).toList()) {
                String name = resource.getFileName().toString();
                if (name.endsWith(".properties") || name.endsWith(".sql") || name.endsWith(".yml") || name.endsWith(".xml")) {
                    String text = Files.readString(resource, StandardCharsets.UTF_8);
                    assertFalse(text.contains("SiteUrlIdentity") || text.contains("site_url_identit"), resource.toString());
                }
            }
        }
    }

    @Test void theIdentityTypesCarryNoFrameworkOrPersistenceAnnotation() {
        for (Class<?> type : List.of(SiteUrlIdentity.class, SiteUrlIdentityResult.class, SiteUrlIdentityResult.Accepted.class,
                SiteUrlIdentityResult.Rejected.class, SiteUrlRejection.class)) {
            assertEquals(0, type.getAnnotations().length, type + " is annotated");
            assertEquals("com.smartaudits.v2.site", type.getPackageName());
        }
    }
}
