package com.smartaudits.config;

import com.smartaudits.model.Rol;
import com.smartaudits.model.Role;
import com.smartaudits.model.Usuario;
import com.smartaudits.repository.RolRepository;
import com.smartaudits.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Inicializa los datos base de la aplicación al arrancar.
 *
 * 1. Crea los roles CLIENTE y ADMIN en la tabla `roles` si no existen.
 * 2. Si NO hay ningún ADMIN en BD y el bootstrap está habilitado,
 *    crea automáticamente un administrador raíz con las credenciales
 *    suministradas vía variables de entorno. Este admin se marca como
 *    `protegido = true` para impedir que pueda ser degradado, desactivado
 *    o eliminado por otros administradores. Garantiza que el sistema
 *    siempre tenga un administrador raíz funcional.
 *
 * SEGURIDAD: NO existen credenciales por defecto. Si las variables de entorno
 * SMARTAUDITS_ADMIN_EMAIL y SMARTAUDITS_ADMIN_PASSWORD no están definidas,
 * el bootstrap se omite y se registra un aviso claro en los logs. Esto evita
 * que credenciales hardcoded acaben en el repositorio o en producción.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final RolRepository rolRepository;
    private final UsuarioRepository usuarioRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.admin.bootstrap.enabled:false}")
    private boolean bootstrapEnabled;

    @Value("${app.admin.bootstrap.email:}")
    private String bootstrapEmail;

    @Value("${app.admin.bootstrap.password:}")
    private String bootstrapPassword;

    @Value("${app.admin.bootstrap.nombre:Administrador}")
    private String bootstrapNombre;

    @Value("${app.data.initializer.enabled:true}")
    private boolean initializerEnabled;

    @Override
    @Transactional
    public void run(String... args) {
        if (!initializerEnabled) {
            log.info("Inicialización de datos base desactivada por configuración");
            return;
        }

        inicializarRoles();
        inicializarAdminPrincipal();
    }

    private void inicializarRoles() {
        if (rolRepository.findByNombre("CLIENTE").isEmpty()) {
            Rol rolCliente = new Rol();
            rolCliente.setNombre("CLIENTE");
            rolCliente.setDescripcion("Usuario estándar de la plataforma SmartAudits");
            rolRepository.save(rolCliente);
            log.info("Rol CLIENTE creado automáticamente");
        }

        if (rolRepository.findByNombre("ADMIN").isEmpty()) {
            Rol rolAdmin = new Rol();
            rolAdmin.setNombre("ADMIN");
            rolAdmin.setDescripcion("Administrador con acceso total al sistema");
            rolRepository.save(rolAdmin);
            log.info("Rol ADMIN creado automáticamente");
        }

        log.info("Roles del sistema inicializados correctamente");
    }

    /**
     * Crea el administrador raíz si no existe ningún admin todavía.
     * Solo se ejecuta una vez en la vida del sistema (la primera vez que arranca).
     */
    private void inicializarAdminPrincipal() {
        if (!bootstrapEnabled) {
            log.info("Bootstrap del admin raíz desactivado por configuración");
            return;
        }

        // Comprobar si ya hay algún admin en el sistema
        boolean yaHayAdmin = usuarioRepository.findAll().stream()
                .anyMatch(u -> u.getRole() == Role.ADMIN);

        if (yaHayAdmin) {
            log.info("Ya existe al menos un administrador en el sistema. Bootstrap omitido.");
            return;
        }

        // SEGURIDAD: sin credenciales explícitas, no creamos nada.
        // Esto evita admins hardcoded y obliga al desarrollador/operador
        // a proporcionar credenciales conscientemente.
        if (bootstrapEmail == null || bootstrapEmail.isBlank()
                || bootstrapPassword == null || bootstrapPassword.isBlank()) {
            log.warn("================================================");
            log.warn(" BOOTSTRAP DEL ADMIN RAÍZ OMITIDO");
            log.warn(" No se han definido las credenciales requeridas.");
            log.warn(" Para crear el admin raíz automáticamente, define:");
            log.warn("   SMARTAUDITS_ADMIN_EMAIL");
            log.warn("   SMARTAUDITS_ADMIN_PASSWORD");
            log.warn(" antes de arrancar el backend.");
            log.warn("================================================");
            return;
        }

        // Si por alguna razón existe ya un usuario con ese email pero no es admin,
        // no lo machacamos — avisamos y salimos.
        if (usuarioRepository.existsByEmail(bootstrapEmail)) {
            log.warn("El email del bootstrap ({}) ya está en uso por un usuario existente. " +
                    "No se creará el admin raíz automáticamente. " +
                    "Promociona ese usuario manualmente o cambia el email del bootstrap.", bootstrapEmail);
            return;
        }

        // Validación de longitud mínima de contraseña — coherente con el resto de la app
        if (bootstrapPassword.length() < 6) {
            log.warn("La contraseña del bootstrap es demasiado corta (mínimo 6 caracteres). " +
                    "El admin raíz no se ha creado.");
            return;
        }

        // Recuperar el rol ADMIN antes de crear el usuario
        Optional<Rol> rolAdminOpt = rolRepository.findByNombre("ADMIN");
        if (rolAdminOpt.isEmpty()) {
            log.error("No se encontró el rol ADMIN en la tabla roles. " +
                    "El admin raíz no se ha creado.");
            return;
        }
        Rol rolAdmin = rolAdminOpt.get();

        // Construir el usuario admin con el rol N:M ya asignado en memoria
        Usuario admin = new Usuario();
        admin.setNombre(bootstrapNombre != null && !bootstrapNombre.isBlank() ? bootstrapNombre : "Administrador");
        admin.setEmail(bootstrapEmail);
        admin.setPassword(passwordEncoder.encode(bootstrapPassword));
        admin.setRole(Role.ADMIN);
        admin.setActivo(true);
        admin.setProtegido(true);   // ← admin raíz: blindado

        // Asignar la relación N:M ANTES del save() para que se persista
        // en la tabla intermedia usuarios_roles dentro de la misma transacción
        admin.getRoles().add(rolAdmin);

        // saveAndFlush fuerza el INSERT inmediato en BD, garantizando que la
        // tabla usuarios_roles también se rellene en esta misma operación
        Usuario guardado = usuarioRepository.saveAndFlush(admin);

        log.info("================================================");
        log.info(" ADMIN RAÍZ CREADO AUTOMÁTICAMENTE");
        log.info(" Email:     {}", guardado.getEmail());
        log.info(" Nombre:    {}", guardado.getNombre());
        log.info(" Protegido: true (no puede ser degradado ni desactivado)");
        log.info(" Rol N:M:   asignado en tabla usuarios_roles");
        log.info(" IMPORTANTE: cambia la contraseña tras el primer login.");
        log.info("================================================");
    }
}
