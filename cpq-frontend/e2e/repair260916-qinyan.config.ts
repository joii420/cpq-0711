// repair-260916（小计后缀）· 主线亲验专用配置。
// 只跑亲验 spec；目标必须是连 cpq_db_rp0916d 的临时栈（5295 → 8295）。
// 刻意不挂 globalSetup：默认那份会写共享库 cpq_db_0724 的 user 表。
import { defineConfig, devices } from '@playwright/test';

const BASE = process.env.PW_BASE_URL || 'http://localhost:5295';
if (/:(5174|5173)\b/.test(BASE)) throw new Error(`亲验不得跑在共享前端上（${BASE}）`);

export default defineConfig({
  testDir: '.',
  testMatch: /repair260916-qinyan.*\.spec\.ts/,
  timeout: 900_000,
  expect: { timeout: 30_000 },
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  outputDir: 'test-results/repair260916-qinyan',
  use: {
    baseURL: BASE,
    channel: 'chrome',
    headless: true,
    viewport: { width: 1680, height: 1050 },
    locale: 'zh-CN',
    screenshot: 'off',
    trace: 'off',
    video: 'off',
    actionTimeout: 20_000,
    navigationTimeout: 60_000,
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], channel: 'chrome' } }],
});
