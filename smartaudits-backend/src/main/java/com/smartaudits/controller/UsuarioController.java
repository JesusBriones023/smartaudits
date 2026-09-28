package com.smartaudits.controller;

import com.smartaudits.model.Usuario;
import com.smartaudits.model.dto.ActualizarPerfilRequest;
import com.smartaudits.model.dto.AuthResponse;
import com.smartaudits.model.dto.CambiarRolRequest;
import com.smartaudits.model.dto.UsuarioListadoResponse;
import com.smartaudits.security.CustomUserDetails;
import com.smartaudits.service.UsuarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Endpoints sobre usuarios:
 *   - PUT    /usuarios/perfil           — actualiza el perfil del usuario autenticado (cualquier rol)
 *   - GET    /usuarios                  — lista todos los usuarios (solo ADMIN)
 *   - PATCH  /usuarios/{id}/rol         — promover/degradar (solo ADMIN)
 *   - PATCH  /usuarios/{id}/desactivar  — baja lógica (solo ADMIN)
 *   - PATCH  /usuarios/{id}/reactivar   — reactivar cuenta desactivada (solo ADMIN)
 */
@RestController
@RequestMapping("/usuarios")
@RequiredArgsConstructor
public class UsuarioController {

    private final UsuarioService usuarioService;

    @PutMapping("/perfil")
    public ResponseEntity<AuthResponse> actualizarPerfil(
            @Valid @RequestBody ActualizarPerfilRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        AuthResponse response = usuarioService.actualizarPerfil(
                userDetails.getUsuario().getId(), request);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<List<UsuarioListadoResponse>> listarUsuarios(
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        if (!esAdmin(userDetails)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(usuarioService.listarTodos());
    }

    @PatchMapping("/{id}/rol")
    public ResponseEntity<UsuarioListadoResponse> cambiarRol(
            @PathVariable Long id,
            @Valid @RequestBody CambiarRolRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        if (!esAdmin(userDetails)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        UsuarioListadoResponse actualizado = usuarioService.cambiarRol(
                id, request.getNuevoRol(), userDetails.getUsuario().getId());
        return ResponseEntity.ok(actualizado);
    }

    @PatchMapping("/{id}/desactivar")
    public ResponseEntity<Void> desactivarUsuario(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        if (!esAdmin(userDetails)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        usuarioService.desactivarUsuarioPorAdmin(id, userDetails.getUsuario().getId());
        return ResponseEntity.noContent().build();
    }

    /**
     * Reactiva una cuenta previamente desactivada.
     * Toda reactivación queda registrada en los logs del backend para
     * garantizar trazabilidad (quién, a quién, cuándo).
     */
    @PatchMapping("/{id}/reactivar")
    public ResponseEntity<UsuarioListadoResponse> reactivarUsuario(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        if (!esAdmin(userDetails)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        UsuarioListadoResponse reactivado = usuarioService.reactivarUsuario(
                id, userDetails.getUsuario().getId());
        return ResponseEntity.ok(reactivado);
    }

    private boolean esAdmin(CustomUserDetails userDetails) {
        Usuario solicitante = userDetails.getUsuario();
        return userDetails.isEnabled() && "ADMIN".equals(solicitante.getRole().name());
    }
}
