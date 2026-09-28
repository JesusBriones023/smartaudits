package com.smartaudits.repository;

import com.smartaudits.model.HistorialAuditoria;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HistorialAuditoriaRepository extends JpaRepository<HistorialAuditoria, Long> {
    List<HistorialAuditoria> findByAuditoriaIdOrderByFechaAccesoDesc(Long auditoriaId);
    List<HistorialAuditoria> findByUsuarioIdOrderByFechaAccesoDesc(Long usuarioId);
    List<HistorialAuditoria> findByAccion(String accion);
}
