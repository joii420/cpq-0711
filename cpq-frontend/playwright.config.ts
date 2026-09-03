import { defineConfig } from '@playwright/test';

// task-260825：支持 PW_BASE_URL 覆盖，未设置时保持既有默认 5174。
const baseURL = process.env.PW_BASE_URL || 'http://localhost:5174';

export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  retries: 0,
  workers: 1, // 共享后端 DB，串行跑
  globalSetup: './e2e/global-setup',
  use: {
    baseURL,
    trace: 'retain-on-failure',
    // 🚨 必须走系统 google-chrome，不能用 Playwright 自带 chromium。
    // 本项目开发机是 Ubuntu 26.04，`npx playwright install chromium` 直接拒绝：
    //   "Playwright does not support chromium on ubuntu26.04-x64"
    // ⇒ 自带的 chrome-headless-shell 根本装不上，不设 channel 时每个用例都倒在
    //   "browserType.launch: Executable doesn't exist at .../chrome-headless-shell"。
    // 那种失败**长得和业务回归一模一样**（报 N failed），但一个断言都没执行 ——
    // 属于四类假绿里最隐蔽的一种：整套 E2E 是空验证，却看着像"跑过了"。
    // `e2e/global-setup.ts` 的 chromium.launch 早已硬编码 channel:'chrome'，
    // 这里补齐是与它对齐（2026-09-01 之前此处缺失，注释与实际不符）。
    channel: 'chrome',
  },
  // 🚨 5174 是全会话共享的 dev server，测试绝不能重启或抢占它。
  // 仅在未显式指定 PW_BASE_URL（即目标就是默认共享的 5174）时才让 Playwright
  // 对它做探活/按需启动（reuseExistingServer 正常情况下都会命中"已在跑"分支）；
  // 一旦显式指定了 PW_BASE_URL（例如 task-260825 两轮协议的 after 轮，指向
  // worktree 自己另起的临时 vite），说明目标根本不是 5174，此处必须完全不碰它——
  // 不定义 webServer，避免任何情况下对 5174 发起启动/健康检查。
  ...(process.env.PW_BASE_URL
    ? {}
    : {
        webServer: {
          command: 'npm run dev',
          url: 'http://localhost:5174',
          reuseExistingServer: !process.env.CI,
          timeout: 60_000,
        },
      }),
});
