// repair-260911 测试片 S1 专用 Playwright 配置。
// 刻意不挂 globalSetup —— 项目默认的 e2e/global-setup.ts 会对 cpq_db_0724
// 的 "user" 表做 UPDATE（清锁 + is_first_login=false），那是本片写入面之外的
// 全局状态改动（testing.md §4.3），本片一律不碰。
import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: '.',
  testMatch: /repair260911-.*\.spec\.ts/,
  timeout: 300_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: process.env.PW_BASE_URL || 'http://localhost:5091',
    headless: true,
    viewport: { width: 1680, height: 1050 },
    locale: 'zh-CN',
    screenshot: 'only-on-failure',
    trace: 'off',
    video: 'off',
    actionTimeout: 20_000,
    navigationTimeout: 40_000,
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], channel: 'chrome' } }],
});
