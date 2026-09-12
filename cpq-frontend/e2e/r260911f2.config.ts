import { defineConfig } from '@playwright/test';
// repair-260911 F-2 开发自测专用。
// 🚫 不定义 webServer —— 绝不碰共享 5174 / 8081 之外的东西；5174 保留给主线亲验。
export default defineConfig({
  testDir: '.',
  timeout: 420_000,
  expect: { timeout: 25_000 },
  retries: 0,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: process.env.PW_BASE_URL || 'http://localhost:5205',
    channel: 'chrome',
    headless: true,
    viewport: { width: 1680, height: 1050 },
    locale: 'zh-CN',
    trace: 'retain-on-failure',
  },
});
