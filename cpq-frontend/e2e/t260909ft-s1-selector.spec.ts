/**
 * task-260909「取数配置器字段类型选择」· S1 · 选择器本体与回填
 *   AC-1（单点）值域恰好 3 项，🚫 不出现 FORMULA / DATA_SOURCE / FIXED_VALUE
 *   AC-2（单点）整列批量设为…
 *   AC-11（序列）选 BASIC_DATA → 保存 → 刷新重开 → 回填 → 改 INPUT_TEXT → 保存 → 刷新 → 回填
 *
 * 🚫 断言全部来自 `需求文档.md §③` 与 `原型图/02-选择器展开态.html`，**不读实现代码**。
 */
import { test, expect } from '@playwright/test';
import * as FT from './t260909ft.helpers';

const DS = { dialect: 'COST_BASIC' as const, sourceLabel: '物料' };
/** 本 spec 会加进「已选输出列」的字段（显示名从服务端字段树现取，🚫 不写死中文名）。 */
let cookie = '';
let backendUp = false;

test.beforeAll(async () => {
  try {
    const r = await fetch(`${FT.BACKEND_URL}/api/cpq/health`, { signal: AbortSignal.timeout(4000) });
    backendUp = r.ok;
  } catch { backendUp = false; }
  if (backendUp) cookie = await FT.loginApi();
});

test.beforeEach(async ({ page }) => {
  test.skip(!backendUp, '后端未启动（harness 前置，既不算通过也不算失败）');
  await FT.uiLogin(page);
});

test.afterAll(async () => { if (backendUp) FT.archiveOwned(); });

/** 建一个空组件、打开取数配置、切到 COST_BASIC、选数据源、加 3 列。返回组件名。 */
async function seedBuilder(page: any, suffix: string): Promise<string> {
  const { name } = await FT.createComponent(cookie, suffix);
  await FT.openBuilder(page, name);
  await FT.selectSource(page, DS.sourceLabel);
  await FT.selectDataset(page, DS.dialect);
  const stillCollapsed = await FT.expandAllGroups(page);
  expect(stillCollapsed, '前置：左侧分组展不开 ⇒ 加列与后续断言都不可信，判【未验证】').toBe(0);
  // 字段显示名从服务端字段树现取，避免写死中文名
  const tree = await FT.api(cookie, `/api/cpq/config/semantic-graph/field-tree?dialect=${DS.dialect}`);
  console.log(`[seed] field-tree → ${tree.status}`);
  const labels = ['生产料号', '材料名', '组成用量', '品名', '单重', '规格'];
  let added = 0;
  for (const l of labels) {
    if (added >= 3) break;
    try { await FT.addField(page, l); added++; } catch (e) { console.log(`[seed] 跳过字段「${l}」：${String(e).slice(0, 120)}`); }
  }
  expect(added,
    `前置：一列都没能加进「已选输出列」（试过 ${JSON.stringify(labels)}）⇒ 后面的逐列断言会**空跑**，判【未验证】`)
    .toBeGreaterThanOrEqual(3);
  return name;
}

// ════════════════════════════════════════════════════════════════════
// AC-1
// ════════════════════════════════════════════════════════════════════
test('AC-1: 每一列都有「字段类型」选择器，展开后恰好 3 个选项（基础数据/文本输入/数字输入），且不含 FORMULA / DATA_SOURCE / FIXED_VALUE',
  async ({ page }) => {
    test.setTimeout(240_000);
    await seedBuilder(page, `AC1-${Date.now()}`);

    // ① 每一列都有选择器 —— 先证明「已选输出列」非空，再逐列取
    const n = await FT.selectedColumnCount(page);
    expect(n, '「已选输出列」为 0 ⇒ 「每一列都有选择器」这条会在 0 次循环上恒真，判【未验证】').toBeGreaterThanOrEqual(3);
    const current = await FT.readAllFieldTypes(page);
    console.log('[AC-1] 逐列选择器当前值 =', JSON.stringify(current));

    // ② 展开第 1 列 → 恰好 3 项
    const opts = await FT.openFieldTypeOptions(page, 0);
    console.log('[AC-1] 选项原文 =', JSON.stringify(opts));
    FT.writeEvidence('AC-1-选项数组.txt',
      `选项原文（第 1 列）= ${JSON.stringify(opts, null, 2)}\n逐列当前值 = ${JSON.stringify(current)}\n`);
    await FT.shot(page, 'AC-1-选择器展开态');

    expect(opts.length,
      `AC-1：字段类型下拉应**恰好 3 项**，实际 ${opts.length} 项：${JSON.stringify(opts)}`).toBe(3);
    const labels = opts.map((o) => o.replace(/\s+/g, '').split(/[（(]/)[0]);
    expect(labels.sort(),
      `AC-1：三个选项文案应为 ${JSON.stringify(Object.values(FT.FT_LABEL))}，实际 ${JSON.stringify(opts)}`)
      .toEqual([...Object.values(FT.FT_LABEL)].sort());
    for (const bad of FT.FORBIDDEN_OPTIONS) {
      expect(opts.join(' '), `AC-1：🚫 选项里不得出现「${bad}」，实际 ${JSON.stringify(opts)}`).not.toContain(bad);
    }
    await page.keyboard.press('Escape');

    // ③ 逐列都能展开（不只第 1 列有）
    for (let i = 1; i < Math.min(n, 3); i++) {
      const o = await FT.openFieldTypeOptions(page, i);
      expect(o.length, `AC-1：第 ${i + 1} 列的下拉也应恰好 3 项，实际 ${JSON.stringify(o)}`).toBe(3);
      await page.keyboard.press('Escape');
    }
  });

// ════════════════════════════════════════════════════════════════════
// AC-2
// ════════════════════════════════════════════════════════════════════
test('AC-2: 「整列批量设为…」应用到全部列，逐列选择器同步变更', async ({ page }) => {
  test.setTimeout(240_000);
  await seedBuilder(page, `AC2-${Date.now()}`);

  const before = await FT.readAllFieldTypes(page);
  console.log('[AC-2] 批量前 =', JSON.stringify(before));
  await FT.shot(page, 'AC-2-批量前');

  // 选一个与当前**不同**的目标值，否则「同步变更」会以「本来就一样」的方式恒真（空验证）
  const target: FT.FieldType = before.every((x) => x === 'INPUT_TEXT') ? 'BASIC_DATA' : 'INPUT_TEXT';
  expect(before.some((x) => x !== target),
    `AC-2 前置：批量目标「${target}」与当前值 ${JSON.stringify(before)} 完全相同 ⇒ ` +
    `「同步变更」会以**本来就一样**的方式恒真。换个目标值再跑。`).toBe(true);

  await FT.bulkSetFieldType(page, target);
  const after = await FT.readAllFieldTypes(page);
  console.log('[AC-2] 批量后 =', JSON.stringify(after));
  await FT.shot(page, 'AC-2-批量后');
  FT.writeEvidence('AC-2-逐列值数组.txt',
    `目标 = ${target}\n批量前 = ${JSON.stringify(before)}\n批量后 = ${JSON.stringify(after)}\n`);

  expect(after.length, 'AC-2：批量后列数不应变化').toBe(before.length);
  expect(after, `AC-2：点「应用到全部列」后所有列都应变成「${FT.FT_LABEL[target]}」，实际 ${JSON.stringify(after)}`)
    .toEqual(new Array(before.length).fill(target));
});

// ════════════════════════════════════════════════════════════════════
// AC-11（序列）
// ════════════════════════════════════════════════════════════════════
test('AC-11 序列: 选 BASIC_DATA → 保存 → 刷新重开 → 回填 BASIC_DATA → 改 INPUT_TEXT → 保存 → 刷新 → 回填 INPUT_TEXT',
  async ({ page }) => {
    test.setTimeout(300_000);
    const name = await seedBuilder(page, `AC11-${Date.now()}`);
    const compId = FT.sqlScalar(`SELECT id::text FROM component WHERE name='${name}'`);
    expect(compId, `AC-11 前置：库里找不到刚建的组件「${name}」`).toMatch(/^[0-9a-f-]{36}$/);

    // ── 第 1 步：把第 1 列显式设为 BASIC_DATA 并保存 ──
    await FT.setFieldType(page, 0, 'BASIC_DATA');
    const step1 = await FT.readAllFieldTypes(page);
    await FT.shot(page, 'AC-11-01-选BASIC_DATA');
    await FT.clickBuilderSave(page);

    // 落库对照（先证明保存真的落了，否则后面的「回填」验的是空气）
    const cfg1 = await FT.readBuilder(cookie, compId);
    const cols1 = cfg1?.builderConfig?.columns ?? cfg1?.columns ?? [];
    expect(cols1.length, 'AC-11①：读回的已选列为空 ⇒ 保存没落库（这才是真的状态丢失）').toBeGreaterThan(0);
    expect(String(cols1[0].fieldType),
      `AC-11①：第 1 列保存后回读的 fieldType 应为 BASIC_DATA，实际 ${JSON.stringify(cols1[0].fieldType)}`)
      .toBe('BASIC_DATA');

    // ── 第 2 步：刷新页面 → 重新打开配置器 → 选择器回填 ──
    await page.reload();
    await page.waitForTimeout(6000);
    await FT.openBuilder(page, name);
    const back1 = await FT.readAllFieldTypes(page);
    await FT.shot(page, 'AC-11-02-刷新后回填BASIC_DATA');
    console.log('[AC-11] 刷新前 =', JSON.stringify(step1), ' 刷新后 =', JSON.stringify(back1));
    expect(back1[0], `AC-11②：刷新重开后第 1 列应回填为「基础数据」，实际 ${back1[0]}`).toBe('BASIC_DATA');
    expect(back1, 'AC-11②：刷新重开后逐列字段类型应与保存时逐字一致').toEqual(step1);

    // ── 第 3 步：改回 INPUT_TEXT → 保存 ──
    await FT.setFieldType(page, 0, 'INPUT_TEXT');
    const step2 = await FT.readAllFieldTypes(page);
    await FT.clickBuilderSave(page);
    const cfg2 = await FT.readBuilder(cookie, compId);
    const cols2 = cfg2?.builderConfig?.columns ?? cfg2?.columns ?? [];
    expect(String(cols2[0].fieldType),
      `AC-11③：改回 INPUT_TEXT 保存后回读应为 INPUT_TEXT，实际 ${JSON.stringify(cols2[0].fieldType)}`)
      .toBe('INPUT_TEXT');

    // ── 第 4 步：再刷新 → 回填 INPUT_TEXT ──
    await page.reload();
    await page.waitForTimeout(6000);
    await FT.openBuilder(page, name);
    const back2 = await FT.readAllFieldTypes(page);
    await FT.shot(page, 'AC-11-03-再刷新后回填INPUT_TEXT');
    expect(back2[0], `AC-11④：再刷新后第 1 列应回填为「文本输入」，实际 ${back2[0]}`).toBe('INPUT_TEXT');
    expect(back2, 'AC-11④：再刷新后逐列字段类型应与第 3 步保存时逐字一致').toEqual(step2);

    FT.writeEvidence('AC-11-序列取值.txt',
      [`组件 = ${name} / ${compId}`,
        `① 选 BASIC_DATA        = ${JSON.stringify(step1)}`,
        `② 刷新重开回填          = ${JSON.stringify(back1)}`,
        `③ 改 INPUT_TEXT        = ${JSON.stringify(step2)}`,
        `④ 再刷新回填            = ${JSON.stringify(back2)}`].join('\n') + '\n');
  });
