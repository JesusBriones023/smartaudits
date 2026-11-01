import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useParams } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import api from '../api/axios'
import { AuthProvider } from '../context/AuthContext'
import CrearAuditoriaConjunta, { MAX_DOCUMENTOS } from '../pages/CrearAuditoriaConjunta'
import DetalleAuditoriaConjunta from '../pages/DetalleAuditoriaConjunta'
import HistorialConjuntas from '../pages/HistorialConjuntas'
import { buildCoverage } from '../report/jointReportPresentation'
import { informeVariosDocumentos } from './fixtures/jointReportFixtures'

// HTTP boundary only: components and router stay real.
vi.mock('../api/axios', () => ({ default: { get: vi.fn(), post: vi.fn() } }))

const session = { userId: 7, nombre: 'Persona de prueba', email: 'test@example.invalid', role: 'CLIENTE' }

beforeEach(() => {
  api.get.mockRejectedValue(new Error('Unexpected HTTP GET in test'))
  api.post.mockRejectedValue(new Error('Unexpected HTTP POST in test'))
})

function renderAt(routes, path) {
  localStorage.setItem('user', JSON.stringify(session))
  localStorage.setItem('token', 'synthetic-test-token')
  return render(
    <AuthProvider>
      <MemoryRouter initialEntries={[path]} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <Routes>{routes}</Routes>
      </MemoryRouter>
    </AuthProvider>
  )
}

function Destination() {
  const { id } = useParams()
  return <h1>Conjunta recibida: {id}</h1>
}

function deferred() {
  let resolve
  const promise = new Promise(done => { resolve = done })
  return { promise, resolve }
}

describe('joint audit form', () => {
  function showForm() {
    renderAt(<>
      <Route path="/crear-auditoria-conjunta" element={<CrearAuditoriaConjunta />} />
      <Route path="/auditoria-conjunta/:id" element={<Destination />} />
    </>, '/crear-auditoria-conjunta')
  }

  async function fill(user, n, titulo, html) {
    await user.type(screen.getByLabelText(`Título del documento ${n} *`), titulo)
    await user.click(screen.getByLabelText(`HTML del documento ${n} *`))
    await user.paste(html)
  }

  it('adds up to four documents, removes one and sends the batch', async () => {
    const pending = deferred()
    api.post.mockReturnValue(pending.promise)
    showForm()
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('Título de la Auditoría Conjunta *'), 'Web de prueba')
    for (let i = 1; i < MAX_DOCUMENTOS; i++) {
      await user.click(screen.getByRole('button', { name: 'Añadir documento' }))
    }
    expect(screen.getAllByRole('group', { name: /^Documento \d$/ })).toHaveLength(4)
    expect(screen.queryByRole('button', { name: 'Añadir documento' })).not.toBeInTheDocument()
    // New documents get categories not used yet.
    expect(screen.getByLabelText('Categoría legal del documento 2 *')).toHaveValue('Aviso Legal')
    expect(screen.getByLabelText('Categoría legal del documento 4 *')).toHaveValue('Términos y Condiciones')
    await user.click(screen.getByRole('button', { name: 'Quitar documento 4' }))
    await user.click(screen.getByRole('button', { name: 'Quitar documento 3' }))
    await fill(user, 1, 'Privacidad', '<h1>Privacidad</h1><script>x()</script>')
    await fill(user, 2, 'Aviso', '<p>Titular</p>')
    await user.type(screen.getByLabelText('URL del documento 2 (opcional)'), 'https://example.invalid/aviso')
    await user.click(screen.getByRole('button', { name: 'Analizar 2 documentos' }))
    expect(screen.getByText('Analizando 2 documentos...')).toBeVisible()
    expect(api.post).toHaveBeenCalledExactlyOnceWith('/auditorias-conjuntas', {
      titulo: 'Web de prueba',
      documentos: [
        { titulo: 'Privacidad', tipoDocumento: 'Política de Privacidad', html: '<h1>Privacidad</h1><script>x()</script>', urlOpcional: null },
        { titulo: 'Aviso', tipoDocumento: 'Aviso Legal', html: '<p>Titular</p>', urlOpcional: 'https://example.invalid/aviso' },
      ],
    })
    await act(async () => pending.resolve({ data: { id: 5 } }))
    expect(await screen.findByRole('heading', { name: 'Conjunta recibida: 5' })).toBeVisible()
  })

  it('shows a batch error and keeps every document for editing', async () => {
    api.post.mockRejectedValue({ response: { status: 400, data: {
      message: 'Documento 2 («Vacío»): El HTML no contiene texto legible para auditar' } } })
    showForm()
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('Título de la Auditoría Conjunta *'), 'Lote')
    await user.click(screen.getByRole('button', { name: 'Añadir documento' }))
    await fill(user, 1, 'Privacidad', '<p>Texto</p>')
    await fill(user, 2, 'Vacío', '<script>x()</script>')
    await user.click(screen.getByRole('button', { name: 'Analizar 2 documentos' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Documento 2 («Vacío»)')
    expect(screen.getByLabelText('HTML del documento 2 *')).toHaveValue('<script>x()</script>')
    expect(screen.getByLabelText('Título del documento 1 *')).toHaveValue('Privacidad')
  })
})

describe('joint audit report', () => {
  const informe = {
    id: 5, titulo: 'Web de prueba', fechaCreacion: '2026-01-02T12:00:00', usuarioId: 7, documentosAnalizados: 2,
    documentos: [
      { auditoriaId: 101, titulo: 'Privacidad', tipoDocumento: 'Política de Privacidad', urlOpcional: null,
        puntuacionRiesgo: 62, resumen: 'Falta el plazo.', versionMotor: '2', versionReglas: '1',
        incidencias: [{ ruleId: 'R05', titulo: 'Plazo ausente', severidad: 'MEDIA' }] },
      { auditoriaId: 102, titulo: 'Aviso', tipoDocumento: 'Aviso Legal', urlOpcional: null,
        puntuacionRiesgo: 15, resumen: 'Correcto.', versionMotor: '2', versionReglas: '1', incidencias: [] },
    ],
    recomendaciones: [
      { texto: 'Indicar plazos de conservación', auditoriaIds: [101, 102], documentos: ['Privacidad', 'Aviso'] },
    ],
    categoriasNoAportadas: ['Política de Cookies', 'Términos y Condiciones', 'Condiciones de Uso'],
  }

  function showReport() {
    return renderAt(<>
      <Route path="/auditoria-conjunta/:id" element={<DetalleAuditoriaConjunta />} />
      <Route path="/historial-conjuntas" element={<h1>Lista conjunta</h1>} />
    </>, '/auditoria-conjunta/5')
  }

  it('shows each document with its own score, incidents, grouped recommendations and missing categories', async () => {
    api.get.mockResolvedValue({ data: informe })
    showReport()
    expect(await screen.findByRole('heading', { name: 'Web de prueba' })).toBeVisible()
    expect(api.get).toHaveBeenCalledExactlyOnceWith('/auditorias-conjuntas/5')
    expect(screen.getByText(/2 documentos analizados/)).toBeVisible()
    expect(screen.getAllByText(/No se calcula una puntuación global/)[0]).toBeVisible()
    const privacidad = screen.getByRole('article', { name: 'Privacidad' })
    expect(within(privacidad).getByText('62/100')).toBeVisible()
    expect(within(privacidad).getByText('Cumplimiento Bajo')).toBeVisible()
    expect(within(privacidad).getByText('1 hallazgos')).toBeVisible()
    expect(within(privacidad).getByText(/Plazo ausente/)).toBeVisible()
    expect(within(privacidad).getByRole('link', { name: /Ver informe completo/ })).toHaveAttribute('href', '/auditoria/101')
    const aviso = screen.getByRole('article', { name: 'Aviso' })
    expect(within(aviso).getByText('15/100')).toBeVisible()
    expect(within(aviso).getByText('Sin hallazgos detectados')).toBeVisible()
    expect(screen.getByText('Indicar plazos de conservación')).toBeVisible()
    expect(screen.getByText('Privacidad, Aviso', { selector: '[data-joint-field="appliesTo"]' })).toBeVisible()
    const noAportados = screen.getByRole('region', { name: 'Documentos no aportados' })
    for (const categoria of informe.categoriasNoAportadas) {
      expect(within(noAportados).getByText(categoria)).toBeVisible()
    }
  })

  it('shows the coverage matrix of the seven categories without claiming more than each state means', async () => {
    api.get.mockResolvedValue({ data: informeVariosDocumentos })
    showReport()
    const matriz = await screen.findByRole('region', { name: 'Cobertura documental' })
    const filas = within(matriz).getAllByRole('row').slice(1)
    expect(filas.map(f => within(f).getByRole('rowheader').textContent)).toEqual(['Política de Privacidad',
      'Política de Cookies', 'Aviso Legal', 'Términos y Condiciones', 'Condiciones de Uso', 'Condiciones de Compra', 'Accesibilidad'])
    expect(within(filas[0]).getByText('Documento analizado')).toBeVisible()
    expect(within(filas[0]).getByText('H1: Política de privacidad — Tienda Ñandú')).toBeVisible()
    // Links are only candidates: their content is not analysed and they are not clickable.
    expect(within(filas[1]).getByText('Enlace detectado, no analizado')).toBeVisible()
    expect(within(filas[1]).getByText('https://www.ejemplo.es/cookies')).toBeVisible()
    expect(within(filas[1]).queryByRole('link')).toBeNull()
    // Declared «Aviso Legal» but its content is purchase conditions: reported, not counted.
    expect(within(filas[2]).getByText('No verificado')).toBeVisible()
    expect(within(filas[2]).getByText(/se aportó como esta categoría, pero su contenido corresponde a: Condiciones de Compra/)).toBeVisible()
    expect(within(filas[3]).getByText('«Términos y Condiciones» · sin evidencias conservadas')).toBeVisible()
    expect(within(filas[4]).getByText('Ningún documento aportado pertenece a esta categoría ni enlaza a ella.')).toBeVisible()
    expect(within(filas[5]).getByText('«Aviso Legal» (aportado como Aviso Legal)')).toBeVisible()
    expect(within(filas[6]).getByText('/accesibilidad (ruta relativa, sin URL de referencia)')).toBeVisible()
    expect(within(matriz).getByText(/una categoría no verificada no demuestra que la web no la publique/)).toBeVisible()
    expect(within(matriz).getByText(/antes de que se conservaran evidencias.*«Términos y Condiciones»/)).toBeVisible()
    expect(within(matriz).getByText(/se recortó.*«Aviso Legal»/)).toBeVisible()
    expect(matriz.textContent).not.toMatch(/cumple|global|\d+\/100/i)
  })

  it('says so when a report has no coverage matrix', async () => {
    api.get.mockResolvedValue({ data: informe })
    showReport()
    const matriz = await screen.findByRole('region', { name: 'Cobertura documental' })
    expect(within(matriz).getByText('Este informe no incluye la matriz de cobertura documental.')).toBeVisible()
    expect(within(matriz).queryByRole('table')).toBeNull()
  })

  it('keeps unknown coverage values on the safe side', () => {
    const coverage = buildCoverage({ categorias: [{ categoria: 'POLITICA_PRIVACIDAD', etiqueta: 'Política de Privacidad',
      estado: 'CUMPLE', documentos: null, enlaces: [{ url: '/p', relativa: true, origenes: [{ titulo: 'A', contexto: 'otro' }] }],
      contradicciones: [{ titulo: 'B', identificadas: [] }] }] })
    expect(coverage.rows[0]).toMatchObject({ state: 'NO_VERIFICADO', stateLabel: 'No verificado', documents: [] })
    expect(coverage.rows[0].links[0].sources).toBe('Enlazado desde «A» (otro)')
    expect(coverage.rows[0].contradictions[0]).toMatch(/ninguna categoría reconocible$/)
    expect(coverage.notes).toEqual([])
    expect(buildCoverage(undefined)).toBeNull()
    expect(buildCoverage({ categorias: 'x' })).toBeNull()
  })

  it('renders hostile titles as text', async () => {
    const payload = '<img src=x onerror="window.testXss()">'
    const execute = vi.fn()
    vi.stubGlobal('testXss', execute)
    api.get.mockResolvedValue({ data: { ...informe, titulo: payload,
      documentos: [{ ...informe.documentos[0], titulo: payload, resumen: payload }] } })
    const { container } = showReport()
    expect(await screen.findByRole('heading', { name: payload, level: 1 })).toBeVisible()
    expect(container.querySelector('img, [onerror]')).toBeNull()
    expect(execute).not.toHaveBeenCalled()
  })

  it('shows a load error and allows going back', async () => {
    api.get.mockRejectedValue({ response: { status: 403 } })
    showReport()
    expect(await screen.findByRole('alert')).toHaveTextContent('No se pudo cargar la auditoría conjunta')
    await userEvent.setup().click(screen.getByRole('button', { name: 'Volver a las auditorías conjuntas' }))
    expect(await screen.findByRole('heading', { name: 'Lista conjunta' })).toBeVisible()
  })
})

describe('joint audit list', () => {
  it('lists the joint audits with a link to each report', async () => {
    api.get.mockResolvedValue({ data: { content: [
      { id: 5, titulo: 'Web de prueba', fechaCreacion: '2026-01-02T12:00:00', documentosAnalizados: 3 },
    ], totalElements: 1, totalPages: 1 } })
    renderAt(<Route path="/historial-conjuntas" element={<HistorialConjuntas />} />, '/historial-conjuntas')
    expect(await screen.findByRole('link', { name: 'Web de prueba' })).toHaveAttribute('href', '/auditoria-conjunta/5')
    expect(screen.getByText(/3 documentos/)).toBeVisible()
    expect(api.get).toHaveBeenCalledExactlyOnceWith('/auditorias-conjuntas/mias', { params: { page: 0, size: 50 } })
  })

  it('shows the empty state', async () => {
    api.get.mockResolvedValue({ data: { content: [], totalElements: 0, totalPages: 0 } })
    renderAt(<Route path="/historial-conjuntas" element={<HistorialConjuntas />} />, '/historial-conjuntas')
    expect(await screen.findByText('Todavía no hay auditorías conjuntas.')).toBeVisible()
  })
})
