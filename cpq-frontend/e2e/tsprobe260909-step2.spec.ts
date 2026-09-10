import { test } from '@playwright/test';
import * as FT from './t260909ft.helpers';

test('probe: 我建的单 vs 源单 的 Step 页面差异', async ({ page }) => {
  test.setTimeout(300_000);
  await FT.uiLogin(page);
  for (const num of ['QT-20260909-0798', 'QT-20260909-0795']) {
    const qid = FT.sqlScalar(`SELECT id::text FROM quotation WHERE quotation_number='${num}'`);
    await page.goto(`/quotations/${qid}/edit`);
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(3000);
    const info = await page.evaluate(() => {
      const btns = Array.from(document.querySelectorAll('button')).map((b) => ({
        t: (b as HTMLElement).innerText.replace(/\s+/g, ''), dis: (b as HTMLButtonElement).disabled,
      })).filter((x) => x.t);
      return {
        cards: document.querySelectorAll('.qt-product-card').length,
        steps: Array.from(document.querySelectorAll('.ant-steps-item')).map((e) => (e as HTMLElement).innerText.replace(/\s+/g, ' ').trim()),
        current: (document.querySelector('.ant-steps-item-active') as HTMLElement)?.innerText?.replace(/\s+/g, ' ').trim(),
        buttons: btns.slice(0, 14),
        bodyHead: (document.body.innerText || '').replace(/\s+/g, ' ').slice(0, 400),
      };
    });
    console.log(`[STEP ${num}] ` + JSON.stringify(info, null, 1));
    await page.screenshot({ path: `e2e/screenshots/step-${num}.png`, fullPage: true });
  }
});
