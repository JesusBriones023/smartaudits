package com.smartaudits.service;

import com.smartaudits.model.Auditoria;
import com.smartaudits.model.HistorialAuditoria;
import com.smartaudits.model.Usuario;
import com.smartaudits.repository.HistorialAuditoriaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class HistorialService {

    private final HistorialAuditoriaRepository historialRepository;

    /**
     * Registra una acción sobre una auditoría.
     * Acciones: CREACION, CONSULTA, DESCARGA
     */
    public void registrar(Auditoria auditoria, Usuario usuario, String accion, String ipAcceso) {
        try {
            HistorialAuditoria entrada = new HistorialAuditoria();
            entrada.setAuditoria(auditoria);
            entrada.setUsuario(usuario);
            entrada.setAccion(accion);
            entrada.setIpAcceso(ipAcceso != null ? ipAcceso : "desconocida");
            entrada.setFechaAcceso(LocalDateTime.now());
            historialRepository.save(entrada);
            log.info("Historial registrado: {} — auditoría {} — usuario {}",
                accion, auditoria.getId(), usuario.getEmail());
        } catch (Exception e) {
            // El historial nunca debe interrumpir el flujo principal
            log.warn("No se pudo registrar en historial: {}", e.getMessage());
        }
    }

    public void registrarCreacion(Auditoria auditoria, Usuario usuario, String ip) {
        registrar(auditoria, usuario, "CREACION", ip);
    }

    public void registrarConsulta(Auditoria auditoria, Usuario usuario, String ip) {
        registrar(auditoria, usuario, "CONSULTA", ip);
    }

    public void registrarDescarga(Auditoria auditoria, Usuario usuario, String ip) {
        registrar(auditoria, usuario, "DESCARGA", ip);
    }
}
