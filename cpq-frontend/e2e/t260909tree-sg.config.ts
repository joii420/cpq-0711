/**
 * task-260909「核价树骨架分档与轴口径统一」· **S-全局片** 专用 Playwright config。
 *
 * 运行：
 *   npx playwright test --config=e2e/t260909tree-sg.config.ts --reporter=list
 *   # 指向 worktree 临时实例时：
 *   PW_BASE_URL=http://localhost:5175 PW_BACKEND_URL=http://localhost:8082 npx playwright test --config=…
 *
 * ⚠️ `workers: 1` 与 `fullyParallel: false` 是**契约不是性能参数**：
 *    本片写的是 `costing_bom_tree_config` 的**全局单例生效配置**，
 *    并行会让两条用例互相抢生效权，症状是"随机红、且红得像业务回归"（testing.md §4.3）。
 *
 * ⚠️ 本片**串行殿后**：S1 只读片全绿之前不许开跑。
 *
 * ⚠️ 刻意**不复用仓库默认 globalSetup** —— 那份会往共享库的 `user` 表写解锁 UPDATE
 *    （改全局状态，本片的还原清单里没有这一项，改了就还不回去）。用例自己走 UI 登录。
 *
 * ⚠️ 不定义 webServer：5174 是全会话共享的 dev server，测试**绝不能重启或抢占它**。
 */
import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: '.',
  testMatch: /t260909tree-sg\.spec\.ts$/,
  timeout: 300_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  retries: 0,            // 🚫 不许靠重试把偶发洗成绿（testing.md §4.1）
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
    // 不设 channel 时每个用例都倒在 "Executable doesn't exist"，长得和业务回归一模一样。
    { name: 'chromium', use: { ...devices['Desktop Chrome'], channel: 'chrome' } },
  ],
});
