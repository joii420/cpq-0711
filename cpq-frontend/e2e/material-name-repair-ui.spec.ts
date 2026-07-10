/**
 * task-0708 repair-1 材质页 UI 实跑(隔离 5175 → 8082 → cpq_db_repair1)。
 * 覆盖 TC-U1(化学式+名称两列)/U2(搜索占位)/U3(编辑抽屉化学式标签+名称可编辑)/U5(留空保存=化学式)/U6(配比仍隐藏)。
 */
import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';

const __f = fileURLToPath(import.meta.url);
const SHOT_DIR = path.join(path.dirname(__f), 'screenshots');
fs.mkdirSync(SHOT_DIR, { recursive: true });
let idx = 0;
async function shot(page: Page, name: string, fullPage = true) {
  const file = path.join(SHOT_DIR, `r1-ui-${String(++idx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage }).catch(() => {});
  console.log(`📸 ${name} → ${file}`);
}
async function apiName(page: Page, code: string): Promise<string> {
  const res = await page.request.get(`/api/cpq/material-recipes?keyword=${code}`);
  const arr = await res.json();
  const list = Array.isArray(arr) ? arr : arr.data;
  const row = list.find((x: any) => x.code === code);
  return row?.name;
}

test('repair-1 材质页:化学式+名称两列 / 编辑抽屉化学式标签+名称可编辑+留空回落 / 配比隐藏', async ({ page }) => {
  const login = await page.request.post('/api/cpq/auth/login', { data: { username: 'admin', password: 'Admin@2026' } });
  expect(login.ok()).toBeTruthy();
  await page.goto('/master-data-hub');
  if (/change-password|\/login/.test(page.url())) await page.goto('/master-data-hub');
  await page.getByRole('tab', { name: '材质' }).click();
  await expect(page.getByText('材质管理', { exact: true })).toBeVisible({ timeout: 20_000 });
  await page.waitForSelector('.ant-table-row', { timeout: 20_000 });
  await shot(page, 'list');

  // TC-U1 化学式 + 名称 两列并展
  const thead = page.locator('.ant-table-thead');
  await expect(thead.getByText('化学式', { exact: true }), '应有「化学式」列').toBeVisible();
  await expect(thead.getByText('名称', { exact: true }), '应有「名称」列').toBeVisible();
  await expect(thead.getByText('材质名称', { exact: true }), '旧「材质名称」标签应已改掉').toHaveCount(0);

  // TC-U2 搜索占位
  const search = page.getByPlaceholder('搜索 材质编号 / 化学式 / 名称 / 元素');
  await expect(search, '搜索占位应含 化学式/名称').toBeVisible();

  // TC-U3/U5 编辑抽屉:化学式标签 + 名称可编辑 + 配比隐藏
  await search.fill('00002');
  await page.waitForTimeout(700);
  // 列表 00002 化学式列=AgC3、名称列=AgC3
  await expect(page.locator('.ant-table-tbody').getByText('AgC3', { exact: true }).first()).toBeVisible();
  await page.locator('.ant-table-tbody a').filter({ hasText: '00002' }).first().click();
  const drawer = page.locator('.ant-drawer').filter({ hasText: '材质详情' }).last();
  await expect(drawer).toBeVisible({ timeout: 15_000 });
  await expect(drawer.getByText('化学式', { exact: true }), 'symbol 标签应=化学式').toBeVisible();
  await expect(drawer.getByText('名称', { exact: true }), '应有名称字段').toBeVisible();
  await expect(drawer.locator('#symbol'), '化学式回显 AgC3').toHaveValue('AgC3');
  await expect(drawer.locator('#name'), '名称可编辑').toBeEnabled();
  // 配比仍隐藏
  await expect(drawer.getByText('配比', { exact: false }), '配比应仍隐藏').toHaveCount(0);
  await page.waitForTimeout(500);
  await shot(page, 'edit-drawer-00002', false);

  // TC-U4 名称可改:改成「测试名UI」保存
  await drawer.locator('#name').fill('测试名UI');
  await page.locator('.ant-drawer-footer .ant-btn-primary').last().click();
  await page.waitForTimeout(1500);
  expect(await apiName(page, '00002'), '改名后 name=测试名UI').toBe('测试名UI');
  console.log('改名后 00002 name =', await apiName(page, '00002'));

  // TC-U5 留空保存 → 回落化学式(AgC3)
  await search.fill('00002');
  await page.waitForTimeout(700);
  await page.locator('.ant-table-tbody a').filter({ hasText: '00002' }).first().click();
  const drawer2 = page.locator('.ant-drawer').filter({ hasText: '材质详情' }).last();
  await expect(drawer2).toBeVisible({ timeout: 15_000 });
  await drawer2.locator('#name').fill('');
  await page.locator('.ant-drawer-footer .ant-btn-primary').last().click();
  await page.waitForTimeout(1500);
  expect(await apiName(page, '00002'), '留空保存后 name 回落=化学式 AgC3').toBe('AgC3');
  console.log('留空保存后 00002 name =', await apiName(page, '00002'));

  console.log('✅ repair-1 材质页 UI 全走查完成');
});
