package com.smartaudits.v2.acquisition.capture.http;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Throwaway test PKI generated in memory for each run: nothing is written to disk or committed, and
 * these certificates are only ever trusted by the test client context built here.
 */
final class PkiDePrueba {

    record Credencial(KeyPair par, X509Certificate certificado) {}

    private static final AtomicLong SERIE = new AtomicLong(System.nanoTime());

    private PkiDePrueba() {
    }

    static Credencial ca(String nombre) throws Exception {
        KeyPair par = par();
        X500Name sujeto = new X500Name("CN=" + nombre);
        var builder = new JcaX509v3CertificateBuilder(sujeto, BigInteger.valueOf(SERIE.incrementAndGet()),
                Date.from(Instant.now().minus(Duration.ofHours(1))), Date.from(Instant.now().plus(Duration.ofDays(1))),
                sujeto, par.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
        return new Credencial(par, firmar(builder, par));
    }

    /** Server certificate for the given dNSName entries (none: only a Common Name). */
    static Credencial hoja(Credencial ca, String cn, Instant desde, Instant hasta, String... dns) throws Exception {
        KeyPair par = par();
        var builder = new JcaX509v3CertificateBuilder(new X500Name(ca.certificado().getSubjectX500Principal().getName()),
                BigInteger.valueOf(SERIE.incrementAndGet()), Date.from(desde), Date.from(hasta),
                new X500Name("CN=" + cn), par.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
        builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
        if (dns.length > 0) {
            GeneralName[] nombres = new GeneralName[dns.length];
            for (int i = 0; i < dns.length; i++) {
                nombres[i] = new GeneralName(GeneralName.dNSName, dns[i]);
            }
            builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(nombres));
        }
        return new Credencial(par, firmar(builder, ca.par()));
    }

    static Credencial hoja(Credencial ca, String... dns) throws Exception {
        return hoja(ca, dns.length > 0 ? dns[0] : "sin-san", Instant.now().minus(Duration.ofHours(1)),
                Instant.now().plus(Duration.ofDays(1)), dns);
    }

    static SSLContext servidor(Credencial hoja, Credencial ca) throws Exception {
        KeyStore almacen = KeyStore.getInstance("PKCS12");
        almacen.load(null, null);
        char[] clave = "solo-pruebas".toCharArray();
        almacen.setKeyEntry("servidor", hoja.par().getPrivate(), clave, new Certificate[]{hoja.certificado(), ca.certificado()});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(almacen, clave);
        SSLContext contexto = SSLContext.getInstance("TLS");
        contexto.init(kmf.getKeyManagers(), null, null);
        return contexto;
    }

    /** Client context that trusts only these CAs, with the JDK's standard PKIX validation. */
    static SSLContext cliente(Credencial... confiables) throws Exception {
        KeyStore almacen = KeyStore.getInstance("PKCS12");
        almacen.load(null, null);
        for (int i = 0; i < confiables.length; i++) {
            almacen.setCertificateEntry("ca" + i, confiables[i].certificado());
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(almacen);
        SSLContext contexto = SSLContext.getInstance("TLS");
        contexto.init(null, tmf.getTrustManagers(), null);
        return contexto;
    }

    private static KeyPair par() throws Exception {
        KeyPairGenerator generador = KeyPairGenerator.getInstance("EC");
        generador.initialize(256);
        return generador.generateKeyPair();
    }

    private static X509Certificate firmar(JcaX509v3CertificateBuilder builder, KeyPair firmante) throws Exception {
        var holder = builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(firmante.getPrivate()));
        return new JcaX509CertificateConverter().getCertificate(holder);
    }
}
