package com.smartaudits.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "auditorias")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Auditoria {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @Column(name = "fecha_creacion", nullable = false)
    private LocalDateTime fechaCreacion;

    @Column(nullable = false, length = 200)
    private String titulo;

    @Column(name = "tipo_documento", length = 50)
    private String tipoDocumento;

    @Column(name = "texto_original", columnDefinition = "TEXT")
    private String textoOriginal;

    @Column(name = "url_opcional", length = 500)
    private String urlOpcional;

    // JSON completo — se mantiene para el frontend
    @Column(name = "resultado_json", columnDefinition = "TEXT")
    private String resultadoJson;

    @Column(name = "puntuacion_riesgo")
    private Integer puntuacionRiesgo;

    // COMPLETADA, EN_PROCESO, FALLIDA
    @Column(nullable = false, length = 20)
    private String estado = "COMPLETADA";

    // Relación 1:1 con tabla resultados
    @OneToOne(mappedBy = "auditoria", cascade = CascadeType.ALL, orphanRemoval = true)
    private Resultado resultado;

    // Relación 1:N con tabla incidencias
    @OneToMany(mappedBy = "auditoria", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Incidencia> incidencias = new ArrayList<>();

    // Relación 1:N con tabla historial_auditorias
    @OneToMany(mappedBy = "auditoria", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<HistorialAuditoria> historial = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        fechaCreacion = LocalDateTime.now();
        if (estado == null) estado = "COMPLETADA";
    }
}
