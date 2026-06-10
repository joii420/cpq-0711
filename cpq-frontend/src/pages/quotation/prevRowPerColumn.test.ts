import { describe, it, expect } from 'vitest';
import { computeRowsCachesForTest } from './QuotationStep2';

const comp: any = {
  componentId: 'c1', componentCode: 'C1', tabName: 'T',
  fields: [
    { name: 'a', field_type: 'INPUT_NUMBER' },
    { name: 'b', field_type: 'INPUT_NUMBER' },
    { name: '累计A', field_type: 'FORMULA', is_subtotal: true, formula_name: '累计A' },
    { name: '累计B', field_type: 'FORMULA', is_subtotal: true, formula_name: '累计B' },
  ],
  formulas: [
    { name: '累计A', expression: [
      { type: 'previous_row_subtotal' }, { type: 'operator', value: '+' }, { type: 'field', value: 'a' },
    ] },
    { name: '累计B', expression: [
      { type: 'previous_row_subtotal' }, { type: 'operator', value: '+' }, { type: 'field', value: 'b' },
    ] },
  ],
};

describe('previous_row_subtotal 上一行本列（Plan 2b）', () => {
  it('两个累计列各自独立累加', () => {
    const caches = computeRowsCachesForTest(comp, [
      { a: 10, b: 1 }, { a: 20, b: 2 }, { a: 30, b: 3 },
    ]);
    expect(caches.map(c => c['累计A'])).toEqual([10, 30, 60]);
    expect(caches.map(c => c['累计B'])).toEqual([1, 3, 6]);
  });
});
