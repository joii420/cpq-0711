/**
 * repair-260910 · S1 片 · **切换（序列）与边界** —— AC-6 / AC-7 / AC-8 / AC-9
 *
 * 🚨 **本片唯一会写库的文件**，因此排在最后（文件名字母序 s1e > s1a~s1d）。
 *
 * 写入面（test-report「待回收清单」逐项对应）：
 *   ① `costing_order_version_override`：可能新增 (COID, COMP-2299, '300001') 一行
 *   ② 载体核价单 `HJ-20260910-0975` 的 `costing_render` / `costing_total_amount` 随切换重算
 *   ③ **不写**任何其它单据；🚫 不改任何单据的 status；🚫 不 DELETE 任何行
 *
 * 退场纪律：`afterAll` 无条件把 `300001` 切回 `3`（= AC-7 的断言本身）。
 * 🚫 override 行本身**不删除**（DELETE 属 §3.2 红线）—— 写进待回收清单交主线。
 *
 * ── 关于 version-switch 的请求体形状 ──────────────────────────────────────
 *   `api.md` 只写「请求/响应 **不变**」没给字段名，🚫 不许翻实现补齐（派工书 §c）。
 *   ⇒ helpers 里按 A/B/C 三种候选形状**逐个试**，命中 2xx 即钉死。
 *   之所以不算"猜"：AC-8 的 400 断言前有**阳性对照**（同一形状先在非叶子料号上拿到 2xx），
 *   形状错时阳性对照先红，不会把"body 写错"误读成"叶子守卫生效"。
 *   主线拿到真实字段名后用 `R260910_SWITCH_SHAPE=A|B|C` 钉死即可去掉试探。
 */
import { test, expect, APIRequestContext } from '@playwright/test';
import {
  apiContext, switchVersion, currentSwitchShape, getVersionOptions,
  readTreeFromDb, overrideRows, costingTotals, sqlRows, sqlNum, saveEvidence,
  COID, BASELINE_TREE, BASELINE_ROW_COUNT,
  EXPECTED_CURRENT_VERSION, V2_TREE_PART_MULTISET, V2_DISAPPEARING_PART,
  pickFrozenOrder, messageOf,
} from './r260910.helpers';

test.describe.configure({ mode: 'serial' });

let api: APIRequestContext;
/** 进场快照，供 afterAll 核对是否真的还原。 */
let entryTree: ReturnType<typeof readTreeFromDb> = [];
let entryOverride: ReturnType<typeof overrideRows> = [];

function skeletonOf(rows: { nodePath: string; lvl: string; parentNo: string }[]): string[] {
  return rows.map((r) => `${r.nodePath}|lvl=${r.lvl}|parent=${r.parentNo || '(root)'}`).sort();
}
function versionMapOf(rows: { nodePath: string; bomVersion: string | null }[]): Record<string, string> {
  return Object.fromEntries(rows.map((r) => [r.nodePath, r.bomVersion ?? '(null)']));
}

test.beforeAll(async () => {
  api = await apiContext();
  entryTree = readTreeFromDb();
  entryOverride = overrideRows();
  console.log(`[R260910] 进场 override 行（本单）= ${JSON.stringify(entryOverride)}`);
  expect(entryTree.length,
    '进场时树就是 0 行 —— 后面所有"切换后树变了/恢复了"的断言都会空跑（假绿）。先查载体渲染').toBe(BASELINE_ROW_COUNT);
});

test.afterAll(async () => {
  // 🚦 无条件还原：不论前面哪条红，都必须把 300001 切回 v3，否则会把污染留给主线亲验。
  try {
    const r = await switchVersion(api, '300001', '3');
    console.log(`[R260910][退场还原] 300001 → v3：HTTP ${r.status}`);
    const now = readTreeFromDb();
    const same = JSON.stringify(skeletonOf(now)) === JSON.stringify(skeletonOf(entryTree)) &&
      JSON.stringify(versionMapOf(now)) === JSON.stringify(versionMapOf(entryTree));
    console.log(`[R260910][退场还原] 树与进场快照一致 = ${same}`);
    if (!same) {
      console.warn('🚨 [R260910] **退场未还原到进场态** —— 请主线在亲验前先核对载体单，\n' +
        `   进场=${JSON.stringify(versionMapOf(entryTree))}\n   现在=${JSON.stringify(versionMapOf(now))}`);
    }
    saveEvidence('49-退场还原核对',
      `进场树=${JSON.stringify(versionMapOf(entryTree), null, 2)}\n现在树=${JSON.stringify(versionMapOf(now), null, 2)}\n` +
      `进场 override=${JSON.stringify(entryOverride)}\n现在 override=${JSON.stringify(overrideRows())}\n` +
      `⚠️ override 行本次**不删除**（DELETE 属 CLAUDE.md §3.2 红线）—— 见 test-report「待回收清单」`);
  } finally {
    await api?.dispose();
  }
});

// ─────────────────────── AC-8 的阳性对照（先跑，用来钉死 body 形状）───────────────────────

test('AC-8(阳性对照) · 非叶子 300001 切到它自己已有的版本 3 → 2xx，且树不变', async () => {
  const before = readTreeFromDb();
  const r = await switchVersion(api, '300001', '3');
  console.log(`[R260910] 阳性对照命中形状 = ${currentSwitchShape()}`);
  expect(r.status,
    `🚨 阳性对照失败：非叶子 300001 切到自己已有的版本 3 也没成功（HTTP ${r.status}）。\n` +
    `   ${r.raw}\n` +
    `   ⚠️ 两种可能，处理动作完全不同：\n` +
    `     ① 请求体形状全部猜错（A/B/C 都不对）→ 报主线要真实字段名（R260910_SWITCH_SHAPE）；\n` +
    `     ② 产品真的拒绝了合法切换 → 才是缺陷。\n` +
    `   ⇒ **不排除 ① 之前，AC-8 的 400 不能当成"叶子守卫生效"的证据。**`)
    .toBeGreaterThanOrEqual(200);
  expect(r.status, `阳性对照应为 2xx，实得 ${r.status}：${r.raw}`).toBeLessThan(300);

  const after = readTreeFromDb();
  expect(skeletonOf(after), '切到当前版本后树骨架不应变化').toEqual(skeletonOf(before));
  saveEvidence('40-AC8阳性对照', `shape=${currentSwitchShape()}\nHTTP ${r.status}\n${r.raw}`);
});

// ─────────────────────── AC-8（边界）───────────────────────

test('AC-8 · 叶子料号 992 发起切换 → 400，且 override 表不新增行', async () => {
  expect(currentSwitchShape(),
    '请求体形状尚未由阳性对照确定 ⇒ 本条的 400 无法归因（可能只是 body 写错）。先看上一条').not.toBeNull();

  // 前置：确认 992 在本单确实还没有 override 行（否则"不新增"恒真）
  const before992 = sqlNum(
    `SELECT count(*) FROM costing_order_version_override WHERE costing_order_id='${COID}' AND part_no='992'`);
  const beforeTotal = sqlNum('SELECT count(*) FROM costing_order_version_override');
  console.log(`[R260910] AC-8 前置：992 的 override 行数=${before992}，全表 ${beforeTotal} 行`);

  const r = await switchVersion(api, '992', '1', { shape: currentSwitchShape()! });
  expect(r.status,
    `🚨 AC-8 违反：对叶子料号 992 发起切换返回 HTTP ${r.status}，期望 400。\n` +
    `   ${r.raw}\n` +
    `   AC 原文：传叶子料号 → 400，消息说明该料号无自有 BOM（防止绕过 UI 直调接口写出无意义 override）。`)
    .toBe(400);
  const msg = messageOf(r.raw);
  console.log(`[R260910] AC-8 错误消息 = ${msg}`);
  expect(msg,
    `AC-8：400 的消息应说明"该料号没有自己的 BOM"，实得「${msg}」（消息为空 = 用户看不懂拒绝原因）`)
    .not.toBe('');

  // 断言"不新增行" —— 用**针对性计数**（本单 + 该料号），不用全表计数
  const after992 = sqlNum(
    `SELECT count(*) FROM costing_order_version_override WHERE costing_order_id='${COID}' AND part_no='992'`);
  expect(after992, `🚨 AC-8 违反：被拒绝后仍给叶子 992 写了 override 行（${before992} → ${after992}）`).toBe(before992);

  // 另外两个叶子一并验（同一条规则的三个实例）
  for (const p of ['991', '300014']) {
    const b = sqlNum(`SELECT count(*) FROM costing_order_version_override WHERE costing_order_id='${COID}' AND part_no='${p}'`);
    const rr = await switchVersion(api, p, '1', { shape: currentSwitchShape()! });
    expect(rr.status, `AC-8：叶子 ${p} 切换应为 400，实得 ${rr.status}：${rr.raw}`).toBe(400);
    const a = sqlNum(`SELECT count(*) FROM costing_order_version_override WHERE costing_order_id='${COID}' AND part_no='${p}'`);
    expect(a, `AC-8：叶子 ${p} 被拒后仍写了 override 行（${b} → ${a}）`).toBe(b);
  }
  saveEvidence('41-AC8-叶子切换被拒', `992 → HTTP ${r.status} ${r.raw}\nmsg=${msg}`);
});

// ─────────────────────── AC-9（边界）───────────────────────

test('AC-9 · 非 PENDING 核价单切换 → 403「仅待核价(PENDING)可切换版本」（既有行为不得改变）', async () => {
  expect(currentSwitchShape(), '形状未确定 ⇒ 403/400 无法区分归因。先看阳性对照').not.toBeNull();

  const frozen = pickFrozenOrder();
  console.log(`[R260910] AC-9 取用冻结单 ${frozen.no}（${frozen.status}） —— 🚦 只读，不改它的状态`);
  const md5Before = frozen.renderMd5;

  // 用**非叶子且确实有候选**的料号发起，确保 403 不是被叶子守卫（AC-8 的 400）抢先拦下
  const r = await switchVersion(api, '300001', '2', { coid: frozen.id, shape: currentSwitchShape()! });
  expect(r.status,
    `🚨 AC-9 违反：对 ${frozen.status} 单 ${frozen.no} 发起切换返回 HTTP ${r.status}，期望 403。\n` +
    `   ${r.raw}\n` +
    `   ⚠️ 若返 400，说明叶子守卫（改动 4）抢在状态校验之前执行了 —— 那会让"状态校验"这条既有行为\n` +
    `      在某些料号上失效，属 E-9「逐字不变」的回归。`).toBe(403);
  const msg = messageOf(r.raw);
  expect(msg, `AC-9：403 的消息应含「PENDING」或「待核价」，实得「${msg}」`).toMatch(/PENDING|待核价/);

  // 冻结单不得被写
  const md5After = sqlRows(`SELECT md5(COALESCE(costing_render::text,'(null)')), status FROM costing_order WHERE id='${frozen.id}'`)[0];
  expect(md5After[0], `🚨 AC-9：被 403 拒绝后 ${frozen.no} 的 costing_render 仍被改写了`).toBe(md5Before);
  expect(md5After[1], `🚨 ${frozen.no} 的状态被改动了（本片禁止改存量单状态）`).toBe(frozen.status);
  const ovr = sqlNum(`SELECT count(*) FROM costing_order_version_override WHERE costing_order_id='${frozen.id}'`);
  expect(ovr, `🚨 AC-9：被 403 拒绝后仍给冻结单写了 override 行（${ovr} 行）`).toBe(0);

  saveEvidence('42-AC9-非PENDING被拒', `${frozen.no}(${frozen.status}) → HTTP ${r.status} ${r.raw}`);
});

// ─────────────────────── AC-6（序列）───────────────────────

test('AC-6 · 300001 由 3 切到 2 → 树按 v2 重画（300014 消失），costing_render 同步更新', async () => {
  const beforeTotals = costingTotals();
  const beforeTree = readTreeFromDb();
  console.log(`[R260910] AC-6 before: renderMd5=${beforeTotals.renderMd5} costingTotal=${beforeTotals.costingTotal}`);
  expect(beforeTree.map((r) => r.partNo),
    `AC-6 前置：切换前树里应存在 ${V2_DISAPPEARING_PART}（它消失才是本条的可观测断言）`).toContain(V2_DISAPPEARING_PART);

  // 前置：候选里确实有 2（否则"切到 2"这个动作本身就没有意义）
  const opt = await getVersionOptions(api, '300001');
  expect(opt.options, 'AC-6 前置：300001 的候选里应有版本 2').toContain('2');

  const r = await switchVersion(api, '300001', '2', { shape: currentSwitchShape()! });
  expect(r.status, `AC-6：切换到 v2 应成功，实得 HTTP ${r.status}：${r.raw}`).toBeLessThan(300);
  expect(r.status, `AC-6：切换到 v2 应成功，实得 HTTP ${r.status}：${r.raw}`).toBeGreaterThanOrEqual(200);

  const afterTree = readTreeFromDb();
  const afterTotals = costingTotals();
  const afterParts = afterTree.map((r2) => r2.partNo);
  console.log(`[R260910] AC-6 after 料号 = ${JSON.stringify(afterParts)}`);

  // 🚨 防假绿：先证明"切换后树不是空的" —— 空树时"300014 不在里面"恒真
  expect(afterTree.length,
    `🚨 AC-6：切到 v2 后树变成 ${afterTree.length} 行（空/极少）。\n` +
    `   空树会让"300014 消失"这条断言恒真，属假绿；同时它本身就是缺陷（切版本把树切没了）。`)
    .toBeGreaterThanOrEqual(4);

  // ① 核心可观测断言：300014 消失（v2 的组成里没有它）
  expect(afterParts,
    `🚨 AC-6 违反：切到 v2 后 ${V2_DISAPPEARING_PART} 仍在树里。\n` +
    `   实证：300001 的 v2 清单 = {300012, 300013, 300015}，**不含 300014**。\n` +
    `   ⇒ 版本过滤没有按 production_no 分档生效。\n   实际料号 = ${JSON.stringify(afterParts)}`)
    .not.toContain(V2_DISAPPEARING_PART);

  // ② 300015 应作为 300001 的直接子件出现（v2 里它排在 item_seq=30）
  expect(afterParts, `AC-6：v2 里 300015 应出现（作为 300001 的直接组成）`).toContain('300015');
  const direct15 = afterTree.find((x) => x.partNo === '300015' && x.parentNo === '300001');
  expect(direct15,
    `AC-6：v2 下 300015 应有一条以 300001 为父的边（node_path=300001/300015）。\n` +
    `   实际各行 = ${JSON.stringify(afterTree.map((x) => `${x.nodePath}(parent=${x.parentNo})`))}`).toBeTruthy();

  // ③ 整棵树的料号多重集与 v2 期望一致
  expect([...afterParts].sort(),
    `AC-6：v2 树的料号多重集不符。\n   实际 ${JSON.stringify([...afterParts].sort())}\n   期望 ${JSON.stringify([...V2_TREE_PART_MULTISET].sort())}`)
    .toEqual([...V2_TREE_PART_MULTISET].sort());

  // ④ 各行版本仍按"自己那张清单"（AC-1 的规则在 v2 下同样成立）
  const badVer = afterTree.filter((x) => {
    const want = EXPECTED_CURRENT_VERSION[x.partNo];
    // 根节点 300001 此刻被切到 2，其余不变
    const expected = x.partNo === '300001' ? '2' : want;
    const got = x.bomVersion;
    return expected === null ? !(got === null || got === '' || got === '—') : got !== expected;
  }).map((x) => `${x.nodePath}: ${x.bomVersion ?? '(null)'}`);
  expect(badVer, `AC-6：v2 下各行版本列取值不符「自己那张清单」规则：\n     ${badVer.join('\n     ')}`).toEqual([]);

  // ⑤ costing_render 同步更新
  expect(afterTotals.renderMd5,
    `🚨 AC-6 违反：切换后 costing_render 的 md5 没变（${afterTotals.renderMd5}）⇒ 渲染没有重算落库`)
    .not.toBe(beforeTotals.renderMd5);

  // ⑥ costing_total_amount —— ⚠️ 证据强度自述
  console.log(`[R260910] AC-6 costing_total_amount: ${beforeTotals.costingTotal} → ${afterTotals.costingTotal}`);
  if (Number(beforeTotals.costingTotal) === 0 && Number(afterTotals.costingTotal) === 0) {
    console.warn(
      '⚠️ [R260910] AC-6 的 `costing_total_amount` 前后都是 0（载体单实测 2026-09-10 即为 0.000000000000）。\n' +
      '   ⇒ 这一半**不构成"同步更新"的正向证据**（0→0 无法区分"同步了但金额确实是 0"与"根本没重算"）。\n' +
      '   costing_render 的 md5 变化是本条真正的证据。已在 test-report 显式标注，请主线知悉。');
  }

  saveEvidence('43-AC6-切到v2',
    `before renderMd5=${beforeTotals.renderMd5} total=${beforeTotals.costingTotal}\n` +
    `after  renderMd5=${afterTotals.renderMd5} total=${afterTotals.costingTotal}\n` +
    `v2 树:\n  ${afterTree.map((x) => `${x.nodePath} lvl=${x.lvl} parent=${x.parentNo || '(root)'} ver=${x.bomVersion ?? '(null)'}`).join('\n  ')}`);
});

// ─────────────────────── AC-7（序列）───────────────────────

test('AC-7 · 切回 3 → 树逐字恢复到 AC-1 的形态（行数/层级/父子/版本列全等）', async () => {
  const beforeTree = readTreeFromDb();
  expect(beforeTree.map((r) => r.partNo),
    `AC-7 前置：本条要验"从 v2 切回 v3"，但当前树里还有 ${V2_DISAPPEARING_PART}（说明上一条没切成 v2）。\n` +
    `   前置不成立时本条会退化成"切了个寂寞也报绿"。`).not.toContain(V2_DISAPPEARING_PART);

  const r = await switchVersion(api, '300001', '3', { shape: currentSwitchShape()! });
  expect(r.status, `AC-7：切回 v3 应成功，实得 HTTP ${r.status}：${r.raw}`).toBeLessThan(300);

  const after = readTreeFromDb();
  expect(after.length, `AC-7：切回后树行数应恢复为 ${BASELINE_ROW_COUNT}，实得 ${after.length}`).toBe(BASELINE_ROW_COUNT);

  // ① 骨架与 AC 原文钉死的基线逐字等
  const wantSk = BASELINE_TREE.map((x) => `${x.nodePath}|lvl=${x.lvl}|parent=${x.parentNo || '(root)'}`).sort();
  expect(skeletonOf(after),
    `🚨 AC-7 违反：切回 v3 后树骨架与基线不等（行数/层级/父子/node_path）。\n` +
    `   实际:\n     ${skeletonOf(after).join('\n     ')}\n   期望:\n     ${wantSk.join('\n     ')}`).toEqual(wantSk);
  expect(after.map((x) => x.partNo), `AC-7：切回后 ${V2_DISAPPEARING_PART} 应重新出现`).toContain(V2_DISAPPEARING_PART);

  // ② 版本列与 AC-1 的期望逐字等
  const bad = after.filter((x) => {
    const want = EXPECTED_CURRENT_VERSION[x.partNo];
    const got = x.bomVersion;
    return want === null ? !(got === null || got === '' || got === '—') : got !== want;
  }).map((x) => `${x.nodePath}: 实得 ${x.bomVersion ?? '(null)'} / 期望 ${EXPECTED_CURRENT_VERSION[x.partNo] ?? '(null=叶子)'}`);
  expect(bad, `🚨 AC-7 违反：切回 v3 后版本列没回到 AC-1 的形态：\n     ${bad.join('\n     ')}`).toEqual([]);

  // ③ 与本片**进场快照**逐字等（第二个独立参照：证明本片没留下结构性残留）
  expect(skeletonOf(after), 'AC-7：与本片进场快照的骨架不等 ⇒ 本片留下了结构性残留').toEqual(skeletonOf(entryTree));

  // ④ override 行的**内容**登记（不断言相等 —— 本片新增 300001 行是预期内的写入面）
  const nowOvr = overrideRows();
  console.log(`[R260910] AC-7 退场 override（本单）= ${JSON.stringify(nowOvr)}`);
  const added = nowOvr.filter((o) => !entryOverride.some((e) => e.componentId === o.componentId && e.partNo === o.partNo));
  saveEvidence('44-AC7-切回v3恢复',
    `树:\n  ${after.map((x) => `${x.nodePath} lvl=${x.lvl} parent=${x.parentNo || '(root)'} ver=${x.bomVersion ?? '(null)'}`).join('\n  ')}\n` +
    `进场 override=${JSON.stringify(entryOverride)}\n退场 override=${JSON.stringify(nowOvr)}\n` +
    `本片新增 override 行（→ 待回收清单）=${JSON.stringify(added)}`);
});
