package com.smartaudits.repository;

import com.smartaudits.model.Auditoria;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;

@Repository
public interface AuditoriaRepository extends JpaRepository<Auditoria, Long> {

    /**
     * Auditorías de un usuario concreto.
     * Usuario se precarga para evitar consultas adicionales al construir el DTO.
     */
    @EntityGraph(attributePaths = "usuario")
    Page<Auditoria> findByUsuarioId(
            Long usuarioId,
            Pageable pageable
    );

    /**
     * Listado administrativo completo.
     */
    @Override
    @EntityGraph(attributePaths = "usuario")
    Page<Auditoria> findAll(Pageable pageable);

    /**
     * Búsqueda administrativa por nombre del propietario.
     */
    @EntityGraph(attributePaths = "usuario")
    Page<Auditoria> findByUsuarioNombreContainingIgnoreCase(
            String usuarioNombre,
            Pageable pageable
    );
    long countByUsuarioIdAndFechaCreacionGreaterThanEqual(
        Long usuarioId,
        LocalDateTime fechaDesde
        );
}