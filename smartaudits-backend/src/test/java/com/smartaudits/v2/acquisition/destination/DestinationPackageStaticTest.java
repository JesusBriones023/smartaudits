package com.smartaudits.v2.acquisition.destination;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Static guarantees of the package (ADR-0002 sections 5, 13, 15 and 19): no network, name
 * resolution, locale-dependent case mapping or file/resource I/O in production sources, and
 * immutable final types without public setters.
 */
class DestinationPackageStaticTest {
    private static final Path SOURCES = Paths.get("src", "main", "java", "com", "smartaudits", "v2", "acquisition",
            "destination");
    private static final List<String> FORBIDDEN = List.of(
            "java.net.URL", "URLConnection", "URLDecoder", "URLEncoder", "java.net.IDN", "IDN.", "InetAddress",
            "Inet4Address", "Inet6Address", "Socket", "HttpClient", "DatagramChannel", "SocketChannel",
            "equalsIgnoreCase", "toLowerCase", "toUpperCase", "java.io.", "java.nio.file", "getResource",
            "ClassLoader", "System.getenv", "System.getProperty", "ProcessBuilder", "Runtime.getRuntime",
            "SecurityManager", "org.springframework", "jakarta.", "com.fasterxml", "Serializable", "Logger", "System.out",
            "System.err", "printStackTrace", "Random", "Instant.now", "LocalDate", "Clock");
    private static final List<Class<?>> TYPES = List.of(IpFamily.class, IpAddress.class, IpPrefix.class,
            CatalogEntry.class, CatalogEntry.Kind.class, CatalogEntry.Layer.class, CatalogEntry.Origin.class,
            AddressCatalog.class, AddressCatalog.Source.class, AddressCategory.class, AddressPolicy.class,
            CatalogSerialization.class, AddressCatalogData.class);

    @Test void productionSourcesUseNoForbiddenApi() throws IOException {
        List<Path> files;
        try (Stream<Path> stream = Files.list(SOURCES)) {
            files = stream.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
        }
        assertEquals(9, files.size(), "unexpected number of production sources");
        List<String> violations = new ArrayList<>();
        for (Path file : files) {
            String code = stripComments(Files.readString(file, StandardCharsets.UTF_8));
            for (String token : FORBIDDEN) {
                if (code.contains(token)) {
                    violations.add(file.getFileName() + ": " + token);
                }
            }
        }
        assertEquals(List.of(), violations);
    }

    @Test void theStaticScanDetectsAForbiddenApiOutsideComments() {
        String sample = "/* InetAddress */ class X { // Socket\n String s = \"// not a comment\"; java.net.InetAddress a; }";
        String code = stripComments(sample);
        assertTrue(code.contains("InetAddress"));
        assertFalse(code.contains("Socket"));
        assertTrue(code.contains("// not a comment"));
    }

    @Test void typesAreFinalImmutableAndNotSerializable() {
        for (Class<?> type : TYPES) {
            assertFalse(Serializable.class.isAssignableFrom(type) && !type.isEnum(), type + " is Serializable");
            assertFalse(type.isRecord(), type + " is a record");
            if (!type.isEnum()) {
                assertTrue(Modifier.isFinal(type.getModifiers()), type + " is not final");
            }
            for (Field field : type.getDeclaredFields()) {
                if (!field.isSynthetic()) {
                    assertTrue(Modifier.isFinal(field.getModifiers()), type.getSimpleName() + "." + field.getName());
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                assertFalse(method.getName().startsWith("set") && Modifier.isPublic(method.getModifiers()),
                        type.getSimpleName() + "." + method.getName());
            }
        }
    }

    @Test void onlyThePolicyHasAPublicConstructor() {
        for (Class<?> type : TYPES) {
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                boolean allowed = type == AddressPolicy.class;
                assertTrue(allowed || !Modifier.isPublic(constructor.getModifiers()), type + " has a public constructor");
            }
        }
    }

    @Test void diagnosticsNeverRevealAddressBytes() {
        IpAddress metadata = TestAddresses.ip("169.254.169.254");
        IpAddress aws = TestAddresses.ip("fd00:ec2::254");
        List<String> texts = List.of(metadata.toString(), aws.toString(), AddressCatalog.snapshot().toString(),
                new AddressPolicy(AddressCatalog.snapshot()).toString(),
                AddressCatalog.snapshot().negativePrefixes().get(0).toString());
        for (String text : texts) {
            assertFalse(text.contains("169.254") || text.contains("fd00") || text.contains("ec2"), text);
        }
    }

    /** Removes block and line comments while keeping string and character literals intact. */
    static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
            if (c == '/' && next == '*') {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? source.length() : end + 2;
                out.append(' ');
            } else if (c == '/' && next == '/') {
                while (i < source.length() && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '"' || c == '\'') {
                int start = i++;
                while (i < source.length() && source.charAt(i) != c) {
                    i += source.charAt(i) == (char) 92 ? 2 : 1;
                }
                i = Math.min(i + 1, source.length());
                out.append(source, start, i);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
