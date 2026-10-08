export const REPORT_LABELS = Object.freeze({
  type: 'Tipo', date: 'Fecha', url: 'URL de referencia',
  score: 'Puntuación de Cumplimiento', level: 'Clasificación',
  description: 'Descripción', evidence: 'Evidencia', impact: 'Impacto',
  action: 'Acción correctiva',
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

  const sections = seccionesInforme(report, REPORT_POLICIES.copy).map(section => {
    let body
    if (section.kind === 'text') body = section.text
    else if (section.items.length === 0) body = section.emptyText
    else if (section.kind === 'findings') {
      body = section.items.map((item, index) => [
        `${index + 1}. ${item.title} [${item.severity}]`,
        ...['description', 'evidence', 'impact', 'action'].map(field =>
          `   ${REPORT_LABELS[field]}: ${item[field]}`),
      ].join('\n')).join('\n\n')
    } else body = section.items.map((item, index) => `${index + 1}. ${item}`).join('\n')
    return `=== ${section.title.toUpperCase()} ===\n${body}`
  })
  // The last section is the exact original text. Never trim the assembled report.
  return [lines.join('\n'), ...sections].join('\n\n')
}
