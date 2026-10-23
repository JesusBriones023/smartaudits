package com.smartaudits.v2.acquisition.destination;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * Test-only text helpers, written independently of the production code: dotted IPv4 and
 * RFC 4291 IPv6 text (with "::" and an optional dotted IPv4 tail) to bytes, and an RFC 5952
 * formatter used to cross-check the frozen network text. No InetAddress, no resolution.
 */
final class TestAddresses {
    private TestAddresses() { }

    static IpAddress ip(String text) {
        return text.indexOf(':') >= 0 ? IpAddress.ipv6(ipv6Bytes(text)) : IpAddress.ipv4(ipv4Bytes(text));
    }

    static IpAddress hex(String hex) {
        byte[] bytes = HexFormat.of().parseHex(hex);
        return bytes.length == 4 ? IpAddress.ipv4(bytes) : IpAddress.ipv6(bytes);
    }

    static byte[] ipv4Bytes(String text) {
        String[] parts = text.split("[.]", -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException("bad IPv4 text: " + text);
        }
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            if (!parts[i].matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(parts[i]) > 255) {
                throw new IllegalArgumentException("bad IPv4 text: " + text);
            }
            out[i] = (byte) Integer.parseInt(parts[i]);
        }
        return out;
    }

    static byte[] ipv6Bytes(String text) {
        List<Integer> groups = new ArrayList<>();
        String body = text;
        byte[] tail = null;
        int lastColon = body.lastIndexOf(':');
        if (body.substring(lastColon + 1).contains(".")) {
            tail = ipv4Bytes(body.substring(lastColon + 1));
            body = body.substring(0, lastColon + 1) + "0:0";
        }
        String[] halves = body.split("::", -1);
        if (halves.length > 2) {
            throw new IllegalArgumentException("bad IPv6 text: " + text);
        }
        List<Integer> left = hextets(halves[0]);
        List<Integer> right = halves.length == 2 ? hextets(halves[1]) : List.of();
        int missing = 8 - left.size() - right.size();
        if ((halves.length == 2 && missing < 1) || (halves.length == 1 && missing != 0)) {
            throw new IllegalArgumentException("bad IPv6 text: " + text);
        }
        groups.addAll(left);
        for (int i = 0; i < (halves.length == 2 ? missing : 0); i++) {
            groups.add(0);
        }
        groups.addAll(right);
        byte[] out = new byte[16];
        for (int i = 0; i < 8; i++) {
            out[2 * i] = (byte) (groups.get(i) >> 8);
            out[2 * i + 1] = (byte) (groups.get(i) & 0xFF);
        }
        if (tail != null) {
            System.arraycopy(tail, 0, out, 12, 4);
        }
        return out;
    }

    private static List<Integer> hextets(String part) {
        if (part.isEmpty()) {
            return List.of();
        }
        List<Integer> out = new ArrayList<>();
        for (String h : part.split(":", -1)) {
            if (!h.matches("[0-9a-f]{1,4}")) {
                throw new IllegalArgumentException("bad hextet: " + part);
            }
            out.add(Integer.parseInt(h, 16));
        }
        return out;
    }

    /** IPv4 dotted decimal, or IPv6 RFC 5952 (longest zero run of 2+ groups, leftmost; mapped in mixed form). */
    static String canonicalText(byte[] bytes) {
        if (bytes.length == 4) {
            return (bytes[0] & 0xFF) + "." + (bytes[1] & 0xFF) + "." + (bytes[2] & 0xFF) + "." + (bytes[3] & 0xFF);
        }
        int[] g = new int[8];
        for (int i = 0; i < 8; i++) {
            g[i] = ((bytes[2 * i] & 0xFF) << 8) | (bytes[2 * i + 1] & 0xFF);
        }
        boolean mapped = Arrays.stream(g, 0, 5).allMatch(x -> x == 0) && g[5] == 0xFFFF;
        if (mapped) {
            return "::ffff:" + canonicalText(Arrays.copyOfRange(bytes, 12, 16));
        }
        int bestStart = -1;
        int bestLen = 0;
        for (int i = 0; i < 8; ) {
            if (g[i] != 0) {
                i++;
                continue;
            }
            int j = i;
            while (j < 8 && g[j] == 0) {
                j++;
            }
            if (j - i > bestLen) {
                bestStart = i;
                bestLen = j - i;
            }
            i = j;
        }
        if (bestLen < 2) {
            bestStart = -1;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i == bestStart) {
                out.append("::");
                i += bestLen - 1;
                continue;
            }
            if (out.length() > 0 && out.charAt(out.length() - 1) != ':') {
                out.append(':');
            }
            out.append(Integer.toHexString(g[i]));
        }
        return out.toString();
    }
}
