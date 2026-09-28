package com.smartaudits.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(
            MethodArgumentNotValidException exception) {

        Map<String, String> fieldErrors = new LinkedHashMap<>();

        exception.getBindingResult()
                .getFieldErrors()
                .forEach(error ->
                        fieldErrors.putIfAbsent(
                                error.getField(),
                                error.getDefaultMessage()
                        )
                );

        String message = fieldErrors.values()
                .stream()
                .findFirst()
                .orElse("Los datos enviados no son válidos");

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(errorBody(
                        HttpStatus.BAD_REQUEST,
                        "VALIDATION_ERROR",
                        message,
                        fieldErrors
                ));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleMalformedJson(
            HttpMessageNotReadableException exception) {

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(errorBody(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_JSON",
                        "El cuerpo de la petición no contiene un JSON válido",
                        Map.of()
                ));
    }

    private Map<String, Object> errorBody(
            HttpStatus status,
            String error,
            String message,
            Map<String, String> fieldErrors) {

        Map<String, Object> body = new LinkedHashMap<>();

        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", error);
        body.put("message", message);

        if (!fieldErrors.isEmpty()) {
            body.put("fieldErrors", fieldErrors);
        }

        return body;
    }
    @ExceptionHandler(com.smartaudits.security.RateLimitExceededException.class)
        public ResponseEntity<Map<String, Object>> handleRateLimit(
                com.smartaudits.security.RateLimitExceededException exception) {

        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .header(
                        "Retry-After",
                        String.valueOf(
                                exception.getRetryAfterSeconds()
                        )
                )
                .body(errorBody(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "RATE_LIMIT_EXCEEDED",
                        exception.getMessage(),
                        Map.of()
                ));
}
        @ExceptionHandler(
        com.smartaudits.service.AuditQuotaExceededException.class
        )
        public ResponseEntity<Map<String, Object>> handleAuditQuota(
                com.smartaudits.service.AuditQuotaExceededException exception) {

        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .header(
                        "Retry-After",
                        String.valueOf(
                                exception.getRetryAfterSeconds()
                        )
                )
                .body(errorBody(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "AUDIT_QUOTA_EXCEEDED",
                        exception.getMessage(),
                        Map.of()
                ));
        }
}