import { defineConfig, devices } from '@playwright/test';
export default defineConfig({
  testDir: '.', testMatch: /mainverify260910\.spec\.ts/,
  timeout: 300_000, expect: { timeout: 20_000 },
  fullyParallel: false, retries: 1, workers: 1, reporter: [['list']],
  use: { baseURL: 'http://localhost:5178', headless: true, locale: 'zh-CN',
         viewport: { width: 1920, height: 1200 },
         screenshot: 'only-on-failure', actionTimeout: 20_000, navigationTimeout: 40_000 },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], channel: 'chrome' } }],
});
