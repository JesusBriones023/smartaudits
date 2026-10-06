package com.smartaudits.service;

import com.smartaudits.model.Role;
import com.smartaudits.model.TipoAccionAdmin;
import com.smartaudits.model.Usuario;
import com.smartaudits.model.dto.ActualizarPerfilRequest;
import com.smartaudits.model.dto.AuthResponse;
import com.smartaudits.model.dto.LoginRequest;
import com.smartaudits.model.dto.RegisterRequest;
import com.smartaudits.model.dto.UsuarioListadoResponse;
import com.smartaudits.repository.RolRepository;
import com.smartaudits.repository.UsuarioRepository;
import com.smartaudits.security.JwtUtil;
import com.smartaudits.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UsuarioService {

    private final UsuarioRepository usuarioRepository;
    private final RolRepository rolRepository;
    private final RolUsuarioService rolUsuarioService;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AuthenticationManager authenticationManager;
    private final HistorialAdminService historialAdminService;  // ← NUEVO

    public Optional<Usuario> obtenerPorId(Long id) {
        return usuarioRepository.findById(id);
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (usuarioRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("El email ya está registrado");
        }

        Usuario usuario = new Usuario();
        usuario.setNombre(request.getNombre());
        usuario.setEmail(request.getEmail());
        usuario.setPassword(passwordEncoder.encode(request.getPassword()));
        usuario.setActivo(true);
        usuario.setProtegido(false);
        rolUsuarioService.sincronizar(usuario, Role.CLIENTE);

        // Confirmar la persistencia antes de firmar la versión de revocación.
        Usuario savedUser = usuarioRepository.saveAndFlush(usuario);
        String token = jwtUtil.generateToken(new CustomUserDetails(savedUser));

        return new AuthResponse(
                token,
                savedUser.getNombre(),
                savedUser.getEmail(),
                savedUser.getRole().name(),
                savedUser.getId()
        );
    }

    public AuthResponse login(LoginRequest request) {
        var authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.getEmail(),
                        request.getPassword()
                )
        );

        // Firmar exactamente la identidad/versión cuya contraseña se verificó.
        CustomUserDetails userDetails = (CustomUserDetails) authentication.getPrincipal();
        Usuario usuario = userDetails.getUsuario();
        String token = jwtUtil.generateToken(userDetails);

        return new AuthResponse(
                token,
                usuario.getNombre(),
                usuario.getEmail(),
                usuario.getRole().name(),
                usuario.getId()
        );
    }

    // ======================================================================
    //  BAJA LÓGICA (soft delete) — preserva auditorías e historial
    // ======================================================================

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void desactivarUsuario(Long usuarioId) {
        bloquearAdministracion();
        Usuario usuario = usuarioRepository.findById(usuarioId)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));

        comprobarBaja(usuario);
        if (Boolean.FALSE.equals(usuario.getActivo())) {
            log.info("El usuario {} ya estaba desactivado", usuario.getEmail());
            return;
        }

        usuario.setActivo(false);
        revocarTokens(usuario);
        usuarioRepository.save(usuario);
        log.info("Usuario {} desactivado correctamente", usuario.getEmail());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void desactivarUsuarioPorAdmin(Long usuarioIdObjetivo, Long adminId) {
        bloquearAdministracion();
        Usuario admin = exigirAdminActivo(adminId);

        if (admin.getId().equals(usuarioIdObjetivo)) {
            throw new AccessDeniedException("No puedes desactivar tu propia cuenta de administrador desde aquí. " +
                    "Usa el botón 'Darse de baja' del menú lateral si quieres hacerlo.");
        }

        Usuario objetivo = usuarioRepository.findById(usuarioIdObjetivo)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));

        desactivarUsuario(usuarioIdObjetivo);

        // Registrar la acción en el historial administrativo
        historialAdminService.registrarAccion(
                admin, objetivo, TipoAccionAdmin.DESACTIVAR,
                "Usuario desactivado (baja lógica)");
    }

    // ======================================================================
    //  REACTIVACIÓN DE CUENTA (solo ADMIN, con trazabilidad)
    // ======================================================================

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UsuarioListadoResponse reactivarUsuario(Long usuarioIdObjetivo, Long adminId) {
        bloquearAdministracion();
        Usuario admin = exigirAdminActivo(adminId);

        Usuario objetivo = usuarioRepository.findById(usuarioIdObjetivo)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));

        if (Boolean.TRUE.equals(objetivo.getActivo())) {
            log.info("El usuario {} ya estaba activo. Reactivación omitida.", objetivo.getEmail());
            return toListadoResponse(objetivo);
        }

        objetivo.setActivo(true);
        revocarTokens(objetivo);
        Usuario actualizado = usuarioRepository.save(objetivo);

        // Registrar la acción en el historial administrativo
        historialAdminService.registrarAccion(
                admin, actualizado, TipoAccionAdmin.REACTIVAR,
                "Cuenta reactivada por administrador");

        return toListadoResponse(actualizado);
    }

    // ======================================================================
    //  MODIFICACIÓN DE PERFIL (autoservicio del usuario autenticado)
    // ======================================================================

    @Transactional
    public AuthResponse actualizarPerfil(Long usuarioId, ActualizarPerfilRequest request) {
        Usuario usuario = usuarioRepository.findById(usuarioId)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));
        if (!Boolean.TRUE.equals(usuario.getActivo())) {
            throw new AccessDeniedException("Cuenta desactivada");
        }

        String nuevoEmail = request.getEmail().trim();
        boolean cambiaEmail = !usuario.getEmail().equalsIgnoreCase(nuevoEmail);
        if (cambiaEmail) {
            if (usuarioRepository.existsByEmail(nuevoEmail)) {
                throw new RuntimeException("El email ya está en uso por otro usuario");
            }
            usuario.setEmail(nuevoEmail);
        }

        usuario.setNombre(request.getNombre().trim());

        boolean quiereCambiarPassword = request.getPasswordNueva() != null
                && !request.getPasswordNueva().isBlank();

        if (quiereCambiarPassword) {
            if (request.getPasswordActual() == null || request.getPasswordActual().isBlank()) {
                throw new RuntimeException("Para cambiar la contraseña debes indicar la contraseña actual");
            }
            if (!passwordEncoder.matches(request.getPasswordActual(), usuario.getPassword())) {
                throw new RuntimeException("La contraseña actual es incorrecta");
            }
            if (request.getPasswordNueva().length() < 6) {
                throw new RuntimeException("La nueva contraseña debe tener al menos 6 caracteres");
            }
            usuario.setPassword(passwordEncoder.encode(request.getPasswordNueva()));
            log.info("Contraseña actualizada para el usuario {}", usuario.getEmail());
        }

        if (cambiaEmail || quiereCambiarPassword) {
            revocarTokens(usuario);
        }
        Usuario actualizado = usuarioRepository.saveAndFlush(usuario);
        log.info("Perfil actualizado: {}", actualizado.getEmail());

        String token = jwtUtil.generateToken(new CustomUserDetails(actualizado));

        return new AuthResponse(
                token,
                actualizado.getNombre(),
                actualizado.getEmail(),
                actualizado.getRole().name(),
                actualizado.getId()
        );
    }

    // ======================================================================
    //  GESTIÓN DE USUARIOS (panel de administración)
    // ======================================================================

    public List<UsuarioListadoResponse> listarTodos() {
        return usuarioRepository.findAll().stream()
                .map(this::toListadoResponse)
                .sorted((a, b) -> {
                    int cmpRol = a.getRole().compareTo(b.getRole());
                    if (cmpRol != 0) return -cmpRol;
                    if (a.getFechaRegistro() == null) return 1;
                    if (b.getFechaRegistro() == null) return -1;
                    return b.getFechaRegistro().compareTo(a.getFechaRegistro());
                })
                .collect(Collectors.toList());
    }

    private UsuarioListadoResponse toListadoResponse(Usuario u) {
        UsuarioListadoResponse r = new UsuarioListadoResponse();
        r.setId(u.getId());
        r.setNombre(u.getNombre());
        r.setEmail(u.getEmail());
        r.setRole(u.getRole().name());
        r.setActivo(u.getActivo());
        r.setProtegido(u.getProtegido() != null ? u.getProtegido() : false);
        r.setFechaRegistro(u.getFechaRegistro());
        r.setNumeroAuditorias(u.getAuditorias() != null ? u.getAuditorias().size() : 0);
        return r;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UsuarioListadoResponse cambiarRol(Long usuarioIdObjetivo, String nuevoRol, Long adminId) {
        bloquearAdministracion();

        Role rolDestino;
        try {
            rolDestino = Role.valueOf(nuevoRol);
        } catch (IllegalArgumentException ex) {
            throw new RuntimeException("Rol no válido. Valores permitidos: CLIENTE, ADMIN.");
        }

        Usuario admin = exigirAdminActivo(adminId);

        if (admin.getId().equals(usuarioIdObjetivo)) {
            throw new AccessDeniedException("No puedes cambiar tu propio rol.");
        }

        Usuario objetivo = usuarioRepository.findById(usuarioIdObjetivo)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));

        if (Boolean.TRUE.equals(objetivo.getProtegido())) {
            throw new AccessDeniedException("Este usuario está protegido y su rol no puede modificarse.");
        }

        if (objetivo.getRole() == rolDestino) {
            rolUsuarioService.sincronizar(objetivo, rolDestino);
            return toListadoResponse(objetivo);
        }

        if (objetivo.getRole() == Role.ADMIN && rolDestino == Role.CLIENTE
                && Boolean.TRUE.equals(objetivo.getActivo())) {
            long adminsActivos = usuarioRepository.countByRoleAndActivoTrue(Role.ADMIN);
            if (adminsActivos <= 1) {
                throw new AccessDeniedException("No se puede degradar al último administrador activo del sistema.");
            }
        }

        Role rolAnterior = objetivo.getRole();
        rolUsuarioService.sincronizar(objetivo, rolDestino);
        revocarTokens(objetivo);

        Usuario actualizado = usuarioRepository.save(objetivo);

        // Registrar la acción en el historial administrativo
        TipoAccionAdmin tipoAccion = (rolDestino == Role.ADMIN)
                ? TipoAccionAdmin.PROMOVER
                : TipoAccionAdmin.DEGRADAR;
        String detalles = String.format("Cambio de rol: %s → %s", rolAnterior, rolDestino);

        historialAdminService.registrarAccion(admin, actualizado, tipoAccion, detalles);

        return toListadoResponse(actualizado);
    }

    private void revocarTokens(Usuario usuario) {
        usuario.setTokenVersion(Math.incrementExact(usuario.getTokenVersion()));
    }

    private void bloquearAdministracion() {
        rolRepository.bloquearAdministracion()
                .orElseThrow(() -> new IllegalStateException("Falta el rol ADMIN del sistema"));
    }

    private Usuario exigirAdminActivo(Long id) {
        Usuario admin = usuarioRepository.findById(id)
                .orElseThrow(() -> new AccessDeniedException("Administrador no encontrado"));
        if (!Boolean.TRUE.equals(admin.getActivo()) || admin.getRole() != Role.ADMIN) {
            throw new AccessDeniedException("Se requiere un administrador activo");
        }
        return admin;
    }

    private void comprobarBaja(Usuario usuario) {
        if (Boolean.TRUE.equals(usuario.getProtegido())) {
            throw new AccessDeniedException("Este usuario está protegido y no puede darse de baja");
        }
        if (usuario.getRole() == Role.ADMIN && Boolean.TRUE.equals(usuario.getActivo())
                && usuarioRepository.countByRoleAndActivoTrue(Role.ADMIN) <= 1) {
            throw new AccessDeniedException("No se puede desactivar al último administrador activo");
        }
    }
}
