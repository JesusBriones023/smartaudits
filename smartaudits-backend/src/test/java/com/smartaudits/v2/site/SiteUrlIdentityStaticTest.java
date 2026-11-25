package com.smartaudits.v2.site;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.BreakTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ContinueTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.LabeledStatementTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.PackageTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Structural guards of docs/F4_1C_SITE_URL_IDENTITY.md, not proofs. The identity package is pure
 * Java over the destination parser: no network, name resolution, locale, I/O, persistence, framework
 * or transport. Outside it, only the approved V10 mapping and migration may name it (Increment 1).
 * The whole text of each source is scanned, comments included, on purpose: it is stricter.
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
     * Increment 1 (docs/F4_1C_SITE_URL_IDENTITY.md, "Increment 1") persists the identity. Exactly two
     * production files outside the pure package may name it: the mapping and the V10 migration, by
     * exact path. Nothing may use the mapping, so no service, repository, controller, endpoint,
     * configuration or transport consumes the identity yet. A future caller must be added here on
     * purpose, with its tenancy, authorization and transaction reviewed.
     */
    @Test void onlyTheApprovedMappingAndMigrationUseTheIdentity() throws IOException {
        Map<String, String> java = readJava(javaFiles(MAIN));
        assertTrue(java.size() > 100, "the scan must see the production sources");
        assertTrue(java.containsKey(APPROVED_MAPPING), "the approved mapping must exist: " + APPROVED_MAPPING);
        Map<String, String> resources;
        try (Stream<Path> files = Files.walk(RESOURCES)) {
            resources = read(files.filter(Files::isRegularFile).sorted().toList());
        }
        assertTrue(resources.containsKey(APPROVED_MIGRATION), "the approved migration must exist: " + APPROVED_MIGRATION);
        assertEquals(List.of(), identityUseViolations(java, resources));
        // Both readings of the real mapping agree, and javac accepted every production source.
        JavacReading mapping = javacReadings(Map.of(APPROVED_MAPPING, java.get(APPROVED_MAPPING))).get(APPROVED_MAPPING);
        assertEquals(List.of(), mapping.errors());
        assertEquals(codeFindings(javacMappingFindings(APPROVED_MAPPING, mapping)),
                codeFindings(textMappingFindings(APPROVED_MAPPING, java.get(APPROVED_MAPPING))));
    }

    @Test void theAllowlistAcceptsExactlyTheApprovedMappingAndMigration() {
        assertEquals(List.of(), identityUseViolations(Map.of(APPROVED_MAPPING, GOOD_MAPPING, OTHER_SOURCE, "class Other { }"),
                Map.of(APPROVED_MIGRATION, "CREATE TABLE site_url_identities (site_id BIGINT);", OTHER_MIGRATION, "CREATE TABLE sites (id BIGINT);")));
        // The pure package itself is not a consumer and is guarded by the tests above.
        assertEquals(List.of(), identityUseViolations(Map.of(PURE_SOURCE, "class SiteUrlIdentity { }"), Map.of()));
    }

    @Test void theAllowlistRejectsEveryOtherConsumerAndEveryWideningOfTheMapping() {
        String controller = "src/main/java/com/smartaudits/controller/SiteController.java";
        String sameNameElsewhere = "src/main/java/com/smartaudits/service/SiteUrlIdentityRecord.java";
        String service = "src/main/java/com/smartaudits/v2/model/SiteCreationService.java";
        Map<String, List<String>> javaCases = new LinkedHashMap<>();
        javaCases.put("controller importing the identity", violations(Map.of(controller,
                "import com.smartaudits.v2.site.SiteUrlIdentity;\n@RestController class SiteController { }")));
        javaCases.put("fully qualified use without import", violations(Map.of(OTHER_SOURCE,
                "class Other { Object o = com.smartaudits.v2.site.SiteUrlRejection.PATH; }")));
        javaCases.put("mapping file name in another package", violations(Map.of(sameNameElsewhere, GOOD_MAPPING)));
        javaCases.put("consumer of the mapping", violations(Map.of(APPROVED_MAPPING, GOOD_MAPPING, service,
                "class SiteCreationService { SiteUrlIdentityRecord record; }")));
        assertEquals(List.of(controller + ": uses the Site URL identity outside the allowlist"), javaCases.get("controller importing the identity"));
        assertEquals(List.of(OTHER_SOURCE + ": uses the Site URL identity outside the allowlist"), javaCases.get("fully qualified use without import"));
        assertEquals(List.of(sameNameElsewhere + ": uses the Site URL identity outside the allowlist"), javaCases.get("mapping file name in another package"));
        assertEquals(List.of(service + ": uses the Site URL identity outside the allowlist"), javaCases.get("consumer of the mapping"));

        // Each widened import, and every forbidden token it also brings, in the order of MAPPING_FORBIDDEN.
        Map<String, List<String>> widenings = new LinkedHashMap<>();
        widenings.put("java.net.InetAddress", List.of("java.net", "InetAddress"));
        widenings.put("com.smartaudits.v2.acquisition.destination.DestinationUrl", List.of("DestinationUrl", "acquisition"));
        widenings.put("com.smartaudits.v2.site.SiteUrlIdentityResult", List.of("SiteUrlIdentityResult"));
        widenings.put("org.springframework.stereotype.Service", List.of());
        widenings.put("com.smartaudits.repository.SiteRepository", List.of("Repository"));
        widenings.put("jakarta.persistence.OneToOne", List.of());
        for (var widening : widenings.entrySet()) {
            String mapping = GOOD_MAPPING.replace("import java.util.Objects;", "import java.util.Objects;\nimport " + widening.getKey() + ";");
            List<String> expected = new ArrayList<>(List.of(APPROVED_MAPPING + ": import outside the mapping allowlist: " + widening.getKey()));
            widening.getValue().forEach(token -> expected.add(APPROVED_MAPPING + ": forbidden in the mapping: " + token));
            assertEquals(expected, violations(Map.of(APPROVED_MAPPING, mapping)), widening.getKey());
        }
        for (String token : List.of("SiteUrlIdentity.parse(", "@RestController", "@RequestMapping", "@Service", "@Component",
                "@Transactional", "Repository", "HttpClient", "Socket", "DestinationPolicy", "HostResolver", "Captura", "Transporte")) {
            String mapping = GOOD_MAPPING.replace("class SiteUrlIdentityRecord {", "class SiteUrlIdentityRecord { /* " + token + " */");
            assertEquals(List.of(APPROVED_MAPPING + ": forbidden in the mapping: " + token), violations(Map.of(APPROVED_MAPPING, mapping)), token);
        }

        String laterMigration = "src/main/resources/db/migration/V11__site_url_identities_index.sql";
        String properties = "src/main/resources/application.properties";
        String sameMigrationElsewhere = "src/main/resources/other/V10__create_site_url_identities.sql";
        assertEquals(List.of(laterMigration + ": names the Site URL identity outside the allowlist"), identityUseViolations(Map.of(),
                Map.of(APPROVED_MIGRATION, "CREATE TABLE site_url_identities (site_id BIGINT);",
                        laterMigration, "CREATE INDEX ix ON site_url_identities (site_id);")));
        assertEquals(List.of(properties + ": names the Site URL identity outside the allowlist"), identityUseViolations(Map.of(),
                Map.of(properties, "smartaudits.site.identity=com.smartaudits.v2.site.SiteUrlIdentity")));
        assertEquals(List.of(sameMigrationElsewhere + ": names the Site URL identity outside the allowlist"), identityUseViolations(Map.of(),
                Map.of(sameMigrationElsewhere, "CREATE TABLE site_url_identities (site_id BIGINT);")));
    }

    /**
     * Review finding MEDIUM-1: the first version matched exact substrings only, so a fully qualified
     * annotation and an ordinarily spaced parser call passed. These are the reviewer's own inputs.
     */
    @Test void theOriginalBypassesAreNowDetected() {
        assertMappingViolation(annotate("@org.springframework.stereotype.Service"),
                "annotation outside the mapping allowlist: org.springframework.stereotype.Service",
                "forbidden qualified name in the mapping code: org.springframework.stereotype.Service");
        assertMappingViolation(annotate("@org.springframework.web.bind.annotation.RestController"),
                "annotation outside the mapping allowlist: org.springframework.web.bind.annotation.RestController",
                "forbidden qualified name in the mapping code: org.springframework.web.bind.annotation.RestController");
        assertMappingViolation(body("Object x = SiteUrlIdentity . parse(input);"), "parser reference in the mapping code");
    }

    /**
     * Second review, MEDIUM-1, the reviewer's two reproductions. A line comment ended only at LF, so
     * a CR produced by a unicode escape hid the annotation after it; and the first three quotes
     * after a text block's opening were taken as its end even when a backslash escaped the first,
     * which hid the parser call after the block's real end. Both readings now see each of them.
     */
    @Test void theSecondReviewBypassesAreNowDetected() {
        String unicodeCr = GOOD_MAPPING.replace("@Entity public class",
                "// note" + BS_U + "000d@org.springframework.stereotype.Service\n@Entity public class");
        assertBothReadings("unicode CR ends the comment", unicodeCr,
                "annotation outside the mapping allowlist: org.springframework.stereotype.Service",
                "forbidden qualified name in the mapping code: org.springframework.stereotype.Service");
        String escapedQuotes = body("String a = " + TQ + "\n    \\" + TQ + "\n    " + TQ + ";\n"
                + "    Object x = SiteUrlIdentity . parse(input);\n    String b = " + TQ + "\n    end\n    " + TQ + ";");
        assertBothReadings("escaped quote in a text block", escapedQuotes, "parser reference in the mapping code");
    }

    /**
     * Java ends a line comment at CR, at LF and at CR LF (JLS 3.4 and 3.7), after unicode
     * translation (JLS 3.3), so a terminator produced by an escape counts. F: a forbidden
     * annotation after the terminator; G: a spaced parser call after it.
     */
    @Test void lineCommentsEndAtEveryJavaLineTerminator() {
        Map<String, String> terminators = new LinkedHashMap<>();
        terminators.put("A. CR", "\r");
        terminators.put("B. LF", "\n");
        terminators.put("C. CR LF", "\r\n");
        terminators.put("D. unicode CR", BS_U + "000d");
        terminators.put("D. unicode CR, upper case and several u", BS_U + "uuu000D");
        terminators.put("E. unicode LF", BS_U + "000a");
        terminators.put("E. unicode CR then unicode LF", BS_U + "000d" + BS_U + "000a");
        // javac pairs a backslash produced by an escape with the next raw one, so the escape after them is live.
        terminators.put("javac pairing: unicode backslash, raw backslash, unicode CR", BS_U + "005c" + "\\" + BS_U + "000d");
        for (var terminator : terminators.entrySet()) {
            String annotated = GOOD_MAPPING.replace("@Entity public class",
                    "// note" + terminator.getValue() + "@org.springframework.stereotype.Service\n@Entity public class");
            assertBothReadings("F " + terminator.getKey(), annotated,
                    "annotation outside the mapping allowlist: org.springframework.stereotype.Service",
                    "forbidden qualified name in the mapping code: org.springframework.stereotype.Service");
            assertBothReadings("G " + terminator.getKey(), body("// note" + terminator.getValue() + "Object x = SiteUrlIdentity . parse(input);"),
                    "parser reference in the mapping code");
        }
        // Whole files with CR-only and CR LF line endings.
        for (String eol : List.of("\r", "\r\n")) {
            String file = GOOD_MAPPING.replace("@Entity public class", "// note\n@org.springframework.stereotype.Service\n@Entity public class")
                    .replace("\n", eol);
            assertBothReadings("file with " + printable(eol) + " line endings", file,
                    "annotation outside the mapping allowlist: org.springframework.stereotype.Service");
            assertBothReadings("call in a file with " + printable(eol) + " line endings",
                    body("// note\nObject x = SiteUrlIdentity . parse(input);").replace("\n", eol), "parser reference in the mapping code");
        }
    }

    /**
     * Unicode translation is not uniform: an escape whose backslash is itself escaped, or that
     * follows a backslash produced by an escape, is plain text, and its CR does not end the comment.
     * javac agrees with both readings that the annotation stays inside the comment.
     */
    @Test void escapedUnicodeEscapesDoNotEndALineComment() {
        for (String notATerminator : List.of("\\" + BS_U + "000d", "\\\\\\" + BS_U + "000a", BS_U + "005c" + "u000d",
                BS_U + "005c" + BS_U + "005c" + "\\" + BS_U + "000d")) {
            String mapping = GOOD_MAPPING.replace("@Entity public class",
                    "// note" + notATerminator + "@org.springframework.stereotype.Service\n@Entity public class");
            JavacReading reading = javacReadings(Map.of(APPROVED_MAPPING, mapping)).get(APPROVED_MAPPING);
            assertEquals(List.of(), reading.errors(), printable(notATerminator));
            assertEquals(Set.of(), codeFindings(javacMappingFindings(APPROVED_MAPPING, reading)), printable(notATerminator));
            assertEquals(Set.of(), codeFindings(textMappingFindings(APPROVED_MAPPING, mapping)), printable(notATerminator));
            assertEquals(List.of(), violations(Map.of(APPROVED_MAPPING, mapping)), printable(notATerminator));
        }
        // A unicode LF inside a string literal is a line terminator there too: javac refuses it, and so does the guard.
        String broken = body("String s = \"a" + BS_U + "000a\";");
        assertTrue(violations(Map.of(APPROVED_MAPPING, broken)).stream().anyMatch(v -> v.contains("javac 17 rejects it")),
                () -> violations(Map.of(APPROVED_MAPPING, broken)).toString());
    }

    /**
     * Text blocks (JLS 3.10.6): the opening delimiter is three quotes, optional spaces, tabs or form
     * feeds and a line terminator; the block ends at the first three quotes that no backslash
     * escapes. Code after the real end is code, and comment-like text inside the block is not a comment.
     */
    @Test void textBlocksEndOnlyAtTheirRealClosingDelimiter() {
        String call = "Object x = SiteUrlIdentity . parse(input);";
        Map<String, String> calls = new LinkedHashMap<>();
        calls.put("escaped triple quote, then a second block", "String a = " + TQ + "\n    \\" + TQ + "\n    " + TQ + ";\n    " + call
                + "\n    String b = " + TQ + "\n    end\n    " + TQ + ";");
        calls.put("escaped quote right before the closing delimiter", "String a = " + TQ + "\n    x\\\"" + TQ + "; " + call);
        calls.put("escaped backslash, then escaped quote and two quotes", "String a = " + TQ + "\n    x\\\\\\\"\"\"\n    " + TQ + "; " + call);
        calls.put("genuine termination after an escaped backslash", "String a = " + TQ + "\n    x\\\\" + TQ + "; " + call);
        calls.put("genuine termination", "String a = " + TQ + "\n    x\n    " + TQ + ";\n    " + call);
        calls.put("parser call on the closing line", "Object a = " + TQ + "\n    x" + TQ + "; " + call);
        calls.put("comment-like text inside the blocks", "String a = " + TQ + "\n    /* not a comment\n    // nor this\n    " + TQ + ";\n    "
                + call + "\n    String b = " + TQ + "\n    */\n    " + TQ + ";");
        calls.put("unusual valid whitespace and escapes", "String a = " + TQ + " \t\f\r\n    one \\\r\n    two\\s\\t\\\"\"\r    " + TQ + ";\r    " + call);
        // A backslash and a quote made by escapes, then two raw quotes: an escaped quote and two quotes of content.
        calls.put("quotes made by unicode escapes", "String a = " + TQ + "\n    " + BS_U + "005c" + BS_U + "0022" + "\"\"\n    " + TQ + "; " + call);
        for (var c : calls.entrySet()) {
            assertBothReadings(c.getKey(), body(c.getValue()), "parser reference in the mapping code");
        }
        assertBothReadings("fully qualified annotation after a text block", members("String a = " + TQ + "\n    \\" + TQ + "\n    " + TQ + ";\n"
                        + "    @org.springframework.context.annotation.Bean Object bean() { return null; }\n    String b = " + TQ + "\n    " + TQ + ";"),
                "annotation outside the mapping allowlist: org.springframework.context.annotation.Bean",
                "forbidden qualified name in the mapping code: org.springframework.context.annotation.Bean");
        // Inside a block, annotation-like and call-like text is text: no violation at all.
        assertEquals(List.of(), violations(Map.of(APPROVED_MAPPING, body("String a = " + TQ + "\n    @Deprecated parse(input) // x\n    \\" + TQ + " /*\n    " + TQ + ";"))));
    }

    /**
     * javac drops identifier-ignorable characters (JLS 3.8: most C0 and C1 controls and format
     * characters) from identifiers, so they could otherwise split a name in two. The octal escape in
     * a string literal is only visible to javac's own reading, which is why the guard has two.
     */
    @Test void ignorableCharactersAndLiteralEscapesCannotHideAName() {
        String nul = String.valueOf((char) 0);
        assertBothReadings("unicode NUL inside parse", body("Object x = SiteUrlIdentity.pa" + BS_U + "0000rse(input);"),
                "parser reference in the mapping code");
        assertBothReadings("raw NUL inside parse", body("Object x = SiteUrlIdentity.pa" + nul + "rse(input);"), "parser reference in the mapping code");
        assertBothReadings("BEL inside the annotation", annotate("@org.springframework.stereotype.Ser" + BS_U + "0007vice"),
                "annotation outside the mapping allowlist: org.springframework.stereotype.Service");
        assertBothReadings("zero width space inside a package", body("Object a = java.n" + BS_U + "200bet.InetAddress.getLoopbackAddress();"),
                "forbidden qualified name in the mapping code: java.net.InetAddress.getLoopbackAddress");
        for (String code : List.of("class Other { SiteUrl" + BS_U + "0000Identity identity; }", "class Other { SiteUrl" + nul + "Identity identity; }",
                "class Other { Object o = Class.forName(\"com.smartaudits.v2.\\163ite.SiteUrl\\111dentity\"); }")) {
            assertEquals(List.of(OTHER_SOURCE + ": uses the Site URL identity outside the allowlist"), violations(Map.of(OTHER_SOURCE, code)), printable(code));
        }
        assertFalse(textUses("class Other { Object o = Class.forName(\"com.smartaudits.v2.\\163ite.SiteUrl\\111dentity\"); }"),
                "the octal escape is expected to need javac's reading");
    }

    @Test void annotationsAreCheckedWhateverTheirSpacingQualificationOrEncoding() {
        for (String annotation : List.of("@ Service", "@\n\tService", "@\r\n  Service", "@/* hidden */Service", "@// hidden\nService",
                "@" + BS_U + "0053ervice", BS_U + "0040Service", BS_U + "uuu0040Service")) {
            assertMappingViolation(annotate(annotation), "annotation outside the mapping allowlist: Service");
        }
        assertMappingViolation(annotate("@org . springframework\n\t. stereotype . Service"),
                "annotation outside the mapping allowlist: org.springframework.stereotype.Service");
        assertMappingViolation(annotate("@jakarta.transaction.Transactional"),
                "annotation outside the mapping allowlist: jakarta.transaction.Transactional",
                "forbidden qualified name in the mapping code: jakarta.transaction.Transactional");
        // A same-package meta-annotation needs no import, so only an annotation allowlist can see it.
        assertMappingViolation(annotate("@SiteStereotype"), "annotation outside the mapping allowlist: SiteStereotype");
        assertMappingViolation(annotate("@interface Nested { }\n@Entity"), "annotation outside the mapping allowlist: interface");
        assertMappingViolation(annotate("@OneToOne"), "annotation outside the mapping allowlist: OneToOne");
    }

    @Test void parserCallsAreDetectedWithAnyLegalJavaSpacing() {
        for (String call : List.of("SiteUrlIdentity . parse(input)", "SiteUrlIdentity\n\t.parse\n(input)", "SiteUrlIdentity\t.\tparse\t(input)",
                "SiteUrlIdentity./* c */parse(input)", "SiteUrlIdentity.// c\nparse(input)", "SiteUrlIdentity::parse",
                "SiteUrlIdentity :: parse", "com.smartaudits.v2.site.SiteUrlIdentity.parse(input)", "parse(input)",
                "SiteUrlIdentity." + BS_U + "0070arse(input)", "SiteUrlIdentity" + BS_U + "002eparse(input)")) {
            assertMappingViolation(body("Object x = " + call + ";"), "parser reference in the mapping code");
        }
        // A static import is an unlisted import, and the bare call that it enables is a parser reference.
        String staticImport = body("Object x = parse(input);").replace("import java.util.Objects;",
                "import java.util.Objects;\nimport static com.smartaudits.v2.site.SiteUrlIdentity.parse;");
        assertMappingViolation(staticImport, "import outside the mapping allowlist: static com.smartaudits.v2.site.SiteUrlIdentity.parse",
                "parser reference in the mapping code");
    }

    @Test void forbiddenQualifiedNamesAreDetectedWithAnySpacing() {
        assertMappingViolation(body("Object a = java . net . InetAddress.getLoopbackAddress();"),
                "forbidden qualified name in the mapping code: java.net.InetAddress.getLoopbackAddress");
        assertMappingViolation(body("Object a = com.smartaudits\n.v2.acquisition.destination.DestinationPolicy.class;"),
                "forbidden qualified name in the mapping code: com.smartaudits.v2.acquisition.destination.DestinationPolicy.class");
        assertMappingViolation(body("Object a = com.smartaudits.repository.SiteRepository.class;"),
                "forbidden qualified name in the mapping code: com.smartaudits.repository.SiteRepository.class");
    }

    @Test void otherConsumersAreDetectedWhateverTheirSpacingOrEncoding() {
        for (String code : List.of("import com . smartaudits . v2 . site . *;\nclass Other { }",
                "class Other { Object o = com.smartaudits.v2\n\t.site.SiteUrlRejection.PATH; }",
                "class Other { SiteUrl" + BS_U + "0049dentity identity; }",
                "class Other { Object o = Class.forName(\"com.smartaudits.v2.site.SiteUrlIdentity\"); }",
                "// x\rimport com . smartaudits . v2 . site . *;\nclass Other { }",
                "// x" + BS_U + "000dimport com.smartaudits . v2.site.*;\nclass Other { }")) {
            assertEquals(List.of(OTHER_SOURCE + ": uses the Site URL identity outside the allowlist"), violations(Map.of(OTHER_SOURCE, code)), code);
        }
        String laterMigration = "src/main/resources/db/migration/V11__index.sql";
        assertEquals(List.of(laterMigration + ": names the Site URL identity outside the allowlist"), identityUseViolations(Map.of(),
                Map.of(laterMigration, "CREATE INDEX ix ON `SITE_URL_IDENTITIES` (site_id);")));
    }

    @Test void legitimateMappingSpellingsStayAccepted() {
        // Qualified and multi-line forms of the allowed annotations, parse only in comments, and an
        // escaped backslash that is not a unicode escape (JLS 3.3) are all ordinary, valid sources.
        String mapping = "package com.smartaudits.v2.model;\n"
                + "import com.smartaudits.v2.site.SiteUrlIdentity;\nimport jakarta.persistence.Entity;\nimport java.util.Objects;\n"
                + "/** Built from an identity that {@link SiteUrlIdentity#parse(String)} accepted. */\n"
                + "@jakarta.persistence.Entity\n@org.hibernate.annotations.Immutable\n@jakarta.persistence.Table(\n    name = \"site_url_identities\")\n"
                + "public class SiteUrlIdentityRecord {\n"
                + "    // parse(...) is mentioned only here, in a comment.\n"
                + "    @Id @Column(name = \"site_id\", nullable = false) private Long siteId;\n"
                + "    @JdbcTypeCode(SqlTypes.VARBINARY) @Column(columnDefinition = \"VARBINARY(261)\") private byte[] origin;\n"
                + "    private final String note = \"@Override parse( " + "\\" + "\\" + "u0040\";\n"
                + "    String form() { return new String(origin, java.nio.charset.StandardCharsets.US_ASCII); }\n}\n";
        assertEquals(List.of(), violations(Map.of(APPROVED_MAPPING, mapping)));
        for (String eol : List.of("\r", "\r\n")) {
            assertEquals(List.of(), violations(Map.of(APPROVED_MAPPING, mapping.replace("\n", eol))), printable(eol));
        }
    }

    /**
     * Unicode translation against javac itself: every string of up to four of these pieces is put in
     * a string literal, and the value javac reads (or its refusal) must equal the guard's
     * translation followed by Java's string escapes. The pieces mix raw backslashes, escaped
     * backslashes and several u, so the pairing rule of javac is exercised.
     */
    @Test void unicodeTranslationMatchesJavacOnGeneratedSequences() {
        List<String> sequences = sequences(List.of("\\", BS_U + "005c", BS_U + "uu005C", BS_U + "0041", "u0041", "x"), 4);
        Map<String, String> sources = new LinkedHashMap<>();
        for (int i = 0; i < sequences.size(); i++) {
            sources.put(String.format("unicode-%05d", i), "class S { String s = \"" + sequences.get(i) + "\"; }");
        }
        Map<String, JavacReading> readings = javacReadings(sources);
        int accepted = 0;
        int i = 0;
        for (var source : sources.entrySet()) {
            String sequence = sequences.get(i++);
            JavacReading reading = readings.get(source.getKey());
            String model = stringValue(decodeUnicodeEscapes(sequence));
            if (reading.errors().isEmpty()) {
                accepted++;
                assertNotNull(model, () -> "javac accepts what the guard refuses: " + printable(sequence));
                assertEquals(Set.of(model), reading.strings(), () -> "javac reads another value: " + printable(sequence));
            } else {
                assertNull(model, () -> "javac refuses what the guard accepts: " + printable(sequence) + " " + reading.errors());
            }
        }
        assertTrue(accepted > 100 && accepted < sequences.size(), "both outcomes must occur: " + accepted);
        System.out.println("STATIC GUARD DIFFERENTIAL unicode: " + sequences.size() + " sequences, " + accepted
                + " accepted by javac with the guard's value, " + (sequences.size() - accepted) + " refused by both");
    }

    /** Line comments against javac: the forbidden annotation after the comment is seen by both readings, or by neither. */
    @Test void bothReadingsAgreeOnGeneratedLineComments() {
        List<String> mappings = new ArrayList<>();
        for (String s : sequences(List.of("\\", BS_U + "005c", BS_U + "000d", BS_U + "000a", BS_U + "uu000D", "\r", "x"), 4)) {
            mappings.add(GOOD_MAPPING.replace("@Entity public class", "// note" + s + "@org.springframework.stereotype.Service\n@Entity public class"));
        }
        int[] result = compareReadings(mappings, "annotation outside the mapping allowlist: org.springframework.stereotype.Service");
        assertTrue(result[2] > 0 && result[2] < result[1], "both outcomes must occur: " + result[1] + " accepted, " + result[2] + " with the annotation");
        System.out.println("STATIC GUARD DIFFERENTIAL comments: " + result[0] + " sources, " + result[1] + " accepted by javac, "
                + result[2] + " with the annotation in code, " + (result[1] - result[2]) + " with it inside the comment; readings equal on all");
    }

    /** Text blocks against javac: whenever javac accepts the source, both readings see the parser call after the block. */
    @Test void bothReadingsAgreeOnGeneratedTextBlocks() {
        List<String> mappings = new ArrayList<>();
        for (String s : sequences(List.of("\\\"", "\"", "\\\\", BS_U + "0022", BS_U + "005c", "\n", "\\\n", "x"), 4)) {
            mappings.add(body("String a = " + TQ + "\n" + s + TQ + "; Object o = SiteUrlIdentity . parse(input); String b = " + TQ + "\nq" + TQ + ";"));
        }
        int[] result = compareReadings(mappings, "parser reference in the mapping code");
        assertTrue(result[1] > 100, "javac must accept a fair share: " + result[1]);
        assertEquals(result[1], result[2], "every accepted source has the call in code");
        System.out.println("STATIC GUARD DIFFERENTIAL text blocks: " + result[0] + " sources, " + result[1]
                + " accepted by javac, parser call seen by both readings in all " + result[2]);
    }

    private static List<String> violations(Map<String, String> java) {
        return identityUseViolations(java, Map.of());
    }

    /** A backslash followed by u, built at run time so that neither javac nor any tool sees an escape here. */
    private static final String BS_U = "\\" + "u";
    private static final String TQ = "\"\"\"";

    private static String annotate(String annotation) {
        return GOOD_MAPPING.replace("@Entity public class", annotation + " public class");
    }

    private static String body(String statement) {
        return GOOD_MAPPING.replace("public class SiteUrlIdentityRecord { }", "public class SiteUrlIdentityRecord { void m(String input) { " + statement + " } }");
    }

    private static String members(String declarations) {
        return GOOD_MAPPING.replace("public class SiteUrlIdentityRecord { }", "public class SiteUrlIdentityRecord { " + declarations + " }");
    }

    private static void assertMappingViolation(String mapping, String... expected) {
        List<String> actual = violations(Map.of(APPROVED_MAPPING, mapping));
        for (String message : expected) {
            assertTrue(actual.contains(APPROVED_MAPPING + ": " + message), () -> "missing <" + message + "> in " + actual + " for:\n" + mapping);
        }
    }

    /** Each reading on its own, and the guard, report every expected violation; javac must accept the sample. */
    private static void assertBothReadings(String label, String mapping, String... expected) {
        Map<String, JavacReading> readings = javacReadings(Map.of(APPROVED_MAPPING, mapping));
        JavacReading reading = readings.get(APPROVED_MAPPING);
        assertEquals(List.of(), reading.errors(), () -> label + ": javac must accept the sample, or it proves nothing:\n" + printable(mapping));
        List<String> text = textMappingFindings(APPROVED_MAPPING, mapping);
        List<String> javac = javacMappingFindings(APPROVED_MAPPING, reading);
        List<String> guard = identityUseViolations(Map.of(APPROVED_MAPPING, mapping), Map.of(), readings);
        for (String message : expected) {
            String full = APPROVED_MAPPING + ": " + message;
            assertTrue(text.contains(full), () -> label + ": the hand reading misses <" + message + "> in " + text + " for:\n" + printable(mapping));
            assertTrue(javac.contains(full), () -> label + ": the javac reading misses <" + message + "> in " + javac + " for:\n" + printable(mapping));
            assertTrue(guard.contains(full), () -> label + ": the guard misses <" + message + "> in " + guard);
        }
    }

    /** {sources, accepted by javac, accepted with the expected finding}; on every accepted source both readings find the same code violations. */
    private static int[] compareReadings(List<String> mappings, String expected) {
        Map<String, String> sources = new LinkedHashMap<>();
        for (int i = 0; i < mappings.size(); i++) {
            sources.put(String.format("case-%05d", i), mappings.get(i));
        }
        Map<String, JavacReading> readings = javacReadings(sources);
        int accepted = 0;
        int found = 0;
        for (var source : sources.entrySet()) {
            JavacReading reading = readings.get(source.getKey());
            if (!reading.errors().isEmpty()) {
                continue; // the guard refuses a source javac rejects (see escapedUnicodeEscapesDoNotEndALineComment)
            }
            accepted++;
            Set<String> javac = codeFindings(javacMappingFindings(APPROVED_MAPPING, reading));
            Set<String> text = codeFindings(textMappingFindings(APPROVED_MAPPING, source.getValue()));
            assertEquals(javac, text, () -> "the readings differ on:\n" + printable(source.getValue()));
            if (javac.contains(APPROVED_MAPPING + ": " + expected)) {
                found++;
            }
        }
        return new int[] {sources.size(), accepted, found};
    }

    /** The findings about code (imports, annotations, parser references, qualified names); tokens are also searched in comments and literals. */
    private static Set<String> codeFindings(List<String> findings) {
        Set<String> code = new TreeSet<>();
        for (String finding : findings) {
            if (finding.contains(": import outside") || finding.contains(": annotation outside") || finding.contains(": parser reference")
                    || finding.contains(": forbidden qualified name")) {
                code.add(finding);
            }
        }
        return code;
    }

    private static List<String> sequences(List<String> pieces, int maxLength) {
        List<String> all = new ArrayList<>();
        List<String> level = List.of("");
        for (int length = 1; length <= maxLength; length++) {
            List<String> next = new ArrayList<>();
            for (String prefix : level) {
                for (String piece : pieces) {
                    next.add(prefix + piece);
                }
            }
            all.addAll(next);
            level = next;
        }
        return all;
    }

    /** The value of a string literal body after unicode translation (JLS 3.10.5, 3.10.7), or null where javac refuses it. */
    private static String stringValue(String translated) {
        StringBuilder value = new StringBuilder();
        int n = translated.length();
        for (int i = 0; i < n; i++) {
            char c = translated.charAt(i);
            if (c == '"' || c == '\r' || c == '\n') {
                return null;
            }
            if (c != '\\') {
                value.append(c);
                continue;
            }
            if (++i == n) {
                return null;
            }
            char e = translated.charAt(i);
            switch (e) {
                case 'b' -> value.append('\b');
                case 't' -> value.append('\t');
                case 'n' -> value.append('\n');
                case 'f' -> value.append('\f');
                case 'r' -> value.append('\r');
                case 's' -> value.append(' ');
                case '\'', '"', '\\' -> value.append(e);
                default -> {
                    if (e < '0' || e > '7') {
                        return null;
                    }
                    int code = e - '0';
                    for (int more = e <= '3' ? 2 : 1; more > 0 && i + 1 < n && translated.charAt(i + 1) >= '0' && translated.charAt(i + 1) <= '7'; more--) {
                        code = code * 8 + (translated.charAt(++i) - '0');
                    }
                    value.append((char) code);
                }
            }
        }
        return value.toString();
    }

    private static String printable(String text) {
        return text.replace("\r", "<CR>").replace("\n", "<LF>").replace("\t", "<TAB>").replace("\f", "<FF>").replace(String.valueOf((char) 0), "<NUL>");
    }

    private static final Path RESOURCES = Paths.get("src", "main", "resources");
    static final String APPROVED_MAPPING = "src/main/java/com/smartaudits/v2/model/SiteUrlIdentityRecord.java";
    static final String APPROVED_MIGRATION = "src/main/resources/db/migration/V10__create_site_url_identities.sql";
    private static final String PURE_PREFIX = "src/main/java/com/smartaudits/v2/site/";
    private static final String PURE_SOURCE = PURE_PREFIX + "SiteUrlIdentity.java";
    private static final String OTHER_SOURCE = "src/main/java/com/smartaudits/v2/model/Other.java";
    private static final String OTHER_MIGRATION = "src/main/resources/db/migration/V6__create_sites.sql";
    /** Every import the approved mapping may have: persistence annotations, ASCII bytes and the identity type. */
    private static final Set<String> MAPPING_IMPORTS = Set.of(
            "com.smartaudits.v2.site.SiteUrlIdentity",
            "jakarta.persistence.Column", "jakarta.persistence.Entity", "jakarta.persistence.Id",
            "jakarta.persistence.Index", "jakarta.persistence.Table", "jakarta.persistence.UniqueConstraint",
            "org.hibernate.annotations.Immutable", "org.hibernate.annotations.JdbcTypeCode", "org.hibernate.type.SqlTypes",
            "java.nio.charset.StandardCharsets", "java.util.Objects");
    /** The mapping never parses, resolves, acquires, serves requests or becomes a bean or a repository. */
    private static final List<String> MAPPING_FORBIDDEN = List.of(
            "SiteUrlIdentity.parse(", "SiteUrlIdentityResult", "SiteUrlRejection",
            "@RestController", "@Controller", "@RequestMapping", "@GetMapping", "@PostMapping", "@PutMapping", "@DeleteMapping",
            "@Service", "@Component", "@Configuration", "@Bean", "@Autowired", "@Transactional", "Repository",
            "java.net", "InetAddress", "URLConnection", "HttpClient", "Socket", "Channel",
            "DestinationUrl", "DestinationPolicy", "HostResolver", "ValidatedHost", "acquisition", "Captura", "Transporte",
            "forName", "MethodHandles", "java.lang.reflect");
    /** Every annotation the mapping code may carry, simple or fully qualified. Anything else, including a meta-annotation, is refused. */
    private static final Set<String> MAPPING_ANNOTATIONS = Set.of(
            "Entity", "Table", "UniqueConstraint", "Index", "Id", "Column", "Immutable", "JdbcTypeCode",
            "jakarta.persistence.Entity", "jakarta.persistence.Table", "jakarta.persistence.UniqueConstraint",
            "jakarta.persistence.Index", "jakarta.persistence.Id", "jakarta.persistence.Column",
            "org.hibernate.annotations.Immutable", "org.hibernate.annotations.JdbcTypeCode");
    /** Qualified names the mapping code may never reach, whatever their spacing. A deny-list, because variables also take this shape. */
    private static final List<String> MAPPING_FORBIDDEN_PACKAGES = List.of(
            "org.springframework", "jakarta.transaction", "jakarta.servlet", "jakarta.ws", "javax", "java.net", "java.nio.channels",
            "java.lang.reflect", "java.lang.invoke", "org.apache.hc", "org.apache.http",
            "com.smartaudits.v2.acquisition", "com.smartaudits.controller", "com.smartaudits.service", "com.smartaudits.repository",
            "com.smartaudits.config", "com.smartaudits.security", "com.smartaudits.v2.site.SiteUrlIdentityResult",
            "com.smartaudits.v2.site.SiteUrlRejection");
    private static final java.util.regex.Pattern IMPORT = java.util.regex.Pattern.compile("(?<![\\w$])import\\s+([^;]*);");
    private static final java.util.regex.Pattern DECLARATION = java.util.regex.Pattern.compile("(?<![\\w$])(?:package|import)\\s[^;]*;");
    private static final java.util.regex.Pattern ANNOTATION =
            java.util.regex.Pattern.compile("@\\s*([A-Za-z_$][\\w$]*(?:\\s*\\.\\s*[A-Za-z_$][\\w$]*)*)");
    private static final java.util.regex.Pattern QUALIFIED =
            java.util.regex.Pattern.compile("(?<![\\w$.])[A-Za-z_$][\\w$]*(?:\\s*\\.\\s*[A-Za-z_$][\\w$]*)+");
    private static final java.util.regex.Pattern PARSE = java.util.regex.Pattern.compile("(?<![\\w$])parse(?![\\w$])");
    private static final String GOOD_MAPPING = "package com.smartaudits.v2.model;\n"
            + "import com.smartaudits.v2.site.SiteUrlIdentity;\nimport jakarta.persistence.Entity;\nimport java.util.Objects;\n"
            + "@Entity public class SiteUrlIdentityRecord { }\n";

    /**
     * Pure check over (repository-relative path, text). A Java source outside the pure package that
     * names the identity, its package or the mapping is a violation unless it is exactly the
     * approved mapping, whose imports, tokens, annotations, parser references and qualified names
     * are then held to the lists above. A resource that names the identity or its table, in any
     * letter case, is a violation unless it is exactly the V10 migration.
     *
     * <p>Every Java source is read twice, independently, and a violation from either reading counts:
     * <ol>
     * <li>by hand, as javac 17 lexes ({@link #decodeUnicodeEscapes}, then {@link #codeForm}), with
     * the raw text, comments and literals included, still scanned for the plain tokens;</li>
     * <li>by javac 17 itself ({@link #javacReadings}), whose tree gives the names, imports,
     * annotations and literal values exactly as the compiler sees them.</li>
     * </ol>
     * A source that javac does not accept is a violation, since neither reading can then be trusted.
     * This is still a structural guard: it does not see reflection by computed names, generated code
     * or a dependency that is not source in this module.
     */
    static List<String> identityUseViolations(Map<String, String> javaSources, Map<String, String> resources) {
        return identityUseViolations(javaSources, resources, javacReadings(consumers(javaSources)));
    }

    private static Map<String, String> consumers(Map<String, String> javaSources) {
        Map<String, String> consumers = new TreeMap<>();
        javaSources.forEach((path, text) -> {
            if (!path.startsWith(PURE_PREFIX)) {
                consumers.put(path, text);
            }
        });
        return consumers;
    }

    /** The same check with javac's readings of the consumers already made, so that a test parses each sample once. */
    private static List<String> identityUseViolations(Map<String, String> javaSources, Map<String, String> resources,
                                                      Map<String, JavacReading> javac) {
        Map<String, String> consumers = consumers(javaSources);
        assertEquals(consumers.keySet(), javac.keySet(), "one javac reading per consumer");
        List<String> violations = new ArrayList<>();
        for (var source : consumers.entrySet()) {
            String path = source.getKey();
            String raw = source.getValue();
            JavacReading reading = javac.get(path);
            if (!reading.errors().isEmpty()) {
                violations.add(path + ": javac 17 rejects it, so the guard cannot read it as compiled: " + String.join(", ", reading.errors()));
            }
            if (!textUses(raw) && !javacUses(reading)) {
                continue;
            }
            if (!path.equals(APPROVED_MAPPING)) {
                violations.add(path + ": uses the Site URL identity outside the allowlist");
                continue;
            }
            Set<String> found = new LinkedHashSet<>(textMappingFindings(path, raw));
            found.addAll(javacMappingFindings(path, reading));
            violations.addAll(found);
        }
        for (var resource : new TreeMap<>(resources).entrySet()) {
            String text = resource.getValue().toLowerCase(java.util.Locale.ROOT);
            boolean names = text.contains("siteurlidentity") || text.contains("site_url_identit") || text.contains("smartaudits.v2.site");
            if (names && !resource.getKey().equals(APPROVED_MIGRATION)) {
                violations.add(resource.getKey() + ": names the Site URL identity outside the allowlist");
            }
        }
        return violations;
    }

    private static boolean names(String text) {
        return text.contains("SiteUrlIdentity") || text.contains("SiteUrlRejection") || text.contains("smartaudits.v2.site");
    }

    /** The hand reading of a consumer: the raw, translated, ignorable-free and compact code texts. */
    static boolean textUses(String raw) {
        String decoded = decodeUnicodeEscapes(raw);
        String compact = codeForm(decoded).replaceAll("\\s+", "");
        return names(raw) || names(decoded) || names(withoutIgnorables(decoded)) || names(compact);
    }

    private static boolean javacUses(JavacReading reading) {
        return reading.names().stream().anyMatch(SiteUrlIdentityStaticTest::names) || reading.qualified().stream().anyMatch(SiteUrlIdentityStaticTest::names)
                || reading.strings().stream().anyMatch(SiteUrlIdentityStaticTest::names);
    }

    /** The hand reading of the mapping, in the order: imports, forbidden tokens, annotations, parser reference, qualified names. */
    static List<String> textMappingFindings(String path, String raw) {
        String decoded = decodeUnicodeEscapes(raw);
        String visible = withoutIgnorables(decoded);
        String code = codeForm(decoded);
        List<String> findings = new ArrayList<>();
        var imports = IMPORT.matcher(code);
        while (imports.find()) {
            String imported = imports.group(1).strip().replaceAll("\\s*\\.\\s*", ".").replaceAll("^static\\s+", "static ");
            if (!MAPPING_IMPORTS.contains(imported)) {
                findings.add(path + ": import outside the mapping allowlist: " + imported);
            }
        }
        for (String token : MAPPING_FORBIDDEN) {
            if (raw.contains(token) || decoded.contains(token) || visible.contains(token)) {
                findings.add(path + ": forbidden in the mapping: " + token);
            }
        }
        // Declarations have their own allow-list above; what follows is the rest of the code.
        String body = DECLARATION.matcher(code).replaceAll(" ");
        Set<String> found = new LinkedHashSet<>();
        var annotations = ANNOTATION.matcher(body);
        while (annotations.find()) {
            String name = annotations.group(1).replaceAll("\\s+", "");
            if (!MAPPING_ANNOTATIONS.contains(name)) {
                found.add(path + ": annotation outside the mapping allowlist: " + name);
            }
        }
        if (PARSE.matcher(body).find()) {
            found.add(path + ": parser reference in the mapping code");
        }
        var qualified = QUALIFIED.matcher(body);
        while (qualified.find()) {
            String name = qualified.group().replaceAll("\\s+", "");
            for (String prefix : MAPPING_FORBIDDEN_PACKAGES) {
                if (name.equals(prefix) || name.startsWith(prefix + ".")) {
                    found.add(path + ": forbidden qualified name in the mapping code: " + name);
                }
            }
        }
        findings.addAll(found);
        return findings;
    }

    /** The javac reading of the mapping, held to the same lists and reported with the same messages. */
    static List<String> javacMappingFindings(String path, JavacReading reading) {
        List<String> findings = new ArrayList<>();
        for (String imported : reading.imports()) {
            if (!MAPPING_IMPORTS.contains(imported)) {
                findings.add(path + ": import outside the mapping allowlist: " + imported);
            }
        }
        for (String token : MAPPING_FORBIDDEN) {
            boolean inName = isIdentifier(token) && reading.names().stream().anyMatch(name -> name.contains(token));
            if (inName || reading.strings().stream().anyMatch(value -> value.contains(token))) {
                findings.add(path + ": forbidden in the mapping: " + token);
            }
        }
        for (String annotation : reading.annotations()) {
            if (!MAPPING_ANNOTATIONS.contains(annotation)) {
                findings.add(path + ": annotation outside the mapping allowlist: " + annotation);
            }
        }
        if (reading.declaresAnnotationType()) {
            findings.add(path + ": annotation outside the mapping allowlist: interface");
        }
        if (reading.bodyNames().contains("parse")) {
            findings.add(path + ": parser reference in the mapping code");
        }
        for (String name : reading.bodyQualified()) {
            for (String prefix : MAPPING_FORBIDDEN_PACKAGES) {
                if (name.equals(prefix) || name.startsWith(prefix + ".")) {
                    findings.add(path + ": forbidden qualified name in the mapping code: " + name);
                }
            }
        }
        return findings;
    }

    private static boolean isIdentifier(String token) {
        return !token.isEmpty() && Character.isJavaIdentifierStart(token.charAt(0)) && token.chars().allMatch(Character::isJavaIdentifierPart);
    }

    /**
     * Unicode translation exactly as javac 17 performs it (com.sun.tools.javac.parser.UnicodeReader):
     * a backslash may begin an escape unless it is the second of a pair, a backslash produced by an
     * escape counts towards that pairing, and a character produced by an escape never begins another
     * escape. JLS 3.3 counts raw backslashes only; where the two differ, javac is what compiles this
     * code, and the guard's own javac reading would see the difference anyway.
     */
    static String decodeUnicodeEscapes(String source) {
        StringBuilder out = new StringBuilder(source.length());
        boolean wasBackslash = false;
        boolean wasUnicodeEscape = false;
        int n = source.length();
        int i = 0;
        while (i < n) {
            char c = source.charAt(i);
            if (c == '\\' && (!wasBackslash || wasUnicodeEscape)) {
                int j = i + 1;
                while (j < n && source.charAt(j) == 'u') {
                    j++;
                }
                if (j == i + 1) {
                    out.append('\\');
                    wasUnicodeEscape = false;
                    wasBackslash = !wasBackslash;
                    i++;
                    continue;
                }
                if (j + 4 <= n && hexDigits(source, j)) {
                    char translated = (char) (Character.digit(source.charAt(j), 16) << 12 | Character.digit(source.charAt(j + 1), 16) << 8
                            | Character.digit(source.charAt(j + 2), 16) << 4 | Character.digit(source.charAt(j + 3), 16));
                    out.append(translated);
                    wasUnicodeEscape = true;
                    wasBackslash = translated == '\\' && !wasBackslash;
                    i = j + 4;
                    continue;
                }
                // A malformed escape: javac refuses the source, so the guard does too. Kept as text.
            }
            out.append(c);
            wasBackslash = false;
            wasUnicodeEscape = false;
            i++;
        }
        return out.toString();
    }

    /** Four digits as javac reads them (Character.digit, which also accepts non-ASCII digits). */
    private static boolean hexDigits(String source, int from) {
        for (int k = from; k < from + 4; k++) {
            if (Character.digit(source.charAt(k), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static String withoutIgnorables(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.chars().filter(c -> !Character.isIdentifierIgnorable(c)).forEach(c -> out.append((char) c));
        return out.toString();
    }

    /**
     * Code as javac 17 tokenizes translated text (JLS 3.4 to 3.10; JavaTokenizer): a line comment
     * ends at CR or LF; a traditional comment at the first star and slash; a string or character
     * literal at its unescaped quote or at a line terminator; a text block, whose opening delimiter
     * is three quotes, optional spaces, tabs or form feeds and a line terminator, at the first three
     * quotes that no backslash escapes. Each comment and each whitespace character becomes a space,
     * literals keep only their quotes, and identifier-ignorable characters, which javac drops from
     * identifiers (JLS 3.8), are dropped. Where javac reports an error, the text is read on as code.
     */
    static String codeForm(String decoded) {
        StringBuilder out = new StringBuilder(decoded.length());
        int n = decoded.length();
        int i = 0;
        while (i < n) {
            char c = decoded.charAt(i);
            char next = i + 1 < n ? decoded.charAt(i + 1) : ' ';
            if (c == '/' && next == '/') {
                i = lineEnd(decoded, i + 2);
                out.append(' ');
            } else if (c == '/' && next == '*') {
                int end = decoded.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                out.append(' ');
            } else if (decoded.startsWith(TQ, i)) {
                i = textBlockEnd(decoded, i + 3);
                out.append("\"\"");
            } else if (c == '"' || c == '\'') {
                i = literalEnd(decoded, i + 1, c);
                out.append(c).append(c);
            } else if (c == ' ' || c == '\t' || c == '\f' || c == '\r' || c == '\n') {
                out.append(' ');
                i++;
            } else {
                if (!Character.isIdentifierIgnorable(c)) {
                    out.append(c);
                }
                i++;
            }
        }
        return out.toString();
    }

    private static boolean lineTerminator(char c) {
        return c == '\r' || c == '\n';
    }

    /** The line terminator that ends a line comment, which is not part of the comment. */
    private static int lineEnd(String text, int from) {
        for (int j = from; j < text.length(); j++) {
            if (lineTerminator(text.charAt(j))) {
                return j;
            }
        }
        return text.length();
    }

    /** After the opening quote of a string or character literal. A line terminator ends an unterminated literal. */
    private static int literalEnd(String text, int from, char quote) {
        int n = text.length();
        int j = from;
        while (j < n) {
            char c = text.charAt(j);
            if (c == quote) {
                return j + 1;
            }
            if (lineTerminator(c)) {
                return j;
            }
            if (c == '\\') {
                if (j + 1 < n && lineTerminator(text.charAt(j + 1))) {
                    return j + 1;
                }
                j += 2;
            } else {
                j++;
            }
        }
        return n;
    }

    /** After the three opening quotes of a text block. An escape takes the next character, so an escaped quote never closes. */
    private static int textBlockEnd(String text, int from) {
        int n = text.length();
        int j = from;
        while (j < n && (text.charAt(j) == ' ' || text.charAt(j) == '\t' || text.charAt(j) == '\f')) {
            j++;
        }
        if (j >= n || !lineTerminator(text.charAt(j))) {
            return j; // not an opening delimiter: javac refuses it and reads on
        }
        while (j < n) {
            if (text.startsWith(TQ, j)) {
                return j + 3;
            }
            j += text.charAt(j) == '\\' ? 2 : 1;
        }
        return n;
    }

    /** What javac 17 reads in one source. Body sets leave out the package and import declarations. */
    record JavacReading(List<String> errors, List<String> imports, Set<String> annotations, boolean declaresAnnotationType,
                        Set<String> names, Set<String> bodyNames, Set<String> qualified, Set<String> bodyQualified, Set<String> strings) { }

    private static final JavaCompiler JAVAC = ToolProvider.getSystemJavaCompiler();
    private static final StandardJavaFileManager JAVAC_FILES = JAVAC == null ? null : JAVAC.getStandardFileManager(null, null, StandardCharsets.UTF_8);
    /** Java 17 source; every error reported (javac stops at 100 by default, which would hide refusals in a batch). */
    private static final List<String> JAVAC_OPTIONS = List.of("-source", "17", "-proc:none", "-Xmaxerrs", String.valueOf(Integer.MAX_VALUE));

    /** Parses every source with javac 17 (parse only: no class path, no annotation processing) and reads its tree. */
    static synchronized Map<String, JavacReading> javacReadings(Map<String, String> sources) {
        Map<String, JavacReading> readings = new TreeMap<>();
        if (sources.isEmpty()) {
            return readings;
        }
        assertNotNull(JAVAC, "the guard reads every source with javac 17 too, so it must run on a JDK");
        Map<String, String> keys = new HashMap<>();
        List<JavaFileObject> files = new ArrayList<>();
        int index = 0;
        for (var source : sources.entrySet()) {
            URI uri = URI.create("string:///guard/" + index++ + ".java");
            String text = source.getValue();
            keys.put(uri.toString(), source.getKey());
            files.add(new SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
                @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return text;
                }
            });
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavacTask task = (JavacTask) JAVAC.getTask(null, JAVAC_FILES, diagnostics, JAVAC_OPTIONS, null, files);
        Map<String, Reader> readers = new HashMap<>();
        try {
            for (CompilationUnitTree unit : task.parse()) {
                Reader reader = new Reader();
                reader.read(unit);
                readers.put(keys.get(unit.getSourceFile().toUri().toString()), reader);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, Set<String>> errors = new HashMap<>();
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                assertNotNull(diagnostic.getSource(), () -> "javac error without a source: " + diagnostic);
                errors.computeIfAbsent(keys.get(diagnostic.getSource().toUri().toString()), k -> new TreeSet<>()).add(diagnostic.getCode());
            }
        }
        for (String key : sources.keySet()) {
            Reader r = readers.get(key);
            assertNotNull(r, "javac returned no tree for " + key);
            readings.put(key, new JavacReading(List.copyOf(errors.getOrDefault(key, Set.of())), List.copyOf(r.imports), Set.copyOf(r.annotations),
                    r.annotationType, Set.copyOf(r.names), Set.copyOf(r.bodyNames), Set.copyOf(r.qualified), Set.copyOf(r.bodyQualified),
                    Set.copyOf(r.strings)));
        }
        return readings;
    }

    /** A qualified name made only of identifiers, as javac prints it. */
    private static boolean isName(Tree tree) {
        return tree instanceof IdentifierTree || tree instanceof MemberSelectTree select && isName(select.getExpression());
    }

    private static String nameOf(Tree tree) {
        if (tree instanceof IdentifierTree identifier) {
            return identifier.getName().toString();
        }
        if (tree instanceof MemberSelectTree select) {
            return nameOf(select.getExpression()) + "." + select.getIdentifier();
        }
        return tree.toString();
    }

    /** Collects names (every identifier and declared name), the longest qualified names, annotations and string values. */
    private static final class Reader extends TreeScanner<Void, Void> {
        private boolean declaration;
        private boolean inQualifiedName;
        private boolean annotationType;
        private final List<String> imports = new ArrayList<>();
        private final Set<String> annotations = new LinkedHashSet<>();
        private final Set<String> names = new LinkedHashSet<>();
        private final Set<String> bodyNames = new LinkedHashSet<>();
        private final Set<String> qualified = new LinkedHashSet<>();
        private final Set<String> bodyQualified = new LinkedHashSet<>();
        private final Set<String> strings = new LinkedHashSet<>();

        void read(CompilationUnitTree unit) {
            declaration = true;
            scan(unit.getPackage(), null);
            for (ImportTree imported : unit.getImports()) {
                imports.add((imported.isStatic() ? "static " : "") + nameOf(imported.getQualifiedIdentifier()));
                scan(imported, null);
            }
            declaration = false;
            scan(unit.getTypeDecls(), null);
        }

        private void name(CharSequence name) {
            if (name != null) {
                names.add(name.toString());
                if (!declaration) {
                    bodyNames.add(name.toString());
                }
            }
        }

        @Override public Void visitPackage(PackageTree tree, Void v) {
            declaration = false; // package annotations are code, as for the hand reading
            scan(tree.getAnnotations(), v);
            declaration = true;
            return scan(tree.getPackageName(), v);
        }

        @Override public Void visitAnnotation(AnnotationTree tree, Void v) {
            if (!declaration) {
                annotations.add(nameOf(tree.getAnnotationType()));
            }
            return super.visitAnnotation(tree, v);
        }

        @Override public Void visitClass(ClassTree tree, Void v) {
            annotationType |= tree.getKind() == Tree.Kind.ANNOTATION_TYPE;
            name(tree.getSimpleName());
            return super.visitClass(tree, v);
        }

        @Override public Void visitMethod(MethodTree tree, Void v) {
            name(tree.getName());
            return super.visitMethod(tree, v);
        }

        @Override public Void visitVariable(VariableTree tree, Void v) {
            name(tree.getName());
            return super.visitVariable(tree, v);
        }

        @Override public Void visitTypeParameter(TypeParameterTree tree, Void v) {
            name(tree.getName());
            return super.visitTypeParameter(tree, v);
        }

        @Override public Void visitLabeledStatement(LabeledStatementTree tree, Void v) {
            name(tree.getLabel());
            return super.visitLabeledStatement(tree, v);
        }

        @Override public Void visitBreak(BreakTree tree, Void v) {
            name(tree.getLabel());
            return super.visitBreak(tree, v);
        }

        @Override public Void visitContinue(ContinueTree tree, Void v) {
            name(tree.getLabel());
            return super.visitContinue(tree, v);
        }

        @Override public Void visitIdentifier(IdentifierTree tree, Void v) {
            name(tree.getName());
            return null;
        }

        @Override public Void visitMemberReference(MemberReferenceTree tree, Void v) {
            name(tree.getName());
            return super.visitMemberReference(tree, v);
        }

        @Override public Void visitMemberSelect(MemberSelectTree tree, Void v) {
            name(tree.getIdentifier());
            if (inQualifiedName || !isName(tree)) {
                return super.visitMemberSelect(tree, v);
            }
            String name = nameOf(tree);
            qualified.add(name);
            if (!declaration) {
                bodyQualified.add(name);
            }
            inQualifiedName = true;
            try {
                return super.visitMemberSelect(tree, v);
            } finally {
                inQualifiedName = false;
            }
        }

        @Override public Void visitLiteral(LiteralTree tree, Void v) {
            if (tree.getValue() instanceof String value) {
                strings.add(value);
            }
            return null;
        }
    }

    /** Repository-relative path with forward slashes, to the text (ISO-8859-1 never fails on binary files). */
    private static Map<String, String> read(List<Path> files) throws IOException {
        Map<String, String> texts = new TreeMap<>();
        for (Path file : files) {
            texts.put(file.toString().replace('\\', '/'), new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1));
        }
        return texts;
    }

    /** Java sources as javac reads them: strict UTF-8 (the build's source encoding); a malformed byte fails the test. */
    private static Map<String, String> readJava(List<Path> files) throws IOException {
        Map<String, String> texts = new TreeMap<>();
        for (Path file : files) {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(Files.readAllBytes(file))).toString();
            texts.put(file.toString().replace('\\', '/'), text);
        }
        return texts;
    }

    @Test void theIdentityTypesCarryNoFrameworkOrPersistenceAnnotation() {
        for (Class<?> type : List.of(SiteUrlIdentity.class, SiteUrlIdentityResult.class, SiteUrlIdentityResult.Accepted.class,
                SiteUrlIdentityResult.Rejected.class, SiteUrlRejection.class)) {
            assertEquals(0, type.getAnnotations().length, type + " is annotated");
            assertEquals("com.smartaudits.v2.site", type.getPackageName());
        }
    }
}
