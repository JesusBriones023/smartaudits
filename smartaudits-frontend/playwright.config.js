import { defineConfig, devices } from '@playwright/test'

// The runner owns all servers and supplies the only permitted test origin.
if (!process.env.E2E_RUN_ID || !/^http:\/\/127\.0\.0\.1:\d+$/.test(process.env.E2E_BASE_URL || '')) {
  throw new Error('Run npm run test:e2e to provision an isolated environment first.')
}

export default defineConfig({
  testDir: './e2e',
  testMatch: '**/*.spec.js',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: true,
  timeout: 45_000,
  expect: { timeout: 10_000 },
  reporter: 'list',
  use: {
    baseURL: process.env.E2E_BASE_URL,
    ...devices['Desktop Chrome'],
    headless: true,
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
    serviceWorkers: 'block',
  },
  projects: [{ name: 'chromium', use: { browserName: 'chromium' } }],
})
