import { defineConfig, devices } from '@playwright/test';
export default defineConfig({
  testDir: '.',
  timeout: 300_000,
  expect: { timeout: 15_000 },
  fullyParallel: false, retries: 0, workers: 1,
  reporter: [['list']],
  use: {
    baseURL: process.env.PW_BASE_URL || 'http://localhost:5301',
    headless: true, viewport: { width: 1600, height: 1000 }, locale: 'zh-CN',
    screenshot: 'only-on-failure', trace: 'off', video: 'off',
    actionTimeout: 15_000, navigationTimeout: 30_000,
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], channel: 'chrome' } }],
});
