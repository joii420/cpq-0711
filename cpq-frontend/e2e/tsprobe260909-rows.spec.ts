import { test } from '@playwright/test';
import * as FT from './t260909ft.helpers';
import { openComponentByCode, switchTab } from './task260908-s2.helpers';

test('probe: 逐行展开字段类型下拉', async ({ page }) => {
  test.setTimeout(240_000);
  await FT.uiLogin(page);
  await openComponentByCode(page, 'COMP-2299');
  await switchTab(page, '取数配置');
  await page.waitForTimeout(5000);
  const sels = page.locator('[data-role="field-type-select"]');
  const n = await sels.count();
  console.log('[ROWS] 选择器个数 =', n);
  for (let i = 0; i < Math.min(n, 4); i++) {
    const s = sels.nth(i);
    const info = await s.evaluate((el) => ({
      cls: (el as HTMLElement).className,
      field: el.getAttribute('data-field-name'),
      disabled: (el as HTMLElement).className.includes('disabled'),
      rect: (() => { const r = el.getBoundingClientRect(); return { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) }; })(),
    }));
    await s.scrollIntoViewIfNeeded().catch(() => {});
    await s.click({ force: true });
    await page.waitForTimeout(1200);
    const cnt = await page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option').count();
    const anyDd = await page.locator('.ant-select-dropdown').count();
    console.log(`[ROWS] i=${i} ${JSON.stringify(info)} → 可见dropdown选项=${cnt} 全部dropdown层数=${anyDd}`);
    await page.keyboard.press('Escape');
    await page.waitForTimeout(600);
  }
});
