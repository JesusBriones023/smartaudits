package com.smartaudits.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "resultados")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Resultado {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "auditoria_id", nullable = false, unique = true)
    private Auditoria auditoria;

    @Column(name = "resumen_general", columnDefinition = "TEXT")
    private String resumenGeneral;

    @Column(name = "recomendaciones_generales", columnDefinition = "TEXT")
    private String recomendacionesGenerales;

    @Column(name = "puntuacion_cumplimiento")
    private Integer puntuacionCumplimiento;

    @Column(name = "fecha_resultado", nullable = false)
    private LocalDateTime fechaResultado;

    @PrePersist
    protected void onCreate() {
        if (fechaResultado == null) {
            fechaResultado = LocalDateTime.now();
        }
    }
}
