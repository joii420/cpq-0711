import { test } from '@playwright/test';
import * as FT from './t260909ft.helpers';

test('probe: 点下一步后的 Step2 差异 + 接口错误', async ({ page }) => {
  test.setTimeout(300_000);
  await FT.uiLogin(page);
  for (const num of ['QT-20260909-0798', 'QT-20260909-0795']) {
    const qid = FT.sqlScalar(`SELECT id::text FROM quotation WHERE quotation_number='${num}'`);
    const bad: string[] = [];
    const onResp = async (r: any) => {
      if (!/\/api\/cpq\//.test(r.url())) return;
      if (r.status() >= 400) { bad.push(`${r.status()} ${r.url().replace(/^https?:\/\/[^/]+/, '')} ${(await r.text().catch(() => '')).slice(0, 250)}`); return; }
      const t = await r.text().catch(() => '');
      if (/"status"\s*:\s*"ERROR"|"code"\s*:\s*[45]/.test(t)) bad.push(`BODY-ERR ${r.url().replace(/^https?:\/\/[^/]+/, '')} ${t.slice(0, 250)}`);
    };
    page.on('response', onResp);
    await page.goto(`/quotations/${qid}/edit`);
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(2500);
    const next = page.locator('button').filter({ hasText: /下\s*一\s*步/ }).first();
    await next.click().catch(() => {});
    await page.waitForTimeout(8000);
    const info = await page.evaluate(() => ({
      cards: document.querySelectorAll('.qt-product-card').length,
      current: (document.querySelector('.ant-steps-item-active') as HTMLElement)?.innerText?.replace(/\s+/g, ' ').trim(),
      body: (document.body.innerText || '').replace(/\s+/g, ' ').slice(0, 700),
    }));
    console.log(`[STEP2 ${num}] cards=${info.cards} current=${info.current}`);
    console.log(`[STEP2 ${num}] body=${info.body}`);
    console.log(`[STEP2 ${num}] 异常响应=${JSON.stringify(bad.slice(0, 6), null, 1)}`);
    await page.screenshot({ path: `e2e/screenshots/step2b-${num}.png`, fullPage: true });
    page.off('response', onResp);
  }
});
