// task-260916 test slice S-全局 (S-GL) dedicated Playwright config. DRAFT — move to cpq-frontend/e2e/ after review.
//
// Deliberately NO globalSetup: the default e2e/global-setup.ts writes the "user" table of cpq_db_0724
// (global state, testing.md §4.3 / test.md §4 item 3). This slice logs in through the UI itself.
// workers: 1 + fullyParallel: false are a CONTRACT (S-全局 runs serially, last), not a performance knob.
import { defineConfig, devices } from '@playwright/test';

const BASE = process.env.PW_BASE_URL || '';
if (!BASE) throw new Error('task260916-sgl: PW_BASE_URL is required (temporary worktree frontend, test.md §4 item 2)');
if (/:(5174|5173)\b/.test(BASE)) {
  throw new Error(`task260916-sgl must not drive the shared frontend (${BASE}); use the worktree's temporary vite port`);
}

export default defineConfig({
  testDir: '.',
  testMatch: /task260916-sgl-.*\.spec\.ts/,
  timeout: 600_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  retries: 0, // testing.md §4.1: no "retry until green"
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: BASE,
    headless: true,
    viewport: { width: 1680, height: 1050 },
    locale: 'zh-CN',
    screenshot: 'only-on-failure',
    trace: 'off',
    video: 'off', // no ffmpeg build on Ubuntu 26.04
    actionTimeout: 20_000,
    navigationTimeout: 60_000,
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'], channel: 'chrome', viewport: { width: 1680, height: 1050 } } },
  ],
});
