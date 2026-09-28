package com.smartaudits.service.motor;

import com.smartaudits.model.dto.ResultadoAuditoria;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MotorAnalisisLegalTest {
    private final MotorAnalisisLegal motor = new MotorAnalisisLegal();

    // Basic structural examples only; the regression corpus belongs to Phase 1.2.
    private static final String COMPLETE_TEXT = """
            Aviso legal. Responsable del tratamiento: Ejemplo S.L., NIF de prueba,
            domicilio social en Madrid e inscrita en el Registro Mercantil de Madrid.
            Contacto: privacidad@example.invalid. Delegado de protección de datos:
            dpo@example.invalid.
            La finalidad es la prestación del servicio y la gestión de clientes.
            La base legal es la ejecución del contrato y el consentimiento del interesado.
            Puede ejercer su derecho de acceso, rectificación, supresión, limitación,
            portabilidad y oposición escribiendo al contacto indicado.
            El plazo de conservación es de cinco años tras finalizar la relación contractual.
            Los destinatarios son proveedores de alojamiento sujetos a contrato.
            Las transferencias internacionales cuentan con cláusulas contractuales tipo.
            La política de cookies informa de tipos, finalidades y duración; el panel permite
            aceptar, rechazar o configurar cookies con igual facilidad.
            Puede presentar una reclamación ante la AEPD.
            El consentimiento se obtiene mediante una casilla desmarcada y puede retirarse.
            Los menores de 14 años requieren autorización parental.
            Las categorías especiales de datos tienen garantías reforzadas.
            Aplicamos medidas de seguridad: cifrado, control de acceso y copias de seguridad.
            Las decisiones automatizadas están sujetas a intervención humana.
            El procedimiento ante una brecha de seguridad contempla notificación en 72 horas.
            Las comunicaciones comerciales requieren consentimiento y permiten darse de baja.
            """;

    @Test
    void completeTextProducesCoherentStructuredResult() {
        ResultadoAuditoria result = motor.analyze(COMPLETE_TEXT, "Documento Completo");

        assertStructure(result);
        assertThat(result.getErrores()).isEmpty();
        assertThat(result.getRiesgos()).isEmpty();
        assertThat(result.getFaltantes()).isEmpty();
        assertThat(result.getTextosSugeridos()).isEmpty();
        assertThat(result.getPuntuacionRiesgo()).isBetween(85, 100);
    }

    @Test
    void incompleteTextProducesActionableIncidentsAndBoundedScore() {
        ResultadoAuditoria result = motor.analyze("Bienvenido a nuestra página.", "Documento Completo");

        assertStructure(result);
        assertThat(result.getErrores()).isNotEmpty().allSatisfy(error -> {
            assertThat(error.getTitulo()).isNotBlank();
            assertThat(error.getDescripcion()).isNotBlank();
            assertThat(error.getSeveridad()).isIn("ALTA", "MEDIA", "BAJA");
            assertThat(error.getEvidencia()).isNotBlank();
            assertThat(error.getImpacto()).isNotBlank();
            assertThat(error.getAccion()).isNotBlank();
        });
        assertThat(result.getRiesgos()).isNotEmpty();
        assertThat(result.getFaltantes()).isNotEmpty();
        assertThat(result.getTextosSugeridos()).isNotEmpty();
        assertThat(result.getPuntuacionRiesgo()).isLessThan(85);
    }

    private void assertStructure(ResultadoAuditoria result) {
        assertThat(result).isNotNull();
        assertThat(result.getResumen()).isNotBlank();
        assertThat(result.getPuntuacionRiesgo()).isBetween(0, 100);
        assertThat(result.getErrores()).isNotNull();
        assertThat(result.getRiesgos()).isNotNull();
        assertThat(result.getFaltantes()).isNotNull();
        assertThat(result.getTextosSugeridos()).isNotNull();
        assertThat(result.getRecomendaciones()).isNotEmpty().allSatisfy(value -> assertThat(value).isNotBlank());
        assertThat(result.getReferenciasLegales()).isNotEmpty().allSatisfy(value -> assertThat(value).isNotBlank());
    }
}
