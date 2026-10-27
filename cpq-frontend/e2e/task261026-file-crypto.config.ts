import { defineConfig, devices } from '@playwright/test';
import path from 'node:path';

const baseURL = process.env.PW_BASE_URL;
const backendURL = process.env.PW_BACKEND_URL;
const fakeURL = process.env.FILE_CRYPTO_FAKE_URL;

if (!baseURL || !backendURL || !fakeURL) {
  throw new Error('S-UI requires PW_BASE_URL, PW_BACKEND_URL and FILE_CRYPTO_FAKE_URL');
}
if (/:(5174|8081)(?:\/|$)/.test(baseURL) || /:(5174|8081)(?:\/|$)/.test(backendURL)) {
  throw new Error(`S-UI refuses shared ports: ${baseURL} / ${backendURL}`);
}

export default defineConfig({
  testDir: '.',
  testMatch: /(?:^|\/)task261026-file-crypto\.spec\.ts$/,
  timeout: 20 * 60_000,
  globalTimeout: 30 * 60_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  outputDir: path.resolve(process.env.PW_OUTPUT_DIR || 'test-results/task261026-file-crypto'),
  use: {
    ...devices['Desktop Chrome'],
    channel: 'chrome',
    baseURL,
    headless: true,
    viewport: { width: 1440, height: 900 },
    locale: 'zh-CN',
    trace: 'off',
    screenshot: 'only-on-failure',
    video: 'off',
    actionTimeout: 20_000,
    navigationTimeout: 60_000,
  },
});
