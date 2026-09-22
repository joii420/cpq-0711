// task-260920 · S-2（正泰 · S-全局）R1「未计算窗口」界面子片专用 Playwright 配置
//
// 刻意**不挂** globalSetup：默认 e2e/global-setup.ts 会 UPDATE cpq_db_0724 的 "user" 表（全局状态），
// 还会探 8081 —— S-2 的后端是 worktree 临时后端 8130，前端是临时 vite 5230。
// 登录走 API（POST /api/cpq/auth/login，整轮只登一次，cookie 缓存 e2e/.auth/task260920-s2-admin.json）。
//
// 运行（在 worktree 的 cpq-frontend/ 下；R1 报批通过、临时栈已起、E-1~E-4 已确认之后）：
//   pgrep -af "node.*[p]laywright test"    # 必须为空（E-4）
//   PW_BASE_URL=http://localhost:5230 S2_BACKEND_PORT=8130 S2_BACKEND_LOG=<临时后端日志> \
//   S2_ALLOW_GENERATE=R1-APPROVED S2_RUN=R1-<时间> \
//     npx playwright test -c e2e/task260920-s2.config.ts
// 只列用例（不连任何服务，用于静态自检）：PW_BASE_URL=http://localhost:5230 npx playwright test -c e2e/task260920-s2.config.ts --list
//
// workers: 1 + fullyParallel: false 是契约：用例按 R1 窗口内的先后次序串行，且共用同一登录缓存与证据目录。
import { defineConfig, devices } from '@playwright/test';

if (!process.env.PW_BASE_URL) {
  throw new Error('S-2 必须显式给 PW_BASE_URL（worktree 临时 vite http://localhost:5230）；不许落到 5174');
}
if (/:(5174|5295)\b/.test(process.env.PW_BASE_URL) && !(process.env.S2_STEP === 'R4-AC23' && process.env.S2_AC23_OLD === '1' && /:5174\b/.test(process.env.PW_BASE_URL))) {
  throw new Error(`S-2 不许占 5174（用户/主仓）或 5295（主线亲验）：${process.env.PW_BASE_URL}`);
}
if (!process.env.S2_RUN) {
  const d = new Date(); const p = (n: number) => String(n).padStart(2, '0');
  process.env.S2_RUN = `R1-${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`;
}

export default defineConfig({
  testDir: '.',
  testMatch: /task260920-s2-.*\.spec\.ts/,
  timeout: 600_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  outputDir: 'test-results/task260920-s2',
  use: {
    ...devices['Desktop Chrome'],
    channel: 'chrome',
    baseURL: process.env.PW_BASE_URL,
    headless: true,
    viewport: { width: 1600, height: 1000 },
    locale: 'zh-CN',
    trace: 'retain-on-failure',
    video: 'off',
    screenshot: 'only-on-failure',
    actionTimeout: 15_000,
    navigationTimeout: 60_000,
  },
});
