/**
 * repair-260908 · AC-R8 E2E 专用 config。
 *
 * 必须跑在 worktree 的隔离端口上（helper 的 assertIsolatedEnv() 会拦 5174/8081）：
 *   PW_BASE_URL=http://localhost:5199 PW_BACKEND_URL=http://localhost:8097 \
 *     npx playwright test --config=e2e/repair260908-r8.config.ts --reporter=list
 *
 * `workers: 1` 是契约不是性能参数：本 spec 串行造/清同一批 T260908R-* 夹具。
 */
import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: '.',
  testMatch: /repair260908-r8-.*\.spec\.ts/,
  timeout: 240_000,
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
