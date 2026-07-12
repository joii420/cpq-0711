import { test, expect } from '@playwright/test';

// task-0712 料号核价 前端冒烟（QA 交付验证）
// 目标环境: 前端 5175 → 代理后端 8082（均新代码）
const FE = 'http://localhost:5176';
const SHOT = 'e2e/screenshots';

async function apiLogin(request: any, username: string, password: string) {
  const r = await request.post(`${FE}/api/cpq/auth/login`, {
    data: { username, password },
    headers: { 'Content-Type': 'application/json' },
  });
  return r;
}

test('TC0712 料号核价 端到端冒烟', async ({ page, context }) => {
  const errors: string[] = [];
  page.on('pageerror', (e) => { errors.push('PAGEERROR: ' + e.message); console.log('LF-PAGEERROR', e.message); });
  page.on('console', (m) => { if (m.type() === 'error') { errors.push('CONSOLE: ' + m.text()); console.log('LF-CONSOLE-ERR', m.text()); } });

  // 1) API 登录 admin，cookie 注入 context
  const lr = await apiLogin(context.request, 'admin', 'Admin@2026');
  expect(lr.ok(), 'admin 登录应成功').toBeTruthy();

  // 2) 打开主数据维护
  await page.goto(`${FE}/master-data-hub`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1000);
  await page.screenshot({ path: `${SHOT}/tc0712-01-hub.png`, fullPage: true });
  const url1 = page.url();
  console.log('LF-URL after goto master-data-hub =', url1);

  // 3) 点「料号核价」tab
  const tab = page.getByText('料号核价', { exact: true }).first();
  await expect(tab, 'A: 应出现「料号核价」tab').toBeVisible({ timeout: 8000 });
  await tab.click();
  await page.waitForTimeout(1500);
  await page.screenshot({ path: `${SHOT}/tc0712-02-list.png`, fullPage: true });

  // 4) 列表: 表头 + 行数
  const headers = await page.locator('.ant-table-thead th').allInnerTexts();
  console.log('LF-LIST-HEADERS =', JSON.stringify(headers));
  const rowCount = await page.locator('.ant-table-tbody tr.ant-table-row').count();
  console.log('LF-LIST-ROWS =', rowCount);
  expect(rowCount, 'B: 列表应有数据行').toBeGreaterThan(0);

  // 5) 搜索 S-3120014539
  const search = page.locator('input[placeholder*="搜索"]').first();
  await search.fill('S-3120014539');
  await page.waitForTimeout(1200);
  const rowCount2 = await page.locator('.ant-table-tbody tr.ant-table-row').count();
  console.log('LF-SEARCH-ROWS(S-3120014539) =', rowCount2);
  await page.screenshot({ path: `${SHOT}/tc0712-03-search.png`, fullPage: true });

  // 6) 点行开抽屉
  const cell = page.getByRole('cell', { name: 'S-3120014539', exact: true }).first();
  await cell.click();
  const drawer = page.locator('.ant-drawer').first();
  await expect(drawer, 'C: 应打开抽屉').toBeVisible({ timeout: 8000 });

  // 7) 固定等待 sheets 加载，数 16 tab（不用轮询，减轻浏览器压力）
  await page.waitForTimeout(6000);
  const drawerTabs = await page.locator('.ant-drawer .ant-tabs-tab').allInnerTexts();
  console.log('LF-DRAWER-TABS count =', drawerTabs.length, ' names =', JSON.stringify(drawerTabs));
  const titleTxt = await page.locator('.ant-drawer-title').innerText().catch(() => '');
  console.log('LF-DRAWER-TITLE =', JSON.stringify(titleTxt));
  await page.screenshot({ path: `${SHOT}/tc0712-04-drawer.png` });

  // 8) 当前 tab: 版本下拉 + 表体 + 保存按钮
  const versionSelect = await page.locator('.ant-drawer .ant-select').count();
  const innerTableRows = await page.locator('.ant-drawer .ant-table-tbody tr.ant-table-row').count();
  const saveBtn = await page.getByRole('button', { name: /保存/ }).count();
  console.log('LF-DRAWER-SELECT =', versionSelect, ' innerTableRows =', innerTableRows, ' saveBtnCount =', saveBtn);

  // 9) 切到"物料BOM"（有数据）看表体
  const bomTab = page.locator('.ant-drawer .ant-tabs-tab', { hasText: '物料BOM' }).first();
  if (await bomTab.count()) {
    await bomTab.click();
    await page.waitForTimeout(2500);
    const bomRows = await page.locator('.ant-drawer .ant-table-tbody tr.ant-table-row').count();
    console.log('LF-BOM-ROWS =', bomRows);
    await page.screenshot({ path: `${SHOT}/tc0712-06-bom.png` });
  }

  console.log('LF-DONE');
  expect(drawerTabs.length, 'C: 抽屉应有 16 个 tab').toBe(16);
});
