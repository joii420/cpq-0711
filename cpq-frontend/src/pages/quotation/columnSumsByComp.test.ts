/**
 * Task 1 TDD: buildCrossTabRows 产出 columnSumsByComp
 *
 * columnSumsByComp: Record<compKey, Record<colName, sum>>
 *   compKey = componentId | componentCode | tabName (三键，与 allComponentSubtotals 同逻辑)
 *   colName = is_subtotal 或 field_type ∈ {INPUT_NUMBER, FORMULA, DATA_SOURCE} 的列
 *   sum     = Σ行 resolvedRow[colName]，按 applyUnitConversion canonical，4dp 舍入
 *
 * 5 个断言：
 *   ① 非小计 cross_tab/SUMIF 列合计 == 逐行 resolvedRow 之和且非 0
 *   ② is_subtotal 列 columnSumsByComp == allComponentSubtotals[`tab#col`]（两者同源）
 *   ③ previous_row_subtotal 累加列末行累加值 == columnSumsByComp（证明串了 prevRowValues）
 *   ④ B2 二阶列：columnSumsByComp 取第二轮最终值（含二阶列），非第一轮影子组件值
 *   ⑤ 配 unit_source_field 的列按 canonical 求和（非原值）
 */
import { describe, it, expect } from 'vitest';
import type { DecimalContext } from '../../utils/formulaEngine';
import { buildCrossTabRows, computeTabSubtotalsByColumn } from './QuotationStep2';
import { buildComponentDeps } from './crossTabOrder';
import { sumDecimal, toCalculationString } from '../../utils/precision';

// ─────────────────────────────────────────────────────────────────────────────
// 断言 ①②: 非小计 FORMULA 列(cross_tab_ref)合计 == Σ行；is_subtotal 列两源同值
//
// fixture:
//   外购件(A): 3 行，费用(INPUT_NUMBER) = [10, 20, 30] → 无公式，值在 row
//   来料(B):   1 行，
//     materialCost — FORMULA, is_subtotal=true  = cross_tab SUM(A.费用) = 60
//     加工费     — FORMULA (无 is_subtotal)    = field(materialCost) = 60
//     管理费     — INPUT_NUMBER (无 is_subtotal) = 5（固定输入）
// ─────────────────────────────────────────────────────────────────────────────

const compA_fields = [
  { name: '费用', field_type: 'INPUT_NUMBER' },
] as any;

const compA_rows = [
  { 费用: '10' },
  { 费用: '20' },
  { 费用: '30' },
];

const compA_exp = {
  rowCount: 3,
  rows: [
    { driverRow: { 费用: '10' }, basicDataValues: {} },
    { driverRow: { 费用: '20' }, basicDataValues: {} },
    { driverRow: { 费用: '30' }, basicDataValues: {} },
  ],
} as any;

const compB_fields = [
  { name: 'materialCost', field_type: 'FORMULA', is_subtotal: true },
  { name: '加工费',       field_type: 'FORMULA' },
  { name: '管理费',       field_type: 'INPUT_NUMBER' },
] as any;

const compB_exp = {
  rowCount: 1,
  rows: [{ driverRow: {}, basicDataValues: {} }],
} as any;

// 来料行：管理费固定输入 5
const compB_rows = [{ 管理费: '5' }];

const componentData_AB = [
  {
    componentId: 'A', componentCode: 'A', tabName: 'A',
    componentType: 'NORMAL',
    fields: compA_fields,
    formulas: [],
    rows: compA_rows,
    componentData: [], snapshotRows: 3, subtotal: '0',
  },
  {
    componentId: 'B', componentCode: 'B', tabName: 'B',
    componentType: 'NORMAL',
    fields: compB_fields,
    formulas: [
      {
        name: 'materialCost',
        expression: [
          { type: 'cross_tab_ref', source: 'A', target: '费用', match: [], agg: 'SUM' },
        ],
      },
      {
        name: '加工费',
        expression: [
          { type: 'field', value: 'materialCost' },
        ],
      },
    ],
    rows: compB_rows,
    componentData: [], snapshotRows: 1, subtotal: '0',
  },
] as any;

const lookup_AB = (comp: any) => {
  if (comp.componentId === 'A') return compA_exp;
  if (comp.componentId === 'B') return compB_exp;
  return undefined;
};

describe('columnSumsByComp — 断言① 非小计 FORMULA 列合计 == Σ行', () => {
  it('① 非小计 cross_tab FORMULA 列(加工费) columnSumsByComp 等于行值之和且非 0', () => {
    const allSubs: DecimalContext = {};
    const { columnSumsByComp } = buildCrossTabRows(componentData_AB, allSubs, undefined, lookup_AB);

    // materialCost = 60 (cross_tab SUM of 10+20+30), 加工费 = field(materialCost) = 60
    // columnSumsByComp['B']['加工费'] = Σ行 加工费 = 60（1 行，每行=60）
    expect(columnSumsByComp).toBeDefined();
    const bSums = columnSumsByComp?.['B'];
    expect(bSums).toBeDefined();
    expect(bSums?.['加工费']).toBe('60'); // 非 0
  });

  it('① INPUT_NUMBER 列(管理费=5) columnSumsByComp == 5', () => {
    const allSubs: DecimalContext = {};
    const { columnSumsByComp } = buildCrossTabRows(componentData_AB, allSubs, undefined, lookup_AB);
    // 管理费 = INPUT_NUMBER，行值 = 5，1 行 → sum = 5
    expect(columnSumsByComp?.['B']?.['管理费']).toBe('5');
  });
});

describe('columnSumsByComp — 断言② is_subtotal 列与 allComponentSubtotals 同源', () => {
  it('② is_subtotal 列 columnSumsByComp["B"]["materialCost"] == allComponentSubtotals["B#materialCost"]', () => {
    const allSubs: DecimalContext = {};
    const { columnSumsByComp } = buildCrossTabRows(componentData_AB, allSubs, undefined, lookup_AB);

    const fromColSums = columnSumsByComp?.['B']?.['materialCost'];
    const fromSubtotals = allSubs['B#materialCost'];
    expect(fromColSums).toBeDefined();
    expect(fromColSums).toBe('60');
    // 两者数值相同（同一 resolvedRows 求和，不再是两个分叉算法）
    expect(fromColSums).toBe(fromSubtotals);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// 断言 ③: previous_row_subtotal 累加列 — 证明 computeRows 串了 prevRowValues
//
// fixture: 1 个组件, 3 行 INPUT_NUMBER(val=[1,2,3]) + 累计(FORMULA, previous_row_subtotal + val)
//   行 0: 累计 = 0 + 1 = 1
//   行 1: 累计 = 1 + 2 = 3
//   行 2: 累计 = 3 + 3 = 6
//
// columnSumsByComp['C']['累计'] = 1+3+6 = 10（Σ行逐行值之和）
// 若没有串 prevRowValues，每行 previous_row_subtotal = 0 → 每行累计 = val → sum = 1+2+3 = 6（错误）
// ─────────────────────────────────────────────────────────────────────────────

const compC_fields = [
  { name: 'val', field_type: 'INPUT_NUMBER' },
  { name: '累计', field_type: 'FORMULA', is_subtotal: false },
] as any;

const compC_exp = {
  rowCount: 3,
  rows: [
    { driverRow: { val: '1' }, basicDataValues: {} },
    { driverRow: { val: '2' }, basicDataValues: {} },
    { driverRow: { val: '3' }, basicDataValues: {} },
  ],
} as any;

const compC_rows = [{ val: '1' }, { val: '2' }, { val: '3' }];

const componentData_C = [
  {
    componentId: 'C', componentCode: 'C', tabName: 'C',
    componentType: 'NORMAL',
    fields: compC_fields,
    formulas: [
      {
        name: '累计',
        expression: [
          { type: 'previous_row_subtotal' },
          { type: 'operator', value: '+' },
          { type: 'field', value: 'val' },
        ],
      },
    ],
    rows: compC_rows,
    componentData: [], snapshotRows: 3, subtotal: '0',
  },
] as any;

const lookup_C = (comp: any) => {
  if (comp.componentId === 'C') return compC_exp;
  return undefined;
};

describe('columnSumsByComp — 断言③ prevRowValues 串行（累加列）', () => {
  it('③ 累积列末行值=6时 columnSumsByComp["C"]["累计"]=10(Σ行=1+3+6)，非串则=6(1+2+3)', () => {
    const allSubs: DecimalContext = {};
    const { columnSumsByComp, store } = buildCrossTabRows(componentData_C, allSubs, undefined, lookup_C);

    // store['C'] 的 resolvedRows 应含正确累计值
    const rows = store['C'];
    expect(rows?.[0]?.['累计']).toBe('1'); // 0+1
    expect(rows?.[1]?.['累计']).toBe('3'); // 1+2
    expect(rows?.[2]?.['累计']).toBe('6'); // 3+3

    // columnSumsByComp['C']['累计'] = Σ行 = 1+3+6 = 10
    const cumSum = columnSumsByComp?.['C']?.['累计'];
    expect(cumSum).toBe('10');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// 断言 ④: B2 二阶列 columnSumsByComp 取第二轮最终值（非第一轮影子组件值）
//
// 沿用 buildCrossTabRows.test.ts B1 fixture（ll 含一阶 aCost/bCost + 二阶 total）：
//   total = component_subtotal(ll·aCost) + component_subtotal(ll·bCost)
//   若 columnSumsByComp 取第一轮行（影子组件，无 total 列），total 为 undefined
//   若取第二轮行（完整 comp），total = 0.518
// ─────────────────────────────────────────────────────────────────────────────

const b4_wgjFields = [
  { name: '费用', field_type: 'FIXED_VALUE', content: '' },
] as any;
const b4_wgjRows = [{ 费用: '0.05' }, { 费用: '0.2' }, { 费用: '0.002' }, { 费用: '0.007' }];
const b4_wgjExp = {
  rowCount: 4,
  rows: [
    { driverRow: { 费用: '0.05' },  basicDataValues: {} },
    { driverRow: { 费用: '0.2' },   basicDataValues: {} },
    { driverRow: { 费用: '0.002' }, basicDataValues: {} },
    { driverRow: { 费用: '0.007' }, basicDataValues: {} },
  ],
} as any;

const b4_llFields = [
  { name: 'aCost', field_type: 'FORMULA', is_subtotal: true },
  { name: 'bCost', field_type: 'FORMULA', is_subtotal: true },
  { name: 'total', field_type: 'FORMULA', is_subtotal: true },
] as any;
const b4_llExp = { rowCount: 1, rows: [{ driverRow: {}, basicDataValues: {} }] } as any;

const crossTabSumExpr_b4 = [
  { type: 'cross_tab_ref', source: 'b4_wgj', target: '费用', match: [], agg: 'SUM' },
] as any;
const totalExpr_b4 = [
  { type: 'component_subtotal', component_code: 'b4_ll', value: 'aCost', tab_name: 'aCost' },
  { type: 'operator', value: '+' },
  { type: 'component_subtotal', component_code: 'b4_ll', value: 'bCost', tab_name: 'bCost' },
] as any;

const componentData_B4 = [
  {
    componentId: 'b4_wgj', componentCode: 'b4_wgj', tabName: 'b4_wgj',
    componentType: 'NORMAL',
    fields: b4_wgjFields,
    formulas: [],
    rows: b4_wgjRows,
    componentData: [], snapshotRows: 4, subtotal: '0',
  },
  {
    componentId: 'b4_ll', componentCode: 'b4_ll', tabName: 'b4_ll',
    componentType: 'NORMAL',
    fields: b4_llFields,
    formulas: [
      { name: 'aCost', expression: crossTabSumExpr_b4 },
      { name: 'bCost', expression: crossTabSumExpr_b4 },
      { name: 'total', expression: totalExpr_b4 },
    ],
    rows: [{}],
    componentData: [], snapshotRows: 1, subtotal: '0',
  },
] as any;

const lookup_B4 = (comp: any) => {
  if (comp.componentId === 'b4_wgj') return b4_wgjExp;
  if (comp.componentId === 'b4_ll') return b4_llExp;
  return undefined;
};

describe('columnSumsByComp — 断言④ B2 二阶列取第二轮最终值', () => {
  it('④ 二阶列 total 的 columnSumsByComp ≈ 0.518（取第二轮完整 comp resolvedRows）', () => {
    const allSubs: DecimalContext = {};
    const { columnSumsByComp } = buildCrossTabRows(componentData_B4, allSubs, undefined, lookup_B4);

    // 第二轮 total 行值 = aCost小计(0.259) + bCost小计(0.259) = 0.518
    // 若取第一轮影子组件（无 total 列），columnSumsByComp['b4_ll']['total'] 为 undefined 或 0
    const b4llSums = columnSumsByComp?.['b4_ll'];
    expect(b4llSums?.['total']).toBe('0.518');
    // 同时验证 aCost/bCost 也正确
    expect(b4llSums?.['aCost']).toBe('0.259');
    expect(b4llSums?.['bCost']).toBe('0.259');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// 断言 ⑤: unit_source_field 换算 — columnSumsByComp 按 canonical 求和
//
// fixture: 元素组件 2 行，净用量配 unit_source_field=计价单位
//   行0: 净用量=100, 计价单位=G   → canonical = 100/1000 = 0.1 kg
//   行1: 净用量=200, 计价单位=KG  → canonical = 200
//   columnSumsByComp['元素']['净用量'] = 0.1 + 200 = 200.1（按 canonical）
//   若用原值: 100 + 200 = 300（错误）
// ─────────────────────────────────────────────────────────────────────────────

const compUnit_fields = [
  { name: '净用量', field_type: 'INPUT_NUMBER', unit_source_field: '计价单位' },
  { name: '计价单位', field_type: 'INPUT_TEXT' },
] as any;

const compUnit_rows = [
  { 净用量: '100', 计价单位: 'G' },
  { 净用量: '200', 计价单位: 'KG' },
];

const compUnit_exp = {
  rowCount: 2,
  rows: [
    { driverRow: { 净用量: '100', 计价单位: 'G' },  basicDataValues: {} },
    { driverRow: { 净用量: '200', 计价单位: 'KG' }, basicDataValues: {} },
  ],
} as any;

const componentData_Unit = [
  {
    componentId: 'unit_comp', componentCode: 'unit_comp', tabName: '元素',
    componentType: 'NORMAL',
    fields: compUnit_fields,
    formulas: [],
    rows: compUnit_rows,
    componentData: [], snapshotRows: 2, subtotal: '0',
  },
] as any;

const lookup_Unit = (comp: any) => {
  if (comp.componentId === 'unit_comp') return compUnit_exp;
  return undefined;
};

describe('columnSumsByComp — 断言⑤ unit_source_field 换算按 canonical 求和', () => {
  it('⑤ 净用量(G+KG 混) columnSumsByComp 按 canonical 求和 = 0.1+200=200.1，非原值 300', () => {
    const allSubs: DecimalContext = {};
    const { columnSumsByComp } = buildCrossTabRows(componentData_Unit, allSubs, undefined, lookup_Unit);

    const unitSums = columnSumsByComp?.['unit_comp'] ?? columnSumsByComp?.['元素'];
    // applyUnitConversion: G → KG = /1000；KG → KG = ×1
    // row0: 0.1, row1: 200 → sum = 200.1
    expect(unitSums?.['净用量']).toBe('200.1');
    // 确保不是原值之和 300
    expect(unitSums?.['净用量']).not.toBe('300');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// 回归 (QT-20260616-1741): 输入框写入的是【字符串】值，footer 小计必须 parseFloat 兜底，
// 不能因 typeof!=='number' 丢成 0。此前 fixture 都用数字值，漏了字符串路径。
// ─────────────────────────────────────────────────────────────────────────────
describe('columnSumsByComp — 回归: INPUT_NUMBER 字符串值不得丢成 0', () => {
  const compStr: any = {
    componentId: 'PSTR', componentCode: 'PSTR', tabName: '产品', componentType: 'NORMAL',
    fields: [
      { name: '材料管理费', field_type: 'INPUT_NUMBER' },
      { name: '单重', field_type: 'INPUT_NUMBER' },
      { name: '管理费', field_type: 'FORMULA', formula_name: 'f_m', is_subtotal: true },
    ],
    formulas: [{ name: 'f_m', expression: [
      { type: 'field', value: '材料管理费' }, { type: 'operator', value: '*' },
      { type: 'field', value: '单重' }, { type: 'operator', value: '*' }, { type: 'number', value: '0.001' },
    ] }],
    formulaAssignments: { '2': 'f_m' },
    rows: [{ '材料管理费': '12', '单重': '32' }],  // 字符串，模拟输入框
    subtotal: '0',
  };

  it('字符串 "12"/"32" → 材料管理费=12, 单重=32（非 0）, 依赖输入的公式列=0.384', () => {
    const acs: DecimalContext = {};
    const { columnSumsByComp } = buildCrossTabRows([compStr], acs, 'PART', () => undefined);
    expect(columnSumsByComp['PSTR']['材料管理费']).toBe('12');
    expect(columnSumsByComp['PSTR']['单重']).toBe('32');
    expect(columnSumsByComp['PSTR']['管理费']).toBe('0.384');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// 回归 (QT-20260616-1743): component_subtotal 跨组件依赖必须进拓扑序。
// 公式列引用别组件列小计(component_subtotal)时，被引用组件必须先处理，否则其列小计未回填→读 0。
// 此前 extractSourceRefs 只看 cross_tab_ref，漏 component_subtotal → 引用方排前时算 0。
//
// repair-0808 订正（2026-08-09）：本 describe 原来只有一条用例，"绕过 PASS1"（直接传空 `acs`）
// 调 buildCrossTabRows，断言必须靠拓扑边把值传过去。repair-0808 把 component_subtotal 的依赖边
// 收窄到列粒度（AC-5）：被引用列是 INPUT_NUMBER 时不再建边，因为它的值在行迭代前就已定死、
// 与页签算序无关——但原用例把 A.cost 设成 INPUT_NUMBER，又跳过 PASS1，导致 C 排前处理时
// acs['COMP-A#cost'] 还没有任何来源（既无边、也无 PASS1）能写入，于是塌成 0。
//
// 这不是回归，是旧用例的 setup 从未反映生产真实调用形状——三个生产调用点
// （QuotationStep2.tsx:3012 / ReadonlyProductCard.tsx:462 / buildExcelSnapshot.ts:124）
// 都是先跑 PASS1（`computeTabSubtotalsByColumn` 逐组件登记）再调 buildCrossTabRows，
// 没有空 acs 的生产路径。改法：setup 换成真实链路（PASS1 + PASS2），并拆成两条覆盖两种列类型：
//   ① 被引用列 INPUT_NUMBER（repair-0808 起不再建边）—— PASS1 兜底，mgmt 仍 = 200
//   ② 被引用列 FORMULA（QT-1743 的真形状，仍然建边）—— 引用方排前也 = 200，防止本次改动漏建边
// ─────────────────────────────────────────────────────────────────────────────
describe('columnSumsByComp — 回归: component_subtotal 跨组件依赖（QT-1743 不回归 + repair-0808 列粒度）', () => {
  /** 复刻生产 PASS1（ProductCard QuotationStep2.tsx:2976-3008 的核心：逐 NORMAL 组件登记三键 + 列键）。 */
  function pass1(comps: any[]): DecimalContext {
    const acs: DecimalContext = {};
    for (const c of comps) {
      if (c.componentType !== 'NORMAL') continue;
      const byCol = computeTabSubtotalsByColumn(c, acs, undefined, undefined, 'PART', undefined);
      const tot = toCalculationString(sumDecimal(Object.values(byCol)));
      for (const k of [c.componentId, c.componentCode, c.tabName]) {
        if (!k) continue;
        acs[k] = tot;
        for (const [col, v] of Object.entries(byCol)) acs[`${k}#${col}`] = v;
      }
    }
    return acs;
  }

  // ① 被引用列 INPUT_NUMBER：repair-0808 起不建边，靠 PASS1 兜底出精确值（与算序无关）。
  const A_input: any = {
    componentId: 'COMP-A', componentCode: 'COMP-A', tabName: 'TabA', componentType: 'NORMAL',
    fields: [{ name: 'cost', field_type: 'INPUT_NUMBER', is_subtotal: true }],
    formulas: [], rows: [{ cost: '100' }], subtotal: '0',
  };
  const C_refInput: any = {
    componentId: 'COMP-C', componentCode: 'COMP-C', tabName: 'TabC', componentType: 'NORMAL',
    fields: [{ name: 'mgmt', field_type: 'FORMULA', formula_name: 'f_mgmt', is_subtotal: true }],
    formulas: [{ name: 'f_mgmt', expression: [
      { type: 'component_subtotal', component_code: 'COMP-A', value: 'cost', tab_name: 'cost' },
      { type: 'operator', value: '*' }, { type: 'number', value: '2' },
    ] }],
    formulaAssignments: { '0': 'f_mgmt' }, rows: [{}], subtotal: '0',
  };

  it('buildComponentDeps: 被引用列 INPUT_NUMBER → deps[COMP-C] 不含 COMP-A（不建边）', () => {
    const deps = buildComponentDeps([
      { cid: 'COMP-C', code: 'COMP-C', tabName: 'TabC', formulas: C_refInput.formulas, fields: C_refInput.fields },
      { cid: 'COMP-A', code: 'COMP-A', tabName: 'TabA', formulas: A_input.formulas, fields: A_input.fields },
    ]);
    expect(deps['COMP-C']).not.toContain('COMP-A');
  });

  it('① 引用方(C)排在被引用方(A)之前（无边，声明序不变）：真实链路(PASS1+PASS2)下 mgmt 小计=200 非 0', () => {
    const acs = pass1([C_refInput, A_input]);
    const { columnSumsByComp } = buildCrossTabRows([C_refInput, A_input], acs, 'PART', () => undefined);
    expect(columnSumsByComp['COMP-A']['cost']).toBe('100');
    expect(columnSumsByComp['COMP-C']['mgmt']).toBe('200');
    expect(acs['COMP-C#mgmt']).toBe('200');
  });

  // ② 被引用列 FORMULA：QT-1743 的真实形状，repair-0808 后仍必须建边——
  // 这条是防止本次改动把 QT-1743 的修复连带漏掉的门禁。
  const A_formula: any = {
    componentId: 'COMP-A', componentCode: 'COMP-A', tabName: 'TabA', componentType: 'NORMAL',
    fields: [
      { name: 'qty', field_type: 'INPUT_NUMBER' },
      { name: 'cost', field_type: 'FORMULA', formula_name: 'f_cost', is_subtotal: true },
    ],
    formulas: [{ name: 'f_cost', expression: [
      { type: 'field', value: 'qty' }, { type: 'operator', value: '*' }, { type: 'number', value: '10' },
    ] }],
    formulaAssignments: { '1': 'f_cost' },
    rows: [{ qty: '10' }], subtotal: '0',
  };
  const C_refFormula: any = {
    ...C_refInput,
    formulas: [{ name: 'f_mgmt', expression: [
      { type: 'component_subtotal', component_code: 'COMP-A', value: 'cost', tab_name: 'cost' },
      { type: 'operator', value: '*' }, { type: 'number', value: '2' },
    ] }],
  };

  it('buildComponentDeps: 被引用列 FORMULA → deps[COMP-C] 含 COMP-A（仍建边，QT-1743 门禁）', () => {
    const deps = buildComponentDeps([
      { cid: 'COMP-C', code: 'COMP-C', tabName: 'TabC', formulas: C_refFormula.formulas, fields: C_refFormula.fields },
      { cid: 'COMP-A', code: 'COMP-A', tabName: 'TabA', formulas: A_formula.formulas, fields: A_formula.fields },
    ]);
    expect(deps['COMP-C']).toContain('COMP-A');
  });

  it('② 引用方(C)排在被引用方(A)之前（建边，拓扑序纠正为 A→C）：mgmt 小计=200 非 0', () => {
    const acs = pass1([C_refFormula, A_formula]);
    const { columnSumsByComp } = buildCrossTabRows([C_refFormula, A_formula], acs, 'PART', () => undefined);
    expect(columnSumsByComp['COMP-A']['cost']).toBe('100');
    expect(columnSumsByComp['COMP-C']['mgmt']).toBe('200');
    expect(acs['COMP-C#mgmt']).toBe('200');
  });
});
