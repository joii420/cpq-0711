/**
 * task-260911 · S2 片 · AC-8（序列）。
 * 由外部脚本按时点驱动：T0911S2_PHASE 指定本次跑的是哪个时点。
 *   P1 = 改值后·未物化   P2 = 物化后   P3 = 刷新后   P4 = 切走切回后
 * P2 一次跑里连做 P2/P3/P4 三个时点（物化后首屏 → reload → 切走切回）。
 */
import { test, expect } from '@playwright/test';
import { gotoStep2, dumpAllCards, colIdx, shot, save, CASES, LABEL } from './t0911s2.helpers';

const PHASE = process.env.T0911S2_PHASE || 'P1';

function summarize(dump: any[]) {
  return dump.map((d) => {
    const ci = colIdx(d.headers, '客户产品编号');
    const ni = colIdx(d.headers, '品名');
    return { card: d.cardIdx, rows: d.rows.length, cpn: ci >= 0 ? (d.rows[0]?.[ci] ?? '<无行>') : '<无列>',
             name: ni >= 0 ? (d.rows[0]?.[ni] ?? '<无行>') : '<无列>' };
  });
}

test(`AC-8 序列 · ${PHASE}`, async ({ page }) => {
  test.setTimeout(1_200_000);
  const c = CASES['MAIN'];
  await gotoStep2(page, c, 5);

  if (PHASE === 'P1') {
    const s = summarize(await dumpAllCards(page, '产品'));
    console.log(`[S2][${LABEL}] AC-8 时点1（改值后·未物化） = ${JSON.stringify(s)}`);
    save('AC8-时点1-改值后未物化.json', s);
    await shot(page, 'AC8-时点1-改值后未物化');
    expect(s.length).toBe(5);
    return;
  }

  // ---- 时点2：物化后首屏 ----
  const s2 = summarize(await dumpAllCards(page, '产品'));
  console.log(`[S2][${LABEL}] AC-8 时点2（物化后） = ${JSON.stringify(s2)}`);
  save('AC8-时点2-物化后.json', s2);
  await shot(page, 'AC8-时点2-物化后');

  // ---- 时点3：刷新（重新从详情页进入；🚫 不用 page.reload()，实测直接 reload /edit 得 0 张卡片 = 量具失效）----
  await gotoStep2(page, c, 5);
  const s3 = summarize(await dumpAllCards(page, '产品'));
  console.log(`[S2][${LABEL}] AC-8 时点3（刷新后） = ${JSON.stringify(s3)}`);
  save('AC8-时点3-刷新后.json', s3);
  await shot(page, 'AC8-时点3-刷新后');

  // ---- 时点4：切走再切回 ----
  const dumpAway = await dumpAllCards(page, 'BOM');
  expect(dumpAway.length, '切走到 BOM 页签必须成功').toBe(5);
  const s4 = summarize(await dumpAllCards(page, '产品'));
  console.log(`[S2][${LABEL}] AC-8 时点4（切走切回后） = ${JSON.stringify(s4)}`);
  save('AC8-时点4-切走切回后.json', s4);
  await shot(page, 'AC8-时点4-切走切回后');

  // AC-8 断言（证据已全部落盘后再断言）：被改的那行（card#2）跟着变为 T0911S2-CP2；其余行不受影响
  for (const [tag, s] of [['时点2', s2], ['时点3', s3], ['时点4', s4]] as const) {
    expect(s[2].cpn, `🚨 ${tag}：被改行 card#2 应为 T0911S2-CP2`).toBe('T0911S2-CP2');
    expect(s[0].cpn, `🚨 ${tag}：card#0 不应受影响`).toBe('T0911S2-CP1');
    expect(s[1].cpn, `🚨 ${tag}：card#1 不应受影响`).toBe('T0911S2-CP2');
    expect(s[3].cpn, `🚨 ${tag}：card#3 不应受影响`).toBe('T0911S2-CP1');
    for (const r of s) expect(r.rows, `🚨 ${tag}：每卡片应恰好 1 行`).toBe(1);
  }
});
