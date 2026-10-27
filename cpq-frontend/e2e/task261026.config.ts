// task-261026 · S-UI 专用隔离配置。
// 刻意不挂仓库默认 globalSetup：它会 UPDATE 共享 cpq_db_0724 的 user 表。
import { defineConfig, devices } from '@playwright/test';

const baseURL = process.env.PW_BASE_URL;
const backendURL = process.env.PW_BACKEND_URL;

if (!baseURL || !backendURL) {
  throw new Error(
    'task261026 S-UI 必须显式设置 PW_BASE_URL 和 PW_BACKEND_URL，且两者都必须指向临时隔离栈。',
  );
}
if (/:(5174|8081)(?:\/|$)/.test(baseURL) || /:(5174|8081)(?:\/|$)/.test(backendURL)) {
  throw new Error(
    `task261026 S-UI 拒绝共享 5174/8081：PW_BASE_URL=${baseURL} PW_BACKEND_URL=${backendURL}`,
  );
}

export default defineConfig({
  testDir: '.',
  testMatch: /(?:^|\/)task261026-material-profit-ui\.spec\.ts$/,
  timeout: 120_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  outputDir: 'test-results/task261026-material-profit-ui',
  use: {
    ...devices['Desktop Chrome'],
    channel: 'chrome',
    baseURL,
    headless: true,
    viewport: { width: 1440, height: 900 },
    locale: 'zh-CN',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
    actionTimeout: 15_000,
    navigationTimeout: 45_000,
  },
});
