import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useParams } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import api from '../api/axios'
import { AuthProvider } from '../context/AuthContext'
import CrearAuditoriaConjunta, { MAX_DOCUMENTOS } from '../pages/CrearAuditoriaConjunta'
import DetalleAuditoriaConjunta from '../pages/DetalleAuditoriaConjunta'
import HistorialConjuntas from '../pages/HistorialConjuntas'

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
    expect(screen.getByText(/No se calcula una puntuación global/)).toBeVisible()
    const privacidad = screen.getByRole('article', { name: 'Privacidad' })
    expect(within(privacidad).getByText('62')).toBeVisible()
    expect(within(privacidad).getByText('1 incidencias')).toBeVisible()
    expect(within(privacidad).getByText(/Plazo ausente/)).toBeVisible()
    expect(within(privacidad).getByRole('link', { name: /Ver informe completo/ })).toHaveAttribute('href', '/auditoria/101')
    const aviso = screen.getByRole('article', { name: 'Aviso' })
    expect(within(aviso).getByText('15')).toBeVisible()
    expect(within(aviso).getByText('Sin incidencias detectadas')).toBeVisible()
    expect(screen.getByText('Indicar plazos de conservación')).toBeVisible()
    expect(screen.getByText('Aplica a: Privacidad, Aviso')).toBeVisible()
    const noAportados = screen.getByRole('region', { name: 'Documentos no aportados' })
    for (const categoria of informe.categoriasNoAportadas) {
      expect(within(noAportados).getByText(categoria)).toBeVisible()
    }
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
