// repair-260916 · AC-13④ read-only UI check on the shared dev stack (5174 -> 8081 -> cpq_db_0724).
// No globalSetup (the default one writes the shared "user" table). The spec installs a write guard.
import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: '.',
  testMatch: /repair260916-devcheck-.*\.spec\.ts/,
  timeout: 600_000,
  expect: { timeout: 30_000 },
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  use: { baseURL: 'http://localhost:5174', headless: true, locale: 'zh-CN', screenshot: 'off', trace: 'off', video: 'off', actionTimeout: 20_000, navigationTimeout: 60_000 },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], channel: 'chrome', viewport: { width: 1680, height: 1050 } } }],
});
