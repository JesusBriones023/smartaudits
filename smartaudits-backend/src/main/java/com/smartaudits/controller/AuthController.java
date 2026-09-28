package com.smartaudits.controller;

import com.smartaudits.model.dto.AuthResponse;
import com.smartaudits.model.dto.LoginRequest;
import com.smartaudits.model.dto.RegisterRequest;
import com.smartaudits.security.AuthRateLimitService;
import com.smartaudits.security.CustomUserDetails;
import com.smartaudits.service.UsuarioService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UsuarioService usuarioService;
    private final AuthRateLimitService authRateLimitService;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest) {

        authRateLimitService.consumeRegisterAttempt(
                obtenerIpSegura(httpRequest)
        );

        return ResponseEntity.ok(
                usuarioService.register(request)
        );
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {

        String ip = obtenerIpSegura(httpRequest);

        authRateLimitService.checkLoginAllowed(
                ip,
                request.getEmail()
        );

        try {
            AuthResponse response =
                    usuarioService.login(request);

            authRateLimitService.recordLoginSuccess(
                    ip,
                    request.getEmail()
            );

            return ResponseEntity.ok(response);

        } catch (AuthenticationException exception) {

            authRateLimitService.recordLoginFailure(
                    ip,
                    request.getEmail()
            );

            throw exception;
        }
    }

    @DeleteMapping("/baja")
    public ResponseEntity<Void> darseDeBaja(
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        usuarioService.desactivarUsuario(
                userDetails.getUsuario().getId()
        );

        return ResponseEntity.noContent().build();
    }

    /**
     * Para decisiones de seguridad no confiamos directamente en
     * X-Forwarded-For enviado por el cliente.
     *
     * Cuando exista un reverse proxy propio y configurado como confiable,
     * podremos resolver aquí la IP original de forma controlada.
     */
    private String obtenerIpSegura(HttpServletRequest request) {
        String ip = request.getRemoteAddr();

        return ip == null || ip.isBlank()
                ? "unknown"
                : ip;
    }
}