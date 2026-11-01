package com.smartaudits.service.html;

/** El HTML recibido no produce un texto analizable (vacío o demasiado largo). */
public class ContenidoHtmlInvalidoException extends RuntimeException {
    public ContenidoHtmlInvalidoException(String message) {
        super(message);
    }
}
