import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { pdf } from '@react-pdf/renderer'
import api from '../api/axios'
import DetalleAuditoriaConjunta from '../pages/DetalleAuditoriaConjunta'
import AuditoriaConjuntaPdfDocument, { textoPdf } from '../pdf/AuditoriaConjuntaPdfDocument'
import { buildJointReport, jointPdfFileName } from '../report/jointReportPresentation'
import { EVIDENCE_STATES } from '../report/reportPresentation'
import { informePocosHallazgos, informeVariosDocumentos as informe, payloadHostil } from './fixtures/jointReportFixtures'

vi.mock('../api/axios', () => ({ default: { get: vi.fn(), post: vi.fn() } }))
// Real PDF document; only the binary rendering boundary is replaced here.
// jointPdf.test.jsx exercises the actual renderer and the produced bytes.
vi.mock('@react-pdf/renderer', async importOriginal => ({ ...await importOriginal(), pdf: vi.fn() }))

beforeEach(() => {
  api.get.mockResolvedValue({ data: informe })
  api.post.mockRejectedValue(new Error('Unexpected HTTP POST in test'))
  pdf.mockReset()
})

async function showReport(data = informe) {
  api.get.mockResolvedValue({ data })
  const view = render(<MemoryRouter initialEntries={['/auditoria-conjunta/5']} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
    <Routes><Route path="/auditoria-conjunta/:id" element={<DetalleAuditoriaConjunta />} /></Routes>
  </MemoryRouter>)
  await screen.findByRole('heading', { level: 1 })
  return view
}

// Resolve the PDF document and its local components down to primitive nodes.
function pdfTree(element) {
  if (element == null || typeof element === 'boolean') return []
  if (Array.isArray(element)) return element.flatMap(pdfTree)
  if (typeof element !== 'object') return [String(element)]
  if (typeof element.type === 'function') return pdfTree(element.type(element.props))
  return [{ props: element.props, children: pdfTree(element.props.children) }]
}
const nodes = (tree, attr) => tree.flatMap(n => typeof n === 'string' ? [] : [
  ...(n.props[attr] !== undefined ? [n] : []), ...nodes(n.children, attr)])
const textOf = tree => tree.map(n => typeof n === 'string' ? n : textOf(n.children)).join('')
const FIELDS = ['category', 'url', 'score', 'classification', 'engine', 'summary', 'risk']
const FINDING_FIELDS = ['findingTitle', 'severity', 'status', 'description', 'evidence', 'impact', 'action']
const COVERAGE_FIELDS = ['coverageCategory', 'coverageState', 'coverageDocument', 'coverageEvidence', 'coverageLink',
  'coverageLinkSources', 'coverageContradiction', 'coverageEmpty']

function pdfModel(tree) {
  const fieldsIn = (scope, name) => nodes(scope, 'data-joint-field').filter(n => n.props['data-joint-field'] === name).map(n => textOf(n.children))
  return {
    documents: nodes(tree, 'data-joint-document').map(doc => ({
      ...Object.fromEntries(FIELDS.map(f => [f, fieldsIn(doc.children, f)])),
      findings: nodes(doc.children, 'data-joint-finding').map(fi => Object.fromEntries(FINDING_FIELDS.map(f => [f, fieldsIn(fi.children, f)[0] ?? '']))),
    })),
    recommendations: nodes(tree, 'data-joint-recommendation').map(r => [fieldsIn(r.children, 'recommendation')[0], fieldsIn(r.children, 'appliesTo')[0]]),
    missing: fieldsIn(tree, 'missing'),
    coverage: nodes(tree, 'data-joint-coverage').map(row => [row.props['data-joint-coverage'],
      ...COVERAGE_FIELDS.map(f => fieldsIn(row.children, f))]),
    coverageNotes: fieldsIn(tree, 'coverageNote'),
    limitations: fieldsIn(tree, 'limitation'),
  }
}

function screenModel(container) {
  const fieldsIn = (scope, name) => [...scope.querySelectorAll(`[data-joint-field="${name}"]`)].map(e => textoPdf(e.textContent))
  return {
    documents: [...container.querySelectorAll('[data-joint-document]')].map(doc => ({
      ...Object.fromEntries(FIELDS.map(f => [f, fieldsIn(doc, f)])),
      findings: [...doc.querySelectorAll('[data-joint-finding]')].map(fi => Object.fromEntries(FINDING_FIELDS.map(f => [f, fieldsIn(fi, f)[0] ?? '']))),
    })),
    recommendations: [...container.querySelectorAll('[data-joint-recommendation]')].map(r => [fieldsIn(r, 'recommendation')[0], fieldsIn(r, 'appliesTo')[0]]),
    missing: fieldsIn(container, 'missing'),
    coverage: [...container.querySelectorAll('[data-joint-coverage]')].map(row => [row.dataset.jointCoverage,
      ...COVERAGE_FIELDS.map(f => fieldsIn(row, f))]),
    coverageNotes: fieldsIn(container, 'coverageNote'),
    limitations: fieldsIn(container, 'limitation'),
  }
}

describe('joint audit PDF download', () => {
  it('generates the PDF from the authorized download response, not from the page state', async () => {
    const servidor = { ...informe, titulo: 'Título devuelto por la descarga / v2' }
    api.post.mockResolvedValue({ data: servidor })
    const blob = new Blob(['%PDF-1.3'], { type: 'application/pdf' })
    pdf.mockReturnValue({ toBlob: vi.fn().mockResolvedValue(blob) })
    const createObjectURL = vi.fn().mockReturnValue('blob:joint')
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL, revokeObjectURL: vi.fn() }))
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    await showReport()

    await userEvent.setup().click(screen.getByRole('button', { name: 'Descargar PDF' }))

    await waitFor(() => expect(click).toHaveBeenCalledTimes(1))
    expect(api.post).toHaveBeenCalledExactlyOnceWith('/auditorias-conjuntas/5/descarga')
    const element = pdf.mock.calls[0][0]
    expect(element.type).toBe(AuditoriaConjuntaPdfDocument)
    expect(element.props.informe).toBe(servidor)
    expect(createObjectURL).toHaveBeenCalledWith(blob)
    expect(click.mock.instances[0].download).toBe(jointPdfFileName(servidor.titulo))
    expect(jointPdfFileName(servidor.titulo)).toBe('Auditoria conjunta - Título devuelto por la descarga _ v2.pdf')
    click.mockRestore()
  })

  it('reports a forbidden download without generating anything', async () => {
    api.post.mockRejectedValue({ response: { status: 403 } })
    await showReport()
    await userEvent.setup().click(screen.getByRole('button', { name: 'Descargar PDF' }))
    expect(await screen.findByText('No tienes permiso para descargar este informe.')).toBeVisible()
    expect(pdf).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Descargar PDF' })).toBeEnabled()
  })

  it('shows on screen and in the PDF the same data, which is the persisted DTO', async () => {
    const { container } = await showReport()
    const fromScreen = screenModel(container)
    const fromPdf = pdfModel(pdfTree(<AuditoriaConjuntaPdfDocument informe={informe} />))
    expect(fromPdf).toEqual(fromScreen)

    // And both carry exactly the persisted values: nothing added, nothing dropped.
    expect(fromPdf.documents).toHaveLength(informe.documentos.length)
    informe.documentos.forEach((d, i) => {
      const doc = fromPdf.documents[i]
      expect(doc.category).toEqual([textoPdf(d.tipoDocumento)])
      expect(doc.score).toEqual([d.puntuacionRiesgo === null ? 'No disponible' : `${d.puntuacionRiesgo}/100`])
      expect(doc.risk).toEqual((d.riesgos || []).map(textoPdf))
      expect(doc.findings.map(f => [f.findingTitle, f.severity])).toEqual(d.incidencias.map(x => [x.titulo, x.severidad]))
      expect(doc.findings.map(f => f.evidence)).toEqual(d.incidencias.map(x => textoPdf(x.evidencia) || 'No disponible'))
      expect(doc.findings.map(f => f.status)).toEqual(d.incidencias.map(x => EVIDENCE_STATES[x.estado] ?? ''))
    })
    expect(fromPdf.recommendations).toEqual(informe.recomendaciones.map(r => [r.texto, r.documentos.join(', ')]))
    expect(fromPdf.missing).toEqual(informe.categoriasNoAportadas)
    expect(fromPdf.limitations).toEqual(buildJointReport(informe).limitations)

    // The coverage matrix: the seven persisted rows, their states and every evidence, link and contradiction.
    expect(fromPdf.coverage.map(r => r[0])).toEqual(informe.cobertura.categorias.map(f => f.categoria))
    expect(fromPdf.coverage.map(r => r[1][0])).toEqual(informe.cobertura.categorias.map(f => textoPdf(f.etiqueta)))
    expect(fromPdf.coverage.map(r => r[2][0])).toEqual(['Documento analizado', 'Enlace detectado, no analizado', 'No verificado',
      'Documento analizado', 'No verificado', 'Documento analizado', 'Enlace detectado, no analizado'])
    const column = field => fromPdf.coverage.flatMap(r => r[1 + COVERAGE_FIELDS.indexOf(field)])
    expect(column('coverageDocument')).toEqual(['«Política de Privacidad»', '«Términos y Condiciones» · sin evidencias conservadas',
      '«Aviso Legal» (aportado como Aviso Legal)'])
    expect(column('coverageEvidence')).toContain('H1: Política de privacidad - Tienda Ñandú')
    expect(column('coverageLink')).toEqual(['https://www.ejemplo.es/cookies', '/accesibilidad (ruta relativa, sin URL de referencia)'])
    expect(column('coverageLinkSources')[0]).toBe('Enlazado desde «Política de Privacidad» (pie de página), «Aviso Legal» (navegación)')
    expect(column('coverageContradiction')).toEqual(['«Aviso Legal» se aportó como esta categoría, pero su contenido corresponde a: Condiciones de Compra'])
    expect(column('coverageEmpty')).toHaveLength(2)
    expect(fromPdf.coverageNotes).toHaveLength(2)
    expect(fromPdf.coverageNotes[0]).toContain('«Términos y Condiciones»')
    expect(fromPdf.coverageNotes[1]).toContain('«Aviso Legal»')
  })

  it('states on screen and in the PDF that a report without a matrix has none', async () => {
    const { container } = await showReport(informePocosHallazgos)
    const fromScreen = screenModel(container)
    const fromPdf = pdfModel(pdfTree(<AuditoriaConjuntaPdfDocument informe={informePocosHallazgos} />))
    expect(fromPdf).toEqual(fromScreen)
    expect(fromPdf.coverage).toEqual([])
    expect(fromPdf.coverageNotes).toEqual(['Este informe no incluye la matriz de cobertura documental.'])
  })

  it('renders hostile persisted text as text on screen and in the PDF tree', async () => {
    const execute = vi.fn()
    vi.stubGlobal('testXss', execute)
    const fila = informe.cobertura.categorias[1]
    const hostil = { ...informe, titulo: payloadHostil,
      documentos: [{ ...informe.documentos[1], resumen: payloadHostil }],
      cobertura: { ...informe.cobertura, categorias: [{ ...fila,
        enlaces: [{ url: 'https://www.ejemplo.es/' + payloadHostil, relativa: false,
          origenes: [{ ...fila.enlaces[0].origenes[0], titulo: payloadHostil }] }] }] } }
    const { container } = await showReport(hostil)
    expect(container.querySelector('img, script, [onerror]')).toBeNull()
    expect(screen.getByText(payloadHostil, { selector: '[data-joint-field="summary"]' })).toBeVisible()
    // Detected URLs are shown as text, never as something the browser could follow.
    const enlace = container.querySelector('[data-joint-field="coverageLink"]')
    expect(enlace.textContent).toBe('https://www.ejemplo.es/' + payloadHostil)
    expect(container.querySelector('[data-joint-coverage] a')).toBeNull()
    const tree = pdfTree(<AuditoriaConjuntaPdfDocument informe={hostil} />)
    expect(textOf(tree)).toContain(payloadHostil)
    expect(textOf(tree)).toContain('Enlazado desde «' + payloadHostil + '»')
    expect(execute).not.toHaveBeenCalled()
  })
})
