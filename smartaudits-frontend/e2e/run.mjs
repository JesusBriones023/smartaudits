import { spawn, execFileSync } from 'node:child_process'
import { randomBytes, randomUUID } from 'node:crypto'
import { mkdir, stat, writeFile } from 'node:fs/promises'
import { createServer, createConnection } from 'node:net'
import { dirname, resolve, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { setTimeout as delay } from 'node:timers/promises'

const frontend = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const repo = resolve(frontend, '..')
const runId = randomUUID().replaceAll('-', '')
const label = 'smartaudits.e2e'
const container = `sa14_db_${runId}`
const volume = `sa14_data_${runId}`
const database = 'sa14_e2e'
const runtime = join(frontend, '.e2e-runtime', runId)
const children = []
const ports = []
let containerRegistered = false
let volumeRegistered = false
let habitualBefore
let envBefore
let cleanupPromise
let stopping = false
let successful = false

// Do not inherit DB/JWT/SPRING/VITE/JAVA_TOOL_OPTIONS or load any .env.
// Only OS/tool discovery variables are passed to child processes.
const allowed = new Set(['path', 'systemroot', 'windir', 'comspec', 'pathext',
  'temp', 'tmp', 'tmpdir', 'home', 'userprofile', 'localappdata', 'appdata',
  'programdata', 'programfiles', 'programfiles(x86)', 'java_home', 'maven_home', 'm2_home'])
const baseEnv = Object.fromEntries(Object.entries(process.env).filter(([key]) => allowed.has(key.toLowerCase())))
const password = randomBytes(24).toString('hex')
const dbPassword = randomBytes(24).toString('hex')
const rootPassword = randomBytes(24).toString('hex')
const jwt = randomBytes(48).toString('base64')
const redact = text => [password, dbPassword, rootPassword, jwt].reduce((s, secret) => s.replaceAll(secret, '[REDACTED]'), text)

function docker(args, extraEnv = {}) {
  try {
    return execFileSync('docker', args, { env: { ...baseEnv, ...extraEnv },
      windowsHide: true, encoding: 'utf8', timeout: 30_000, stdio: ['ignore', 'pipe', 'pipe'] }).trim()
  } catch {
    // Do not echo command arguments, container environment or credentials.
    throw new Error(`Docker ${args[0]} failed. Check Docker availability and the E2E resource labels.`)
  }
}

function habitualState() {
  // Metadata only: never exec, stop, restart, or connect to the habitual DB.
  const names = docker(['ps', '-a', '--filter', 'name=^/smartaudits_mariadb$', '--format', '{{.Names}}'])
  if (!names) return 'ABSENT'
  return docker(['inspect', '--format',
    '{{.Id}}|{{.State.Status}}|{{.State.StartedAt}}|{{.State.FinishedAt}}|{{.RestartCount}}', 'smartaudits_mariadb'])
}

async function envMetadata() {
  return Promise.all([repo, frontend, join(repo, 'smartaudits-backend')].map(async dir => {
    try {
      const info = await stat(join(dir, '.env'))
      return [info.size, info.mtimeMs, info.ctimeMs, info.ino]
    } catch (error) { if (error.code === 'ENOENT') return null; throw error }
  }))
}

async function freePort() {
  const server = createServer()
  await new Promise((ok, fail) => { server.once('error', fail); server.listen(0, '127.0.0.1', ok) })
  const port = server.address().port
  await new Promise(ok => server.close(ok))
  if (ports.includes(port)) return freePort()
  ports.push(port)
  return port
}

function listening(port) {
  return new Promise(ok => {
    const socket = createConnection({ host: '127.0.0.1', port })
    const finish = result => { socket.destroy(); ok(result) }
    socket.setTimeout(500, () => finish(false))
    socket.once('connect', () => finish(true))
    socket.once('error', () => finish(false))
  })
}

function start(name, command, args, cwd, env = {}) {
  if (stopping) throw new Error('E2E interrupted.')
  const child = spawn(command, args, { cwd, env: { ...baseEnv, ...env },
    windowsHide: true, detached: process.platform !== 'win32', stdio: ['ignore', 'pipe', 'pipe'] })
  const record = { name, child, log: '', error: null }
  children.push(record)
  const capture = data => { record.log = (record.log + data.toString()).slice(-1_000_000) }
  child.stdout.on('data', capture)
  child.stderr.on('data', capture)
  record.done = new Promise(ok => {
    child.once('error', error => { record.error = error; ok(-1) })
    child.once('close', code => ok(code ?? -1))
  })
  return record
}

async function waitUntil(description, predicate, timeout = 90_000, processRecord) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    if (stopping) throw new Error('E2E interrupted.')
    if (processRecord && (processRecord.error || processRecord.child.exitCode !== null)) {
      throw new Error(`${processRecord.name} stopped before readiness.`)
    }
    if (await predicate()) return
    // Bounded readiness polling, not a fixed startup sleep or a browser test delay.
    await delay(250)
  }
  throw new Error(`Timed out waiting for ${description}.`)
}

function sql(query) {
  return docker(['exec', '-e', 'MYSQL_PWD', container, 'mariadb', '--host=127.0.0.1', '-uroot', '-N', '-B', database, '-e', query],
    { MYSQL_PWD: rootPassword })
}

async function cleanup() {
  if (cleanupPromise) return cleanupPromise
  stopping = true
  cleanupPromise = (async () => {
    const errors = []
    for (const record of [...children].reverse()) {
      const { child } = record
      try {
        if (child.pid && child.exitCode === null && child.signalCode === null) {
          if (process.platform === 'win32') {
            // Only this runner's still-running child trees, never process-name matching.
            execFileSync('taskkill', ['/PID', String(child.pid), '/T', '/F'], { windowsHide: true, stdio: 'ignore' })
          } else process.kill(-child.pid, 'SIGTERM') // Owned Linux process group, including Vite/esbuild.
        }
        await Promise.race([record.done, delay(10_000).then(() => {
          if (child.exitCode === null && child.signalCode === null) throw new Error(`${record.name} did not stop.`)
        })])
        await writeFile(join(runtime, `${record.name}.log`), redact(record.log))
      } catch { errors.push(`Could not stop/verify ${record.name}.`) }
    }
    // Registered before creation; validate exact run ownership before any deletion.
    for (const [kind, name, registered] of [['container', container, containerRegistered], ['volume', volume, volumeRegistered]]) {
      if (!registered) continue
      try {
        const names = docker(kind === 'container'
          ? ['ps', '-a', '--filter', `label=${label}=${runId}`, '--format', '{{.Names}}']
          : ['volume', 'ls', '--filter', `label=${label}=${runId}`, '--format', '{{.Name}}']).split(/\r?\n/)
        if (names.includes(name)) {
          const owner = docker([kind, 'inspect', '--format', `{{index .${kind === 'container' ? 'Config.' : ''}Labels "${label}"}}`, name])
          if (owner !== runId) throw new Error('Ownership mismatch.')
          docker(kind === 'container' ? ['rm', '-f', name] : ['volume', 'rm', name])
        }
      } catch { errors.push(`Could not remove owned ${kind}: ${name}`) }
    }
    try {
      const containers = docker(['ps', '-aq', '--filter', `label=${label}=${runId}`])
      const volumes = docker(['volume', 'ls', '-q', '--filter', `label=${label}=${runId}`])
      if (containers || volumes) throw new Error('Owned Docker resources remain.')
      console.log('TEMP CONTAINERS=0\nTEMP VOLUMES=0')
    } catch { errors.push('Could not verify Docker cleanup.') }
    if ((await Promise.all(ports.map(listening))).some(Boolean)) errors.push('A temporary port is still listening.')
    else if (!errors.some(error => error.includes('stop/verify'))) console.log('TEMP PROCESSES=0')
    try {
      if (habitualBefore && habitualState() !== habitualBefore) throw new Error('Habitual container metadata changed.')
      if (habitualBefore) console.log('HABITUAL DATABASE UNTOUCHED (no connection; metadata or absence unchanged)')
      if (envBefore && JSON.stringify(await envMetadata()) !== JSON.stringify(envBefore)) throw new Error('.env metadata changed.')
      if (envBefore) console.log('.ENV UNCHANGED (metadata; contents not read)')
    } catch (error) { errors.push(error.message) }
    if (errors.length) throw new Error(errors.join('\n'))
  })()
  return cleanupPromise
}

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => { cleanup().then(() => process.exit(130), error => {
    console.error(error.message); process.exit(1)
  }) })
}

try {
  await mkdir(runtime, { recursive: true })
  habitualBefore = habitualState()
  envBefore = await envMetadata()
  docker(['image', 'inspect', '--format', '{{.Id}}', 'mariadb:11.2'])
  console.log(`E2E run ${runId}: isolated MariaDB 11.2, Flyway V1, Chromium.`)

  // Always build current sources; offline Maven avoids runtime Internet dependency.
  const build = process.platform === 'win32'
    ? start('build', baseEnv.ComSpec || 'cmd.exe', ['/d', '/s', '/c', 'mvn -B -o -DskipTests package'], join(repo, 'smartaudits-backend'))
    : start('build', 'mvn', ['-B', '-o', '-DskipTests', 'package'], join(repo, 'smartaudits-backend'))
  if (await build.done !== 0) throw new Error('Backend artifact build failed (see isolated build.log).')

  volumeRegistered = true
  docker(['volume', 'create', '--label', `${label}=${runId}`, volume])
  containerRegistered = true
  docker(['create', '--pull=never', '--name', container, '--label', `${label}=${runId}`,
    '--publish', '127.0.0.1::3306', '--mount', `type=volume,source=${volume},target=/var/lib/mysql`,
    '-e', 'MARIADB_ROOT_PASSWORD', '-e', 'MARIADB_DATABASE', '-e', 'MARIADB_USER', '-e', 'MARIADB_PASSWORD',
    'mariadb:11.2'], { MARIADB_ROOT_PASSWORD: rootPassword, MARIADB_DATABASE: database,
    MARIADB_USER: 'sa14_user', MARIADB_PASSWORD: dbPassword })
  docker(['start', container])
  const dbPort = Number(docker(['inspect', '--format', '{{(index (index .NetworkSettings.Ports "3306/tcp") 0).HostPort}}', container]))
  ports.push(dbPort)
  await waitUntil('temporary MariaDB', () => {
    try { return sql('SELECT 1') === '1' } catch { return false }
  })
  if (sql('SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()') !== '0') {
    throw new Error('Temporary schema must be empty before Flyway.')
  }
  const backendPort = await freePort()
  const frontendPort = await freePort()
  const backend = start('backend', 'java', ['-jar', join(repo, 'smartaudits-backend/target/smartaudits-backend-1.0.0.jar'),
    '--spring.config.location=classpath:/application.properties'], runtime, {
    DB_URL: `jdbc:mariadb://127.0.0.1:${dbPort}/${database}`, DB_USERNAME: 'sa14_user', DB_PASSWORD: dbPassword,
    JWT_SECRET: jwt, SERVER_ADDRESS: '127.0.0.1', SERVER_PORT: String(backendPort),
    FLYWAY_ENABLED: 'true', FLYWAY_BASELINE_ON_MIGRATE: 'false', SPRING_JPA_HIBERNATE_DDL_AUTO: 'validate',
    APP_DATA_INITIALIZER_ENABLED: 'true', APP_ADMIN_BOOTSTRAP_ENABLED: 'true',
    SMARTAUDITS_ADMIN_EMAIL: `admin-${runId}@example.invalid`, SMARTAUDITS_ADMIN_PASSWORD: password,
    SMARTAUDITS_ADMIN_NOMBRE: 'Administrador E2E',
  })
  await waitUntil('backend startup', () => backend.log.includes('Started SmartAuditsApplication'), 120_000, backend)
  if (sql('SELECT version, type, script, success FROM flyway_schema_history ORDER BY installed_rank') !== '1\tSQL\tV1__initial_schema.sql\t1') {
    throw new Error('Expected exactly one normal V1 migration and no baseline.')
  }
  // Spring's Started log precedes CommandLineRunner; wait for its transaction
  // to commit before allowing registration or login in the browser.
  await waitUntil('temporary ADMIN bootstrap', () =>
    sql("SELECT COUNT(*) FROM usuarios WHERE role = 'ADMIN' AND protegido = b'1'") === '1', 30_000, backend)
  console.log('EMPTY DATABASE + FLYWAY V1 OK (no baseline)')
  const web = start('frontend', process.execPath, [join(frontend, 'e2e/server.mjs')], frontend, {
    E2E_BACKEND_URL: `http://127.0.0.1:${backendPort}`, E2E_FRONTEND_PORT: String(frontendPort),
  })
  await waitUntil('frontend startup', () => web.log.includes('E2E_FRONTEND_READY'), 60_000, web)
  const tests = start('playwright', process.execPath, [join(frontend, 'node_modules/@playwright/test/cli.js'), 'test'], frontend, {
    E2E_RUN_ID: runId, E2E_BASE_URL: `http://127.0.0.1:${frontendPort}`,
    E2E_ADMIN_EMAIL: `admin-${runId}@example.invalid`, E2E_PASSWORD: password,
  })
  const code = await tests.done
  console.log(redact(tests.log))
  if (code !== 0) throw new Error('Playwright suite failed.')
  successful = true
} catch (error) {
  console.error(redact(error.message))
  console.error(`Isolated diagnostic logs: .e2e-runtime/${runId}/ (ignored by Git).`)
  process.exitCode = 1
} finally {
  try { await cleanup() } catch (error) { console.error(error.message); process.exitCode = 1 }
}
if (successful && !process.exitCode) console.log('PHASE 1.4 E2E PASSED')
