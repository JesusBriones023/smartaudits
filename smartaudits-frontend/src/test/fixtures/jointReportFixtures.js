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
  categoriasNoAportadas: ['Política de Cookies', 'Condiciones de Uso'],
}

export const informePocosHallazgos = {
  id: 6, titulo: 'Web mínima', fechaCreacion: '2026-02-03T08:30:00', usuarioId: 7, documentosAnalizados: 1,
  documentos: [{ auditoriaId: 201, titulo: 'Aviso', tipoDocumento: 'Aviso Legal', urlOpcional: null, puntuacionRiesgo: 95,
    resumen: 'Correcto.', riesgos: [], versionMotor: '2', versionReglas: '1', incidencias: [] }],
  recomendaciones: [],
  categoriasNoAportadas: ['Política de Privacidad', 'Política de Cookies', 'Términos y Condiciones', 'Condiciones de Uso'],
}

export const payloadHostil = '<img src=x onerror="window.testXss()"><script>window.testXss()</script>'
