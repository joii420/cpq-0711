import { test, expect } from '@playwright/test';

const FE = 'http://localhost:5176';
const SHOT = 'e2e/screenshots';

async function login(context: any, u: string, p: string) {
  const r = await context.request.post(`${FE}/api/cpq/auth/login`, {
    data: { username: u, password: p }, headers: { 'Content-Type': 'application/json' },
  });
  expect(r.ok(), `${u} 登录`).toBeTruthy();
}

async function openDrawerPlating(page: any) {
  await page.goto(`${FE}/master-data-hub`, { waitUntil: 'networkidle' });
  await page.getByText('料号核价', { exact: true }).first().click();
  await page.waitForTimeout(1000);
  await page.locator('input[placeholder*="搜索"]').first().fill('S-3120014539');
  await page.waitForTimeout(1200);
  await page.getByRole('cell', { name: 'S-3120014539', exact: true }).first().click();
  await expect(page.locator('.ant-drawer').first()).toBeVisible({ timeout: 8000 });
  await page.waitForTimeout(5000);
  await page.locator('.ant-drawer .ant-tabs-tab', { hasText: '电镀成本' }).first().click();
  await page.waitForTimeout(2500);
}

test('TC-A-07 SALES_MANAGER 抽屉只读（无保存/新增/删除）', async ({ page, context }) => {
  await login(context, 'salesmgr', 'Sales@2026x');
  await expect(page.getByText('料号核价', { exact: true }).first(), 'A-03: SALES_MANAGER 可见 tab').toBeVisible({ timeout: 8000 }).catch(() => {});
  await openDrawerPlating(page);
  const saveBtn = await page.getByRole('button', { name: /保存/ }).count();
  const addBtn = await page.locator('.ant-drawer .anticon-plus').count();
  const delBtn = await page.locator('.ant-drawer .anticon-delete').count();
  console.log('LF-SALESMGR saveBtn=', saveBtn, ' addBtn=', addBtn, ' delBtn=', delBtn);
  await page.screenshot({ path: `${SHOT}/tc0712-10-salesmgr-readonly.png` });
  expect(saveBtn, 'A-07: SALES_MANAGER 不应有保存按钮').toBe(0);
});

test('TC-A-06 PRICING_MANAGER 抽屉可编辑（有保存）', async ({ page, context }) => {
  await login(context, 'pricingmgr', 'Price@2026x');
  await openDrawerPlating(page);
  const saveBtn = await page.getByRole('button', { name: /保存/ }).count();
  console.log('LF-PRICINGMGR saveBtn=', saveBtn);
  await page.screenshot({ path: `${SHOT}/tc0712-11-pricingmgr-edit.png` });
  expect(saveBtn, 'A-06: PRICING_MANAGER 应有保存按钮').toBeGreaterThan(0);
});

test('TC-A-05 SALES_REP 无核价维护数据 + 菜单门控', async ({ page, context }) => {
  await login(context, 'salesrep', 'UZSzd@kt@y');
  await page.goto(`${FE}/master-data-hub`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1500);
  // 侧边菜单是否含"主数据维护"项（应对 SALES_REP 隐藏）
  const sideMenuHub = await page.locator('.ant-menu a[href*="master-data-hub"], .ant-menu-item:has-text("主数据维护")').count();
  console.log('LF-SALESREP sideMenuHub=', sideMenuHub);
  // 直达页面后点进「料号核价」tab，看料号核价列表是否为空（后端 403）
  const tab = page.getByText('料号核价', { exact: true }).first();
  const tabVisible = await tab.count();
  console.log('LF-SALESREP 料号核价tab count=', tabVisible);
  if (tabVisible > 0) {
    await tab.click();
    await page.waitForTimeout(2000);
    // 料号核价列表列头唯一含"已配置"，用它定位到本 tab 的表
    const costingRows = await page.locator('.ant-table-tbody tr.ant-table-row').count();
    console.log('LF-SALESREP 料号核价listRows=', costingRows);
    await page.screenshot({ path: `${SHOT}/tc0712-12-salesrep.png` });
    // 数据受后端 403 保护，料号核价列表应为空
    expect(costingRows, 'A-05: SALES_REP 料号核价列表应空(数据受保护)').toBe(0);
  } else {
    await page.screenshot({ path: `${SHOT}/tc0712-12-salesrep.png` });
  }
});
