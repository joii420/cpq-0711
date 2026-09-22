// task-260920 · 主线亲验专用 Playwright 配置（不挂 globalSetup：默认的 e2e/global-setup.ts 会写开发库 "user" 表）
// 运行：PW_BASE_URL=http://localhost:5295 MV_EVIDENCE=<证据目录> npx playwright test -c e2e/mainline260920.config.ts
import { defineConfig, devices } from '@playwright/test';

if (!process.env.PW_BASE_URL || /:5174\b/.test(process.env.PW_BASE_URL)) {
  throw new Error('主线亲验必须显式给 PW_BASE_URL（worktree 临时 vite），🚫 5174');
}

export default defineConfig({
  testDir: '.',
  testMatch: /mainline260920-.*\.spec\.ts/,
  timeout: 900_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  outputDir: 'test-results/mainline260920',
  use: {
    ...devices['Desktop Chrome'],
    channel: 'chrome',
    baseURL: process.env.PW_BASE_URL,
    headless: true,
    viewport: { width: 1600, height: 1000 },
    locale: 'zh-CN',
    trace: 'retain-on-failure',
    video: 'off',
    screenshot: 'only-on-failure',
    actionTimeout: 15_000,
    navigationTimeout: 60_000,
  },
});
