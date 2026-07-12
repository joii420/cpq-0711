import { defineConfig } from '@playwright/test';

// 独立配置：绕开 global-setup（psql/chrome 依赖），用系统 chromium 打 5175
export default defineConfig({
  testDir: '.',
  testMatch: /tc0712-.*\.spec\.ts/,
  timeout: 120000,
  reporter: [['list']],
  use: {
    browserName: 'chromium',
    headless: true,
    viewport: { width: 1600, height: 1000 },
    launchOptions: {
      executablePath: '/usr/bin/chromium-browser',
      args: ['--no-sandbox', '--disable-dev-shm-usage', '--disable-gpu', '--disable-software-rasterizer'],
    },
  },
});
