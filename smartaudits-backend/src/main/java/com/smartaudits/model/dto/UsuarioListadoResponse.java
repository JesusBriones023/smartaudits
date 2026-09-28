package com.smartaudits.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO de listado de usuarios para el panel de administración.
 * Excluye datos sensibles como la contraseña.
 */
@Data
@NoArgsConstructor
public class UsuarioListadoResponse {
    private Long id;
    private String nombre;
    private String email;
    private String role;          // "CLIENTE" o "ADMIN"
    private Boolean activo;
    private Boolean protegido;
    private LocalDateTime fechaRegistro;
    private Integer numeroAuditorias;
}
