import { defineConfig } from '@playwright/test';

/**
 * task-260901 专用 Playwright 配置。
 *
 * 🚨 <b>不挂 globalSetup</b>（2026-09-01 实测决定）：
 *    项目的 `global-setup.ts` 会去登录 alice/bob，而这两个账号在 cpq_db_0724 里不存在，
 *    每轮固定白等 2×15 s；它还有一句硬编码 `psql -d cpq_db` 打在老库上，已无意义。
 *    本套用例改为在夹具里直接 UI 登录 admin（探针实测 1.3 s），不依赖 storageState。
 *
 * <b>为什么单独一份</b>（沿用项目既有惯例：tc0712.config.ts / repair1-ui.config.ts /
 * element-ui.config.ts / repro.config.ts 都是这么做的）：
 *
 * 1. 本机**没有安装 Playwright 内置 chromium**（`chromium_headless_shell-1217` 不存在），
 *    但装了系统 Chrome。项目的 `global-setup.ts` 早就用 `channel:'chrome'` 绕过这点，
 *    而共享的 `playwright.config.ts` 的 `use` 段里没设 channel —— 于是用例本身仍去找内置包。
 *    🚫 不改共享 `playwright.config.ts`：那会影响全部 40+ 个 spec，不是本任务该动的面。
 *
 * 2. 本套用例打的是 **worktree 的临时服务**（PW_BASE_URL/PW_BACKEND_URL），
 *    8081/5174 保留给主线亲验，绝不能碰。
 *
 * 3. 单条用例可能跑满 20 分钟（1845 行大单 + 3 轮性能测量），远超共享配置的 30 s。
 *
 * 🚨 `workers: 1` 是契约不是性能参数 —— 并行 spec 会互相写同一张基准单。
 */
const baseURL = process.env.PW_BASE_URL || 'http://localhost:5199';

export default defineConfig({
  testDir: '.',
  testMatch: /task260901-.*\.spec\.ts/,
  timeout: 1_800_000,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL,
    channel: 'chrome',            // 与 global-setup.ts 保持一致：用系统 Chrome
    headless: true,
    viewport: { width: 1600, height: 1000 },
    // 🚨 关掉 trace：在 24.9 MB 响应的页面上录 snapshot 极重，是 harness 卡死的嫌疑项之一。
    //    证据改由用例自己的 archiveShot()/archiveJson() 落到任务目录 证据/ 下（更可留存）。
    trace: 'off',
    actionTimeout: 120_000,        // 大单页面上一次点击/断言的重试窗口
    navigationTimeout: 240_000,    // goto 一张 1845 行单（24.9 MB getById）
    launchOptions: {
      args: ['--no-sandbox', '--disable-dev-shm-usage', '--disable-gpu'],
    },
  },
  // 🚫 不定义 webServer：目标是 worktree 自起的临时端口，绝不对 5174 发起启动/探活。
});
