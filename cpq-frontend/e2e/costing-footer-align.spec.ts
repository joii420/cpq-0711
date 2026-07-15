/**
 * E2E 回归守卫：核价 BOM 树表格 tfoot（小计/合计）行的网格列数必须 == 表头列数。
 *
 * 背景（2026-07-13 修复）：QuotationStep2.tsx 核价 BOM 树 footer 占位单元格残留 3 个 <td>
 *   （料号/父料号/版本 旧三列），但 2026-07-03 已隐藏「父料号」→ 表头/数据行只剩 2 固定列。
 *   footer 多一格 → 合计/小计行末尾单元格溢出表格右边界（用户报的 bug）。
 *   修复：footer 占位 3→2，与表头/数据行对齐。ReadonlyProductCard(只读侧) 早已是 2，故只编辑页有 bug。
 *
 * 断言：对任一渲染出核价 BOM 树(含 disabled 版本下拉)的核价单产品卡片页签：
 *   header 列数(Σcolspan) == 小计行列数(Σcolspan) == 合计行列数(Σcolspan) == 数据行列数(Σcolspan)。
 *
 * 非破坏：只读渲染 + 截图，不写任何业务数据。夹具用当天真实 DRAFT 单（自动挑第一个能渲染 BOM 树的）。
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

// 当天(2026-07-13)/近期已绑核价卡片模板的真实 DRAFT 单，按序尝试直到找到能渲染 BOM 树的
const CANDIDATES = [
  'c4d9b1dc-889a-4264-83d1-0b1d9b067fb6', // QT-20260713-1963
  '06206647-730e-4691-a64d-c9d41d2e71a9', // QT-20260713-1962
  '5786f324-da7a-4be9-adf0-c718fad7a3fe', // QT-20260713-1961
  '63040961-9b00-4c1b-941c-4aaf911d77ec', // QT-20260712-1960
  '08c22be0-0f51-4cbd-8a9a-960908544ced', // QT-20260712-1959
];

let backendUp = false;
test.beforeAll(async () => { backendUp = await isBackendUp(); });

/** 进核价单产品卡片视图 */
async function enterCostingCard(page: Page, qid: string): Promise<boolean> {
  await page.goto(`/quotations/${qid}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1200);
  const nextBtn = page.locator('button', { hasText: /下一步|继续/ }).first();
  if (await nextBtn.count() > 0 && await nextBtn.isEnabled().catch(() => false)) {
    await nextBtn.click().catch(() => {});
    await page.waitForTimeout(1000);
  }
  const costing = page.locator('.ant-segmented-item', { hasText: '核价单' }).first();
  if (await costing.count() === 0) return false;
  await costing.click().catch(() => {});
  await page.waitForTimeout(900);
  const cardSeg = page.locator('.ant-segmented-item', { hasText: '产品卡片' }).first();
  if (await cardSeg.count() > 0) { await cardSeg.click().catch(() => {}); await page.waitForTimeout(700); }
  await page.waitForTimeout(1200);
  return true;
}

/** 计算某表格 header / 每类行的“网格列数”（Σ colspan），返回 { header, dataRow, subtotal, tabTotal } */
async function gridColumnCounts(page: Page) {
  return await page.evaluate(() => {
    const sumColspan = (cells: Element[]) =>
      cells.reduce((s, c) => s + (parseInt(c.getAttribute('colspan') || '1', 10) || 1), 0);
    const table = document.querySelector('.qt-cost-table');
    if (!table) return null;
    const headerCells = Array.from(table.querySelectorAll('thead tr:last-child th'));
    const firstDataRow = table.querySelector('tbody tr');
    const subtotalRow = table.querySelector('tfoot tr.qt-subtotal-row:not(.qt-tab-total-row)');
    const tabTotalRow = table.querySelector('tfoot tr.qt-tab-total-row');
    return {
      header: sumColspan(headerCells),
      dataRow: firstDataRow ? sumColspan(Array.from(firstDataRow.children)) : null,
      subtotal: subtotalRow ? sumColspan(Array.from(subtotalRow.children)) : null,
      tabTotal: tabTotalRow ? sumColspan(Array.from(tabTotalRow.children)) : null,
    };
  });
}

test('核价 BOM 树 footer(小计/合计) 列数与表头对齐（不溢出）', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  await loginAsAdmin(page);
  expect(page.url()).not.toContain('/login');

  let found: { qid: string; tab: string; counts: any } | null = null;

  outer:
  for (const qid of CANDIDATES) {
    const ok = await enterCostingCard(page, qid);
    if (!ok) continue;
    const tabs = page.locator('.qt-tab-btn');
    const tabN = await tabs.count();
    for (let i = 0; i < tabN; i++) {
      await tabs.nth(i).click().catch(() => {});
      await page.waitForTimeout(700);
      const name = (await tabs.nth(i).innerText().catch(() => '')).trim();
      // BOM 树标志：含 disabled 版本下拉；且有 tfoot 合计行（need is_subtotal+is_amount）
      const hasBomVersion = await page.locator('.qt-cost-table tbody select[disabled]').count() > 0;
      const hasTabTotal = await page.locator('.qt-cost-table tfoot tr.qt-tab-total-row').count() > 0;
      if (hasBomVersion && hasTabTotal) {
        const counts = await gridColumnCounts(page);
        console.log(`[FOOTER] qid=${qid} tab='${name}' counts=`, JSON.stringify(counts));
        if (counts) { found = { qid, tab: name, counts }; break outer; }
      }
    }
  }

  // 优雅降级：若当前库里已无可渲染 BOM 树的活单(DRAFT 单被清理)，skip 而非假红
  //（本仓 costing-bom-tree.spec.ts 因硬编码夹具失效而长期红，避免重蹈覆辙）
  test.skip(!found, '当前无可渲染核价 BOM 树的活单，跳过 footer 对齐断言');
  const c = found!.counts;
  console.log(`[FOOTER] ✔ 命中 ${found!.qid} / '${found!.tab}':`, JSON.stringify(c));

  await page.locator('.qt-cost-table').first().screenshot({
    path: path.join(SHOT_DIR, 'footer-align.png'),
  }).catch(() => {});

  // 核心断言：footer 两行列数（Σcolspan）必须与表头一致，否则末尾单元格溢出
  expect(c.subtotal, '小计行列数应 == 表头列数（对齐，不溢出）').toBe(c.header);
  expect(c.tabTotal, '合计行列数应 == 表头列数（对齐，不溢出）').toBe(c.header);
  if (c.dataRow != null) {
    expect(c.dataRow, '数据行列数应 == 表头列数').toBe(c.header);
  }
});
