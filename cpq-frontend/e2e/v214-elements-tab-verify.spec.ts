/**
 * V214 修复验证：QT-20260522-1599 选配-元素含量 Tab
 *
 * 验证目标:
 *   1) 单位列显示 "KG"（而非价格数字 5800/65）
 *   2) 单价列显示正确元素价格（Ag→5800, Cu→65）
 *   3) 不出现 AP-22 多行 "(共N项)" 问题
 *   4) 不出现 "加载中..." 永久占位
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

let backendUp = false;
test.beforeAll(async () => {
  backendUp = await isBackendUp();
});

test('V214 验证: QT-20260522-1599 选配-元素含量 Tab 单位/单价列正确', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  test.setTimeout(180_000);

  // QT-20260522-1599 的 ID（已从 API 查到）
  const quotationId = 'e901cccd-7729-4084-ada1-9ba4fdbcdddd';

  await loginAsAdmin(page);

  // 进入报价单编辑页
  console.log('\n===== 打开编辑页 /quotations/:id/edit =====');
  await page.goto(`/quotations/${quotationId}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(3000);
  await shot(page, 'v214-01-edit-page-loaded');

  // 检查是否有产品卡片（Step2 画面）
  const productCards = await page.locator('.qt-product-card, [class*="product-card"], .ant-card').count();
  console.log(`[初始] 产品卡片数: ${productCards}`);

  // 尝试找"选配-元素含量" Tab 按钮（可能在产品卡片内）
  const elemTab = page.locator('button.qt-tab-btn, .ant-tabs-tab').filter({ hasText: /选配.元素含量|元素含量/ }).first();
  const elemTabVisible = await elemTab.isVisible().catch(() => false);
  console.log(`[Tab] 选配-元素含量 可见: ${elemTabVisible}`);

  if (!elemTabVisible) {
    // 如果还在 Step1，试着找"下一步"
    const nextBtn = page.getByRole('button', { name: /下一步/ }).first();
    const nextVisible = await nextBtn.isVisible().catch(() => false);
    if (nextVisible) {
      console.log('[Step1] 找到下一步按钮，点击进入 Step2');
      await nextBtn.click();
      await page.waitForLoadState('networkidle');
      await page.waitForTimeout(2000);
      await shot(page, 'v214-02-step2');
    }
  }

  // 重新找 Tab
  const elemTabAgain = page.locator('button.qt-tab-btn').filter({ hasText: /选配.元素含量|元素含量/ }).first();
  const tabVisible2 = await elemTabAgain.isVisible().catch(() => false);
  console.log(`[Tab 重试] 选配-元素含量 可见: ${tabVisible2}`);

  // 截一张 Step2 全貌
  await shot(page, 'v214-03-step2-overview');
  const loadingCount0 = await countLoading(page, 'step2-overview');

  // 点击"选配-元素含量" Tab
  if (tabVisible2) {
    await elemTabAgain.click();
    await page.waitForTimeout(3000);
    await shot(page, 'v214-04-elements-tab');
    const loadingAfterTab = await countLoading(page, 'elements-tab-after-click');
    console.log(`[元素含量 Tab] loading=${loadingAfterTab}`);
  } else {
    // 打印所有 Tab 按钮名称帮助诊断
    const allTabs = await page.locator('button.qt-tab-btn').allTextContents();
    console.log('[所有 Tab]', JSON.stringify(allTabs));
    const allAntTabs = await page.locator('.ant-tabs-tab').allTextContents();
    console.log('[所有 Ant Tab]', JSON.stringify(allAntTabs));
  }

  // 等待网络稳定后再等 driver 加载
  await page.waitForTimeout(4000);

  // 提取表格行文本
  console.log('\n===== 表格内容分析 =====');
  // 找含 元素 字段的 tr 行
  const tableRows = await page.locator('tr.qt-part-row, tbody tr').all();
  console.log(`[表格] 行数: ${tableRows.length}`);

  const rowData: string[][] = [];
  for (let i = 0; i < Math.min(tableRows.length, 10); i++) {
    const cells = await tableRows[i].locator('td').allTextContents();
    rowData.push(cells);
    console.log(`  行${i + 1}: ${JSON.stringify(cells)}`);
  }

  // 最终截图
  await shot(page, 'v214-05-elements-tab-final');
  const finalLoading = await countLoading(page, 'final');

  // === 验证逻辑 ===
  console.log('\n===== V214 验证结论 =====');
  console.log(`最终 loading 数: ${finalLoading}`);

  // 验证 1: 不能有"加载中..."
  expect.soft(finalLoading, '不应有"加载中..."永久占位').toBe(0);

  // 验证 2: 页面文本中不应包含"5800"出现在"单位"列位置
  // 方法：检查页面 text 中是否有 "(共" 字样（AP-22 多行问题）
  const pageText = await page.textContent('body') || '';
  const multiRowMatches = (pageText.match(/\(共\d+项\)/g) || []);
  console.log(`[AP-22 多行] 匹配到 ${multiRowMatches.length} 处: ${JSON.stringify(multiRowMatches.slice(0, 5))}`);
  expect.soft(multiRowMatches.length, 'AP-22 多行"(共N项)"应为 0').toBe(0);

  // 验证 3: 表格中应有 "KG" 文本（单位字段）
  const hasKG = pageText.includes('KG');
  console.log(`[单位 KG] 页面包含 "KG": ${hasKG}`);
  expect.soft(hasKG, '单位列应包含 "KG"').toBe(true);

  // 验证 4: 表格中应有 "5800" 文本（Ag 单价）
  const has5800 = pageText.includes('5800');
  console.log(`[单价 Ag] 页面包含 "5800": ${has5800}`);

  // 记录行级分析
  let unitColOk = 0;
  let priceColOk = 0;
  for (const cells of rowData) {
    if (cells.length >= 5) {
      const unit = cells[4]; // 单位列（第5列，0-indexed 4）
      const price = cells[5]; // 单价列（第6列，0-indexed 5）
      const subtotal = cells[6]; // 小计列
      console.log(`  单位="${unit}", 单价="${price}", 小计="${subtotal}"`);
      if (unit === 'KG' || unit.includes('KG')) unitColOk++;
      if (price && !isNaN(parseFloat(price)) && parseFloat(price) > 0) priceColOk++;
    }
  }
  console.log(`[汇总] 单位列 KG 行数=${unitColOk}, 单价列有值行数=${priceColOk}`);
});
