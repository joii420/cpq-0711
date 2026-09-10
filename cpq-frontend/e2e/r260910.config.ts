/**
 * repair-260910「核价树版本切换」· **S1 片**（唯一分片）专用 Playwright config。
 *
 * 运行（改动落地后，由主线解锁）：
 *   npx playwright test --config=e2e/r260910.config.ts --reporter=list
 *   # 指向 worktree 临时实例时：
 *   PW_BASE_URL=http://localhost:5xxx PW_BACKEND_URL=http://localhost:8xxx npx playwright test --config=…
 *
 * ⚠️ `workers: 1` + `fullyParallel: false` 是**契约不是性能参数**：
 *    `r260910-s1e-switch.spec.ts` 写载体单的 override / costing_render，
 *    并行会让「切到 v2」和「切回 v3」互相抢，症状是"随机红、且红得像业务回归"。
 *
 * ⚠️ 文件字母序即执行序，**不要改文件名**：
 *    s1a 前置正身 → s1b 接口只读 → s1c UI 只读 → s1d 基线对照（只读）→ s1e 切换（唯一写入，殿后）
 *
 * ⚠️ 刻意**不复用仓库默认 globalSetup** —— 那份会往共享库的 `user` 表写解锁 UPDATE
 *    （改全局状态，本片的还原清单里没有这一项，改了就还不回去）。用例自己走 UI 登录。
 *
 * ⚠️ 不定义 webServer：5174 是全会话共享的 dev server，测试**绝不能重启或抢占它**。
 */
import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: '.',
  testMatch: /r260910-s1[a-z]-.*\.spec\.ts$/,
  timeout: 300_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  retries: 0,            // 🚫 不许靠重试把偶发洗成绿（testing.md）
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: process.env.PW_BASE_URL || 'http://localhost:5174',
    headless: true,
    viewport: { width: 1680, height: 1050 },
    locale: 'zh-CN',
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
    video: 'off',
    actionTimeout: 20_000,
    navigationTimeout: 60_000,
  },
  projects: [
    // Ubuntu 26.04 无 Playwright 内置 chromium 构建，必须走系统 google-chrome。
    // 不设 channel 时每个用例都倒在 "Executable doesn't exist"，长得和业务回归一模一样 ——
    // 那是"整套 E2E 一个断言都没跑"的假绿，最隐蔽的一种。
    { name: 'chromium', use: { ...devices['Desktop Chrome'], channel: 'chrome' } },
  ],
});
