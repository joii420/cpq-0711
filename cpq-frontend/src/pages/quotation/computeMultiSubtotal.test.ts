import { describe, it, expect } from 'vitest';
import { computeTabSubtotalsByColumn } from './QuotationStep2';

// 两个小计列：材料费 = 单价*数量；加工费 = 工时*费率。两行。
const comp: any = {
  componentId: 'c1', componentCode: 'TOULIAO', tabName: '投料',
  fields: [
    { name: '单价', field_type: 'INPUT_NUMBER' },
    { name: '数量', field_type: 'INPUT_NUMBER' },
    { name: '工时', field_type: 'INPUT_NUMBER' },
    { name: '费率', field_type: 'INPUT_NUMBER' },
    { name: '材料费', field_type: 'FORMULA', is_subtotal: true, formula_name: '材料费' },
    { name: '加工费', field_type: 'FORMULA', is_subtotal: true, formula_name: '加工费' },
  ],
  formulas: [
    { name: '材料费', expression: [
      { type: 'field', value: '单价' }, { type: 'operator', value: '*' }, { type: 'field', value: '数量' },
    ] },
    { name: '加工费', expression: [
      { type: 'field', value: '工时' }, { type: 'operator', value: '*' }, { type: 'field', value: '费率' },
    ] },
  ],
  rows: [
    { 单价: 10, 数量: 2, 工时: 3, 费率: 5 },
    { 单价: 4, 数量: 5, 工时: 1, 费率: 7 },
  ],
};

describe('computeTabSubtotalsByColumn', () => {
  it('每个小计列各自求和', () => {
    const byCol = computeTabSubtotalsByColumn(comp);
    expect(byCol['材料费']).toBe(40); // 10*2 + 4*5
    expect(byCol['加工费']).toBe(22); // 3*5 + 1*7
  });

  it('单小计列向后兼容', () => {
    const one = { ...comp, fields: comp.fields.filter((f: any) => f.name !== '加工费') };
    const byCol = computeTabSubtotalsByColumn(one);
    expect(Object.keys(byCol)).toEqual(['材料费']);
    expect(byCol['材料费']).toBe(40);
  });
});
