/**
 * E2E · task-260908「取数配置器优化」· 分片 **S2（配置器交互）**
 *
 * 认领 8 条 AC：**AC-13 / AC-14 / AC-15 / AC-16 / AC-17 / AC-18 / AC-19 / AC-25**
 * （`test.md §2` 追溯矩阵；对应派工项 F-1 / F-2 / F-3）
 *
 * ─────────────────────────────────────────────────────────────────────
 * 🚫 **本文件不读实现源码**。断言逐条派生自 `需求文档.md §③` 的 AC 原文，
 *    视觉基准取 `原型图/01-组件绑定区.html`（状态 4 / 状态 5）与
 *    `原型图/02-取数配置器-已选输出列.html`（状态 1 / 状态 3 / 状态 4 / 状态 5）。
 *
 * ─────────────────────────────────────────────────────────────────────
 * 🧩 分片隔离
 *   · 造数前缀 `T260908-S2-`（组件 + 专属目录），`afterAll` 全部回收
 *   · 🚫 **全片零全局计数断言** —— 不写「组件列表共 N 条」「行键列全库 N 个」这类；
 *     共库并行时别片造一条数据就把本片打红，且**红得像业务回归**（testing.md §4.5）
 *   · 只读打开的现网组件仅 AC-25 的 `010a2589-…`，全程**不点保存**
 *   · 🚫 不改任何全局状态（用户启停用 / 角色权限 / 模板发布态 / 公共基础数据）
 *
 * ─────────────────────────────────────────────────────────────────────
 * 🚨 本片刻意避开的两个「会恒绿」的陷阱（写用例时最容易踩，写在最前面）
 *
 *   ① **AC-16 不许按显示名判残留。**
 *      实测（2026-09-08 field-tree）：`物料BOM` 与 `来料其他费用` 两个源的行键列
 *      **显示名完全同名** —— 都是「销售料号」「投入料号」。
 *      切源后若上一个源的列没被清掉，按显示名比对**看起来一模一样，断言照样绿**。
 *      ⇒ 一律按**视图列名**判：`_物料BOM_销售料号` vs `_来料其他费用_销售料号`。
 *
 *   ② **AC-13 不许拿现网组件验。**
 *      AC 原文已写明：全库仅 2 个组件配了元素绑定，两个都有 builder_config 且
 *      `tabType='材质元素'` ⇒ `boundSemantic` 恒为 `'MATERIAL_ELEMENT'`，
 *      判据无论怎么写都会绿 = **零证据**。⇒ 自造 `boundSemantic === undefined` 那一态。
 *
 * ─────────────────────────────────────────────────────────────────────
 * ⚠️ 运行环境（`test.md §4`）
 *   🚫 5174 / 8081 是共享 dev server，服务**主工作区已合并代码**，看不到本 worktree 的
 *      F-2/F-3 改动 ⇒ 拿它跑出来的绿是假绿，且这两个端口保留给主线亲验。
 *      `assertIsolatedEnv()` 会直接拦下。
 *
 *   PW_BASE_URL=http://localhost:5175 PW_BACKEND_URL=http://localhost:8082 \
 *     npx playwright test --config=e2e/task260908-s2.config.ts --reporter=list
 */
import { test, expect, type Page } from '@playwright/test';
import { loginAsAdmin } from './fixtures/auth';
import {
  S2, BACKEND_URL, DB_DESC, assertIsolatedEnv, psql, setCookieHeader, cleanupFixtures,
  createFixtureComponent, newComponentInBuilder, openComponentByCode,
  selectDataset, selectSource, addField, expandAllGroups,
  readSelectedColumns, findCol, clickRemove, serverRowKeys, fieldTree,
  readBindingArea, watchJsErrors, hasRedOverlay, shot,
} from './task260908-s2.helpers';

/** AC-25 点名的存量组件（2026-09-08 实测：无 builder_config、无元素绑定）。 */
const AC25_COMPONENT_ID = '010a2589-dfa4-4606-9a3d-9917bc0c2380';

// 🚫 刻意**不用** `describe.configure({ mode: 'serial' })`：
//    serial 模式下第一条红了，后面全部变成 skipped —— 报告里 8 条 AC 会有 7 条「未执行」，
//    而「未执行」和「通过」在草率的读法下长得一样。
//    串行执行由 config 的 `workers: 1` + `fullyParallel: false` 保证；
//    每条用例各自造自己的夹具，互不依赖，一条红不该带走其余七条的结论。

test.beforeAll(() => {
  assertIsolatedEnv();
  console.log(`[S2] 造数前缀=${S2} 库=${DB_DESC}`);
});

test.beforeEach(async ({ page, context }) => {
  await loginAsAdmin(page);
  setCookieHeader((await context.cookies()).map((c) => `${c.name}=${c.value}`).join('; '));
});

test.afterAll(async () => {
  await cleanupFixtures();
});

// ═══════════════════════════════════════════════════════════════════════
// T0 · 量具自检（不服务 AC，但**不过就不许看后面的结论**）
//
// `test.md §4.2`：「每个量具先证明它会动」。本片三个量具：
//   ① 已选输出列读取器  ② 绑定区读取器  ③ 服务端行键集合
// 三个里任何一个取不到东西，后面的「不含 X」「不残留 Y」全部会**恒真通过**。
// ═══════════════════════════════════════════════════════════════════════
test('T0 · 量具自检：三个量具都能取到非空值（不过 ⇒ 后面所有绿都不作数）', async ({ page }) => {
  test.setTimeout(180_000);

  const fx = await newComponentInBuilder(page, 'T0');

  // 量具③：服务端行键集合（顺带证明 field-tree 通）
  const rk = await serverRowKeys(page, 'BOM', '', 'QUOTE');
  expect(rk.length, 'T0：报价·物料BOM 的服务端行键列应为 2（销售料号 / 投入料号）').toBe(2);

  // 量具①：已选输出列
  await selectSource(page, '物料BOM');
  const rows = await readSelectedColumns(page, 'T0 选源后');
  const parsed = rows.filter((r) => r.fieldName || r.leaves.length);
  expect(
    parsed.length,
    'T0：已选列取到了行，但每行都解析不出字段名/叶子文本 ⇒ 量具坏了。'
    + '后面「字段名应为 X」会**永远比对空串**，那是假绿。',
  ).toBeGreaterThan(0);
  expect(
    rows.some((r) => r.fieldName !== ''),
    'T0：没有任何一行读到「字段名输入框」的值 ⇒ AC-17/18/19 的量具是坏的（恒比空串）。判【未验证】',
  ).toBe(true);
  expect(
    rows.some((r) => r.hasRemove),
    'T0：没有任何一行读到 ✕ 控件 ⇒ AC-15 的量具是坏的（「✕ 被禁用」会因为「压根没找到 ✕」而恒真）。判【未验证】',
  ).toBe(true);

  // 量具②：绑定区（切回「字段配置」侧的组件详情，绑定区在组件详情上）
  const bind = await readBindingArea(page);
  expect(
    bind['料号列'] !== null || bind['名称列'] !== null,
    'T0：绑定区一个标签都没读到（料号列 / 名称列）⇒ AC-13 / AC-25 的量具是坏的。'
    + '「不出现元素列」会因为「整个绑定区都没读到」而恒真通过。判【未验证】',
  ).toBe(true);

  console.log(`[T0] ✅ 三个量具均可用（组件 ${fx.code}）`);
  await shot(page, 'T0-量具自检');
});

// ═══════════════════════════════════════════════════════════════════════
// AC-14 · 选中数据源自动带出全部行键列
//
// AC 原文：新建组件 → 取数配置 Tab → 数据集「报价」→ 数据源选「物料BOM」→
//   **无需任何拖拽**，「已选输出列」中已存在该源全部带 `行键` 角色的列
//   （实测 MATERIAL_BOM(QUOTE) 为 material_no「销售料号」与 input_material_no「投入料号」2 列）
// 原型 02 状态 1：pane 头写「2 列 · 均为必选行键」
// ═══════════════════════════════════════════════════════════════════════
test('AC-14: 选中「物料BOM」后，无需任何拖拽，已选输出列已含该源全部行键列（恰好 2 列）', async ({ page }) => {
  test.setTimeout(180_000);
  await newComponentInBuilder(page, 'AC14');

  // 「全部行键角色的列」现算，🚫 不写死 —— 哪几列是行键由语义图决定，不由我定
  const rk = await serverRowKeys(page, 'BOM', '', 'QUOTE');

  await selectDataset(page, '报价');
  await selectSource(page, '物料BOM');

  // 🚨 零拖拽：本条到此为止不调 addField()
  const rows = await readSelectedColumns(page, 'AC-14 选源后（零拖拽）');

  for (const f of rk) {
    expect(
      findCol(rows, f.viewColumn),
      `AC-14：选中数据源后应自动带出行键列「${f.displayName}」（视图列 ${f.viewColumn}），`
      + `实际已选列=${JSON.stringify(rows.map((r) => r.text))}`,
    ).toBeTruthy();
  }
  expect(
    rows.length,
    `AC-14：应恰好带出 ${rk.length} 个行键列（原型 02 状态 1：「2 列 · 均为必选行键」），`
    + `实际 ${rows.length} 列 = ${JSON.stringify(rows.map((r) => r.text))}。\n`
    + '  多出来的是什么？若是重复的行键列 ⇒ 自动填充跑了两遍（AC-16 必红形态①的同源病）。',
  ).toBe(rk.length);

  // 原型 02 状态 1：行键行带绿色「行键」徽标
  for (const f of rk) {
    const row = findCol(rows, f.viewColumn)!;
    expect(row.hasRowKeyBadge,
      `AC-14：行键列「${f.displayName}」应带「行键」徽标（原型 02 状态 1）`).toBe(true);
  }
  await shot(page, 'AC-14-自动带出行键列');
});

// ═══════════════════════════════════════════════════════════════════════
// AC-15 · 行键列不可移除
//
// AC 原文：AC-14 状态下，这 2 行的 ✕ 按钮为禁用态（置灰、点击无效），hover 显示原因文案；
//   **非行键列的 ✕ 仍可正常点击移除**。
//
// 🚨 「点了没被删」这条**必须配阳性对照**：
//    如果点击根本没落到 ✕ 上（选择器错、被遮挡），行数当然也不变 ⇒ 恒真通过。
//    ⇒ 先用一个**非行键业务列**证明「点 ✕ 确实能删掉一行」，再断言行键列点了删不掉。
// ═══════════════════════════════════════════════════════════════════════
test('AC-15: 行键列 ✕ 禁用且点击无效（含阳性对照：非行键列的 ✕ 点了确实能删）', async ({ page }) => {
  test.setTimeout(180_000);
  await newComponentInBuilder(page, 'AC15');
  const rk = await serverRowKeys(page, 'BOM', '', 'QUOTE');

  await selectDataset(page, '报价');
  await selectSource(page, '物料BOM');

  // ── ① 禁用态（形式断言）──
  let rows = await readSelectedColumns(page, 'AC-15 初始');
  for (const f of rk) {
    const row = findCol(rows, f.viewColumn);
    expect(row, `AC-15 前置：找不到行键列「${f.displayName}」⇒ AC-14 没达成，本条无从验起`).toBeTruthy();
    expect(row!.hasRemove,
      `AC-15：行键列「${f.displayName}」的 ✕ 应**存在但禁用**（原型 02 状态 1 / frontend.md §1.2：`
      + '禁用但可见 + 说明原因）—— 🚫 不是不渲染').toBe(true);
    expect(row!.removeDisabled,
      `AC-15：行键列「${f.displayName}」的 ✕ 应为禁用态（置灰 / aria-disabled / cursor:not-allowed 任一），`
      + `实际读到的禁用信号=false`).toBe(true);
    expect(row!.removeReason.length,
      `AC-15：行键列「${f.displayName}」的 ✕ 应能拿到原因文案（原型 02 状态 1：title="行键列不可移除"）。\n`
      + '  取不到 title/aria-label 时，若产品用的是 antd Tooltip，请在 test-report 里注明'
      + '「文案以 Tooltip 形式呈现，本量具读不到」并附 hover 截图，🚫 不要直接判红也不要直接判绿。')
      .toBeGreaterThan(0);
  }

  // ── ② 阳性对照：拖入一个非行键业务列，点它的 ✕ 必须真的删掉 ──
  const tree = await fieldTree(page, 'BOM', '', 'QUOTE');
  const biz = tree.find((f) => f.roles.length === 0 && f.displayName === '材料毛重')
    ?? tree.find((f) => f.roles.length === 0);
  expect(biz, 'AC-15 阳性对照前置：该源没有任何「非行键业务列」可拖 ⇒ 对照做不了，判【未验证】').toBeTruthy();
  await addField(page, biz!.displayName);
  rows = await readSelectedColumns(page, `AC-15 拖入业务列「${biz!.displayName}」后`);
  expect(findCol(rows, biz!.viewColumn),
    `AC-15 阳性对照前置：业务列「${biz!.displayName}」没被加进来 ⇒ 对照做不了`).toBeTruthy();
  const nBefore = rows.length;

  await clickRemove(page, biz!.viewColumn);
  rows = await readSelectedColumns(page, 'AC-15 点非行键列 ✕ 之后');
  expect(
    rows.length,
    `AC-15【阳性对照】：点非行键列的 ✕ 应删掉一行（${nBefore} → ${nBefore - 1}），实际 ${rows.length}。\n`
    + '  🚨 不变说明**点击机制本身无效**（选择器错 / 被遮挡）⇒ 下面「点行键列没被删」是空验证，本条判【未验证】。',
  ).toBe(nBefore - 1);
  expect(findCol(rows, biz!.viewColumn),
    'AC-15【阳性对照】：被点掉的那一列应确实消失').toBeFalsy();

  // ── ③ 行键列点了删不掉（行为断言，才是 AC 原文的「点击无效」）──
  for (const f of rk) {
    const n0 = (await readSelectedColumns(page, `AC-15 点「${f.displayName}」✕ 之前`)).length;
    await clickRemove(page, f.viewColumn);
    const after = await readSelectedColumns(page, `AC-15 点「${f.displayName}」✕ 之后`);
    expect(after.length,
      `AC-15：点行键列「${f.displayName}」的 ✕ 后行数不应变化（${n0}），实际 ${after.length}`).toBe(n0);
    expect(findCol(after, f.viewColumn),
      `AC-15：行键列「${f.displayName}」被 ✕ 删掉了 —— 行键决定行身份（删行/回填/墓碑匹配全靠它），`
      + '删得掉就会导致删错行/回填错位').toBeTruthy();
  }
  await shot(page, 'AC-15-行键列不可移除');
});

// ═══════════════════════════════════════════════════════════════════════
// AC-15 边界 · 价格策略原子组
//
// 原型 02 状态 5 明确要求「**必须实测材质元素数据源**」：
//   已选输出列里有一块整组拖动 / 整组移除的 `.svb-pgrp` 区块。
//   若某个行键列落进该组，「移除整组」的 ✕ 也必须挡住 —— 否则用户绕过单行禁用，
//   从整组入口把行键列删掉了。
//
// ⚠️ 2026-09-08 实测：材质元素源的 3 个行键列全在 `ELEMENT_BOM` 组内，
//    价格策略组是另一个组（`FUNC_ELEMENT_PRICE`）⇒ 「行键列落进价格策略组」这一形态
//    **当前数据下不存在**。此时本条记【不适用·空集】，🚫 不记成通过。
// ═══════════════════════════════════════════════════════════════════════
test('AC-15（边界）: 材质元素源下，若有行键列落进价格策略原子组，则「移除整组」入口也必须挡住',
  async ({ page }) => {
    test.setTimeout(180_000);
    await newComponentInBuilder(page, 'AC15G');
    await selectDataset(page, '报价');
    await selectSource(page, '物料与元素BOM');
    await expandAllGroups(page);

    const rk = await serverRowKeys(page, '材质元素', '', 'QUOTE');
    const rows = await readSelectedColumns(page, 'AC-15 边界 · 材质元素源');
    console.log(`[AC-15 边界] 已选列 ${rows.length} 行（材质元素源）`);

    // 已选列里，哪些行落在 .svb-pgrp（价格策略原子组）区块内
    const inGroup: string[] = await page.$$eval(
      '.svb-pgrp [data-role="selected-column"], .svb-pgrp .selected-column-row',
      (els) => els.map((e) => ((e as HTMLElement).innerText || '').replace(/\s+/g, ' ').trim()),
    ).catch(() => [] as string[]);
    const pgrpCount = await page.locator('.svb-pgrp').count();
    console.log(`[AC-15 边界] .svb-pgrp 区块数=${pgrpCount}；组内已选行=${JSON.stringify(inGroup)}`);

    const rkInGroup = rk.filter((f) => inGroup.some((t) => t.includes(f.viewColumn)));
    if (rkInGroup.length === 0) {
      console.log(
        '[AC-15 边界] ⚠️ 【不适用·空集】当前数据下没有任何行键列落进价格策略原子组'
        + `（该源行键=${JSON.stringify(rk.map((f) => f.viewColumn))}，`
        + `组内已选行 ${inGroup.length} 条）。\n`
        + '  🚫 本条**不记为通过**，在 test-report 里按「该形态当前不可复现」登记。'
        + '  若后续语义图把行键列挪进价格策略组，本条会自动开始真验。',
      );
      // 阳性对照：至少证明我们**看得见**这个区块，否则连「空集」这个结论都不可信
      expect(
        pgrpCount + inGroup.length,
        'AC-15 边界：`.svb-pgrp` 一个都没找到，且组内一行都没读到 ⇒ 「行键列没落进该组」这个结论'
        + '**可能只是选择器没命中**。判【未验证】，停下来报主线核对价格策略组的类名。',
      ).toBeGreaterThan(0);
      await shot(page, 'AC-15-边界-价格策略组-空集');
      return;
    }

    // 真有行键列落进组：整组移除入口必须被挡住
    const groupRemoveBlocked = await page.$$eval('.svb-pgrp', (groups) => groups.map((g) => {
      // ⚠️ `✕` 不是合法 CSS 标识，🚫 不能写进 querySelectorAll —— 会抛 SyntaxError，
      //    整条用例以「脚本错误」倒掉，长得像产品缺陷。按叶子文本筛。
      const rm = Array.from(g.querySelectorAll('*')).find((e) => {
        const t = (e.textContent || '').trim();
        return e.childElementCount === 0 && /^[✕✖×]$/.test(t);
      }) as HTMLElement | undefined;
      if (!rm) return 'no-remove-entry';
      const cs = getComputedStyle(rm);
      const blocked = rm.hasAttribute('disabled') || rm.getAttribute('aria-disabled') === 'true'
        || /disabled|\boff\b/.test(rm.className || '')
        || cs.pointerEvents === 'none' || cs.cursor === 'not-allowed';
      return blocked ? 'blocked' : 'CLICKABLE';
    }));
    console.log('[AC-15 边界] 整组移除入口状态 =', JSON.stringify(groupRemoveBlocked));
    expect(
      groupRemoveBlocked.includes('CLICKABLE'),
      `AC-15 边界：有行键列（${rkInGroup.map((f) => f.viewColumn)}）落在价格策略原子组内，`
      + '而「移除整组」的 ✕ 仍可点 ⇒ 用户能绕过单行禁用、从整组入口把行键列删掉（原型 02 状态 5）。',
    ).toBe(false);
    await shot(page, 'AC-15-边界-价格策略组');
  });

// ═══════════════════════════════════════════════════════════════════════
// AC-16 · 序列 —— 切源往返，行键列不堆积不丢失
//
// AC 原文：新建组件 → 数据源选「物料BOM」（自动带出 2 个行键列）→ 手动拖入 1 个业务列
//   「材料毛重」→ 切换数据源到「来料其他费用」→ 断言：已选输出列被重置为该源的行键列集合，
//   **不残留上一个源的列** → 再切回「物料BOM」→ 断言：已选输出列**恰好是 2 个行键列，
//   不是 4 个**（不重复追加）、不含刚才那个业务列。
//
// 原型 02 状态 3 画出两种必红形态：① 切回变 4 列 ② 切回仍残留「材料毛重」
//
// 🚨 全程按**视图列名**判，🚫 不按显示名 ——
//    两个源的行键列显示名完全同名（销售料号 / 投入料号），按名字判残留会**恒绿**。
// ═══════════════════════════════════════════════════════════════════════
test('AC-16（序列）: 物料BOM(2 行键) → 拖入材料毛重(3 列) → 切到来料其他费用(重置且零残留) '
  + '→ 切回物料BOM(恰好 2 列，非 4 列，不含材料毛重)', async ({ page }) => {
  test.setTimeout(240_000);
  await newComponentInBuilder(page, 'AC16');

  const rkBom = await serverRowKeys(page, 'BOM', '', 'QUOTE');
  const rkFee = await serverRowKeys(page, '费用类', 'INCOMING_OTHER_FEE', 'QUOTE');

  // 🚨 判据自检：这两组行键列的**显示名**是否重合？重合就更加只能按视图列名判
  const dupNames = rkBom.map((f) => f.displayName).filter((n) => rkFee.some((g) => g.displayName === n));
  console.log(`[AC-16] 两源行键显示名重合项 = ${JSON.stringify(dupNames)} `
    + '（非空 ⇒ 按显示名判残留会恒绿，本用例一律按视图列名判）');

  const bizName = '材料毛重';
  const biz = (await fieldTree(page, 'BOM', '', 'QUOTE')).find((f) => f.displayName === bizName);
  expect(biz, `AC-16 前置：报价·物料BOM 里找不到业务列「${bizName}」⇒ 序列做不了，判【未验证】`).toBeTruthy();

  await selectDataset(page, '报价');

  // ── 中间态 ①：选物料BOM → 2 个行键列 ──
  await selectSource(page, '物料BOM');
  let rows = await readSelectedColumns(page, 'AC-16 ① 选物料BOM');
  expect(rows.length, `AC-16 ①【中间态】选物料BOM 后应有 ${rkBom.length} 个行键列，`
    + `实际 ${rows.length} = ${JSON.stringify(rows.map((r) => r.text))}`).toBe(rkBom.length);
  for (const f of rkBom) {
    expect(findCol(rows, f.viewColumn), `AC-16 ①：应含 ${f.viewColumn}`).toBeTruthy();
  }

  // ── 中间态 ②：拖入业务列 → 3 列 ──
  await addField(page, bizName);
  rows = await readSelectedColumns(page, `AC-16 ② 拖入「${bizName}」`);
  expect(findCol(rows, biz!.viewColumn),
    `AC-16 ②【中间态】：业务列 ${biz!.viewColumn} 应被加入 ⇒ 没加进来的话，`
    + '第 ④ 步「不含材料毛重」是空验证（它本来就没进来过）').toBeTruthy();
  expect(rows.length, `AC-16 ②【中间态】：应为 ${rkBom.length + 1} 列`).toBe(rkBom.length + 1);

  // ── 中间态 ③：切到来料其他费用 → 重置为该源行键列，零残留 ──
  await selectSource(page, '来料其他费用');
  rows = await readSelectedColumns(page, 'AC-16 ③ 切到来料其他费用');
  for (const f of rkFee) {
    expect(findCol(rows, f.viewColumn),
      `AC-16 ③【中间态】：应重置为来料其他费用的行键列 ${f.viewColumn}`).toBeTruthy();
  }
  const leaked = rows.filter((r) => rkBom.some((f) => findCol([r], f.viewColumn))
    || findCol([r], biz!.viewColumn));
  expect(
    leaked.map((r) => r.text),
    'AC-16 ③【中间态】：切源后**不得残留上一个源的列**。\n'
    + `  🚨 判据按视图列名（_物料BOM_*）—— 两源行键显示名同名(${JSON.stringify(dupNames)})，`
    + '按显示名判会恒绿。\n'
    + '  残留的列在新源里根本不存在，编译必然失败（原型 02 状态 3 必红形态②）。',
  ).toEqual([]);
  expect(rows.length, `AC-16 ③【中间态】：应恰好是来料其他费用的 ${rkFee.length} 个行键列，`
    + `实际 ${rows.length} = ${JSON.stringify(rows.map((r) => r.text))}`).toBe(rkFee.length);

  // ── 最终态 ④：切回物料BOM → 恰好 2 列，非 4 列，不含业务列 ──
  await selectSource(page, '物料BOM');
  rows = await readSelectedColumns(page, 'AC-16 ④ 切回物料BOM');
  for (const f of rkBom) {
    expect(findCol(rows, f.viewColumn), `AC-16 ④【最终态】：应含行键列 ${f.viewColumn}`).toBeTruthy();
  }
  expect(
    rows.length,
    `AC-16 ④【最终态】：切回后应**恰好 ${rkBom.length} 列**，实际 ${rows.length} = `
    + `${JSON.stringify(rows.map((r) => r.text))}。\n`
    + `  · 若为 ${rkBom.length * 2} 列 ⇒ 原型 02 状态 3 必红形态①：自动填充跑在重置之前，行键列被重复追加。\n`
    + '  · 若多出别的列 ⇒ 上一个源的列没被清干净。',
  ).toBe(rkBom.length);
  expect(
    findCol(rows, biz!.viewColumn),
    `AC-16 ④【最终态】：切回后**不得**含手动拖入的业务列 ${biz!.viewColumn}`
    + '（原型 02 状态 3 必红形态②）',
  ).toBeFalsy();
  // 残留的来料其他费用列同样不许有
  for (const f of rkFee) {
    expect(findCol(rows, f.viewColumn),
      `AC-16 ④【最终态】：切回后不得残留来料其他费用的列 ${f.viewColumn}`).toBeFalsy();
  }
  await shot(page, 'AC-16-切源往返-最终态');
});

// ═══════════════════════════════════════════════════════════════════════
// AC-17 / AC-18 / AC-19 · 料号列拖入后的默认字段名
//
// AC 原文（表）：
//   AC-17 报价「物料BOM」  input_material_no（面板显示「投入料号」）→ 字段名 **「料号」**
//   AC-18 报价「电镀费用」  material_no      （面板显示「销售料号」）→ 字段名 **「销售料号」**（例外，保持）
//   AC-19 明细核价「模具工装成本」production_no（面板「生产料号」）→ **「生产料号」**（例外，保持）
//         基础核价「物料BOM」component_no    （面板「组成料号」）→ **「料号」**
//   三条共同的反向断言：**左侧「可用字段」面板中的显示名保持原样不变**
//
// ⚠️ 实测提醒（2026-09-08 field-tree）：这四列**本身都带 ROW_KEY 角色**
//    ⇒ 按 AC-14，选中数据源时它们已被**自动带出**，不需要再拖。
//    ⇒ 本组用例先看它在不在；不在才拖。这样 F-2（自动带出）出问题时，
//      AC-17/18/19 不会跟着连坐红成「F-3 没做」。
// ═══════════════════════════════════════════════════════════════════════
async function assertDefaultFieldName(
  page: Page, opts: {
    ac: string; dataset: '报价' | '基础核价' | '明细核价'; source: string;
    tabType: string; variantKey: string; dialect: string;
    sourceColumn: string; panelName: string; expectFieldName: string; why: string;
  },
) {
  const tree = await fieldTree(page, opts.tabType, opts.variantKey, opts.dialect);
  const f = tree.find((x) => x.sourceColumn === opts.sourceColumn);
  expect(f, `${opts.ac} 前置：${opts.source} 里找不到列 ${opts.sourceColumn} ⇒ 判【未验证】`).toBeTruthy();
  expect(
    f!.displayName,
    `${opts.ac}【反向断言】：左侧「可用字段」面板的显示名应保持原样「${opts.panelName}」，`
    + `服务端给的是「${f!.displayName}」。\n`
    + '  🚨 改的是**拖入后的默认字段名**，不是面板显示名（D-8）。面板名也变了 = 改错了地方。',
  ).toBe(opts.panelName);

  await selectDataset(page, opts.dataset);
  await selectSource(page, opts.source);

  let rows = await readSelectedColumns(page, `${opts.ac} 选源后`);
  let row = findCol(rows, f!.viewColumn);
  if (!row) {
    console.log(`[${opts.ac}] 该列未被自动带出（它 roles=${JSON.stringify(f!.roles)}），改用拖入路径`);
    await addField(page, opts.panelName);
    rows = await readSelectedColumns(page, `${opts.ac} 拖入后`);
    row = findCol(rows, f!.viewColumn);
  } else {
    console.log(`[${opts.ac}] 该列被自动带出（roles=${JSON.stringify(f!.roles)}），直接读它的字段名`);
  }
  expect(row, `${opts.ac}：已选输出列里找不到 ${f!.viewColumn} ⇒ 无从验默认名，判【未验证】`).toBeTruthy();
  expect(
    row!.fieldName,
    `${opts.ac}：${opts.source} 的「${opts.panelName}」拖入后，字段名应为「${opts.expectFieldName}」`
    + `（${opts.why}），实际读到「${row!.fieldName}」`,
  ).toBe(opts.expectFieldName);

  // 反向断言：面板上仍显示原名（页面可见性层面再验一次，不只信服务端）
  await expandAllGroups(page);
  await expect(
    page.locator('.svb-grp').getByText(opts.panelName, { exact: true }).first(),
    `${opts.ac}【反向断言】：左侧面板里应仍可见原显示名「${opts.panelName}」`,
  ).toBeVisible({ timeout: 10_000 });
}

test('AC-17: 报价「物料BOM」的「投入料号」拖入后字段名为「料号」（面板显示名不变）', async ({ page }) => {
  test.setTimeout(180_000);
  await newComponentInBuilder(page, 'AC17');
  await assertDefaultFieldName(page, {
    ac: 'AC-17', dataset: '报价', source: '物料BOM',
    tabType: 'BOM', variantKey: '', dialect: 'QUOTE',
    sourceColumn: 'input_material_no', panelName: '投入料号', expectFieldName: '料号',
    why: '料号列默认名统一为「料号」，S-6',
  });
  await shot(page, 'AC-17-投入料号默认名料号');
});

test('AC-18: 报价「电镀费用」的「销售料号」拖入后字段名保持「销售料号」（销售料号例外，D-8）',
  async ({ page }) => {
    test.setTimeout(180_000);
    await newComponentInBuilder(page, 'AC18');
    await assertDefaultFieldName(page, {
      ac: 'AC-18', dataset: '报价', source: '电镀费用',
      tabType: '费用类', variantKey: 'PLATING_FEE', dialect: 'QUOTE',
      sourceColumn: 'material_no', panelName: '销售料号', expectFieldName: '销售料号',
      why: '报价侧销售料号是 D-8 明列的例外，不改名',
    });
    await shot(page, 'AC-18-销售料号保持原名');
  });

test('AC-19（核价侧·上半）: 明细核价「模具工装成本」的「生产料号」拖入后字段名保持「生产料号」',
  async ({ page }) => {
    test.setTimeout(180_000);
    await newComponentInBuilder(page, 'AC19a');
    await assertDefaultFieldName(page, {
      ac: 'AC-19（上半）', dataset: '明细核价', source: '模具工装成本',
      tabType: '费用类', variantKey: 'TOOLING', dialect: 'COST_DETAIL',
      sourceColumn: 'production_no', panelName: '生产料号', expectFieldName: '生产料号',
      why: '核价侧生产料号是 D-8 明列的例外，不改名',
    });
    await shot(page, 'AC-19a-生产料号保持原名');
  });

test('AC-19（核价侧·下半）: 基础核价「物料BOM」的「组成料号」拖入后字段名为「料号」', async ({ page }) => {
  test.setTimeout(180_000);
  await newComponentInBuilder(page, 'AC19b');
  await assertDefaultFieldName(page, {
    ac: 'AC-19（下半）', dataset: '基础核价', source: '物料BOM',
    tabType: 'BOM', variantKey: '', dialect: 'COST_BASIC',
    sourceColumn: 'component_no', panelName: '组成料号', expectFieldName: '料号',
    why: '组成料号不在例外名单里，按 S-6 统一改叫「料号」',
  });
  await shot(page, 'AC-19b-组成料号默认名料号');
});

// ═══════════════════════════════════════════════════════════════════════
// AC-13 · 未绑数据源但已配元素列的组件，不被隐藏成僵尸值
//
// AC 原文：
//   前置：造一个组件，直接写库设 element_code_field='元素'、element_price_field='元素单价'，
//        且**不建 component_sql_view 记录**（模拟 builder_config 为空的存量形态
//        ⇒ 前端 boundSemantic === undefined）
//   操作：组件管理中打开它
//   断言：绑定区**显示** 4 个下拉，元素列回填 `元素`、元素单价列回填 `元素单价`
//   原型 01 状态 4：判据必须是 `semantic === 'MATERIAL_ELEMENT' || 已有元素值`
//
// ─────────────────────────────────────────────────────────────────────
// 🚨 证伪实验（AC 原文要求「必做，否则这条不算数」）
//
// AC 原文给的做法是「把 F-1 的判据临时改成只看 boundSemantic → 必须变红」——
// 那要**改前端实现源码**，而本片按派工是**禁读禁改 `cpq-frontend/src/`** 的。
// ⇒ 本用例改用**数据侧证伪**，判别力等价且不碰实现、可重复跑：
//
//   状态 A（元素值有 + boundSemantic undefined）→ 必须 4 个下拉
//   状态 B（把元素两列清空，boundSemantic 仍 undefined）→ 必须只剩 2 个下拉
//   状态 C（把元素两列填回去）→ 必须又回到 4 个下拉
//
// 三态覆盖了两种坏判据：
//   · 判据「只看 semantic」 ⇒ 状态 A 只出 2 个下拉 ⇒ **红**
//   · 判据「恒显示 4 个」   ⇒ 状态 B 出 4 个下拉  ⇒ **红**
// 也就是说，量具在本用例内部自己证明了它会动（`test.md §4.2`）。
//
// 📌 仍需主线知晓：AC 原文点名的「改源码判据」实验属于**可改实现的角色**，
//    本片按访问边界不做，已在回报里列明，🚫 不静默跳过。
// ═══════════════════════════════════════════════════════════════════════
test('AC-13: boundSemantic 为空但已配元素列的组件，绑定区仍显示 4 个下拉并回填值'
  + '（含三态数据侧证伪实验）', async ({ page }) => {
  test.setTimeout(240_000);

  // ── 前置：造 T260908-S2-legacy-elem ──
  const fx = await createFixtureComponent('legacy-elem');
  psql(`UPDATE component SET element_code_field='元素', element_price_field='元素单价' `
    + `WHERE id='${fx.id}'`);

  // 前置守卫①：确认它确实是「无 builder_config」那一态。
  // 🚨 不确认的话，万一建组件时顺手建了 component_sql_view，本条验的就不是 AC-13 说的那一态了。
  const svCount = psql(`SELECT count(*) FROM component_sql_view WHERE component_id='${fx.id}'`);
  expect(
    svCount,
    `AC-13 前置：夹具 ${fx.code} 有 ${svCount} 行 component_sql_view ⇒ 它不是「builder_config 为空」`
    + '的存量形态，boundSemantic 不会是 undefined ⇒ 本条验的是另一件事。判【未验证】。',
  ).toBe('0');
  // 前置守卫②：元素两列确实写进去了
  const vals = psql(`SELECT COALESCE(element_code_field,'(null)')||'/'||`
    + `COALESCE(element_price_field,'(null)') FROM component WHERE id='${fx.id}'`);
  expect(vals, 'AC-13 前置：元素两列没写进去 ⇒ 后面「回填值」是空验证').toBe('元素/元素单价');
  console.log(`[AC-13] 夹具 ${fx.code}：component_sql_view=0 行，元素两列=${vals}`);

  // ── 状态 A：应显示 4 个下拉并回填 ──
  await openComponentByCode(page, fx.code);
  let bind = await readBindingArea(page);
  // 阳性对照：绑定区本身读到了（否则「元素列存在」的判定不可信）
  expect(bind['料号列'] !== null, 'AC-13 前置：绑定区连「料号列」都没读到 ⇒ 量具没命中，判【未验证】').toBe(true);
  expect(bind['名称列'] !== null, 'AC-13 前置：绑定区连「名称列」都没读到 ⇒ 量具没命中，判【未验证】').toBe(true);

  expect(
    bind['元素列'] !== null,
    'AC-13【状态A】：未绑数据源但 element_code_field 有值的组件，绑定区应**显示**「元素列」下拉。\n'
    + '  🚨 只看 semantic 会把这些**已生效的配置藏起来** —— 值还在参与计算，界面上却看不见、改不了'
    + '（原型 01 状态 4）。',
  ).toBe(true);
  expect(bind['元素单价列'] !== null,
    'AC-13【状态A】：绑定区应显示「元素单价列」下拉').toBe(true);
  expect(bind['元素列'],
    'AC-13【状态A】：「元素列」应回填 `元素`（原型 01 状态 4）').toBe('元素');
  expect(bind['元素单价列'],
    'AC-13【状态A】：「元素单价列」应回填 `元素单价`').toBe('元素单价');
  await shot(page, 'AC-13-状态A-四个下拉');

  // ── 状态 B（证伪）：清空元素两列 → 必须只剩 2 个下拉 ──
  psql(`UPDATE component SET element_code_field=NULL, element_price_field=NULL WHERE id='${fx.id}'`);
  await openComponentByCode(page, fx.code);
  bind = await readBindingArea(page);
  expect(bind['料号列'] !== null, 'AC-13【状态B】前置：绑定区没读到 ⇒ 结论不可信').toBe(true);
  expect(
    bind['元素列'] === null && bind['元素单价列'] === null,
    'AC-13【证伪·状态B】：把元素两列清空后（boundSemantic 仍为空），绑定区应**只剩料号列/名称列**。\n'
    + `  实际 元素列=${JSON.stringify(bind['元素列'])} 元素单价列=${JSON.stringify(bind['元素单价列'])}。\n`
    + '  🚨 仍显示 ⇒ 判据是「恒显示 4 个」，状态 A 的绿是恒真通过，不算证据。',
  ).toBe(true);
  await shot(page, 'AC-13-状态B-证伪-清空后只剩两个');

  // ── 状态 C（回证）：填回去 → 又是 4 个下拉 ──
  psql(`UPDATE component SET element_code_field='元素', element_price_field='元素单价' `
    + `WHERE id='${fx.id}'`);
  await openComponentByCode(page, fx.code);
  bind = await readBindingArea(page);
  expect(
    bind['元素列'] === '元素' && bind['元素单价列'] === '元素单价',
    'AC-13【证伪·状态C】：把元素两列填回去后应又显示并回填。\n'
    + `  实际 元素列=${JSON.stringify(bind['元素列'])} 元素单价列=${JSON.stringify(bind['元素单价列'])}。\n`
    + '  🚨 不恢复 ⇒ 判据只看 semantic，A→B→C 三态里量具根本没随数据动。',
  ).toBe(true);
  console.log('[AC-13] ✅ 三态证伪通过：A(有值→4) / B(清空→2) / C(填回→4)，判据确实读了「已有元素值」');
  await shot(page, 'AC-13-状态C-回证');
});

// ═══════════════════════════════════════════════════════════════════════
// AC-25 · 未绑定数据源、也没配元素列的存量组件不崩
//
// AC 原文：打开 010a2589-…（「BOM」，2026-09-08 实测无 builder_config、无元素绑定）→
//   绑定区只显示料号列/名称列两个下拉、页面无红色遮罩、控制台无未捕获异常。
//   🔑 与 AC-13 配对：AC-13 验「有值就显示」，本条验「没值就不显示」，
//      两条一起才把 F-1 的判据两侧都钉住。
//
// 🚫 本条只读打开现网组件，**全程不点保存**（S2 的隔离约束）。
// ═══════════════════════════════════════════════════════════════════════
test('AC-25: 未绑数据源且无元素值的存量组件，绑定区只显示 2 个下拉、无红屏、无未捕获异常',
  async ({ page }) => {
    test.setTimeout(180_000);
    const { errors, libWarnings } = watchJsErrors(page);

    // 前置守卫：这条 AC 点的是一个**现网组件**，共享库数据会漂。
    // 漂了要判【未验证】并换一个同坐标的组件，🚫 不许判红（那会长得像产品缺陷）。
    const res = await page.request.get(`${BACKEND_URL}/api/cpq/components/${AC25_COMPONENT_ID}`);
    expect(res.ok(),
      `AC-25 前置：取不到点名组件 ${AC25_COMPONENT_ID}（HTTP ${res.status()}）⇒ 它可能已被清理。\n`
      + '  换一个「无 builder_config + 无元素绑定」的组件即可，坐标才是判据。本条判【未验证】。').toBe(true);
    const d = (await res.json()).data;
    expect(
      [d.elementCodeField, d.elementPriceField],
      `AC-25 前置：点名组件 ${d.code}「${d.name}」的元素绑定已非空 ⇒ 它不再是本条要验的那一态，`
      + '判【未验证】并换组件。',
    ).toEqual([null, null]);
    const sv = psql(`SELECT count(*) FROM component_sql_view WHERE component_id='${AC25_COMPONENT_ID}'`);
    console.log(`[AC-25] 点名组件 ${d.code}「${d.name}」：元素绑定=null/null，component_sql_view=${sv} 行`);

    await openComponentByCode(page, d.code);
    const bind = await readBindingArea(page);

    // 阳性对照：料号列/名称列必须读到 —— 否则「元素列不出现」只是因为整个绑定区都没读到
    expect(
      bind['料号列'] !== null && bind['名称列'] !== null,
      'AC-25【阳性对照】：绑定区应能读到「料号列」「名称列」两个下拉。\n'
      + `  实际 料号列=${JSON.stringify(bind['料号列'])} 名称列=${JSON.stringify(bind['名称列'])}。\n`
      + '  🚨 读不到 ⇒ 下面「不出现元素列」是恒真通过，本条判【未验证】。',
    ).toBe(true);

    expect(
      bind['元素列'],
      'AC-25：没配元素列的组件，绑定区**不应**出现「元素列」（与 AC-13 配对，钉住判据的另一侧）',
    ).toBeNull();
    expect(bind['元素单价列'],
      'AC-25：绑定区不应出现「元素单价列」').toBeNull();

    const overlay = await hasRedOverlay(page);
    expect(overlay, `AC-25：页面出现红色遮罩 / 错误边界：${overlay}`).toBe('');
    console.log(`[AC-25] 被过滤的库告警 ${libWarnings.length} 条：`
      + JSON.stringify(libWarnings.slice(0, 5)));
    expect(errors,
      'AC-25：控制台不应有未捕获异常（库的 `Warning:` 告警已排除并单独打印）').toEqual([]);

    await shot(page, 'AC-25-空态只显示两个下拉');
  });
