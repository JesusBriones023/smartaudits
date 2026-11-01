package com.smartaudits.model.dto;

import com.smartaudits.model.AuditoriaConjunta;
import com.smartaudits.service.html.ExtractorTextoHtml;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.hibernate.validator.constraints.URL;

import java.util.List;

/** De uno a cuatro documentos HTML pegados por el usuario, analizados en una única operación. */
@Data
public class AuditoriaConjuntaRequest {

    /** Suma máxima del HTML de todos los documentos. */
    public static final int MAX_HTML_TOTAL = 1_000_000;

    @NotBlank(message = "El título es obligatorio")
    @Size(max = 200, message = "El título no puede superar los 200 caracteres")
    private String titulo;

    @NotNull(message = "Añade al menos un documento")
    @Size(min = 1, max = AuditoriaConjunta.MAX_DOCUMENTOS, message = "Una auditoría conjunta admite entre 1 y 4 documentos")
    private List<@NotNull(message = "Documento vacío") @Valid Documento> documentos;

    @Data
    public static class Documento {

        @NotBlank(message = "El título del documento es obligatorio")
        @Size(max = 200, message = "El título del documento no puede superar los 200 caracteres")
        private String titulo;

        @NotBlank(message = "La categoría legal es obligatoria")
        @Pattern(
                regexp = "Política de Privacidad|Aviso Legal|Política de Cookies|Términos y Condiciones|Condiciones de Uso",
                message = "La categoría legal no es válida"
        )
        private String tipoDocumento;

        @NotBlank(message = "El HTML del documento es obligatorio")
        @Size(max = ExtractorTextoHtml.MAX_HTML_LENGTH, message = "El HTML de un documento no puede superar los 500000 caracteres")
        private String html;

        @Size(max = 500, message = "La URL no puede superar los 500 caracteres")
        @URL(regexp = "(?i)^https?://.*$", message = "La URL debe ser una dirección HTTP o HTTPS válida")
        private String urlOpcional;
    }
}
