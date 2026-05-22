/**
 * AP-50 Bug-R1 复测：详情页 BASIC_DATA 工序 Tab 不再"加载中…"
 *
 * 验收标准:
 *   Given: 已保存的报价单 9ecf8630 (E2E-test-1779285560107, v1.10, 含 3120012574)
 *   When: 分别打开详情页和编辑页，切到 [元素含量] 和 [工序] Tab
 *   Then:
 *     - 详情页 [工序] Tab 单价列不再显示"加载中…"，应显示"—"或具体数值
 *     - 详情页 [元素含量] Tab 正常渲染
 *     - 编辑页两 Tab 同样正常
 *
 * 生成截图:
 *   ap50-detail-elements-v2.png
 *   ap50-edit-elements-v2.png
 *   ap50-detail-processes-v2.png
 *   ap50-edit-processes-v2.png
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

async function shot(page: Page, name: string) {
  const file = path.join(SHOT_DIR, `${name}.png`);
  await page.screenshot({ path: file, fullPage: true }).catch(() => {});
  console.log(`SHOT: ${name} -> ${file}`);
}

async function countLoading(page: Page, tag: string): Promise<number> {
  const c = await page.locator('text=加载中').count();
  console.log(`[${tag}] loading=${c}`);
  return c;
}

async function clickTab(page: Page, tabName: string) {
  const tabLocator = page.locator('button.qt-tab-btn').filter({ hasText: new RegExp(`^${tabName}$`) }).first();
  const visible = await tabLocator.isVisible().catch(() => false);
  if (!visible) {
    console.log(`  Tab "${tabName}": NOT VISIBLE`);
    return false;
  }
  await tabLocator.click();
  await page.waitForTimeout(2000);
  return true;
}

let backendUp = false;
test.beforeAll(async () => { backendUp = await isBackendUp(); });

test('AP-50 Bug-R1 复测: 详情页工序 Tab 不再"加载中..."', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  test.setTimeout(240_000);

  const quotationId = '9ecf8630-9e53-4fac-9ce7-6e03d534b451';

  await loginAsAdmin(page);

  // ===== 1. 详情页截图 =====
  console.log('\n===== 详情页 /quotations/:id =====');
  await page.goto(`/quotations/${quotationId}`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(4000);

  // 详情页: 元素含量 Tab
  const ok1 = await clickTab(page, '元素含量');
  if (ok1) {
    await page.waitForTimeout(2000);
    const loadCount1 = await countLoading(page, 'detail-元素含量');
    console.log(`详情页 元素含量 Tab loading=${loadCount1}`);
    await shot(page, 'ap50-detail-elements-v2');
  } else {
    // 尝试选配-元素含量
    const ok1b = await clickTab(page, '选配-元素含量');
    await page.waitForTimeout(2000);
    await shot(page, 'ap50-detail-elements-v2');
    console.log(`尝试 选配-元素含量: ok=${ok1b}`);
  }

  // 详情页: 工序 Tab
  const ok2 = await clickTab(page, '工序');
  if (ok2) {
    await page.waitForTimeout(2000);
    const loadCount2 = await countLoading(page, 'detail-工序');
    console.log(`详情页 工序 Tab loading=${loadCount2}`);
    await shot(page, 'ap50-detail-processes-v2');
  } else {
    const ok2b = await clickTab(page, '选配-工序列表');
    await page.waitForTimeout(2000);
    await shot(page, 'ap50-detail-processes-v2');
    console.log(`尝试 选配-工序列表: ok=${ok2b}`);
  }

  const detailLoadingTotal = await countLoading(page, 'detail-total');

  // ===== 2. 编辑页截图 =====
  console.log('\n===== 编辑页 /quotations/:id/edit =====');
  await page.goto(`/quotations/${quotationId}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(3000);

  // 如果停在 Step1，切到 Step2
  const hasProductCard = await page.locator('.qt-product-card, button.qt-tab-btn').count() > 0;
  if (!hasProductCard) {
    const nextBtn = page.getByRole('button', { name: /下一步/ }).first();
    if (await nextBtn.isVisible().catch(() => false)) {
      await nextBtn.click();
      await page.waitForLoadState('networkidle');
      await page.waitForTimeout(3000);
    }
  }

  // 编辑页: 元素含量 Tab
  const ok3 = await clickTab(page, '元素含量');
  if (ok3) {
    await page.waitForTimeout(2000);
    const loadCount3 = await countLoading(page, 'edit-元素含量');
    console.log(`编辑页 元素含量 Tab loading=${loadCount3}`);
    await shot(page, 'ap50-edit-elements-v2');
  } else {
    const ok3b = await clickTab(page, '选配-元素含量');
    await page.waitForTimeout(2000);
    await shot(page, 'ap50-edit-elements-v2');
    console.log(`尝试 选配-元素含量: ok=${ok3b}`);
  }

  // 编辑页: 工序 Tab
  const ok4 = await clickTab(page, '工序');
  if (ok4) {
    await page.waitForTimeout(2000);
    const loadCount4 = await countLoading(page, 'edit-工序');
    console.log(`编辑页 工序 Tab loading=${loadCount4}`);
    await shot(page, 'ap50-edit-processes-v2');
  } else {
    const ok4b = await clickTab(page, '选配-工序列表');
    await page.waitForTimeout(2000);
    await shot(page, 'ap50-edit-processes-v2');
    console.log(`尝试 选配-工序列表: ok=${ok4b}`);
  }

  const editLoadingTotal = await countLoading(page, 'edit-total');

  // ===== 3. 断言 =====
  console.log('\n===== Bug-R1 验收 =====');
  console.log(`详情页总 loading: ${detailLoadingTotal} (期望 0)`);
  console.log(`编辑页总 loading: ${editLoadingTotal} (期望 0)`);

  expect.soft(detailLoadingTotal, '详情页 loading count 期望 0 (Bug-R1 修复)').toBe(0);
  expect.soft(editLoadingTotal, '编辑页 loading count 期望 0').toBe(0);
});
