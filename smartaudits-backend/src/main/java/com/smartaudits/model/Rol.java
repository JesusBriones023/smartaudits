package com.smartaudits.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.HashSet;
import java.util.Set;

/**
 * Entidad Rol.
 *
 * IMPORTANTE: equals/hashCode SOLO usan el id (o el nombre) para evitar
 * cargar la colección perezosa `usuarios` cuando se mete un Rol en un Set.
 * Esto previene el error LazyInitializationException al ejecutar el
 * DataInitializer fuera de una sesión activa de Hibernate.
 *
 * Por la misma razón evitamos @ToString sobre la colección bidireccional
 * (provoca recursión y arranque pesado).
 */
@Entity
@Table(name = "roles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = "usuarios")
@EqualsAndHashCode(of = {"id", "nombre"})
public class Rol {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String nombre;

    @Column(length = 200)
    private String descripcion;

    @ManyToMany(mappedBy = "roles")
    private Set<Usuario> usuarios = new HashSet<>();
}
