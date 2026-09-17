// repair-260916 · mainline hands-on verification (亲验) config.
// No globalSetup (the default one writes cpq_db_0724's "user" table). Acceptance stack only: 5196 -> 8196 -> cpq_db_260916.
import { defineConfig, devices } from '@playwright/test';

const BASE = process.env.PW_BASE_URL || 'http://localhost:5196';
if (/:(5174|5173)\b/.test(BASE)) {
  throw new Error(`repair260916-mainline must not run against the shared frontend (${BASE})`);
}

export default defineConfig({
  testDir: '.',
  testMatch: /repair260916-mainline-.*\.spec\.ts/,
  timeout: 900_000,
  expect: { timeout: 30_000 },
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: BASE,
    headless: true,
    locale: 'zh-CN',
    screenshot: 'off',
    trace: 'off',
    video: 'off',
    actionTimeout: 20_000,
    navigationTimeout: 60_000,
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], channel: 'chrome', viewport: { width: 1680, height: 1050 } } }],
});
