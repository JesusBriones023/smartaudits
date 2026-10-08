import { describe, expect, it } from 'vitest'
import { buildAuditReport } from './auditReport'
import { REPORT_POLICIES, REPORT_SECTIONS, renderReportText, reportHeader, seccionesInforme } from './reportPresentation'
import { auditoriaCompleta, auditoriaLegado, auditoriaPuntuacionNull } from '../test/fixtures/auditReportFixtures'

function freezeDeep(value) {
  if (value && typeof value === 'object') {
    Object.values(value).forEach(freezeDeep)
    Object.freeze(value)
  }
  return value
}

describe('AuditReportModel contract', () => {
  it('builds the exact semantic model without owner, secondary score or renderer styles', () => {
    expect(buildAuditReport(auditoriaCompleta)).toEqual({
      metadata: { id: 42, title: 'Informe / privado', documentType: 'Aviso Legal',
        createdAt: '2026-01-02T12:00:00',
        referenceUrl: { text: 'https://example.invalid/legal', href: 'https://example.invalid/legal' } },
      score: { value: 62, level: { nivel: 'ALTO', etiqueta: 'Cumplimiento Bajo', etiquetaRiesgo: 'RIESGO ALTO' } },
      summary: 'Resumen sintético',
      risks: ['Plazo ausente', ' <script>riesgo</script> ', 'Plazo ausente'],
      findings: [
        { ruleId: 'R05', motor: 'LEGAL_TEXT', version: 'regla-1', title: 'Conservación',
          description: 'Falta plazo', severity: 'MEDIA', severityLevel: 'MEDIA', evidence: 'Sin duración',
          impact: 'Conservación excesiva', action: 'Indicar plazo' },
        { ruleId: 'R05', motor: 'LEGAL_TEXT', version: 'regla-2', title: '<script>título</script>',
          description: 'Descripción segunda', severity: ' alta ', severityLevel: 'ALTA',
          evidence: '<img src=x onerror="alert(1)">', impact: 'Impacto segundo', action: 'Acción segunda' },
        { ruleId: 'R19', motor: 'LEGAL_TEXT', version: 'regla-1', title: 'Tercer hallazgo',
          description: 'Descripción tercera', severity: 'BAJA', severityLevel: 'BAJA',
          evidence: 'Evidencia tercera', impact: 'Impacto tercero', action: 'Acción tercera' },
      ],
      recommendations: ['Definir duración', '<script>recomendación</script>', 'Definir duración'],
      unverifiableItems: ['Duración', 'Responsable'],
      suggestedTexts: ['Conservamos durante un año.', '  <img src=x onerror="alert(2)">\n'],
      legalReferences: ['RGPD', 'Referencia sin ruta', 'RGPD'],
      originalText: '<script>alert("texto")</script>\n \n',
      provenance: { versionMotor: 'motor-superior-2', versionReglas: 'reglas-superiores-1',
        fechaAnalisis: '2026-01-01T11:00:00', tipoFuente: 'MANUAL' },
    })
  })

  it('is deterministic, accepts frozen input and returns detached immutable data', () => {
    const input = freezeDeep(structuredClone(auditoriaCompleta))
    const before = structuredClone(input)
    const report = buildAuditReport(input)
    expect(buildAuditReport(input)).toEqual(report)
    expect(input).toEqual(before)
    expect(report.risks).not.toBe(input.resultado.riesgos)
    expect(report.findings[0]).not.toBe(input.resultado.errores[0])
    expect(() => report.risks.push('mutación')).toThrow(TypeError)
    expect(() => { report.findings[0].title = 'mutación' }).toThrow(TypeError)
    expect(() => { report.provenance.versionMotor = 'actual' }).toThrow(TypeError)
    expect(() => { report.score.level.nivel = 'BAJO' }).toThrow(TypeError)
  })

  it('keeps historical metadata absent and never reconciles divergent scores', () => {
    const report = buildAuditReport(auditoriaLegado)
    expect(report.score.value).toBe(17)
    expect(report.provenance).toEqual({ versionMotor: null, versionReglas: null, fechaAnalisis: null, tipoFuente: null })
    expect(report.findings).toEqual([{ ruleId: null, motor: null, version: null,
      title: 'Hallazgo histórico', description: '', severity: '', severityLevel: null,
      evidence: 'Evidencia histórica', impact: '', action: '' }])
    expect(report.suggestedTexts).toEqual([])
    expect(report.recommendations).toEqual([])
    expect(report.originalText).toBe(auditoriaLegado.textoOriginal)
  })

  it.each(['riesgos', 'recomendaciones', 'faltantes', 'textosSugeridos', 'referenciasLegales'])('normalizes %s without losing order, duplicates, spaces or scalars', field => {
    const mapping = { riesgos: 'risks', recomendaciones: 'recommendations', faltantes: 'unverifiableItems',
      textosSugeridos: 'suggestedTexts', referenciasLegales: 'legalReferences' }
    const report = buildAuditReport({ resultado: { [field]: [null, ' a ', undefined, 0, false, ' a ', ''] } })
    expect(report[mapping[field]]).toEqual([' a ', '0', 'false', ' a ', ''])
    for (const invalid of [undefined, null, {}, 'texto', 0]) {
      expect(buildAuditReport({ resultado: { [field]: invalid } })[mapping[field]]).toEqual([])
    }
  })

  it('handles missing results and malformed finding collections', () => {
    for (const value of [undefined, null, 'texto', {}]) {
      expect(buildAuditReport({ resultado: { errores: value } }).findings).toEqual([])
    }
    expect(buildAuditReport({ resultado: { errores: [undefined, null] } }).findings).toEqual([])
    expect(buildAuditReport(null).originalText).toBe('')
    expect(buildAuditReport({ resultado: null }).risks).toEqual([])
  })

  it.each([
    [' alta ', 'ALTA'], ['MeDiA', 'MEDIA'], ['\tbaja\n', 'BAJA'], ['otra', null], [null, null], [undefined, null],
  ])('interprets severity %j while preserving its original text', (severity, expected) => {
    const report = buildAuditReport({ resultado: { errores: [{ severidad: severity }] } })
    expect(report.findings[0].severityLevel).toBe(expected)
    expect(report.findings[0].severity).toBe(severity ?? '')
  })

  it.each([
    [0, 'MUY_ALTO'], [39, 'MUY_ALTO'], [40, 'ALTO'], [64, 'ALTO'],
    [65, 'MODERADO'], [84, 'MODERADO'], [85, 'BAJO'], [100, 'BAJO'],
  ])('uses the existing score classification for %s', (value, level) => {
    expect(buildAuditReport({ puntuacionRiesgo: value }).score).toMatchObject({ value, level: { nivel: level } })
  })

  it.each([null, undefined, NaN, Infinity, -1, 101, '85', false])('does not invent a score or classification for %j', value => {
    expect(buildAuditReport({ puntuacionRiesgo: value, resultado: { puntuacionRiesgo: 85 } }).score)
      .toEqual({ value: null, level: null })
  })

  it.each(['javascript:alert(1)', 'data:text/html,test', 'ftp://example.invalid', '/relative', 'not a URL'])('rejects href %s but preserves text', value => {
    expect(buildAuditReport({ urlOpcional: value }).metadata.referenceUrl).toEqual({ text: value, href: null })
  })

  it.each(['http://example.invalid/legal', 'https://example.invalid/legal'])('keeps safe URL %s', value => {
    expect(buildAuditReport({ urlOpcional: value }).metadata.referenceUrl).toEqual({ text: value, href: value })
  })

  it('separates URL normalization from original text', () => {
    expect(buildAuditReport({ urlOpcional: ' HTTPS://Example.Invalid ' }).metadata.referenceUrl)
      .toEqual({ text: ' HTTPS://Example.Invalid ', href: 'https://example.invalid/' })
  })
})

describe('report presentation policy', () => {
  it('fixes the eight canonical section IDs and titles in order', () => {
    expect(REPORT_SECTIONS.map(({ id, title }) => [id, title])).toEqual([
      ['summary', 'Resumen'],
      ['risks', 'Incumplimientos detectados'],
      ['findings', 'Errores detectados'],
      ['recommendations', 'Recomendaciones'],
      ['suggestedTexts', 'Textos sugeridos'],
      ['unverifiableItems', 'Elementos no verificables'],
      ['legalReferences', 'Referencias legales'],
      ['originalText', 'Texto original auditado'],
    ])
  })

  it('uses approved fallbacks and no classification for an unavailable score', () => {
    const report = buildAuditReport({ ...auditoriaPuntuacionNull, tipoDocumento: null })
    expect(reportHeader(report)).toMatchObject({ score: 'No disponible', level: null,
      documentType: 'No especificado', date: 'Fecha no disponible' })
    expect(renderReportText(report)).toContain('Puntuación de Cumplimiento: No disponible\n')
    expect(renderReportText(report)).not.toContain('Clasificación:')
  })

  it('declares empty-list differences centrally and preserves original trailing whitespace', () => {
    const report = buildAuditReport(auditoriaLegado)
    const expected = ['summary', 'findings', 'originalText']
    expect(seccionesInforme(report, REPORT_POLICIES.screen).map(s => s.id)).toEqual(expected)
    expect(seccionesInforme(report, REPORT_POLICIES.pdf).map(s => s.id)).toEqual(expected)
    expect(seccionesInforme(report, REPORT_POLICIES.copy)).toHaveLength(8)
    const text = renderReportText(report)
    expect(text).toContain('=== RESUMEN ===\nSin resumen disponible.')
    expect(text).toContain('=== RECOMENDACIONES ===\nNinguna')
    expect(text).toContain('=== ELEMENTOS NO VERIFICABLES ===\nNinguno')
    expect(text).toContain('Clasificación: Cumplimiento Crítico')
    expect(text.endsWith('=== TEXTO ORIGINAL AUDITADO ===\n' + auditoriaLegado.textoOriginal)).toBe(true)
  })
})
