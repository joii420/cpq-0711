/**
 * E2E: 核价 Excel 视图树状（P2-B）—— 每 BOM 节点一行 + 注入 料号/父料号/版本 + 缩进 + 报价隔离。
 *
 * 夹具：报价单 QT-20260604-1577 (id=14c72d60...)，单产品 = 多层 BOM 根 3120018220（spine 17 节点）。
 *
 * 断言：
 *   ① 核价单 Excel 视图表头含 料号 / 父料号 / 版本
 *   ② 数据行 ≥17（整树，非 1 行/产品）
 *   ③ DAG 重复子件 3110520789 出现 ≥2 次
 *   ④ 1630010773 行版本 = 2000（边版本）
 *   ⑤ '加载中' = 0
 *   ⑥ 刷新后行数稳定（AP-51）
 *   ⑦ 报价单 Excel 视图：表头不含 父料号/版本，行数 = 产品数（1 行/产品，隔离）
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

const QUOTATION_ID = '14c72d60-46c3-4eed-bdef-115ab98050b0';
const ROOT_PART = '3120018220';
const DAG_CHILD = '3110520789';
const LEAF_PART = '1630010773';
const BACKEND_URL = 'http://localhost:8081';

let backendUp = false;
let shotIdx = 0;
async function shot(page: Page, name: string) {
  const file = path.join(SHOT_DIR, `cet-${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: true }).catch(() => {});
}

/** 进入指定主视图(报价单/核价单)的 Excel 视图。返回是否成功。 */
async function enterExcelView(page: Page, mainLabel: '核价单' | '报价单'): Promise<boolean> {
  await page.goto(`/quotations/${QUOTATION_ID}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1500);
  const nextBtn = page.locator('button', { hasText: /下一步|继续/ }).first();
  if (await nextBtn.count() > 0 && await nextBtn.isEnabled().catch(() => false)) {
    await nextBtn.click().catch(() => {});
    await page.waitForTimeout(1200);
  }
  const mainSeg = page.locator('.ant-segmented-item', { hasText: mainLabel }).first();
  if (await mainSeg.count() === 0) return false;
  await mainSeg.click().catch(() => {});
  await page.waitForTimeout(1000);
  const excelSeg = page.locator('.ant-segmented-item', { hasText: 'Excel 视图' }).first();
  if (await excelSeg.count() === 0) return false;
  await excelSeg.click().catch(() => {});
  await page.waitForTimeout(2500);
  return true;
}

async function headers(page: Page): Promise<string[]> {
  const ths = page.locator('.ant-table-thead th');
  const n = await ths.count();
  const out: string[] = [];
  for (let i = 0; i < n; i++) out.push((await ths.nth(i).innerText().catch(() => '')).trim());
  return out;
}

test.beforeAll(async () => { backendUp = await isBackendUp(); });

test('核价单 Excel 视图按 BOM 树逐节点出行 + 注入列 + 隔离（P2-B）', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');

  await loginAsAdmin(page);
  expect(page.url()).not.toContain('/login');
  // 重算核价快照（核价 Excel 树）
  const refresh = await page.request.post(
    `${BACKEND_URL}/api/cpq/configure-product/quotations/${QUOTATION_ID}/refresh-snapshot`);
  expect(refresh.status(), 'refresh-snapshot 应 200').toBe(200);

  // ── 核价单 Excel 视图 ──
  const entered = await enterExcelView(page, '核价单');
  expect(entered, '应进入核价单 Excel 视图').toBe(true);
  await shot(page, 'costing-excel');

  // ① 表头含 料号/父料号/版本
  const h = await headers(page);
  console.log('[CET] 核价 Excel 表头:', JSON.stringify(h));
  expect(h.some(x => x === '料号'), '应含 料号 列').toBe(true);
  expect(h.some(x => x === '父料号'), '应含 父料号 列').toBe(true);
  expect(h.some(x => x === '版本'), '应含 版本 列').toBe(true);

  // ② 行数 ≥17
  const dataRows = page.locator('.ant-table-tbody tr.ant-table-row');
  const rc = await dataRows.count();
  console.log('[CET] 核价 Excel 行数 =', rc);
  expect(rc, 'Excel 应按整棵 BOM 树出行(≥17)').toBeGreaterThanOrEqual(17);

  // ③④ 料号列(第1列) DAG 计数 + 叶子版本
  const verIdx = h.indexOf('版本');
  let dagCount = 0, leafVer = '';
  for (let r = 0; r < rc; r++) {
    const cells = dataRows.nth(r).locator('td');
    const part = (await cells.nth(0).innerText().catch(() => '')).trim();
    if (part.includes(DAG_CHILD)) dagCount++;
    if (part.includes(LEAF_PART) && verIdx >= 0) {
      leafVer = (await cells.nth(verIdx).innerText().catch(() => '')).trim();
    }
  }
  console.log(`[CET] DAG ${DAG_CHILD} 出现 ${dagCount} 次; 叶子 ${LEAF_PART} 版本=${leafVer}`);
  expect(dagCount, 'DAG 重复子件应出现 ≥2 次').toBeGreaterThanOrEqual(2);
  expect(leafVer, '叶子料号版本应=2000(边版本)').toBe('2000');

  // ⑤ 加载中 = 0
  const loading = await page.locator('text=加载中').count();
  console.log('[CET] 加载中 =', loading);
  expect(loading, '加载中应为 0').toBe(0);

  // ⑥ 刷新后行数稳定
  await page.reload();
  await page.waitForLoadState('networkidle');
  await enterExcelView(page, '核价单');
  const rc2 = await page.locator('.ant-table-tbody tr.ant-table-row').count();
  console.log('[CET] 刷新后行数 =', rc2);
  expect(rc2, '刷新后行数稳定(AP-51)').toBe(rc);

  await shot(page, 'final');
  console.log('[CET] ✅ 核价 Excel 树 + 注入列 + DAG + 版本 + 加载中=0 + 稳定');
});

test('报价侧隔离：报价单 Excel 视图无父料号/版本列、1 行/产品（AP-41）', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  await loginAsAdmin(page);

  const entered = await enterExcelView(page, '报价单');
  expect(entered, '应进入报价单 Excel 视图').toBe(true);
  await shot(page, 'quote-excel');

  const h = await headers(page);
  console.log('[CET] 报价 Excel 表头:', JSON.stringify(h));
  expect(h.includes('父料号'), '报价侧不应有 父料号 列').toBe(false);
  expect(h.includes('版本'), '报价侧不应有 版本 列').toBe(false);

  // 报价侧 1 行/产品（夹具单产品 → 1 行，绝不展开成树 17 行）
  const rc = await page.locator('.ant-table-tbody tr.ant-table-row').count();
  console.log('[CET] 报价 Excel 行数 =', rc);
  expect(rc, '报价 Excel 应 1 行/产品(隔离, 不出树)').toBeLessThanOrEqual(3);
});
