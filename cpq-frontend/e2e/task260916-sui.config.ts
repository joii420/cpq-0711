// task-260916 · S-UI 分片专用 Playwright 配置（草稿；审核通过后移入 cpq-frontend/e2e/）
//
// 刻意不挂 globalSetup —— 默认 e2e/global-setup.ts 会 UPDATE cpq_db_0724 的 "user" 表（全局状态，testing.md §4.3）。
// 本片用 API 登录（page.request.post /auth/login），登录被锁时停下报主线，不改 user 表。
//
// workers: 1 + fullyParallel: false 是契约：
//   · sui-3-write 内 AC-7 → AC-18 有先后依赖（AC-18 前置 = AC-7 已执行）；
//   · 本片所有 spec 共用同一个私有写面（TEST-PM-SRC-A × 2020-09-08/09/10），并行会互相清数据。
// timezoneId 钉死 Asia/Shanghai：AC-17 审核单 created_at = 2026-09-09 06:20 UTC，本机 TZ=-0700 时会显示成 09-08。
//
// 运行（在 worktree 的 cpq-frontend 目录下；端口由主线给）：
//   pgrep -af "node.*[p]laywright test"      # 开跑前必须为空（test.md §4 第 3 条）
//   SUI_PHASE=after PW_BASE_URL=http://localhost:<前端临时端口> PW_BACKEND_URL=http://localhost:<后端临时端口> \
//     SUI_EXPECT_MIGRATION=<新迁移版本号> npx playwright test -c e2e/task260916-sui.config.ts
//   基线（合并前 / 迁移前，只读）：SUI_PHASE=before PW_BASE_URL=http://localhost:5174 PW_BACKEND_URL=http://localhost:8081 \
//     npx playwright test -c e2e/task260916-sui.config.ts e2e/task260916-sui-1-sql.spec.ts e2e/task260916-sui-2-readonly.spec.ts
import { defineConfig, devices } from '@playwright/test';

if (!process.env.PW_BASE_URL || !process.env.PW_BACKEND_URL) {
  throw new Error('S-UI 必须显式给 PW_BASE_URL 与 PW_BACKEND_URL（不许落到默认 5174/8081 上去验改后代码）');
}

export default defineConfig({
  testDir: '.',
  testMatch: /task260916-sui-.*\.spec\.ts/,
  timeout: 240_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  outputDir: 'test-results/task260916-sui',
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
    actionTimeout: 15_000,
    navigationTimeout: 60_000,
  },
});
