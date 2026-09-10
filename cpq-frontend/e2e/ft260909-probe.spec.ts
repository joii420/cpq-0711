import { test } from '@playwright/test';
import { openComponentByCode, switchTab } from './task260908-s2.helpers';
test.use({ viewport: { width: 1920, height: 1080 } });
test('measure pane widths', async ({ page }) => {
  await page.goto('/login');
  await page.fill('input[placeholder*="用户名"], input#username', 'admin');
  await page.fill('input[type="password"]', 'Admin@2026');
  await page.getByRole('button', { name: /登\s*录/ }).first().click();
  await page.waitForURL(/\/(dashboard|customers|quotations|system|products|components)/, { timeout: 30_000 });
  await page.waitForTimeout(3000);
  await openComponentByCode(page, 'COMP-2422');
  await switchTab(page, '取数配置');
  await page.waitForTimeout(5000);
  const m = await page.evaluate(() => {
    const q = (s: string) => document.querySelector(s) as HTMLElement | null;
    const box = (e: HTMLElement | null) => e ? { w: Math.round(e.getBoundingClientRect().width), sw: e.scrollWidth, x: Math.round(e.getBoundingClientRect().x) } : null;
    return {
      cols: box(q('.svb-cols')),
      left: box(q('.svb-pane.left')),
      right: box(q('.svb-pane.right')),
      sql: box(q('.svb-pane.sql')),
      rightHeader: box(q('.svb-pane.right .svb-pane-h')),
      firstRow: box(q('.svb-pane.right .svb-sel-row')),
      win: { w: window.innerWidth, h: window.innerHeight },
      drawer: box(q('.ant-drawer-body') || q('.ant-modal-body')),
    };
  });
  console.log('[MEASURE] ' + JSON.stringify(m, null, 1));
  await page.screenshot({ path: 'e2e/screenshots/ft6-measure-1920.png', fullPage: false });
});
