package com.smartaudits.model;

/**
 * Enumeración de niveles de riesgo de cumplimiento legal.
 *
 * Los niveles se derivan de la puntuación de cumplimiento (0-100)
 * generada por el MotorAnalisisLegal. La puntuación se guarda en la
 * base de datos como dato cuantitativo; el nivel cualitativo se
 * calcula a partir de ella mediante {@link #desdePuntuacion(int)}.
 *
 * Esto permite modificar los umbrales sin necesidad de migrar datos
 * históricos en la base de datos.
 *
 * Umbrales:
 *   85-100 → BAJO            (Cumplimiento Alto)
 *   65-84  → MODERADO        (Cumplimiento Medio)
 *   40-64  → ALTO            (Cumplimiento Bajo)
 *   0-39   → MUY_ALTO        (Cumplimiento Crítico)
 */
public enum NivelRiesgo {

    BAJO("Cumplimiento Alto", "BAJO RIESGO", 85),
    MODERADO("Cumplimiento Medio", "RIESGO MODERADO", 65),
    ALTO("Cumplimiento Bajo", "RIESGO ALTO", 40),
    MUY_ALTO("Cumplimiento Crítico", "RIESGO MUY ALTO", 0);

    private final String etiquetaCumplimiento;
    private final String etiquetaRiesgo;
    private final int umbralMinimo;

    NivelRiesgo(String etiquetaCumplimiento, String etiquetaRiesgo, int umbralMinimo) {
        this.etiquetaCumplimiento = etiquetaCumplimiento;
        this.etiquetaRiesgo = etiquetaRiesgo;
        this.umbralMinimo = umbralMinimo;
    }

    public String getEtiquetaCumplimiento() {
        return etiquetaCumplimiento;
    }

    public String getEtiquetaRiesgo() {
        return etiquetaRiesgo;
    }

    public int getUmbralMinimo() {
        return umbralMinimo;
    }

    /**
     * Calcula el nivel de riesgo a partir de una puntuación de cumplimiento (0-100).
     *
     * @param puntuacion puntuación entre 0 y 100
     * @return nivel de riesgo correspondiente
     */
    public static NivelRiesgo desdePuntuacion(int puntuacion) {
        if (puntuacion >= BAJO.umbralMinimo)     return BAJO;
        if (puntuacion >= MODERADO.umbralMinimo) return MODERADO;
        if (puntuacion >= ALTO.umbralMinimo)     return ALTO;
        return MUY_ALTO;
    }
}
