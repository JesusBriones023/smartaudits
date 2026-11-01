package com.smartaudits.v2.acquisition.capture.http;

import java.io.IOException;

/** The server's response breaks the strict HTTP/1.1 subset or a size limit. */
final class ErrorRespuesta extends IOException {
    ErrorRespuesta(String message) {
        super(message);
    }
}
