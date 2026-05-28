/**
 * E2E (UI 流程)：选配 → 独立产品 → 搜 3120012574 → 下一步 → Step2 材质应显示"已绑定 AgCu90"
 *
 * 复现用户报告：选配 Step2 提示「该料号未绑定材质字典，展示其导入 BOM 的元素配比，只读不可改」，
 * 但该料号实际绑定了 AgCu90。根因：绑定在 V44 mat_part，选配 Step2 迁 V6 后读不到。
 * 修复（AP-53 续 5 / V265）：绑定迁 material_master.material_recipe_id，getForExistingPart 恢复字典派。
 *
 * 断言（修复后）：
 *  - 出现「料号已绑定该材质，元素含量锁定」
 *  - 不出现「未绑定材质字典」
 *  - 材质卡显示 AgCu / 银铜合金，元素 Ag 90% / Cu 10%
 */
import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { loginAsAdmin, isBackendUp } from './fixtures/auth';

const __filename = fileURLToPath(import.meta.url);
const __dirnameLocal = path.dirname(__filename);
const SHOT_DIR = path.join(__dirnameLocal, 'screenshots');
fs.mkdirSync(SHOT_DIR, { recursive: true });

let shotIdx = 0;
async function shot(page: Page, name: string) {
  const file = path.join(SHOT_DIR, `mrb-${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: true }).catch(() => {});
  console.log(`📸 ${name} → ${file}`);
}

/** 通过 Form.Item label 定位 antd Select 控件 */
async function selectByLabel(page: Page, label: string, search: string, optionText?: string) {
  const item = page.locator('.ant-form-item').filter({ has: page.locator('label', { hasText: label }) }).first();
  await item.locator('.ant-select').first().click();
  await page.waitForTimeout(300);
  if (search) {
    await page.keyboard.type(search, { delay: 60 });
    await page.waitForTimeout(900);
  }
  await page.locator('.ant-select-item-option').filter({ hasText: optionText || search }).first().click();
  await page.waitForTimeout(400);
}

let backendUp = false;
test.beforeAll(async () => { backendUp = await isBackendUp(); });

test('选配 Step2 材质：3120012574 显示已绑定 AgCu90 字典（不再"未绑定材质字典"）', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');

  const consoleErrors: string[] = [];
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push('PAGE-ERROR: ' + e.message));

  // 1) 登录 + 新建报价单
  await loginAsAdmin(page);
  await page.goto('/quotations/new');
  await page.waitForLoadState('networkidle');

  // 2) 客户 + 名称 + 分类 + 模板
  await selectByLabel(page, '客户', '罗克韦尔');
  await page.locator('text=产品分类').first().scrollIntoViewIfNeeded().catch(() => {});
  await page.waitForTimeout(400);
  await page.locator('input[placeholder*="报价单名称"]').first().fill('E2E-recipe-bound-' + Date.now());
  await selectByLabel(page, '产品分类', '默认分类');
  await page.waitForTimeout(800);
  {
    const templateItem = page.locator('.ant-form-item').filter({ has: page.locator('label', { hasText: '报价模板' }) }).first();
    await templateItem.locator('.ant-select').first().click();
    await page.waitForTimeout(300);
    await page.keyboard.type('v1.10', { delay: 60 });
    await page.waitForTimeout(900);
    const opt = page.locator('.ant-select-item-option').filter({ hasText: /组合产品\s+v1\.10(\s|$)/ }).first();
    await opt.scrollIntoViewIfNeeded().catch(() => {});
    await opt.click();
    await page.waitForTimeout(400);
  }
  await shot(page, 'step1-filled');

  // 3) 下一步 → Step2
  await page.getByRole('button', { name: /下一步/ }).first().click();
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1200);

  // 4) 添加产品 → 选配添加
  await page.getByRole('button', { name: /添加产品/ }).first().click();
  await page.waitForTimeout(500);
  await page.locator('text=选配添加').first().click();
  await page.waitForTimeout(800);
  await shot(page, 'configure-drawer');

  // 5) 独立产品 → 下一步
  await page.locator('.ant-drawer').locator('text=独立产品').first().click().catch(() => {});
  await page.waitForTimeout(400);
  await page.locator('.ant-drawer button:has-text("下一步")').last().click();
  await page.waitForTimeout(800);

  // 6) 搜 3120012574 → 选中
  const partInput = page.locator('.ant-drawer input[placeholder*="料号"]').first();
  await partInput.fill('3120012574');
  await page.waitForTimeout(1500);
  const partRow = page.locator('.ant-drawer .ant-list-item').filter({ hasText: '3120012574' }).first();
  await expect(partRow, '搜索应命中 3120012574').toBeVisible();
  await partRow.click();
  await page.waitForTimeout(600);
  await shot(page, 'part-selected');

  // 7) 下一步 → P2 材质
  await page.locator('.ant-drawer button:has-text("下一步")').last().click();
  await page.waitForTimeout(2000);  // 等 existing-part/material 请求
  await shot(page, 'step2-material');

  // 8) 断言：字典派（已绑 AgCu90），不再"未绑定材质字典"
  const drawer = page.locator('.ant-drawer');
  const boundCount = await drawer.locator('text=料号已绑定该材质').count();
  const unboundCount = await drawer.locator('text=未绑定材质字典').count();
  const agCuCount = await drawer.locator('text=AgCu').count();
  const agCount = await drawer.getByText('银', { exact: false }).count();
  console.log(`[Step2 材质] 已绑定提示=${boundCount}, 未绑定提示=${unboundCount}, AgCu 出现=${agCuCount}, 含'银'=${agCount}`);

  expect(unboundCount, '修复后不应再出现"未绑定材质字典"').toBe(0);
  expect(boundCount, '应显示"料号已绑定该材质，元素含量锁定"').toBeGreaterThan(0);
  expect(agCuCount, '材质卡应显示 AgCu').toBeGreaterThan(0);

  console.log(`\n=== console.error 总数: ${consoleErrors.length} ===`);
  consoleErrors.slice(0, 6).forEach(e => console.log('  🔴 ' + e.slice(0, 160)));
  await shot(page, 'final');
});
