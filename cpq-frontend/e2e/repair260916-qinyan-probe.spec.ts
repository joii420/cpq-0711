/**
 * repair-260916 · 主线亲验前的只读探测：把模板页 / 报价单新建页的结构 dump 出来。
 * 只看不改：任何非 GET 的 /api 调用都被拦截（登录除外）。
 */
import { test, expect, type Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';

const OUT = path.resolve(process.cwd(), '..', 'dev-docs', 'task-260801-页签连表公式配置优化',
  'repair-260916-小计列无法选为字段引用', '证据', '亲验', '探测');
fs.mkdirSync(OUT, { recursive: true });
const w = (n: string, s: string) => fs.writeFileSync(path.join(OUT, n), s, 'utf8');

async function readOnly(page: Page) {
  const blocked: string[] = [];
  await page.route('**/api/**', async (route) => {
    const r = route.request();
    if (r.method() === 'GET' || /\/auth\/login$/.test(r.url())) return route.continue();
    blocked.push(`${r.method()} ${r.url()}`);
    return route.abort();
  });
  return blocked;
}

async function login(page: Page) {
  const user = page.locator('input[placeholder="用户名或邮箱"]');
  await page.goto('/login');
  await expect(user).toBeVisible({ timeout: 60_000 });
  await user.fill('admin');
  await page.locator('input[placeholder="密码"]').fill('Admin@2026');
  await page.locator('button[type="submit"]').click();
  await page.waitForURL(/\/(dashboard|customers|quotations|system|products|change-password)/, { timeout: 60_000 });
  if (page.url().includes('/change-password')) await page.goto('/dashboard');
}

test('探测 · 模板页与报价单新建页', async ({ page }) => {
  const blocked = await readOnly(page);
  await login(page);

  // ── 模板列表
  await page.goto('/templates');
  await page.waitForTimeout(4000);
  w('模板页-url.txt', page.url());
  await page.screenshot({ path: path.join(OUT, '模板页.png'), fullPage: true });
  w('模板页-按钮.txt', (await page.locator('button:visible').allInnerTexts()).join('\n'));
  const rows = await page.locator('.ant-table-tbody tr:visible').allInnerTexts();
  w('模板页-行.txt', rows.join('\n---\n'));

  // 搜索「施耐德5.4模板」
  const search = page.locator('input[placeholder*="搜索"], input[type="search"]').first();
  if (await search.count()) {
    await search.fill('施耐德5.4');
    await page.keyboard.press('Enter');
    await page.waitForTimeout(3000);
    w('模板页-搜索后行.txt', (await page.locator('.ant-table-tbody tr:visible').allInnerTexts()).join('\n---\n'));
    await page.screenshot({ path: path.join(OUT, '模板页-搜索后.png'), fullPage: true });
  }

  // ── 报价单列表 / 新建入口
  await page.goto('/quotations');
  await page.waitForTimeout(4000);
  w('报价单页-按钮.txt', (await page.locator('button:visible').allInnerTexts()).join('\n'));
  await page.screenshot({ path: path.join(OUT, '报价单页.png'), fullPage: true });

  const create = page.locator('button:visible').filter({ hasText: /新\s*建|新\s*增|创\s*建/ }).first();
  if (await create.count()) {
    await create.click();
    await page.waitForTimeout(5000);
    w('新建后-url.txt', page.url());
    await page.screenshot({ path: path.join(OUT, '报价单-新建后.png'), fullPage: true });
    w('新建后-步骤条.txt', (await page.locator('.ant-steps-item').allInnerTexts()).join('\n'));
    w('新建后-标签.txt', (await page.locator('label:visible').allInnerTexts()).join('\n'));
    w('新建后-按钮.txt', (await page.locator('button:visible').allInnerTexts()).join('\n'));
  }
  w('被拦截的写请求.txt', blocked.join('\n') || '(无)');
  console.log(`[probe] blocked=${blocked.length} 输出目录=${OUT}`);
});
