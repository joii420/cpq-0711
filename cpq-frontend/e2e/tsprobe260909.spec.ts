import { test } from '@playwright/test';
import * as FT from './t260909ft.helpers';
import { openComponentByCode, switchTab } from './task260908-s2.helpers';

test('probe: open COMP-2299 and dump detail head + field type selects', async ({ page }) => {
  test.setTimeout(180_000);
  await FT.uiLogin(page);
  await openComponentByCode(page, 'COMP-2299');
  const heads = await page.evaluate(() => {
    const out: any = {};
    out.cmmDetailHead = document.querySelectorAll('.cmm-detail-head').length;
    const cands = Array.from(document.querySelectorAll('[class*="detail"],[class*="cmm-d"]'))
      .slice(0, 25).map((e) => ({ cls: (e as HTMLElement).className, txt: (e as HTMLElement).innerText?.slice(0, 60) }));
    out.candidates = cands;
    return out;
  });
  console.log('[PROBE-HEAD] ' + JSON.stringify(heads, null, 1));
  await switchTab(page, '取数配置');
  await page.waitForTimeout(4000);
  const sel = await page.evaluate(() => {
    const ftSel = Array.from(document.querySelectorAll('[data-role="field-type-select"]'));
    return {
      recipeBar: document.querySelectorAll('.svb-recipe-bar').length,
      selectedColumn: document.querySelectorAll('[data-role="selected-column"]').length,
      fieldTypeSelect: ftSel.length,
      bulk: document.querySelectorAll('[data-role="bulk-field-type"]').length,
      applyAll: document.querySelectorAll('[data-role="apply-field-type-all"]').length,
      items: ftSel.map((e) => ({
        field: (e as HTMLElement).getAttribute('data-field-name'),
        txt: (e as HTMLElement).innerText?.replace(/\s+/g, ' ').trim(),
      })),
    };
  });
  console.log('[PROBE-SVB] ' + JSON.stringify(sel, null, 1));
  await page.screenshot({ path: 'e2e/screenshots/tsprobe-2299.png', fullPage: true });
});
