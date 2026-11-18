import { useState } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { BrowserRouter, Link, Route, Routes, useLocation, useNavigate } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import api from '../api/axios'
import App from '../App'

// Client-side routing boundaries under the real BrowserRouter and the jsdom History API.
// These are unit checks of the router contract the application relies on (GHSA-wrjc-x8rr-h8h6),
// not a browser-security guarantee: jsdom does not perform real cross-document navigations.
// The application has no SSR, so GHSA-337j-9hxr-rhxg (SSR hydration) is not exercised here.
vi.mock('../api/axios', () => ({
  default: { get: vi.fn(), post: vi.fn(), patch: vi.fn(), put: vi.fn(), delete: vi.fn() }
}))

const ORIGIN = window.location.origin
const here = () => window.location.href.slice(ORIGIN.length)

// Every way of writing a protocol-relative destination with slashes and backslashes.
const PROTOCOL_RELATIVE = ['//evil.example', '/\\evil.example', '\\\\evil.example', '\\/evil.example', '/\\/evil.example']

function signIn(role) {
  localStorage.setItem('user', JSON.stringify({ userId: 7, nombre: 'Cuenta de prueba', email: 'router@example.invalid', role }))
  localStorage.setItem('token', 'synthetic-router-test-token')
}

function startApp(path, role) {
  if (role) signIn(role)
  window.history.replaceState(null, '', path)
  return render(<App />)
}

/** Records each popstate's URL; next() resolves on the following one or rejects after a bounded wait. */
function recordPopstates(timeoutMs = 2000) {
  const events = []
  const waiters = new Set()
  const listener = () => {
    events.push({ path: here() })
    for (const wake of waiters) wake()
  }
  window.addEventListener('popstate', listener)
  return {
    next() {
      const index = events.length
      return new Promise((resolve, reject) => {
        const wake = () => {
          if (events.length <= index) return
          cleanup()
          resolve(events[index])
        }
        const timer = setTimeout(() => {
          cleanup()
          reject(new Error(`No history traversal (popstate) within ${timeoutMs} ms`))
        }, timeoutMs)
        const cleanup = () => { clearTimeout(timer); waiters.delete(wake) }
        waiters.add(wake)
      })
    },
    stop() {
      window.removeEventListener('popstate', listener)
      waiters.clear()
    }
  }
}

const loginHeading = () => screen.findByRole('heading', { name: 'Bienvenido de vuelta' })
const dashboardHeading = () => screen.findByRole('heading', { name: /Hola, Cuenta de prueba/ })

beforeEach(() => {
  for (const method of ['get', 'post', 'patch', 'put', 'delete']) {
    api[method].mockRejectedValue(new Error(`Unexpected HTTP ${method} in test`))
  }
})

afterEach(() => {
  window.history.replaceState(null, '', '/')
})

describe('the real application under BrowserRouter', () => {
  it('replaces an anonymous visit to a private route with /login in the same origin', async () => {
    window.history.replaceState(null, '', '/historial')
    const entries = window.history.length
    render(<App />)
    await loginHeading()
    expect(window.location.origin).toBe(ORIGIN)
    expect(here()).toBe('/login')
    expect(window.history.length).toBe(entries)
    expect(screen.queryByText('Mis Auditorías')).not.toBeInTheDocument()
    expect(api.get).not.toHaveBeenCalled()
  })

  it.each([
    '/%5C%5Cevil.example', '/%5Cevil.example', '/%2F%2Fevil.example', '/%2F%5Cevil.example',
    '/.%2F%2Fevil.example', '/x/..%2F..%2F%2Fevil.example', '/login%5C..%5Cdashboard'
  ])('keeps the unknown encoded path %s inside the application', async path => {
    const anonymous = startApp(path)
    await loginHeading()
    expect(window.location.origin).toBe(ORIGIN)
    expect(here()).toBe('/login')
    anonymous.unmount()

    startApp(path, 'CLIENTE')
    await dashboardHeading()
    expect(window.location.origin).toBe(ORIGIN)
    expect(here()).toBe('/dashboard')
  })

  it.each(['ADMIN', 'CLIENTE'])('renders only same-origin internal links in the private layout for %s', async role => {
    startApp('/dashboard', role)
    await dashboardHeading()
    const internal = [...document.querySelectorAll('a[href]')].filter(a => a.getAttribute('href').startsWith('/'))
    expect(internal.length).toBeGreaterThan(5)
    for (const link of internal) {
      const href = link.getAttribute('href')
      expect(href, href).toMatch(/^\/(?![/\\])/)
      expect(href, href).not.toContain('\\')
      expect(new URL(link.href).origin, href).toBe(ORIGIN)
    }
    const adminLinks = internal.filter(a => a.getAttribute('href').startsWith('/admin/'))
    expect(adminLinks.length > 0).toBe(role === 'ADMIN')
  })

  it('follows browser Back and Forward between internal routes', async () => {
    startApp('/dashboard', 'ADMIN')
    await dashboardHeading()
    await userEvent.click(document.querySelector('a[href="/crear-auditoria"]'))
    expect(await screen.findByRole('heading', { name: 'Nueva Auditoría Legal' })).toBeVisible()
    expect(here()).toBe('/crear-auditoria')

    window.history.back()
    await waitFor(() => expect(here()).toBe('/dashboard'))
    await dashboardHeading()

    window.history.forward()
    await waitFor(() => expect(here()).toBe('/crear-auditoria'))
    expect(await screen.findByRole('heading', { name: 'Nueva Auditoría Legal' })).toBeVisible()
  })

  it('does not show private content again through Back after logout', async () => {
    // Registered before the app mounts, so it runs ahead of the router's own popstate
    // listener and records the entry the traversal really reached, before any redirect.
    const popstates = recordPopstates()
    try {
      startApp('/dashboard', 'CLIENTE')
      await dashboardHeading()
      await userEvent.click(document.querySelector('a[href="/crear-auditoria"]'))
      await screen.findByRole('heading', { name: 'Nueva Auditoría Legal' })
      await userEvent.click(screen.getByRole('button', { name: /Cerrar sesión/ }))
      await loginHeading()
      expect(here()).toBe('/login')
      expect(localStorage.getItem('token')).toBeNull()
      const entries = window.history.length

      // Back really traverses to the protected entry pushed before logout...
      const back = popstates.next()
      window.history.back()
      expect((await back).path).toBe('/crear-auditoria')
      // ...and the guard then replaces it with /login, without rendering it.
      await waitFor(() => expect(here()).toBe('/login'))
      await loginHeading()
      expect(screen.queryByRole('heading', { name: 'Nueva Auditoría Legal' })).not.toBeInTheDocument()
      expect(window.history.length).toBe(entries)
      expect([localStorage.getItem('token'), localStorage.getItem('user')]).toEqual([null, null])

      // Proof the guard replaced that entry: Forward reaches the logout entry, and Back
      // now lands on /login where /crear-auditoria used to be.
      const forward = popstates.next()
      window.history.forward()
      expect((await forward).path).toBe('/login')
      const backAgain = popstates.next()
      window.history.back()
      expect((await backAgain).path).toBe('/login')
      await loginHeading()
      expect(screen.queryByRole('heading', { name: 'Nueva Auditoría Legal' })).not.toBeInTheDocument()
      expect(localStorage.getItem('token')).toBeNull()
    } finally {
      popstates.stop()
    }
  })

  it.each([
    ['/auditoria/%5C%5Cevil.example', '\\\\evil.example'],
    ['/auditoria/%2F%2Fevil.example', '//evil.example'],
    ['/auditoria/..%2Fadmin%2Fusuarios', '../admin/usuarios']
  ])('keeps the route parameter of %s inside the detail route', async (path, decoded) => {
    startApp(path, 'ADMIN')
    await waitFor(() => expect(api.get).toHaveBeenCalledTimes(1))
    // The parameter reaches the page decoded, as in React Router 6. The detail route does not
    // re-route on it; the API path it produces is authorized by the backend, never by the router.
    expect(api.get).toHaveBeenCalledWith(`/auditorias/${decoded}`)
    expect(window.location.origin).toBe(ORIGIN)
    expect(here()).toBe(path)
    expect(screen.queryByRole('heading', { name: /Gestión de Usuarios/ })).not.toBeInTheDocument()
  })
})

function Probe({ target }) {
  const navigate = useNavigate()
  const location = useLocation()
  const [refusal, setRefusal] = useState('')
  const go = () => {
    try {
      navigate(target)
    } catch (error) {
      setRefusal(error.message)
    }
  }
  return (
    <>
      <output aria-label="router location">{location.pathname + location.search + location.hash}</output>
      <p data-testid="refusal">{refusal}</p>
      <button type="button" onClick={go}>navigate</button>
      <Link to={target}>link</Link>
    </>
  )
}

function renderProbe(target) {
  window.history.replaceState(null, '', '/inicio')
  render(
    <BrowserRouter>
      <Routes>
        <Route path="*" element={<Probe target={target} />} />
      </Routes>
    </BrowserRouter>
  )
  return () => screen.getByRole('status', { name: 'router location' }).textContent
}

/** Clicks and reports whether the router handled the click itself; the document never navigates. */
async function clickRecordingRouterHandling(element) {
  let handledByRouter
  const listener = event => {
    handledByRouter = event.defaultPrevented
    event.preventDefault()
  }
  window.addEventListener('click', listener)
  try {
    await userEvent.click(element)
  } finally {
    window.removeEventListener('click', listener)
  }
  return handledByRouter
}

describe('router navigation APIs with protocol-relative destinations', () => {
  it.each(PROTOCOL_RELATIVE)('useNavigate(%s) is refused and moves nowhere', async target => {
    const routerLocation = renderProbe(target)
    const entries = window.history.length
    await userEvent.click(screen.getByRole('button', { name: 'navigate' }))
    expect(screen.getByTestId('refusal')).toHaveTextContent('External navigation is not allowed')
    expect(routerLocation()).toBe('/inicio')
    expect(here()).toBe('/inicio')
    expect(window.history.length).toBe(entries)
  })

  it('useNavigate keeps an internal path that contains a backslash after its first segment in the origin', async () => {
    const routerLocation = renderProbe('/auditoria/\\evil.example')
    await userEvent.click(screen.getByRole('button', { name: 'navigate' }))
    await waitFor(() => expect(routerLocation()).not.toBe('/inicio'))
    expect(screen.getByTestId('refusal')).toHaveTextContent('')
    expect(window.location.origin).toBe(ORIGIN)
    expect(window.location.pathname).toMatch(/^\/auditoria\//)
  })

  it.each(PROTOCOL_RELATIVE)('<Link to="%s"> is never routed client-side as an internal path', async target => {
    const routerLocation = renderProbe(target)
    const link = screen.getByRole('link', { name: 'link' })
    // The rendered link points where a browser would really go: another origin.
    expect(new URL(link.href).origin).not.toBe(ORIGIN)
    const handledByRouter = await clickRecordingRouterHandling(link)
    expect(handledByRouter).toBe(false)
    expect(routerLocation()).toBe('/inicio')
    expect(here()).toBe('/inicio')
  })

  it('still routes an internal destination client-side with its parameter, query and hash', async () => {
    const routerLocation = renderProbe('/auditoria/42?vista=resumen#hallazgos')
    const handledByRouter = await clickRecordingRouterHandling(screen.getByRole('link', { name: 'link' }))
    expect(handledByRouter).toBe(true)
    await waitFor(() => expect(routerLocation()).toBe('/auditoria/42?vista=resumen#hallazgos'))
    expect(here()).toBe('/auditoria/42?vista=resumen#hallazgos')
    // A push, not a replace: Back returns to the previous entry.
    window.history.back()
    await waitFor(() => expect(routerLocation()).toBe('/inicio'))
  })
})
