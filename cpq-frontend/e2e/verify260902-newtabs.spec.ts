/**
 * task-260902 · 新页签外壳渲染取证（前端工程师自检用，非正式用例）
 * ⚠️ 共享 8081 跑的是主工作区后端，`/dataset/*` 尚未部署（404）——
 *    本用例只验「外壳按原型渲染 + 后端不可用时优雅降级（不红屏/不永久加载中）」，
 *    数据相关行为一律标为未联调。
 */
import { test, expect } from '@playwright/test';
import { loginAsAdmin } from './fixtures/auth';

test('新页签外壳 + 降级', async ({ page }) => {
  test.setTimeout(180_000);
  const pageErrors: string[] = [];
  page.on('pageerror', (e) => pageErrors.push(String(e).slice(0, 200)));

  await loginAsAdmin(page);
  await page.goto('/master-data-hub');
  await page.waitForSelector('.ant-tabs-nav .ant-tabs-tab', { timeout: 60_000 });

  for (const tabName of ['基础核价', '详细核价']) {
    await page.locator('.ant-tabs-nav .ant-tabs-tab', { hasText: tabName }).first().click();
    await page.waitForTimeout(2500);
    const pane = page.locator('.ant-tabs-tabpane-active').first();
    const headers = await pane.locator('.ant-table-thead th').allInnerTexts();
    console.log(`[${tabName}] HEADERS = ${JSON.stringify(headers.map((h) => h.trim()))}`);
    const ph = await pane.locator('input[type="text"], input.ant-input').first().getAttribute('placeholder');
    console.log(`[${tabName}] SEARCH_PLACEHOLDER = ${JSON.stringify(ph)}`);
    const paneText = (await pane.innerText()).replace(/\s+/g, ' ').trim();
    console.log(`[${tabName}] PANE_TEXT = ${JSON.stringify(paneText.slice(0, 300))}`);
    console.log(`[${tabName}] HAS_LOADING_PLACEHOLDER = ${paneText.includes('加载中')}`);
    await page.screenshot({ path: `e2e/screenshots/newtab-${tabName}.png`, fullPage: true });
  }

  // 导入抽屉外壳
  await page.locator('.ant-tabs-nav .ant-tabs-tab', { hasText: '基础核价' }).first().click();
  await page.waitForTimeout(1200);
  await page.locator('.ant-tabs-tabpane-active button', { hasText: '导入核价数据' }).first().click();
  await page.waitForSelector('.ant-drawer', { timeout: 15_000 });
  await page.waitForTimeout(1200);
  const dTitle = (await page.locator('.ant-drawer-title').innerText()).replace(/\s+/g, ' ').trim();
  console.log(`IMPORT_DRAWER_TITLE = ${JSON.stringify(dTitle)}`);
  const dBody = (await page.locator('.ant-drawer-body').innerText()).replace(/\s+/g, ' ').trim();
  console.log(`IMPORT_DRAWER_BODY = ${JSON.stringify(dBody.slice(0, 400))}`);
  const dBtns = await page.locator('.ant-drawer button').allInnerTexts();
  console.log(`IMPORT_DRAWER_BUTTONS = ${JSON.stringify(dBtns.map((b) => b.replace(/\s+/g, '')).filter(Boolean))}`);
  await page.screenshot({ path: 'e2e/screenshots/newtab-import-drawer.png', fullPage: true });

  console.log(`PAGE_ERRORS = ${JSON.stringify(pageErrors)}`);
  expect(pageErrors, '不允许有未捕获的运行时异常（红屏族）').toEqual([]);
});
