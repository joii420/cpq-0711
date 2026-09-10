/**
 * task-260909「取数配置器字段类型选择」· S1 · 存量保护与回填一致性
 *   AC-15（边界·存量保护·核心）打开存量 `COMP-2299` → 各列显示**库里的真实值** →
 *                              什么都不改直接保存 → `component.fields[].field_type` **逐字不变**（12 个全部）
 *   AC-16（契约）任意一次 save() 之后，`builder_config.columns[].fieldType` 不再为 null，
 *                且与 `component.fields[].field_type` **同名字段逐字相同**
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 🚦 **本文件是全套里唯一碰存量对象的地方**，且**只读 + 保存一次**。
 *    `COMP-2299` 是 `核价模板1` 的 BOM 页签 —— 上一个任务刚交付的对象。
 *    万一实现有问题，这一次保存会**真的改坏它**。所以：
 *      ① 保存前把 `fields` / `builder_config` 的完整 JSON + md5 **存档进证据目录**
 *      ② 保存后立刻比对 md5，不一致 = AC-15 失败 ⇒ **停下报主线**并附存档（主线据此还原）
 *      ③ 🚫 只保存这一次（模块级 `savedOnce` 硬闸），🚫 不做任何"顺便试试"的操作
 *      ④ 🚫 不碰 `核价模板1` 本体、不碰 `QT-20260909-0661`
 *
 * 🚫 断言全部来自 `需求文档.md §③ 五点五` 与 `api.md §1.6`，**不读实现代码**。
 */
import { test, expect } from '@playwright/test';
import * as FT from './t260909ft.helpers';

/** AC-15 点名的存量组件。`code` 是身份（唯一），`name` 只作打开后的校验 —— 「BOM」全库重名。 */
const LEGACY = { code: 'COMP-2299', name: 'BOM', expectedFields: 12 };

let cookie = '';
let backendUp = false;
/** 🔒 硬闸：本进程内 `COMP-2299` 只允许保存一次。 */
let savedOnce = false;

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

function legacyId(): string {
  const id = FT.sqlScalar(`SELECT id::text FROM component WHERE code='${LEGACY.code}'`);
  expect(id, `AC-15 前置：库里找不到存量组件 ${LEGACY.code} ⇒ **夹具/环境缺失**，不是产品缺陷，判【未验证】`)
    .toMatch(/^[0-9a-f-]{36}$/);
  return id;
}

// ════════════════════════════════════════════════════════════════════
// AC-15
// ════════════════════════════════════════════════════════════════════
test('AC-15 存量保护: 打开 COMP-2299 各列显示库里真实值（NUMBER 列=数字输入）；什么都不改直接保存 → 12 个字段 field_type 逐字不变',
  async ({ page }) => {
    test.setTimeout(300_000);
    const id = legacyId();

    // ── ① 保存前存档（🔒 出问题时主线据此还原）──────────────────────────
    const fieldsBefore = FT.sqlScalar(`SELECT coalesce(fields::text,'[]') FROM component WHERE id='${id}'`);
    const md5Before = FT.fieldsFingerprint(id);
    const cfgBefore = FT.sqlScalar(
      `SELECT coalesce(builder_config::text,'null') FROM component_sql_view WHERE component_id='${id}'`);
    FT.writeEvidence('AC-15-COMP-2299-保存前-fields.json', fieldsBefore + '\n');
    FT.writeEvidence('AC-15-COMP-2299-保存前-builder_config.json', cfgBefore + '\n');

    const dbFields = FT.dbFieldsOf(id);
    const dbCols = FT.builderColumnsOf(id);
    const nullCount = dbCols.filter((c) => c.fieldType == null).length;
    const typeByName = new Map(dbFields.map((f) => [f.field_name, f.field_type]));

    FT.writeEvidence('AC-15-COMP-2299-存档说明.md',
      [`# AC-15 存量保护 · ${LEGACY.code} 保存前存档（${new Date().toISOString()}）`, '',
        `- component.id = ${id}`,
        `- fields 条数 = ${dbFields.length}`,
        `- **fields md5（保存前）= ${md5Before}**`,
        `- builder_config.columns 条数 = ${dbCols.length}，其中 fieldType 为 null = ${nullCount}`,
        '',
        '## 逐字段真实类型（库里）',
        ...dbFields.map((f) => `- ${f.field_name} = ${f.field_type}`),
        '',
        '🚦 若 AC-15 失败（保存后 md5 变了），用本目录的 `AC-15-COMP-2299-保存前-fields.json` 还原。',
      ].join('\n') + '\n');

    // ── 前置守卫：场景是否还在 ────────────────────────────────────────
    expect(dbFields.length,
      `AC-15 前置：${LEGACY.code} 应有 ${LEGACY.expectedFields} 个字段，实际 ${dbFields.length} 个 ⇒ ` +
      `**夹具/环境漂移**（有人改过它？），本条判【未验证】，🚫 不得记成产品缺陷`).toBe(LEGACY.expectedFields);
    expect(nullCount,
      `AC-15 前置：${LEGACY.code} 的 builder_config.columns[].fieldType 应 **${LEGACY.expectedFields}/${LEGACY.expectedFields} 全为 null**` +
      `（这正是「存量组件」这个场景的定义），实际只有 ${nullCount} 个为 null。\n` +
      `  ⇒ **前置已被消耗**：它可能已被本轮之前的某次保存（或别的会话）写过了。\n` +
      `     此时本条**验不到 AC-15 要验的东西**，判【未验证】，🚫 不是产品缺陷。\n` +
      `  📌 同时请核对 fields 是否被改坏：当前 md5=${md5Before}\n` +
      `     （进场存档在 证据/AC-15-COMP-2299-保存前-fields.json）`).toBe(LEGACY.expectedFields);

    // 阳性对照：真实类型里必须**两种都有**，否则「NUMBER 列显示数字输入」这半从未被断言
    const kinds = new Set(dbFields.map((f) => f.field_type));
    expect(kinds.has('INPUT_NUMBER') && kinds.has('INPUT_TEXT'),
      `AC-15 前置：${LEGACY.code} 的真实类型应同时含 INPUT_TEXT 与 INPUT_NUMBER（AC 原文点名），` +
      `实际 ${JSON.stringify([...kinds])} ⇒ 「NUMBER 列显示数字输入」这半会空跑，判【未验证】`).toBe(true);

    // ── ② UI：打开取数配置，读逐列显示值 ──────────────────────────────
    await FT.openBuilder(page, LEGACY.name, LEGACY.code);
    await FT.shot(page, 'AC-15-01-COMP-2299-取数配置');

    const shown = await FT.readAllFieldTypes(page);
    const uiNames = await FT.readSelectedColumnFieldNames(page);
    console.log(`[AC-15] UI 逐列显示 = ${JSON.stringify(shown)}`);
    console.log(`[AC-15] UI 逐列字段名 = ${JSON.stringify(uiNames)}`);
    console.log(`[AC-15] 库里真实值   = ${JSON.stringify(dbFields.map((f) => `${f.field_name}=${f.field_type}`))}`);

    expect(shown.length,
      `AC-15①：配置器里的列数（${shown.length}）应与库里的字段数（${dbFields.length}）一致，` +
      `否则逐列比对会错位 ⇒ **量具/入口问题**，判【未验证】`).toBe(dbFields.length);

    // 🚨 兜底值的症状 = **所有列显示同一个值**。先把这条最直接的判据打上。
    expect(new Set(shown).size,
      `AC-15①：配置器 ${shown.length} 列**全部显示同一个值** ${JSON.stringify(shown[0])} ⇒ ` +
      `这正是「显示的是统一兜底值、不是库里真实值」的症状（api.md §1.6 的 GET 回填没接上）。\n` +
      `  库里真实值 = ${JSON.stringify(dbFields.map((f) => `${f.field_name}=${f.field_type}`))}`)
      .toBeGreaterThan(1);

    // 逐列比对：优先按字段名精确配对；读不到字段名时降级到多重集（并写明用了哪条路径）
    const sortMs = (a: string[]) => [...a].sort().join(',');
    let matchPath = '';
    if (uiNames.length === shown.length && uiNames.every((n) => typeByName.has(n))) {
      matchPath = '按字段名精确配对';
      for (let i = 0; i < shown.length; i++) {
        expect(shown[i],
          `AC-15①：字段「${uiNames[i]}」库里真实是 ${typeByName.get(uiNames[i])}，` +
          `配置器却显示 ${shown[i]}（${FT.FT_LABEL[shown[i]]}）⇒ 回填显示的不是真实值。\n` +
          `  UI 全量 = ${JSON.stringify(uiNames.map((n, k) => `${n}=${shown[k]}`))}`)
          .toBe(typeByName.get(uiNames[i]));
      }
    } else {
      matchPath = `多重集比对（读不到 UI 字段名：${JSON.stringify(uiNames)}）`;
      console.log(`[AC-15] ⚠️ 量具降级：${matchPath} —— 降的是精度，不是断言强度`);
      expect(sortMs(shown),
        `AC-15①：配置器逐列显示值的多重集应与库里真实值的多重集相同。\n` +
        `  UI = ${JSON.stringify([...shown].sort())}\n` +
        `  DB = ${JSON.stringify(dbFields.map((f) => f.field_type).sort())}`)
        .toBe(sortMs(dbFields.map((f) => f.field_type)));
    }
    // AC 原文点名的两句，单独再打一次（可读性强的判据）
    expect(shown, 'AC-15①：应有列显示「数字输入」（对应 NUMBER 列）').toContain('INPUT_NUMBER');
    expect(shown, 'AC-15①：应有列显示「文本输入」（对应 TEXT 列）').toContain('INPUT_TEXT');

    // ── ③ 什么都不改，直接保存（🔒 只此一次）────────────────────────────
    expect(savedOnce,
      `🚨 硬闸：${LEGACY.code} 在本进程内已经保存过一次，🚫 不允许第二次 —— 停下来报主线`).toBe(false);
    savedOnce = true;
    await FT.clickBuilderSave(page);
    await FT.shot(page, 'AC-15-02-COMP-2299-保存后');

    // ── ④ 逐字比对 ────────────────────────────────────────────────────
    const md5After = FT.fieldsFingerprint(id);
    const fieldsAfter = FT.dbFieldsOf(id);
    FT.writeEvidence('AC-15-COMP-2299-保存后-fields.json',
      FT.sqlScalar(`SELECT coalesce(fields::text,'[]') FROM component WHERE id='${id}'`) + '\n');

    const diff = fieldsAfter
      .map((f, i) => ({ name: f.field_name, before: dbFields[i]?.field_type, after: f.field_type }))
      .filter((x) => x.before !== x.after);
    FT.writeEvidence('AC-15-保存前后对照.md',
      [`# AC-15 · ${LEGACY.code} 保存前后对照（${new Date().toISOString()}）`, '',
        `- 保存前 md5 = ${md5Before}`,
        `- 保存后 md5 = ${md5After}`,
        `- 逐列显示值 = ${JSON.stringify(shown)}（配对方式：${matchPath}）`, '',
        '| 字段名 | 保存前 field_type | 保存后 field_type | 变了？ |',
        '|---|---|---|---|',
        ...fieldsAfter.map((f, i) =>
          `| ${f.field_name} | ${dbFields[i]?.field_type} | ${f.field_type} | ` +
          `${dbFields[i]?.field_type === f.field_type ? '否' : '**是**'} |`),
      ].join('\n') + '\n');

    expect(diff,
      `🚨 **AC-15② 失败：${LEGACY.code} 的字段类型被这一次「什么都不改的保存」改掉了。**\n` +
      `  变更清单 = ${JSON.stringify(diff, null, 2)}\n` +
      `  ⇒ **停下来报主线**。保存前存档在 证据/AC-15-COMP-2299-保存前-fields.json，据此还原。\n` +
      `  这正是 F-3 单独做会引入的全面回归：42 个存量组件「打开即损坏」。`).toEqual([]);
    expect(md5After,
      `🚨 **AC-15② 失败：${LEGACY.code} 的 fields 整体 md5 变了**（逐字段 field_type 虽然一致，` +
      `但 fields 里还有别的东西被改动）。\n  before=${md5Before}\n  after =${md5After}\n` +
      `  ⇒ **停下来报主线**，附 证据/AC-15-COMP-2299-保存前-fields.json`).toBe(md5Before);

    // ── AC-16 附带取证（同一次保存，**只读**，不额外写库）──────────────
    const parity = FT.fieldTypeParity(id);
    FT.writeEvidence('AC-16-COMP-2299-并排对照.md',
      [`# AC-16 · ${LEGACY.code} 保存后 fieldType 一致性（AC-15 那一次保存的附带取证，只读）`, '',
        parity.table, '',
        `- builder_config 里仍为 null 的列数 = ${parity.nullCount}`,
        `- 不一致项 = ${JSON.stringify(parity.mismatches)}`].join('\n') + '\n');
    console.log(`[AC-16 附带] ${LEGACY.code} null=${parity.nullCount} mismatches=${JSON.stringify(parity.mismatches)}`);
  });

// ════════════════════════════════════════════════════════════════════
// AC-16
// ════════════════════════════════════════════════════════════════════
test('AC-16 契约: 任意一次 save() 之后，builder_config.columns[].fieldType 不再为 null，且与 component.fields[].field_type 同名字段逐字相同',
  async () => {
    test.setTimeout(180_000);

    // 🚨 主路径用**自建组件**（`T260909FT-` 前缀），🚫 不为验这条再去写存量对象。
    //    两个方向各验一次：
    //      A. **不传** fieldType（旧客户端）—— 这才是 null 会残留下来的那条路径
    //      B. 显式传合法值 —— 保证 effective 值就是显式值
    const BASE: FT.BuilderConfigBody = {
      tabType: '主件', variantKey: '', dialect: 'COST_BASIC',
      columns: [
        { sourceNodeKey: 'MATERIAL', sourceColumn: 'production_no', fieldName: '生产料号',
          dataType: 'TEXT', roles: ['PART_NO', 'ROW_KEY'], isPartNo: true, isRowKey: true },
        { sourceNodeKey: 'MATERIAL', sourceColumn: 'material_name', fieldName: '材料名', dataType: 'TEXT', roles: [] },
        { sourceNodeKey: 'MATERIAL', sourceColumn: 'unit_weight', fieldName: '单重', dataType: 'NUMBER', roles: [] },
      ],
    };

    const report: string[] = [`# AC-16 · builder_config.fieldType 与 component.fields.field_type 一致性`, ''];

    const cases: { label: string; body: FT.BuilderConfigBody }[] = [
      { label: 'A · 不传 fieldType（旧客户端，null 残留的那条路径）',
        body: { ...BASE, columns: BASE.columns.map(({ fieldType, ...rest }) => rest) } },
      { label: 'B · 显式传合法值（INPUT_TEXT）',
        body: { ...BASE, columns: BASE.columns.map((c) => ({ ...c, fieldType: 'INPUT_TEXT' })) } },
    ];

    for (const c of cases) {
      const { id, name } = await FT.createComponent(cookie, `AC16-${Date.now()}`);
      const r = await FT.saveBuilder(cookie, id, c.body);
      expect(r.status, `AC-16 前置（${c.label}）：保存应 2xx，实际 ${r.status}：${r.text.slice(0, 300)}`)
        .toBeLessThan(300);

      const parity = FT.fieldTypeParity(id);
      report.push(`## ${c.label}（组件 ${name} / ${id}）`, '', parity.table, '',
        `- 仍为 null 的列数 = ${parity.nullCount}`, `- 不一致项 = ${JSON.stringify(parity.mismatches)}`, '');

      // 阳性对照：列数非空，否则下面两条会在 0 行上恒真
      expect(parity.rows.length,
        `AC-16（${c.label}）：builder_config.columns 为空 ⇒ 「不再为 null」「逐字相同」都会在 0 行上恒真，判【未验证】`)
        .toBe(BASE.columns.length);
      expect(parity.rows.every((x) => x.fieldsFieldType != null),
        `AC-16（${c.label}）：有列在 component.fields 里按字段名配不到 ⇒ 对照表右列全是 null，` +
        `「逐字相同」会退化成「两边都 null」的假绿。\n${parity.table}`).toBe(true);

      expect(parity.nullCount,
        `AC-16（${c.label}）：save() 之后 builder_config.columns[].fieldType **不应再为 null**（api.md §1.6），` +
        `实际还有 ${parity.nullCount} 个为 null。\n${parity.table}`).toBe(0);
      expect(parity.mismatches,
        `AC-16（${c.label}）：builder_config.columns[].fieldType 应与 component.fields[].field_type 同名字段**逐字相同**。\n` +
        `${parity.table}`).toEqual([]);
    }

    FT.writeEvidence('AC-16-并排对照.md', report.join('\n') + '\n');
  });
