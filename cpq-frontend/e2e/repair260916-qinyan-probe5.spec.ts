/** 只读探测：Excel 列已存文字打开时的块色随时间变化？（不保存） */
import { test, expect, type Page } from '@playwright/test';
import * as fs from 'fs'; import * as path from 'path';
import { readEditor, drawerLoc, editorOf, leftCards } from './repair260916-subtotal-suffix.helpers';
const OUT = path.resolve(process.cwd(), '..', 'dev-docs', 'task-260801-页签连表公式配置优化',
  'repair-260916-小计列无法选为字段引用', '证据', '亲验', '探测');
const w = (n: string, c: unknown) => fs.writeFileSync(path.join(OUT, n), typeof c === 'string' ? c : JSON.stringify(c, null, 2), 'utf8');
async function login(page: Page) {
  await page.goto('/login');
  await page.locator('input[placeholder="用户名或邮箱"]').fill('admin');
  await page.locator('input[placeholder="密码"]').fill('Admin@2026');
  await page.locator('button[type="submit"]').click();
  await page.waitForURL(/\/(dashboard|customers|quotations|system|products|change-password)/, { timeout: 60_000 });
}
test('探测5 · Excel 已存列的块色', async ({ page }) => {
  await login(page);
  await page.goto('/components');
  const search = page.getByPlaceholder('🔍 搜索组件名 / 编码');
  await expect(search).toBeVisible({ timeout: 30_000 });
  await search.fill('COMP-0011');
  await page.waitForTimeout(1500);
  const dirs = page.locator('.cmm-dir');
  for (let i = 0; i < await dirs.count(); i++) {
    const d = dirs.nth(i);
    const n = ((await d.locator('.cmm-dir-name').first().innerText().catch(() => '')) || '').replace(/^📁\s*/, '').trim();
    if (n === '施耐德成环检测') { if (!(await d.evaluate((el) => el.classList.contains('open')))) await d.locator('.cmm-dir-head').first().click(); break; }
  }
  await page.waitForTimeout(1200);
  await page.locator('.cmm-card').filter({ hasText: 'COMP-0011' }).first().click();
  await page.waitForTimeout(1500);
  const rows: any[] = [];
  const btns = page.locator('button:visible').filter({ hasText: /^\s*(配置公式|公式[:：])/ });
  const n = await btns.count();
  for (let i = 0; i < n; i++) {
    await btns.nth(i).click();
    const d = drawerLoc(page);
    await expect(d).toBeVisible({ timeout: 20_000 });
    await expect.poll(() => leftCards(d).count(), { timeout: 20_000 }).toBeGreaterThan(0);
    const samples: any[] = [];
    for (const wait of [0, 3000, 5000]) {
      await page.waitForTimeout(wait);
      const r = await readEditor(editorOf(d));
      samples.push({ afterMs: wait, raw: r.raw, colors: r.blocks.map((b) => `${b.display}:${b.color}`) });
    }
    const err = await d.locator('.ant-typography-danger, .ant-alert-error, [class*=danger]:visible').allInnerTexts().catch(() => []);
    rows.push({ i, label: (await btns.nth(i).innerText()).trim(), samples, err });
    console.log(`[probe5] 列${i} ${JSON.stringify(samples[samples.length - 1])} err=${JSON.stringify(err)}`);
    await d.screenshot({ path: path.join(OUT, `probe5-Excel列${i}.png`) });
    await d.locator('button').filter({ hasText: /取\s*消/ }).first().click().catch(() => {});
    await page.waitForTimeout(800);
  }
  w('probe5-Excel列块色.json', rows);
});
