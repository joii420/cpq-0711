// repair-260916（改上游页签后公式按旧值算）S-E 片专用 Playwright 配置。
// - baseURL 取 PW_BASE_URL（master 轮 = 共享 5174；修复轮 = 临时 5197）。
// - 沿用 e2e/global-setup.ts（admin storageState）。它会 UPDATE 共享库 "user" 表解锁账号 ——
//   已在 test.md §4 登记为本片会动的全局状态；开跑前必须采样 `pgrep -f "node.*[p]laywright test"` 互斥。
// - workers: 1 + fullyParallel: false 是契约（S-E 按 S-全局 纪律串行），不是性能参数。
import { defineConfig, devices } from '@playwright/test';
import * as path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

export default defineConfig({
  testDir: '.',
  testMatch: /repair260916-bfield-stale\.spec\.ts/,
  globalSetup: './global-setup.ts',
  outputDir: path.join(__dirname, '..', 'test-results', 'repair260916'),
  timeout: 1_200_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: process.env.PW_BASE_URL || 'http://localhost:5174',
    storageState: path.join(__dirname, '.auth', 'admin.json'),
    headless: true,
    viewport: { width: 1680, height: 1050 },
    locale: 'zh-CN',
    screenshot: 'off',
    trace: 'off',
    video: 'off',
    actionTimeout: 20_000,
    navigationTimeout: 60_000,
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], channel: 'chrome', viewport: { width: 1680, height: 1050 } } }],
});
