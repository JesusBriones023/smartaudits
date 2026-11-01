import { getNivelCumplimiento } from '../utils/helpers'
import { REPORT_FALLBACKS, REPORT_LABELS, formatReportDate } from './reportPresentation'

// Single view model of a joint audit for the screen and the PDF: both render exactly
// this, built from the persisted, authorized DTO. Nothing here computes a global score
// or adds conclusions; the score label and classification are the product's own.
export const JOINT_LABELS = Object.freeze({
  score: REPORT_LABELS.score,
  level: REPORT_LABELS.level,
  category: 'Categoría',
  url: REPORT_LABELS.url,
  engine: 'Motor de análisis',
  summary: 'Resumen',
  risks: 'Incumplimientos detectados',
  findings: 'Hallazgos',
  severity: 'Severidad',
  description: REPORT_LABELS.description,
  evidence: REPORT_LABELS.evidence,
  impact: REPORT_LABELS.impact,
  action: REPORT_LABELS.action,
  recommendations: 'Recomendaciones agrupadas',
  appliesTo: 'Aplica a',
  missing: 'Documentos no aportados',
  limitations: 'Limitaciones del análisis',
})

export const JOINT_FALLBACKS = Object.freeze({
  evidence: 'No disponible',
  noFindings: 'Sin hallazgos detectados',
  noRisks: 'Ninguno',
  noRecommendations: 'No hay recomendaciones.',
  noMissing: 'Se aportaron todas las categorías legales.',
})

export const JOINT_LIMITATIONS = Object.freeze([
  'El análisis se basa únicamente en el HTML aportado por el usuario. SmartAudits no ha descargado, visitado ni verificado las páginas publicadas ni las URLs indicadas.',
  'Es una revisión automática del texto con el motor de SmartAudits. No acredita por sí sola el cumplimiento normativo ni sustituye el asesoramiento jurídico.',
  'Cada documento se puntúa por separado. No se calcula una puntuación global porque no hay reglas definidas para combinarlas.',
  'Solo se han evaluado los documentos aportados; las categorías no aportadas no se han analizado.',
])

const text = value => (typeof value === 'string' ? value : '')

export function formatScore(value) {
  return value === null || value === undefined ? REPORT_FALLBACKS.score : `${value}/100`
}

export function scoreClassification(value) {
  return value === null || value === undefined ? null : getNivelCumplimiento(value).etiqueta
}

export function buildJointReport(informe) {
  const documentos = Array.isArray(informe?.documentos) ? informe.documentos : []
  return {
    title: text(informe?.titulo),
    date: formatReportDate(informe?.fechaCreacion),
    documentCount: documentos.length,
    documents: documentos.map(d => ({
      id: d.auditoriaId,
      title: text(d.titulo),
      category: text(d.tipoDocumento) || REPORT_FALLBACKS.documentType,
      url: text(d.urlOpcional) || null,
      score: formatScore(d.puntuacionRiesgo),
      classification: scoreClassification(d.puntuacionRiesgo),
      engine: `versión del motor ${text(d.versionMotor) || '?'} · reglas ${text(d.versionReglas) || '?'}`,
      summary: text(d.resumen) || REPORT_FALLBACKS.summary,
      risks: (Array.isArray(d.riesgos) ? d.riesgos : []).map(text),
      findings: (Array.isArray(d.incidencias) ? d.incidencias : []).map(i => ({
        title: text(i.titulo),
        severity: text(i.severidad),
        description: text(i.descripcion),
        evidence: text(i.evidencia) || JOINT_FALLBACKS.evidence,
        impact: text(i.impacto),
        action: text(i.accion),
      })),
    })),
    recommendations: (Array.isArray(informe?.recomendaciones) ? informe.recomendaciones : []).map(r => ({
      text: text(r.texto),
      documents: (Array.isArray(r.documentos) ? r.documentos : []).map(text),
    })),
    missing: (Array.isArray(informe?.categoriasNoAportadas) ? informe.categoriasNoAportadas : []).map(text),
    limitations: JOINT_LIMITATIONS,
  }
}

/** File name without path separators, reserved or control characters. */
export function jointPdfFileName(title) {
  const safe = Array.from(text(title))
    .map(c => (c.charCodeAt(0) < 32 || '<>:"/\\|?*'.includes(c) ? '_' : c))
    .join('').replace(/\s+/g, ' ').trim().slice(0, 120)
  return `Auditoria conjunta - ${safe || 'SmartAudits'}.pdf`
}
