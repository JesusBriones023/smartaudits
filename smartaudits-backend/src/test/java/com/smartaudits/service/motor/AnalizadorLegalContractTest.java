package com.smartaudits.service.motor;

import com.smartaudits.model.dto.ResultadoAuditoria;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnalizadorLegalContractTest {
    private final AnalizadorLegal analizador = new MotorAnalisisLegal();

    @Test
    void interfaceProducesIdenticalFullResultsForEquivalentContent() {
        EntradaAnalisis first = new EntradaAnalisis(
                "Contacto: privacidad@example.invalid. No conservamos los datos indefinidamente.",
                "Política de Privacidad");
        EntradaAnalisis second = new EntradaAnalisis(first.texto(), first.tipoDocumento());

        ResultadoAuditoria result = analizador.analyze(first);

        assertThat(result).isNotNull();
        assertThat(result.getErrores()).isNotEmpty();
        assertThat(analizador.analyze(second)).isEqualTo(result);
    }

    @Test
    void inputNeedsOnlyContentAndDocumentTypeAndPreservesThemVerbatim() {
        String text = "  RESPONSABLE: Ejemplo.\r\nContacto y conservación.\t";
        EntradaAnalisis input = new EntradaAnalisis(text, "Aviso Legal");

        assertThat(input.texto()).isEqualTo(text);
        assertThat(input.tipoDocumento()).isEqualTo("Aviso Legal");
        assertThat(analizador.analyze(input)).isNotNull();
        assertThat(input).isEqualTo(new EntradaAnalisis(text, "Aviso Legal"));
    }

    @Test
    void nullContentRetainsTheExistingEmptyTextResult() {
        ResultadoAuditoria result = analizador.analyze(new EntradaAnalisis(null, null));

        assertThat(result).isEqualTo(analizador.analyze(new EntradaAnalisis("", null)));
        assertThat(result.getPuntuacionRiesgo()).isZero();
        assertThat(result.getResumen()).contains("el texto proporcionado está vacío");
    }
}
