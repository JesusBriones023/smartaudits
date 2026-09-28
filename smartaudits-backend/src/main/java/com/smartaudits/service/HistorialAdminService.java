package com.smartaudits.service;

import com.smartaudits.model.HistorialAccionAdmin;
import com.smartaudits.model.TipoAccionAdmin;
import com.smartaudits.model.Usuario;
import com.smartaudits.model.dto.HistorialAccionAdminResponse;
import com.smartaudits.repository.HistorialAccionAdminRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Servicio responsable de registrar y consultar las acciones administrativas
 * sobre usuarios. Cada vez que un admin desactiva, reactiva, promueve o
 * degrada a otro usuario, este service inserta una fila en la tabla
 * historial_acciones_admin para garantizar trazabilidad completa.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HistorialAdminService {

    private final HistorialAccionAdminRepository historialRepository;

    /**
     * Registra una acción administrativa en la BD.
     * Se invoca desde UsuarioService tras realizar la acción correspondiente.
     */
    @Transactional
    public void registrarAccion(Usuario admin, Usuario objetivo,
                                 TipoAccionAdmin tipoAccion, String detalles) {
        HistorialAccionAdmin entrada = new HistorialAccionAdmin();
        entrada.setAdmin(admin);
        entrada.setAdminNombre(admin.getNombre());
        entrada.setAdminEmail(admin.getEmail());
        entrada.setUsuarioObjetivo(objetivo);
        entrada.setObjetivoNombre(objetivo.getNombre());
        entrada.setObjetivoEmail(objetivo.getEmail());
        entrada.setTipoAccion(tipoAccion);
        entrada.setDetalles(detalles);

        historialRepository.save(entrada);

        log.info("Historial admin registrado: {} por {} sobre {}",
                tipoAccion, admin.getEmail(), objetivo.getEmail());
    }

    /**
     * Lista todas las acciones del historial, más recientes primero.
     */
    public List<HistorialAccionAdminResponse> listarTodas() {
        return historialRepository.findAllByOrderByFechaDesc().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private HistorialAccionAdminResponse toResponse(HistorialAccionAdmin h) {
        HistorialAccionAdminResponse r = new HistorialAccionAdminResponse();
        r.setId(h.getId());
        r.setAdminId(h.getAdmin() != null ? h.getAdmin().getId() : null);
        r.setAdminNombre(h.getAdminNombre());
        r.setAdminEmail(h.getAdminEmail());
        r.setObjetivoId(h.getUsuarioObjetivo() != null ? h.getUsuarioObjetivo().getId() : null);
        r.setObjetivoNombre(h.getObjetivoNombre());
        r.setObjetivoEmail(h.getObjetivoEmail());
        r.setTipoAccion(h.getTipoAccion().name());
        r.setTipoAccionEtiqueta(h.getTipoAccion().getEtiqueta());
        r.setDetalles(h.getDetalles());
        r.setFecha(h.getFecha());
        return r;
    }
}
