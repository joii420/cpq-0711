import { defineConfig, devices } from '@playwright/test';

// 复现专用最小配置: 不跑 global-setup(psql/chrome 依赖), 直接用系统 chromium。
export default defineConfig({
  testDir: '.',
  timeout: 180000,
  fullyParallel: false,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: 'http://localhost:5174',
    trace: 'off',
    screenshot: 'off',
    actionTimeout: 30000,
    navigationTimeout: 30000,
  },
  projects: [
    {
      name: 'chromium-system',
      use: {
        ...devices['Desktop Chrome'],
        channel: undefined,
        launchOptions: {
          executablePath: '/usr/bin/chromium-browser',
          args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage'],
        },
      },
    },
  ],
});
