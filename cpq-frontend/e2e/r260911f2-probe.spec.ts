/** F-2 诊断探针：打开已建好的夹具单，看卡片能不能渲染（区分「数据问题」与「post-configure 那一帧的问题」）。 */
import { test, expect } from '@playwright/test';
import * as H from './r260911f2.helpers';

test('probe · 打开夹具单看卡片', async ({ page }) => {
  const qid = process.env.PW_QID || '24860c86-6242-4ff1-a8a0-bc2ccedfda51';
  const errs: string[] = [];
  page.on('console', (m: any) => { if (m.type() === 'error') errs.push(m.text().slice(0, 300)); });
  page.on('pageerror', (e: any) => errs.push('PAGEERROR ' + String(e).slice(0, 300)));
  await H.uiLogin(page);
  await page.goto(`/quotations/${qid}/edit`);
  await page.waitForSelector('.ant-steps-item', { timeout: 90_000 });
  const next = page.locator('button').filter({ hasText: /^\s*下\s*一\s*步\s*$/ }).first();
  await expect(next).toBeEnabled({ timeout: 40_000 });
  await next.click();
  await page.waitForTimeout(30_000);
  const cards = await page.locator('.qt-product-card').count();
  console.log(`[probe] 卡片数 = ${cards}`);
  console.log(`[probe] console errors = ${JSON.stringify(errs.slice(0, 10), null, 1)}`);
  if (cards > 0) {
    const t = await H.probeTab(page, '物料BOM');
    console.log('[probe] 页签 = ' + JSON.stringify(t.tabNames));
    console.log('[probe] 表头 = ' + JSON.stringify(t.header));
    console.log('[probe] 行 = ' + JSON.stringify(t.rows));
  } else {
    console.log('[probe] 主区文本 = ' + (await page.locator('main').innerText()).replace(/\s+/g, ' ').slice(0, 800));
  }
  await H.shot(page, 'F2-probe-打开夹具单');
});
