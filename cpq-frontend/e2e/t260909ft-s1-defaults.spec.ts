/**
 * task-260909「取数配置器字段类型选择」· S1 · 按方言的默认值（AC-3 / AC-4 / AC-5）
 *
 * AC-3（核心）COST_BASIC：不动任何字段类型选择器直接保存 → `field_type` 全 `BASIC_DATA`，
 *              且每个字段写**顶层 `basic_data_path`**（形如 `$builder_xxxx.<列名>`），**不含 `default_source`**
 * AC-4（核心）COST_DETAIL：同 AC-3
 * AC-5（零回归）QUOTE：`field_type` 按数据类型推 `INPUT_TEXT`/`INPUT_NUMBER`，
 *              写的是 **`default_source.path`**（嵌套对象），**无顶层 `basic_data_path`**
 *
 * 🚨 「不动任何字段类型选择器」是 AC 的字面要求 ⇒ 本 spec 走 **UI 真实保存路径**，
 *    🚫 不用 API 直接塞 fieldType（那验的是另一件事）。
 */
import { test, expect, Page } from '@playwright/test';
import * as FT from './t260909ft.helpers';

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
  test.skip(!backendUp, '后端未启动（harness 前置）');
  await FT.uiLogin(page);
});

test.afterAll(async () => { if (backendUp) FT.archiveOwned(); });

/**
 * 走 UI：建组件 → 取数配置 → 选数据源 → 切数据集 → 加若干列 →
 * **一个字段类型选择器都不碰** → 保存。返回 componentId。
 */
async function buildAndSaveUntouched(
  page: Page, dialect: 'QUOTE' | 'COST_BASIC' | 'COST_DETAIL', sourceLabel: string, suffix: string,
): Promise<{ id: string; name: string; uiTypes: FT.FieldType[] }> {
  const { id, name } = await FT.createComponent(cookie, suffix);
  await FT.openBuilder(page, name);
  await FT.selectSource(page, sourceLabel);
  await FT.selectDataset(page, dialect);
  const stillCollapsed = await FT.expandAllGroups(page);
  expect(stillCollapsed, '前置：左侧分组展不开 ⇒ 加列不可信，判【未验证】').toBe(0);

  // 至少要一个 TEXT 列和一个 NUMBER 列 —— AC-5 的「按数据类型推」需要两种都覆盖，
  // 只加 TEXT 列会让 INPUT_NUMBER 这一半**从未被断言**（空跑）。
  // 🩹 执行轮 2026-09-10：原顺序在加满 4 列时**还没轮到任何 NUMBER 列**，导致 AC-5 只覆盖到
  //    INPUT_TEXT 一种，被本 spec 自己的防空跑守卫判红（守卫是对的，是取样顺序不对）。
  //    把 NUMBER 列「单重」提到最前，保证两种数据类型都进得来。判据一字未改。
  const candidates = ['单重', '材料名', '品名', '规格', '生产料号', '组成用量', '尺寸'];
  let added = 0;
  for (const l of candidates) {
    if (added >= 4) break;
    try { await FT.addField(page, l); added++; } catch { /* 该方言下没有这个字段，跳过 */ }
  }
  expect(added, `前置：${dialect} 下一列都没加进去（试过 ${JSON.stringify(candidates)}）⇒ 断言会空跑，判【未验证】`)
    .toBeGreaterThanOrEqual(2);

  // 🚨 「不动任何字段类型选择器」—— 只**读**不改，并把读到的默认值留档
  const uiTypes = await FT.readAllFieldTypes(page);
  console.log(`[${dialect}] 保存前 UI 上的默认字段类型 =`, JSON.stringify(uiTypes));
  await FT.shot(page, `${dialect}-保存前默认值`);

  await FT.clickBuilderSave(page);
  return { id, name, uiTypes };
}

/** 落库取证：把 `component.fields[]` 全文与关键判据写进证据文件。 */
function evidenceOfFields(label: string, componentId: string, viewName: string) {
  const fields = FT.dbFieldsOf(componentId);
  const raw = FT.sqlScalar(`SELECT coalesce(fields::text,'[]') FROM component WHERE id='${componentId}'`);
  FT.writeEvidence(`${label}-component.fields.json`, raw + '\n');
  FT.writeEvidence(`${label}-判据摘要.txt`,
    [`component = ${componentId}`, `sql_view_name = ${viewName}`,
      ...fields.map((f) =>
        `- ${f.field_name} | field_type=${f.field_type} | basic_data_path=${JSON.stringify(f.basic_data_path)}` +
        ` | default_source=${JSON.stringify(f.default_source)}`)].join('\n') + '\n');
  return fields;
}

// ════════════════════════════════════════════════════════════════════
// AC-3
// ════════════════════════════════════════════════════════════════════
test('AC-3: COST_BASIC 不动选择器直接保存 → field_type 全 BASIC_DATA + 顶层 basic_data_path + 无 default_source',
  async ({ page }) => {
    test.setTimeout(300_000);
    const { id, uiTypes } = await buildAndSaveUntouched(page, 'COST_BASIC', '物料', `AC3-${Date.now()}`);
    const viewName = FT.sqlViewNameOf(id);
    const fields = evidenceOfFields('AC-3-COST_BASIC', id, viewName);

    // 阳性对照：字段非空，否则「全部为 BASIC_DATA」会在 0 个元素上恒真
    expect(fields.length,
      'AC-3：落库 `component.fields` 为空 ⇒ 「全部为 BASIC_DATA」会在 0 个元素上恒真，判【未验证】')
      .toBeGreaterThanOrEqual(2);

    for (const f of fields) {
      expect(f.field_type, `AC-3：字段「${f.field_name}」的 field_type 应为 BASIC_DATA，实际 ${f.field_type}`)
        .toBe('BASIC_DATA');
      expect(f.basic_data_path,
        `AC-3：字段「${f.field_name}」应写**顶层 basic_data_path**，实际 ${JSON.stringify(f.basic_data_path)}`)
        .toBeTruthy();
      expect(String(f.basic_data_path),
        `AC-3：basic_data_path 应形如 $${viewName}.<列名>，实际 ${f.basic_data_path}`)
        .toMatch(new RegExp(`^\\$${viewName}\\.[^.\\s]+$`)); // 🩹 列名可含中文（存量实测 347/420 条如此，非本次引入）
      expect(f.default_source,
        `AC-3：BASIC_DATA 字段「${f.field_name}」**不应含 default_source**，实际 ${JSON.stringify(f.default_source)}`)
        .toBeFalsy();
    }
    // UI 上显示的默认值也应是「基础数据」（前端默认与后端落库口径一致）
    expect(uiTypes, `AC-3：COST_BASIC 下 UI 默认应全为「基础数据」，实际 ${JSON.stringify(uiTypes)}`)
      .toEqual(new Array(uiTypes.length).fill('BASIC_DATA'));
  });

// ════════════════════════════════════════════════════════════════════
// AC-4
// ════════════════════════════════════════════════════════════════════
test('AC-4: COST_DETAIL 不动选择器直接保存 → field_type 全 BASIC_DATA（同 AC-3）', async ({ page }) => {
  test.setTimeout(300_000);
  const { id, uiTypes } = await buildAndSaveUntouched(page, 'COST_DETAIL', '物料', `AC4-${Date.now()}`);
  const viewName = FT.sqlViewNameOf(id);
  const fields = evidenceOfFields('AC-4-COST_DETAIL', id, viewName);

  expect(fields.length, 'AC-4：落库 fields 为空 ⇒ 断言会空跑，判【未验证】').toBeGreaterThanOrEqual(2);
  for (const f of fields) {
    expect(f.field_type, `AC-4：字段「${f.field_name}」的 field_type 应为 BASIC_DATA，实际 ${f.field_type}`)
      .toBe('BASIC_DATA');
    expect(f.basic_data_path, `AC-4：字段「${f.field_name}」应写顶层 basic_data_path`).toBeTruthy();
    expect(f.default_source, `AC-4：BASIC_DATA 字段不应含 default_source`).toBeFalsy();
  }
  expect(uiTypes, `AC-4：COST_DETAIL 下 UI 默认应全为「基础数据」，实际 ${JSON.stringify(uiTypes)}`)
    .toEqual(new Array(uiTypes.length).fill('BASIC_DATA'));
});

// ════════════════════════════════════════════════════════════════════
// AC-5（零回归）
// ════════════════════════════════════════════════════════════════════
test('AC-5 零回归: QUOTE 不动选择器直接保存 → field_type 为 INPUT_TEXT/INPUT_NUMBER + default_source.path + 无顶层 basic_data_path',
  async ({ page }) => {
    test.setTimeout(300_000);
    const { id, uiTypes } = await buildAndSaveUntouched(page, 'QUOTE', '物料', `AC5-${Date.now()}`);
    const viewName = FT.sqlViewNameOf(id);
    const fields = evidenceOfFields('AC-5-QUOTE', id, viewName);

    expect(fields.length, 'AC-5：落库 fields 为空 ⇒ 断言会空跑，判【未验证】').toBeGreaterThanOrEqual(2);
    for (const f of fields) {
      expect(['INPUT_TEXT', 'INPUT_NUMBER'],
        `AC-5：报价侧字段「${f.field_name}」的 field_type 应为 INPUT_TEXT/INPUT_NUMBER，实际 ${f.field_type}`)
        .toContain(f.field_type);
      expect(f.default_source,
        `AC-5：INPUT_* 字段「${f.field_name}」应写 default_source（嵌套对象），实际 ${JSON.stringify(f.default_source)}`)
        .toBeTruthy();
      const path = (f.default_source as any)?.path;
      expect(path, `AC-5：字段「${f.field_name}」的 default_source.path 缺失，实际 ${JSON.stringify(f.default_source)}`)
        .toBeTruthy();
      expect(String(path), `AC-5：default_source.path 应形如 $${viewName}.<列名>，实际 ${path}`)
        .toMatch(new RegExp(`^\\$${viewName}\\.[^.\\s]+$`)); // 🩹 列名可含中文（存量实测 347/420 条如此，非本次引入）
      expect(f.basic_data_path,
        `AC-5：INPUT_* 字段「${f.field_name}」**不应**有顶层 basic_data_path，实际 ${JSON.stringify(f.basic_data_path)}`)
        .toBeFalsy();
    }

    // 🔑 「按数据类型推」必须**两种都出现过**，否则 INPUT_NUMBER 这一半从未被断言（空跑）
    const kinds = new Set(fields.map((f) => f.field_type));
    console.log('[AC-5] 落库 field_type 集合 =', JSON.stringify([...kinds]),
      ' UI 默认 =', JSON.stringify(uiTypes));
    expect(kinds.size,
      `AC-5：本次只覆盖到 ${JSON.stringify([...kinds])} 一种类型 ⇒ 「按数据类型推 TEXT→INPUT_TEXT / 其余→INPUT_NUMBER」` +
      `只验了一半。请确保加入的列里同时有 TEXT 列与 NUMBER 列（本条判【部分未验证】）。`)
      .toBeGreaterThanOrEqual(2);
    expect(uiTypes.every((t) => t === 'INPUT_TEXT' || t === 'INPUT_NUMBER'),
      `AC-5：QUOTE 下 UI 默认应全为「文本输入/数字输入」，实际 ${JSON.stringify(uiTypes)}`).toBe(true);
  });
