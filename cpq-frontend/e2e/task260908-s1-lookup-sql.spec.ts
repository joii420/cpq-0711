/**
 * E2E · task-260908「取数配置器优化」· 分片 S1（语义图与编译）· SQL 结构篇
 *
 * 认领 AC：**AC-1 / AC-3 / AC-5 / AC-5b / AC-7**
 * （同片的 AC-2 / AC-4 / AC-6 / AC-23 / AC-24 在 `task260908-s1-preview.spec.ts`，那几条要跑真实预览。）
 *
 * 🚦 写入面 = **只读**。全程不点「保存」；每条用例用 readOnlyGuard 断言被触碰组件的
 *    builder_config / sql_template 逐字节未变。
 *
 * 🚫 本文件不读实现代码。断言按 `需求文档.md §③` 的 AC 原文写。
 */
import { test, expect } from '@playwright/test';
import { loginAsAdmin, isBackendUp } from './fixtures/auth';
import {
  ANCHORS, F_MATERIAL_NAME, F_PRODUCTION_NO,
  openBuilderOf, expandAllGroups, addField, sqlPanelText,
  tableRefCount, leftJoinsOf, onConditionCount,
  readOnlyGuard, archive, shot, compileColumnNames,
} from './task260908-s1.helpers';

let backendUp = true;

test.beforeAll(async () => { backendUp = await isBackendUp(); });

test.beforeEach(async ({ page }) => {
  test.skip(!backendUp, '后端未启动，跳过（不视为失败，也不视为通过）');
  await loginAsAdmin(page);
});

/** 从服务端 field-tree 拿分组树。🚫 不写死中文名时用它当权威。 */
async function fieldTree(page: any, tabType: string, variantKey: string, dialect: string) {
  const url = `/api/cpq/config/semantic-graph/field-tree?tabType=${encodeURIComponent(tabType)}`
    + `&variantKey=${encodeURIComponent(variantKey)}&dialect=${dialect}`;
  const resp = await page.request.get(url);
  expect(resp.ok(), `前置：field-tree 应 200，实际=${resp.status()}（${url}）`).toBe(true);
  return resp.json();
}

function groupTitle(g: any): string {
  const t = g?.title ?? g?.label ?? g?.name ?? g?.groupName ?? g?.displayName ?? g?.groupLabel;
  expect(typeof t === 'string',
    `field-tree 的分组对象里读不到标题（试过 title/label/name/groupName/displayName/groupLabel）。` +
    `分组=${JSON.stringify(g).slice(0, 300)} ⇒ 量具未校准，本条判【未验证】。`
  ).toBe(true);
  return String(t);
}

// ═══════════════════════════════════════════════════════════════════
// T0 · 量具自检（testing.md §4.4：新加的断言先做一次证伪实验）
// ═══════════════════════════════════════════════════════════════════

test('T0 量具自检: 表名匹配必须按标识符边界 —— ds_quote_material 不得被 ds_quote_material_bom 冒名', async () => {
  // 🚨 这是本片最容易静默出错的一处：`ds_quote_material` 是 `ds_quote_material_bom` 的前缀。
  //    裸 substring 计数会让 AC-5「只出现 1 次」永远红、AC-3「出现 2 次」永远绿 —— 两个方向同时错。
  const fake = [
    'SELECT a.x FROM ds_quote_material_bom a',
    'LEFT JOIN ds_quote_material m1 ON a.input_material_no = m1.material_no AND a.customer_no = m1.customer_no',
    'LEFT JOIN ds_quote_material m2 ON a.material_no = m2.material_no',
    'LEFT JOIN material_recipe r ON a.input_material_no = r.code',
    'LEFT JOIN ds_quote_material_bom_record z ON 1=1',
  ].join('\n');

  // ① 边界匹配：只数到 m1 / m2 两处，🚫 不把 _bom / _bom_record 算进来
  expect(tableRefCount(fake, 'ds_quote_material'),
    '量具自检①：tableRefCount 把 ds_quote_material_bom(_record) 也数进去了 ⇒ 后面所有次数断言不可信'
  ).toBe(2);
  expect(tableRefCount(fake, 'ds_quote_material_bom'), '量具自检①b').toBe(1);
  expect(tableRefCount(fake, 'material_recipe'),
    '量具自检①c：material_recipe 不应被 material_recipe_element 之类冒名'
  ).toBe(1);

  // ② 证伪：换成裸 substring 计数，同一段文本会数出 4 —— 证明「边界匹配」这层真的在起作用
  const naive = fake.split('ds_quote_material').length - 1;
  expect(naive,
    '量具自检②（证伪对照）：裸 substring 若也只数出 2，说明本自检样本没覆盖到冒名场景，自检本身失效'
  ).toBeGreaterThan(2);
  console.log(`[量具自检] 边界匹配=2，裸 substring=${naive} ⇒ 边界匹配确实生效`);

  // ③ LEFT JOIN 解析：两条 join、别名不同、ON 条件数 2 与 1
  const js = leftJoinsOf(fake, 'ds_quote_material');
  expect(js.length, '量具自检③：leftJoinsOf 应解析出 2 条 ds_quote_material 的 LEFT JOIN').toBe(2);
  expect(new Set(js.map((j) => j.alias)).size, '量具自检③b：两条 join 的别名应不同').toBe(2);
  expect(onConditionCount(js[0].on), '量具自检③c：第一条 ON 应有 2 个条件').toBe(2);
  expect(onConditionCount(js[1].on), '量具自检③d：第二条 ON 应有 1 个条件').toBe(1);

  // ④ 「.material_no」的点号必须要求 —— 否则 input_material_no 会被误当成 material_no
  expect(/\.material_no(?![A-Za-z0-9_])/.test('a.input_material_no = r.code'),
    '量具自检④：`.material_no` 的匹配不能命中 `a.input_material_no`，否则 AC-3 的两条 ON 分不开'
  ).toBe(false);
});

// ═══════════════════════════════════════════════════════════════════
// AC-1 · 报价侧普通数据源的材料名（连物料表，带客户维度）
// ═══════════════════════════════════════════════════════════════════

test('AC-1: 「来料其他费用」拖入「材料名」→ LEFT JOIN ds_quote_material，'
  + 'ON 同时含 .input_material_no=别名.material_no 与 .customer_no=别名.customer_no', async ({ page }) => {
  test.setTimeout(220_000);
  const a = ANCHORS.incomingOtherFee;
  const { id, name } = await openBuilderOf(page, a);
  const guard = readOnlyGuard(id, name);
  try {
    // ── 前置①：服务端 field-tree 里确有「材料名」，且带 lookupLib 徽标数据 ──
    const tree = await fieldTree(page, a.tabType, a.variantKey, a.dialect);
    const groups: any[] = tree.groups ?? [];
    expect(groups.length,
      `AC-1 前置：field-tree 一个分组都没有（${a.tabType}/${a.variantKey}/${a.dialect}）⇒ 本条判【未验证】`
    ).toBeGreaterThan(0);
    const allFields = groups.flatMap((g: any) => (g.fields ?? []).map((f: any) => ({ ...f, __g: g })));
    const mn = allFields.find((f: any) => f.displayName === F_MATERIAL_NAME);
    if (!mn) {
      const partNameRoles = allFields
        .filter((f: any) => (f.roles ?? []).includes('PART_NAME'))
        .map((f: any) => `${f.displayName}(${f.sourceColumn})`);
      expect(mn,
        `AC-1①：可用字段里没有「${F_MATERIAL_NAME}」。\n` +
        `  该坐标下带 PART_NAME 角色的列 = ${JSON.stringify(partNameRoles)}\n` +
        `  ⇒ 若上面非空但名字不是「材料名」，那是**命名与 AC 原文不符**（AC 原文逐字要求「材料名」）；\n` +
        `     若上面为空，那是查名边根本没挂上去。两者都要报给主线，不要自行放宽判据。`
      ).toBeTruthy();
    }
    expect(mn.lookupLib,
      `AC-1①：「${F_MATERIAL_NAME}」存在但没有 lookupLib 标记（api.md 约定它带「物料（查名）」徽标）。` +
      `字段=${JSON.stringify(mn).slice(0, 300)}`
    ).toBeTruthy();
    console.log(`[AC-1] field-tree 里的材料名 = ${JSON.stringify(mn).slice(0, 300)}`);

    // ── 前置②：面板渲染出来了，且能看见「材料名」和它的徽标 ──
    const stillCollapsed = await expandAllGroups(page);
    expect(stillCollapsed, 'AC-1 前置：分组展不开 ⇒ 后面的可见性断言不可信，本条判【未验证】').toBe(0);
    const fieldNode = page.locator('.svb-grp').getByText(F_MATERIAL_NAME, { exact: true }).first();
    await expect(fieldNode, `AC-1①：左侧「可用字段」面板里看不到「${F_MATERIAL_NAME}」`)
      .toBeVisible({ timeout: 10_000 });
    const panelText = await page.locator('.svb-grp').allInnerTexts().then((xs) => xs.join('\n'));
    expect(panelText,
      `AC-1①：面板里没有 lookupLib 徽标文案「${mn.lookupLib}」（服务端已给出该值）⇒ 徽标没渲染`
    ).toContain(String(mn.lookupLib));

    // ── 证伪对照：加列前先记 SQL，加列后必须**逐字变了** ──
    const sqlBefore = await sqlPanelText(page);
    const joinsBefore = leftJoinsOf(sqlBefore, 'ds_quote_material').length;

    await addField(page, F_MATERIAL_NAME);

    const sqlAfter = await sqlPanelText(page);
    expect(sqlAfter,
      'AC-1 证伪对照：加了「材料名」列，但「生成的 SQL」逐字未变 ⇒ 要么列没真加进去，' +
      '要么面板显示的是上一次编译结果。此时下面的断言无论红绿都不可信。'
    ).not.toBe(sqlBefore);

    // ── ② LEFT JOIN ds_quote_material，ON 两个条件 ──
    const joins = leftJoinsOf(sqlAfter, 'ds_quote_material');
    expect(joins.length,
      `AC-1②：生成的 SQL 里应出现 LEFT JOIN ds_quote_material（加列前 ${joinsBefore} 条，加列后 ${joins.length} 条）。\n` +
      `SQL=\n${sqlAfter.slice(0, 1500)}`
    ).toBeGreaterThan(joinsBefore);

    const lookupJoin = joins.find((j) =>
      /\binput_material_no\b/.test(j.on) &&
      new RegExp(`\\.material_no(?![A-Za-z0-9_])`).test(j.on));
    expect(lookupJoin,
      `AC-1②：找不到「ON 含 .input_material_no = <别名>.material_no」的那条 LEFT JOIN ds_quote_material。\n` +
      `实际的 join 们 = ${JSON.stringify(joins, null, 2)}`
    ).toBeTruthy();

    expect(lookupJoin!.on,
      `AC-1②：ON 子句里缺 customer_no 条件（D-11：报价侧连物料表必须带客户维度，否则跨客户重号会取错值）。` +
      `ON=${lookupJoin!.on}`
    ).toMatch(/\bcustomer_no\b/);
    expect(onConditionCount(lookupJoin!.on),
      `AC-1②：ON 子句应恰好两个条件（料号 + 客户），实际=${lookupJoin!.on}`
    ).toBe(2);

    archive('AC-1-sql.txt',
      `# AC-1 组件 ${id} / ${name}\n# 坐标 ${a.tabType}/${a.variantKey}/${a.dialect}\n\n` +
      `## 加「材料名」之前\n${sqlBefore}\n\n## 加「材料名」之后\n${sqlAfter}\n\n` +
      `## 命中的查名 JOIN\n别名=${lookupJoin!.alias}\nON=${lookupJoin!.on}\n`);
    await shot(page, 'AC-1-来料其他费用-材料名.png');
  } finally {
    guard.assertUntouched();
  }
});

// ═══════════════════════════════════════════════════════════════════
// AC-7 · 材料名内联在锚点分组内，不另起分组
// ═══════════════════════════════════════════════════════════════════

test('AC-7: 「材料名」出现在锚点数据源自己的分组里，面板中不存在「物料」「材质」这类独立查名分组',
  async ({ page }) => {
  test.setTimeout(220_000);
  const a = ANCHORS.incomingOtherFee;
  const { id, name } = await openBuilderOf(page, a);
  const guard = readOnlyGuard(id, name);
  try {
    // ── 服务端侧（权威）──
    const tree = await fieldTree(page, a.tabType, a.variantKey, a.dialect);
    const groups: any[] = tree.groups ?? [];
    expect(groups.length, 'AC-7 前置：field-tree 无分组 ⇒ 本条判【未验证】').toBeGreaterThan(0);

    const owner = groups.find((g: any) =>
      (g.fields ?? []).some((f: any) => f.displayName === F_MATERIAL_NAME));
    expect(owner,
      `AC-7①：field-tree 里没有任何分组含「${F_MATERIAL_NAME}」⇒ 查名字段没产出，本条不成立（先看 AC-1）`
    ).toBeTruthy();
    expect(owner.groupKind,
      `AC-7①：「${F_MATERIAL_NAME}」所在分组的 groupKind 应为 MAIN（内联进锚点分组），` +
      `实际=${owner.groupKind}，分组标题=${groupTitle(owner)}`
    ).toBe('MAIN');

    // ── 🚫 反向断言：不存在名为「物料」「材质」的独立查名分组 ──
    // ⚠️ 本条只对**锚点本身不是物料/材质**的数据源成立，故固定用「来料其他费用」这个坐标验（AC-7 原文即以 AC-1 的界面状态为前提）。
    const titles = groups.map(groupTitle);
    console.log(`[AC-7] field-tree 分组标题 = ${JSON.stringify(titles)}`);
    for (const bad of ['物料', '材质']) {
      expect(titles,
        `AC-7②：面板中不应出现名为「${bad}」的独立查名分组（查名字段必须内联进锚点分组）。` +
        `实际分组 = ${JSON.stringify(titles)}`
      ).not.toContain(bad);
    }

    // ── 面板侧（可观测）：含「材料名」的那个 .svb-grp，其标题应是锚点数据源名 ──
    const stillCollapsed = await expandAllGroups(page);
    expect(stillCollapsed, 'AC-7 前置：分组展不开 ⇒ 断言不可信，本条判【未验证】').toBe(0);

    const grpWithField = page.locator('.svb-grp').filter({
      has: page.getByText(F_MATERIAL_NAME, { exact: true }),
    });
    expect(await grpWithField.count(),
      `AC-7①：面板里含「${F_MATERIAL_NAME}」的分组数应为 1（内联在锚点分组内、且按 coalesceGroup 去重后只出现一次）`
    ).toBe(1);
    const head = await grpWithField.first().locator('.svb-grp-h').innerText();
    console.log(`[AC-7] 含材料名的分组标题 = ${JSON.stringify(head.trim())}`);
    expect(head,
      `AC-7①：含「${F_MATERIAL_NAME}」的分组标题应是锚点数据源「${a.sourceLabel}」，实际=${head.trim()}`
    ).toContain(a.sourceLabel);

    archive('AC-7-分组.txt',
      `# AC-7 组件 ${id} / ${name}\nfield-tree 分组标题 = ${JSON.stringify(titles)}\n` +
      `材料名所在分组 groupKind = ${owner.groupKind} / 标题 = ${groupTitle(owner)}\n` +
      `面板里该分组表头 = ${head.trim()}\n`);
    await shot(page, 'AC-7-材料名内联在锚点分组.png');
  } finally {
    guard.assertUntouched();
  }
});

// ═══════════════════════════════════════════════════════════════════
// AC-3 · 生产料号走独立 JOIN（不被别名缓存吞掉）
// ═══════════════════════════════════════════════════════════════════

test('AC-3: 报价 BOM 同时拖入「材料名」与「生产料号」→ ds_quote_material 出现 2 次且别名不同；'
  + '🚫 反向断言：两列不得共用同一别名', async ({ page }) => {
  test.setTimeout(240_000);
  const a = ANCHORS.materialBom;
  const { id, name } = await openBuilderOf(page, a);
  const guard = readOnlyGuard(id, name);
  try {
    await expandAllGroups(page);

    // 第一步：材料名
    await addField(page, F_MATERIAL_NAME);
    const sqlOne = await sqlPanelText(page);
    const joinsOne = leftJoinsOf(sqlOne, 'ds_quote_material');
    expect(joinsOne.length,
      `AC-3 前置：只加「材料名」时就应有 1 条 LEFT JOIN ds_quote_material（AC-2），实际 ${joinsOne.length} 条`
    ).toBe(1);

    // 第二步：生产料号
    await addField(page, F_PRODUCTION_NO);
    const sqlTwo = await sqlPanelText(page);
    expect(sqlTwo,
      'AC-3 证伪对照：加了「生产料号」后 SQL 逐字未变 ⇒ 列没加进去或读到旧结果'
    ).not.toBe(sqlOne);

    // ── ① ds_quote_material 恰好出现 2 次（🚨 边界匹配，ds_quote_material_bom 不算）──
    const refs = tableRefCount(sqlTwo, 'ds_quote_material');
    const joins = leftJoinsOf(sqlTwo, 'ds_quote_material');
    console.log(`[AC-3] ds_quote_material 引用次数=${refs}，LEFT JOIN 条数=${joins.length}，`
      + `别名=${JSON.stringify(joins.map((j) => j.alias))}`);
    expect(joins.length,
      `AC-3①：ds_quote_material 应出现 **2 次**（材料名一条、生产料号一条），实际 ${joins.length} 条。\n` +
      `SQL=\n${sqlTwo.slice(0, 2000)}`
    ).toBe(2);

    // ── ② 🚫 反向断言：别名必须不同 ──
    // 这正是 SemanticCompiler.ensureLeftJoin 按 target.id 缓存别名会造成的**静默失效**形态：
    // 第二条边的连接键被吞、值取错且不报错。共用别名时 joins.length 会退化成 1（上面已挡），
    // 这里再挡「两条 join 却给了同一个别名」这种更隐蔽的形态。
    const aliases = joins.map((j) => j.alias);
    expect(new Set(aliases).size,
      `AC-3②：两条 LEFT JOIN ds_quote_material 用了**同一个别名** ${JSON.stringify(aliases)}。\n` +
      `  🚨 这就是 ensureLeftJoin 按 target.id 缓存别名的静默失效形态：第二条边的连接键被吞，` +
      `值取错且不报错。SQL=\n${sqlTwo.slice(0, 2000)}`
    ).toBe(2);

    // ── ③ 两条 ON 的连接键必须不同：一条 input_material_no（材料名），一条 .material_no（生产料号，D-2 用销售料号轴列）──
    const byInput = joins.filter((j) => /\binput_material_no\b/.test(j.on));
    const bySales = joins.filter((j) =>
      !/\binput_material_no\b/.test(j.on) && /\.material_no(?![A-Za-z0-9_])/.test(j.on));
    expect(byInput.length,
      `AC-3③：应恰好有 1 条 ON 用 input_material_no（材料名用），实际 ${byInput.length} 条。` +
      `joins=${JSON.stringify(joins, null, 2)}`
    ).toBe(1);
    expect(bySales.length,
      `AC-3③：应恰好有 1 条 ON 用销售料号 material_no（生产料号用，D-2），实际 ${bySales.length} 条。` +
      `joins=${JSON.stringify(joins, null, 2)}`
    ).toBe(1);

    archive('AC-3-sql.txt',
      `# AC-3 组件 ${id} / ${name}\n\n## 只加材料名\n${sqlOne}\n\n## 再加生产料号\n${sqlTwo}\n\n` +
      `## 两条 JOIN\n${JSON.stringify(joins, null, 2)}\n`);
    await shot(page, 'AC-3-生产料号独立JOIN.png');
  } finally {
    guard.assertUntouched();
  }
});

// ═══════════════════════════════════════════════════════════════════
// AC-5 · 物料数据源同表退化，不产生自连接
// ═══════════════════════════════════════════════════════════════════

test('AC-5: 报价「物料」拖入「材料名」→ ds_quote_material 只出现 1 次（锚点自己），无自连接；'
  + '且面板中「品名」与「材料名」并存（D-7 已知代价）', async ({ page }) => {
  test.setTimeout(220_000);
  const a = ANCHORS.quoteMaterial;
  const { id, name } = await openBuilderOf(page, a);
  const guard = readOnlyGuard(id, name);
  try {
    const stillCollapsed = await expandAllGroups(page);
    expect(stillCollapsed, 'AC-5 前置：分组展不开 ⇒ 断言不可信').toBe(0);

    // ── ② 「品名」与「材料名」两个字段并存（AC 原文：D-7「规则无例外」的已知代价，是预期行为）──
    await expect(page.locator('.svb-grp').getByText('品名', { exact: true }).first(),
      'AC-5②：面板里应仍有「品名」（锚点自带列）'
    ).toBeVisible({ timeout: 10_000 });
    await expect(page.locator('.svb-grp').getByText(F_MATERIAL_NAME, { exact: true }).first(),
      `AC-5②：面板里应同时有「${F_MATERIAL_NAME}」（查名字段）`
    ).toBeVisible({ timeout: 10_000 });

    const sqlBefore = await sqlPanelText(page);
    await addField(page, F_MATERIAL_NAME);
    const sqlAfter = await sqlPanelText(page);
    expect(sqlAfter, 'AC-5 证伪对照：加了「材料名」后 SQL 逐字未变 ⇒ 列没加进去或读到旧结果')
      .not.toBe(sqlBefore);

    // ── ① 同表退化：只出现 1 次，且没有 LEFT JOIN ds_quote_material ──
    const refs = tableRefCount(sqlAfter, 'ds_quote_material');
    const joins = leftJoinsOf(sqlAfter, 'ds_quote_material');
    console.log(`[AC-5] ds_quote_material 引用次数=${refs}，LEFT JOIN 条数=${joins.length}`);
    expect(joins.length,
      `AC-5①：不应出现 LEFT JOIN ds_quote_material（锚点就是它自己，应直接取锚点别名的 material_name）。\n` +
      `实际 join = ${JSON.stringify(joins, null, 2)}\nSQL=\n${sqlAfter.slice(0, 1500)}`
    ).toBe(0);
    expect(refs,
      `AC-5①：ds_quote_material 在 SQL 里应只出现 **1 次**（锚点自己），实际 ${refs} 次 ⇒ 产生了自连接。\n` +
      `SQL=\n${sqlAfter.slice(0, 1500)}`
    ).toBe(1);

    archive('AC-5-sql.txt', `# AC-5 组件 ${id} / ${name}\n\n## 加材料名之后\n${sqlAfter}\n`);
    await shot(page, 'AC-5-物料同表退化.png');
  } finally {
    guard.assertUntouched();
  }
});

// ═══════════════════════════════════════════════════════════════════
// AC-5b · 核价「物料」的列名撞名由去重机制消解
// ═══════════════════════════════════════════════════════════════════

test('AC-5b: 核价（基础）「物料」拖入「材料名」→ ds_cost_basic_material 只出现 1 次；'
  + '且该列的视图列名不是裸 material_name（撞名被 dedupeAlias 消解）', async ({ page }) => {
  test.setTimeout(220_000);
  const a = ANCHORS.costBasicMaterial;
  const { id, name } = await openBuilderOf(page, a);
  const guard = readOnlyGuard(id, name);
  try {
    await expandAllGroups(page);

    const sqlBefore = await sqlPanelText(page);
    const compiled = await addField(page, F_MATERIAL_NAME);
    const sqlAfter = await sqlPanelText(page);
    expect(sqlAfter, 'AC-5b 证伪对照：加了「材料名」后 SQL 逐字未变 ⇒ 列没加进去或读到旧结果')
      .not.toBe(sqlBefore);

    // ── ① 同表退化 ──
    const refs = tableRefCount(sqlAfter, 'ds_cost_basic_material');
    const joins = leftJoinsOf(sqlAfter, 'ds_cost_basic_material');
    expect(joins.length,
      `AC-5b①：不应出现 LEFT JOIN ds_cost_basic_material（同表退化）。join=${JSON.stringify(joins)}`
    ).toBe(0);
    expect(refs,
      `AC-5b①：ds_cost_basic_material 应只出现 1 次（锚点自己），实际 ${refs} 次。\nSQL=\n${sqlAfter.slice(0, 1500)}`
    ).toBe(1);

    // ── ② 视图列名不是裸 material_name，且全表列名不重复 ──
    expect(compiled,
      'AC-5b②：没抓到 /builder/compile 响应 ⇒ 拿不到列名清单。' +
      '🚫 不许改成从 SQL 文本正则抠列名（那会把「撞名被消解」验成另一件事）。本条判【未验证】。'
    ).toBeTruthy();
    const cols = compileColumnNames(compiled, 'AC-5b②');
    const names = cols.map((c) => c.viewColumn);
    console.log(`[AC-5b] compile 列名清单 = ${JSON.stringify(cols)}`);

    const dup = names.filter((n, i) => names.indexOf(n) !== i);
    expect(dup,
      `AC-5b②：视图列名出现重复 ${JSON.stringify(dup)} ⇒ 撞名**没有**被消解。列清单=${JSON.stringify(cols)}`
    ).toEqual([]);

    const mnCol = cols.find((c) => c.fieldName === F_MATERIAL_NAME);
    expect(mnCol,
      `AC-5b②：compile 列清单里找不到 fieldName=「${F_MATERIAL_NAME}」的列。清单=${JSON.stringify(cols)}` +
      ` ⇒ 量具未校准（列元数据的字段名属性与预期不符），本条判【未验证】。`
    ).toBeTruthy();
    expect(mnCol!.viewColumn,
      `AC-5b②：「${F_MATERIAL_NAME}」的视图列名是裸 material_name —— 它会与锚点 ds_cost_basic_material ` +
      `自己的 material_name 撞名（核价侧视图列名规则为裸 dbColumn）。应是 dedupeAlias 加过后缀的形式。`
    ).not.toBe('material_name');

    archive('AC-5b-列名.txt',
      `# AC-5b 组件 ${id} / ${name}（主件 / COST_BASIC）\n` +
      `## 实际生成的列名清单\n${JSON.stringify(cols, null, 2)}\n\n` +
      `## 「材料名」的视图列名（AC 要求把它记进证据）\n${mnCol!.viewColumn}\n\n## SQL\n${sqlAfter}\n`);
    await shot(page, 'AC-5b-核价物料撞名消解.png');
  } finally {
    guard.assertUntouched();
  }
});
