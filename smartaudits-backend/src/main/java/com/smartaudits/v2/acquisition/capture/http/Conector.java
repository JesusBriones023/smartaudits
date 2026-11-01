package com.smartaudits.v2.acquisition.capture.http;

import com.smartaudits.v2.acquisition.destination.IpAddress;

import java.io.IOException;
import java.time.Duration;

/** Opens the TCP connection to one approved address. It receives an IP, never a host name. */
public interface Conector {

    Conexion conectar(IpAddress ip, int puerto, Duration timeout) throws IOException;
}
