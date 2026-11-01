import { readFile } from 'node:fs/promises'
import { inflateSync } from 'node:zlib'
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

test('HTML pegado: texto extraído, auditoría persistida e informe sin ejecutar el HTML', async ({ page, baseURL }) => {
  const external = []
  page.on('request', request => {
    if (new URL(request.url()).origin !== baseURL) external.push(request.url())
  })
  const html = `<!doctype html><html><head><title>Privacidad</title><style>p{color:red}</style>
    <script>window.__smartauditsXss = 'script'</script></head><body>
    <nav><a href="https://evil.example.invalid/">Menú</a></nav>
    <h1>Política de Privacidad</h1>
    <p>Responsable: Empresa E2E HTML. Contacto: privacidad@example.invalid.</p>
    <p onclick="window.__smartauditsXss = 'click'">Tratamos los datos para gestionar el servicio con consentimiento expreso.
       Conservamos los datos durante 12 meses.</p>
    <ul><li>Acceso, rectificación y supresión</li><li>Oposición, limitación y portabilidad</li></ul>
    <img src="https://evil.example.invalid/pixel.png" onerror="window.__smartauditsXss = 'img'">
    <p>Aplicamos cifrado y medidas de seguridad.</p>
    <footer><a href="/legal/privacidad">Política de privacidad</a>
    <a href="https://cookies.example.invalid/politica">Cookies</a>
    <a href="javascript:window.__smartauditsXss = 'link'">Aviso legal</a></footer></body></html>`
  const extracted = [
    'Política de Privacidad',
    '',
    'Responsable: Empresa E2E HTML. Contacto: privacidad@example.invalid.',
    '',
    'Tratamos los datos para gestionar el servicio con consentimiento expreso. Conservamos los datos durante 12 meses.',
    '',
    '- Acceso, rectificación y supresión',
    '- Oposición, limitación y portabilidad',
    '',
    'Aplicamos cifrado y medidas de seguridad.',
    '',
    'Política de privacidad Cookies Aviso legal',
  ].join('\n')

  // The temporary ADMIN avoids a fourth registration (3 per IP and 15 minutes).
  await login(page, adminEmail)
  await page.goto('/crear-auditoria')
  const title = `Auditoría HTML E2E ${runId}`
  await page.getByLabel('Título de la Auditoría *', { exact: true }).fill(title)
  await page.getByLabel('Tipo de Documento *', { exact: true }).selectOption('Política de Privacidad')
  await page.getByLabel('URL del Sitio Web (opcional)', { exact: true }).fill('https://evil.example.invalid/privacidad')
  await page.getByRole('radio', { name: 'HTML', exact: true }).check()
  await page.getByLabel('HTML de la Página *', { exact: true }).fill(html)

  // Legal link discovery: static analysis of the pasted HTML, nothing is fetched.
  const detected = page.waitForResponse(r => r.url().endsWith('/api/auditorias/html/enlaces-legales'))
  await page.getByRole('button', { name: 'Detectar documentos legales', exact: true }).click()
  expect((await detected).status()).toBe(200)
  const panel = page.getByRole('region', { name: 'Documentos legales enlazados en el HTML' })
  const row = key => panel.locator(`[data-categoria="${key}"]`)
  await expect(row('POLITICA_PRIVACIDAD')).toContainText('DETECTADO EN EL HTML')
  await expect(row('POLITICA_PRIVACIDAD')).toContainText('https://evil.example.invalid/legal/privacidad')
  await expect(row('POLITICA_COOKIES')).toContainText('https://cookies.example.invalid/politica')
  await expect(row('POLITICA_COOKIES')).toContainText('Enlace externo')
  await expect(row('AVISO_LEGAL')).toContainText('NO DETECTADO EN EL HTML')
  await expect(panel).toContainText('No se han descargado ni verificado')
  expect(await panel.locator('a').count()).toBe(0)
  const created = page.waitForResponse(r => r.url().endsWith('/api/auditorias/html') && r.request().method() === 'POST')
  await page.getByRole('button', { name: 'Analizar documento', exact: true }).click()
  const response = await created
  expect(response.status()).toBe(201)
  const audit = await response.json()
  expect(audit.textoOriginal).toBe(extracted)
  expect(audit.tipoFuente).toBe('MANUAL')
  expect(audit.urlOpcional).toBe('https://evil.example.invalid/privacidad')
  expect(audit.resultado.resumen).toBeTruthy()
  await expect(page).toHaveURL(new RegExp(`/auditoria/${audit.id}$`))

  const loaded = page.waitForResponse(r => r.url().endsWith(`/api/auditorias/${audit.id}`) && r.request().method() === 'GET')
  await page.reload()
  const persisted = await (await loaded).json()
  expect(persisted.textoOriginal).toBe(extracted)
  expect(persisted.resultado).toEqual(audit.resultado)
  await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible()
  await expect(page.getByText(audit.resultado.resumen, { exact: true })).toBeVisible()
  await expect(page.getByText(String(audit.puntuacionRiesgo), { exact: true })).toBeVisible()
  await expect(page.getByText(/Responsable: Empresa E2E HTML/)).toBeVisible()
  expect(await page.evaluate(() => window.__smartauditsXss)).toBeUndefined()
  expect(await page.locator('script:not([type="module"]), img[onerror], [onclick]').count()).toBe(0)
  // Nothing from the pasted HTML or the reference URL is ever requested. The app's own
  // font stylesheet (index.html) is the only pre-existing outside request, and it is blocked.
  expect(external.filter(url => new URL(url).hostname.endsWith('example.invalid'))).toEqual([])
  expect(external.every(url => new URL(url).hostname === 'fonts.googleapis.com')).toBe(true)
})

test('auditoría conjunta de tres HTML: informe persistente y recuperable desde el historial', async ({ page, baseURL }) => {
  const external = []
  page.on('request', request => {
    if (new URL(request.url()).origin !== baseURL) external.push(request.url())
  })
  const docs = [
    ['Privacidad E2E', '<h1>Política de Privacidad</h1><script>window.__smartauditsXss = 1</script>' +
      '<p>Responsable: Empresa E2E. Contacto: privacidad@example.invalid. Conservamos los datos 12 meses.</p>'],
    ['Aviso E2E', '<h1>Aviso Legal</h1><p>Titular: Empresa E2E S.L., CIF B12345678, domicilio en Madrid.</p>'],
    ['Cookies E2E', '<h1>Política de Cookies</h1><p>Usamos cookies técnicas y, con consentimiento, analíticas.</p>' +
      '<img src="https://evil.example.invalid/p.png" onerror="window.__smartauditsXss = 2">'],
  ]
  // The temporary ADMIN avoids another registration (3 per IP and 15 minutes).
  await login(page, adminEmail)
  await page.goto('/crear-auditoria-conjunta')
  const title = `Auditoría conjunta E2E ${runId}`
  await page.getByLabel('Título de la Auditoría Conjunta *', { exact: true }).fill(title)
  for (let i = 0; i < docs.length; i++) {
    if (i > 0) await page.getByRole('button', { name: 'Añadir documento', exact: true }).click()
    await page.getByLabel(`Título del documento ${i + 1} *`, { exact: true }).fill(docs[i][0])
    await page.getByLabel(`HTML del documento ${i + 1} *`, { exact: true }).fill(docs[i][1])
  }
  await page.getByLabel('URL del documento 1 (opcional)', { exact: true }).fill('https://evil.example.invalid/privacidad')
  await expect(page.getByLabel('Categoría legal del documento 3 *', { exact: true })).toHaveValue('Política de Cookies')
  const created = page.waitForResponse(r => r.url().endsWith('/api/auditorias-conjuntas') && r.request().method() === 'POST')
  await page.getByRole('button', { name: 'Analizar 3 documentos', exact: true }).click()
  const response = await created
  expect(response.status()).toBe(201)
  const report = await response.json()
  expect(report.documentosAnalizados).toBe(3)
  expect(report.documentos.map(d => d.tipoDocumento)).toEqual(['Política de Privacidad', 'Aviso Legal', 'Política de Cookies'])
  expect(report.categoriasNoAportadas).toEqual(['Términos y Condiciones', 'Condiciones de Uso'])
  expect(report).not.toHaveProperty('puntuacionGlobal')
  await expect(page).toHaveURL(new RegExp(`/auditoria-conjunta/${report.id}$`))

  const loaded = page.waitForResponse(r => r.url().endsWith(`/api/auditorias-conjuntas/${report.id}`) && r.request().method() === 'GET')
  await page.reload()
  expect(await (await loaded).json()).toEqual(report)
  await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible()
  for (const documento of report.documentos) {
    const card = page.getByRole('article', { name: documento.titulo, exact: true })
    await expect(card).toContainText(String(documento.puntuacionRiesgo))
    await expect(card).toContainText(documento.tipoDocumento)
  }
  await expect(page.getByText('No se calcula una puntuación global', { exact: false }).first()).toBeVisible()
  await expect(page.getByRole('region', { name: 'Documentos no aportados' })).toContainText('Condiciones de Uso')

  // Real PDF download, generated from the authorized /descarga response.
  const pdfRequest = page.waitForResponse(r => r.url().endsWith(`/api/auditorias-conjuntas/${report.id}/descarga`) && r.request().method() === 'POST')
  const downloadEvent = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Descargar PDF', exact: true }).click()
  expect((await pdfRequest).status()).toBe(200)
  const download = await downloadEvent
  expect(download.suggestedFilename()).toBe(`Auditoria conjunta - ${title}.pdf`)
  const bytes = await readFile(await download.path())
  expect(bytes.subarray(0, 5).toString()).toBe('%PDF-')
  expect(bytes.toString('latin1').trimEnd().endsWith('%%EOF')).toBe(true)
  expect(bytes.toString('latin1')).toMatch(/\/MediaBox \[0 0 595\.2\d* 841\.8\d*\]/)
  const decoder = new TextDecoder('windows-1252')
  const pdfText = [...bytes.toString('latin1').matchAll(/stream\r?\n([\s\S]*?)\r?\nendstream/g)].flatMap(m => {
    try {
      return [...inflateSync(Buffer.from(m[1], 'latin1')).toString('latin1').matchAll(/\[([^\]]*)\]\s*TJ/g)]
        .map(op => [...op[1].matchAll(/<([0-9a-fA-F]*)>/g)].map(x => decoder.decode(Buffer.from(x[1], 'hex'))).join(''))
    } catch { return [] }
  }).join('\n')
  for (const fragment of [title, 'Puntuación de Cumplimiento', 'Privacidad E2E', 'Aviso E2E', 'Cookies E2E',
    'Documentos no aportados', 'Limitaciones del análisis', String(report.documentos[0].puntuacionRiesgo) + '/100']) {
    expect(pdfText, fragment).toContain(fragment)
  }
  expect(pdfText).not.toContain('evil.example.invalid/p.png')

  // Recover it from the history after leaving the page.
  await page.goto('/historial')
  await page.getByRole('link', { name: 'Ver auditorías conjuntas (varios documentos)', exact: true }).click()
  await page.getByRole('link', { name: title, exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`/auditoria-conjunta/${report.id}$`))
  await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible()
  // Each document is an individual audit with its own full report.
  await page.getByRole('link', { name: 'Ver informe completo de «Aviso E2E»', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`/auditoria/${report.documentos[1].auditoriaId}$`))
  await expect(page.getByRole('heading', { name: 'Aviso E2E', exact: true })).toBeVisible()

  expect(await page.evaluate(() => window.__smartauditsXss)).toBeUndefined()
  expect(external.filter(url => new URL(url).hostname.endsWith('example.invalid'))).toEqual([])
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
