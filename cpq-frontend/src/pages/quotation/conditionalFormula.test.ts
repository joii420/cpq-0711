import { describe, it, expect } from 'vitest';
import { computeRowsCachesForTest } from './QuotationStep2';

const comp: any = {
  componentId: 'c1', componentCode: 'C1', tabName: 'T',
  fields: [
    { name: '类型', field_type: 'INPUT' },
    { name: '单价', field_type: 'INPUT_NUMBER' },
    { name: '加工费', field_type: 'FORMULA', conditional_formula: {
      rules: [
        { when: { kind: 'leaf', left: '类型', op: 'eq', rhs: { type: 'literal', value: '车削' } }, formula: 'f_turn' },
        { when: { kind: 'leaf', left: '类型', op: 'eq', rhs: { type: 'literal', value: '铣削' } }, formula: 'f_mill' },
      ],
      default: 'f_base',
    } },
  ],
  formulas: [
    { name: 'f_turn', expression: [{ type: 'field', value: '单价' }, { type: 'operator', value: '*' }, { type: 'number', value: '1.2' }] },
    { name: 'f_mill', expression: [{ type: 'field', value: '单价' }, { type: 'operator', value: '*' }, { type: 'number', value: '1.5' }] },
    { name: 'f_base', expression: [{ type: 'field', value: '单价' }] },
  ],
};

describe('条件公式逐行选公式（Plan 3a）', () => {
  it('车削*1.2 / 铣削*1.5 / 默认*1', () => {
    const caches = computeRowsCachesForTest(comp, [
      { 类型: '车削', 单价: 100 }, { 类型: '铣削', 单价: 100 }, { 类型: '钻孔', 单价: 100 },
    ]);
    expect(caches.map(c => c['加工费'])).toEqual([120, 150, 100]);
  });
});
