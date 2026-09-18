// repair-260916（小计后缀）测试分片 S-C 专用配置。
// 只跑 repair260916-excel-migration.spec.ts；目标必须是连 cpq_db_rp0916d 的临时栈（5293 → 8293）。
// 刻意不挂 globalSetup：本 spec 自行 API 登录，不需要改 cpq_db_0724 的 user 表。
// 运行（worktree 的 cpq-frontend 下）：
//   PW_BASE_URL=http://localhost:5293 PW_BACKEND_URL=http://localhost:8293 npx playwright test -c e2e/repair260916-sc.config.ts
import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: '.',
  testMatch: /repair260916-(excel-migration|sc-probe)\.spec\.ts/,
  timeout: 300_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  outputDir: 'test-results/repair260916-sc',
  use: {
    baseURL: process.env.PW_BASE_URL,
    channel: 'chrome',
    headless: true,
    viewport: { width: 1920, height: 1080 },
    locale: 'zh-CN',
    trace: 'off',
    video: 'off',
    screenshot: 'off',
  },
});
