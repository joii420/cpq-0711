/**
 * repair-260911 · S1 · AC-1 / AC-2 / AC-4（真实浏览器，只读）
 *
 * 同一套观测代码跑两种期望，由 R260911_MODE 切换：
 *   R260911_MODE=before  —— 未修复态：物料成本 7 行全 0、小计 0（红基线 / 还原实验 2 的对照）
 *   R260911_MODE=after   —— 修复态  ：00003=233922.5、00081=5204745、其余 5 行仍 0、小计 5438667.5
 * 🔑 两个模式共用同一段读取逻辑，所以「after 绿」不可能是观测手段失灵造成的假绿——
 *    因为同一段代码在 before 模式下必须报出全 0（否则 before 用例自己就红）。
 */
import { test, expect } from '@playwright/test';
import { login, openStep2, switchView, cardOf, clickTab, dumpTable, colIdx, rowByPartNo, num, shot, shotTable, saveJson } from './repair260911-helpers';

const MODE = (process.env.R260911_MODE || 'after') as 'before' | 'after';

// AC-1 / AC-2 原文里的期望值
const EXP_00003 = 233922.5;
const EXP_00081 = 5204745;
const EXP_SUBTOTAL = 5438667.5;
// AC-4：这些行在两种模式下都必须是 0
const ZERO_PART_NOS = ['300012', '300013', '300014', '300015'];

test(`AC-1/AC-2/AC-4 核价单 BOM 物料成本（mode=${MODE}）`, async ({ page }) => {
  const consoleErrors: string[] = [];
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });

  await login(page);
  await openStep2(page);
  await switchView(page, '核价单');
  const card = cardOf(page);
  await expect(card, '应找到销售料号 S0001 的产品卡片').toBeVisible({ timeout: 20_000 });
  await expect(card, '卡片头应带客户产品编号 A002').toContainText('A002');
  await clickTab(card, 'BOM');
  await shot(page, `ac1-${MODE}-bom`);
  await shotTable(card, `ac1-${MODE}-bom-物料成本列`);

  const d = await dumpTable(card);
  saveJson(`ac1-${MODE}-table.json`, d);
  const ci = colIdx(d, '物料成本');
  const pi = colIdx(d, '料号');

  // 断言前先证明「结果非空」——防止表格空转导致断言从未执行（testing.md §3）
  expect(d.rows.length, 'BOM 页签应有 7 行（根行 + 6）').toBe(7);
  console.log(`[实际值] 料号列 = ${JSON.stringify(d.rows.map((r) => r[pi]))}`);
  console.log(`[实际值] 物料成本列 = ${JSON.stringify(d.rows.map((r) => r[ci]))}`);
  console.log(`[实际值] tfoot = ${d.footer}`);

  const r00003 = rowByPartNo(d, '00003');
  const r00081 = rowByPartNo(d, '00081');
  // 前置事实核对：这两行的乘数必须是 AC 假定的那组，否则期望值本身失效
  expect(r00003[colIdx(d, '组成用量')], '00003 行组成用量应为 3.5').toBe('3.5');
  expect(r00081[colIdx(d, '组成用量')], '00081 行组成用量应为 12.3').toBe('12.3');

  const v00003 = num(r00003[ci]);
  const v00081 = num(r00081[ci]);
  const footNums = (d.footer.match(/-?[\d,]+(\.\d+)?/g) || []).map((s) => Number(s.replace(/,/g, '')));
  console.log(`[实际值] 00003 物料成本 = ${v00003} ; 00081 物料成本 = ${v00081} ; tfoot 数字 = ${JSON.stringify(footNums)}`);

  if (MODE === 'before') {
    expect(v00003, '未修复态：00003 应为 0').toBe(0);
    expect(v00081, '未修复态：00081 应为 0').toBe(0);
    expect(footNums.every((n) => n === 0), `未修复态：tfoot 小计/合计应全 0，实际 ${JSON.stringify(footNums)}`).toBe(true);
  } else {
    expect(v00003, 'AC-1：00003 行物料成本应为 233922.5').toBeCloseTo(EXP_00003, 4);
    expect(v00081, 'AC-1：00081 行物料成本应为 5204745').toBeCloseTo(EXP_00081, 4);
    expect(footNums.some((n) => Math.abs(n - EXP_SUBTOTAL) < 1e-4),
      `AC-2：tfoot 应含小计 ${EXP_SUBTOTAL}，实际 ${JSON.stringify(footNums)}`).toBe(true);
    expect(footNums.filter((n) => n !== 0).length, 'AC-2：卡片/页签合计不应再是 0').toBeGreaterThan(0);
  }

  // AC-4：无对应材质元素行的四个料号 + 根行，两种模式下都必须是 0
  for (const p of ZERO_PART_NOS) {
    const row = rowByPartNo(d, p);
    expect(num(row[ci]), `AC-4：料号 ${p} 的物料成本应为 0`).toBe(0);
  }
  const rootRow = d.rows.find((r) => r[pi] === '—' || r[pi] === '');
  expect(rootRow, 'AC-4：应能定位到根行（料号列为 —）').toBeTruthy();
  expect(num(rootRow![ci]), 'AC-4：根行物料成本应为 0').toBe(0);

  // 不该出现「加载中…」占位（AP-31/AP-38）
  expect(await card.locator('text=加载中').count(), '卡片内不应有「加载中…」占位').toBe(0);
  saveJson(`ac1-${MODE}-consoleErrors.json`, consoleErrors);
});
