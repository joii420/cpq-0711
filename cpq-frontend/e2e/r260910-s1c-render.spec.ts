/**
 * repair-260910 · S1 片 · **UI 渲染层** —— AC-1 / AC-2（+ AC-3 / AC-4 的用户可见路径复核）
 *
 * 为什么 UI 层不可省：用户报的现象就是**点开下拉看到「无可选版本」**。
 * 接口层绿只证明「后端会给候选」，证明不了「用户点得到、看得见」——
 * 中间还隔着 `bom_version` 是否被渲染成下拉、下拉是否绑对了 partNo。
 *
 * 🚫 本文件全部只读（只看不点选项，不触发任何切换）。
 *    唯一的副作用风险是"打开报价单编辑页可能触发 autosave" —— 已在回报里向主线登记。
 */
import { test, expect } from '@playwright/test';
import {
  uiLogin, enterCostingCard, enterCostingReviewPage, cardOf, switchTabInCard, readUiTree, openVersionDropdown,
  EXPECTED_CURRENT_VERSION, EXPECTED_OPTIONS, LEAF_PARTS, NON_LEAF_PARTS,
  BASELINE_ROW_COUNT, TAB_TREE, shot, saveEvidence,
} from './r260910.helpers';

/*
 * ⚠️ 刻意**不用** `mode: 'serial'`：本文件各条是**互相独立的探针/断言**，
 *    serial 的 fail-fast 会在第一条红之后把其余整片标成 skipped ——
 *    2026-09-10 实证：P3 红了一次，P4（配置还原点，与 P3 毫无依赖）就再也没跑，
 *    报告里看起来像"只有一个问题"。写入型的 s1e 才需要 serial。
 */

/** 载体产品卡片的锚（S0001 铆钉 = 树根 300001 那张卡）。 */
const CARD_ANCHOR = process.env.R260910_CARD_ANCHOR || 'S0001';

/*
 * 🚨 **不在 beforeEach 里统一进视图** —— 本任务三个视图的版本列形态完全不同：
 *    核价工作台=可交互 antd 下拉 / 编辑页 Step2=原生只读壳 / 详情页=纯文本。
 *    一刀切会让某几条 AC 跑在错误视图上还报绿（本片首跑实测：在 Step2 上验 AC-3/AC-4，
 *    量具若退化成"读 <option> 就算候选"，会在**根本没有候选交互**的视图上验出绿）。
 */
test.beforeEach(async ({ page }) => {
  test.setTimeout(300_000);
  await uiLogin(page);
});

// ─────────────────────── AC-1（单点）───────────────────────

test('AC-1 · 树上每行版本 = 自己那张清单的当前版本（3/1/1/—/1/—/—），不再是 3/3/3/3', async ({ page }) => {
  await enterCostingCard(page);            // 报价单编辑页 Step2 → 核价单
  const card = await cardOf(page, CARD_ANCHOR);
  await switchTabInCard(card, TAB_TREE);
  const rows = await readUiTree(card);
  await shot(page, 'AC1-BOM树版本列');

  // 🚨 防假绿①：先证明表里真有行 —— 0 行时下面的 for 循环 0 次，断言全部空跑却报绿
  expect(rows.length,
    `BOM 页签 0 行 ⇒ 下面的逐行断言会一条都不执行（假绿）。先查载体渲染是否正常`).toBeGreaterThan(0);
  expect(rows.length, `AC-13：树行数应为 ${BASELINE_ROW_COUNT}（本次只改版本列取值，不得改变行数）`).toBe(BASELINE_ROW_COUNT);

  // 🚨 防假绿②：先证明"料号列读得出真值"，否则 partNo 全空时下面按料号查找会全部落空
  const parts = rows.map((r) => r.partNo);
  console.log(`[R260910] AC-1 实际料号列 = ${JSON.stringify(parts)}`);
  console.log(`[R260910] AC-1 实际版本列 = ${JSON.stringify(rows.map((r) => r.versionText))}`);
  expect(parts.filter((p) => p !== '').length, '料号列全空 —— 读取口径不对（先看 dump）').toBe(rows.length);

  const missing = Object.keys(EXPECTED_CURRENT_VERSION).filter((p) => !parts.includes(p));
  expect(missing, `树里缺料号 ${JSON.stringify(missing)} —— 前置树结构变了，报主线（不是版本列的问题）`).toEqual([]);

  const actual: Record<string, string> = {};
  for (const r of rows) actual[r.partNo] = r.versionText;

  for (const p of NON_LEAF_PARTS) {
    const want = EXPECTED_CURRENT_VERSION[p]!;
    expect(actual[p],
      `🚨 AC-1 违反：料号 ${p} 的版本列显示「${actual[p]}」，期望「${want}」。\n` +
      `   AC 原文：每行显示**该料号自己那张 BOM** 的当前版本，🚫 不是上级那张清单的版本。\n` +
      `   （改动前的错值是 ${p === '300001' ? '3（恰好相同，本行不能当判据）' : '3'}）\n` +
      `   本轮全表实际 = ${JSON.stringify(actual)}`).toBe(want);
  }
  for (const p of LEAF_PARTS) {
    expect(actual[p],
      `🚨 AC-1/AC-2 违反：叶子 ${p} 的版本列显示「${actual[p]}」，期望占位「—」（空）。\n` +
      `   本轮全表实际 = ${JSON.stringify(actual)}`).toMatch(/^(—|-|–|)$/);
  }

  // 🚨 证伪性自检：若 300012/300013 仍是 3，说明改动 1（骨架递归体）没生效 —— 单独报出来
  expect([actual['300012'], actual['300013']].join(','),
    `AC-1：300012/300013 仍显示上级清单的版本 3 ⇒ 骨架 SQL 递归体的 bom_version 还取 ch.version_no`)
    .not.toBe('3,3');

  saveEvidence('20-AC1-版本列实际值', JSON.stringify(actual, null, 2));
});

// ─────────────────────── AC-2（单点 + 阳性对照）───────────────────────

test('AC-2 · 叶子行版本列为「—」且 DOM 无版本控件壳；阳性对照：同表非叶子行仍有壳', async ({ page }) => {
  await enterCostingCard(page);            // 报价单编辑页 Step2 → 核价单
  const card = await cardOf(page, CARD_ANCHOR);
  await switchTabInCard(card, TAB_TREE);
  const rows = await readUiTree(card);
  await shot(page, 'AC2-叶子无下拉');

  expect(rows.length, 'BOM 页签 0 行 ⇒ 断言空跑（假绿）').toBe(BASELINE_ROW_COUNT);
  const byPart = new Map(rows.map((r) => [r.partNo, r]));

  // ① 阳性对照**先断言** —— 证明"这一轮下拉是渲染得出来的"。
  //    没有它，"整表都没渲染下拉"会被读成"叶子规则生效"，是本任务最容易踩的假绿。
  // 🚨 「壳」= antd `.ant-select` **或**原生 `<select>`（本视图是后者，见 helpers 的视图对照表）。
  //    只认 antd 一种形态会得出"整表都没渲染"的错误结论（本片首跑实测踩到）。
  const withSelect = NON_LEAF_PARTS.filter((p) => byPart.get(p)?.hasSelect);
  console.log(`[R260910] AC-2 阳性对照：非叶子行有壳的 = ${JSON.stringify(withSelect)}`);
  expect(withSelect.length,
    `🚨 阳性对照失败：同一张表里**非叶子行也没有**版本控件壳（${JSON.stringify(NON_LEAF_PARTS)} 全无）。\n` +
    `   ⇒ 无法把"叶子没壳"归因到叶子规则 —— 可能是整表都没渲染。\n` +
    `   本轮各行 = ${JSON.stringify(rows.map((r) => [r.partNo, `antd${r.antdSelect}/native${r.nativeSelect}`]))}`)
    .toBe(NON_LEAF_PARTS.length);

  // ② 叶子断言
  for (const p of LEAF_PARTS) {
    const r = byPart.get(p);
    expect(r, `树里找不到叶子行 ${p}`).toBeTruthy();
    expect(r!.hasSelect,
      `🚨 AC-2 违反：叶子 ${p} 那一行仍渲染了版本控件壳` +
      `（antd ${r!.antdSelect} / native ${r!.nativeSelect}，其中 disabled ${r!.nativeSelectDisabled}）。\n` +
      `   AC 原文：叶子节点版本列为 —，且 DOM 中**不存在** VersionSelectDropdown。`).toBe(false);
    expect(r!.versionText,
      `AC-2：叶子 ${p} 的版本列应显示占位「—」，实得「${r!.versionText}」`).toMatch(/^(—|-|–|)$/);
  }

  saveEvidence('21-AC2-各行壳存在性',
    rows.map((r) => `${r.partNo}\t壳=${r.hasSelect}\tantd=${r.antdSelect}\tnative=${r.nativeSelect}` +
      `\tnativeDisabled=${r.nativeSelectDisabled}\tversion="${r.versionText}"`).join('\n'));
});

// ─────────────────────── AC-3 / AC-4 的 UI 复核 ───────────────────────

test('AC-3(UI) · 核价工作台点开 300001 的版本下拉，选项恰好 3/2/1，不再出现「无可选版本」', async ({ page }) => {
  await enterCostingReviewPage(page);      // 🚨 唯一渲染可交互下拉的视图
  const card = await cardOf(page, CARD_ANCHOR);
  await switchTabInCard(card, TAB_TREE);

  const { options, emptyText } = await openVersionDropdown(card, '300001');
  await shot(page, 'AC3-300001下拉展开');

  // 🚨 防假绿：下拉没展开时 `.ant-select-item-option` 恒 0 个 ⇒ 必须先断言 > 0
  expect(options.length,
    `🚨 AC-3 违反：300001 的下拉展开后**一个选项都没有**${emptyText ? `（空态文案「${emptyText}」）` : ''}。\n` +
    `   这正是用户报的现象。⚠️ 也可能是下拉根本没展开 —— 截图 AC3-300001下拉展开 里能分辨。`)
    .toBeGreaterThan(0);
  expect(emptyText, `AC-3：下拉里仍出现空态文案「${emptyText}」`).toBe('');
  expect([...options].sort(), `AC-3：下拉选项应恰好为 3/2/1，实得 ${JSON.stringify(options)}`)
    .toEqual([...EXPECTED_OPTIONS['300001']].sort());

  saveEvidence('22-AC3-UI下拉选项', `300001 → ${JSON.stringify(options)}  空态="${emptyText}"`);
});

test('AC-4(UI) · 核价工作台：300012/300013/300015 的下拉各只有 1 项，且 300015 里没有 2', async ({ page }) => {
  await enterCostingReviewPage(page);      // 🚨 唯一渲染可交互下拉的视图
  const card = await cardOf(page, CARD_ANCHOR);
  await switchTabInCard(card, TAB_TREE);

  const log: string[] = [];
  for (const p of ['300012', '300013', '300015']) {
    const { options, emptyText } = await openVersionDropdown(card, p);
    log.push(`${p} → ${JSON.stringify(options)}  空态="${emptyText}"`);
    expect(options.length,
      `🚨 AC-4/E-7 违反：${p} 的下拉是空的${emptyText ? `（「${emptyText}」）` : ''}。\n` +
      `   E-7 原文：真的只有 1 个版本时**照常显示该单项**，🚫 不许因为"只有一个就不给切"而隐藏。`)
      .toBeGreaterThan(0);
    expect([...options].sort(), `AC-4：${p} 的下拉选项应恰好为 ${JSON.stringify(EXPECTED_OPTIONS[p])}`)
      .toEqual([...EXPECTED_OPTIONS[p]].sort());
    if (p === '300015') {
      expect(options, `🚨 AC-4/E-6 违反：300015 的下拉里出现了版本 2（那是它挂 300001 下那条边的版本）`)
        .not.toContain('2');
    }
  }
  await shot(page, 'AC4-中间层下拉');
  saveEvidence('23-AC4-UI下拉选项', log.join('\n'));
});

// ─────────────────────── AC-16（开工后扩范围并入 S1，主线 2026-09-10 裁决）───────────────────────

/**
 * AC 原文（`问题说明.md` §五之后新增段）：
 *   报价单**编辑页 Step2** 的核价视图中，叶子节点（`bom_version` 为空/空白）版本列渲染为 **`—` 纯文本**，
 *   🚫 **不渲染 `<select>` 壳**（含 disabled 壳）；非叶子行仍渲染只读壳且显示正确版本号（阳性对照）。
 *   判空口径与 F-1 一致：`!= null && trim() !== ''`，🚫 不许用 `!!` —— 版本 `"0"` 是合法值。
 *
 * 🚨 与 AC-2 的分工：AC-2 管「叶子无 VersionSelectDropdown」，AC-16 管「叶子连 `<select>` 标签
 *    都不该存在」。二者在**本视图**（只读壳视图）上的可观测量不同，不是重复覆盖 ——
 *    AC-2 的壳口径若只认 antd，会在这个只读壳视图上恒真（首跑实证）。
 */
test('AC-16 · 编辑页 Step2 核价视图：叶子无任何 <select> 标签 + 非叶子仍有只读壳（含 "0" 不被真值判断吞掉）', async ({ page }) => {
  await enterCostingCard(page);            // 报价单编辑页 Step2 → 核价单
  const card = await cardOf(page, CARD_ANCHOR);
  await switchTabInCard(card, TAB_TREE);
  const rows = await readUiTree(card);
  await shot(page, 'AC16-编辑页只读壳');

  expect(rows.length, 'AC-16：BOM 页签 0 行 ⇒ 断言空跑（假绿）').toBe(BASELINE_ROW_COUNT);
  const byPart = new Map(rows.map((r) => [r.partNo, r]));

  // ① 阳性对照先跑：非叶子**必须**有只读壳，且壳内值正确。
  //    没有它，"叶子没有 <select>" 可能只是整表都没渲染壳。
  const shellful = NON_LEAF_PARTS.filter((p) => (byPart.get(p)?.nativeSelect ?? 0) > 0);
  console.log(`[R260910] AC-16 阳性对照：非叶子行有原生 <select> 壳的 = ${JSON.stringify(shellful)}`);
  expect(shellful.length,
    `🚨 AC-16 阳性对照失败：非叶子行也没有原生 <select> 只读壳。\n` +
    `   ⇒ 无法把"叶子无壳"归因到判空规则 —— 可能整表都没渲染壳。\n` +
    `   本轮各行 = ${JSON.stringify(rows.map((r) => [r.partNo, `native${r.nativeSelect}(disabled ${r.nativeSelectDisabled})`]))}`)
    .toBe(NON_LEAF_PARTS.length);
  for (const p of NON_LEAF_PARTS) {
    expect(byPart.get(p)!.versionText,
      `AC-16：非叶子 ${p} 的只读壳内应显示 ${EXPECTED_CURRENT_VERSION[p]}，实得「${byPart.get(p)!.versionText}」`)
      .toBe(EXPECTED_CURRENT_VERSION[p]);
  }

  // ② 叶子：DOM 里**连 <select> 标签都不该存在**（不是"壳还在但显示 —"）
  for (const p of LEAF_PARTS) {
    const r = byPart.get(p);
    expect(r, `AC-16：树里找不到叶子行 ${p}`).toBeTruthy();
    expect(r!.nativeSelect,
      `🚨 AC-16 违反：叶子 ${p} 的版本列 DOM 里仍有 ${r!.nativeSelect} 个 <select> 标签` +
      `（其中 disabled ${r!.nativeSelectDisabled}）。\n` +
      `   AC 原文要求：叶子渲染为 —— 纯文本，🚫 不渲染 <select> 壳（含 disabled 壳）。`).toBe(0);
    expect(r!.antdSelect, `AC-16：叶子 ${p} 也不应有 antd 下拉`).toBe(0);
    expect(r!.versionText, `AC-16：叶子 ${p} 应渲染占位「—」，实得「${r!.versionText}」`).toMatch(/^(—|-|–)$/);
  }

  // ③ 判空口径：`"0"` 是合法版本，不得被 `!!` 吞掉。
  //    🚨 当前载体的 7 个料号里**没有版本 "0"** ⇒ 这条在本数据上**无法取得正向证据**。
  //       如实报「未验证」，🚫 不许因为"逻辑上应该没问题"就打勾（testing.md 假绿第 1 类）。
  const hasZero = rows.some((r) => r.versionText === '0');
  console.log(
    `[R260910] AC-16 ③ 判空口径（版本 "0" 合法）：本轮实际版本值 = ` +
    `${JSON.stringify(rows.map((r) => r.versionText))}\n` +
    `           其中存在 "0" = ${hasZero}\n` +
    (hasZero ? '' :
     '           ⚠️ **未验证**：载体数据里没有版本 "0" 的料号 ⇒ 无法区分实现用的是 `!= null && trim() !== \'\'`\n' +
     '              还是 `!!`（两者对本轮全部取值行为一致）。要取证需要一条 version_no=0 的 BOM 记录，\n' +
     '              属造数前置，已报主线。🚫 不据此打勾。'));

  saveEvidence('24-AC16-编辑页只读壳',
    rows.map((r) => `${r.partNo}\tnative=${r.nativeSelect}(disabled ${r.nativeSelectDisabled})` +
      `\tantd=${r.antdSelect}\tversion="${r.versionText}"`).join('\n') +
    `\n\n③ 判空口径（版本 "0"）：本轮无 "0" 值 ⇒ **未验证**，需 version_no=0 的造数前置。`);
});
