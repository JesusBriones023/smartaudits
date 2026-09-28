package com.smartaudits.service.motor;

import com.smartaudits.model.dto.ResultadoAuditoria;

/** Analiza contenido sin conocer su origen ni su persistencia. */
public interface AnalizadorLegal {
    /** Versión estable de esta implementación y de las reglas que ejecuta. */
    VersionAnalizador version();

    ResultadoAuditoria analyze(EntradaAnalisis entrada);
}
