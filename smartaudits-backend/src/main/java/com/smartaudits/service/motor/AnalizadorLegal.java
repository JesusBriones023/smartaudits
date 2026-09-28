package com.smartaudits.service.motor;

import com.smartaudits.model.dto.ResultadoAuditoria;

/** Analiza contenido sin conocer su origen ni su persistencia. */
@FunctionalInterface
public interface AnalizadorLegal {
    ResultadoAuditoria analyze(EntradaAnalisis entrada);
}
