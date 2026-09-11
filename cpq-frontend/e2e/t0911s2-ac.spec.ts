/**
 * task-260911 · S2 片 · AC-3 / AC-5 / AC-6 的 UI 断言。
 * 🚫 未读任何被改动的实现文件；断言从 `需求文档.md ③` 的 AC 原文派生。
 */
import { test, expect } from '@playwright/test';
import { gotoStep2, dumpAllCards, colIdx, shot, save, CASES, LABEL } from './t0911s2.helpers';

/** 卡片下标 ↔ 明细行：卡片按 sortOrder 顺序渲染，由 PROBE 实测确认。 */
test('AC-3 / AC-5 / AC-6 · T0911S2-MAIN 五行各取自己的客编', async ({ page }) => {
  test.setTimeout(900_000);
  const c = CASES['MAIN'];
  await gotoStep2(page, c, 5);
  const dump = await dumpAllCards(page, '产品');
  await shot(page, 'AC3-5-6-MAIN-全卡片');
  save('AC3-5-6-MAIN-dump.json', dump);

  expect(dump.length, '量具自证：必须 5 张卡片').toBe(5);

  const observed: Array<{ so: number; expect: string | null; cpn: string; name: string; rows: number }> = [];
  for (const d of dump) {
    expect(d.err, `卡片#${d.cardIdx} 读表失败：${d.err}`).toBe('');
    // 🔑 断言非空自证：表头必须含「客户产品编号」与「品名」，否则量具没读到东西
    const ci = colIdx(d.headers, '客户产品编号');
    const ni = colIdx(d.headers, '品名');
    expect(ci, `卡片#${d.cardIdx} 表头无「客户产品编号」列，实际=${JSON.stringify(d.headers)}`).toBeGreaterThanOrEqual(0);
    expect(ni, `卡片#${d.cardIdx} 表头无「品名」列`).toBeGreaterThanOrEqual(0);
    // AC-3/5/6 共同断言：每个明细行各渲染 1 行（🚫 不是 0 行、不是 N 行）
    expect(d.rows.length, `🚨 卡片#${d.cardIdx} 产品页签应恰好 1 行，实际 ${d.rows.length} 行`).toBe(1);
    observed.push({ so: d.cardIdx, expect: c.lineItems[d.cardIdx].custPartNo,
                    cpn: d.rows[0][ci] ?? '', name: d.rows[0][ni] ?? '', rows: d.rows.length });
  }
  console.log(`[S2][${LABEL}] 观测汇总 = ${JSON.stringify(observed, null, 1)}`);
  save('AC3-5-6-MAIN-观测汇总.json', observed);

  // AC-3：前三行客编各不相同，且 = 本行自己的
  expect(observed[0].cpn).toBe('T0911S2-CP1');
  expect(observed[1].cpn).toBe('T0911S2-CP2');
  expect(observed[2].cpn).toBe('T0911S2-CP3');
  // AC-6：第 0 行与第 3 行客编相同 ⇒ 各 1 行且内容相同
  expect(observed[3].cpn).toBe('T0911S2-CP1');
  expect(observed[3].name).toBe(observed[0].name);
  // AC-5：第 4 行客编为空 ⇒ 客编列空、物料侧列有值
  expect(observed[4].name, 'AC-5 物料侧列必须有值').toBe('S2测试件甲');
  expect(observed[4].cpn, `🚨 AC-5 客编列必须为空，实际渲染="${observed[4].cpn}"（串了别的明细行的客户侧数据）`).toBe('');
});

test('AC-5 · T0911S2-EMPTY 单行空客编', async ({ page }) => {
  test.setTimeout(900_000);
  const c = CASES['EMPTY'];
  await gotoStep2(page, c, 1);
  const dump = await dumpAllCards(page, '产品');
  await shot(page, 'AC5-EMPTY');
  save('AC5-EMPTY-dump.json', dump);
  const d = dump[0];
  const ci = colIdx(d.headers, '客户产品编号'), ni = colIdx(d.headers, '品名');
  expect(ci).toBeGreaterThanOrEqual(0);
  expect(d.rows.length, '🚨 AC-5 必须 1 行，0 行 = 兜底失效').toBe(1);
  expect(d.rows[0][ni]).toBe('S2测试件甲');
  expect(d.rows[0][ci]).toBe('');
});

test('判别性反例 · T0911S2-NEG 客编填表中不存在的值', async ({ page }) => {
  test.setTimeout(900_000);
  const c = CASES['NEG'];
  await gotoStep2(page, c, 1);
  const dump = await dumpAllCards(page, '产品');
  await shot(page, 'NEG');
  save('NEG-dump.json', dump);
  const d = dump[0];
  const ci = colIdx(d.headers, '客户产品编号'), ni = colIdx(d.headers, '品名');
  // 1 行 + 客编空 = 谓词在 ON 且兜底生效；0 行 = 误入 WHERE；N 行 = 谓词没生效
  expect(d.rows.length, '🚨 0 行 = 谓词误入 WHERE；>1 行 = 谓词未生效').toBe(1);
  expect(d.rows[0][ni]).toBe('S2测试件甲');
  expect(d.rows[0][ci]).toBe('');
});
