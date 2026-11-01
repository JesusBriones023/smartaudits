import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useParams } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import api from '../api/axios'
import { AuthProvider, useAuth } from '../context/AuthContext'
import PrivateRoute from '../components/PrivateRoute'
import Sidebar from '../components/Sidebar'
import Login from '../pages/Login'
import CrearAuditoria, { MAX_HTML_LENGTH } from '../pages/CrearAuditoria'
import DetalleAuditoria from '../pages/DetalleAuditoria'
import GestionUsuarios from '../pages/GestionUsuarios'

// HTTP boundary only: components, auth state and the router remain real.
// Interceptors are tested separately by axios.test.mjs with a real Axios adapter.
vi.mock('../api/axios', () => ({ default: { get: vi.fn(), post: vi.fn() } }))

const session = { userId: 7, nombre: 'Persona de prueba', email: 'test@example.invalid', role: 'CLIENTE' }

beforeEach(() => {
  api.get.mockRejectedValue(new Error('Unexpected HTTP GET in test'))
  api.post.mockRejectedValue(new Error('Unexpected HTTP POST in test'))
})

function renderRoutes(routes, path, user = null) {
  if (user) {
    localStorage.setItem('user', JSON.stringify(user))
    localStorage.setItem('token', 'synthetic-test-token')
  }
  return render(
    <AuthProvider>
      <MemoryRouter initialEntries={[path]} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <Routes>{routes}</Routes>
      </MemoryRouter>
    </AuthProvider>
  )
}

function DashboardSession() {
  const { user } = useAuth()
  return <h1>Panel de {user?.nombre}</h1>
}

function DetailDestination() {
  const { id } = useParams()
  return <h1>Detalle recibido: {id}</h1>
}

function deferred() {
  let resolve
  const promise = new Promise(done => { resolve = done })
  return { promise, resolve }
}

describe('Login with real AuthProvider', () => {
  function showLogin() {
    renderRoutes(<>
      <Route path="/login" element={<Login />} />
      <Route path="/dashboard" element={<PrivateRoute />}>
        <Route index element={<DashboardSession />} />
      </Route>
    </>, '/login')
  }

  async function submitLogin() {
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('Email'), session.email)
    await user.type(screen.getByLabelText('Contraseña'), 'synthetic-password')
    await user.click(screen.getByRole('button', { name: /Iniciar sesión/ }))
  }

  it('renders the form, sends credentials, stores the session and navigates with updated auth state', async () => {
    api.post.mockResolvedValue({ data: { ...session, token: 'synthetic-login-token' } })
    showLogin()
    expect(screen.getByRole('heading', { name: 'Bienvenido de vuelta' })).toBeVisible()
    await submitLogin()
    expect(api.post).toHaveBeenCalledExactlyOnceWith('/auth/login', {
      email: session.email, password: 'synthetic-password'
    })
    expect(await screen.findByRole('heading', { name: `Panel de ${session.nombre}` })).toBeVisible()
    expect(localStorage.getItem('token')).toBe('synthetic-login-token')
    expect(JSON.parse(localStorage.getItem('user'))).toEqual(session)
    expect(screen.queryByRole('button', { name: /Iniciar sesión/ })).not.toBeInTheDocument()
  })

  it('shows rejected credentials without creating a session and leaves the form usable', async () => {
    api.post.mockRejectedValue({ response: { status: 401, data: { message: 'Credenciales incorrectas' } } })
    showLogin()
    await submitLogin()
    expect(await screen.findByText('Credenciales incorrectas')).toBeVisible()
    expect(screen.getByRole('button', { name: /Iniciar sesión/ })).toBeEnabled()
    expect(localStorage.getItem('token')).toBeNull()
    expect(localStorage.getItem('user')).toBeNull()
    expect(screen.queryByRole('heading', { name: /Panel de/ })).not.toBeInTheDocument()
  })
})

describe('Private routes', () => {
  function showPrivate(user) {
    renderRoutes(<>
      <Route element={<PrivateRoute />}>
        <Route path="/privada" element={<h1>Contenido privado</h1>} />
      </Route>
      <Route path="/login" element={<Login />} />
    </>, '/privada', user)
  }

  it('redirects an anonymous user to login without showing private content', async () => {
    showPrivate(null)
    expect(await screen.findByRole('heading', { name: 'Bienvenido de vuelta' })).toBeVisible()
    expect(screen.queryByText('Contenido privado')).not.toBeInTheDocument()
  })

  it('restores an authenticated session and renders the protected outlet', async () => {
    showPrivate(session)
    expect(await screen.findByRole('heading', { name: 'Contenido privado' })).toBeVisible()
    expect(screen.queryByRole('button', { name: /Iniciar sesión/ })).not.toBeInTheDocument()
  })
})

describe('Audit creation', () => {
  function showCreation() {
    renderRoutes(<>
      <Route path="/crear-auditoria" element={<CrearAuditoria />} />
      <Route path="/auditoria/:id" element={<DetailDestination />} />
    </>, '/crear-auditoria', session)
  }

  async function fillForm(url = '') {
    const user = userEvent.setup()
    await user.type(screen.getByLabelText(/Título de la Auditoría/), 'Auditoría de prueba')
    await user.selectOptions(screen.getByLabelText(/Tipo de Documento/), 'Aviso Legal')
    await user.type(screen.getByLabelText(/Texto Legal a Auditar/), 'Contenido legal sintético para analizar.')
    if (url) await user.type(screen.getByLabelText(/URL del Sitio Web/), url)
    return user
  }

  it.each(['', 'https://example.invalid/legal'])('sends the form payload with optional URL %j and navigates to the returned ID', async url => {
    const pending = deferred()
    api.post.mockReturnValue(pending.promise)
    showCreation()
    expect(screen.getByRole('heading', { name: 'Nueva Auditoría Legal' })).toBeVisible()
    const user = await fillForm(url)
    await user.click(screen.getByRole('button', { name: 'Analizar documento' }))
    expect(screen.getByText('Analizando texto legal...')).toBeVisible()
    expect(api.post).toHaveBeenCalledExactlyOnceWith('/auditorias', {
      titulo: 'Auditoría de prueba', tipoDocumento: 'Aviso Legal',
      textoOriginal: 'Contenido legal sintético para analizar.', urlOpcional: url || null
    })
    await act(async () => pending.resolve({ data: { id: 42 } }))
    expect(await screen.findByRole('heading', { name: 'Detalle recibido: 42' })).toBeVisible()
  })

  it('shows the API error and preserves editable input for a retry', async () => {
    api.post.mockRejectedValue({ response: { status: 500, data: { message: 'Análisis no disponible' } } })
    showCreation()
    const user = await fillForm()
    await user.click(screen.getByRole('button', { name: 'Analizar documento' }))
    expect(await screen.findByText('Análisis no disponible')).toBeVisible()
    expect(screen.getByLabelText(/Título de la Auditoría/)).toHaveValue('Auditoría de prueba')
    expect(screen.getByLabelText(/Texto Legal a Auditar/)).toHaveValue('Contenido legal sintético para analizar.')
    expect(screen.getByRole('button', { name: 'Analizar documento' })).toBeEnabled()
  })

  it('keeps manual text as the default mode', () => {
    showCreation()
    expect(screen.getByRole('radio', { name: 'Texto' })).toBeChecked()
    expect(screen.getByLabelText(/Texto Legal a Auditar/)).toBeVisible()
    expect(screen.queryByLabelText(/HTML de la Página/)).not.toBeInTheDocument()
  })

  it('sends pasted HTML to the HTML endpoint without rendering it', async () => {
    const execute = vi.fn()
    vi.stubGlobal('testXss', execute)
    const html = '<h1>Aviso</h1><img src=x onerror="window.testXss()"><script>window.testXss()</script><p>Titular</p>'
    const pending = deferred()
    api.post.mockReturnValue(pending.promise)
    const { container } = renderRoutes(<>
      <Route path="/crear-auditoria" element={<CrearAuditoria />} />
      <Route path="/auditoria/:id" element={<DetailDestination />} />
    </>, '/crear-auditoria', session)
    const user = userEvent.setup()
    await user.click(screen.getByRole('radio', { name: 'HTML' }))
    expect(screen.queryByLabelText(/Texto Legal a Auditar/)).not.toBeInTheDocument()
    await user.type(screen.getByLabelText(/Título de la Auditoría/), 'Auditoría HTML')
    await user.selectOptions(screen.getByLabelText(/Tipo de Documento/), 'Aviso Legal')
    await user.type(screen.getByLabelText(/URL del Sitio Web/), 'https://example.invalid/aviso')
    const campo = screen.getByLabelText(/HTML de la Página/)
    expect(campo).toHaveAttribute('maxLength', String(MAX_HTML_LENGTH))
    await user.click(campo)
    await user.paste(html)
    expect(campo).toHaveValue(html)
    expect(container.querySelector('script, img, [onerror]')).toBeNull()
    await user.click(screen.getByRole('button', { name: 'Analizar documento' }))
    expect(screen.getByText('Extrayendo y analizando el texto del HTML...')).toBeVisible()
    expect(api.post).toHaveBeenCalledExactlyOnceWith('/auditorias/html', {
      titulo: 'Auditoría HTML', tipoDocumento: 'Aviso Legal', html,
      urlOpcional: 'https://example.invalid/aviso'
    })
    await act(async () => pending.resolve({ data: { id: 77 } }))
    expect(await screen.findByRole('heading', { name: 'Detalle recibido: 77' })).toBeVisible()
    expect(execute).not.toHaveBeenCalled()
  })

  it('shows the backend error for HTML without readable text and keeps the HTML for editing', async () => {
    api.post.mockRejectedValue({ response: { status: 400, data: { message: 'El HTML no contiene texto legible para auditar' } } })
    showCreation()
    const user = userEvent.setup()
    await user.click(screen.getByRole('radio', { name: 'HTML' }))
    await user.type(screen.getByLabelText(/Título de la Auditoría/), 'Vacía')
    await user.click(screen.getByLabelText(/HTML de la Página/))
    await user.paste('<script>x()</script>')
    await user.click(screen.getByRole('button', { name: 'Analizar documento' }))
    expect(await screen.findByText('El HTML no contiene texto legible para auditar')).toBeVisible()
    expect(screen.getByLabelText(/HTML de la Página/)).toHaveValue('<script>x()</script>')
  })

  describe('legal document detection', () => {
    const deteccion = {
      detectados: [
        { categoria: 'POLITICA_PRIVACIDAD', texto: 'Política de privacidad', url: 'https://www.ejemplo.es/privacidad',
          relativa: false, externo: false, contexto: 'footer', senal: 'texto' },
        { categoria: 'AVISO_LEGAL', texto: 'Aviso legal', url: 'https://legal.example.invalid/aviso',
          relativa: false, externo: true, contexto: 'footer', senal: 'texto' },
      ],
      noDetectados: ['POLITICA_COOKIES', 'TERMINOS_CONDICIONES', 'CONDICIONES_USO', 'CONDICIONES_COMPRA', 'DECLARACION_ACCESIBILIDAD'],
      enlacesAnalizados: 4, enlacesDescartados: 1, limiteAlcanzado: false, referenciaAplicada: true,
    }

    async function htmlMode(html) {
      const user = userEvent.setup()
      await user.click(screen.getByRole('radio', { name: 'HTML' }))
      await user.type(screen.getByLabelText(/Título de la Auditoría/), 'Auditoría HTML')
      await user.click(screen.getByLabelText(/URL del Sitio Web/))
      await user.paste('https://www.ejemplo.es/')
      await user.click(screen.getByLabelText(/HTML de la Página/))
      await user.paste(html)
      return user
    }

    it('shows detected and not detected categories as text, without creating an audit', async () => {
      const html = '<footer><a href="/privacidad">Política de privacidad</a><a href="javascript:x()">Cookies</a></footer>'
      api.post.mockResolvedValueOnce({ data: deteccion })
      showCreation()
      expect(screen.queryByRole('button', { name: 'Detectar documentos legales' })).not.toBeInTheDocument()
      const user = await htmlMode(html)
      await user.click(screen.getByRole('button', { name: 'Detectar documentos legales' }))
      const panel = await screen.findByRole('region', { name: 'Documentos legales enlazados en el HTML' })
      expect(api.post).toHaveBeenCalledExactlyOnceWith('/auditorias/html/enlaces-legales', {
        html, urlOpcional: 'https://www.ejemplo.es/'
      })
      const fila = clave => panel.querySelector(`[data-categoria="${clave}"]`)
      expect(fila('POLITICA_PRIVACIDAD')).toHaveTextContent('DETECTADO EN EL HTML')
      expect(fila('POLITICA_PRIVACIDAD')).toHaveTextContent('https://www.ejemplo.es/privacidad')
      expect(fila('POLITICA_PRIVACIDAD')).not.toHaveTextContent('Enlace externo')
      expect(fila('AVISO_LEGAL')).toHaveTextContent('Enlace externo')
      for (const clave of deteccion.noDetectados) {
        expect(fila(clave)).toHaveTextContent('NO DETECTADO EN EL HTML')
      }
      expect(panel).toHaveTextContent('No se han descargado ni verificado')
      expect(panel).toHaveTextContent('4 enlaces analizados · 1 descartados')
      // Candidate URLs are never rendered as navigable links.
      expect(panel.querySelector('a')).toBeNull()
      expect(screen.getByText('https://legal.example.invalid/aviso').tagName).toBe('CODE')
      expect(api.post).toHaveBeenCalledTimes(1)
    })

    it('keeps the audit flow working after detection and clears stale results when the HTML changes', async () => {
      const pending = deferred()
      api.post.mockResolvedValueOnce({ data: { ...deteccion, limiteAlcanzado: true } }).mockReturnValueOnce(pending.promise)
      showCreation()
      const user = await htmlMode('<a href="/privacidad">Privacidad</a>')
      await user.click(screen.getByRole('button', { name: 'Detectar documentos legales' }))
      expect(await screen.findByText(/Se alcanzó el límite/)).toBeVisible()
      await user.type(screen.getByLabelText(/HTML de la Página/), ' ')
      expect(screen.queryByRole('region', { name: 'Documentos legales enlazados en el HTML' })).not.toBeInTheDocument()
      await user.click(screen.getByRole('button', { name: 'Analizar documento' }))
      expect(api.post).toHaveBeenLastCalledWith('/auditorias/html', {
        titulo: 'Auditoría HTML', tipoDocumento: 'Política de Privacidad',
        html: '<a href="/privacidad">Privacidad</a> ', urlOpcional: 'https://www.ejemplo.es/'
      })
      await act(async () => pending.resolve({ data: { id: 88 } }))
      expect(await screen.findByRole('heading', { name: 'Detalle recibido: 88' })).toBeVisible()
    })

    it('shows a detection error without blocking the form', async () => {
      api.post.mockRejectedValueOnce({ response: { status: 400, data: { message: 'La URL debe ser una dirección HTTP o HTTPS válida' } } })
      showCreation()
      const user = await htmlMode('<a href="/privacidad">Privacidad</a>')
      await user.click(screen.getByRole('button', { name: 'Detectar documentos legales' }))
      expect(await screen.findByRole('alert')).toHaveTextContent('La URL debe ser una dirección HTTP o HTTPS válida')
      expect(screen.getByRole('button', { name: 'Detectar documentos legales' })).toBeEnabled()
      expect(screen.getByRole('button', { name: 'Analizar documento' })).toBeEnabled()
    })
  })
})

describe('Audit detail', () => {
  const audit = {
    id: 42, titulo: 'Informe de privacidad', tipoDocumento: 'Política de Privacidad',
    fechaCreacion: '2026-01-02T12:00:00', puntuacionRiesgo: 62,
    textoOriginal: 'Texto original de prueba', urlOpcional: null,
    resultado: {
      resumen: 'Falta concretar la conservación.', riesgos: ['Conservación excesiva'],
      errores: [{ titulo: 'Plazo ausente', severidad: 'MEDIA', descripcion: 'Falta un plazo',
        evidencia: 'Cláusula ausente', impacto: 'Conservación excesiva', accion: 'Indicar el plazo' }],
      recomendaciones: ['Definir la duración'], textosSugeridos: [], faltantes: [], referenciasLegales: []
    }
  }

  function showDetail() {
    return renderRoutes(<>
      <Route path="/auditoria/:id" element={<DetalleAuditoria />} />
      <Route path="/historial" element={<h1>Historial de prueba</h1>} />
    </>, '/auditoria/42', session)
  }

  it('shows loading, requests the route ID and renders essential analysis information', async () => {
    const pending = deferred()
    api.get.mockReturnValue(pending.promise)
    showDetail()
    expect(screen.getByText('Cargando auditoría...')).toBeVisible()
    expect(api.get).toHaveBeenCalledExactlyOnceWith('/auditorias/42')
    await act(async () => pending.resolve({ data: audit }))
    expect(await screen.findByRole('heading', { name: audit.titulo })).toBeVisible()
    expect(screen.getByText('62')).toBeVisible()
    expect(screen.getByText(audit.resultado.resumen)).toBeVisible()
    expect(screen.getByRole('heading', { name: 'Plazo ausente' })).toBeVisible()
    expect(screen.getByText('Definir la duración')).toBeVisible()
    expect(screen.getByText(audit.textoOriginal)).toBeVisible()
    expect(screen.queryByText('Cargando auditoría...')).not.toBeInTheDocument()
  })

  it('shows an API failure and allows returning to history', async () => {
    api.get.mockRejectedValue({ response: { status: 404, data: { message: 'Auditoría no encontrada' } } })
    showDetail()
    expect(await screen.findByText('Auditoría no encontrada')).toBeVisible()
    await userEvent.setup().click(screen.getByRole('button', { name: 'Volver al Historial' }))
    expect(await screen.findByRole('heading', { name: 'Historial de prueba' })).toBeVisible()
  })

  it('renders hostile user content as text and does not expose a javascript link', async () => {
    const payload = '<img src=x onerror="window.testXss()"><script>window.testXss()</script>'
    const execute = vi.fn()
    vi.stubGlobal('testXss', execute)
    api.get.mockResolvedValue({ data: {
      ...audit, titulo: payload, textoOriginal: payload, urlOpcional: 'javascript:window.testXss()',
      resultado: { ...audit.resultado, resumen: payload }
    } })
    const { container } = showDetail()
    expect(await screen.findByRole('heading', { name: payload })).toBeVisible()
    expect(screen.getAllByText(payload)).toHaveLength(3)
    // Absence of executable DOM is the regression check: jsdom alone cannot prove browser XSS safety.
    expect(container.querySelector('script, img, [onerror], [onclick], a[href^="javascript:"]')).toBeNull()
    expect(screen.getByText('URL de referencia no navegable')).toBeVisible()
    expect(execute).not.toHaveBeenCalled()
  })
})

describe('Admin UI visibility (not security authorization)', () => {
  it.each(['CLIENTE', 'ADMIN'])('shows administrative sidebar links only for ADMIN: %s', async role => {
    renderRoutes(<Route path="/dashboard" element={<Sidebar />} />, '/dashboard', { ...session, role })
    expect(await screen.findByText(session.nombre)).toBeVisible()
    expect(screen.getByRole('link', { name: 'Nueva Auditoría' })).toHaveAttribute('href', '/crear-auditoria')
    for (const [name, path] of [['Gestión Usuarios', '/admin/usuarios'], ['Historial Acciones', '/admin/historial']]) {
      if (role === 'ADMIN') expect(screen.getByRole('link', { name })).toHaveAttribute('href', path)
      else expect(screen.queryByRole('link', { name })).not.toBeInTheDocument()
    }
  })

  it('displays the backend denial when a client visits user management directly', async () => {
    api.get.mockRejectedValue({ response: { status: 403 } })
    renderRoutes(<Route element={<PrivateRoute />}>
      <Route path="/admin/usuarios" element={<GestionUsuarios />} />
    </Route>, '/admin/usuarios', session)
    expect(await screen.findByText('No se pudo cargar la lista de usuarios.')).toBeVisible()
    expect(api.get).toHaveBeenCalledExactlyOnceWith('/usuarios')
    expect(screen.queryByText('Cargando usuarios...')).not.toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: /Buscar/ })).toBeEnabled()
  })
})
