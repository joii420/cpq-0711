import { describe, it, expect } from 'vitest';
import { buildCrossTabRows } from './QuotationStep2';
import { bnfDriverLookupKey } from './useDriverExpansions';

const bdvYs = (liao: string, price: number) => ({
  [bnfDriverLookupKey('$ys_view._料件')]: liao,
  [bnfDriverLookupKey('$ys_view._单价')]: price,
});

const ysExpansion = {
  rowCount: 3,
  rows: [
    { driverRow: { _料件: '料8', _单价: 60 },   basicDataValues: bdvYs('料8', 60) },
    { driverRow: { _料件: '料8', _单价: 34.5 }, basicDataValues: bdvYs('料8', 34.5) },
    { driverRow: { _料件: '料9', _单价: 99 },   basicDataValues: bdvYs('料9', 99) },
  ],
} as any;

const llExpansion = {
  rowCount: 1,
  rows: [
    { driverRow: { _料件: '料8' }, basicDataValues: { [bnfDriverLookupKey('$ll_view._料件')]: '料8' } },
  ],
} as any;

const ysFields = [
  { name: '料件', field_type: 'INPUT_TEXT',  default_source: { type: 'BASIC_DATA', path: '$ys_view._料件' } },
  { name: '单价', field_type: 'INPUT_NUMBER', default_source: { type: 'BASIC_DATA', path: '$ys_view._单价' } },
] as any;

const llFields = [
  { name: '料件',   field_type: 'INPUT_TEXT', default_source: { type: 'BASIC_DATA', path: '$ll_view._料件' } },
  { name: '材料费', field_type: 'FORMULA' },
] as any;

const componentData = [
  { componentId: '元素', componentCode: '元素', tabName: '元素', componentType: 'NORMAL',
    fields: ysFields, formulas: [], componentData: [], snapshotRows: 0,
    rows: [], subtotal: 0 },
  { componentId: '来料', componentCode: '来料', tabName: '来料', componentType: 'NORMAL',
    fields: llFields,
    formulas: [{ name: '材料费', expression: [
      { type: 'cross_tab_ref', source: '元素', target: '单价',
        match: [{ a: '料件', b: '料件' }], agg: 'SUM' },
    ] }],
    componentData: [], snapshotRows: 0,
    rows: [], subtotal: 0 },
] as any;

const lookupExpansion = (comp: any) =>
  comp.componentId === '元素' ? ysExpansion
  : comp.componentId === '来料' ? llExpansion
  : undefined;

describe('cross_tab INPUT+default_source 行解析', () => {
  it('源行按字段名解析 INPUT default_source（料件文本 / 单价数值）', () => {
    const store = buildCrossTabRows(componentData, {}, undefined, lookupExpansion);
    const ysRows = store['元素'];
    expect(ysRows).toHaveLength(3);
    expect(ysRows[0]['料件']).toBe('料8');
    expect(Number(ysRows[0]['单价'])).toBe(60);
  });

  it('宿主行 match 键经 INPUT default_source 解析 → SUM 命中正确（料8=94.5）', () => {
    const store = buildCrossTabRows(componentData, {}, undefined, lookupExpansion);
    const llRow = store['来料'][0];
    expect(Number(llRow['材料费'])).toBeCloseTo(94.5, 4);
  });
});
