/**
 * task-261030 · S-UNIT · T-03 的 global-setup：用新的 run id 调 Vitest 生成静态页。
 *
 * 为什么不在 spec 里直接 SSR：e2e/ 的 tsconfig 不启用 jsx，且 Playwright 不走 Vite 的 TSX 转换；
 * 由 Vitest（与 T-01/T-02 同一条 SSR 路径）生成，几何量的就是单测断言过文本的那份标记。
 *
 * 新鲜度：每轮生成新的 run id 写进静态页 <meta>，spec 逐页核对；旧文件、别人的文件都过不了。
 * 生成失败（Vitest 非 0 退出）→ 直接抛错，整轮不跑，绝不拿旧页面量。
 */
import { execFileSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const FRONTEND_ROOT = path.resolve(HERE, '..');
const GENERATOR = 'src/pages/quotation/task261030-costing-subtotal.static.test.tsx';

export default async function globalSetup(): Promise<void> {
  const runId = `T261030_COST_${Date.now()}_${crypto.randomBytes(4).toString('hex')}`;
  const outDir = fs.mkdtempSync(path.join(os.tmpdir(), 'task261030-costing-subtotal-static-'));
  const env = { ...process.env, T261030_STATIC_OUT: outDir, T261030_STATIC_RUN_ID: runId };

  let output = '';
  try {
    output = execFileSync('npx', ['vitest', 'run', GENERATOR], {
      cwd: FRONTEND_ROOT,
      env,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
    });
  } catch (error) {
    const failure = error as { stdout?: string; stderr?: string; status?: number };
    throw new Error(
      `T261030_COST_ static page generation failed (exit ${failure.status}):\n${failure.stdout ?? ''}\n${failure.stderr ?? ''}`,
    );
  }
  console.log(`[T261030_COST_][global-setup] vitest output:\n${output}`);

  for (const file of ['long-edit.html', 'long-readonly.html', 'default-edit.html', 'default-readonly.html']) {
    const target = path.join(outDir, file);
    if (!fs.existsSync(target)) {
      throw new Error(`T261030_COST_ generator exited 0 but did not write ${target} (skipped generator = empty verification)`);
    }
    if (!fs.readFileSync(target, 'utf8').includes(`content="${runId}"`)) {
      throw new Error(`T261030_COST_ ${target} does not carry this run id ${runId}`);
    }
  }
  process.env.T261030_STATIC_OUT = outDir;
  process.env.T261030_STATIC_RUN_ID = runId;
}
