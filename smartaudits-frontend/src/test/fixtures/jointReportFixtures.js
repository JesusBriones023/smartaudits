// Shape of AuditoriaConjuntaResponse as persisted and returned by the backend.
const largo = 'Cláusula de conservación indefinida de datos personales sin plazo ni criterio. '.repeat(40)
const urlLarga = 'https://www.ejemplo-de-empresa-con-nombre-largo.es/legal/politica-de-privacidad-y-proteccion-de-datos-personales-version-2026-revisada?utm_source=ñandú&ref=' + 'x'.repeat(180)

export const informeVariosDocumentos = {
  id: 5,
  titulo: 'Auditoría «Tienda Ñandú» — Política & Términos: ¿cumple? ¡Revísalo!',
  fechaCreacion: '2026-01-02T12:00:00',
  usuarioId: 7,
  documentosAnalizados: 3,
  documentos: [
    {
      auditoriaId: 101, titulo: 'Política de Privacidad', tipoDocumento: 'Política de Privacidad', urlOpcional: urlLarga,
      puntuacionRiesgo: 62, resumen: 'Falta concretar la conservación y los derechos de oposición.',
      riesgos: ['Conservación excesiva de datos', 'Derecho de oposición no explicado'],
      versionMotor: '2', versionReglas: '1',
      incidencias: [
        { ruleId: 'R05', motor: 'LEGAL_TEXT', version: '1', titulo: 'Plazo de conservación ausente', severidad: 'ALTA',
          descripcion: largo, evidencia: 'Conservaremos sus datos indefinidamente' + '-sin-espacios'.repeat(30),
          impacto: 'Incumplimiento del principio de limitación del plazo.', accion: 'Indicar un plazo concreto.' },
        { ruleId: 'R07', motor: 'LEGAL_TEXT', version: '1', titulo: 'Derechos incompletos', severidad: 'MEDIA',
          descripcion: 'No se explica la oposición.', evidencia: '', impacto: 'Información incompleta.', accion: 'Añadir el derecho.' },
      ],
    },
    {
      auditoriaId: 102, titulo: 'Aviso Legal', tipoDocumento: 'Aviso Legal', urlOpcional: null,
      puntuacionRiesgo: 90, resumen: 'Identificación completa del titular.', riesgos: [],
      versionMotor: '2', versionReglas: '1', incidencias: [],
    },
    {
      auditoriaId: 103, titulo: 'Términos y Condiciones', tipoDocumento: 'Términos y Condiciones', urlOpcional: null,
      puntuacionRiesgo: null, resumen: null, riesgos: null, versionMotor: '2', versionReglas: '1',
      incidencias: [{ ruleId: 'R11', motor: 'LEGAL_TEXT', version: '1', titulo: 'Ley aplicable no indicada', severidad: 'BAJA',
        descripcion: 'Sin cláusula de jurisdicción.', evidencia: 'No consta', impacto: '', accion: '' }],
    },
  ],
  recomendaciones: [
    { texto: 'Indicar plazos de conservación concretos', auditoriaIds: [101, 103], documentos: ['Política de Privacidad', 'Términos y Condiciones'] },
    { texto: 'Revisar la redacción de los derechos ARSOPL', auditoriaIds: [101], documentos: ['Política de Privacidad'] },
  ],
  categoriasNoAportadas: ['Política de Cookies', 'Condiciones de Uso', 'Condiciones de Compra', 'Accesibilidad'],
  // Document 103 was stored before evidence was kept; 102 is declared «Aviso Legal» but reads as purchase conditions.
  cobertura: {
    categorias: [
      { categoria: 'POLITICA_PRIVACIDAD', etiqueta: 'Política de Privacidad', estado: 'DOCUMENTO_ANALIZADO',
        documentos: [{ auditoriaId: 101, titulo: 'Política de Privacidad', categoriaDeclarada: 'Política de Privacidad', historico: false,
          evidencias: [{ categoria: 'POLITICA_PRIVACIDAD', origen: 'H1', evidencia: 'Política de privacidad — Tienda Ñandú' }] }],
        enlaces: [], contradicciones: [] },
      { categoria: 'POLITICA_COOKIES', etiqueta: 'Política de Cookies', estado: 'ENLACE_DETECTADO_NO_ANALIZADO', documentos: [],
        enlaces: [{ url: 'https://www.ejemplo.es/cookies', relativa: false, origenes: [
          { auditoriaId: 101, titulo: 'Política de Privacidad', texto: 'Cookies', contexto: 'footer', senal: 'texto' },
          { auditoriaId: 102, titulo: 'Aviso Legal', texto: 'Política de cookies', contexto: 'nav', senal: 'texto' }] }],
        contradicciones: [] },
      { categoria: 'AVISO_LEGAL', etiqueta: 'Aviso Legal', estado: 'NO_VERIFICADO', documentos: [], enlaces: [],
        contradicciones: [{ auditoriaId: 102, titulo: 'Aviso Legal', identificadas: ['Condiciones de Compra'] }] },
      { categoria: 'TERMINOS_CONDICIONES', etiqueta: 'Términos y Condiciones', estado: 'DOCUMENTO_ANALIZADO',
        documentos: [{ auditoriaId: 103, titulo: 'Términos y Condiciones', categoriaDeclarada: 'Términos y Condiciones', historico: true,
          evidencias: [{ categoria: 'TERMINOS_CONDICIONES', origen: 'Categoría declarada (auditoría anterior sin evidencias conservadas)', evidencia: 'Términos y Condiciones' }] }],
        enlaces: [], contradicciones: [] },
      { categoria: 'CONDICIONES_USO', etiqueta: 'Condiciones de Uso', estado: 'NO_VERIFICADO', documentos: [], enlaces: [], contradicciones: [] },
      { categoria: 'CONDICIONES_COMPRA', etiqueta: 'Condiciones de Compra', estado: 'DOCUMENTO_ANALIZADO',
        documentos: [{ auditoriaId: 102, titulo: 'Aviso Legal', categoriaDeclarada: 'Aviso Legal', historico: false,
          evidencias: [{ categoria: 'CONDICIONES_COMPRA', origen: 'H1', evidencia: 'Condiciones generales de contratación' }] }],
        enlaces: [], contradicciones: [] },
      { categoria: 'DECLARACION_ACCESIBILIDAD', etiqueta: 'Accesibilidad', estado: 'ENLACE_DETECTADO_NO_ANALIZADO', documentos: [],
        enlaces: [{ url: '/accesibilidad', relativa: true, origenes: [
          { auditoriaId: 102, titulo: 'Aviso Legal', texto: 'Accesibilidad', contexto: 'footer', senal: 'texto' }] }],
        contradicciones: [] },
    ],
    sinEvidencias: [{ auditoriaId: 103, titulo: 'Términos y Condiciones' }],
    enlacesLimitados: [{ auditoriaId: 102, titulo: 'Aviso Legal' }],
  },
}

export const informePocosHallazgos = {
  id: 6, titulo: 'Web mínima', fechaCreacion: '2026-02-03T08:30:00', usuarioId: 7, documentosAnalizados: 1,
  documentos: [{ auditoriaId: 201, titulo: 'Aviso', tipoDocumento: 'Aviso Legal', urlOpcional: null, puntuacionRiesgo: 95,
    resumen: 'Correcto.', riesgos: [], versionMotor: '2', versionReglas: '1', incidencias: [] }],
  recomendaciones: [],
  categoriasNoAportadas: ['Política de Privacidad', 'Política de Cookies', 'Términos y Condiciones', 'Condiciones de Uso'],
}

export const payloadHostil = '<img src=x onerror="window.testXss()"><script>window.testXss()</script>'
