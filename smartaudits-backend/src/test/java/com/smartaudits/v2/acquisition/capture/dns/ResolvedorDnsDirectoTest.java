package com.smartaudits.v2.acquisition.capture.dns;

import com.smartaudits.v2.acquisition.capture.dns.ServidorDnsDePrueba.Consulta;
import com.smartaudits.v2.acquisition.capture.dns.ServidorDnsDePrueba.Registro;
import com.smartaudits.v2.acquisition.destination.DestinationDecision;
import com.smartaudits.v2.acquisition.destination.DestinationPolicy;
import com.smartaudits.v2.acquisition.destination.RejectionCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;

import static com.smartaudits.v2.acquisition.capture.dns.ResolvedorDnsDirecto.TYPE_A;
import static com.smartaudits.v2.acquisition.capture.dns.ResolvedorDnsDirecto.TYPE_AAAA;
import static com.smartaudits.v2.acquisition.capture.dns.ServidorDnsDePrueba.respuesta;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The real resolver against a scripted DNS server on 127.0.0.1, then the unchanged ADR-0002 policy.
 * Wire format, matching, TCP fallback and failure handling are exercised for real over loopback;
 * a physical recursive resolver and its network path remain a physical validation (QD-01..09).
 */
class ResolvedorDnsDirectoTest {
    static final byte[] V6 = {0x2a, 0x00, 0x14, 0x50, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
    static final String URL = "https://www.ejemplo.es/";

    ServidorDnsDePrueba servidor;

    @AfterEach
    void cerrar() throws IOException {
        if (servidor != null) {
            servidor.close();
        }
    }

    /** Script by query type: answers for A and AAAA. */
    DestinationPolicy politica(Function<byte[], List<byte[]>> a, Function<byte[], List<byte[]>> aaaa, Duration timeout) throws IOException {
        servidor = new ServidorDnsDePrueba((q, tcp) -> ServidorDnsDePrueba.tipo(q) == TYPE_A ? a.apply(q) : aaaa.apply(q));
        return DestinationPolicy.using(new ResolvedorDnsDirecto(new byte[]{127, 0, 0, 1}, servidor.puerto(), timeout));
    }

    DestinationPolicy politica(Function<byte[], List<byte[]>> a, Function<byte[], List<byte[]>> aaaa) throws IOException {
        return politica(a, aaaa, Duration.ofMillis(800));
    }

    static Function<byte[], List<byte[]>> con(Registro... registros) {
        return q -> List.of(respuesta(q, 0, List.of(registros)));
    }

    static Function<byte[], List<byte[]>> rcode(int rcode) {
        return q -> List.of(respuesta(q, rcode, List.of()));
    }

    static RejectionCode codigo(DestinationDecision d) {
        assertThat(d).isInstanceOf(DestinationDecision.Rejected.class);
        return ((DestinationDecision.Rejected) d).code();
    }

    @Test
    void publicAnswerIsApprovedWithExactlyTwoAbsoluteQueries() throws IOException {
        var p = politica(con(Registro.a("www.ejemplo.es", 8, 8, 8, 8)), con(Registro.aaaa("www.ejemplo.es", V6)));

        DestinationDecision d = p.evaluate(URL);

        assertThat(d).isInstanceOf(DestinationDecision.Approved.class);
        assertThat(((DestinationDecision.Approved) d).addresses()).hasSize(2);
        assertThat(servidor.consultas()).containsExactly(
                new Consulta("www.ejemplo.es", TYPE_A, false, true, true),
                new Consulta("www.ejemplo.es", TYPE_AAAA, false, true, true));
    }

    @Test
    void forbiddenOrMixedAddressesRejectTheWholeAnswer() throws IOException {
        assertThat(codigo(politica(con(Registro.a("www.ejemplo.es", 10, 0, 0, 1)), rcode(0)).evaluate(URL)))
                .isEqualTo(RejectionCode.ADDRESS_FORBIDDEN);
        cerrar();
        assertThat(codigo(politica(con(Registro.a("www.ejemplo.es", 8, 8, 8, 8), Registro.a("www.ejemplo.es", 127, 0, 0, 1)),
                rcode(0)).evaluate(URL))).isEqualTo(RejectionCode.ADDRESS_MIXED);
        cerrar();
        assertThat(codigo(politica(con(Registro.a("www.ejemplo.es", 8, 8, 8, 8)),
                con(Registro.aaaa("www.ejemplo.es", new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff, 127, 0, 0, 1})))
                .evaluate(URL))).isEqualTo(RejectionCode.ADDRESS_MIXED);
    }

    @Test
    void failuresAndEmptyAnswersAreNeverApproved() throws IOException {
        // AAAA SERVFAIL: no fallback to the A family.
        assertThat(codigo(politica(con(Registro.a("www.ejemplo.es", 8, 8, 8, 8)), rcode(2)).evaluate(URL)))
                .isEqualTo(RejectionCode.DNS_FAILURE);
        cerrar();
        assertThat(codigo(politica(rcode(3), rcode(3)).evaluate(URL))).isEqualTo(RejectionCode.DNS_EMPTY);
        cerrar();
        assertThat(codigo(politica(rcode(0), rcode(0)).evaluate(URL))).isEqualTo(RejectionCode.DNS_EMPTY);
        cerrar();
        assertThat(codigo(politica(rcode(5), rcode(0)).evaluate(URL))).isEqualTo(RejectionCode.DNS_FAILURE);
    }

    @Test
    void aSilentServerTimesOutAsFailure() throws IOException {
        var p = politica(q -> List.of(), q -> List.of(), Duration.ofMillis(300));
        long inicio = System.nanoTime();
        assertThat(codigo(p.evaluate(URL))).isEqualTo(RejectionCode.DNS_FAILURE);
        assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void spoofedDatagramsAreIgnoredAndOnlySpoofsFail() throws IOException {
        Function<byte[], List<byte[]>> conFalso = q -> List.of(
                respuesta(q, ServidorDnsDePrueba.id(q) ^ 0x5A5A, 0, false, List.of(Registro.a("www.ejemplo.es", 10, 0, 0, 9))),
                respuesta(q, 0, List.of(Registro.a("www.ejemplo.es", 8, 8, 8, 8))));
        assertThat(politica(conFalso, rcode(0)).evaluate(URL)).isInstanceOf(DestinationDecision.Approved.class);
        cerrar();
        Function<byte[], List<byte[]>> soloFalsos = q -> List.of(
                respuesta(q, ServidorDnsDePrueba.id(q) ^ 0x1111, 0, false, List.of(Registro.a("www.ejemplo.es", 8, 8, 8, 8))));
        assertThat(codigo(politica(soloFalsos, rcode(0), Duration.ofMillis(300)).evaluate(URL))).isEqualTo(RejectionCode.DNS_FAILURE);
    }

    @Test
    void truncatedUdpAnswersAreRepeatedOverTcp() throws IOException {
        servidor = new ServidorDnsDePrueba((q, tcp) -> {
            if (ServidorDnsDePrueba.tipo(q) == TYPE_AAAA) {
                return List.of(respuesta(q, 0, List.of()));
            }
            return tcp ? List.of(respuesta(q, 0, List.of(Registro.a("www.ejemplo.es", 8, 8, 8, 8))))
                    : List.of(respuesta(q, ServidorDnsDePrueba.id(q), 0, true, List.of()));
        });
        var p = DestinationPolicy.using(new ResolvedorDnsDirecto(new byte[]{127, 0, 0, 1}, servidor.puerto(), Duration.ofSeconds(1)));
        assertThat(p.evaluate(URL)).isInstanceOf(DestinationDecision.Approved.class);
        assertThat(servidor.consultas()).extracting(Consulta::tipo, Consulta::tcp)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(TYPE_A, false), org.assertj.core.groups.Tuple.tuple(TYPE_A, true),
                        org.assertj.core.groups.Tuple.tuple(TYPE_AAAA, false));
    }

    @Test
    void cnameChainsAreFollowedAndInconsistentAnswersFail() throws IOException {
        var p = politica(con(Registro.cname("www.ejemplo.es", "cdn.ejemplo.net"), Registro.a("cdn.ejemplo.net", 8, 8, 4, 4)),
                con(Registro.cname("www.ejemplo.es", "cdn.ejemplo.net")));
        assertThat(p.evaluate(URL)).isInstanceOf(DestinationDecision.Approved.class);
        cerrar();
        // An address of a name outside the chain: inconsistent, never used.
        assertThat(codigo(politica(con(Registro.a("otro.ejemplo.es", 8, 8, 8, 8)), rcode(0)).evaluate(URL)))
                .isEqualTo(RejectionCode.DNS_FAILURE);
        cerrar();
        // A record of the wrong length reaches the policy, which rejects it.
        assertThat(codigo(politica(q -> List.of(respuesta(q, 0, List.of(new Registro("www.ejemplo.es", TYPE_A, V6, null)))),
                rcode(0)).evaluate(URL))).isEqualTo(RejectionCode.DNS_INVALID);
        cerrar();
        // Different chains per family: rejected by the policy.
        assertThat(codigo(politica(con(Registro.cname("www.ejemplo.es", "a.ejemplo.net"), Registro.a("a.ejemplo.net", 8, 8, 8, 8)),
                con(Registro.cname("www.ejemplo.es", "b.ejemplo.net"))).evaluate(URL))).isEqualTo(RejectionCode.DNS_INVALID);
    }

    @Test
    void responsesToAnotherQuestionAreIgnored() throws IOException {
        Function<byte[], List<byte[]>> otraPregunta = q -> {
            byte[] r = respuesta(q, 0, List.of(Registro.a("www.ejemplo.es", 8, 8, 8, 8)));
            r[13] = 'x'; // the echoed question names another host
            return List.of(r);
        };
        assertThat(codigo(politica(otraPregunta, rcode(0), Duration.ofMillis(300)).evaluate(URL))).isEqualTo(RejectionCode.DNS_FAILURE);
    }

    @Test
    void theServerMustBeAnIpLiteralWithSaneLimits() {
        assertThatThrownBy(() -> new ResolvedorDnsDirecto(new byte[]{1, 2, 3}, 53, Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResolvedorDnsDirecto(new byte[]{8, 8, 8, 8}, 0, Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResolvedorDnsDirecto(new byte[]{8, 8, 8, 8}, 53, Duration.ofMinutes(1))).isInstanceOf(IllegalArgumentException.class);
    }
}
