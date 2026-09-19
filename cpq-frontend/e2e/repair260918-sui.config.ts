// repair-260918 · 测试分片 S-UI 专用 Playwright 配置
//
// 刻意**不挂** globalSetup：默认 e2e/global-setup.ts 会 UPDATE cpq_db_0724 的 "user" 表（全局状态）。
// 本片用 API 登录（POST /api/cpq/auth/login，同一前端端口整轮只登一次，cookie 缓存在 e2e/.auth/，已被 .gitignore 忽略）。
// 仍在每个 spec 的 beforeAll 里 pgrep 确认没有别的 Playwright 在跑（派工 d 段）。
//
// workers: 1 + fullyParallel: false 是契约（testing.md / CLAUDE.md）：各 spec 共用同一登录缓存文件与同一证据目录。
//
// 运行（在 worktree 的 cpq-frontend/ 下；前端临时端口 5294 由主线给，/api 代理到共享 8081，只读）：
//   pgrep -af "node.*[p]laywright test"      # 必须为空
//   PW_BASE_URL=http://localhost:5294 npx playwright test -c e2e/repair260918-sui.config.ts
// 证伪（对照 master 旧代码，期望 AC-1/2/4/16 变红；证据写独立目录，不覆盖正式证据 —— testing.md §5.7⑤）：
//   RP0918_EXPECT_TREE=master RP0918_RUN=证伪-master PW_BASE_URL=http://localhost:5174 \
//     npx playwright test -c e2e/repair260918-sui.config.ts
import { defineConfig, devices } from '@playwright/test';

if (!process.env.PW_BASE_URL) {
  throw new Error('S-UI 必须显式给 PW_BASE_URL（worktree 临时 vite，如 http://localhost:5294）；不许默认落到 5174 上验改后代码');
}
// 一轮一个证据子目录；在主进程里定下来，worker 继承（失败后 worker 重启也不会拆成两个目录）
if (!process.env.RP0918_RUN) {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, '0');
  process.env.RP0918_RUN = `run-${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`;
}

export default defineConfig({
  testDir: '.',
  testMatch: /repair260918-.*\.spec\.ts/,
  timeout: 180_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  outputDir: 'test-results/repair260918-sui',
  use: {
    ...devices['Desktop Chrome'],
    channel: 'chrome',
    baseURL: process.env.PW_BASE_URL,
    headless: true,
    viewport: { width: 1600, height: 1000 },
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    trace: 'retain-on-failure',
    video: 'off',
    screenshot: 'only-on-failure',
    actionTimeout: 15_000,
    navigationTimeout: 60_000,
  },
});
