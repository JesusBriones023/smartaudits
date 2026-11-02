export const REPORT_LABELS = Object.freeze({
  type: 'Tipo', date: 'Fecha', url: 'URL de referencia',
  score: 'Puntuación del análisis automático', level: 'Nivel de la puntuación',
  description: 'Descripción', evidence: 'Evidencia', impact: 'Impacto',
  action: 'Acción correctiva', status: 'Estado de la evidencia',
})

// Shown wherever a score is: the number summarises textual evidence, nothing more.
export const SCORE_DISCLAIMER = 'La puntuación resume la evidencia que el motor encuentra en el texto aportado. '
  + 'No certifica el cumplimiento normativo ni prueba el comportamiento real del sitio web.'

export const SCORE_BREAKDOWN_TITLE = 'Cómo se compone la puntuación'

const count = value => (Number.isFinite(value) ? value : 0)

export const COVERAGE_TITLE = 'Cobertura de la evaluación'
const COVERAGE_UNAVAILABLE = 'Cobertura no disponible: este análisis es anterior a esa información y no se reconstruye.'

const hasCoverage = cobertura => Boolean(cobertura) && typeof cobertura === 'object' && cobertura.version === 'cobertura-1'

/**
 * What the score could cover ("cobertura-1"): how much of the applicable weight the text let the engine evaluate,
 * the score over that evaluated weight and how the controls were settled. The engine computes it; this only words it.
 * Results without it say so; nothing is reconstructed for them.
 */
export function coverageExplanation(cobertura, score) {
  if (!hasCoverage(cobertura)) {
    return Object.freeze({ available: false, warnings: Object.freeze([]), lines: Object.freeze([COVERAGE_UNAVAILABLE]) })
  }
  const c = cobertura
  const pendientes = Array.isArray(c.controlesNoVerificables) ? c.controlesNoVerificables.length : 0
  const evaluados = count(c.suficientes) + count(c.insuficientes) + count(c.incidenciasRedaccion) + count(c.incidenciasAusencia)
  const resultado = Number.isFinite(c.resultadoControlesEvaluados) ? c.resultadoControlesEvaluados : null
  const cobertura100 = Number.isFinite(c.porcentajeCobertura) ? c.porcentajeCobertura : null
  const lines = [
    cobertura100 === null
      ? 'Cobertura: ningún control es aplicable a este tipo de documento.'
      : `Cobertura: ${cobertura100} % del peso de los controles aplicables (${count(c.pesoEvaluado)} de ${count(c.pesoAplicable)} puntos evaluados).`,
    resultado === null
      ? `Resultado en los controles evaluados: no se calcula. ${typeof c.motivoSinResultado === 'string' ? c.motivoSinResultado : ''}`.trim()
      : `Resultado en los controles evaluados: ${resultado} %`
        + (Number.isFinite(score) ? ` (${score} puntos sobre los ${count(c.pesoEvaluado)} evaluados).` : '.'),
    `Controles: ${count(c.suficientes)} con evidencia suficiente, ${count(c.insuficientes)} con información insuficiente, `
      + `${count(c.incidenciasRedaccion)} con redacción problemática, ${count(c.incidenciasAusencia)} sin la información que `
      + `el documento debería dar, ${pendientes} pendientes de verificación y ${count(c.noAplicables)} no aplicables.`,
  ]
  if (count(c.riesgosGlobales) > 0) {
    lines.push(`Riesgos detectados en el texto: ${count(c.riesgosGlobales)}; restan 2 puntos cada uno, también en el resultado anterior.`)
  }
  const warnings = []
  if (pendientes > 0) {
    warnings.push(`${pendientes} ${pendientes === 1 ? 'control aplicable está pendiente' : 'controles aplicables están pendientes'} `
      + 'de verificación: el texto no permite saber si se cumplen y no se cuentan como incumplidos.')
  }
  if (resultado !== null && evaluados < 3) {
    warnings.push(`Solo se ${evaluados === 1 ? 'ha evaluado 1 control' : `han evaluado ${evaluados} controles`}: el resultado describe únicamente ${evaluados === 1 ? 'ese control' : 'esos controles'}.`)
  }
  if (resultado !== null && resultado >= 85 && (cobertura100 < 100 || evaluados < 3)) {
    warnings.push('Un resultado alto se limita a lo que el texto permite evaluar y no demuestra el cumplimiento del documento.')
  }
  return Object.freeze({ available: true, warnings: Object.freeze(warnings), lines: Object.freeze(lines) })
}

/**
 * How a score is made up ("desglose-1"), as final text lines shared by the screen, the PDF and the
 * copied report. Results without a breakdown say so; nothing is reconstructed for them. With an evaluation
 * coverage the share over evaluated controls is given there instead, so it is not repeated here.
 */
export function scoreExplanation(desglose, score, cobertura) {
  if (!desglose || typeof desglose !== 'object' || desglose.version !== 'desglose-1') {
    return Object.freeze({ available: false, warning: null, lines: Object.freeze([
      'Desglose no disponible: este análisis es anterior a esa información y no se reconstruye.',
    ]) })
  }
  // A breakdown that does not add up to the stored score is never shown.
  if (desglose.puntuacion !== score) {
    return Object.freeze({ available: false, warning: null, lines: Object.freeze([
      'Desglose no disponible: no coincide con la puntuación guardada.',
    ]) })
  }
  const d = desglose
  // Rules version 5 evaluates every control by context; only breakdowns stored before it carry keyword weight.
  const claves = count(d.pesoPalabrasClave)
  const lines = [
    `Puntos acreditados por el texto: ${count(d.puntosAcreditados)} de ${count(d.pesoTotal)}`
      + (count(d.penalizacionRiesgos) > 0 ? `; se restan ${count(d.penalizacionRiesgos)} por riesgos detectados.` : '.'),
    `Controles evaluados por contexto, aplicables y verificables: ${count(d.puntosContextuales)} de ${count(d.pesoContextualEvaluado)} puntos.`,
  ]
  if (claves > 0) lines.push(`Controles evaluados por palabras clave, sin determinar si aplican: ${count(d.puntosPalabrasClave)} de ${claves} puntos.`)
  if (count(d.pesoNoAplicable) > 0) lines.push(`Controles no aplicables a este tipo de documento: ${count(d.pesoNoAplicable)} puntos que no se suman.`)
  if (count(d.pesoNoVerificable) > 0) lines.push(`Controles no verificables con el texto: ${count(d.pesoNoVerificable)} puntos que no se suman.`)
  if (hasCoverage(cobertura)) {
    lines.push('La puntuación global cuenta como 0 los controles no aplicables y no verificables; lo que el texto permite '
      + 'evaluar se indica en la cobertura de la evaluación.')
  } else {
    lines.push(Number.isFinite(d.porcentajeContextual)
      ? `Evidencia en los controles evaluados por contexto: ${d.porcentajeContextual} %.`
      : `Evidencia en los controles evaluados por contexto: no disponible. ${typeof d.motivoSinPorcentaje === 'string' ? d.motivoSinPorcentaje : ''}`.trim())
    lines.push(claves > 0
      ? `No se calcula una puntuación ajustada: ${claves} puntos dependen de controles por palabras clave cuya aplicabilidad no se determina.`
      : 'No se calcula una puntuación ajustada: los puntos no aplicables o no verificables no se redistribuyen, y evaluar todos los controles por contexto no hace exacto el análisis.')
  }
  const fuera = count(d.pesoNoAplicable) + count(d.pesoNoVerificable)
  return Object.freeze({
    available: true,
    warning: fuera > 0 ? `${fuera} de los 100 puntos no pueden obtenerse en este documento: la puntuación no es comparable con la de documentos de otro tipo.` : null,
    lines: Object.freeze(lines),
  })
}

// Evidence states of the context-aware rules (rules version 2). Results without them show nothing.
export const EVIDENCE_STATES = Object.freeze({
  SUFICIENTE: 'Evidencia suficiente',
  INSUFICIENTE: 'Evidencia insuficiente',
  POSIBLE_INCIDENCIA: 'Posible incidencia',
  NO_VERIFICABLE: 'No verificable con el contenido',
  NO_APLICABLE: 'No aplicable a este documento',
})

export const REPORT_FALLBACKS = Object.freeze({
  score: 'No disponible', summary: 'Sin resumen disponible.',
  documentType: 'No especificado', date: 'Fecha no disponible',
})

export const REPORT_SECTIONS = Object.freeze([
  { id: 'summary', title: 'Resumen', kind: 'text' },
  { id: 'risks', title: 'Incumplimientos detectados', kind: 'list', emptyText: 'Ninguno' },
  { id: 'findings', title: 'Errores detectados', kind: 'findings', emptyText: 'Ninguno' },
  { id: 'recommendations', title: 'Recomendaciones', kind: 'list', emptyText: 'Ninguna' },
  { id: 'suggestedTexts', title: 'Textos sugeridos', kind: 'list', emptyText: 'Ninguno' },
  { id: 'unverifiableItems', title: 'Elementos no verificables', kind: 'list', emptyText: 'Ninguno' },
  { id: 'legalReferences', title: 'Referencias legales', kind: 'list', emptyText: 'Ninguna' },
  { id: 'originalText', title: 'Texto original auditado', kind: 'text' },
].map(Object.freeze))

export const REPORT_POLICIES = Object.freeze({
  screen: Object.freeze({ showEmptyLists: false }),
  pdf: Object.freeze({ showEmptyLists: false }),
  copy: Object.freeze({ showEmptyLists: true }),
})

export function formatReportDate(value) {
  if (!value) return REPORT_FALLBACKS.date
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? REPORT_FALLBACKS.date : date.toLocaleDateString('es-ES')
}

export function reportHeader(report) {
  return {
    title: report.metadata.title,
    documentType: report.metadata.documentType || REPORT_FALLBACKS.documentType,
    date: formatReportDate(report.metadata.createdAt),
    score: report.score.value === null ? REPORT_FALLBACKS.score : String(report.score.value),
    level: report.score.level?.etiqueta ?? null,
    referenceUrl: report.metadata.referenceUrl.text,
  }
}

/** The only decision point for section order, content fallbacks and empty lists. */
export function seccionesInforme(report, policy) {
  return REPORT_SECTIONS.map(section => section.kind === 'text'
    ? { ...section, text: section.id === 'summary'
      ? report.summary || REPORT_FALLBACKS.summary : report.originalText }
    : { ...section, items: report[section.id] })
    .filter(section => section.kind === 'text' || section.items.length > 0 || policy.showEmptyLists)
}

export function renderReportText(report) {
  const header = reportHeader(report)
  const lines = [
    `AUDITORÍA LEGAL — ${header.title}`,
    `${REPORT_LABELS.type}: ${header.documentType}`,
    `${REPORT_LABELS.date}: ${header.date}`,
  ]
  if (header.referenceUrl) lines.push(`${REPORT_LABELS.url}: ${header.referenceUrl}`)
  lines.push(`${REPORT_LABELS.score}: ${header.score}${report.score.value === null ? '' : '/100'}`)
  if (header.level) lines.push(`${REPORT_LABELS.level}: ${header.level}`)
  lines.push(SCORE_DISCLAIMER)
  if (report.scoring?.warning) lines.push(report.scoring.warning)
  for (const line of report.scoring?.lines ?? []) lines.push(`- ${line}`)
  if (report.coverage) {
    lines.push(`${COVERAGE_TITLE}:`)
    for (const warning of report.coverage.warnings) lines.push(warning)
    for (const line of report.coverage.lines) lines.push(`- ${line}`)
  }

  const sections = seccionesInforme(report, REPORT_POLICIES.copy).map(section => {
    let body
    if (section.kind === 'text') body = section.text
    else if (section.items.length === 0) body = section.emptyText
    else if (section.kind === 'findings') {
      body = section.items.map((item, index) => [
        `${index + 1}. ${item.title} [${item.severity}]`,
        ...[...(item.status ? ['status'] : []), 'description', 'evidence', 'impact', 'action'].map(field =>
          `   ${REPORT_LABELS[field]}: ${item[field]}`),
      ].join('\n')).join('\n\n')
    } else body = section.items.map((item, index) => `${index + 1}. ${item}`).join('\n')
    return `=== ${section.title.toUpperCase()} ===\n${body}`
  })
  // The last section is the exact original text. Never trim the assembled report.
  return [lines.join('\n'), ...sections].join('\n\n')
}
