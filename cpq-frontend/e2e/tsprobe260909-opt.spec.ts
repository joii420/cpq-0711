import { test } from '@playwright/test';
import * as FT from './t260909ft.helpers';
import { openComponentByCode, switchTab } from './task260908-s2.helpers';

test('probe: 字段类型选项 DOM 结构', async ({ page }) => {
  test.setTimeout(180_000);
  await FT.uiLogin(page);
  await openComponentByCode(page, 'COMP-2299');
  await switchTab(page, '取数配置');
  await page.waitForTimeout(5000);
  await page.locator('[data-role="field-type-select"]').first().click();
  await page.waitForTimeout(900);
  const opts = await page.evaluate(() => {
    const dd = document.querySelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden)');
    if (!dd) return { found: false };
    return {
      found: true,
      count: dd.querySelectorAll('.ant-select-item-option').length,
      html: Array.from(dd.querySelectorAll('.ant-select-item-option')).map((e) => (e as HTMLElement).innerHTML),
      text: Array.from(dd.querySelectorAll('.ant-select-item-option')).map((e) => (e as HTMLElement).innerText),
      values: Array.from(dd.querySelectorAll('.ant-select-item-option')).map((e) => e.getAttribute('title') || e.getAttribute('data-value') || ''),
    };
  });
  console.log('[OPT] ' + JSON.stringify(opts, null, 1));
  await FT.shot(page, 'AC-1-选项展开态-实拍');
});
