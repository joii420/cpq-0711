/**
 * AC-1（问题说明 ⑥ 原文）：打开 QT-20260912-0011 编辑页 → Step2 → 核价单 → Excel 视图：
 *   4 行，四列逐格等于实查总计表。
 * AC-6：某页签卡片总计确为 0 时对应列仍显示 0，不得编造回退值
 *   （用 QT-20260911-0010：其 S0004/S0008/S0012 的 BOM 页签 subtotal 实查为 0）。
 * 纯只读：不点保存、不改数据。
 */
import { test, expect, Page } from '@playwright/test';
import { QID, QID_0010, EXPECT, login, switchSeg, excelTable, dump, num, shot, saveJson, Dump } from './repair260912-helpers';

const COLS = ['元素小计', '物料小计', '加工费', '单价'] as const;

async function openEditExcel(page: Page, qid: string) {
  await page.goto(`/quotations/${qid}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(3500);
  const next = page.locator('button', { hasText: '下一步' }).first();
  await expect(next, 'Step1 应有可点的「下一步」').toBeEnabled({ timeout: 20_000 });
  await next.click();
  await page.waitForTimeout(6000);
  await switchSeg(page, '核价单');
  await switchSeg(page, 'Excel 视图');
  await expect(page.getByText('元素小计').first(), '应已切到核价 Excel 视图').toBeVisible({ timeout: 20_000 });
}

/** 表头实测形如 "[col_1]元素小计"，按后缀匹配。 */
function idx(d: Dump, name: string): number {
  const i = d.headers.findIndex(h => h.replace(/\s+/g, '').endsWith(name));
  if (i < 0) throw new Error(`表头缺「${name}」，实际=${JSON.stringify(d.headers)}`);
  return i;
}
/** 按「料号」列定位行 —— 不用行序，避免排序变化时静默错位。 */
function rowOf(d: Dump, sku: string): string[] {
  const i = idx(d, '料号');
  const hit = d.rows.filter(r => r[i].trim() === sku);
  if (hit.length !== 1) throw new Error(`料号 ${sku} 命中 ${hit.length} 行（期望恰好 1）；料号列=${JSON.stringify(d.rows.map(r => r[i]))}`);
  return hit[0];
}

test('AC-1 编辑页核价 Excel 视图 = 4 行 × 四列总计', async ({ page }) => {
  await login(page);
  await openEditExcel(page, QID);
  const t = await excelTable(page);
  const d = await dump(t);
  console.log('[AC-1 表头]', JSON.stringify(d.headers));
  d.rows.forEach((r, i) => console.log(`[AC-1 数据行${i}]`, JSON.stringify(r)));
  saveJson('ac1-edit-dump.json', d);
  await shot(page, 'AC-1-编辑页核价Excel视图');

  expect(d.rows.length, `每产品一行 ⇒ 应恰好 4 行，实得 ${d.rows.length}`).toBe(4);
  // 阴性（用户明确要的「不要树状」）：编辑页也不得出现子件料号行
  const partCol = idx(d, '料号');
  const parts = d.rows.map(r => r[partCol].trim());
  expect(parts.sort(), '料号列应恰好是四个产品，不含 BOM 子件').toEqual(['S0001', 'S0004', 'S0008', 'S0012']);

  const seen: Record<string, Record<string, number>> = {};
  for (const sku of ['S0001', 'S0004', 'S0008', 'S0012']) {
    const row = rowOf(d, sku);
    seen[sku] = {};
    for (const c of COLS) seen[sku][c] = num(row[idx(d, c)]);
  }
  console.log('[AC-1 实际观测值]', JSON.stringify(seen, null, 2));
  for (const sku of Object.keys(seen))
    for (const c of COLS)
      expect(seen[sku][c], `${sku} 的「${c}」`).toBe(EXPECT[sku][c]);
});

test('AC-6 卡片总计确为 0 的页签 → 对应列仍为 0（QT-20260911-0010）', async ({ page }) => {
  await login(page);
  await openEditExcel(page, QID_0010);
  const t = await excelTable(page);
  const d = await dump(t);
  d.rows.forEach((r, i) => console.log(`[AC-6 数据行${i}]`, JSON.stringify(r)));
  saveJson('ac6-edit-dump-0010.json', d);
  await shot(page, 'AC-6-编辑页0010');

  expect(d.rows.length, '应 4 行').toBe(4);
  // 实查 costing_card_values：0010 的 S0004/S0008/S0012 的 BOM 页签 subtotal = 0
  for (const sku of ['S0004', 'S0008', 'S0012']) {
    const row = rowOf(d, sku);
    const bom = num(row[idx(d, '物料小计')]);
    const ele = num(row[idx(d, '元素小计')]);
    const fee = num(row[idx(d, '加工费')]);
    const up  = num(row[idx(d, '单价')]);
    console.log(`[AC-6] ${sku} 元素=${ele} 物料=${bom} 加工费=${fee} 单价=${up}`);
    expect(bom, `${sku}「物料小计」（该页签卡片总计实查为 0）应为 0`).toBe(0);
    // 阴性：不得整行塌成 0（否则「=0」断言无鉴别力）
    expect(ele, `${sku}「元素小计」应非 0`).toBeGreaterThan(0);
    expect(fee, `${sku}「加工费」应非 0`).toBeGreaterThan(0);
  }
  // 同一张表里 S0001 的 BOM 实查为 5438667.5 → 必须非 0，证明「0」不是恒真
  const s1 = rowOf(d, 'S0001');
  expect(num(s1[idx(d, '物料小计')]), 'S0001「物料小计」应为 5438667.5（证明 0 不是恒真）').toBe(5438667.5);
});
