package com.smartaudits.repository;

import com.smartaudits.model.AuditoriaConjunta;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditoriaConjuntaRepository extends JpaRepository<AuditoriaConjunta, Long> {

    Page<AuditoriaConjunta> findByUsuarioId(Long usuarioId, Pageable pageable);
}
