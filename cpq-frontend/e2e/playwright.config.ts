import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: '.',
  globalSetup: './global-setup.ts',
  timeout: 120_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'report' }]],
  use: {
    // task-0713 E2E 验收：支持 PW_BASE_URL 覆盖（临时 Vite + 临时后端场景），
    // 未设置时保持既有默认 5174，不影响其他任务/常规运行。
    baseURL: process.env.PW_BASE_URL || 'http://localhost:5174',
    headless: true,
    viewport: { width: 1600, height: 1000 },
    locale: 'zh-CN',
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
    // video 需 Playwright ffmpeg 二进制，Ubuntu 26.04 无构建可下；截图已够用，关闭 video
    video: 'off',
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
  },
  projects: [
    {
      name: 'chromium',
      // Ubuntu 26.04 无 Playwright 内置 chromium 构建，改用系统 google-chrome
      use: { ...devices['Desktop Chrome'], channel: 'chrome' },
    },
  ],
});
