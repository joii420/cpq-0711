import { Page, BrowserContext } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

/**
 * 后端接口地址（直连，不经过前端代理）。
 * task-0713 E2E 验收：支持 PW_BACKEND_URL 覆盖（临时后端场景），未设置时保持既有默认。
 */
const BACKEND_URL = process.env.PW_BACKEND_URL || 'http://localhost:8081';

/** 预存 storageState 文件目录（由 global-setup.ts 在测试前创建） */
const AUTH_DIR = path.join(__dirname, '..', '.auth');

/**
 * 检查后端是否在运行。若不在运行，返回 false。
 * 测试文件应在 beforeAll 中调用此函数，若返回 false 则整套 skip。
 */
export async function isBackendUp(): Promise<boolean> {
  try {
    const res = await fetch(`${BACKEND_URL}/api/cpq/health`, { signal: AbortSignal.timeout(3000) });
    return res.ok;
  } catch {
    return false;
  }
}

/**
 * 通过 UI 页面完成登录，等待跳转到 /dashboard。
 * 使用前请确保后端已运行。
 *
 * 优先使用 storageState（global-setup.ts 预存的 session cookie），
 * 避免重复 UI 登录触发 Redis rate limiter（30次/分/IP）。
 * 若 storageState 文件不存在则回退到 UI 登录。
 *
 * 修复：如果首次登录跳转到 /change-password，自动跳转到 /dashboard。
 */
export async function loginAs(page: Page, username: string, password: string) {
  // 尝试使用预存 storageState（由 global-setup 准备）
  const stateFile = path.join(AUTH_DIR, `${username}.json`);
  if (fs.existsSync(stateFile)) {
    // 通过 context 加载 storageState（Page 没有 addCookies 方法，需要用底层 context）
    const ctx: BrowserContext = page.context();
    const state = JSON.parse(fs.readFileSync(stateFile, 'utf-8'));
    // 清除现有 cookies 再添加保存的 session
    await ctx.clearCookies();
    if (state.cookies && state.cookies.length > 0) {
      await ctx.addCookies(state.cookies);
    }
    // 加载 localStorage 等
    if (state.origins && state.origins.length > 0) {
      for (const origin of state.origins) {
        if (origin.localStorage && origin.localStorage.length > 0) {
          await page.goto(origin.origin);
          for (const item of origin.localStorage) {
            await page.evaluate(({ key, value }) => {
              localStorage.setItem(key, value);
            }, item);
          }
        }
      }
    }
    // 导航到 dashboard 验证 session 有效
    await page.goto('/dashboard');
    await page.waitForLoadState('networkidle');
    if (!page.url().includes('/login') && !page.url().includes('/change-password')) {
      return; // session 有效，直接返回
    }
    // session 失效，回退到 UI 登录
  }

  // UI 登录（fallback 或 storageState 不存在）
  await page.goto('/login');
  await page.locator('input[placeholder="用户名或邮箱"]').fill(username);
  await page.locator('input[placeholder="密码"]').fill(password);
  await page.locator('button[type="submit"]').click();
  // 等待跳转（接受 change-password 页面，稍后处理）
  await page.waitForURL(/\/(dashboard|customers|quotations|system|products|change-password)/, { timeout: 15_000 });
  // 如果被导向到修改密码页（is_first_login），直接跳转到 dashboard
  if (page.url().includes('/change-password')) {
    await page.goto('/dashboard');
    await page.waitForLoadState('networkidle');
  }
}

/**
 * 以 admin 账号登录（种子数据：admin / Admin@2026）
 */
export async function loginAsAdmin(page: Page) {
  return loginAs(page, 'admin', 'Admin@2026');
}

/**
 * 以 alice 账号登录（种子数据 V68：alice / Admin@2026，角色 SALES_REP）
 */
export async function loginAsAlice(page: Page) {
  return loginAs(page, 'alice', 'Admin@2026');
}

/**
 * 以 bob 账号登录（种子数据 V68：bob / Admin@2026，角色 SALES_MANAGER）
 */
export async function loginAsBob(page: Page) {
  return loginAs(page, 'bob', 'Admin@2026');
}
