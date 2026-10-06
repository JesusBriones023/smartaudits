package com.smartaudits.service;

import com.smartaudits.model.Rol;
import com.smartaudits.model.Role;
import com.smartaudits.model.Usuario;
import com.smartaudits.repository.RolRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Sincroniza las dos representaciones del único rol de dominio de un usuario. */
@Service
@RequiredArgsConstructor
public class RolUsuarioService {
    private final RolRepository rolRepository;

    /**
     * Operación interna: el llamador conserva autorización, bloqueo administrativo,
     * revocación y persistencia dentro de su misma transacción. No repara otros usuarios.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void sincronizar(Usuario usuario, Role destino) {
        Rol rol = rolRepository.findByNombre(destino.name())
                .orElseThrow(() -> new IllegalStateException("Falta el rol " + destino.name() + " del sistema"));

        usuario.setRole(destino);
        // Evitar marcar la colección como modificada cuando ya es coherente.
        if (usuario.getRoles().size() != 1 || !usuario.getRoles().contains(rol)) {
            usuario.getRoles().clear();
            usuario.getRoles().add(rol);
        }
    }
}
