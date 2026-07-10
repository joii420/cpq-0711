import { defineConfig, devices } from '@playwright/test';
// 隔离:task-0708 repair-1 前端 5175 → 后端 8082 / cpq_db_repair1,无 globalSetup。
export default defineConfig({
  testDir: '.',
  timeout: 120_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: 'http://localhost:5175',
    headless: true,
    viewport: { width: 1600, height: 1000 },
    locale: 'zh-CN',
    screenshot: 'only-on-failure',
    trace: 'off',
    video: 'off',
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], channel: 'chrome' } }],
});
