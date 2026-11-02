import { getNivelCumplimiento } from '../utils/helpers'
import { EVIDENCE_STATES, REPORT_FALLBACKS, REPORT_LABELS, SCORE_DISCLAIMER, coverageExplanation, formatReportDate, scoreExplanation } from './reportPresentation'

// Single view model of a joint audit for the screen and the PDF: both render exactly
// this, built from the persisted, authorized DTO. Nothing here computes a global score
// or adds conclusions; the score label and classification are the product's own.
export const JOINT_LABELS = Object.freeze({
  score: REPORT_LABELS.score,
  level: REPORT_LABELS.level,
  category: 'Categoría',
  url: REPORT_LABELS.url,
  engine: 'Motor de análisis',
  scoring: 'Cómo se compone la puntuación',
  evaluationCoverage: 'Cobertura de la evaluación',
  summary: 'Resumen',
  risks: 'Incumplimientos detectados',
  findings: 'Hallazgos',
  severity: 'Severidad',
  description: REPORT_LABELS.description,
  evidence: REPORT_LABELS.evidence,
  impact: REPORT_LABELS.impact,
  action: REPORT_LABELS.action,
  status: REPORT_LABELS.status,
  recommendations: 'Recomendaciones agrupadas',
  appliesTo: 'Aplica a',
  missing: 'Documentos no aportados',
  coverage: 'Cobertura documental',
  coverageCategory: 'Categoría',
  coverageState: 'Estado',
  coverageDetail: 'Detalle',
  limitations: 'Limitaciones del análisis',
})

export const JOINT_FALLBACKS = Object.freeze({
  evidence: 'No disponible',
  noFindings: 'Sin hallazgos detectados',
  noRisks: 'Ninguno',
  noRecommendations: 'No hay recomendaciones.',
  noMissing: 'Se aportaron todas las categorías legales.',
})

// The three states the backend computes; the labels never claim more than the state means.
export const COVERAGE_STATES = Object.freeze({
  DOCUMENTO_ANALIZADO: 'Documento analizado',
  ENLACE_DETECTADO_NO_ANALIZADO: 'Enlace detectado, no analizado',
  NO_VERIFICADO: 'No verificado',
})

export const COVERAGE_TEXTS = Object.freeze({
  intro: 'Qué categorías legales cubren los documentos aportados. Un enlace detectado no significa que se haya analizado su contenido, y una categoría no verificada no demuestra que la web no la publique.',
  unavailable: 'Este informe no incluye la matriz de cobertura documental.',
  notVerified: 'Ningún documento aportado pertenece a esta categoría ni enlaza a ella.',
  historic: 'Documentos analizados antes de que se conservaran evidencias de clasificación (se muestra su categoría declarada y no se conocen sus enlaces): ',
  limited: 'La lista de enlaces se recortó por los límites de procesamiento o almacenamiento en: ',
})

const CONTEXTS = Object.freeze({ footer: 'pie de página', nav: 'navegación', header: 'cabecera', aside: 'lateral', cuerpo: 'cuerpo' })

export const JOINT_LIMITATIONS = Object.freeze([
  'El análisis se basa únicamente en el HTML aportado por el usuario. SmartAudits no ha descargado, visitado ni verificado las páginas publicadas ni las URLs indicadas.',
  'Es una revisión automática del texto con el motor de SmartAudits. No acredita por sí sola el cumplimiento normativo ni sustituye el asesoramiento jurídico.',
  'Cada documento se puntúa por separado. No se calcula una puntuación global porque no hay reglas definidas para combinarlas.',
  'Solo se han evaluado los documentos aportados; las categorías no aportadas no se han analizado.',
  'Los enlaces legales se han detectado por análisis estático del HTML aportado: no se han abierto ni descargado y su contenido no se ha analizado.',
  SCORE_DISCLAIMER + ' Las puntuaciones de documentos de distinto tipo no son comparables entre sí.',
])

const text = value => (typeof value === 'string' ? value : '')

export function formatScore(value) {
  return value === null || value === undefined ? REPORT_FALLBACKS.score : `${value}/100`
}

export function scoreClassification(value) {
  return value === null || value === undefined ? null : getNivelCumplimiento(value).etiqueta
}

const list = value => (Array.isArray(value) ? value : [])
const titles = refs => list(refs).map(r => `«${text(r.titulo)}»`).join(', ')

/** Every line of the matrix as final text, so the screen and the PDF draw the same strings. */
export function buildCoverage(cobertura) {
  if (!cobertura || !Array.isArray(cobertura.categorias)) return null
  const notes = []
  if (list(cobertura.sinEvidencias).length > 0) notes.push(COVERAGE_TEXTS.historic + titles(cobertura.sinEvidencias))
  if (list(cobertura.enlacesLimitados).length > 0) notes.push(COVERAGE_TEXTS.limited + titles(cobertura.enlacesLimitados))
  return {
    rows: cobertura.categorias.map(f => ({
      key: text(f.categoria),
      label: text(f.etiqueta),
      state: COVERAGE_STATES[f.estado] ? f.estado : 'NO_VERIFICADO',
      stateLabel: COVERAGE_STATES[f.estado] || COVERAGE_STATES.NO_VERIFICADO,
      documents: list(f.documentos).map(d => ({
        text: `«${text(d.titulo)}»`
          + (text(d.categoriaDeclarada) && d.categoriaDeclarada !== f.etiqueta ? ` (aportado como ${text(d.categoriaDeclarada)})` : '')
          + (d.historico ? ' · sin evidencias conservadas' : ''),
        evidence: list(d.evidencias).map(e => `${text(e.origen)}: ${text(e.evidencia)}`),
      })),
      links: list(f.enlaces).map(e => ({
        text: text(e.url) + (e.relativa ? ' (ruta relativa, sin URL de referencia)' : ''),
        sources: 'Enlazado desde ' + list(e.origenes)
          .map(o => `«${text(o.titulo)}» (${CONTEXTS[o.contexto] || text(o.contexto)})`).join(', '),
      })),
      contradictions: list(f.contradicciones).map(c => `«${text(c.titulo)}» se aportó como esta categoría, pero su contenido corresponde a: `
        + (list(c.identificadas).map(text).join(', ') || 'ninguna categoría reconocible')),
    })),
    notes,
  }
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
      scoring: scoreExplanation(d.desglosePuntuacion, d.puntuacionRiesgo, d.coberturaEvaluacion),
      // Each document's own coverage: there is no joint coverage, as there is no joint score.
      evaluationCoverage: coverageExplanation(d.coberturaEvaluacion, d.puntuacionRiesgo),
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
        status: EVIDENCE_STATES[i.estado] ?? null,
      })),
    })),
    recommendations: (Array.isArray(informe?.recomendaciones) ? informe.recomendaciones : []).map(r => ({
      text: text(r.texto),
      documents: (Array.isArray(r.documentos) ? r.documentos : []).map(text),
    })),
    missing: (Array.isArray(informe?.categoriasNoAportadas) ? informe.categoriasNoAportadas : []).map(text),
    coverage: buildCoverage(informe?.cobertura),
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
