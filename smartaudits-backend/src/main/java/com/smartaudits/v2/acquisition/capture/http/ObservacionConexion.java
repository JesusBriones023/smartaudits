package com.smartaudits.v2.acquisition.capture.http;

import com.smartaudits.v2.acquisition.destination.DestinationUrl;
import com.smartaudits.v2.acquisition.destination.IpAddress;

/**
 * What the transport observed on one connection, to be checked against the policy's approval.
 *
 * @param url            destination requested on this connection
 * @param peer           address the connection reached
 * @param puerto         port the connection reached
 * @param peerObservado  the peer was read from the socket (false: declared by a test double)
 * @param tls            TLS was negotiated
 * @param sni            server name sent in the ClientHello
 * @param identidadTls   host name the certificate was verified against (JSSE and the independent check)
 * @param protocoloTls   negotiated protocol, e.g. TLSv1.3
 * @param host           Host header sent
 */
public record ObservacionConexion(DestinationUrl url, IpAddress peer, int puerto, boolean peerObservado, boolean tls,
                                  String sni, String identidadTls, String protocoloTls, String host) {
}
