// task-261030 · S-FLOW 专用 Playwright 配置（T-05 ~ T-09）。
//
// - 自起一个 Vite（strictPort，reuseExistingServer=false）：端口被占就直接失败，绝不探到别人的实例。
// - `/api/**` 全部由 spec 里的 page.route 应答；Vite 代理目标指向死端口 127.0.0.1:9，漏网请求只会失败、不会打到任何后端。
// - 🚫 不加载仓库默认 globalSetup（它会登录 / 改共享库账号）。
//
// 环境变量：
//   T261030_TARGET=candidate|baseline   candidate（默认）= 本 worktree 分支；baseline = master 同夹具基线（AC-7 A/B、夹具自证）
//   T261030_VITE_ROOT                   被服务的 cpq-frontend 目录（baseline 时必须指向 master 检出，不得是本 worktree）
//   T261030_PORT                        默认 candidate 5292 / baseline 5293（由主线最终指定）
//   T261030_AB_DIR                      A/B 记录目录（baseline 写、candidate 读）
//   T261030_EVIDENCE_DIR                证据根目录（默认本任务目录 证据/）
//   T261030_CHROME_EXECUTABLE           可选：指定浏览器可执行文件（默认用 Playwright 自带 Chromium）
import { defineConfig, devices } from '@playwright/test';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const CANDIDATE_ROOT = path.resolve(HERE, '..');
const target = process.env.T261030_TARGET?.trim() === 'baseline' ? 'baseline' : 'candidate';
const viteRoot = path.resolve(process.env.T261030_VITE_ROOT?.trim() || CANDIDATE_ROOT);
const port = Number(process.env.T261030_PORT?.trim() || (target === 'baseline' ? 5293 : 5292));
const chromeExecutable = process.env.T261030_CHROME_EXECUTABLE?.trim();

if (!Number.isInteger(port) || [5173, 5174, 8081, 8201].includes(port)) {
  throw new Error(`T261030_COST_ refuses shared/acceptance port ${port}`);
}
if (target === 'baseline' && viteRoot === CANDIDATE_ROOT) {
  throw new Error('T261030_COST_ baseline must serve a master checkout: set T261030_VITE_ROOT to it (not this worktree)');
}
if (target === 'candidate' && viteRoot !== CANDIDATE_ROOT) {
  throw new Error(`T261030_COST_ candidate must serve this worktree (${CANDIDATE_ROOT}), got ${viteRoot}`);
}

const baseURL = `http://127.0.0.1:${port}`;
process.env.T261030_BASE_URL = baseURL;
process.env.T261030_TARGET = target;

export default defineConfig({
  testDir: '.',
  testMatch: /(?:^|\/)task261030-costing-subtotal-flow\.spec\.ts$/,
  timeout: 180_000,
  globalTimeout: 2_700_000, // 45 分钟时间盒
  expect: { timeout: 20_000 },
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  outputDir: process.env.T261030_PW_OUTPUT_DIR?.trim()
    || path.join(os.tmpdir(), `task261030-costing-subtotal-flow-${target}`),
  webServer: {
    command: `npx vite --port ${port} --strictPort --host 127.0.0.1`,
    cwd: viteRoot,
    url: `${baseURL}/`,
    reuseExistingServer: false,
    timeout: 120_000,
    stdout: 'pipe',
    stderr: 'pipe',
    env: { VITE_PORT: String(port), VITE_API_TARGET: 'http://127.0.0.1:9' },
  },
  use: {
    ...devices['Desktop Chrome'],
    ...(chromeExecutable ? { launchOptions: { executablePath: chromeExecutable } } : {}),
    baseURL,
    headless: true,
    viewport: { width: 1280, height: 720 },
    locale: 'zh-CN',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
    actionTimeout: 15_000,
    navigationTimeout: 60_000,
  },
});
