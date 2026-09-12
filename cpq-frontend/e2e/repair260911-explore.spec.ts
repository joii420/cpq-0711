/** repair-260911 勘察：只读，导出核价 Excel 视图的 DOM 结构与网络调用，供正式用例定位选择器。 */
import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';

const QID = '405ab315-3ee6-43e0-8a4d-9837b762ab81'; // QT-20260911-0010
const OUT = '/tmp/claude-1000/-home-joii-project-cpq/1f85d6f5-a0ee-457f-9ae5-9cc114a41830/scratchpad/r260911-explore';

async function login(page: Page) {
  await page.goto('/login');
  await page.locator('input[placeholder="用户名或邮箱"]').fill('admin');
  await page.locator('input[placeholder="密码"]').fill('Admin@2026');
  await page.locator('button[type="submit"]').click();
  await page.waitForURL(/dashboard|change-password/, { timeout: 30000 });
  if (page.url().includes('change-password')) await page.goto('/dashboard');
}

test('勘察核价 Excel 视图', async ({ page }) => {
  fs.mkdirSync(OUT, { recursive: true });
  const calls: string[] = [];
  page.on('request', r => { if (r.url().includes('/api/')) calls.push(`${r.method()} ${r.url().replace(/^https?:\/\/[^/]+/, '')}`); });

  await login(page);
  await page.goto(`/quotations/${QID}`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(2000);

  // 所有 segmented 项
  const segs = await page.locator('.ant-segmented-item').allInnerTexts();
  console.log('[SEG] ', JSON.stringify(segs));
  // 产品卡片选择器候选
  const tabs = await page.locator('.ant-tabs-tab').allInnerTexts();
  console.log('[TABS] ', JSON.stringify(tabs));

  // 切核价单
  const costing = page.locator('.ant-segmented-item').filter({ hasText: '核价单' }).first();
  await costing.click();
  await page.waitForTimeout(1500);
  const segs2 = await page.locator('.ant-segmented-item').allInnerTexts();
  console.log('[SEG-after-核价单] ', JSON.stringify(segs2));

  const excel = page.locator('.ant-segmented-item').filter({ hasText: 'Excel 视图' }).first();
  await excel.click();
  await page.waitForTimeout(3000);

  const heads = await page.locator('.ant-table-thead th').allInnerTexts();
  console.log('[TH] ', JSON.stringify(heads));
  const rows = page.locator('.ant-table-tbody tr.ant-table-row');
  const n = await rows.count();
  console.log('[ROWS] ', n);
  for (let i = 0; i < n; i++) {
    const cells = await rows.nth(i).locator('td').allInnerTexts();
    console.log(`[ROW ${i}] `, JSON.stringify(cells.map(s => s.trim())));
  }
  const tabs2 = await page.locator('.ant-tabs-tab').allInnerTexts();
  console.log('[TABS-in-excel] ', JSON.stringify(tabs2));
  fs.writeFileSync(`${OUT}/calls.txt`, calls.join('\n'));
  console.log('[CALLS]\n' + calls.filter(c => /excel|quotation|costing/i.test(c)).join('\n'));
  await page.screenshot({ path: `${OUT}/explore-full.png`, fullPage: true });
});
