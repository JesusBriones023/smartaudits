package com.smartaudits.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class CambiarRolRequest {

    @NotBlank(message = "El nuevo rol es obligatorio")
    @Pattern(regexp = "CLIENTE|ADMIN", message = "El rol debe ser CLIENTE o ADMIN")
    private String nuevoRol;
}
