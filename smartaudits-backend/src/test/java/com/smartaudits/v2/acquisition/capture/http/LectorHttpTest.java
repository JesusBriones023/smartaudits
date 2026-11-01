package com.smartaudits.v2.acquisition.capture.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LectorHttpTest {
    private static LectorHttp lector(String cabeceras, String cuerpo) {
        String respuesta = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n" + cabeceras + "\r\n" + cuerpo;
        return new LectorHttp(new ByteArrayInputStream(respuesta.getBytes(StandardCharsets.ISO_8859_1)),
                LimitesHttp.porDefecto());
    }

    @ParameterizedTest
    @ValueSource(ints = {11, 12, 28, 29, 30, 31})
    void controlsAtHeaderEdgesMustNotBeStrippedIntoValidLengths(int control) {
        for (String valor : new String[]{(char) control + "1", "1" + (char) control}) {
            var lector = lector("Content-Length: " + valor + "\r\n", "x");
            assertThatThrownBy(lector::leerCabecera).isInstanceOf(ErrorRespuesta.class)
                    .hasMessageContaining("valor de cabecera");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " \t", ","})
    void anEmptyTransferEncodingCannotFallBackToContentLength(String valor) {
        var lector = lector("Transfer-Encoding: " + valor + "\r\nContent-Length: 1\r\n", "x");
        assertThatThrownBy(() -> lector.leerCuerpo(lector.leerCabecera()))
                .isInstanceOf(ErrorRespuesta.class).hasMessageContaining("Transfer-Encoding");
    }

    @Test
    void ordinarySpaceAndTabPaddingStillWorks() throws Exception {
        var lector = lector("Content-Length: \t1\t \r\n", "x");
        assertThat(lector.leerCuerpo(lector.leerCabecera())).containsExactly((byte) 'x');
    }
}
