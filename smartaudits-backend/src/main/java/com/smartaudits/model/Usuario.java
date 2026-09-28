package com.smartaudits.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Entidad Usuario.
 *
 * Usamos @Getter/@Setter en lugar de @Data para tener control fino sobre
 * equals/hashCode/toString y evitar problemas con las relaciones perezosas
 * bidireccionales (Set<Rol> roles, List<Auditoria> auditorias).
 */
@Entity
@Table(name = "usuarios")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"roles", "auditorias", "password"})
@EqualsAndHashCode(of = {"id", "email"})
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String nombre;

    @Column(nullable = false, unique = true, length = 150)
    private String email;

    @Column(nullable = false)
    private String password;

    // Exclusivamente concurrencia: no se incluye en el JWT.
    @Version
    @Column(name = "row_version", nullable = false, columnDefinition = "BIGINT DEFAULT 0")
    private long rowVersion;

    // Revocación explícita ante eventos de seguridad, dentro de su transacción.
    @Column(name = "token_version", nullable = false, columnDefinition = "BIGINT DEFAULT 0")
    private long tokenVersion;

    // ENUM principal — Spring Security sigue usándolo igual
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role = Role.CLIENTE;

    // Estado de la cuenta — false = baja lógica
    @Column(nullable = false)
    private Boolean activo = true;

    /**
     * Indica si este usuario está protegido contra modificaciones administrativas.
     * El admin creado por el bootstrap inicial se marca como protegido (true)
     * y no puede ser degradado, desactivado ni eliminado por otros administradores.
     * Garantiza que el sistema siempre tenga un administrador raíz funcional.
     */
    @Column(nullable = false)
    private Boolean protegido = false;

    @Column(name = "fecha_registro", nullable = false)
    private LocalDateTime fechaRegistro;

    // Relación N:M con la tabla roles (modelo relacional TFG)
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "usuarios_roles",
        joinColumns = @JoinColumn(name = "usuario_id"),
        inverseJoinColumns = @JoinColumn(name = "rol_id")
    )
    private Set<Rol> roles = new HashSet<>();

    @OneToMany(mappedBy = "usuario", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Auditoria> auditorias = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        fechaRegistro = LocalDateTime.now();
        if (activo == null) activo = true;
        if (protegido == null) protegido = false;
    }
}
