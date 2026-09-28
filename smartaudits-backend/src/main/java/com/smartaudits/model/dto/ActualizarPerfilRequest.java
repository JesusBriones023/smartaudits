package com.smartaudits.model.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * DTO para actualizar el perfil del usuario autenticado.
 *
 * El campo {@code passwordActual} es obligatorio siempre que se quiera
 * cambiar la contraseña — actúa como confirmación de que es realmente
 * el usuario quien está realizando la modificación.
 *
 * Si {@code passwordNueva} viene vacía o nula, no se modifica la contraseña.
 * La longitud mínima se valida en el service para evitar fallos cuando
 * el campo se envía vacío.
 */
@Data
public class ActualizarPerfilRequest {

    @NotBlank(message = "El nombre es obligatorio")
    @Size(min = 2, max = 100, message = "El nombre debe tener entre 2 y 100 caracteres")
    private String nombre;

    @NotBlank(message = "El email es obligatorio")
    @Email(message = "Email inválido")
    private String email;

    /**
     * Contraseña actual — solo obligatoria si se desea cambiar la contraseña.
     */
    private String passwordActual;

    /**
     * Nueva contraseña — opcional. Si se deja vacía o nula, la contraseña no cambia.
     */
    private String passwordNueva;
}
