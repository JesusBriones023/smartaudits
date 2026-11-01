package com.smartaudits.v2.acquisition.capture.http;

import com.smartaudits.v2.acquisition.capture.CapturaException;
import com.smartaudits.v2.acquisition.destination.DestinationUrl;
import com.smartaudits.v2.acquisition.destination.IpAddress;
import com.smartaudits.v2.acquisition.destination.UrlParseResult;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.net.ssl.SSLSocketFactory;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ClienteHttpPlazoTest {
    @ParameterizedTest
    @ValueSource(ints = {50, 5000})
    void connectingReceivesTheSmallerOfItsOwnLimitAndTheRemainingTotal(int conexionMs) {
        var presupuesto = new AtomicReference<Duration>();
        Conector conector = (ip, puerto, timeout) -> {
            presupuesto.set(timeout);
            throw new SocketTimeoutException("no connection");
        };
        var limites = new LimitesHttp(Duration.ofMillis(conexionMs), Duration.ofMillis(100), Duration.ofMillis(200),
                1024, 10, 1024, 1024, 0);
        var cliente = new ClienteHttpFijado(conector, mock(SSLSocketFactory.class), limites);
        var url = ((UrlParseResult.Parsed) DestinationUrl.parse("http://www.ejemplo.es/")).url();
        assertThatThrownBy(() -> cliente.obtener(url, IpAddress.ipv4(new byte[]{127, 0, 0, 1})))
                .isInstanceOf(CapturaException.class).hasCauseInstanceOf(SocketTimeoutException.class);
        assertThat(presupuesto.get()).isPositive().isLessThanOrEqualTo(limites.total())
                .isLessThanOrEqualTo(limites.conexion());
    }
}
