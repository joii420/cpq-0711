/**
 * 主线亲验（不是测试工程师的产出）—— repair-260911
 * 纪律：只读。不点保存、不改任何单元格。断言直接对着 问题说明.md ⑥ 的 AC 原文。
 * 防空跑：每条断言前先断言「取到的行数 > 0」。
 */
import { test, expect } from '@playwright/test';
import { login, openStep2, switchView, cardOf, clickTab, shotTable, OUT_DIR } from './repair260911-helpers';

/** 读出某页签表格里 (料号 -> 指定列文本) 的映射 + 底部小计文本。 */
async function readTable(card: any, colName: string) {
  const table = card.locator('table').first();
  await expect(table).toBeVisible({ timeout: 20_000 });
  const headers = await table.locator('thead th').allTextContents();
  const idxCol = headers.findIndex((h: string) => h.trim() === colName);
  const idxPart = headers.findIndex((h: string) => h.trim() === '料号');
  expect(idxCol, `表头应含「${colName}」列，实际表头=${JSON.stringify(headers)}`).toBeGreaterThanOrEqual(0);
  const rows = table.locator('tbody tr');
  const n = await rows.count();
  expect(n, '表格必须有数据行（防空跑）').toBeGreaterThan(0);
  const out: Array<{ part: string; val: string }> = [];
  for (let i = 0; i < n; i++) {
    const tds = rows.nth(i).locator('td');
    if (await tds.count() <= Math.max(idxCol, idxPart)) continue;
    out.push({
      part: (await tds.nth(idxPart).innerText()).trim(),
      val: (await tds.nth(idxCol).innerText()).trim(),
    });
  }
  const foot = (await table.locator('tfoot').innerText().catch(() => '')).replace(/\s+/g, ' ').trim();
  return { rows: out, foot, headers };
}

test('主线亲验 · 核价侧 AC-1 / AC-2 / AC-4', async ({ page }) => {
  await login(page);
  await openStep2(page);
  await switchView(page, '核价单');
  const card = cardOf(page);
  await expect(card, '应找到 S0001 卡片').toBeVisible({ timeout: 20_000 });
  await clickTab(card, 'BOM');

  const { rows, foot, headers } = await readTable(card, '物料成本');
  console.log('[亲验] 表头 =', JSON.stringify(headers));
  console.log('[亲验] 物料成本列 =', JSON.stringify(rows));
  console.log('[亲验] tfoot =', foot);
  await shotTable(card, 'MAINLINE-核价BOM-物料成本');

  const pick = (p: string) => rows.find((r) => r.part === p)?.val;

  // AC-1 阳性
  expect(rows.some((r) => r.part === '00003'), 'AC-1 前置：必须有 00003 行').toBeTruthy();
  expect(rows.some((r) => r.part === '00081'), 'AC-1 前置：必须有 00081 行').toBeTruthy();
  expect(pick('00003'), 'AC-1 · 00003 行物料成本').toBe('233922.5');
  expect(pick('00081'), 'AC-1 · 00081 行物料成本').toBe('5204745');

  // AC-2 小计
  expect(foot, 'AC-2 · 页签小计应含 5438667.5').toContain('5438667.5');

  // AC-4 阴性：四个零件行仍为 0
  for (const p of ['300012', '300013', '300014', '300015']) {
    expect(rows.some((r) => r.part === p), `AC-4 前置：必须有 ${p} 行`).toBeTruthy();
    expect(pick(p), `AC-4 · ${p} 行应仍为 0`).toBe('0');
  }
  // AC-4 根行（料号列为「—」或空）
  const rootRows = rows.filter((r) => r.part === '' || r.part === '—' || r.part === '-');
  expect(rootRows.length, 'AC-4 前置：应有根行').toBeGreaterThan(0);
  for (const r of rootRows) expect(r.val, 'AC-4 · 根行应仍为 0').toBe('0');
});

test('主线亲验 · 报价侧零回归 AC-7', async ({ page }) => {
  await login(page);
  await openStep2(page);
  await switchView(page, '报价单');
  const card = cardOf(page);
  await expect(card).toBeVisible({ timeout: 20_000 });
  await clickTab(card, 'BOM');

  const bom = await readTable(card, '物料成本');
  console.log('[亲验] 报价侧 BOM 物料成本 =', JSON.stringify(bom.rows));
  console.log('[亲验] 报价侧 BOM tfoot =', bom.foot);
  await shotTable(card, 'MAINLINE-报价BOM-物料成本');
  const nonZero = bom.rows.filter((r) => r.val && r.val !== '0' && r.val !== '—');
  expect(nonZero.length, 'AC-7 前置：报价侧必须有非零行（防空跑）').toBeGreaterThan(0);
  expect(bom.rows.map((r) => r.val).join('|'), 'AC-7 · 报价侧物料成本应含 203420.460972186').toContain('203420.460972186');

  await clickTab(card, '材质元素');
  const mat = await readTable(card, '材料成本');
  console.log('[亲验] 报价侧 材质元素 tfoot =', mat.foot);
  expect(mat.foot, 'AC-7 · 报价侧材质元素小计应为 33903.410162031').toContain('33903.410162031');
});
