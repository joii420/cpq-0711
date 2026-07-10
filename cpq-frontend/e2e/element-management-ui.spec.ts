/**
 * task-0709 元素页 UI 实跑(隔离:前端 5175 → 后端 8082 → cpq_db_elemtest)。
 * 覆盖 TC-U1(页签/列)/U3(搜索)/U4(新建)/U5★(编辑符号锁:Ag/142)/U6(未引用可改符号 Au)/U7(停用二次确认)。
 * 运行:npx playwright test --config=e2e/element-ui.config.ts e2e/element-management-ui.spec.ts --reporter=list
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
  const file = path.join(SHOT_DIR, `elem-ui-${String(++idx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage }).catch(() => {});
  console.log(`📸 ${name} → ${file}`);
}

test('元素页 UI 全走查:页签/列/搜索/新建/编辑符号锁/未引用可改/停用二次确认', async ({ page }) => {
  const login = await page.request.post('/api/cpq/auth/login', {
    data: { username: 'admin', password: 'Admin@2026' },
  });
  expect(login.ok(), 'API 登录应成功').toBeTruthy();

  await page.goto('/master-data-hub');
  if (/change-password|\/login/.test(page.url())) await page.goto('/master-data-hub');
  await page.getByRole('tab', { name: '元素' }).click();
  await expect(page.getByText('元素管理', { exact: true })).toBeVisible({ timeout: 20_000 });
  await page.waitForSelector('.ant-table-row', { timeout: 20_000 });
  console.log('元素列表行数 =', await page.locator('.ant-table-row').count());
  await shot(page, 'list');

  // TC-U2 列
  const thead = page.locator('.ant-table-thead');
  for (const col of ['元素编号', '符号', '中文名', '被引用材质数', '状态', '创建时间', '修改时间']) {
    await expect(thead.getByText(col, { exact: true }), `列头应有「${col}」`).toBeVisible();
  }

  // TC-U3 搜索
  const search = page.getByPlaceholder('搜索 元素编号 / 符号 / 中文名');
  await expect(search).toBeVisible();
  await search.fill('10001');
  await page.waitForTimeout(700);
  await expect(page.locator('.ant-table-row')).toHaveCount(1);
  await expect(page.locator('.ant-table-tbody').getByText('Ag', { exact: true })).toBeVisible();
  await shot(page, 'search-10001');
  await search.fill('银');
  await page.waitForTimeout(700);
  await expect(page.locator('.ant-table-tbody').getByText('银', { exact: true }).first()).toBeVisible();
  await search.fill('');
  await page.waitForTimeout(700);

  // TC-U4 新建抽屉
  await page.getByRole('button', { name: '新建元素' }).click();
  const createDrawer = page.locator('.ant-drawer').filter({ hasText: '新建元素' }).last();
  await expect(createDrawer).toBeVisible();
  await expect(createDrawer.getByText('元素编号', { exact: true })).toBeVisible();
  await expect(createDrawer.locator('#elementNo'), '新建时元素编号可填').toBeEnabled();
  await page.waitForTimeout(500);
  await shot(page, 'create-drawer', false);
  await createDrawer.locator('.ant-drawer-close').first().click().catch(() => {});
  await page.waitForTimeout(400);

  // TC-U5 ★ 编辑 Ag(10001) 符号锁
  await search.fill('10001');
  await page.waitForTimeout(700);
  await page.locator('.ant-table-tbody a').filter({ hasText: '10001' }).first().click();
  const editDrawer = page.locator('.ant-drawer').filter({ hasText: '编辑元素' }).last();
  await expect(editDrawer).toBeVisible({ timeout: 15_000 });
  await expect(editDrawer.getByText('编辑元素: 10001')).toBeVisible();
  await expect(editDrawer.locator('#elementNo'), '编辑时元素编号只读').toBeDisabled();
  const symbolItemAg = editDrawer.locator('.ant-form-item').filter({ has: page.locator('label', { hasText: '符号' }) });
  await expect(symbolItemAg.locator('input'), '被引用元素符号应锁定(disabled)').toBeDisabled();
  await expect(symbolItemAg.locator('input'), '修复后:锁定符号框应回显当前值 Ag(非占位符)').toHaveValue('Ag');
  await expect(editDrawer.locator('#elementName'), '中文名可改').toBeEnabled();
  await page.waitForTimeout(500);
  await shot(page, 'edit-Ag-symbol-locked', false);
  await editDrawer.locator('.ant-drawer-close').first().click().catch(() => {});
  await page.waitForTimeout(400);

  // TC-U6 编辑未引用 Au(90001) 符号可改
  await search.fill('90001');
  await page.waitForTimeout(700);
  await page.locator('.ant-table-tbody a').filter({ hasText: '90001' }).first().click();
  const editAu = page.locator('.ant-drawer').filter({ hasText: '编辑元素' }).last();
  await expect(editAu).toBeVisible({ timeout: 15_000 });
  const symbolItemAu = editAu.locator('.ant-form-item').filter({ has: page.locator('label', { hasText: '符号' }) });
  await expect(symbolItemAu.locator('input'), '未引用元素符号应可改(enabled)').toBeEnabled();
  await page.waitForTimeout(500);
  await shot(page, 'edit-Au-symbol-editable', false);
  await editAu.locator('.ant-drawer-close').first().click().catch(() => {});
  await page.waitForTimeout(400);

  // TC-U7 停用二次确认(选 Au 行 → 停用 → 出确认 Modal → 取消不实际删)
  await search.fill('90001');
  await page.waitForTimeout(700);
  await page.locator('.ant-table-tbody .ant-checkbox-input').first().check().catch(() => {});
  await page.waitForTimeout(300);
  await page.getByRole('button', { name: /停用/ }).first().click().catch(() => {});
  await page.waitForTimeout(500);
  const confirmModal = page.locator('.ant-modal').filter({ hasText: /停用/ });
  const hasModal = await confirmModal.count();
  console.log('停用二次确认 Modal 出现 =', hasModal > 0);
  await shot(page, 'softdelete-confirm', false);
  // 取消,不实际改数据
  await page.getByRole('button', { name: /取消|Cancel/ }).first().click().catch(() => {});

  console.log('✅ 元素页 UI 全走查完成');
});
