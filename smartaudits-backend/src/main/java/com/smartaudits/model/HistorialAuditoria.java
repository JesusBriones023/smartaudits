package com.smartaudits.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "historial_auditorias")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HistorialAuditoria {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "auditoria_id", nullable = false)
    private Auditoria auditoria;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @Column(name = "fecha_acceso", nullable = false)
    private LocalDateTime fechaAcceso;

    // CREACION, CONSULTA, DESCARGA
    @Column(nullable = false, length = 20)
    private String accion;

    @Column(name = "ip_acceso", length = 50)
    private String ipAcceso;

    @PrePersist
    protected void onCreate() {
        if (fechaAcceso == null) {
            fechaAcceso = LocalDateTime.now();
        }
    }
}
