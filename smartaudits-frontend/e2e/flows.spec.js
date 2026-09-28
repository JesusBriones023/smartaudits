import { test, expect } from '@playwright/test'

const runId = process.env.E2E_RUN_ID
const password = process.env.E2E_PASSWORD
const adminEmail = process.env.E2E_ADMIN_EMAIL
const legalText = 'Responsable: Empresa E2E. Contacto: privacidad@example.invalid. ' +
  'Tratamos los datos para gestionar el servicio con consentimiento expreso. ' +
  'Conservamos los datos durante 12 meses. Puede ejercer acceso, rectificación, ' +
  'supresión, oposición, limitación y portabilidad. Aplicamos cifrado y medidas de seguridad.'

test.beforeEach(async ({ context, baseURL }) => {
  // Real requests to the application only; never fulfill/mock an API response.
  await context.route('**/*', route => new URL(route.request().url()).origin === baseURL
    ? route.continue() : route.abort('blockedbyclient'))
})

async function register(page, suffix) {
  const email = `client-${suffix}-${runId}@example.invalid`
  await page.goto('/register')
  await page.getByLabel('Nombre completo').fill(`Cliente E2E ${suffix}`)
  await page.getByLabel('Email', { exact: true }).fill(email)
  await page.getByLabel('Contraseña', { exact: true }).fill(password)
  await page.getByLabel('Confirmar contraseña', { exact: true }).fill(password)
  const response = page.waitForResponse(r => r.url().endsWith('/api/auth/register') && r.request().method() === 'POST')
  await page.getByRole('button', { name: 'Crear Cuenta', exact: true }).click()
  expect((await response).ok()).toBe(true)
  await expect(page).toHaveURL(/\/dashboard$/)
  await expect.poll(() => page.evaluate(() => {
    const user = JSON.parse(localStorage.getItem('user'))
    return { role: user?.role, hasToken: !!localStorage.getItem('token') }
  })).toEqual({ role: 'CLIENTE', hasToken: true })
  return email
}

async function login(page, email) {
  await page.goto('/login')
  await page.getByLabel('Email', { exact: true }).fill(email)
  await page.getByLabel('Contraseña', { exact: true }).fill(password)
  const response = page.waitForResponse(r => r.url().endsWith('/api/auth/login') && r.request().method() === 'POST')
  await page.getByRole('button', { name: 'Iniciar sesión →', exact: true }).click()
  expect((await response).status()).toBe(200)
  await expect(page).toHaveURL(/\/dashboard$/)
}

async function expectSessionCleared(page) {
  await expect(page).toHaveURL(/\/login$/)
  await expect.poll(() => page.evaluate(() =>
    [localStorage.getItem('token'), localStorage.getItem('user')])).toEqual([null, null])
}

async function logout(page) {
  await page.getByRole('button', { name: 'Cerrar sesión', exact: true }).click()
  await expectSessionCleared(page)
}

test('registro, login explícito, creación y resultado persistido', async ({ page }) => {
  const email = await register(page, 'audit')
  await logout(page)
  await login(page, email)
  await page.getByRole('link', { name: 'Nueva Auditoría', exact: true }).click()
  const title = `Auditoría E2E ${runId}`
  await page.getByLabel('Título de la Auditoría *', { exact: true }).fill(title)
  await page.getByLabel('Tipo de Documento *', { exact: true }).selectOption('Política de Privacidad')
  await page.getByLabel('Texto Legal a Auditar *', { exact: true }).fill(legalText)
  const created = page.waitForResponse(r => r.url().endsWith('/api/auditorias') && r.request().method() === 'POST')
  await page.getByRole('button', { name: 'Analizar documento', exact: true }).click()
  const response = await created
  expect(response.ok()).toBe(true)
  const audit = await response.json()
  expect(audit.id).toBeGreaterThan(0)
  expect(audit.usuarioEmail).toBe(email)
  expect(audit.puntuacionRiesgo).toBeGreaterThanOrEqual(0)
  expect(audit.puntuacionRiesgo).toBeLessThanOrEqual(100)
  expect(audit.resultado.resumen).toBeTruthy()
  await expect(page).toHaveURL(new RegExp(`/auditoria/${audit.id}$`))
  // A fresh GET after reload also proves persistence through the real database.
  const loaded = page.waitForResponse(r => r.url().endsWith(`/api/auditorias/${audit.id}`) && r.request().method() === 'GET')
  await page.reload()
  expect((await loaded).status()).toBe(200)
  await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible()
  await expect(page.getByText(audit.resultado.resumen, { exact: true })).toBeVisible()
  await expect(page.getByText(String(audit.puntuacionRiesgo), { exact: true })).toBeVisible()
  await expect(page.getByText('Puntuación de Cumplimiento', { exact: true })).toBeVisible()
  await expect(page.getByText(legalText, { exact: true })).toBeVisible()
})

test('logout limpia la sesión y una ruta privada redirige al login', async ({ page }) => {
  await login(page, adminEmail)
  await logout(page)
  await page.goto('/crear-auditoria')
  await expectSessionCleared(page)
  await expect(page.getByRole('button', { name: 'Analizar documento', exact: true })).toHaveCount(0)
})

test('token inválido recibe 401 real y limpia la sesión', async ({ page }) => {
  await register(page, 'invalid')
  // Corrupt only the token: AuthContext still has a valid user until the API rejects it.
  await page.evaluate(() => localStorage.setItem('token', 'invalid.e2e.signature'))
  const rejected = page.waitForResponse(r => new URL(r.url()).pathname === '/api/auditorias/mias' && r.status() === 401)
  await page.getByRole('link', { name: 'Historial', exact: true }).click()
  expect((await rejected).status()).toBe(401)
  await expectSessionCleared(page)
})

test('ADMIN consulta usuarios; CLIENTE con rol UI manipulado recibe 403 real', async ({ page }) => {
  await login(page, adminEmail)
  const users = page.waitForResponse(r => r.url().endsWith('/api/usuarios') && r.request().method() === 'GET')
  await page.getByRole('link', { name: 'Gestión Usuarios', exact: true }).click()
  expect((await users).status()).toBe(200)
  await expect(page.getByRole('heading', { name: /Gestión de Usuarios/ })).toBeVisible()
  await expect(page.getByRole('row').filter({ hasText: adminEmail })).toBeVisible()
  await logout(page)

  await register(page, 'denied')
  await expect(page.getByRole('link', { name: 'Gestión Usuarios', exact: true })).toHaveCount(0)
  await page.evaluate(() => {
    const user = JSON.parse(localStorage.getItem('user'))
    localStorage.setItem('user', JSON.stringify({ ...user, role: 'ADMIN' }))
  })
  const denied = page.waitForResponse(r => r.url().endsWith('/api/usuarios') && r.request().method() === 'GET')
  await page.goto('/admin/usuarios')
  await expect(page.getByRole('link', { name: 'Gestión Usuarios', exact: true })).toBeVisible()
  expect((await denied).status()).toBe(403)
  await expect(page.getByText('No se pudo cargar la lista de usuarios.', { exact: true })).toBeVisible()
  await expect(page.getByRole('row').filter({ hasText: adminEmail })).toHaveCount(0)
  expect(await page.evaluate(() => !!localStorage.getItem('token'))).toBe(true)
})
