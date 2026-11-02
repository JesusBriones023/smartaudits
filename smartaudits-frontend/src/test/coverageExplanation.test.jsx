import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import api from '../api/axios'
import PrivateRoute from '../components/PrivateRoute'
import { AuthProvider } from '../context/AuthContext'
import DetalleAuditoria from '../pages/DetalleAuditoria'
import HistorialAuditorias from '../pages/HistorialAuditorias'
import AuditoriaConjuntaPdfDocument from '../pdf/AuditoriaConjuntaPdfDocument'
import AuditoriaPdfDocument from '../pdf/AuditoriaPdfDocument'
import { buildAuditReport } from '../report/auditReport'
import { buildJointReport } from '../report/jointReportPresentation'
import { COVERAGE_TITLE, coverageExplanation, renderReportText, scoreExplanation } from '../report/reportPresentation'
import { auditoriaLegado } from './fixtures/auditReportFixtures'

vi.mock('../api/axios', () => ({ default: { get: vi.fn(), post: vi.fn(), patch: vi.fn() } }))

// The engine's own results (rules 6, output 4) for the cases of CoberturaEvaluacionTest in the backend.
const cobertura = overrides => ({ version: 'cobertura-1', pesoAplicable: 93, pesoEvaluado: 52, porcentajeCobertura: 56,
  coberturaMinima: 50, resultadoControlesEvaluados: 12, motivoSinResultado: null, suficientes: 1, insuficientes: 0,
  incidenciasRedaccion: 1, incidenciasAusencia: 6, controlesNoVerificables: ['R03', 'R09', 'R10', 'R12', 'R13', 'R14', 'R15',
    'R17', 'R18', 'R19'], noAplicables: 1, riesgosGlobales: 1, ...overrides })
const conRiesgo = cobertura()
const avisoCorrecto = cobertura({ pesoAplicable: 13, pesoEvaluado: 13, porcentajeCobertura: 100, resultadoControlesEvaluados: 100,
  suficientes: 2, incidenciasRedaccion: 0, incidenciasAusencia: 0, controlesNoVerificables: [], noAplicables: 17, riesgosGlobales: 0 })
const nadaAplicable = cobertura({ pesoAplicable: 0, pesoEvaluado: 0, porcentajeCobertura: null, resultadoControlesEvaluados: null,
  motivoSinResultado: 'Ningún control es aplicable a este tipo de documento.', suficientes: 0, incidenciasRedaccion: 0,
  incidenciasAusencia: 0, controlesNoVerificables: [], noAplicables: 19, riesgosGlobales: 0 })
const nadaVerificable = cobertura({ pesoAplicable: 100, pesoEvaluado: 0, porcentajeCobertura: 0, resultadoControlesEvaluados: null,
  motivoSinResultado: 'El texto no permite evaluar ninguno de los controles aplicables.', suficientes: 0, incidenciasRedaccion: 0,
  incidenciasAusencia: 0, controlesNoVerificables: Array.from({ length: 19 }, (_, i) => `R${String(i + 1).padStart(2, '0')}`),
  noAplicables: 0, riesgosGlobales: 0 })
const bajaPeroPerfecta = cobertura({ pesoAplicable: 100, pesoEvaluado: 47, porcentajeCobertura: 47, resultadoControlesEvaluados: null,
  motivoSinResultado: 'Se ha evaluado menos del 50 % del peso de los controles aplicables: el texto no permite valorar el documento en su conjunto.',
  suficientes: 7, incidenciasRedaccion: 0, incidenciasAusencia: 0, controlesNoVerificables: ['R08', 'R09', 'R10', 'R11', 'R12',
    'R13', 'R14', 'R15', 'R16', 'R17', 'R18', 'R19'], noAplicables: 0, riesgosGlobales: 0 })

const desgloseRiesgo = { version: 'desglose-1', puntuacion: 6, pesoTotal: 100, puntosAcreditados: 8, penalizacionRiesgos: 2,
  pesoNoAplicable: 7, pesoNoVerificable: 41, pesoContextualEvaluado: 52, puntosContextuales: 8, pesoPalabrasClave: 0,
  puntosPalabrasClave: 0, porcentajeContextual: 15, motivoSinPorcentaje: null, controles: [] }

function pdfTree(element) {
  if (element == null || typeof element === 'boolean') return []
  if (Array.isArray(element)) return element.flatMap(pdfTree)
  if (typeof element !== 'object') return [String(element)]
  if (typeof element.type === 'function') return pdfTree(element.type(element.props))
  return [{ props: element.props, children: pdfTree(element.props.children) }]
}
const pdfNodes = (tree, attr, value) => tree.flatMap(n => typeof n === 'string' ? [] : [
  ...(n.props[attr] === value ? [n] : []), ...pdfNodes(n.children, attr, value)])
const pdfText = tree => tree.map(n => typeof n === 'string' ? n : pdfText(n.children)).join('')

beforeEach(() => {
  api.get.mockReset()
  localStorage.clear()
})

describe('evaluation coverage', () => {
  it('separates coverage, the result over evaluated controls, their states and the global risks', () => {
    expect(coverageExplanation(conRiesgo, 6)).toEqual({
      available: true,
      warnings: ['10 controles aplicables están pendientes de verificación: el texto no permite saber si se cumplen y no se '
        + 'cuentan como incumplidos.'],
      lines: [
        'Cobertura: 56 % del peso de los controles aplicables (52 de 93 puntos evaluados).',
        'Resultado en los controles evaluados: 12 % (6 puntos sobre los 52 evaluados).',
        'Controles: 1 con evidencia suficiente, 0 con información insuficiente, 1 con redacción problemática, 6 sin la '
          + 'información que el documento debería dar, 10 pendientes de verificación y 1 no aplicables.',
        'Riesgos detectados en el texto: 1; restan 2 puntos cada uno, también en el resultado anterior.',
      ],
    })
  })

  it('never turns missing evidence into a good-looking result', () => {
    expect(coverageExplanation(nadaVerificable, 0).lines.slice(0, 2)).toEqual([
      'Cobertura: 0 % del peso de los controles aplicables (0 de 100 puntos evaluados).',
      'Resultado en los controles evaluados: no se calcula. El texto no permite evaluar ninguno de los controles aplicables.',
    ])
    expect(coverageExplanation(nadaVerificable, 0).warnings[0]).toMatch(/^19 controles aplicables están pendientes/)
    // Seven sufficient controls out of 47 % coverage: a «100 %» would mislead, so none is given.
    expect(coverageExplanation(bajaPeroPerfecta, 47).lines[1]).toBe('Resultado en los controles evaluados: no se calcula. '
      + 'Se ha evaluado menos del 50 % del peso de los controles aplicables: el texto no permite valorar el documento en su conjunto.')
    expect(coverageExplanation(nadaAplicable, 0).lines.slice(0, 2)).toEqual([
      'Cobertura: ningún control es aplicable a este tipo de documento.',
      'Resultado en los controles evaluados: no se calcula. Ningún control es aplicable a este tipo de documento.',
    ])
    expect(coverageExplanation(nadaAplicable, 0).warnings).toEqual([])
  })

  it('warns that a high result over few controls proves nothing about compliance', () => {
    const e = coverageExplanation(avisoCorrecto, 13)
    expect(e.lines[1]).toBe('Resultado en los controles evaluados: 100 % (13 puntos sobre los 13 evaluados).')
    expect(e.warnings).toEqual([
      'Solo se han evaluado 2 controles: el resultado describe únicamente esos controles.',
      'Un resultado alto se limita a lo que el texto permite evaluar y no demuestra el cumplimiento del documento.',
    ])
  })

  it('shows older results as unavailable and keeps their breakdown wording', () => {
    expect(coverageExplanation(undefined, 40)).toEqual({ available: false, warnings: [],
      lines: ['Cobertura no disponible: este análisis es anterior a esa información y no se reconstruye.'] })
    expect(coverageExplanation({ ...conRiesgo, version: 'cobertura-9' }, 6).available).toBe(false)
    expect(scoreExplanation(desgloseRiesgo, 6).lines).toContain('Evidencia en los controles evaluados por contexto: 15 %.')
    // With coverage, the breakdown does not repeat a second percentage.
    const conCobertura = scoreExplanation(desgloseRiesgo, 6, conRiesgo).lines
    expect(conCobertura.join(' ')).not.toMatch(/Evidencia en los controles|No se calcula una puntuación ajustada/)
    expect(conCobertura).toContain('La puntuación global cuenta como 0 los controles no aplicables y no verificables; lo que '
      + 'el texto permite evaluar se indica en la cobertura de la evaluación.')
  })

  it('shows the same coverage on screen, in the PDF and in the copied report', async () => {
    const audit = { ...auditoriaLegado, puntuacionRiesgo: 6, tipoDocumento: 'Política de Privacidad',
      resultado: { ...auditoriaLegado.resultado, desglosePuntuacion: desgloseRiesgo, coberturaEvaluacion: conRiesgo } }
    const report = buildAuditReport(audit)
    api.get.mockResolvedValue({ data: audit })
    const { container } = render(<MemoryRouter initialEntries={[`/auditoria/${audit.id}`]} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <Routes><Route path="/auditoria/:id" element={<DetalleAuditoria />} /></Routes>
    </MemoryRouter>)
    await screen.findByRole('heading', { name: audit.titulo })
    const expected = coverageExplanation(conRiesgo, 6)
    const onScreen = name => [...container.querySelectorAll(`[data-report-field="${name}"]`)].map(e => e.textContent)
    const tree = pdfTree(<AuditoriaPdfDocument report={report} />)
    const inPdf = name => pdfNodes(tree, 'data-report-field', name).map(n => pdfText([n]))
    expect(screen.getByRole('heading', { name: COVERAGE_TITLE })).toBeVisible()
    expect(onScreen('coverageLine')).toEqual(expected.lines)
    expect(inPdf('coverageLine')).toEqual(expected.lines)
    expect(onScreen('coverageWarning')).toEqual(expected.warnings)
    expect(inPdf('coverageWarning')).toEqual(expected.warnings)
    const copied = renderReportText(report)
    for (const line of [`${COVERAGE_TITLE}:`, ...expected.warnings, ...expected.lines.map(l => `- ${l}`)]) expect(copied).toContain(line)
  })

  it('gives each document of a joint audit its own coverage and no joint figure', () => {
    const informe = { id: 3, titulo: 'Web', fechaCreacion: '2026-11-02T10:00:00', documentosAnalizados: 2,
      recomendaciones: [], categoriasNoAportadas: [], documentos: [
        { auditoriaId: 1, titulo: 'Privacidad', tipoDocumento: 'Política de Privacidad', puntuacionRiesgo: 6, resumen: 'r',
          riesgos: [], incidencias: [], versionMotor: '4', versionReglas: '6', desglosePuntuacion: desgloseRiesgo,
          coberturaEvaluacion: conRiesgo },
        { auditoriaId: 2, titulo: 'Aviso', tipoDocumento: 'Aviso Legal', puntuacionRiesgo: 13, resumen: 'r', riesgos: [],
          incidencias: [], versionMotor: '3', versionReglas: '5', desglosePuntuacion: null },
      ] }
    const joint = buildJointReport(informe)
    expect(joint.documents.map(d => d.evaluationCoverage.lines[0])).toEqual([
      'Cobertura: 56 % del peso de los controles aplicables (52 de 93 puntos evaluados).',
      'Cobertura no disponible: este análisis es anterior a esa información y no se reconstruye.',
    ])
    // joint.coverage is the existing document-category matrix: no joint score or evaluation percentage exists.
    expect(joint).not.toHaveProperty('score')
    expect(JSON.stringify(joint.coverage ?? null)).not.toContain('% del peso')
    const tree = pdfTree(<AuditoriaConjuntaPdfDocument informe={informe} />)
    expect(pdfText(tree)).toContain('Cobertura: 56 % del peso de los controles aplicables (52 de 93 puntos evaluados).')
    expect(pdfNodes(tree, 'data-joint-field', 'coverageLine')).toHaveLength(joint.documents.reduce((n, d) => n + d.evaluationCoverage.lines.length, 0))
  })

  it('labels the history figure as a score, with its coverage when stored', async () => {
    localStorage.setItem('user', JSON.stringify({ userId: 7, nombre: 'Cliente', email: 'c@example.invalid', role: 'CLIENTE' }))
    localStorage.setItem('token', 'synthetic-coverage-test-token')
    api.get.mockResolvedValue({ data: { totalElements: 2, totalPages: 1, content: [
      { id: 1, titulo: 'Nueva', tipoDocumento: 'Política de Privacidad', fechaCreacion: '2026-11-02T10:00:00', puntuacionRiesgo: 6,
        porcentajeCoberturaEvaluacion: 56 },
      { id: 2, titulo: 'Antigua', tipoDocumento: 'Aviso Legal', fechaCreacion: '2025-01-02T10:00:00', puntuacionRiesgo: 21 },
    ] } })
    const { container } = render(<AuthProvider>
      <MemoryRouter initialEntries={['/historial']} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <Routes><Route element={<PrivateRoute />}><Route path="/historial" element={<HistorialAuditorias />} /></Route></Routes>
      </MemoryRouter>
    </AuthProvider>)
    await screen.findByText('Nueva')
    expect([...container.querySelectorAll('[data-history-field="coverage"]')].map(e => e.textContent.trim())).toEqual(['Cobertura 56 %'])
    expect(container.textContent).not.toContain('Cumplimiento')
    expect(screen.getAllByText('Puntuación')).toHaveLength(2)
  })
})
