// task-261027 · S-FLOW 专用隔离配置。
// 不加载仓库默认 globalSetup：默认 setup 会改共享 cpq_db_0724 的既有账号。
import { defineConfig, devices } from '@playwright/test';

const baseURL = process.env.PW_BASE_URL;
const backendURL = process.env.PW_BACKEND_URL;
const database = process.env.PW_DB;

if (!baseURL || !backendURL || !database) {
  throw new Error(
    'T261027_RDP_FLOW_ 必须显式设置 PW_BASE_URL、PW_BACKEND_URL、PW_DB，且全部指向主线提供的隔离栈。',
  );
}
if (/:(5174|8081)(?:\/|$)/.test(baseURL) || /:(5174|8081)(?:\/|$)/.test(backendURL)) {
  throw new Error(
    `T261027_RDP_FLOW_ 拒绝共享 5174/8081：PW_BASE_URL=${baseURL} PW_BACKEND_URL=${backendURL}`,
  );
}
if (['cpq_db', 'cpq_db_0724', 'cpq_db_test'].includes(database)) {
  throw new Error(`T261027_RDP_FLOW_ 拒绝共享/固有数据库：PW_DB=${database}`);
}
if (!/^cpq_t261027_rdp_[a-z0-9_]+$/i.test(database)) {
  throw new Error(
    `T261027_RDP_FLOW_ 的一次性库必须以 cpq_t261027_rdp_ 开头，实际 PW_DB=${database}`,
  );
}

export default defineConfig({
  testDir: '.',
  testMatch: /(?:^|\/)task261027-role-display-precision-flow\.spec\.ts$/,
  timeout: 150_000,
  globalTimeout: 1_800_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  outputDir: 'test-results/task261027-role-display-precision-flow',
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
