/**
 * dataset-plating-scheme.spec.ts
 *
 * task-260902 · S-9「主数据维护第 7 个页签：电镀方案」· L4 E2E
 * 覆盖 H 组 AC-48 ~ AC-51（TH-01 ~ TH-04）。
 *
 * 判据来源：需求文档.md ④ H 组原文 + 原型图/电镀方案-页签.html。🚫 不读实现源码。
 *
 * 本页签的三条设计约束（AC 原文点名，逐条对应下面的用例）：
 *   1. 第 7 个页签，排在最后                      → TH-01 / AC-48
 *   2. 数据集下拉切换 → 列定义随之变（10 列 ↔ 8 列）→ TH-02 / AC-49
 *   3. 只读，页面上不存在任何写入口              → TH-04 / AC-51
 */

import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { loginAsAdmin, isBackendUp } from './fixtures/auth';

const __filenameLocal = fileURLToPath(import.meta.url);
const __dirnameLocal = path.dirname(__filenameLocal);

/** 🚨 归档到任务目录，不是 test-results/（后者每轮开跑前被清空 = 不算证据）。 */
const SHOT_DIR = path.resolve(
  __dirnameLocal,
  '../../dev-docs/task-260902-报价与核价建表与导入方案新规范/证据/e2e'
);
fs.mkdirSync(SHOT_DIR, { recursive: true });

let shotIdx = 200;
async function shot(page: Page, name: string) {
  const file = path.join(SHOT_DIR, `${++shotIdx}-${name}.png`);
  await page.screenshot({ path: file, fullPage: true }).catch(() => {});
  console.log(`[screenshot] ${name} => ${file}`);
}

/** AC-48：7 个页签，「电镀方案」在最后。 */
const HUB_TABS_7 = ['料号核价', '材质', '元素', '工序', '基础核价', '详细核价', '电镀方案'];

/** AC-49：数据集 =「报价」时的 10 列，顺序即判据。 */
const QUOTE_COLUMNS = [
  '方案编号', '版本', '项次', '电镀元素名称',
  '元素单价来源网站网址', '元素单价来源网站名称', '元素单价抓取规则',
  '电镀面积（cm2）', '镀层厚度（μm）', '电镀要求',
];

/** AC-49：数据集 =「详细核价」时的 8 列。 */
const COST_DETAIL_COLUMNS = [
  '方案编号', '版本', '项次', '电镀元素名称',
  '电镀面积（cm2）', '镀层厚度（μm）', '电镀要求', '密度（g/cm3)',
];

/** AC-51 的说明文案。 */
const READONLY_NOTICE = '电镀方案为导入维护，如需修改请通过「导入报价数据」/「导入核价数据」重新导入';

let backendUp = false;
test.beforeAll(async () => {
  backendUp = await isBackendUp();
  if (!backendUp) {
    console.warn('[plating-scheme] 后端不可用 —— 全套 skip。🚫 skip 不是「通过」，报告里记「未验证」。');
  }
});
test.beforeEach(async ({ page }) => {
  test.skip(!backendUp, '后端未启动 —— 记为「未验证」');
  await loginAsAdmin(page);
});

// ═══════════════════════════════════════════════════════════════════

async function openPlatingTab(page: Page) {
  await page.goto('/master-data-hub');
  await page.waitForLoadState('networkidle');

  const body = (await page.locator('body').textContent()) ?? '';
  expect(body, '主数据维护页崩溃（React ErrorBoundary），后续断言无从执行')
    .not.toContain('Unexpected Application Error');

  await page.locator('.ant-tabs-tab', { hasText: '电镀方案' }).first().click();
  await page.waitForTimeout(1000);
  await page.waitForFunction(() => !document.querySelector('.ant-spin-spinning'), { timeout: 15_000 })
    .catch(() => {});
}

/** 取表格表头的列名数组。 */
async function headerColumns(page: Page): Promise<string[]> {
  return (await page.locator('.ant-table-thead th').allTextContents()).map((t) => t.trim()).filter(Boolean);
}

/** 切数据集下拉。⚠️ 坑：下拉是虚拟滚动，选项要先滚到可见；🚫 不拿 .ant-select-content 采样。 */
async function switchDataset(page: Page, label: string) {
  await page.locator('.ant-select').first().click();
  const opt = page.locator('.ant-select-item-option', { hasText: label }).first();
  await opt.scrollIntoViewIfNeeded();
  await opt.click();
  await page.waitForTimeout(1200);
  await page.waitForFunction(() => !document.querySelector('.ant-spin-spinning'), { timeout: 15_000 })
    .catch(() => {});
}

// ═══════════════════════════════════════════════════════════════════
// AC-48 第 7 个页签
// ═══════════════════════════════════════════════════════════════════

test('TH-01 / AC-48：主数据维护共 7 个页签，「电镀方案」排在最后', async ({ page }) => {
  await page.goto('/master-data-hub');
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1200);

  const tabs = (await page.locator('.ant-tabs > .ant-tabs-nav .ant-tabs-tab').allTextContents())
    .map((t) => t.trim());
  console.log('[TH-01] 实际页签:', tabs);

  expect(tabs.length, `AC-48 期望 7 个页签，实际 ${tabs.length}：${JSON.stringify(tabs)}`).toBe(7);
  expect(tabs, 'AC-48 页签名称/顺序与 原型图/电镀方案-页签.html 不符').toEqual(HUB_TABS_7);
  expect(tabs[6], 'AC-48：「电镀方案」必须排在最后').toBe('电镀方案');

  await shot(page, 'ac48-7-tabs');
});

// ═══════════════════════════════════════════════════════════════════
// AC-49 数据集下拉 → 列定义随之变
// ═══════════════════════════════════════════════════════════════════

test('TH-02 / AC-49：默认「报价」10 列；切「详细核价」变 8 列（列由后端下发，两组列名不同）', async ({ page }) => {
  await openPlatingTab(page);

  // 默认选「报价」
  const selected = (await page.locator('.ant-select-selection-item').first().textContent())?.trim() ?? '';
  console.log('[TH-02] 数据集下拉默认值:', selected);
  expect(selected, 'AC-49：数据集下拉默认应选「报价」').toBe('报价');

  const quoteCols = await headerColumns(page);
  console.log('[TH-02] 报价列:', quoteCols);
  expect(quoteCols.length, `AC-49：报价应为 10 列，实际 ${quoteCols.length}：${JSON.stringify(quoteCols)}`)
    .toBe(10);
  expect(quoteCols, 'AC-49：报价列名/顺序与 AC 原文不符').toEqual(QUOTE_COLUMNS);
  await shot(page, 'ac49-quote-10cols');

  // 切到「详细核价」
  await switchDataset(page, '详细核价');
  const detailCols = await headerColumns(page);
  console.log('[TH-02] 详细核价列:', detailCols);
  expect(detailCols.length, `AC-49：详细核价应为 8 列，实际 ${detailCols.length}：${JSON.stringify(detailCols)}`)
    .toBe(8);
  expect(detailCols, 'AC-49：详细核价列名/顺序与 AC 原文不符').toEqual(COST_DETAIL_COLUMNS);

  // 🚨 「列定义由后端下发、前端不得写死」的可观测判据：两套列必须真的不同。
  //    若前端写死一套列，切数据集后列不会变 —— 那时上面两条里必有一条已经红了，
  //    这里再补一条直指根因的断言，避免读报告的人以为只是「少了一列」。
  expect(detailCols, 'AC-49 🚨 切数据集后列没变 ⇒ 列定义被前端写死了（AC 要求由后端按数据集下发）')
    .not.toEqual(quoteCols);
  expect(detailCols, 'AC-49：详细核价不该有「元素单价来源网站网址」').not.toContain('元素单价来源网站网址');
  expect(detailCols, 'AC-49：详细核价应有「密度（g/cm3)」').toContain('密度（g/cm3)');

  await shot(page, 'ac49-detail-8cols');
});

// ═══════════════════════════════════════════════════════════════════
// AC-50 导入后列表内容 + 总行数
// ═══════════════════════════════════════════════════════════════════

test('TH-03 / AC-50：报价数据集列表含 A0001/2000/1/Ni 那一行；总行数 = ds_quote_plating_scheme 的 count', async ({ page }) => {
  await openPlatingTab(page);

  // 🚨 总行数判据取「同一时刻的接口基准」而不是写死数字 —— 共享库在漂移（test.md §0.3）。
  const resp = await page.request.get('/api/cpq/dataset/quote/plating-schemes?page=0&size=20');
  expect(resp.status(), 'AC-50 前置：电镀方案列表接口不可用').toBe(200);
  const total = (await resp.json())?.data?.total;
  expect(typeof total, 'AC-50：接口未返回 total').toBe('number');
  expect(total, 'AC-50：列表为空 ⇒ 内容断言会空跑（testing.md §3.3）。需先导入报价模板')
    .toBeGreaterThan(0);

  const paginationText = (await page.locator('.ant-pagination-total-text').first().textContent()) ?? '';
  console.log(`[TH-03] 接口 total=${total}，分页文案="${paginationText}"`);
  expect(paginationText, `AC-50：列表总数应等于 ${total}`).toContain(String(total));

  // AC-50 点名的那一行，逐格比对
  const row = page.locator('.ant-table-row').filter({ hasText: 'A0001' }).filter({ hasText: 'Ni' }).first();
  await expect(row, 'AC-50：列表里找不到 A0001 / Ni 这一行').toBeVisible({ timeout: 15_000 });

  const cells = (await row.locator('td').allTextContents()).map((t) => t.trim());
  console.log('[TH-03] 该行:', cells);
  for (const want of [
    'A0001', '2000', '1', 'Ni',
    'https://www.ccmn.cn/', '长江有色网', '1.均价',
    '0.031', '0.4', '镀层厚度≥0.4μm',
  ]) {
    expect(cells, `AC-50：该行缺值「${want}」，实际整行 ${JSON.stringify(cells)}`)
      .toContain(want);
  }

  await shot(page, 'ac50-quote-row');
});

// ═══════════════════════════════════════════════════════════════════
// AC-51 只读
// ═══════════════════════════════════════════════════════════════════

test('TH-04 / AC-51：页签只读 —— 无新增/编辑/删除/保存按钮，单元格不可编辑，顶部有说明文案', async ({ page }) => {
  await openPlatingTab(page);

  // ① 顶部说明文案
  const body = (await page.locator('body').textContent()) ?? '';
  expect(body, 'AC-51：缺顶部说明文案').toContain(READONLY_NOTICE);

  // ② 页面上不存在任何写入口
  //    ⚠️ 判据是「不存在」，属于「断言某事没发生」——testing.md §4.4 要求配阳性对照：
  //       先证明这个选择器在别的页签能抓到按钮，否则「找不到」可能只是选择器写错了。
  const writeButtonRe = /新\s*增|编\s*辑|删\s*除|保\s*存/;
  const platingWriteBtns = await page.locator('button').filter({ hasText: writeButtonRe }).count();

  await page.locator('.ant-tabs-tab', { hasText: '基础核价' }).first().click();
  await page.waitForTimeout(1200);
  const baselineRow = page.locator('.ant-table-row').first();
  let positiveControl = 0;
  if (await baselineRow.isVisible().catch(() => false)) {
    await baselineRow.click();
    await expect(page.locator('.ant-drawer-content')).toBeVisible({ timeout: 10_000 });
    await page.waitForTimeout(800);
    positiveControl = await page.locator('button').filter({ hasText: writeButtonRe }).count();
    await page.keyboard.press('Escape');
    await page.waitForTimeout(500);
  }
  console.log(`[TH-04] 阳性对照：基础核价抽屉里的写按钮数=${positiveControl}；电镀方案页=${platingWriteBtns}`);
  expect(positiveControl,
    'AC-51 阳性对照失败：连基础核价抽屉里都抓不到「保存/新增行」按钮 ⇒ 选择器有问题，'
    + '「电镀方案没有写按钮」这个结论不成立（可能是空验证）')
    .toBeGreaterThan(0);

  // 回到电镀方案，正式断言
  await page.locator('.ant-tabs-tab', { hasText: '电镀方案' }).first().click();
  await page.waitForTimeout(1200);
  expect(platingWriteBtns,
    `AC-51：电镀方案页签不该有任何「新增/编辑/删除/保存」按钮，实际找到 ${platingWriteBtns} 个`)
    .toBe(0);

  // ③ 单元格不可进入编辑态
  const firstCell = page.locator('.ant-table-tbody td').first();
  if (await firstCell.isVisible().catch(() => false)) {
    await firstCell.click();
    await page.waitForTimeout(500);
    const inputs = await page.locator('.ant-table-tbody input').count();
    expect(inputs, `AC-51：点击单元格后出现了 ${inputs} 个 input ⇒ 进入了编辑态，违反只读`).toBe(0);
  } else {
    // 空态分支（原型图同屏画了）：也必须没有写入口
    expect(body, 'AC-51 空态文案不符').toContain('暂无电镀方案数据，请先导入');
  }

  await shot(page, 'ac51-readonly');
});
