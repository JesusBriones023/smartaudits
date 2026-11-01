package com.smartaudits.controller;

import com.smartaudits.model.Role;
import com.smartaudits.model.Usuario;
import com.smartaudits.model.dto.AuditoriaConjuntaRequest;
import com.smartaudits.model.dto.AuditoriaConjuntaResponse;
import com.smartaudits.model.dto.PaginaResponse;
import com.smartaudits.security.CustomUserDetails;
import com.smartaudits.service.AuditoriaConjuntaService;
import com.smartaudits.service.UsuarioService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Auditorías conjuntas de varios HTML pegados; ninguna URL se descarga. */
@RestController
@RequestMapping("/auditorias-conjuntas")
@RequiredArgsConstructor
public class AuditoriaConjuntaController {

    private final AuditoriaConjuntaService service;
    private final UsuarioService usuarioService;

    @PostMapping
    public ResponseEntity<AuditoriaConjuntaResponse> crear(
            @Valid @RequestBody AuditoriaConjuntaRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails,
            HttpServletRequest httpRequest) {
        Usuario usuario = usuario(userDetails);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.crear(request, usuario, ip(httpRequest)));
    }

    @GetMapping("/mias")
    public ResponseEntity<PaginaResponse<AuditoriaConjuntaResponse.Resumen>> listarMias(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(service.listarMias(usuario(userDetails).getId(), page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AuditoriaConjuntaResponse> obtener(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails userDetails,
            HttpServletRequest httpRequest) {
        Usuario usuario = usuario(userDetails);
        return ResponseEntity.ok(service.obtener(id, usuario, usuario.getRole() == Role.ADMIN, ip(httpRequest)));
    }

    private Usuario usuario(CustomUserDetails userDetails) {
        return usuarioService.obtenerPorId(userDetails.getUsuario().getId())
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));
    }

    /** Igual que AuditoriaController: IP real teniendo en cuenta proxies. */
    private String ip(HttpServletRequest request) {
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
