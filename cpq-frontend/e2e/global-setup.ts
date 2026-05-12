/**
 * Playwright 全局 Setup
 *
 * 在所有测试之前执行：
 * 1. 检查后端是否可用
 * 2. 若后端可用，为 admin / alice / bob 各登录一次，保存 storageState
 * 3. 重置 DB 中的 Rate Limiter 锁（cleared by SQL，防止 30次/分/IP 被触发）
 *
 * storageState 文件保存在 .auth/ 目录，供 auth.ts 的 loginAsXxx 复用。
 * 这样每个测试不需要重新走 UI 登录，避免 Redis rate limiter 触发。
 */

import { chromium, FullConfig } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { execSync } from 'child_process';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const BASE_URL = 'http://localhost:5174';
const BACKEND_URL = 'http://localhost:8081';
const AUTH_DIR = path.join(__dirname, '.auth');

async function isBackendAvailable(): Promise<boolean> {
  try {
    const res = await fetch(`${BACKEND_URL}/api/cpq/health`, {
      signal: AbortSignal.timeout(3000),
    });
    return res.ok;
  } catch {
    return false;
  }
}

async function unlockAccounts() {
  try {
    execSync(
      `PGPASSWORD=joii5231 "/c/Program Files/PostgreSQL/16/bin/psql" -U postgres -h 127.0.0.1 -d cpq_db -c "UPDATE \\"user\\" SET locked_until=NULL, failed_login_attempts=0, is_first_login=false WHERE username IN ('admin','alice','bob');"`,
      { stdio: 'pipe', shell: '/bin/bash' }
    );
  } catch (e) {
    console.warn('[global-setup] DB unlock skipped:', e);
  }
}

async function flushRedisRateLimiter() {
  try {
    execSync(
      `redis-cli -h 127.0.0.1 -p 6379 -a joii5231 --no-auth-warning KEYS "cpq:rate:login:*" | xargs -r redis-cli -h 127.0.0.1 -p 6379 -a joii5231 --no-auth-warning DEL`,
      { stdio: 'pipe', shell: '/bin/bash' }
    );
  } catch {
    // redis-cli 可能不在 PATH，忽略
  }
}

async function saveStorageState(username: string, password: string, stateFile: string) {
  const browser = await chromium.launch();
  const context = await browser.newContext({ baseURL: BASE_URL });
  const page = await context.newPage();

  try {
    await page.goto('/login');
    await page.locator('input[placeholder="用户名或邮箱"]').fill(username);
    await page.locator('input[placeholder="密码"]').fill(password);
    await page.locator('button[type="submit"]').click();
    await page.waitForURL(/\/(dashboard|customers|quotations|system|products|change-password)/, {
      timeout: 15_000,
    });
    if (page.url().includes('/change-password')) {
      await page.goto('/dashboard');
      await page.waitForLoadState('networkidle');
    }
    await context.storageState({ path: stateFile });
    console.log(`[global-setup] Saved auth state for ${username} → ${stateFile}`);
  } catch (e) {
    console.warn(`[global-setup] Could not save auth state for ${username}:`, e);
  } finally {
    await browser.close();
  }
}

export default async function globalSetup(_config: FullConfig) {
  const backendUp = await isBackendAvailable();
  if (!backendUp) {
    console.log('[global-setup] Backend not available, skipping auth setup');
    return;
  }

  // 1. 解锁 DB 账号 + 清 Redis rate limiter key
  await unlockAccounts();
  await flushRedisRateLimiter();

  // 2. 创建 .auth 目录
  if (!fs.existsSync(AUTH_DIR)) {
    fs.mkdirSync(AUTH_DIR, { recursive: true });
  }

  // 3. 为三个账号各保存一次 storageState
  await saveStorageState('admin', 'Admin@2026', path.join(AUTH_DIR, 'admin.json'));
  await saveStorageState('alice', 'Admin@2026', path.join(AUTH_DIR, 'alice.json'));
  await saveStorageState('bob', 'Admin@2026', path.join(AUTH_DIR, 'bob.json'));
}
