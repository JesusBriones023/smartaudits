package com.smartaudits.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * Entidad que registra cada acción administrativa ejecutada sobre un usuario.
 *
 * Permite mantener la trazabilidad completa exigida por la naturaleza
 * de auditoría de SmartAudits y por buenas prácticas RGPD: qué admin
 * hizo qué acción, sobre qué usuario, cuándo y desde dónde.
 *
 * Notas de diseño:
 *  - Las relaciones con `usuarios` son LAZY para no cargar los usuarios
 *    completos al listar el historial.
 *  - Se guardan también nombre/email del admin y del objetivo en el
 *    momento de la acción (campos snapshot) para que el historial siga
 *    siendo legible aunque después se modifique el perfil del usuario.
 *  - Se usa @Getter/@Setter en lugar de @Data para evitar el problema
 *    clásico de equals/hashCode con relaciones perezosas.
 */
@Entity
@Table(name = "historial_acciones_admin", indexes = {
        @Index(name = "idx_hist_admin_id", columnList = "admin_id"),
        @Index(name = "idx_hist_objetivo_id", columnList = "usuario_objetivo_id"),
        @Index(name = "idx_hist_fecha", columnList = "fecha")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"admin", "usuarioObjetivo"})
@EqualsAndHashCode(of = "id")
public class HistorialAccionAdmin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Admin que ejecutó la acción */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "admin_id", nullable = false)
    private Usuario admin;

    /** Snapshot del nombre del admin en el momento de la acción */
    @Column(name = "admin_nombre", nullable = false, length = 100)
    private String adminNombre;

    /** Snapshot del email del admin en el momento de la acción */
    @Column(name = "admin_email", nullable = false, length = 150)
    private String adminEmail;

    /** Usuario sobre el que se ejecutó la acción */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_objetivo_id", nullable = false)
    private Usuario usuarioObjetivo;

    /** Snapshot del nombre del usuario objetivo */
    @Column(name = "objetivo_nombre", nullable = false, length = 100)
    private String objetivoNombre;

    /** Snapshot del email del usuario objetivo */
    @Column(name = "objetivo_email", nullable = false, length = 150)
    private String objetivoEmail;

    /** Tipo de acción ejecutada */
    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_accion", nullable = false, length = 30)
    private TipoAccionAdmin tipoAccion;

    /**
     * Detalles adicionales de la acción.
     * Para PROMOVER/DEGRADAR contiene el rol antes y después.
     * Puede quedarse a null para acciones simples.
     */
    @Column(length = 500)
    private String detalles;

    /** Fecha y hora exactas de la acción */
    @Column(nullable = false)
    private LocalDateTime fecha;

    @PrePersist
    protected void onCreate() {
        if (fecha == null) {
            fecha = LocalDateTime.now();
        }
    }
}
