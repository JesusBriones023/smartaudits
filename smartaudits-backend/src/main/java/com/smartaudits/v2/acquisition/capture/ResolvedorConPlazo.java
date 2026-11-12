package com.smartaudits.v2.acquisition.capture;

import com.smartaudits.v2.acquisition.destination.DnsAnswer;
import com.smartaudits.v2.acquisition.destination.HostResolver;
import com.smartaudits.v2.acquisition.destination.ValidatedHost;

import java.util.Objects;

/**
 * Capture-layer resolver that receives the capture's deadline (PV-15). The frozen
 * {@link HostResolver} contract of the destination package is unchanged: {@link TransporteHttpReal}
 * builds, for each capture, a {@code DestinationPolicy} whose resolver delegates here with that
 * capture's deadline. An implementation must end every blocking wait by the deadline, must not start
 * a query after it, and must leave the interrupt flag set when it reports an interruption.
 */
@FunctionalInterface
public interface ResolvedorConPlazo {

    DnsAnswer resolver(ValidatedHost host, PlazoCaptura plazo) throws InterruptedException;

    /**
     * The given resolver itself if it already honours deadlines; otherwise an adapter that ignores
     * the deadline. Such a resolver cannot be bounded or stopped: the transport only checks the
     * deadline after it returns (test doubles; the prepared composition uses a deadline-aware one).
     */
    static ResolvedorConPlazo de(HostResolver resolver) {
        Objects.requireNonNull(resolver, "resolver");
        if (resolver instanceof ResolvedorConPlazo conPlazo) {
            return conPlazo;
        }
        return (host, plazo) -> resolver.resolve(host);
    }
}
