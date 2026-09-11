/**
 * task-260911 · S2 · 无 row_data 残留的判别性载体（FRESH）。
 * 目的：让 UI 证据具备判别力 —— MAIN 的 row_data 写于改动前，UI 绿可能只是读到旧值。
 * AC-3 / AC-5 / AC-6 在此载体上重验。
 */
import { test, expect } from '@playwright/test';
import { gotoStep2, dumpAllCards, colIdx, shot, save, CASES, LABEL } from './t0911s2.helpers';

test('FRESH · AC-3 / AC-5 / AC-6（UI 层）', async ({ page }) => {
  test.setTimeout(1_200_000);
  const c = CASES['FRESH'];
  await gotoStep2(page, c, 5);
  const dump = await dumpAllCards(page, '产品');
  await shot(page, 'FRESH-全卡片');
  save('FRESH-dump.json', dump);

  const obs = dump.map((d) => {
    const ci = colIdx(d.headers, '客户产品编号'), ni = colIdx(d.headers, '品名');
    expect(ci, `卡片#${d.cardIdx} 表头无「客户产品编号」，实际=${JSON.stringify(d.headers)}`).toBeGreaterThanOrEqual(0);
    expect(ni).toBeGreaterThanOrEqual(0);
    return { card: d.cardIdx, title: d.title, rows: d.rows.length,
             cpn: d.rows[0]?.[ci] ?? '<无行>', name: d.rows[0]?.[ni] ?? '<无行>' };
  });
  console.log(`[S2][${LABEL}] FRESH 观测 = ${JSON.stringify(obs, null, 1)}`);
  save('FRESH-观测汇总.json', obs);

  for (const o of obs) {
    expect(o.rows, `🚨 卡片#${o.card} 应恰好 1 行，实际 ${o.rows}`).toBe(1);
    expect(o.name, `🚨 卡片#${o.card} 物料侧列必须有值（断言非空自证）`).toBe('S2测试件甲');
  }
  // AC-3：三行不同客编，各取自己的
  expect(obs[0].cpn).toBe('T0911S2-CP1');
  expect(obs[1].cpn).toBe('T0911S2-CP2');
  expect(obs[2].cpn).toBe('T0911S2-CP3');
  // AC-6：两行同客编
  expect(obs[3].cpn).toBe('T0911S2-CP1');
  // AC-5：空客编行 ⇒ 客编列必须空
  expect(obs[4].cpn, `🚨 AC-5(UI层) 客编列必须为空，实际="${obs[4].cpn}"`).toBe('');
});
