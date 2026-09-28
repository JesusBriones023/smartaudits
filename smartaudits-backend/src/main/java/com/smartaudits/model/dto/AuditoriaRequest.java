package com.smartaudits.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.hibernate.validator.constraints.URL;

@Data
public class AuditoriaRequest {

    /**
     * Límite conservador mientras texto_original siga siendo TEXT en MariaDB.
     * En la fase 0.8 migraremos la columna de forma versionada y podremos
     * ampliar este límite sin depender de ddl-auto.
     */
    public static final int MAX_TEXTO_LENGTH = 15_000;

    @NotBlank(message = "El título es obligatorio")
    @Size(
            max = 200,
            message = "El título no puede superar los 200 caracteres"
    )
    private String titulo;

    @NotBlank(message = "El tipo de documento es obligatorio")
    @Size(
            max = 50,
            message = "El tipo de documento no puede superar los 50 caracteres"
    )
    @Pattern(
            regexp = "Política de Privacidad|Aviso Legal|Política de Cookies|Términos y Condiciones|Condiciones de Uso|Documento Completo",
            message = "El tipo de documento no es válido"
    )
    private String tipoDocumento;

    @NotBlank(message = "El texto a auditar es obligatorio")
    @Size(
            max = MAX_TEXTO_LENGTH,
            message = "El texto a auditar no puede superar los 15000 caracteres"
    )
    private String textoOriginal;

    @Size(
            max = 500,
            message = "La URL no puede superar los 500 caracteres"
    )
    @URL(
            regexp = "(?i)^https?://.*$",
            message = "La URL debe ser una dirección HTTP o HTTPS válida"
    )
    private String urlOpcional;
}