// repair-260916 测试分片 S-1（只读片）专用 Playwright 配置。
// 刻意不挂 globalSetup —— 默认 e2e/global-setup.ts 会 UPDATE cpq_db_0724 的 "user" 表（全局状态，
// testing.md §4.3），本片写入面为空，一律不碰。
// workers: 1 + fullyParallel: false 是契约：T1.2（AC-11 取集合 A）必须先于本片其余访问后端的用例。
// 运行（在 worktree 的 cpq-frontend 目录下）：
//   npx playwright test -c e2e/repair260916-s1.config.ts
import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: '.',
  testMatch: /repair260916-s1-.*\.spec\.ts/,
  timeout: 300_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  outputDir: 'test-results/repair260916-s1',
  use: {
    trace: 'off',
    video: 'off',
    screenshot: 'off',
  },
});
