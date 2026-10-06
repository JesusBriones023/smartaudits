import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import api from '../api/axios'
import { AuthProvider } from '../context/AuthContext'
import PrivateRoute from '../components/PrivateRoute'
import Dashboard from '../pages/Dashboard'
import HistorialAuditorias from '../pages/HistorialAuditorias'
import GestionUsuarios from '../pages/GestionUsuarios'

// Keep the provider and consumers real; only replace the HTTP boundary.
// Literal expectations deliberately pin the external ADMIN/CLIENTE contract.
vi.mock('../api/axios', () => ({ default: { get: vi.fn(), patch: vi.fn() } }))

const session = { userId: 7, nombre: 'Cuenta actual', email: 'actor@example.invalid', role: 'ADMIN' }
const accounts = [
  { id: 7, nombre: 'Cuenta actual', email: session.email, role: 'ADMIN', activo: true, protegido: true },
  { id: 8, nombre: 'Admin secundario', email: 'admin@example.invalid', role: 'ADMIN', activo: true, protegido: false },
  { id: 9, nombre: 'Cliente activo', email: 'client@example.invalid', role: 'CLIENTE', activo: true, protegido: false },
  { id: 10, nombre: 'Cliente inactivo', email: 'inactive@example.invalid', role: 'CLIENTE', activo: false, protegido: false },
  { id: 11, nombre: 'Admin protegido', email: 'protected@example.invalid', role: 'ADMIN', activo: true, protegido: true }
]

beforeEach(() => {
  api.get.mockRejectedValue(new Error('Unexpected HTTP GET in test'))
  api.patch.mockRejectedValue(new Error('Unexpected HTTP PATCH in test'))
})

function show(element, role = 'ADMIN') {
  localStorage.setItem('user', JSON.stringify({ ...session, role }))
  localStorage.setItem('token', 'synthetic-role-test-token')
  return render(
    <AuthProvider>
      <MemoryRouter initialEntries={['/prueba']} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <Routes>
          <Route element={<PrivateRoute />}>
            <Route path="/prueba" element={element} />
          </Route>
          <Route path="/login" element={<h1>Sesión rechazada</h1>} />
        </Routes>
      </MemoryRouter>
    </AuthProvider>
  )
}

describe('role identifiers through AuthContext and its consumers', () => {
  it.each(['ADMIN', 'CLIENTE'])('preserves Dashboard visibility and text for %s', async role => {
    show(<Dashboard />, role)
    expect(await screen.findByRole('heading', { name: /Hola, Cuenta actual/ })).toBeVisible()
    expect(screen.getByRole('heading', { name: 'Nueva Auditoría' })).toBeVisible()
    expect(screen.getByRole('heading', {
      name: role === 'ADMIN' ? 'Todas las Auditorías' : 'Mi Historial'
    })).toBeVisible()
    if (role === 'ADMIN') expect(screen.getByText('Administrador')).toBeVisible()
    else expect(screen.queryByText('Administrador')).not.toBeInTheDocument()
  })

  it.each(['USER', 'ROLE_USER', 'ROLE_ADMIN', 'admin', null])('still rejects an unsupported session role %s', async role => {
    show(<Dashboard />, role)
    expect(await screen.findByRole('heading', { name: 'Sesión rechazada' })).toBeVisible()
    expect(localStorage.getItem('user')).toBeNull()
    expect(localStorage.getItem('token')).toBeNull()
  })

  it.each(['ADMIN', 'CLIENTE'])('preserves the history endpoint and filters for %s', async role => {
    api.get.mockResolvedValue({ data: { content: [], totalElements: 0, totalPages: 0 } })
    show(<HistorialAuditorias />, role)
    await screen.findByRole('heading', {
      name: role === 'ADMIN' ? /Todas las Auditorías \(Vista Administrador\)/ : 'Mis Auditorías'
    })
    const endpoint = role === 'ADMIN' ? '/auditorias' : '/auditorias/mias'
    await waitFor(() => expect(api.get).toHaveBeenCalledExactlyOnceWith(endpoint, {
      params: { page: 0, size: 10 }, signal: expect.any(AbortSignal)
    }))
    if (role === 'CLIENTE') {
      expect(screen.queryByRole('textbox', { name: /Filtrar por usuario/ })).not.toBeInTheDocument()
      expect(screen.queryByText(/Modo Administrador/)).not.toBeInTheDocument()
      return
    }

    expect(screen.getByText(/Modo Administrador/)).toBeVisible()
    const user = userEvent.setup()
    await user.type(screen.getByRole('textbox', { name: /Filtrar por usuario/ }), '  Ana  ')
    await waitFor(() => expect(api.get).toHaveBeenLastCalledWith('/auditorias', {
      params: { page: 0, size: 10, usuario: 'Ana' }, signal: expect.any(AbortSignal)
    }))
    await user.click(screen.getByRole('button', { name: 'Limpiar' }))
    await waitFor(() => expect(api.get).toHaveBeenLastCalledWith('/auditorias', {
      params: { page: 0, size: 10 }, signal: expect.any(AbortSignal)
    }))
  })
})

describe('user management role presentation and API contract', () => {
  async function showAccounts() {
    api.get.mockResolvedValue({ data: accounts })
    show(<GestionUsuarios />)
    await screen.findByRole('table')
  }

  it('preserves badges, counts and actions for active, inactive, protected and own accounts', async () => {
    await showAccounts()
    const admin = screen.getByRole('row', { name: /Admin secundario/ })
    const client = screen.getByRole('row', { name: /Cliente activo/ })
    expect(within(admin).getByText('👑 ADMIN')).toHaveClass('bg-purple-100', 'text-purple-800')
    expect(within(client).getByText('CLIENTE')).toHaveClass('bg-slate-100', 'text-slate-700')
    expect(within(admin).getByRole('button', { name: /Degradar/ })).toBeVisible()
    expect(within(admin).queryByRole('button', { name: /Promover/ })).not.toBeInTheDocument()
    expect(within(client).getByRole('button', { name: /Promover/ })).toBeVisible()
    expect(within(client).queryByRole('button', { name: /Degradar/ })).not.toBeInTheDocument()
    expect(within(screen.getByRole('row', { name: /Cliente inactivo/ })).getAllByRole('button')).toHaveLength(1)
    expect(within(screen.getByRole('row', { name: /Cliente inactivo/ })).getByRole('button', { name: /Reactivar/ })).toBeVisible()
    for (const name of [/Cuenta actual/, /Admin protegido/]) {
      expect(within(screen.getByRole('row', { name })).queryByRole('button')).not.toBeInTheDocument()
    }
    expect(screen.getByText('Administradores').parentElement).toHaveTextContent('Administradores3')
    expect(screen.getByText('Clientes').parentElement).toHaveTextContent('Clientes2')
  })

  it.each([
    [9, 'Cliente activo', 'ADMIN', /Promover/, 'promovido a Administrador'],
    [8, 'Admin secundario', 'CLIENTE', /Degradar/, 'degradado a Cliente']
  ])('sends unchanged role payload for user %s', async (id, name, destination, action, message) => {
    await showAccounts()
    api.patch.mockResolvedValue({ data: {} })
    api.get.mockResolvedValue({ data: accounts.map(account => account.id === id ? { ...account, role: destination } : account) })
    const user = userEvent.setup()
    await user.click(within(screen.getByRole('row', { name: new RegExp(name) })).getByRole('button', { name: action }))
    expect(screen.getByRole('heading', { name: destination === 'ADMIN' ? 'Promover a Administrador' : 'Degradar a Cliente' })).toBeVisible()
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))
    await waitFor(() => expect(api.patch).toHaveBeenCalledExactlyOnceWith(`/usuarios/${id}/rol`, { nuevoRol: destination }))
    expect(await screen.findByText(`✅ ${name} ha sido ${message}.`)).toBeVisible()
    expect(within(screen.getByRole('row', { name: new RegExp(name) })).getByText(
      destination === 'ADMIN' ? '👑 ADMIN' : 'CLIENTE'
    )).toBeVisible()
  })

  it('keeps text and active/inactive filters combined without changing the request', async () => {
    await showAccounts()
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: /Solo desactivados/ }))
    expect(screen.getAllByRole('row')).toHaveLength(2)
    expect(screen.getByRole('row', { name: /Cliente inactivo/ })).toBeVisible()
    await user.type(screen.getByRole('textbox', { name: /Buscar/ }), 'INACTIVE@')
    expect(screen.getAllByRole('row')).toHaveLength(2)
    await user.click(screen.getByRole('button', { name: /Solo activos/ }))
    expect(screen.getByText('No se encontraron usuarios con los filtros aplicados.')).toBeVisible()
    await user.click(screen.getByRole('button', { name: 'Limpiar' }))
    expect(screen.getAllByRole('row')).toHaveLength(5)
    await user.click(screen.getByRole('button', { name: /Todos/ }))
    expect(screen.getAllByRole('row')).toHaveLength(6)
    expect(api.get).toHaveBeenCalledExactlyOnceWith('/usuarios')
  })
})
