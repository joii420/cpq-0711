/**
 * task-260909「取数配置器字段类型选择」· S1 · 边界（AC-8 / AC-9）+ 无副作用（AC-13）
 *
 * AC-8（边界·非法值）`fieldType:"FORMULA"` → 400，消息列出 3 个合法值；垃圾值 `"XXX"` → 400；
 *                    库中该组件字段**不发生变更**（md5 逐字对照）
 * AC-9（边界·向后兼容）**不传** `fieldType`（模拟旧客户端）→ 维持按方言默认的现状行为，不报错
 * AC-13（无副作用）交付前后存量组件的 `field_type` 分布逐位相同
 *
 * 🚨 AC-8 全程走 `PUT /api/cpq/components/{id}/builder` 这条**真实 HTTP 保存路径**，
 *    🚫 不反射直调校验函数（后端子代理正是在那里写出过白测：删掉白名单调用，用例照样全绿）。
 *    并配一条**接线阳性对照**：同一端点传合法值必须 2xx 且**让指纹真的变化** ——
 *    否则「非法保存后字段未变」会因为「这条路径压根不写库」而恒真。
 *
 * 🚨 开跑第一件事是**验明正身**（`assertNewCodeServing`）：
 *    旧代码零校验 ⇒ 传 FORMULA 会 200 并落库。只探活证明不了跑的是本次的代码。
 */
import { test, expect } from '@playwright/test';
import * as FT from './t260909ft.helpers';

let cookie = '';
let backendUp = false;

/** COST_BASIC / 主件 的最小配置 —— 列形状照抄库里既有 COST_BASIC 组件（`api.md §1.5`）。 */
const SAMPLE: FT.BuilderConfigBody = {
  tabType: '主件',
  variantKey: '',
  dialect: 'COST_BASIC',
  columns: [
    { sourceNodeKey: 'MATERIAL', sourceColumn: 'production_no', fieldName: '生产料号',
      dataType: 'TEXT', roles: ['PART_NO', 'ROW_KEY'], isPartNo: true, isRowKey: true },
    { sourceNodeKey: 'MATERIAL', sourceColumn: 'material_name', fieldName: '材料名', dataType: 'TEXT', roles: [] },
    { sourceNodeKey: 'MATERIAL', sourceColumn: 'unit_weight', fieldName: '单重', dataType: 'NUMBER', roles: [] },
  ],
};

/**
 * 🩹 执行轮 2026-09-10：`SAMPLE` 的列取自 **COST_BASIC** 的数据源节点（`MATERIAL`/`production_no`）。
 * 把它原样换个 `dialect: 'QUOTE'` 送出去，那些列在 QUOTE 下**根本不存在** ⇒ 角色解析不出
 * `PART_NO`/`PART_NAME`，后端保存前体检返 400「缺少标识列：料号列与名称列至少要配一个」。
 * 那是**用例 payload 无效**，与 AC-9 要验的「不传 fieldType 不报错」无关（症状却长得一模一样）。
 * ⇒ QUOTE 单独给一组该方言下真实存在的列（形状照抄库里已跑通的 QUOTE 组件 `T260909FT-QUOTE-NEW`）。
 * TEXT 与 NUMBER **两种都放**，否则「按数据类型推」只验一半。
 */
const QUOTE_COLUMNS: FT.BuilderConfigBody['columns'] = [
  { sourceNodeKey: 'SELF_PROCESS_FEE', sourceColumn: 'input_material_no', fieldName: '料号',
    dataType: 'TEXT', roles: ['PART_NO', 'ROW_KEY'], isPartNo: true, isRowKey: true },
  { sourceNodeKey: 'SELF_PROCESS_FEE', sourceColumn: 'material_no', fieldName: '销售料号',
    dataType: 'TEXT', roles: ['ROW_KEY'], isRowKey: true },
  { sourceNodeKey: 'SELF_PROCESS_FEE', sourceColumn: 'item_seq', fieldName: '项次',
    dataType: 'NUMBER', roles: [] },
];

test.beforeAll(async () => {
  try {
    const r = await fetch(`${FT.BACKEND_URL}/api/cpq/health`, { signal: AbortSignal.timeout(4000) });
    backendUp = r.ok;
  } catch { backendUp = false; }
  if (backendUp) cookie = await FT.loginApi();
});

test.beforeEach(() => { test.skip(!backendUp, '后端未启动（harness 前置）'); });
test.afterAll(async () => { if (backendUp) FT.archiveOwned(); });

// ════════════════════════════════════════════════════════════════════
// 0. 验明正身（不是 AC，是本轮全部结论的前提）
// ════════════════════════════════════════════════════════════════════
test('0. 验明正身: 被测后端跑的是含本次改动的代码（传 FORMULA 必须 400）', async () => {
  test.setTimeout(120_000);
  await FT.assertServingExpectedDb(cookie);
  await FT.assertNewCodeServing(cookie, SAMPLE);
});

// ════════════════════════════════════════════════════════════════════
// AC-8
// ════════════════════════════════════════════════════════════════════
test('AC-8 边界: fieldType=FORMULA → 400 且消息列出 3 个合法值；垃圾值 XXX → 400；库中字段逐字不变', async () => {
  test.setTimeout(180_000);

  // 先建一个**已保存过一次**的组件，这样「字段不发生变更」才有可对照的非空基线
  const { id, name } = await FT.createComponent(cookie, `AC8-${Date.now()}`);
  const ok = await FT.saveBuilder(cookie, id, SAMPLE);
  expect(ok.status, `AC-8 前置：合法保存应 2xx，实际 ${ok.status}：${ok.text.slice(0, 300)}`).toBeLessThan(300);
  const baseline = FT.fieldsFingerprint(id);
  const baselineFields = FT.dbFieldsOf(id);
  expect(baselineFields.length,
    'AC-8 前置：基线 fields 为空 ⇒ 「字段不发生变更」会在空对空上恒真（假绿），判【未验证】').toBeGreaterThan(0);
  console.log(`[AC-8] 基线 ${name} fields=${baselineFields.length} 个，指纹=${baseline}`);

  const report: string[] = [`组件 = ${name} / ${id}`, `基线指纹 = ${baseline}`, ''];

  // ① FORMULA → 400 + 消息列出 3 个合法值
  const bad1 = await FT.saveBuilder(cookie, id, {
    ...SAMPLE, columns: SAMPLE.columns.map((c, i) => (i === 0 ? { ...c, fieldType: 'FORMULA' } : c)),
  });
  report.push(`① fieldType="FORMULA" → HTTP ${bad1.status}\n   响应体原文: ${bad1.text}`);
  expect(bad1.status, `AC-8①：传 FORMULA 应 400（api.md §1.4），实际 ${bad1.status}：${bad1.text.slice(0, 400)}`).toBe(400);
  for (const v of FT.FIELD_TYPES) {
    expect(bad1.text, `AC-8①：400 的 message 应列出合法值「${v}」，实际响应=${bad1.text.slice(0, 400)}`).toContain(v);
  }
  expect(bad1.text, 'AC-8①：message 里应带上被拒的值 FORMULA').toContain('FORMULA');

  // ② 垃圾值 → 400
  const bad2 = await FT.saveBuilder(cookie, id, {
    ...SAMPLE, columns: SAMPLE.columns.map((c, i) => (i === 1 ? { ...c, fieldType: 'XXX' } : c)),
  });
  report.push(`② fieldType="XXX" → HTTP ${bad2.status}\n   响应体原文: ${bad2.text}`);
  expect(bad2.status, `AC-8②：传垃圾值 "XXX" 应 400，实际 ${bad2.status}：${bad2.text.slice(0, 400)}`).toBe(400);

  // ③ 另两个被否决的值也应被拒（AC-8 的「等等」在 api.md §1.2 里点名了三个）
  for (const v of ['DATA_SOURCE', 'FIXED_VALUE']) {
    const r = await FT.saveBuilder(cookie, id, {
      ...SAMPLE, columns: SAMPLE.columns.map((c, i) => (i === 0 ? { ...c, fieldType: v } : c)),
    });
    report.push(`③ fieldType="${v}" → HTTP ${r.status}\n   响应体原文: ${r.text}`);
    expect(r.status, `AC-8③：传 ${v} 应 400（api.md §1.2 明确不接受），实际 ${r.status}：${r.text.slice(0, 300)}`).toBe(400);
  }

  // ④ 🚫 校验失败时不得有任何落库 —— 逐字对照
  const after = FT.fieldsFingerprint(id);
  report.push('', `校验后指纹 = ${after}`);
  expect(after,
    `AC-8④：四次非法保存之后，组件 ${name} 的 fields **必须逐字未变**（api.md §1.4 明确「不得有任何落库」）。\n` +
    `  before=${baseline}\n  after =${after}`).toBe(baseline);

  // ⑤ 🔑 **接线阳性对照**（主线 2026-09-09 点名：别让断言绕过真实 save()）
  //
  //    本用例全程走 `PUT /api/cpq/components/{id}/builder` 这条**真实 HTTP 保存路径**，
  //    不是反射直调校验函数 —— 后端子代理正是在那里栽过：删掉白名单调用，反射直调的用例照样全绿。
  //    但"走了 HTTP"还不够，还要证明**这条路径此刻真的能写库**，否则：
  //      · 400 可能来自与 fieldType 无关的原因（端点整个坏了）
  //      · 而「fields 逐字未变」会因此**恒真** —— 两个断言同时以假绿通过
  //    ⇒ 用一次**合法**保存把 field_type 真的改掉，确认指纹会动。指纹是把活的尺子，不是常量。
  const legal = await FT.saveBuilder(cookie, id, {
    ...SAMPLE, columns: SAMPLE.columns.map((c) => ({ ...c, fieldType: 'INPUT_TEXT' })),
  });
  const afterLegal = FT.fieldsFingerprint(id);
  const kindsLegal = FT.dbFieldsOf(id).map((f) => f.field_type);
  report.push('',
    `⑤ 接线阳性对照：同一端点传**合法** fieldType="INPUT_TEXT" → HTTP ${legal.status}`,
    `   合法保存后指纹 = ${afterLegal}`,
    `   合法保存后 field_type = ${JSON.stringify(kindsLegal)}`);
  FT.writeEvidence('AC-8-非法值响应与指纹.txt', report.join('\n') + '\n');

  expect(legal.status,
    `AC-8⑤ 接线阳性对照：同一端点传合法值应 2xx，实际 ${legal.status}：${legal.text.slice(0, 300)}\n` +
    `  🚨 这里非 2xx ⇒ 上面的 400 可能与 fieldType 无关（端点本身坏了），` +
    `整条 AC-8 的结论无效，判【未验证】。`).toBeLessThan(300);
  expect(kindsLegal, `AC-8⑤：合法保存后 field_type 应全部变成 INPUT_TEXT，实际 ${JSON.stringify(kindsLegal)}`)
    .toEqual(new Array(kindsLegal.length).fill('INPUT_TEXT'));
  expect(afterLegal,
    `AC-8⑤ 接线阳性对照：合法保存**必须**让 fields 指纹变化，实际仍是 ${afterLegal}。\n` +
    `  🚨 指纹不动 ⇒ 这条保存路径此刻根本不写库，于是上面「非法保存后逐字未变」是**恒真的假绿**，` +
    `什么都没验到。判【未验证】。`).not.toBe(baseline);
});

// ════════════════════════════════════════════════════════════════════
// AC-9
// ════════════════════════════════════════════════════════════════════
test('AC-9 边界·向后兼容: 保存请求不传 fieldType（旧客户端）→ 不报错，且按方言默认落库', async () => {
  test.setTimeout(180_000);
  const rows: string[] = [];

  for (const dialect of ['COST_BASIC', 'COST_DETAIL', 'QUOTE'] as const) {
    const { id, name } = await FT.createComponent(cookie, `AC9-${dialect}-${Date.now()}`);
    // 🚨 逐列**删掉** fieldType 键（不是传 null 也不是传 ""），精确模拟「旧客户端不发这个字段」
    // QUOTE 的锚点/页签形态也与 COST_BASIC 不同：沿用 `tabType:'主件'` 会得到
    // 400 `COMPILE_PATH_NOT_FOUND: 锚点「物料」没有到「自制加工费」的声明边` ——
    // 同样是**用例 payload 无效**，不是 AC-9 要验的东西。照抄已跑通的 QUOTE 组件的口径。
    const baseCols = dialect === 'QUOTE' ? QUOTE_COLUMNS : SAMPLE.columns;
    const shape = dialect === 'QUOTE'
      ? { tabType: '费用类', variantKey: 'SELF_PROCESS_FEE' }
      : { tabType: SAMPLE.tabType, variantKey: SAMPLE.variantKey };
    const body: FT.BuilderConfigBody = {
      ...SAMPLE, ...shape, dialect,
      columns: baseCols.map(({ fieldType, ...rest }) => rest),
    };
    const r = await FT.saveBuilder(cookie, id, body);
    const fields = FT.dbFieldsOf(id);
    const kinds = fields.map((f) => f.field_type);
    rows.push(`${dialect}: HTTP ${r.status} → field_type = ${JSON.stringify(kinds)}`);

    expect(r.status,
      `AC-9：${dialect} 不传 fieldType 应**不报错**（api.md §1.3「不传/null → 走默认，不报错」），` +
      `实际 ${r.status}：${r.text.slice(0, 300)}`).toBeLessThan(300);
    expect(fields.length, `AC-9：${dialect} 保存后 fields 为空 ⇒ 断言会空跑，判【未验证】`).toBeGreaterThan(0);

    if (dialect === 'QUOTE') {
      expect(kinds.every((k) => k === 'INPUT_TEXT' || k === 'INPUT_NUMBER'),
        `AC-9：QUOTE 不传 fieldType 应维持按数据类型推的现状，实际 ${JSON.stringify(kinds)}`).toBe(true);
    } else {
      expect(kinds, `AC-9：${dialect} 不传 fieldType 应默认 BASIC_DATA（api.md §1.3），实际 ${JSON.stringify(kinds)}`)
        .toEqual(new Array(kinds.length).fill('BASIC_DATA'));
    }
  }

  // 🔑 阳性对照：显式传值必须**恒优先于默认**（api.md §1.2）——
  //    否则「不传走默认」可能只是因为「传了也不生效」，那是另一个缺陷被这条恒真掩盖了
  const { id: idX, name: nameX } = await FT.createComponent(cookie, `AC9-OVERRIDE-${Date.now()}`);
  const rX = await FT.saveBuilder(cookie, idX, {
    ...SAMPLE, dialect: 'COST_BASIC',
    columns: SAMPLE.columns.map((c) => ({ ...c, fieldType: 'INPUT_TEXT' })),
  });
  const kindsX = FT.dbFieldsOf(idX).map((f) => f.field_type);
  rows.push(`阳性对照 COST_BASIC + 显式 INPUT_TEXT: HTTP ${rX.status} → ${JSON.stringify(kindsX)}`);
  expect(rX.status, `AC-9 阳性对照：显式传合法值应 2xx，实际 ${rX.status}：${rX.text.slice(0, 300)}`).toBeLessThan(300);
  expect(kindsX,
    `AC-9 阳性对照：显式传 INPUT_TEXT 必须**恒优先于方言默认**（api.md §1.2），实际 ${JSON.stringify(kindsX)}。\n` +
    `  🚨 若这里落成 BASIC_DATA，说明「显式值被默认盖掉」—— 那会让上面「不传走默认」变成恒真的假绿。`)
    .toEqual(new Array(kindsX.length).fill('INPUT_TEXT'));

  FT.writeEvidence('AC-9-向后兼容.txt', rows.join('\n') + `\n（对照组件 ${nameX} / ${idX}）\n`);
});

// ════════════════════════════════════════════════════════════════════
// AC-13（无副作用）
// ════════════════════════════════════════════════════════════════════
test('AC-13 无副作用: 存量组件的 field_type 分布与改动前基线逐位相同', async () => {
  test.setTimeout(120_000);

  // 🚨 排除本片自建的组件（带 TAG 前缀）—— 否则我自己造的数会把「存量分布」打红，
  //    而那个红**长得像本次改动改了存量**（testing.md §4.5 的串扰形态）
  const rows = FT.sqlRows(
    `SELECT csv.builder_config::jsonb->>'dialect' AS dialect,
            f->>'field_type' AS field_type,
            count(*)::text AS fields,
            count(DISTINCT c.id)::text AS components
       FROM component c
       JOIN component_sql_view csv ON csv.component_id = c.id AND csv.builder_config IS NOT NULL,
            LATERAL jsonb_array_elements(c.fields::jsonb) f
      WHERE c.name NOT LIKE '${FT.TAG}%'
      GROUP BY 1,2 ORDER BY 1,2`,
  );
  const actual = rows.map((r) => `${r.dialect} | ${r.field_type} | ${r.fields}`).join('\n');
  console.log('[AC-13] 当前分布：\n' + actual);

  // 改动前基线（`需求文档.md §④` 实测，逐位对照）
  const BASELINE: Record<string, Record<string, number>> = {
    COST_BASIC: { FORMULA: 1, INPUT_NUMBER: 12, INPUT_TEXT: 21 },
    QUOTE: { BASIC_DATA: 1, FORMULA: 3, INPUT_NUMBER: 136, INPUT_TEXT: 171 },
  };
  const got: Record<string, Record<string, number>> = {};
  for (const r of rows) {
    (got[r.dialect] ??= {})[r.field_type] = Number(r.fields);
  }
  const compCount = FT.sqlRows(
    `SELECT csv.builder_config::jsonb->>'dialect' AS dialect, count(DISTINCT c.id)::text AS n
       FROM component c JOIN component_sql_view csv ON csv.component_id=c.id AND csv.builder_config IS NOT NULL
      WHERE c.name NOT LIKE '${FT.TAG}%' GROUP BY 1 ORDER BY 1`);

  FT.writeEvidence('AC-13-存量分布对照.txt',
    ['# 改动前基线（需求文档 §④）',
      JSON.stringify(BASELINE, null, 2),
      '',
      '# 交付后实测（已排除本片自建的 ' + FT.TAG + ' 前缀组件）',
      JSON.stringify(got, null, 2),
      '',
      '# 组件数',
      compCount.map((r) => `${r.dialect} = ${r.n}`).join('\n'),
      '',
      '# 明细',
      actual].join('\n') + '\n');

  for (const [dialect, dist] of Object.entries(BASELINE)) {
    for (const [ft, n] of Object.entries(dist)) {
      expect(got[dialect]?.[ft] ?? 0,
        `AC-13：存量 ${dialect} 的 ${ft} 字段数应与改动前基线逐位相同（期望 ${n}），` +
        `实际 ${got[dialect]?.[ft] ?? 0}。\n` +
        `  ⚠️ 差异先分清三种成因：①本次改动改了存量（真缺陷）；②别的会话在共享库上新配了组件（串扰）；` +
        `③基线本身在立项后就漂移过。🚫 不许直接判①。\n` +
        `  📌 **若本条与 AC-15 同时变红，多半是同一个根因**（「打开存量组件→什么都不改→保存」把 field_type ` +
        `改掉了），🚫 不要当成两个独立缺陷报。以 AC-15 的保存前后对照表为准。\n  当前完整分布=\n${actual}`).toBe(n);
    }
    // 反向：不应冒出基线里没有的类型
    for (const ft of Object.keys(got[dialect] ?? {})) {
      expect(Object.keys(dist),
        `AC-13：存量 ${dialect} 出现了基线里没有的 field_type「${ft}」（${got[dialect][ft]} 个字段）\n` +
        `  当前完整分布=\n${actual}`).toContain(ft);
    }
  }
});
