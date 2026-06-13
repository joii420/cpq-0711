/**
 * T1 (RED) + T5 (前端对拍): buildCrossTabRows 列小计回填 allComponentSubtotals
 *
 * 场景: 外购件组件 (ygj) 4 行，每行有「费用」字段 (FIXED_VALUE，值 0.05/0.2/0.002/0.007)；
 * 来料组件 (ll) 1 行，「材料费」字段 (FORMULA, is_subtotal=true)
 *   = cross_tab_ref(source=外购件, target=费用, agg=SUM，无 match 条件 → 命中所有行)
 *
 * 外购件费用合计 = 0.05 + 0.2 + 0.002 + 0.007 = 0.259
 * 期望: buildCrossTabRows 调用后 allComponentSubtotals['来料#材料费'] ≈ 0.259
 * (T5 对拍：与后端 FormulaCalculator 同 fixture 同值)
 */
import { describe, it, expect } from 'vitest';
import { buildCrossTabRows } from './QuotationStep2';

// 外购件：4 行，每行一个「费用」字段（FIXED_VALUE，无公式）
const ygjFields = [
  { name: '费用', field_type: 'FIXED_VALUE', content: '' },
] as any;

// 外购件 4 行的数据（费用值写在 row 里）
const ygjRows = [
  { 费用: 0.05 },
  { 费用: 0.2 },
  { 费用: 0.002 },
  { 费用: 0.007 },
];

const ygjExpansion = {
  rowCount: 4,
  rows: [
    { driverRow: { 费用: 0.05 },   basicDataValues: {} },
    { driverRow: { 费用: 0.2 },    basicDataValues: {} },
    { driverRow: { 费用: 0.002 },  basicDataValues: {} },
    { driverRow: { 费用: 0.007 },  basicDataValues: {} },
  ],
} as any;

// 来料：1 行，「材料费」字段（FORMULA, is_subtotal=true）
// 公式 = SUM(外购件.费用)，无 match 条件 → 命中外购件全部行
const llFields = [
  { name: '材料费', field_type: 'FORMULA', is_subtotal: true },
] as any;

const llExpansion = {
  rowCount: 1,
  rows: [
    { driverRow: {}, basicDataValues: {} },
  ],
} as any;

const componentData = [
  {
    componentId: '外购件', componentCode: '外购件', tabName: '外购件',
    componentType: 'NORMAL',
    fields: ygjFields,
    formulas: [],
    rows: ygjRows,
    componentData: [], snapshotRows: 4, subtotal: 0,
  },
  {
    componentId: '来料', componentCode: '来料', tabName: '来料',
    componentType: 'NORMAL',
    fields: llFields,
    formulas: [
      {
        name: '材料费',
        expression: [
          {
            type: 'cross_tab_ref',
            source: '外购件',
            target: '费用',
            // 无 match 条件 → 命中所有行
            match: [],
            agg: 'SUM',
          },
        ],
      },
    ],
    rows: [{}],
    componentData: [], snapshotRows: 1, subtotal: 0,
  },
] as any;

const lookupExpansion = (comp: any) => {
  if (comp.componentId === '外购件') return ygjExpansion;
  if (comp.componentId === '来料') return llExpansion;
  return undefined;
};

describe('buildCrossTabRows 列小计回填', () => {
  it('T1/T5 — 外购件 4 行(费用: 0.05/0.2/0.002/0.007)，来料 cross_tab SUM → allComponentSubtotals["来料#材料费"] ≈ 0.259', () => {
    const allComponentSubtotals: Record<string, number> = {};
    buildCrossTabRows(componentData, allComponentSubtotals, undefined, lookupExpansion);

    // 来料组件的 is_subtotal 列「材料费」应被回填
    const colSubtotal = allComponentSubtotals['来料#材料费'];
    expect(colSubtotal).toBeCloseTo(0.259, 4);
  });

  it('回填同时写入 componentCode#列名 + tabName#列名（两键对齐）', () => {
    const allComponentSubtotals: Record<string, number> = {};
    buildCrossTabRows(componentData, allComponentSubtotals, undefined, lookupExpansion);

    // 按 componentCode 键
    expect(allComponentSubtotals['来料#材料费']).toBeCloseTo(0.259, 4);
    // 总小计键（无列名，按 componentCode/tabName）
    expect(allComponentSubtotals['来料']).toBeCloseTo(0.259, 4);
  });

  it('外购件（无 is_subtotal 列）不应影响回填结果', () => {
    const allComponentSubtotals: Record<string, number> = {};
    buildCrossTabRows(componentData, allComponentSubtotals, undefined, lookupExpansion);

    // 外购件没有 is_subtotal 列，不应有 "外购件#xxx" 键被写入
    const ygjColKeys = Object.keys(allComponentSubtotals).filter(k => k.startsWith('外购件#'));
    expect(ygjColKeys).toHaveLength(0);
  });
});
