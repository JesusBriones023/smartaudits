import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import api from '../api/axios'
import DetalleAuditoria from '../pages/DetalleAuditoria'
import AuditoriaPdfDocument from '../pdf/AuditoriaPdfDocument'
import { buildAuditReport } from '../report/auditReport'
import { SCORE_DISCLAIMER, renderReportText, scoreExplanation } from '../report/reportPresentation'
import { auditoriaLegado } from './fixtures/auditReportFixtures'

vi.mock('../api/axios', () => ({ default: { get: vi.fn(), post: vi.fn() } }))

// A correct legal notice as rules v3 score it: 21/100 because 47 points do not apply to it.
const avisoLegal = { version: 'desglose-1', puntuacion: 21, pesoTotal: 100, puntosAcreditados: 21, penalizacionRiesgos: 0,
  pesoNoAplicable: 47, pesoNoVerificable: 0, pesoContextualEvaluado: 6, puntosContextuales: 6, pesoPalabrasClave: 47,
  puntosPalabrasClave: 15, porcentajeContextual: 100, motivoSinPorcentaje: null, controles: [] }
const LINEAS_AVISO = [
  'Puntos acreditados por el texto: 21 de 100.',
  'Controles evaluados por contexto, aplicables y verificables: 6 de 6 puntos.',
  'Controles evaluados por palabras clave, sin determinar si aplican: 15 de 47 puntos.',
  'Controles no aplicables a este tipo de documento: 47 puntos que no se suman.',
  'Evidencia en los controles evaluados por contexto: 100 %.',
  'No se calcula una puntuación ajustada: 47 puntos dependen de controles por palabras clave cuya aplicabilidad no se determina.',
]
const AVISO_NO_COMPARABLE = '47 de los 100 puntos no pueden obtenerse en este documento: la puntuación no es comparable con la de documentos de otro tipo.'

function pdfTree(element) {
  if (element == null || typeof element === 'boolean') return []
  if (Array.isArray(element)) return element.flatMap(pdfTree)
  if (typeof element !== 'object') return [String(element)]
  if (typeof element.type === 'function') return pdfTree(element.type(element.props))
  return [{ props: element.props, children: pdfTree(element.props.children) }]
}
const pdfNodes = (tree, value) => tree.flatMap(n => typeof n === 'string' ? [] : [
  ...(n.props['data-report-field'] === value ? [n] : []), ...pdfNodes(n.children, value)])
const pdfText = tree => tree.map(n => typeof n === 'string' ? n : pdfText(n.children)).join('')

describe('score explanation', () => {
  it('splits a misleadingly low score into what does and does not apply', () => {
    expect(scoreExplanation(avisoLegal, 21)).toEqual({ available: true, warning: AVISO_NO_COMPARABLE, lines: LINEAS_AVISO })
  })

  it('shows penalties, not verifiable weight and an unavailable share without inventing a denominator', () => {
    const terminos = { ...avisoLegal, puntuacion: 3, puntosAcreditados: 5, penalizacionRiesgos: 2, pesoNoAplicable: 40,
      pesoNoVerificable: 13, pesoContextualEvaluado: 0, puntosContextuales: 0, puntosPalabrasClave: 5, porcentajeContextual: null,
      motivoSinPorcentaje: 'Ningún control evaluado por contexto es aplicable y verificable en este documento.' }
    const e = scoreExplanation(terminos, 3)
    expect(e.lines).toContain('Puntos acreditados por el texto: 5 de 100; se restan 2 por riesgos detectados.')
    expect(e.lines).toContain('Controles no verificables con el texto: 13 puntos que no se suman.')
    expect(e.lines).toContain('Evidencia en los controles evaluados por contexto: no disponible. '
      + 'Ningún control evaluado por contexto es aplicable y verificable en este documento.')
    expect(e.lines.join(' ')).not.toMatch(/ 0 %|NaN|undefined/)
    expect(e.warning).toMatch(/^53 de los 100 puntos/)
  })

  it.each([
    ['historical', undefined, 40, 'Desglose no disponible: este análisis es anterior a esa información y no se reconstruye.'],
    ['unknown version', { ...avisoLegal, version: 'desglose-9' }, 21, 'Desglose no disponible: este análisis es anterior a esa información y no se reconstruye.'],
    ['score mismatch', avisoLegal, 22, 'Desglose no disponible: no coincide con la puntuación guardada.'],
    ['unavailable score', avisoLegal, null, 'Desglose no disponible: no coincide con la puntuación guardada.'],
  ])('reports the breakdown as unavailable: %s', (_, desglose, score, line) => {
    expect(scoreExplanation(desglose, score)).toEqual({ available: false, warning: null, lines: [line] })
  })

  it('shows the same disclaimer, warning and lines on screen, in the PDF and in the copied report', async () => {
    const audit = { ...auditoriaLegado, puntuacionRiesgo: 21, tipoDocumento: 'Aviso Legal',
      resultado: { ...auditoriaLegado.resultado, desglosePuntuacion: avisoLegal } }
    const report = buildAuditReport(audit)
    api.get.mockResolvedValue({ data: audit })
    const { container } = render(<MemoryRouter initialEntries={[`/auditoria/${audit.id}`]} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <Routes><Route path="/auditoria/:id" element={<DetalleAuditoria />} /></Routes>
    </MemoryRouter>)
    await screen.findByRole('heading', { name: audit.titulo })
    const onScreen = name => [...container.querySelectorAll(`[data-report-field="${name}"]`)].map(e => e.textContent)
    const tree = pdfTree(<AuditoriaPdfDocument report={report} />)
    const inPdf = name => pdfNodes(tree, name).map(n => pdfText([n]))
    const copied = renderReportText(report)

    expect(onScreen('scoreDisclaimer')).toEqual([SCORE_DISCLAIMER])
    expect(inPdf('scoreDisclaimer')).toEqual([SCORE_DISCLAIMER])
    expect(onScreen('scoreWarning')).toEqual([AVISO_NO_COMPARABLE])
    expect(inPdf('scoreWarning')).toEqual([AVISO_NO_COMPARABLE])
    expect(onScreen('scoreLine')).toEqual(LINEAS_AVISO)
    expect(inPdf('scoreLine')).toEqual(LINEAS_AVISO)
    for (const line of [SCORE_DISCLAIMER, AVISO_NO_COMPARABLE, ...LINEAS_AVISO.map(l => `- ${l}`)]) {
      expect(copied).toContain(line)
    }
    // The level describes the number, never compliance.
    expect(onScreen('level')).toEqual(['Puntuación muy baja'])
    expect(container.textContent).not.toMatch(/Cumplimiento (Alto|Medio|Bajo|Crítico)|Puntuación de Cumplimiento/)
  })

  it('keeps the disclaimer and an explicit unavailable breakdown for historical audits', async () => {
    const report = buildAuditReport(auditoriaLegado)
    expect(report.scoring.available).toBe(false)
    const copied = renderReportText(report)
    expect(copied).toContain(SCORE_DISCLAIMER)
    expect(copied).toContain('- Desglose no disponible: este análisis es anterior a esa información y no se reconstruye.')
    expect(copied).not.toContain('no es comparable')
  })
})
