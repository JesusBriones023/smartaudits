package com.smartaudits.model.dto;

import com.smartaudits.service.html.ExtractorTextoHtml;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.hibernate.validator.constraints.URL;

/**
 * Auditoría a partir de HTML pegado por el usuario. El HTML nunca se ejecuta ni se
 * guarda: se extrae su texto legible y se analiza como en la auditoría manual.
 * La URL es solo un metadato; nunca se descarga.
 */
@Data
public class AuditoriaHtmlRequest {

    @NotBlank(message = "El título es obligatorio")
    @Size(max = 200, message = "El título no puede superar los 200 caracteres")
    private String titulo;

    @NotBlank(message = "El tipo de documento es obligatorio")
    @Size(max = 50, message = "El tipo de documento no puede superar los 50 caracteres")
    @Pattern(
            regexp = "Política de Privacidad|Aviso Legal|Política de Cookies|Términos y Condiciones|Condiciones de Uso|Documento Completo",
            message = "El tipo de documento no es válido"
    )
    private String tipoDocumento;

    @NotBlank(message = "El HTML a auditar es obligatorio")
    @Size(
            max = ExtractorTextoHtml.MAX_HTML_LENGTH,
            message = "El HTML no puede superar los 500000 caracteres"
    )
    private String html;

    @Size(max = 500, message = "La URL no puede superar los 500 caracteres")
    @URL(regexp = "(?i)^https?://.*$", message = "La URL debe ser una dirección HTTP o HTTPS válida")
    private String urlOpcional;
}
