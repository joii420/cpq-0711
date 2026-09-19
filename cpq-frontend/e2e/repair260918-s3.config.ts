import { defineConfig } from '@playwright/test';

/**
 * repair-260918 · 测试片 S3（只读界面片，AC-9）专用 Playwright 配置。
 *
 * - baseURL = 临时 vite 5338（/api 代理目标由起 vite 时的 VITE_API_TARGET 决定：
 *   第一阶段 8081 master 后端调选择器；第二阶段 8338 本分支临时后端正式跑）。
 * - 🚫 不挂 global-setup：它会 UPDATE 共享库 user 表，本片是只读片。登录在 spec 内走 UI。
 * - 🚫 不定义 webServer：绝不碰 5174 共享 dev server。
 * - outputDir 单独放，避免与其他任务的 test-results 互相清空；证据另由 spec 复制到任务目录。
 */
export default defineConfig({
  testDir: '.', // 相对本配置文件所在的 e2e/ 目录
  testMatch: /repair260918-s3\.spec\.ts$/,
  timeout: 180_000,
  retries: 0,
  workers: 1,
  outputDir: '../test-results-repair260918-s3',
  use: {
    baseURL: 'http://localhost:5338',
    channel: 'chrome',
    viewport: { width: 1600, height: 1000 },
    trace: 'retain-on-failure',
  },
});
