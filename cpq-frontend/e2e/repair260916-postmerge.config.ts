// repair-260916 · 合并后在共享 dev server 上的【只读】复核专用配置（spec 内已对所有非 GET 请求做拦截）。
// 刻意不挂 globalSetup：默认那份会写 cpq_db_0724 的 user 表。
import { defineConfig, devices } from '@playwright/test';
export default defineConfig({
  testDir: '.',
  testMatch: /repair260916-postmerge\.spec\.ts/,
  timeout: 300_000,
  expect: { timeout: 30_000 },
  fullyParallel: false, retries: 0, workers: 1,
  reporter: [['list']],
  outputDir: 'test-results/repair260916-postmerge',
  use: { baseURL: process.env.PW_BASE_URL || 'http://localhost:5174', channel: 'chrome', headless: true,
    viewport: { width: 1680, height: 1050 }, locale: 'zh-CN', screenshot: 'off', trace: 'off', video: 'off',
    actionTimeout: 20_000, navigationTimeout: 60_000 },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], channel: 'chrome' } }],
});
