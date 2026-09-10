import { test, expect } from '@playwright/test';
import * as FT from './t260909ft.helpers';
import { openExistingProductDrawer, checkRow, confirmAdd, fillDrawerFilter, readDrawerRows } from './fixtures/task260909';

/** 探针：把「加产品」抽屉全过程的网络请求记录下来，定位 line item 为 0 的成因 + 拿到 API 形状。 */
test('probe: 加产品流程网络记录', async ({ page }) => {
  test.setTimeout(300_000);
  const cookie = await FT.loginApi();
  const fixture = FT.pickRenderFixture();
  const { id: qid, number } = await FT.createQuotation(cookie, fixture, 'PROBE-ADD');

  const log: string[] = [];
  page.on('request', (r) => {
    if (['POST', 'PUT', 'PATCH'].includes(r.method()) && r.url().includes('/api/cpq/')) {
      log.push(`→ ${r.method()} ${r.url().replace(/^https?:\/\/[^/]+/, '')}  body=${(r.postData() || '').slice(0, 500)}`);
    }
  });
  page.on('response', async (r) => {
    if (['POST', 'PUT', 'PATCH'].includes(r.request().method()) && r.url().includes('/api/cpq/')) {
      const t = await r.text().catch(() => '');
      log.push(`← ${r.status()} ${r.url().replace(/^https?:\/\/[^/]+/, '')}  resp=${t.slice(0, 500)}`);
    }
  });

  await FT.uiLogin(page);
  await page.goto(`/quotations/${qid}/edit`);
  await page.waitForLoadState('networkidle');
  const next = page.locator('button').filter({ hasText: /下\s*一\s*步/ }).first();
  await expect(next, '编辑页找不到「下一步」').toBeVisible({ timeout: 60_000 });
  await next.click();
  await page.waitForTimeout(4000);

  const drawer = await openExistingProductDrawer(page, true);
  await fillDrawerFilter(page, drawer, '销售料号', fixture.salesPartNo)
    .catch(async () => { await fillDrawerFilter(page, drawer, '料号', fixture.salesPartNo); });
  const rows = await readDrawerRows(drawer);
  console.log(`[PROBE-ADD] 抽屉行数=${rows.length} 前3行=${JSON.stringify(rows.slice(0, 3))}`);
  await checkRow(page, drawer, 0);
  await confirmAdd(page, drawer);
  await page.waitForTimeout(6000);

  const li = FT.sqlInt(`SELECT count(*) FROM quotation_line_item WHERE quotation_id='${qid}'`, 'probe');
  console.log(`[PROBE-ADD] 报价单 ${number} line item = ${li}`);
  console.log('[PROBE-ADD] 网络记录:\n' + log.join('\n'));
  FT.writeEvidence('98-加产品探针.txt',
    `报价单 ${number} (${qid})  line item=${li}\n抽屉行数=${rows.length}\n\n` + log.join('\n') + '\n');
});
