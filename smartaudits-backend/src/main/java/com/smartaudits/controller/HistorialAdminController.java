package com.smartaudits.controller;

import com.smartaudits.model.Usuario;
import com.smartaudits.model.dto.HistorialAccionAdminResponse;
import com.smartaudits.security.CustomUserDetails;
import com.smartaudits.service.HistorialAdminService;
import com.smartaudits.service.UsuarioService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Endpoints sobre el historial de acciones administrativas.
 * Solo accesibles para usuarios con rol ADMIN.
 */
@RestController
@RequestMapping("/admin/historial")
@RequiredArgsConstructor
public class HistorialAdminController {

    private final HistorialAdminService historialAdminService;
    private final UsuarioService usuarioService;

    @GetMapping
    public ResponseEntity<List<HistorialAccionAdminResponse>> listar(
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        if (!esAdmin(userDetails)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(historialAdminService.listarTodas());
    }

    private boolean esAdmin(CustomUserDetails userDetails) {
        Usuario solicitante = usuarioService.obtenerPorId(userDetails.getUsuario().getId())
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));
        return "ADMIN".equals(solicitante.getRole().name());
    }
}
