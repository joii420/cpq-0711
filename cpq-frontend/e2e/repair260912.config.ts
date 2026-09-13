// repair-260912 测试片 S1 专用 Playwright 配置。
// 刻意不挂 globalSetup —— 默认 e2e/global-setup.ts 会 UPDATE "user" 表（全局状态，
// testing.md §4.3）；本片写入面为空，一律不碰。
import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: '.',
  testMatch: /repair260912-.*\.spec\.ts/,
  timeout: 300_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: process.env.PW_BASE_URL || 'http://localhost:5096',
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
