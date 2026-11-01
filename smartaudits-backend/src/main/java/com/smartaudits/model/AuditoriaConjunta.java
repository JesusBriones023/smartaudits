package com.smartaudits.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Agrupa de uno a cuatro documentos aportados por el usuario y analizados en una única
 * operación. Cada documento es una {@link Auditoria} normal, con su propio resultado,
 * incidencias e historial; aquí solo se guarda qué auditorías forman el grupo y en qué
 * orden. Por la regla de migraciones aditivas (V9) no hay FK hacia auditorias: el
 * servicio crea grupo y documentos en la misma transacción y comprueba al leer que cada
 * documento existe y pertenece al mismo usuario.
 */
@Entity
@Table(name = "auditorias_conjuntas")
@Getter
@Setter
@NoArgsConstructor
public class AuditoriaConjunta {

    public static final int MAX_DOCUMENTOS = 4;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @Column(nullable = false, length = 200)
    private String titulo;

    @Column(name = "fecha_creacion", nullable = false)
    private LocalDateTime fechaCreacion;

    @Setter(AccessLevel.NONE)
    @Column(name = "documento_1_id", nullable = false, unique = true)
    private Long documento1Id;

    @Setter(AccessLevel.NONE)
    @Column(name = "documento_2_id", unique = true)
    private Long documento2Id;

    @Setter(AccessLevel.NONE)
    @Column(name = "documento_3_id", unique = true)
    private Long documento3Id;

    @Setter(AccessLevel.NONE)
    @Column(name = "documento_4_id", unique = true)
    private Long documento4Id;

    /** Fija los documentos, en orden, una sola vez. */
    public void asignarDocumentos(List<Long> ids) {
        if (documento1Id != null) {
            throw new IllegalStateException("Los documentos de una auditoría conjunta no se modifican");
        }
        if (ids == null || ids.isEmpty() || ids.size() > MAX_DOCUMENTOS || ids.stream().anyMatch(java.util.Objects::isNull)
                || ids.stream().distinct().count() != ids.size()) {
            throw new IllegalArgumentException("Una auditoría conjunta tiene entre 1 y 4 documentos distintos");
        }
        documento1Id = ids.get(0);
        documento2Id = ids.size() > 1 ? ids.get(1) : null;
        documento3Id = ids.size() > 2 ? ids.get(2) : null;
        documento4Id = ids.size() > 3 ? ids.get(3) : null;
    }

    /** IDs de las auditorías de cada documento, en el orden en que se aportaron. */
    public List<Long> documentoIds() {
        List<Long> ids = new ArrayList<>();
        for (Long id : new Long[]{documento1Id, documento2Id, documento3Id, documento4Id}) {
            if (id != null) {
                ids.add(id);
            }
        }
        return List.copyOf(ids);
    }
}
