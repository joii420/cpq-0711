/** 主线亲验 —— 核价 Excel 视图（只读，不点保存、不改数据） */
import { test, expect } from '@playwright/test';
import { login } from './repair260911-helpers';

const QID = '405ab315-3ee6-43e0-8a4d-9837b762ab81';

test('主线亲验 · 核价 Excel 视图四列', async ({ page }) => {
  await login(page);
  await page.goto(`/quotations/${QID}`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(4000);

  // 切「核价单」
  const costing = page.locator('.ant-segmented-item, button', { hasText: /^\s*核价单\s*$/ }).first();
  await expect(costing, '应有「核价单」入口').toBeVisible({ timeout: 20_000 });
  await costing.click();
  await page.waitForTimeout(3000);

  // 切「Excel 视图」
  const excel = page.locator('.ant-segmented-item, button', { hasText: /Excel\s*视图/ }).first();
  await expect(excel, '应有「Excel 视图」入口').toBeVisible({ timeout: 20_000 });
  await excel.click();
  await page.waitForTimeout(5000);

  // 🚨 页面第一个 table 是「基本信息」，不是产品明细 —— 按表头定位到含「元素小计」的那张
  await expect(page.getByText('元素小计').first(), '页面应出现「元素小计」列头（否则说明没切到 Excel 视图）')
    .toBeVisible({ timeout: 20_000 });
  const table = page.locator('table').filter({ has: page.locator('th', { hasText: '元素小计' }) }).first();
  await expect(table, '应定位到含「元素小计」的表').toBeVisible({ timeout: 20_000 });
  const headers = (await table.locator('thead th').allTextContents()).map(h => h.trim());
  console.log('[亲验] 表头 =', JSON.stringify(headers));

  const rows = table.locator('tbody tr');
  const n = await rows.count();
  expect(n, '必须有数据行（防空跑）').toBeGreaterThan(0);

  const out: string[][] = [];
  for (let i = 0; i < n; i++) {
    out.push((await rows.nth(i).locator('td').allTextContents()).map(t => t.trim()));
  }
  console.log('[亲验] 行数 =', n);
  for (const r of out) console.log('[亲验] 行 =', JSON.stringify(r));
  await page.screenshot({ path: 'e2e/mainline-excel-out/MAINLINE-核价Excel视图.png', fullPage: true }).catch(()=>{});

  const flat = out.flat().join('|');
  expect(flat, 'AC-1 · 元素小计 489985').toContain('489985');
  expect(flat, 'AC-1 · 物料小计 5438667.5').toContain('5438667.5');
  expect(flat, 'AC-1 · 加工费 5.8').toContain('5.8');
  expect(flat, 'AC-1 · 单价 5438673.3').toContain('5438673.3');
});
