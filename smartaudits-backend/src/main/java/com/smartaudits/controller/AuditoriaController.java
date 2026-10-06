package com.smartaudits.controller;

import com.smartaudits.model.Usuario;
import com.smartaudits.model.Role;
import com.smartaudits.model.dto.AuditoriaRequest;
import com.smartaudits.model.dto.AuditoriaResponse;
import com.smartaudits.security.CustomUserDetails;
import com.smartaudits.service.AuditoriaService;
import com.smartaudits.service.UsuarioService;
import com.smartaudits.model.dto.PaginaResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.RequestParam;


@RestController
@RequestMapping("/auditorias")
@RequiredArgsConstructor
public class AuditoriaController {

    private final AuditoriaService auditoriaService;
    private final UsuarioService usuarioService;

    @PostMapping
    public ResponseEntity<AuditoriaResponse> crearAuditoria(
            @Valid @RequestBody AuditoriaRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails,
            HttpServletRequest httpRequest) {

        Usuario usuario = usuarioService.obtenerPorId(userDetails.getUsuario().getId())
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));

        AuditoriaResponse response = auditoriaService.crearAuditoria(
                request, usuario, obtenerIp(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/mias")
public ResponseEntity<PaginaResponse<AuditoriaResponse>> obtenerMisAuditorias(
        @AuthenticationPrincipal CustomUserDetails userDetails,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "10") int size) {

    Usuario usuario = usuarioService
            .obtenerPorId(userDetails.getUsuario().getId())
            .orElseThrow(() ->
                    new RuntimeException("Usuario no encontrado"));

    return ResponseEntity.ok(
            auditoriaService.obtenerMisAuditorias(
                    usuario.getId(),
                    page,
                    size
            )
    );
}

    @GetMapping
public ResponseEntity<PaginaResponse<AuditoriaResponse>> obtenerTodasLasAuditorias(
        @AuthenticationPrincipal CustomUserDetails userDetails,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "10") int size,
        @RequestParam(required = false, defaultValue = "") String usuario) {

    Usuario solicitante = usuarioService
            .obtenerPorId(userDetails.getUsuario().getId())
            .orElseThrow(() ->
                    new RuntimeException("Usuario no encontrado"));

    if (solicitante.getRole() != Role.ADMIN) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .build();
    }

    return ResponseEntity.ok(
            auditoriaService.obtenerTodasLasAuditorias(
                    page,
                    size,
                    usuario
            )
    );
}

    @GetMapping("/{id}")
    public ResponseEntity<AuditoriaResponse> obtenerAuditoriaPorId(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails userDetails,
            HttpServletRequest httpRequest) {

        Usuario usuario = usuarioService.obtenerPorId(userDetails.getUsuario().getId())
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));

        boolean isAdmin = usuario.getRole() == Role.ADMIN;

        AuditoriaResponse response = auditoriaService.obtenerAuditoriaPorId(
                id, usuario.getId(), isAdmin, usuario, obtenerIp(httpRequest));
        return ResponseEntity.ok(response);
    }

    /**
     * Registra en historial que el usuario descargó el informe.
     * La generación del PDF ocurre en el frontend.
     */
    @PostMapping("/{id}/descarga")
    public ResponseEntity<Void> registrarDescarga(
        @PathVariable Long id,
        @AuthenticationPrincipal CustomUserDetails userDetails,
        HttpServletRequest httpRequest) {

    Usuario usuario = usuarioService.obtenerPorId(userDetails.getUsuario().getId())
            .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));

    boolean isAdmin = usuario.getRole() == Role.ADMIN;

    auditoriaService.registrarDescarga(
            id,
            usuario.getId(),
            isAdmin,
            usuario,
            obtenerIp(httpRequest)
    );

    return ResponseEntity.ok().build();
}

    /**
     * Extrae la IP real teniendo en cuenta proxies.
     */
    private String obtenerIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
