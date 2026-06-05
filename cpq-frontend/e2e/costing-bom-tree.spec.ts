/**
 * E2E: 核价 BOM 递归展开（P1）—— 核价单产品卡片按整棵 BOM 树展开 + 3 系统固定列 + 建树 + 隔离。
 *
 * 夹具（DRAFT，单产品 = 多层 BOM 根 3120018220）：
 *   - 报价单 QT-20260604-1577, id = 14c72d60-46c3-4eed-bdef-115ab98050b0
 *   - 闭包：partSet=14、spine=17 occurrence（含 DAG 重复子件 3110520789 经两路径各 1 次）
 *
 * 断言（P1 DoD）：
 *   ① 核价卡片每个 driver 组件 3 系统固定列：料号 / 父料号 / 版本（下拉占位，disabled）
 *   ② driver tab 行数 = 闭包 occurrence（材质/元素=17，工序=27 …），不是单料号 1 行
 *   ③ DAG 重复子件 3110520789 在料号列出现 ≥2 次（独立 occurrence，不塌 DAG）
 *   ④ 树形：根 3120018220 有展开/折叠箭头（hasChildren）
 *   ⑤ '加载中' 总数 = 0（AP-38/31）
 *   ⑥ 刷新页面后行数稳定（AP-51 不累加）
 *   ⑦ 报价侧隔离：报价单卡片无「版本下拉占位」固定列（disabled select 数 = 0）
 *
 * 非破坏：beforeAll 调 refresh-snapshot 重算核价快照（仅 COSTING，不碰报价编辑）；不删任何业务数据。
 */
import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { isBackendUp, loginAsAdmin } from './fixtures/auth';

const __filename = fileURLToPath(import.meta.url);
const __dirnameLocal = path.dirname(__filename);
const SHOT_DIR = path.join(__dirnameLocal, 'screenshots');
fs.mkdirSync(SHOT_DIR, { recursive: true });

const QUOTATION_ID = '14c72d60-46c3-4eed-bdef-115ab98050b0';  // QT-20260604-1577, 单产品 3120018220
const ROOT_PART = '3120018220';
const DAG_CHILD = '3110520789';   // 经 2120011658 / 2120011659 两路径各 1 次 → 出现 ≥2
const BACKEND_URL = 'http://localhost:8081';

let backendUp = false;
let shotIdx = 0;
async function shot(page: Page, name: string) {
  const file = path.join(SHOT_DIR, `cbt-${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: true }).catch(() => {});
}

/** 进核价单产品卡片视图。返回是否成功进入。 */
async function enterCostingCard(page: Page): Promise<boolean> {
  await page.goto(`/quotations/${QUOTATION_ID}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1500);
  // 若停在 step1 → 下一步
  const nextBtn = page.locator('button', { hasText: /下一步|继续/ }).first();
  if (await nextBtn.count() > 0 && await nextBtn.isEnabled().catch(() => false)) {
    await nextBtn.click().catch(() => {});
    await page.waitForTimeout(1200);
  }
  // 核价单 Segmented
  const costing = page.locator('.ant-segmented-item', { hasText: '核价单' }).first();
  if (await costing.count() === 0) return false;
  await costing.click().catch(() => {});
  await page.waitForTimeout(1000);
  // 产品卡片 viewType（默认即 card，保险点一下）
  const cardSeg = page.locator('.ant-segmented-item', { hasText: '产品卡片' }).first();
  if (await cardSeg.count() > 0) { await cardSeg.click().catch(() => {}); await page.waitForTimeout(800); }
  await page.waitForTimeout(1500);
  return true;
}

/** 读当前活动核价表（.qt-cost-table）表头文本 */
async function tableHeaders(page: Page): Promise<string[]> {
  const ths = page.locator('.qt-cost-table thead th');
  const n = await ths.count();
  const out: string[] = [];
  for (let i = 0; i < n; i++) out.push((await ths.nth(i).innerText().catch(() => '')).trim());
  return out;
}

test.beforeAll(async () => {
  backendUp = await isBackendUp();
});

test('核价单卡片按 BOM 树展开 + 3 固定列 + DAG + 加载中=0（P1）', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');

  await loginAsAdmin(page);
  expect(page.url()).not.toContain('/login');

  // beforeAll 等价：用已登录 session 重算核价快照（存量单刷出整棵树）
  const refresh = await page.request.post(
    `${BACKEND_URL}/api/cpq/configure-product/quotations/${QUOTATION_ID}/refresh-snapshot`);
  expect(refresh.status(), 'refresh-snapshot 应 200').toBe(200);

  const entered = await enterCostingCard(page);
  expect(entered, '应能进入核价单卡片视图').toBe(true);
  await shot(page, 'costing-card');

  // ① 3 系统固定列在表头前部
  const headers = await tableHeaders(page);
  console.log('[CBT] 核价表头:', JSON.stringify(headers));
  expect(headers.length, '应有核价表').toBeGreaterThan(3);
  expect(headers[0], '第1列=料号').toBe('料号');
  expect(headers[1], '第2列=父料号').toBe('父料号');
  expect(headers[2], '第3列=版本').toBe('版本');

  // ② driver tab 行数 = 闭包 occurrence（≥17，绝非单料号 1 行）
  const dataRows = page.locator('.qt-cost-table tbody tr');
  const rc = await dataRows.count();
  console.log('[CBT] 当前 driver tab 行数 =', rc);
  expect(rc, 'driver 组件应按整棵 BOM 树展开（≥17 行），不是单料号 1 行').toBeGreaterThanOrEqual(17);

  // ③ 根料号 + DAG 重复子件：料号列(每行第1个 td)文本统计
  const partCells = page.locator('.qt-cost-table tbody tr td:first-child');
  const pcN = await partCells.count();
  let rootCount = 0, dagCount = 0;
  for (let i = 0; i < pcN; i++) {
    const t = (await partCells.nth(i).innerText().catch(() => '')).trim();
    if (t.includes(ROOT_PART)) rootCount++;
    if (t.includes(DAG_CHILD)) dagCount++;
  }
  console.log(`[CBT] 根 ${ROOT_PART} 出现 ${rootCount} 次; DAG ${DAG_CHILD} 出现 ${dagCount} 次`);
  expect(rootCount, '根料号应作为树根出现 1 次').toBeGreaterThanOrEqual(1);
  expect(dagCount, 'DAG 重复子件应出现 ≥2 次（独立 occurrence，不塌 DAG）').toBeGreaterThanOrEqual(2);

  // ④ 树形：存在展开/折叠箭头（根 hasChildren）
  const caret = page.locator('.qt-cost-table tbody button', { hasText: /[▼▶]/ });
  expect(await caret.count(), '应有树展开/折叠箭头（根含子节点）').toBeGreaterThanOrEqual(1);

  // ⑤ 版本下拉占位：disabled select 存在；叶子料号 1630010773 版本应为边版本 2000（非空）
  const verSelect = page.locator('.qt-cost-table tbody select[disabled]');
  expect(await verSelect.count(), '版本列应为 disabled 下拉占位').toBeGreaterThanOrEqual(1);
  // 叶子 1630010773 行的版本下拉值（第3列 select）
  const leafRow = page.locator('.qt-cost-table tbody tr', { hasText: '1630010773' }).first();
  if (await leafRow.count() > 0) {
    const leafVer = await leafRow.locator('select').first().inputValue().catch(() => '');
    console.log('[CBT] 叶子 1630010773 版本 =', leafVer);
    expect(leafVer, '叶子料号版本应带出边版本(非空 —)').not.toBe('');
    expect(leafVer, '叶子料号版本应带出边版本(非 —)').not.toBe('—');
  }

  // ⑥ 加载中 = 0 —— 逐个内部组件 tab（材质/子配件/元素/工序/组合工艺）都要 0
  //    （元素 tab 的 类型/单价 列曾因子料号空行落 globalPathCache 永久"加载中"，本断言专防回归）
  const innerTabs = page.locator('.qt-tab-btn');
  const tabN = await innerTabs.count();
  console.log('[CBT] 内部组件 tab 数 =', tabN);
  for (let i = 0; i < tabN; i++) {
    const name = (await innerTabs.nth(i).innerText().catch(() => '')).trim();
    await innerTabs.nth(i).click().catch(() => {});
    await page.waitForTimeout(900);
    const lc = await page.locator('text=加载中').count();
    console.log(`[CBT] tab '${name}' 加载中 = ${lc}`);
    expect(lc, `tab '${name}' 加载中应为 0`).toBe(0);
  }
  const loading = await page.locator('text=加载中').count();
  expect(loading, '加载中应为 0').toBe(0);

  // ⑦ AP-51：刷新页面后行数稳定（不累加）
  await page.reload();
  await page.waitForLoadState('networkidle');
  await enterCostingCard(page);
  const rc2 = await page.locator('.qt-cost-table tbody tr').count();
  console.log('[CBT] 刷新后行数 =', rc2);
  expect(rc2, '刷新后行数应稳定（AP-51 不累加）').toBe(rc);

  await shot(page, 'final');
  console.log('[CBT] ✅ BOM 树 + 3 固定列 + DAG + 加载中=0 + 行数稳定');
});

test('报价侧隔离：报价单卡片无版本下拉占位固定列（AP-41）', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');

  await loginAsAdmin(page);
  await page.goto(`/quotations/${QUOTATION_ID}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1500);
  const nextBtn = page.locator('button', { hasText: /下一步|继续/ }).first();
  if (await nextBtn.count() > 0 && await nextBtn.isEnabled().catch(() => false)) {
    await nextBtn.click().catch(() => {});
    await page.waitForTimeout(1200);
  }
  // 报价单视图（默认即 quote，保险点一下）
  const quoteSeg = page.locator('.ant-segmented-item', { hasText: '报价单' }).first();
  if (await quoteSeg.count() > 0) { await quoteSeg.click().catch(() => {}); await page.waitForTimeout(1200); }
  await shot(page, 'quote-card');

  // 报价侧不应有 BOM 版本下拉占位（disabled select）
  const verSelect = page.locator('.qt-cost-table tbody select[disabled]');
  const cnt = await verSelect.count();
  console.log('[CBT] 报价侧 disabled 版本下拉数 =', cnt);
  expect(cnt, '报价侧不应注入核价 BOM 固定列（隔离）').toBe(0);

  // 报价侧表头第一列不应是核价系统固定列「料号/父料号/版本」三连
  const headers = await tableHeaders(page);
  console.log('[CBT] 报价表头:', JSON.stringify(headers));
  const isBomTriple = headers[0] === '料号' && headers[1] === '父料号' && headers[2] === '版本';
  expect(isBomTriple, '报价侧不应出现 料号/父料号/版本 系统固定列三连').toBe(false);
});
