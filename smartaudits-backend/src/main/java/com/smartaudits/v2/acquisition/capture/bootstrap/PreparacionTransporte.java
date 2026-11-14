package com.smartaudits.v2.acquisition.capture.bootstrap;

import com.smartaudits.v2.acquisition.capture.LimitesAdquisicion;
import com.smartaudits.v2.acquisition.capture.TransporteHttpReal;
import com.smartaudits.v2.acquisition.capture.dns.ResolvedorDnsDirecto;
import com.smartaudits.v2.acquisition.capture.http.ClienteHttpFijado;
import com.smartaudits.v2.acquisition.capture.http.ConectorDirecto;
import com.smartaudits.v2.acquisition.capture.http.LimitesHttp;

import javax.net.ssl.SSLContext;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;

/**
 * Prepared composition only: package-private, no Spring annotations, bean or production caller.
 * Future activation requires a separately authorized wiring change, not a property or request.
 */
final class PreparacionTransporte {
    private PreparacionTransporte() {
    }

    static TransporteHttpReal preparar(ConfiguracionDnsTransporte dns) {
        var resolver = new ResolvedorDnsDirecto(dns.direccion(), dns.port(), dns.timeout());
        try {
            var cliente = new ClienteHttpFijado(new ConectorDirecto(), SSLContext.getDefault().getSocketFactory(),
                    LimitesHttp.porDefecto());
            // TransporteHttpReal builds one DestinationPolicy per capture over this deadline-aware
            // resolver, re-evaluating every hop; the 20 s acquisition deadline is a code default (PV-15).
            return new TransporteHttpReal(resolver, cliente, Clock.systemUTC(), LimitesAdquisicion.porDefecto(),
                    System::nanoTime);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("No se pudo preparar el contexto TLS estándar", e);
        }
    }
}
