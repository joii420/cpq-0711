// repair-260911 专用 Playwright 配置：不挂 globalSetup（避免其对 cpq_db_0724 的 user UPDATE），
// 本片写入面必须为空。目标是 worktree 临时前端 5096 → 临时后端 8096。
import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: '.',
  testMatch: /repair260911-.*\.spec\.ts/,
  timeout: 180_000,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: process.env.PW_BASE_URL || 'http://localhost:5096',
    trace: 'off',
    channel: 'chrome',
    viewport: { width: 1680, height: 1000 },
  },
});
