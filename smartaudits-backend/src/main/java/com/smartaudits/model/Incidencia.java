package com.smartaudits.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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
