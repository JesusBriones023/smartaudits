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

/**
 * How a score is made up ("desglose-1"), as final text lines shared by the screen, the PDF and the
 * copied report. Results without a breakdown say so; nothing is reconstructed for them.
 */
export function scoreExplanation(desglose, score) {
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
  lines.push(Number.isFinite(d.porcentajeContextual)
    ? `Evidencia en los controles evaluados por contexto: ${d.porcentajeContextual} %.`
    : `Evidencia en los controles evaluados por contexto: no disponible. ${typeof d.motivoSinPorcentaje === 'string' ? d.motivoSinPorcentaje : ''}`.trim())
  lines.push(claves > 0
    ? `No se calcula una puntuación ajustada: ${claves} puntos dependen de controles por palabras clave cuya aplicabilidad no se determina.`
    : 'No se calcula una puntuación ajustada: los puntos no aplicables o no verificables no se redistribuyen, y evaluar todos los controles por contexto no hace exacto el análisis.')
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
