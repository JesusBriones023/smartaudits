package com.smartaudits.repository;

import com.smartaudits.model.Incidencia;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface IncidenciaRepository extends JpaRepository<Incidencia, Long> {
    List<Incidencia> findByAuditoriaId(Long auditoriaId);
    List<Incidencia> findBySeveridad(String severidad);
}
