package com.smartaudits.model.dto;

import com.smartaudits.service.html.ExtractorTextoHtml;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.hibernate.validator.constraints.URL;

/** HTML pegado en el que buscar enlaces legales; la URL solo sirve para resolver rutas relativas. */
@Data
public class DeteccionEnlacesRequest {

    @NotBlank(message = "El HTML a auditar es obligatorio")
    @Size(max = ExtractorTextoHtml.MAX_HTML_LENGTH, message = "El HTML no puede superar los 500000 caracteres")
    private String html;

    @Size(max = 500, message = "La URL no puede superar los 500 caracteres")
    @URL(regexp = "(?i)^https?://.*$", message = "La URL debe ser una dirección HTTP o HTTPS válida")
    private String urlOpcional;
}
