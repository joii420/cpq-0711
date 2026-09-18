/**
 * repair-260916 · 亲验前只读探测 2：模板配置页（v1.9）的可用操作 + 组件 COMP-0002 公式页。
 * 只看不改：非 GET 的 /api 一律拦截。
 */
import { test, expect, type Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';

const OUT = path.resolve(process.cwd(), '..', 'dev-docs', 'task-260801-页签连表公式配置优化',
  'repair-260916-小计列无法选为字段引用', '证据', '亲验', '探测');
fs.mkdirSync(OUT, { recursive: true });
const w = (n: string, s: string) => fs.writeFileSync(path.join(OUT, n), s, 'utf8');

const TPL_V19 = 'eb761e77-9fda-4000-88cd-3f3e96f2d91f';

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

test('探测2 · 模板配置页与组件公式页', async ({ page }) => {
  const blocked: string[] = [];
  await page.route('**/api/**', async (route) => {
    const r = route.request();
    if (r.method() === 'GET' || /\/auth\/login$/.test(r.url())) return route.continue();
    blocked.push(`${r.method()} ${r.url()}`);
    return route.abort();
  });
  await login(page);

  // ── 模板配置页
  await page.goto(`/templates/${TPL_V19}`);
  await page.waitForTimeout(6000);
  await page.screenshot({ path: path.join(OUT, '模板配置页-v1.9.png'), fullPage: true });
  w('模板配置页-按钮.txt', (await page.locator('button:visible').allInnerTexts()).join('\n'));
  w('模板配置页-正文首屏.txt', (await page.locator('body').innerText()).slice(0, 4000));

  // ── 组件页：COMP-0002「物料」的公式列表
  await page.goto('/components');
  const search = page.getByPlaceholder('🔍 搜索组件名 / 编码');
  await expect(search).toBeVisible({ timeout: 30_000 });
  await search.fill('COMP-0002');
  await page.waitForTimeout(1500);
  const dirs = page.locator('.cmm-dir');
  let opened = false;
  for (let i = 0; i < await dirs.count(); i++) {
    const d = dirs.nth(i);
    const name = ((await d.locator('.cmm-dir-name').first().innerText().catch(() => '')) || '').replace(/^📁\s*/, '').trim();
    if (name === '施耐德成环检测') {
      if (!(await d.evaluate((el) => el.classList.contains('open')))) await d.locator('.cmm-dir-head').first().click();
      opened = true; break;
    }
  }
  w('组件页-目录命中.txt', String(opened));
  await page.waitForTimeout(1200);
  const card = page.locator('.cmm-card').filter({ hasText: 'COMP-0002' }).first();
  await expect(card, '应能看到 COMP-0002 卡片').toBeVisible({ timeout: 20_000 });
  await card.click();
  await page.waitForTimeout(1500);
  const tab = page.getByRole('tab', { name: '公式', exact: true });
  if (await tab.count()) { await tab.first().click(); await page.waitForTimeout(1200); }
  await page.screenshot({ path: path.join(OUT, '组件-COMP-0002-公式页.png'), fullPage: true });
  const rows = page.locator('.ant-table-tbody tr').filter({ has: page.getByRole('button', { name: '配置' }) }).filter({ visible: true });
  const texts = await rows.allInnerTexts();
  w('组件-COMP-0002-公式行.txt', texts.join('\n---\n'));
  console.log(`[probe2] 公式行数=${texts.length} blocked=${blocked.length}`);
  w('被拦截的写请求2.txt', blocked.join('\n') || '(无)');
});
