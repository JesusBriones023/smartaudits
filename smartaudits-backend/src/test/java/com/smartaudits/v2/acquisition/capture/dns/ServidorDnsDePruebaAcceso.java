package com.smartaudits.v2.acquisition.capture.dns;

import com.smartaudits.v2.acquisition.capture.dns.ServidorDnsDePrueba.Registro;

import java.io.IOException;
import java.util.List;

/**
 * Public access, for tests in other packages, to a loopback DNS server with a fixed script:
 * www.ejemplo.es A 8.8.8.8 (truncated over UDP when {@code truncarA}, complete over TCP) and
 * AAAA NODATA.
 */
public final class ServidorDnsDePruebaAcceso implements AutoCloseable {

    private final ServidorDnsDePrueba servidor;

    public ServidorDnsDePruebaAcceso(boolean truncarA) throws IOException {
        servidor = new ServidorDnsDePrueba((q, tcp) -> {
            if (ServidorDnsDePrueba.tipo(q) == ResolvedorDnsDirecto.TYPE_AAAA) {
                return List.of(ServidorDnsDePrueba.respuesta(q, 0, List.of()));
            }
            if (truncarA && !tcp) {
                return List.of(ServidorDnsDePrueba.respuesta(q, ServidorDnsDePrueba.id(q), 0, true, List.of()));
            }
            return List.of(ServidorDnsDePrueba.respuesta(q, 0, List.of(Registro.a("www.ejemplo.es", 8, 8, 8, 8))));
        });
    }

    public int puerto() {
        return servidor.puerto();
    }

    public long consultasTcp() {
        return servidor.consultas().stream().filter(ServidorDnsDePrueba.Consulta::tcp).count();
    }

    @Override
    public void close() throws IOException {
        servidor.close();
    }
}
