import { defineConfig, devices } from '@playwright/test';
export default defineConfig({
  testDir: '.', testMatch: /d38-verify\.spec\.ts/,
  timeout: 600_000, expect: { timeout: 20_000 },
  fullyParallel: false, retries: 0, workers: 1, reporter: [['list']],
  use: {
    baseURL: process.env.PW_BASE_URL || 'http://localhost:5211',
    headless: true, locale: 'zh-CN', viewport: { width: 1920, height: 1200 },
    screenshot: 'only-on-failure', actionTimeout: 25_000, navigationTimeout: 60_000,
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], channel: 'chrome' } }],
});
