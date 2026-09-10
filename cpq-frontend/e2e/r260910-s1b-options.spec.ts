/**
 * repair-260910 · S1 片 · **候选列表（接口层）** —— AC-3 / AC-4 / AC-5 / AC-10 / AC-11
 *
 * 层级说明（testing.md：只有单测覆盖的 AC 不算已验收）：
 *   AC-3 / AC-4 在本文件是**接口层**取证，用户可见路径的取证在 `r260910-s1c-render.spec.ts`（UI 层）。
 *   两层测的是同一条 AC 的同一个可观测断言，不是重复覆盖 —— 接口层给精确值，UI 层证明用户真的看得到。
 *
 * 🚫 本文件**全部只读**（只发 GET），不产生任何库写入。
 */
import { test, expect, APIRequestContext } from '@playwright/test';
import {
  apiContext, getVersionOptions, assertOptionsExactly,
  EXPECTED_OPTIONS, EXPECTED_CURRENT_VERSION, LEAF_PARTS,
  CID_TREE, CID_FLAT, CID_DETAIL_TREE, sqlRows, sqlRead, saveEvidence,
} from './r260910.helpers';

/*
 * ⚠️ 刻意**不用** `mode: 'serial'`：本文件各条是**互相独立的探针/断言**，
 *    serial 的 fail-fast 会在第一条红之后把其余整片标成 skipped ——
 *    2026-09-10 实证：P3 红了一次，P4（配置还原点，与 P3 毫无依赖）就再也没跑，
 *    报告里看起来像"只有一个问题"。写入型的 s1e 才需要 serial。
 */

let api: APIRequestContext;
test.beforeAll(async () => { api = await apiContext(); });
test.afterAll(async () => { await api?.dispose(); });

// ─────────────────────── AC-3（单点·核心）───────────────────────

test('AC-3 · 根节点 300001 候选恰好 [3,2,1]，不再是「无可选版本」', async () => {
  const r = await getVersionOptions(api, '300001');
  expect(r.status, `version-options 应返回 200（实得 ${r.status}）：${r.raw}`).toBe(200);
  expect(r.partNo, '响应里的 partNo 应回显请求值').toBe('300001');

  // 🚨 防假绿：先证明拿到了非空候选，再比内容（空数组恰是待修 bug 的样子）
  assertOptionsExactly(r.options, EXPECTED_OPTIONS['300001'], 'AC-3 · 300001 候选');

  // AC-1 的接口侧同源证据：currentVersion = 自己那张清单的当前版本
  expect(String(r.currentVersion),
    `AC-3 · 300001 的 currentVersion 应为 3（自己那张清单 is_current 的版本），实得 ${r.currentVersion}`)
    .toBe(EXPECTED_CURRENT_VERSION['300001']);

  saveEvidence('10-AC3-根节点候选', r.raw);
});

// ─────────────────────── AC-4（单点·核心）───────────────────────

test('AC-4 · 300012/300013/300015 候选恰好 [1]，且 300015 不得出现版本 2', async () => {
  const collected: string[] = [];
  for (const p of ['300012', '300013', '300015']) {
    const r = await getVersionOptions(api, p);
    expect(r.status, `${p}: HTTP ${r.status} ${r.raw}`).toBe(200);
    collected.push(r.raw);
    assertOptionsExactly(r.options, EXPECTED_OPTIONS[p], `AC-4 · ${p} 候选`);
    expect(String(r.currentVersion), `AC-4 · ${p} 的 currentVersion 应为 1`).toBe(EXPECTED_CURRENT_VERSION[p]);
  }

  // 🚨 AC-4 的**专项反向断言**（repair-0590 前科 / 问题说明 E-6）：
  //    300015 同时挂在 300012(v1) 与 300001(v2) 下。按"查子件所在边"会给出 2，
  //    按"查自己那张清单"只有 1。这一条单独断言，不能被上面的集合相等顺带带过 ——
  //    集合断言红了只会说"不等"，说不清是哪种错法。
  const r15 = await getVersionOptions(api, '300015');
  expect(r15.options,
    `🚨 AC-4/E-6 违反：300015 的候选里出现了版本 2。\n` +
    `   2 是它**挂在 300001 下那条边**的版本，不是它自己那张清单的版本。\n` +
    `   ⇒ 候选来源查错了轴（按 component_no 查而不是按 production_no 查）。\n` +
    `   实得 ${JSON.stringify(r15.options)}`).not.toContain('2');

  saveEvidence('11-AC4-中间层候选', collected.join('\n'));
});

// ─────────────────────── AC-5（单点）───────────────────────

test('AC-5 · 候选含历史表数据：1/2 只在 _history、3 在主表，三者同现', async () => {
  // ① 先用只读 SQL 证明"1、2 确实只存在于 _history，3 确实在主表" ——
  //    没有这一步，"三者同现"证明不了"历史+当前"口径，只能证明"有三个数"
  const main = sqlRows(
    `SELECT DISTINCT version_no::text FROM ds_cost_basic_material_bom WHERE production_no='300001' ORDER BY 1`)
    .map((r) => r[0]);
  const hist = sqlRows(
    `SELECT DISTINCT version_no::text FROM ds_cost_basic_material_bom_history WHERE production_no='300001' ORDER BY 1`)
    .map((r) => r[0]);
  console.log(`[R260910] 300001 主表版本=${JSON.stringify(main)}  历史表版本=${JSON.stringify(hist)}`);

  expect(main.length, '主表里 300001 一行都没有 —— 前置数据漂移，报主线').toBeGreaterThan(0);
  expect(hist.length, '历史表里 300001 一行都没有 —— AC-5 无从验起，报主线').toBeGreaterThan(0);
  expect(main, 'AC-5 前置：版本 3 应在主表').toContain('3');
  expect(hist.sort(), 'AC-5 前置：版本 1、2 应在历史表').toEqual(['1', '2']);
  expect(main, 'AC-5 前置：版本 1 不应出现在主表（否则"只在历史表"的前提不成立）').not.toContain('1');
  expect(main, 'AC-5 前置：版本 2 不应出现在主表').not.toContain('2');

  // ② 接口把两边都给出来了，才算"历史 + 当前"口径成立
  const r = await getVersionOptions(api, '300001');
  assertOptionsExactly(r.options, ['3', '2', '1'], 'AC-5 · 300001 候选（历史+当前）');
  for (const v of hist) {
    expect(r.options, `AC-5：历史版本 ${v} 未出现在候选里 ⇒ 候选只查了主表，没查 _history`).toContain(v);
  }
  for (const v of main) {
    expect(r.options, `AC-5：主表当前版本 ${v} 未出现在候选里 ⇒ 候选只查了 _history`).toContain(v);
  }
  saveEvidence('12-AC5-历史与当前同现',
    `主表版本=${JSON.stringify(main)}\n历史表版本=${JSON.stringify(hist)}\n接口候选=${JSON.stringify(r.options)}\n${r.raw}`);
});

// ─────────────────────── AC-2 的接口侧同源证据（叶子无版本）───────────────────────

test('AC-2(接口侧) · 叶子 992/991/300014 → 空候选 + currentVersion 为 null；阳性对照：同批非叶子非空', async () => {
  // 🚨 阳性对照先跑：证明"这一轮接口是能返回非空候选的"，
  //    否则叶子返空可能只是整条链路全空（正是待修 bug 的样子），断言会恒真。
  const ctrl = await getVersionOptions(api, '300001');
  expect(ctrl.options?.length,
    '阳性对照失败：非叶子 300001 也返空 ⇒ 无法把"叶子返空"归因到叶子规则。先看 AC-3').toBeGreaterThan(0);

  const lines = [`阳性对照 300001 → ${JSON.stringify(ctrl.options)}`];
  for (const p of LEAF_PARTS) {
    const r = await getVersionOptions(api, p);
    lines.push(`叶子 ${p} → HTTP ${r.status} ${r.raw}`);
    expect(r.status, `叶子 ${p} 不应报错（实得 ${r.status}）`).toBe(200);
    expect(r.options, `AC-2：叶子 ${p} 的候选应为空数组，实得 ${JSON.stringify(r.options)}`).toEqual([]);
    expect(r.currentVersion, `AC-2：叶子 ${p} 的 currentVersion 应为 null，实得 ${r.currentVersion}`).toBeNull();
  }
  saveEvidence('13-AC2接口侧-叶子空候选', lines.join('\n'));
});

// ─────────────────────── AC-10（边界）───────────────────────

test('AC-10 · COST_DETAIL 方言的树组件走 detail 视图：空候选且不报错，🚫 不许回落 basic', async () => {
  if (!CID_DETAIL_TREE) {
    // 🚫 刻意不 skip：skip 会把"没有前置"洗成绿，覆盖缺口就此消失（testing.md 假绿第 1 类）
    const probe = sqlRows(`
      SELECT c.code, c.name, c.bom_recursive_expand::text, c.status
      FROM component c JOIN component_sql_view v ON v.component_id=c.id
      WHERE v.sql_template ILIKE '%ds_cost_detail%' ORDER BY c.code`);
    throw new Error(
      `🚨 AC-10 **前置缺失，不是产品缺陷**：需要一个「COST_DETAIL 方言 + bom_recursive_expand=true」的组件，\n` +
      `   否则调不到本次改动的那条树分支。\n` +
      `   实查（本轮）库里引用 ds_cost_detail_* 的组件共 ${probe.length} 个，全部非树：\n` +
      probe.map((r) => `     ${r[0]} | ${r[1]} | tree=${r[2]} | ${r[3]}`).join('\n') + '\n' +
      `   ⇒ 请主线/后端提供一个（前缀 R260910-），或用 R260910_CID_DETAIL_TREE=<uuid> 注入。`);
  }

  // 🚨 判别式设计：300001 在 **basic** 视图里有 [3,2,1]，在 **detail** 视图里 0 行（已实查）。
  //    ⇒ 若返回非空，说明回落到了 basic 视图 —— 这正是 AC-10 要拦的错法。
  const detailRows = sqlRead(
    `SELECT count(*)::text FROM v_ds_cost_detail_material_bom_all WHERE production_no='300001'`);
  expect(Number(detailRows),
    `AC-10 的判别式失效：detail 视图里 300001 现在有 ${detailRows} 行了（前置漂移）。\n` +
    `   本用例靠"basic 有 / detail 无"来区分"走对视图"与"回落 basic"，该前提不成立就换一个判别料号。`).toBe(0);

  const r = await getVersionOptions(api, '300001', { componentId: CID_DETAIL_TREE });
  expect(r.status, `AC-10：不许 500 —— 实得 HTTP ${r.status} ${r.raw}`).toBe(200);
  expect(r.options, `AC-10：detail 视图无该料号 ⇒ 候选必须为空。非空 = 回落到了 basic 视图`).toEqual([]);
  expect(r.currentVersion, 'AC-10：无数据时 currentVersion 应为 null').toBeNull();
  saveEvidence('14-AC10-COST_DETAIL方言', `componentId=${CID_DETAIL_TREE}\n${r.raw}`);
});

// ─────────────────────── AC-11（无副作用 · 候选侧）───────────────────────

test('AC-11(候选侧) · 非树组件 COMP-2300 对 300013 仍能列出候选 [3,2,1]', async () => {
  // 改动前实测基线（2026-09-10，见 证据/e2e-s1/基线-改动前.txt [B8]）：
  //   {"componentId":"9291b050-…","partNo":"300013","currentVersion":"2","options":["3","2","1"]}
  const r = await getVersionOptions(api, '300013', { componentId: CID_FLAT });
  expect(r.status, `HTTP ${r.status} ${r.raw}`).toBe(200);
  assertOptionsExactly(r.options, ['3', '2', '1'], 'AC-11 · 非树组件 300013 候选');
  expect(r.componentId, '响应应回显非树组件 id').toBe(CID_FLAT);

  // ⚠️ currentVersion 这里是 "2"，来自**已存在的 override 行**（2026-09-10 07:36 那次成功切换）。
  //    它是 E-9「override 覆盖优先级逐字不变」的活证据 —— 若变成 "3"（视图 is_current）
  //    说明 override 优先级被改动波及了。
  expect(String(r.currentVersion),
    `AC-11/E-9：非树组件的 currentVersion 应仍取 override 行的值 2（override 优先于视图 is_current）。\n` +
    `   实得 ${r.currentVersion} —— 若为 3，说明 override 优先级被本次改动波及。\n` +
    `   ⚠️ 若库里那行 override 被别人改了/删了，这条会红但**不是产品缺陷**，先查 costing_order_version_override。`)
    .toBe('2');

  saveEvidence('15-AC11-非树组件候选', r.raw);
});

test('AC-11(隔离) · 树组件与非树组件互不串号：同一 partNo 两个组件给出不同候选', async () => {
  // 300013 在 basic BOM 视图里只有 v1（树组件应给 [1]），在材质元素数据集里有 [3,2,1]。
  // 两者若相等，说明 componentId 没有参与候选解析（cache key 缺组件维度 / 分支选错）。
  const tree = await getVersionOptions(api, '300013', { componentId: CID_TREE });
  const flat = await getVersionOptions(api, '300013', { componentId: CID_FLAT });
  assertOptionsExactly(tree.options, EXPECTED_OPTIONS['300013'], 'AC-11 隔离 · 树组件 300013');
  assertOptionsExactly(flat.options, ['3', '2', '1'], 'AC-11 隔离 · 非树组件 300013');
  expect(JSON.stringify(tree.options),
    `同一料号在树/非树两个组件上给出了**相同**候选 ⇒ componentId 未参与解析（串号）。\n` +
    `   树=${JSON.stringify(tree.options)} 非树=${JSON.stringify(flat.options)}`)
    .not.toBe(JSON.stringify(flat.options));
  saveEvidence('16-AC11-组件隔离', `tree(${CID_TREE}) → ${tree.raw}\nflat(${CID_FLAT}) → ${flat.raw}`);
});
