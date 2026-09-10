import { defineConfig } from '@playwright/test';
// repair-260910 专用：指向本 worktree 临时 vite(5212) + 临时后端(8212)。
// 🚫 不定义 webServer —— 绝不碰共享 5174 / 8081 / 8091 / 8099；也不跑 global-setup（反复 UI 登录会触发限流）。
// ⚠️ 本环境 Playwright 自带 chromium 未安装 → 用系统 Chrome。
export default defineConfig({
  testDir: '.',
  timeout: 300_000,
  expect: { timeout: 20_000 },
  retries: 0,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: process.env.PW_BASE_URL || 'http://localhost:5212',
    launchOptions: { executablePath: '/usr/bin/google-chrome', args: ['--no-sandbox'] },
    headless: true,
    viewport: { width: 1600, height: 1000 },
    locale: 'zh-CN',
    trace: 'retain-on-failure',
  },
});
