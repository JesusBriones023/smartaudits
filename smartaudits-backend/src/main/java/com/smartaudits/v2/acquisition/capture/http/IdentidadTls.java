package com.smartaudits.v2.acquisition.capture.http;

import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Independent check of the server certificate against the authorized host name, after JSSE has
 * already validated chain and host name (endpoint identification HTTPS). Only dNSName entries of the
 * subjectAltName count (no Common Name fallback, no IP entries): an exact match, or a wildcard that
 * covers exactly the leftmost label of a name with at least three labels ({@code *.ejemplo.es}).
 */
final class IdentidadTls {

    private static final int SAN_DNS = 2;

    private IdentidadTls() {
    }

    static boolean coincide(X509Certificate certificado, String host) {
        String nombre = host.toLowerCase(Locale.ROOT);
        Collection<List<?>> alternativos;
        try {
            alternativos = certificado.getSubjectAlternativeNames();
        } catch (CertificateParsingException e) {
            return false;
        }
        if (alternativos == null) {
            return false;
        }
        for (List<?> entrada : alternativos) {
            if (entrada.size() >= 2 && entrada.get(0) instanceof Integer tipo && tipo == SAN_DNS
                    && entrada.get(1) instanceof String dns && cubre(dns.toLowerCase(Locale.ROOT), nombre)) {
                return true;
            }
        }
        return false;
    }

    static boolean cubre(String patron, String host) {
        if (patron.endsWith(".") || host.endsWith(".")) {
            return false;
        }
        if (!patron.startsWith("*.")) {
            return patron.equals(host);
        }
        String sufijo = patron.substring(1); // ".ejemplo.es"
        if (sufijo.indexOf('*') >= 0 || sufijo.chars().filter(c -> c == '.').count() < 2) {
            return false;
        }
        int punto = host.indexOf('.');
        return punto > 0 && host.substring(punto).equals(sufijo);
    }
}
