/**
 * task-260901 材质管理 E2E 专用 config。
 *
 * 🚨 **必须跑在隔离环境上**（见 task260901-material.helpers.ts 的 assertIsolatedEnv）：
 *   PW_BASE_URL=http://localhost:5175 PW_BACKEND_URL=http://localhost:8082 \
 *   PW_DB=<临时库或 dev 库> \
 *   npx playwright test --config=e2e/task260901-material.config.ts --reporter=list
 *
 * 8081 / 5174 保留给主线亲验，默认拒绝。
 *
 * ⚠️ `workers: 1` 是**契约不是性能参数** —— 本套 spec 共用同一份材质库全局状态，
 *    并行会互相清库。别调大。
 */
import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: '.',
  testMatch: /task260901-material-.*\.spec\.ts/,
  // 不复用仓库默认 globalSetup：那份会往 cpq_db_0724 的 user 表写解锁 UPDATE。
  // 本套用例每个 spec 自己 API 登录，不需要 storageState。
  timeout: 180_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: process.env.PW_BASE_URL,
    headless: true,
    viewport: { width: 1600, height: 1000 },
    locale: 'zh-CN',
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
    video: 'off',
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'], channel: 'chrome' } },
  ],
});
