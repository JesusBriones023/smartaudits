package com.smartaudits.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;
import lombok.Setter;

@Entity
@Table(name = "incidencias")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Incidencia {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "auditoria_id", nullable = false)
    private Auditoria auditoria;

    @Setter(AccessLevel.NONE)
    @Column(name = "rule_id", nullable = false, updatable = false, length = 50)
    private String ruleId;

    @Setter(AccessLevel.NONE)
    @Column(nullable = false, updatable = false, length = 50)
    private String motor;

    @Setter(AccessLevel.NONE)
    @Column(nullable = false, updatable = false, length = 50)
    private String version;

    /** Identificación del detector y de sus reglas, fijada al crear el finding. */
    public void registrarProcedencia(String ruleId, String motor, String version) {
        if (this.ruleId != null || this.motor != null || this.version != null) {
            throw new IllegalStateException("La procedencia de la incidencia ya está registrada");
        }
        for (String value : new String[]{ruleId, motor, version}) {
            if (value == null || value.isBlank() || value.length() > 50) {
                throw new IllegalArgumentException("La procedencia requiere identificadores de 1 a 50 caracteres");
            }
        }
        this.ruleId = ruleId;
        this.motor = motor;
        this.version = version;
    }

    @Column(length = 200)
    private String categoria;

    @Column(nullable = false, length = 10)
    private String severidad; // ALTA, MEDIA, BAJA

    @Column(columnDefinition = "TEXT")
    private String descripcion;

    @Column(columnDefinition = "TEXT")
    private String recomendacion;

    @Column(columnDefinition = "TEXT")
    private String evidencia;

    @Column(columnDefinition = "TEXT")
    private String impacto;
}
