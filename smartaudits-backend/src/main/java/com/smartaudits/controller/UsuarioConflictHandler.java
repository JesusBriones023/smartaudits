package com.smartaudits.controller;

import jakarta.persistence.OptimisticLockException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice(assignableTypes = {UsuarioController.class, AuthController.class})
public class UsuarioConflictHandler {
    @ExceptionHandler({OptimisticLockingFailureException.class, OptimisticLockException.class})
    public ResponseEntity<Map<String, String>> conflictoOptimista() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "message", "La cuenta se ha modificado en otra petición. Recarga los datos e inténtalo de nuevo."));
    }
}
