import { defineConfig, devices } from '@playwright/test';
import os from 'node:os';
import path from 'node:path';

const baseURL = process.env.PW_BASE_URL?.trim();
const backendURL = process.env.PW_BACKEND_URL?.trim();
const database = process.env.PW_DB?.trim();

if (!baseURL || !backendURL || !database) {
  throw new Error(
    'T261028_VAT_FLOW_ requires PW_BASE_URL, PW_BACKEND_URL and PW_DB for an isolated stack.',
  );
}
if (/:(5173|5174)(?:\/|$)/.test(baseURL) || /:(8081|8201)(?:\/|$)/.test(backendURL)) {
  throw new Error(
    `T261028_VAT_FLOW_ refuses shared/acceptance ports: base=${baseURL} backend=${backendURL}`,
  );
}
if (['cpq_db', 'cpq_db_0724', 'cpq_db_test'].includes(database)) {
  throw new Error(`T261028_VAT_FLOW_ refuses a shared or persistent database: ${database}`);
}
if (!/^cpq_t261028_vat_[a-z0-9_]+$/i.test(database)) {
  throw new Error(`T261028_VAT_FLOW_ database must start with cpq_t261028_vat_: ${database}`);
}

export default defineConfig({
  testDir: '.',
  testMatch: /(?:^|\/)task261028-vat-subtotal-flow\.spec\.ts$/,
  timeout: 150_000,
  globalTimeout: 1_800_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  outputDir: process.env.PW_VAT_OUTPUT_DIR
    || path.join(os.tmpdir(), 'task261028-vat-subtotal-playwright'),
  use: {
    ...devices['Desktop Chrome'],
    channel: 'chrome',
    baseURL,
    headless: true,
    viewport: { width: 1280, height: 720 },
    locale: 'zh-CN',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
    actionTimeout: 15_000,
    navigationTimeout: 45_000,
  },
});
