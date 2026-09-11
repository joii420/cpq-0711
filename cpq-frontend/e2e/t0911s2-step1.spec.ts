/** task-260911 · S2 · 勘察：Step1 上「客户产品编号」是否可编辑（AC-8 的用户路径）。 */
import { test } from '@playwright/test';
import { CASES, LABEL, shot, save } from './t0911s2.helpers';
import { loginAsAdmin } from './fixtures/auth';

test('PROBE · Step1 客户产品编号编辑入口', async ({ page }) => {
  test.setTimeout(600_000);
  const c = CASES['MAIN'];
  await loginAsAdmin(page);
  await page.goto(`/quotations/${c.quotationId}`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(4000);
  await page.locator('button').filter({ hasText: /^\s*编\s*辑\s*$/ }).first().click();
  await page.waitForTimeout(8000);
  await shot(page, 'PROBE-Step1');
  const info = await page.evaluate(() => {
    const norm = (s: string) => (s || '').replace(/\s+/g, ' ').trim();
    const tables = Array.from(document.querySelectorAll('table')).filter((t) => (t as HTMLElement).offsetParent !== null);
    return tables.map((t) => ({
      headers: Array.from(t.querySelectorAll('thead th')).map((th) => norm(th.textContent || '')),
      rows: Array.from(t.querySelectorAll('tbody tr')).map((tr) =>
        Array.from(tr.querySelectorAll('td')).map((td) => {
          const inp = td.querySelector('input,textarea,.ant-select-selection-item') as HTMLElement | null;
          const isInput = !!td.querySelector('input,textarea');
          return (isInput ? '[INPUT]' : '') + norm(inp ? ((inp as HTMLInputElement).value ?? inp.textContent ?? '') : td.textContent || '');
        })),
    }));
  });
  console.log(`[S2][${LABEL}] Step1 表格 = ${JSON.stringify(info, null, 1)}`);
  save('PROBE-Step1.json', info);
});
