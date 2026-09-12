import { defineConfig } from '@playwright/test';
// repair-260911 · 分片 S-A（落库与渲染）测试专用配置。
// 🚫 不定义 webServer —— 服务由测试员自己在 scratchpad 副本里起（后端 8104 / 前端 5208）。
// 🚫 绝不碰 5174 / 8081（共享，主线亲验用）。
export default defineConfig({
  testDir: '.',
  timeout: 600_000,
  expect: { timeout: 25_000 },
  retries: 0,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: process.env.PW_BASE_URL || 'http://localhost:5208',
    channel: 'chrome',
    headless: true,
    viewport: { width: 1680, height: 1050 },
    locale: 'zh-CN',
    trace: 'retain-on-failure',
  },
});
