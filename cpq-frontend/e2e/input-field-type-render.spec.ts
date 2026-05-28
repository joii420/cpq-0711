/**
 * E2E 复现 + 验证：报价单编辑页字段类型渲染 + 文本输入可编辑
 *
 * 背景 Bug：QuotationStep2 内 ProductCard 的 normalComponents 过滤掉了 SUBTOTAL 组件，
 * 其下标 (activeTab) 与底层 item.componentData 下标错位（SUBTOTAL 在最前 → 整体 +1）。
 * 而 handleRowChange / handleInputBlur / handleDeleteRow / handleAddRow / dsStateKey
 * 都直接用 activeTab 索引 item.componentData，导致编辑写入"错位的 Tab"，
 * 文本/数字输入框看起来"无法输入字符"（受控 input 的 value 永远回退）。
 *
 * 目标报价单：QT-20260527-1656（DRAFT，组合产品模板 v1.33）
 * Tab 顺序：[SUBTOTAL 选配-总成本] | 选配-材质 | 选配-工序列表 | 选配-元素含量
 *           | 选配-子配件清单(INPUT_TEXT: 子料号名称/单位) | 选配-组合工艺(INPUT_NUMBER: 工艺单价)
 *
 * 验证：
 *  1) 选配-子配件清单：文本输入框可输入字符且持久（修复前回退为空）
 *  2) 选配-组合工艺：数字输入框可输入数字且持久
 *  3) 字段类型渲染：纯 BASIC_DATA 的 Tab（选配-材质）无可编辑 input（只读 span）
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

const QUOTE_ID = '03e6cd66-3c4a-4d57-b11e-e5a03a115b58'; // QT-20260527-1656

let shotIdx = 0;
async function shot(page: Page, name: string) {
  const file = path.join(SHOT_DIR, `iftr-${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: true }).catch(() => {});
  console.log(`📸 ${name} → ${file}`);
}

/** 切到指定 Tab（精确文本匹配，避免 "选配-工序列表" 撞 "工序" 子串） */
async function clickTab(page: Page, tabName: string) {
  const tab = page.locator('button.qt-tab-btn').filter({ hasText: new RegExp(`^${tabName}$`) }).first();
  await tab.scrollIntoViewIfNeeded().catch(() => {});
  await tab.click();
  await page.waitForTimeout(1500); // 等 driver 展开 / 渲染
}

/** 取当前 active tab 表格里某列（按表头文本）在指定行的 <td> */
function cellByHeader(page: Page, headerText: string, rowIndex = 0) {
  return page.evaluate(
    ({ headerText, rowIndex }) => {
      const table = document.querySelector('table.qt-cost-table');
      if (!table) return -1;
      const ths = Array.from(table.querySelectorAll('thead th'));
      const colIdx = ths.findIndex(th => (th.textContent || '').trim() === headerText);
      return colIdx;
    },
    { headerText, rowIndex },
  );
}

let backendUp = false;
test.beforeAll(async () => { backendUp = await isBackendUp(); });

test('报价单编辑页：文本/数字输入可编辑 + 字段类型渲染正确', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');

  const consoleErrors: string[] = [];
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push('PAGE-ERROR: ' + e.message));

  // ── 1) 登录 + 打开编辑页 ──
  await loginAsAdmin(page);
  await page.goto(`/quotations/${QUOTE_ID}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1500);
  await shot(page, 'edit-step1');

  // ── 2) 下一步 → Step2「添加产品」(QuotationStep2) ──
  const next = page.getByRole('button', { name: /下一步/ }).first();
  await expect(next).toBeEnabled({ timeout: 15000 });
  await next.click();
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(2000);
  await shot(page, 'step2-product-card');

  // 等产品卡片 Tab 渲染
  await page.locator('button.qt-tab-btn').first().waitFor({ state: 'visible', timeout: 15000 });
  const tabCount = await page.locator('button.qt-tab-btn').count();
  console.log(`=== qt-tab-btn 数量: ${tabCount} (期望 5 = 6 组件 - 1 SUBTOTAL) ===`);
  for (let i = 0; i < tabCount; i++) {
    console.log(`  Tab[${i}]: "${(await page.locator('button.qt-tab-btn').nth(i).innerText()).trim()}"`);
  }

  // ── 3) 选配-子配件清单：文本输入可编辑 ──
  await clickTab(page, '选配-子配件清单');
  await shot(page, 'tab-zcj-bom');

  const nameCol = await cellByHeader(page, '子料号名称');
  const unitCol = await cellByHeader(page, '单位');
  console.log(`[子配件清单] 子料号名称 colIdx=${nameCol}, 单位 colIdx=${unitCol}`);
  expect(nameCol, '应找到「子料号名称」列').toBeGreaterThanOrEqual(0);

  // 第一行 子料号名称 单元格里的 input（INPUT_TEXT → <input type="text">）
  const firstRow = page.locator('table.qt-cost-table tbody tr').first();
  const nameInput = firstRow.locator('td').nth(nameCol).locator('input').first();
  await expect(nameInput, '子料号名称应渲染为可编辑 input').toBeVisible();
  await expect(nameInput).toHaveAttribute('type', 'text');

  const TEXT_VAL = 'E2E文本' + Date.now() % 10000;
  await nameInput.click();
  await nameInput.fill(''); // 清空
  await nameInput.pressSequentially(TEXT_VAL, { delay: 40 });
  await page.waitForTimeout(700); // 等 React 受控回写
  const nameVal = await nameInput.inputValue();
  console.log(`[子配件清单] 子料号名称 输入后 value="${nameVal}" (期望 "${TEXT_VAL}")`);
  await shot(page, 'zcj-name-typed');
  expect(nameVal, '文本输入框必须能保留用户输入（修复前因 Tab 错位回退为空）').toBe(TEXT_VAL);

  // 单位列同理（也是 INPUT_TEXT）
  if (unitCol >= 0) {
    const unitInput = firstRow.locator('td').nth(unitCol).locator('input').first();
    if (await unitInput.count() > 0) {
      await unitInput.click();
      await unitInput.fill('');
      await unitInput.pressSequentially('PCS', { delay: 40 });
      await page.waitForTimeout(500);
      const unitVal = await unitInput.inputValue();
      console.log(`[子配件清单] 单位 输入后 value="${unitVal}" (期望 "PCS")`);
      expect(unitVal).toBe('PCS');
    }
  }

  // ── 4) 选配-组合工艺：数字输入可编辑 ──
  await clickTab(page, '选配-组合工艺');
  await shot(page, 'tab-composite-process');
  const priceCol = await cellByHeader(page, '工艺单价');
  console.log(`[组合工艺] 工艺单价 colIdx=${priceCol}`);
  if (priceCol >= 0) {
    const priceRow = page.locator('table.qt-cost-table tbody tr').first();
    const priceInput = priceRow.locator('td').nth(priceCol).locator('input').first();
    await expect(priceInput, '工艺单价应渲染为可编辑 input').toBeVisible();
    await expect(priceInput).toHaveAttribute('type', 'number');
    await priceInput.click();
    await priceInput.fill('');
    await priceInput.pressSequentially('88.5', { delay: 40 });
    await page.waitForTimeout(700);
    const priceVal = await priceInput.inputValue();
    console.log(`[组合工艺] 工艺单价 输入后 value="${priceVal}" (期望 "88.5")`);
    await shot(page, 'composite-price-typed');
    expect(priceVal, '数字输入框必须能保留用户输入').toBe('88.5');
  }

  // ── 5) 字段类型渲染：逐 Tab 校验"按组件管理字段类型渲染" ──
  //   只读类型 (BASIC_DATA / DATA_SOURCE / LIST_FORMULA / FORMULA / FIXED_VALUE) → 0 可编辑 input
  //   输入类型 (INPUT_TEXT / INPUT_NUMBER) → 对应类型 input 可见
  //   注：页面有 2 张产品卡 (两个料号) 各 1 张表，统一锁定第一张产品卡的表 (.first())。
  const card1Table = () => page.locator('table.qt-cost-table').first();
  type TabExpect = { name: string; readonly: boolean; text?: number; number?: number };
  const tabExpectations: TabExpect[] = [
    { name: '选配-材质', readonly: true },                 // 全 BASIC_DATA
    { name: '选配-工序列表', readonly: true },             // BASIC_DATA + DATA_SOURCE + LIST_FORMULA + FORMULA
    { name: '选配-元素含量', readonly: true },             // BASIC_DATA + FORMULA
    { name: '选配-子配件清单', readonly: false, text: 1 }, // 含 INPUT_TEXT
    { name: '选配-组合工艺', readonly: false, number: 1 }, // 含 INPUT_NUMBER
  ];
  for (const exp of tabExpectations) {
    await clickTab(page, exp.name);
    await shot(page, `fieldtype-${exp.name}`);
    const tbl = card1Table();
    const total = await tbl.locator('tbody input').count();
    const texts = await tbl.locator('tbody input[type="text"]').count();
    const numbers = await tbl.locator('tbody input[type="number"]').count();
    console.log(`[${exp.name}] inputs total=${total} text=${texts} number=${numbers}`);
    if (exp.readonly) {
      expect(total, `${exp.name}: 只读字段类型 Tab 不应出现可编辑 input`).toBe(0);
    } else {
      if (exp.text) expect(texts, `${exp.name}: 应有 INPUT_TEXT 文本输入框`).toBeGreaterThanOrEqual(exp.text);
      if (exp.number) expect(numbers, `${exp.name}: 应有 INPUT_NUMBER 数字输入框`).toBeGreaterThanOrEqual(exp.number);
    }
  }

  console.log(`\n=== console.error 总数: ${consoleErrors.length} ===`);
  consoleErrors.slice(0, 8).forEach(e => console.log('  🔴 ' + e.slice(0, 200)));

  await shot(page, 'final');
});
