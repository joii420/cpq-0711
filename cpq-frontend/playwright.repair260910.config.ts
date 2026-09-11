import { defineConfig } from '@playwright/test';

/**
 * S-2 片（repair-260910）专用 Playwright 配置。
 *
 * 🚦 为什么另起一份而不是用 playwright.config.ts：
 *   默认配置挂了 e2e/global-setup.ts，而它在 :42 对 **cpq_db_0724** 的 `user` 表执行
 *   `UPDATE "user" SET locked_until=NULL, failed_login_attempts=0, is_first_login=false ...`。
 *   那是「改共享库的全局状态」，属 S-全局 片的写入面，S-2（私有写片）不许碰。
 *   ⇒ 本配置 **不挂 globalSetup**，用例自己走 UI 登录。
 *
 * 🚫 本文件不修改任何既有共享 E2E 基建（global-setup.ts / playwright.config.ts 一个字没动）。
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  retries: 0,
  workers: 1, // 契约不是性能参数：共享后端 DB，必须串行
  use: {
    baseURL: process.env.PW_BASE_URL || 'http://localhost:5175',
    trace: 'retain-on-failure',
    channel: 'chrome', // 本机无 Playwright 内置 chromium，必须走系统 chrome
    viewport: { width: 1680, height: 1050 },
  },
  // 🚫 不定义 webServer：目标是 worktree 自己的临时 vite(5175)，绝不碰共享的 5174
  reporter: [['list']],
});
