package com.smartaudits.service.motor;

/**
 * Contenido inmutable para el análisis, independiente de cómo se haya obtenido.
 * Conserva los valores sin normalizarlos; el motor mantiene el tratamiento
 * existente de texto nulo o vacío. El tipo conserva el contexto documental,
 * aunque el motor actual aplica las mismas reglas a todos los tipos.
 */
public record EntradaAnalisis(String texto, String tipoDocumento) {}
