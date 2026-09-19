import { defineConfig, devices } from '@playwright/test';

// repair-260918 主线亲验（写流程）专用配置 —— 只匹配 mainline260918-verify.spec.ts，🚫 不要并入 S-UI 配置。
if (!process.env.PW_BASE_URL) throw new Error('必须显式给 PW_BASE_URL（亲验用 http://localhost:5174）');

export default defineConfig({
  testDir: '.',
  testMatch: /mainline260918-verify\.spec\.ts/,
  timeout: 300_000,
  expect: { timeout: 30_000 },
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  outputDir: 'test-results/mainline260918',
  use: {
    ...devices['Desktop Chrome'],
    channel: 'chrome',
    baseURL: process.env.PW_BASE_URL,
    headless: true,
    viewport: { width: 1600, height: 1000 },
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    trace: 'retain-on-failure',
    video: 'off',
    screenshot: 'only-on-failure',
    actionTimeout: 20_000,
    navigationTimeout: 60_000,
  },
});
