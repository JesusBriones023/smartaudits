package com.smartaudits.repository;

import com.smartaudits.model.HistorialAccionAdmin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HistorialAccionAdminRepository extends JpaRepository<HistorialAccionAdmin, Long> {

    /** Todas las acciones, más recientes primero */
    List<HistorialAccionAdmin> findAllByOrderByFechaDesc();
}
