// task-261030 · S-UNIT 专用 Playwright 配置（只跑 T-03 几何）。
//
// 不启动 Vite、不连后端、不连任何库：global-setup 先用 Vitest 把 CostingSubtotalSummary 的 SSR 标记
// 连同样式表写成静态页，spec 用 file:// 打开量几何。
// 🚫 不加载仓库默认 globalSetup（它会登录 / 改共享库账号）。
import { defineConfig, devices } from '@playwright/test';
import os from 'node:os';
import path from 'node:path';

const chromeExecutable = process.env.T261030_CHROME_EXECUTABLE?.trim();

export default defineConfig({
  testDir: '.',
  testMatch: /(?:^|\/)task261030-costing-subtotal-geometry\.spec\.ts$/,
  globalSetup: './task261030-costing-subtotal-unit.global-setup.ts',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  outputDir: process.env.T261030_PW_OUTPUT_DIR?.trim()
    || path.join(os.tmpdir(), 'task261030-costing-subtotal-unit-playwright'),
  use: {
    ...devices['Desktop Chrome'],
    // 默认用 Playwright 自带 Chromium（主线 2026-10-30 已在 WSL 装好 ~/.cache/ms-playwright/chromium-1217）；
    // 需要时可用 T261030_CHROME_EXECUTABLE 指定其他可执行文件。
    ...(chromeExecutable ? { launchOptions: { executablePath: chromeExecutable } } : {}),
    headless: true,
    viewport: { width: 1280, height: 720 },
    locale: 'zh-CN',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
  },
});
