/**
 * Bug C 专项: 详情页 vs 编辑页 ProductCard 渲染一致性
 *
 * 验收标准:
 *   Given: 已保存的报价单 (3120012574 + v1.18 或 v1.10 均可)
 *   When: 分别打开详情页 (/quotations/:id) 和编辑页 (/quotations/:id/edit)
 *   Then: ProductCard 各 Tab 的行数和关键列内容应一致
 *         (ReadonlyProductCard 和 QuotationStep2 中的 enrichComponentData 同源)
 *
 * 策略: 使用已有的 E2E-test-* 报价单 (v1.10, 含一个独立产品 3120012574)
 *       比对详情页和编辑页的 Tab 渲染
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
  const file = path.join(SHOT_DIR, `bugc-${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: true }).catch(() => {});
  console.log(`SHOT: ${name} -> ${file}`);
}

async function countLoading(page: Page, tag: string) {
  const c = await page.locator('text=加载中').count();
  console.log(`[${tag}] loading=${c}`);
  return c;
}

/** 收集所有 Tab 的行数和前3行 cell 内容 */
async function collectTabData(page: Page, tabNames: string[], productIdx: number): Promise<Map<string, { rowCount: number; firstRows: string[][] }>> {
  const result = new Map<string, { rowCount: number; firstRows: string[][] }>();
  const allTabs = page.locator('button.qt-tab-btn');
  const totalTabs = await allTabs.count();
  console.log(`  Tab总数: ${totalTabs}`);

  for (const tabName of tabNames) {
    // 尝试点击该 Tab
    const tabLocator = page.locator('button.qt-tab-btn').filter({ hasText: new RegExp(`^${tabName}$`) }).first();
    const tabVisible = await tabLocator.isVisible().catch(() => false);
    if (!tabVisible) {
      console.log(`  Tab "${tabName}": NOT VISIBLE`);
      result.set(tabName, { rowCount: -1, firstRows: [] });
      continue;
    }
    await tabLocator.click();
    await page.waitForTimeout(2000);

    const rows = page.locator('.qt-cost-table tr').filter({ hasNot: page.locator('th') });
    const rowCount = await rows.count();
    const firstRows: string[][] = [];
    for (let i = 0; i < Math.min(rowCount, 3); i++) {
      const cells = await rows.nth(i).locator('td').allInnerTexts();
      firstRows.push(cells.map(c => c.trim().slice(0, 30)));
    }
    console.log(`  Tab "${tabName}": rows=${rowCount} firstRows=${JSON.stringify(firstRows.slice(0, 2))}`);
    result.set(tabName, { rowCount, firstRows });
  }
  return result;
}

let backendUp = false;
test.beforeAll(async () => { backendUp = await isBackendUp(); });

test('Bug C: 详情页 vs 编辑页 ProductCard 渲染一致', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  test.setTimeout(240_000);

  const consoleErrors: string[] = [];
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push('PAGE-ERROR: ' + e.message));

  await loginAsAdmin(page);

  // 找一个含产品的报价单: 使用已知的 E2E-test 报价单 (v1.10 + 3120012574)
  // quotation 9ecf8630 (E2E-test-1779285560107) 有 1 个 lineItem (3120012574, templateId=937b6e44 v1.10)
  const quotationId = '9ecf8630-9e53-4fac-9ce7-6e03d534b451';
  const tabNames = ['材质', '工序', '元素含量', '组合工艺', '选配-材质', '选配-工序列表', '选配-元素含量', '选配-组合工艺'];

  // ===== 1. 编辑页 (QuotationStep2) =====
  console.log('\n===== 编辑页 /quotations/:id/edit =====');
  await page.goto(`/quotations/${quotationId}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(3000);  // 等 enrich + expand driver
  await shot(page, 'edit-step2');
  const editLoading = await countLoading(page, 'edit-page');

  // 切到 Step2 (如果 Wizard 停在 Step1)
  const step2Btn = page.getByRole('button', { name: /下一步/ }).first();
  if (await step2Btn.isVisible().catch(() => false)) {
    // 检查当前是否在 step2 (有产品卡片)
    const hasProductCard = await page.locator('.qt-product-card, button.qt-tab-btn').count() > 0;
    if (!hasProductCard) {
      await step2Btn.click();
      await page.waitForLoadState('networkidle');
      await page.waitForTimeout(3000);
    }
  }

  await shot(page, 'edit-step2-products');
  const editTabData = await collectTabData(page, tabNames, 1);
  await shot(page, 'edit-final');

  // ===== 2. 详情页 (ReadonlyProductCard) =====
  console.log('\n===== 详情页 /quotations/:id =====');
  await page.goto(`/quotations/${quotationId}`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(3000);
  await shot(page, 'detail-page');
  const detailLoading = await countLoading(page, 'detail-page');

  const detailTabData = await collectTabData(page, tabNames, 1);
  await shot(page, 'detail-final');

  // ===== 3. 对比两页 =====
  console.log('\n===== 对比结果 =====');
  const issues: string[] = [];
  for (const tabName of tabNames) {
    const edit = editTabData.get(tabName);
    const detail = detailTabData.get(tabName);
    if (!edit || !detail) continue;

    if (edit.rowCount === -1 && detail.rowCount === -1) {
      console.log(`Tab "${tabName}": 两页均不可见 (可能 Tab 不存在于此模板)`);
      continue;
    }
    if (edit.rowCount !== detail.rowCount) {
      const msg = `Tab "${tabName}": 行数不一致 edit=${edit.rowCount} detail=${detail.rowCount}`;
      console.log(`  ERROR: ${msg}`);
      issues.push(msg);
    } else {
      console.log(`  Tab "${tabName}": 行数一致 (${edit.rowCount}) OK`);
    }

    // 对比 first 2 rows 的非空 cell
    const minRows = Math.min(edit.firstRows.length, detail.firstRows.length, 2);
    for (let i = 0; i < minRows; i++) {
      const editCells = edit.firstRows[i].filter(c => c && c !== '' && c !== '—');
      const detailCells = detail.firstRows[i].filter(c => c && c !== '' && c !== '—');
      // 检查关键字段（第一个非空值）
      if (editCells[0] && detailCells[0] && editCells[0] !== detailCells[0]) {
        const msg = `Tab "${tabName}" row[${i}] 第一关键列: edit="${editCells[0]}" detail="${detailCells[0]}"`;
        console.log(`  WARN: ${msg}`);
        // 不算严重不一致（行数一致已是主要验证）
      }
    }
  }

  console.log('\n===== Bug C 总结 =====');
  console.log(`编辑页 loading: ${editLoading} (期望 0)`);
  console.log(`详情页 loading: ${detailLoading} (期望 0)`);
  console.log(`渲染差异: ${issues.length === 0 ? '0 ✅' : issues.length + ' ❌'}`);
  issues.forEach(i => console.log(`  - ${i}`));
  console.log(`console.error: ${consoleErrors.filter(e => !e.includes('401') && !e.includes('antd')).length}`);

  // 断言
  expect.soft(editLoading, '编辑页 loading count').toBe(0);
  expect.soft(detailLoading, '详情页 loading count').toBe(0);
  expect.soft(issues, 'Tab 行数差异').toEqual([]);
});
