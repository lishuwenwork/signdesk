import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  timeout: 45000,
  expect: { timeout: 10000 },
  reporter: [['list']],
  use: {
    baseURL: 'http://127.0.0.1:18080',
    viewport: { width: 1440, height: 1000 },
    trace: 'retain-on-failure',
    launchOptions: process.env.SIGNDESK_TEST_BROWSER
      ? { executablePath: process.env.SIGNDESK_TEST_BROWSER, args: ['--no-sandbox'] }
      : {},
  },
  webServer: {
    command: 'node ../scripts/e2e-server.mjs',
    url: 'http://127.0.0.1:18080/api/system/status',
    reuseExistingServer: false,
    timeout: 60000,
  },
})
