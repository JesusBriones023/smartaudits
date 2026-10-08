package com.smartaudits.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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

    @Setter(AccessLevel.NONE)
    @Column(name = "version_motor", nullable = false, updatable = false, length = 50)
    private String versionMotor;

    @Setter(AccessLevel.NONE)
    @Column(name = "version_reglas", nullable = false, updatable = false, length = 50)
    private String versionReglas;

    @Setter(AccessLevel.NONE)
    @Column(name = "fecha_analisis", nullable = false, updatable = false)
    private LocalDateTime fechaAnalisis;

    @Setter(AccessLevel.NONE)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "tipo_fuente", nullable = false, updatable = false, length = 16)
    private TipoFuente tipoFuente;

    /** La procedencia se fija una sola vez, al ejecutar el análisis. */
    public void registrarProcedencia(String motor, String reglas, LocalDateTime fecha, TipoFuente fuente) {
        if (versionMotor != null || versionReglas != null || fechaAnalisis != null || tipoFuente != null) {
            throw new IllegalStateException("La procedencia del análisis ya está registrada");
        }
        if (motor == null || motor.isBlank() || motor.length() > 50
                || reglas == null || reglas.isBlank() || reglas.length() > 50) {
            throw new IllegalArgumentException("Las versiones deben tener entre 1 y 50 caracteres");
        }
        Objects.requireNonNull(fecha, "fechaAnalisis");
        Objects.requireNonNull(fuente, "tipoFuente");
        versionMotor = motor;
        versionReglas = reglas;
        fechaAnalisis = fecha;
        tipoFuente = fuente;
    }

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
