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
import { buildCrossTabRows } from './QuotationStep2';

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
  { 费用: 10 },
  { 费用: 20 },
  { 费用: 30 },
];

const compA_exp = {
  rowCount: 3,
  rows: [
    { driverRow: { 费用: 10 }, basicDataValues: {} },
    { driverRow: { 费用: 20 }, basicDataValues: {} },
    { driverRow: { 费用: 30 }, basicDataValues: {} },
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
const compB_rows = [{ 管理费: 5 }];

const componentData_AB = [
  {
    componentId: 'A', componentCode: 'A', tabName: 'A',
    componentType: 'NORMAL',
    fields: compA_fields,
    formulas: [],
    rows: compA_rows,
    componentData: [], snapshotRows: 3, subtotal: 0,
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
    componentData: [], snapshotRows: 1, subtotal: 0,
  },
] as any;

const lookup_AB = (comp: any) => {
  if (comp.componentId === 'A') return compA_exp;
  if (comp.componentId === 'B') return compB_exp;
  return undefined;
};

describe('columnSumsByComp — 断言① 非小计 FORMULA 列合计 == Σ行', () => {
  it('① 非小计 cross_tab FORMULA 列(加工费) columnSumsByComp 等于行值之和且非 0', () => {
    const allSubs: Record<string, number> = {};
    const { columnSumsByComp } = buildCrossTabRows(componentData_AB, allSubs, undefined, lookup_AB);

    // materialCost = 60 (cross_tab SUM of 10+20+30), 加工费 = field(materialCost) = 60
    // columnSumsByComp['B']['加工费'] = Σ行 加工费 = 60（1 行，每行=60）
    expect(columnSumsByComp).toBeDefined();
    const bSums = columnSumsByComp?.['B'];
    expect(bSums).toBeDefined();
    expect(bSums?.['加工费']).toBeCloseTo(60, 4); // 非 0
  });

  it('① INPUT_NUMBER 列(管理费=5) columnSumsByComp == 5', () => {
    const allSubs: Record<string, number> = {};
    const { columnSumsByComp } = buildCrossTabRows(componentData_AB, allSubs, undefined, lookup_AB);
    // 管理费 = INPUT_NUMBER，行值 = 5，1 行 → sum = 5
    expect(columnSumsByComp?.['B']?.['管理费']).toBeCloseTo(5, 4);
  });
});

describe('columnSumsByComp — 断言② is_subtotal 列与 allComponentSubtotals 同源', () => {
  it('② is_subtotal 列 columnSumsByComp["B"]["materialCost"] == allComponentSubtotals["B#materialCost"]', () => {
    const allSubs: Record<string, number> = {};
    const { columnSumsByComp } = buildCrossTabRows(componentData_AB, allSubs, undefined, lookup_AB);

    const fromColSums = columnSumsByComp?.['B']?.['materialCost'];
    const fromSubtotals = allSubs['B#materialCost'];
    expect(fromColSums).toBeDefined();
    expect(fromColSums).toBeCloseTo(60, 4);
    // 两者数值相同（同一 resolvedRows 求和，不再是两个分叉算法）
    expect(fromColSums).toBeCloseTo(fromSubtotals, 4);
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
    { driverRow: { val: 1 }, basicDataValues: {} },
    { driverRow: { val: 2 }, basicDataValues: {} },
    { driverRow: { val: 3 }, basicDataValues: {} },
  ],
} as any;

const compC_rows = [{ val: 1 }, { val: 2 }, { val: 3 }];

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
    componentData: [], snapshotRows: 3, subtotal: 0,
  },
] as any;

const lookup_C = (comp: any) => {
  if (comp.componentId === 'C') return compC_exp;
  return undefined;
};

describe('columnSumsByComp — 断言③ prevRowValues 串行（累加列）', () => {
  it('③ 累积列末行值=6时 columnSumsByComp["C"]["累计"]=10(Σ行=1+3+6)，非串则=6(1+2+3)', () => {
    const allSubs: Record<string, number> = {};
    const { columnSumsByComp, store } = buildCrossTabRows(componentData_C, allSubs, undefined, lookup_C);

    // store['C'] 的 resolvedRows 应含正确累计值
    const rows = store['C'];
    expect(rows?.[0]?.['累计']).toBeCloseTo(1, 4); // 0+1
    expect(rows?.[1]?.['累计']).toBeCloseTo(3, 4); // 1+2
    expect(rows?.[2]?.['累计']).toBeCloseTo(6, 4); // 3+3

    // columnSumsByComp['C']['累计'] = Σ行 = 1+3+6 = 10
    const cumSum = columnSumsByComp?.['C']?.['累计'];
    expect(cumSum).toBeCloseTo(10, 4);
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
const b4_wgjRows = [{ 费用: 0.05 }, { 费用: 0.2 }, { 费用: 0.002 }, { 费用: 0.007 }];
const b4_wgjExp = {
  rowCount: 4,
  rows: [
    { driverRow: { 费用: 0.05 },  basicDataValues: {} },
    { driverRow: { 费用: 0.2 },   basicDataValues: {} },
    { driverRow: { 费用: 0.002 }, basicDataValues: {} },
    { driverRow: { 费用: 0.007 }, basicDataValues: {} },
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
    componentData: [], snapshotRows: 4, subtotal: 0,
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
    componentData: [], snapshotRows: 1, subtotal: 0,
  },
] as any;

const lookup_B4 = (comp: any) => {
  if (comp.componentId === 'b4_wgj') return b4_wgjExp;
  if (comp.componentId === 'b4_ll') return b4_llExp;
  return undefined;
};

describe('columnSumsByComp — 断言④ B2 二阶列取第二轮最终值', () => {
  it('④ 二阶列 total 的 columnSumsByComp ≈ 0.518（取第二轮完整 comp resolvedRows）', () => {
    const allSubs: Record<string, number> = {};
    const { columnSumsByComp } = buildCrossTabRows(componentData_B4, allSubs, undefined, lookup_B4);

    // 第二轮 total 行值 = aCost小计(0.259) + bCost小计(0.259) = 0.518
    // 若取第一轮影子组件（无 total 列），columnSumsByComp['b4_ll']['total'] 为 undefined 或 0
    const b4llSums = columnSumsByComp?.['b4_ll'];
    expect(b4llSums?.['total']).toBeCloseTo(0.518, 3);
    // 同时验证 aCost/bCost 也正确
    expect(b4llSums?.['aCost']).toBeCloseTo(0.259, 4);
    expect(b4llSums?.['bCost']).toBeCloseTo(0.259, 4);
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
  { 净用量: 100, 计价单位: 'G' },
  { 净用量: 200, 计价单位: 'KG' },
];

const compUnit_exp = {
  rowCount: 2,
  rows: [
    { driverRow: { 净用量: 100, 计价单位: 'G' },  basicDataValues: {} },
    { driverRow: { 净用量: 200, 计价单位: 'KG' }, basicDataValues: {} },
  ],
} as any;

const componentData_Unit = [
  {
    componentId: 'unit_comp', componentCode: 'unit_comp', tabName: '元素',
    componentType: 'NORMAL',
    fields: compUnit_fields,
    formulas: [],
    rows: compUnit_rows,
    componentData: [], snapshotRows: 2, subtotal: 0,
  },
] as any;

const lookup_Unit = (comp: any) => {
  if (comp.componentId === 'unit_comp') return compUnit_exp;
  return undefined;
};

describe('columnSumsByComp — 断言⑤ unit_source_field 换算按 canonical 求和', () => {
  it('⑤ 净用量(G+KG 混) columnSumsByComp 按 canonical 求和 = 0.1+200=200.1，非原值 300', () => {
    const allSubs: Record<string, number> = {};
    const { columnSumsByComp } = buildCrossTabRows(componentData_Unit, allSubs, undefined, lookup_Unit);

    const unitSums = columnSumsByComp?.['unit_comp'] ?? columnSumsByComp?.['元素'];
    // applyUnitConversion: G → KG = /1000；KG → KG = ×1
    // row0: 0.1, row1: 200 → sum = 200.1
    expect(unitSums?.['净用量']).toBeCloseTo(200.1, 4);
    // 确保不是原值之和 300
    expect(unitSums?.['净用量']).not.toBeCloseTo(300, 1);
  });
});
