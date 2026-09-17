// repair-260916 test slice S-global dedicated Playwright config.
// Deliberately NO globalSetup: the default e2e/global-setup.ts runs UPDATE on cpq_db_0724's "user" table
// (global state, testing.md §4.3). This slice only talks to the acceptance stack 5196 -> 8196 -> cpq_db_260916.
// workers: 1 + fullyParallel: false are a contract (serial, run last), not a performance knob.
import { defineConfig, devices } from '@playwright/test';

const BASE = process.env.PW_BASE_URL || 'http://localhost:5196';
if (/:(5174|5173)\b/.test(BASE)) {
  throw new Error(`repair260916-global must not run against the shared frontend (${BASE}); use the acceptance frontend 5196`);
}

export default defineConfig({
  testDir: '.',
  testMatch: /repair260916-global-.*\.spec\.ts/,
  timeout: 900_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: BASE,
    headless: true,
    viewport: { width: 1680, height: 1050 },
    locale: 'zh-CN',
    screenshot: 'only-on-failure',
    trace: 'off',
    video: 'off',
    actionTimeout: 20_000,
    navigationTimeout: 60_000,
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], channel: 'chrome', viewport: { width: 1680, height: 1050 } } }],
});
