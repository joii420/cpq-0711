/**
 * task-260911 · **S2 片**（私有写片，造数前缀 `T0911S2`）专用 Playwright config。
 * 认领 AC-3 / AC-5 / AC-6 / AC-8。
 *
 * 运行：
 *   # A 侧（改动前 = 共享 5174/8081 = master + repair-260910 的标量产物）
 *   T0911S2_LABEL=改动前 npx playwright test --config=e2e/t0911s2.config.ts --reporter=list
 *   # B 侧（改动后 🚫 必须打本片自己的临时实例，不许打 5174/8081/8099）
 *   PW_BASE_URL=http://localhost:5233 PW_BACKEND_URL=http://localhost:8123 T0911S2_LABEL=改动后 npx playwright test --config=e2e/t0911s2.config.ts
 *
 * ⚠️ `workers: 1` 是契约不是性能参数：本片三张单共用同一个 admin 会话与同一份共享库。
 * ⚠️ 刻意不复用仓库默认 globalSetup（那份会往共享库 user 表写 UPDATE = 改全局状态）。
 * ⚠️ 不定义 webServer：5174 是全会话共享 dev server，测试绝不能重启或抢占它。
 */
import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: '.',
  testMatch: /t0911s2-.*\.spec\.ts$/,
  timeout: 600_000,
  expect: { timeout: 25_000 },
  fullyParallel: false,
  retries: 0,            // 🚫 不许靠重试把偶发洗成绿
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: process.env.PW_BASE_URL || 'http://localhost:5174',
    headless: true,
    viewport: { width: 1680, height: 1400 },
    locale: 'zh-CN',
    screenshot: 'only-on-failure',
    trace: 'off',
    video: 'off',
    actionTimeout: 25_000,
    navigationTimeout: 120_000,
  },
  // Ubuntu 26.04 无 Playwright 内置 chromium 构建，必须走系统 google-chrome。
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], channel: 'chrome' } }],
});
