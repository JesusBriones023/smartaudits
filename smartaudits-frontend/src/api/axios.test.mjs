// @vitest-environment node
import { test, vi } from 'vitest'
import assert from 'node:assert/strict'
import api from './axios.js'

for (const status of [401, 403, 409]) {
  test(`HTTP ${status}: only invalid authentication clears the session`, async () => {
    const storage = new Map([['token', 'synthetic-test-token'], ['user', 'synthetic-user']])
    vi.stubGlobal('localStorage', {
      getItem: (key) => storage.get(key),
      removeItem: (key) => storage.delete(key)
    })
    vi.stubGlobal('window', { location: { href: '/perfil' } })
    const failure = { response: { status } }
    // Exercise the real Axios interceptors; the adapter prevents all network access.
    await assert.rejects(api.get('/usuarios', {
      adapter: (config) => {
        assert.equal(config.headers.Authorization, 'Bearer synthetic-test-token')
        return Promise.reject(failure)
      }
    }), (error) => error === failure)
    assert.equal(storage.has('token'), status !== 401)
    assert.equal(storage.has('user'), status !== 401)
    assert.equal(window.location.href, status === 401 ? '/login' : '/perfil')
  })
}
