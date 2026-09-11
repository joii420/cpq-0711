/**
 * repair-260910 · **S2 片**（私有写片，造数前缀 `R0910S2`）专用 Playwright config。
 *
 * 认领 AC-1 / AC-2 / AC-4 / AC-5。
 *
 * 运行：
 *   # 改动前基线（打共享 5174/8081 = master = pre-fix）
 *   R0910S2_LABEL=改动前 npx playwright test --config=e2e/r0910s2.config.ts --reporter=list
 *   # 改动后验收（🚫 必须打 worktree 的临时实例，不许打 5174/8081，否则是假绿）
 *   PW_BASE_URL=http://localhost:5<临时> R0910S2_LABEL=改动后 npx playwright test --config=e2e/r0910s2.config.ts
 *
 * ⚠️ `workers: 1` 是契约不是性能参数：本片三张单共用同一个 admin 会话与同一份共享库，
 *    并行会让「读 A 单的卡片」和「读 B 单的卡片」抢同一个页面上下文。
 *
 * ⚠️ 刻意**不复用仓库默认 globalSetup** —— 那份会往共享库的 `user` 表写解锁 UPDATE（改全局状态）。
 *    本片的还原清单里没有这一项，改了就还不回去。用例自己走 UI 登录。
 *
 * ⚠️ 不定义 webServer：5174 是全会话共享 dev server，测试绝不能重启或抢占它。
 */
import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: '.',
  testMatch: /r0910s2-.*\.spec\.ts$/,
  timeout: 420_000,
  expect: { timeout: 25_000 },
  fullyParallel: false,
  retries: 0,            // 🚫 不许靠重试把偶发洗成绿
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: process.env.PW_BASE_URL || 'http://localhost:5174',
    headless: true,
    viewport: { width: 1680, height: 1050 },
    locale: 'zh-CN',
    screenshot: 'only-on-failure',
    trace: 'off',
    video: 'off',
    actionTimeout: 25_000,
    navigationTimeout: 90_000,
  },
  projects: [
    // Ubuntu 26.04 无 Playwright 内置 chromium 构建，必须走系统 google-chrome。
    // 不设 channel 时每个用例都倒在 "Executable doesn't exist" —— 那是"一个断言都没跑"的假绿。
    { name: 'chromium', use: { ...devices['Desktop Chrome'], channel: 'chrome' } },
  ],
});
