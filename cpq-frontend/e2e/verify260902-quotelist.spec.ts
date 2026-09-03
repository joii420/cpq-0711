/**
 * task-260902 · F-7 报价单管理工具栏取证（前端工程师自检用，非正式用例）
 * AC-35：工具栏依次为 导入历史 / 从基础数据导入 / 导入报价数据 / 新建报价单；
 *        点「从基础数据导入」打开的仍是**原有**抽屉（AC-43：现有按钮与抽屉零改动）。
 */
import { test, expect } from '@playwright/test';
import { loginAsAdmin } from './fixtures/auth';

test('AC-35 · 报价单管理工具栏', async ({ page }) => {
  test.setTimeout(180_000);
  const errs: string[] = [];
  page.on('pageerror', (e) => errs.push(String(e).slice(0, 200)));
  await loginAsAdmin(page);
  await page.goto('/quotations');
  await page.waitForSelector('.ant-table-thead th', { timeout: 60_000 });
  await page.waitForTimeout(1500);

  const btns = (await page.locator('.ant-card-body button').allInnerTexts())
    .map((b) => b.replace(/\s+/g, '')).filter(Boolean);
  console.log('AC35_TOOLBAR_BUTTONS =', JSON.stringify(btns.slice(0, 8)));
  await page.screenshot({ path: 'e2e/screenshots/quotelist-toolbar.png', fullPage: true });

  // 旧按钮：打开的仍是原有抽屉（标题取自 QuoteBasicDataImportV6Drawer）
  await page.locator('button', { hasText: '从基础数据导入' }).first().click();
  await page.waitForSelector('.ant-drawer', { timeout: 15_000 });
  await page.waitForTimeout(1200);
  console.log('AC35_OLD_DRAWER_TITLE =', JSON.stringify((await page.locator('.ant-drawer-title').innerText()).replace(/\s+/g, ' ').trim()));
  await page.keyboard.press('Escape');
  await page.waitForTimeout(1200);

  // 新按钮：打开 dataset=quote 的新抽屉
  await page.locator('button', { hasText: '导入报价数据' }).first().click();
  await page.waitForSelector('.ant-drawer', { timeout: 15_000 });
  await page.waitForTimeout(1200);
  console.log('AC35_NEW_DRAWER_TITLE =', JSON.stringify((await page.locator('.ant-drawer-title').innerText()).replace(/\s+/g, ' ').trim()));
  console.log('AC35_NEW_DRAWER_BODY =', JSON.stringify((await page.locator('.ant-drawer-body').innerText()).replace(/\s+/g, ' ').trim().slice(0, 200)));
  await page.screenshot({ path: 'e2e/screenshots/quotelist-new-drawer.png', fullPage: true });

  console.log('PAGE_ERRORS =', JSON.stringify(errs));
  expect(errs).toEqual([]);
});
