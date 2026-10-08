import { getNivelCumplimiento } from '../utils/helpers'

const text = value => value == null ? '' : String(value)
const optionalText = value => value == null ? null : String(value)
const items = value => Array.isArray(value) ? value.filter(item => item != null) : []
const texts = value => Object.freeze(items(value).map(text))

function referenceUrl(value) {
  const original = text(value)
  let href = null
  try {
    const url = new URL(original)
    if (url.protocol === 'http:' || url.protocol === 'https:') href = url.href
  } catch { /* Preserve the original text, including malformed URLs. */ }
  return Object.freeze({ text: original, href })
}

function finding(value) {
  const severity = text(value.severidad)
  const normalized = severity.trim().toUpperCase()
  return Object.freeze({
    ruleId: optionalText(value.ruleId),
    motor: optionalText(value.motor),
    version: optionalText(value.version),
    title: text(value.titulo),
    description: text(value.descripcion),
    severity,
    severityLevel: ['ALTA', 'MEDIA', 'BAJA'].includes(normalized) ? normalized : null,
    evidence: text(value.evidencia),
    impact: text(value.impacto),
    action: text(value.accion),
  })
}

/** Pure, detached, immutable presentation data for a complete audit response. */
export function buildAuditReport(auditoriaResponse) {
  const audit = auditoriaResponse ?? {}
  const result = audit.resultado ?? {}
  const rawScore = audit.puntuacionRiesgo
  const value = typeof rawScore === 'number' && Number.isFinite(rawScore)
    && rawScore >= 0 && rawScore <= 100 ? rawScore : null
  // Use the existing classification, retaining only its semantic fields.
  // Its renderer-specific styling must not enter the report model.
  const classification = value === null ? null : getNivelCumplimiento(value)
  const level = classification && Object.freeze({
    nivel: classification.nivel,
    etiqueta: classification.etiqueta,
    etiquetaRiesgo: classification.etiquetaRiesgo,
  })

  return Object.freeze({
    metadata: Object.freeze({
      id: audit.id ?? null,
      title: text(audit.titulo),
      documentType: text(audit.tipoDocumento),
      createdAt: optionalText(audit.fechaCreacion),
      referenceUrl: referenceUrl(audit.urlOpcional),
    }),
    score: Object.freeze({ value, level }),
    summary: text(result.resumen),
    risks: texts(result.riesgos),
    findings: Object.freeze(items(result.errores).map(finding)),
    recommendations: texts(result.recomendaciones),
    unverifiableItems: texts(result.faltantes),
    suggestedTexts: texts(result.textosSugeridos),
    legalReferences: texts(result.referenciasLegales),
    originalText: text(audit.textoOriginal),
    provenance: Object.freeze({
      versionMotor: optionalText(audit.versionMotor),
      versionReglas: optionalText(audit.versionReglas),
      fechaAnalisis: optionalText(audit.fechaAnalisis),
      tipoFuente: optionalText(audit.tipoFuente),
    }),
  })
}
