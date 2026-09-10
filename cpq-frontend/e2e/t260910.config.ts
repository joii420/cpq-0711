import { defineConfig } from '@playwright/test';
// task-260910 专用：指向 worktree 临时 vite(5211)+临时后端(8211)。
// 🚫 不定义 webServer —— 绝不碰共享 5174；也不跑 global-setup（它会反复 UI 登录触发限流）。
export default defineConfig({
  testDir: '.',
  timeout: 240_000,
  expect: { timeout: 20_000 },
  retries: 0,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: process.env.PW_BASE_URL || 'http://localhost:5211',
    channel: 'chrome',
    headless: true,
    viewport: { width: 1600, height: 1000 },
    locale: 'zh-CN',
    trace: 'retain-on-failure',
  },
});
