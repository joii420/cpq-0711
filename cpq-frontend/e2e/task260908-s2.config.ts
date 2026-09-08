/**
 * task-260908 · 分片 S2（配置器交互）E2E 专用 config。
 *
 * 🚨 **必须跑在隔离环境上**（helper 的 `assertIsolatedEnv()` 会拦）：
 *   PW_BASE_URL=http://localhost:5175 PW_BACKEND_URL=http://localhost:8082 \
 *   npx playwright test --config=e2e/task260908-s2.config.ts --reporter=list
 *
 * 5174 / 8081 保留给主线亲验，且它们服务的是**主工作区已合并代码**，
 * 看不到本 worktree 的 F-2/F-3 改动 ⇒ 拿它跑 = 假绿。默认拒绝。
 *
 * ⚠️ `workers: 1` 是**契约不是性能参数** —— 本套 spec 串行造/清同一批
 *    `T260908-S2-*` 夹具，并行会互相删对方的组件。别调大。
 *
 * ⚠️ 不复用仓库默认 globalSetup：那份会往共享库的 `user` 表写解锁 UPDATE
 *    （改全局状态，S2 不许碰）。本 spec 每条用例自己走 UI 登录。
 */
import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: '.',
  testMatch: /task260908-s2-.*\.spec\.ts/,
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
    // video 需 Playwright ffmpeg 二进制，Ubuntu 26.04 无构建可下；截图已够用
    video: 'off',
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
  },
  projects: [
    // Ubuntu 26.04 无 Playwright 内置 chromium 构建，改用系统 google-chrome
    { name: 'chromium', use: { ...devices['Desktop Chrome'], channel: 'chrome' } },
  ],
});
