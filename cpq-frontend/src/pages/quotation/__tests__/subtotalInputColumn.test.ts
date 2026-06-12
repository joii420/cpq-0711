import { describe, it, expect } from 'vitest';
import { computeTabSubtotalsByColumn } from '../QuotationStep2';

const comp: any = {
  componentCode: 'CP', code: 'CP',
  fields: [
    { name: '料号', field_type: 'INPUT_TEXT' },
    { name: '汇率', field_type: 'INPUT_NUMBER', is_subtotal: true },
  ],
  formulas: [],
  rows: [{ 料号: 'A', 汇率: 7.12 }, { 料号: 'B', 汇率: 3 }],
};

describe('computeTabSubtotalsByColumn — 输入型小计列求和', () => {
  it('INPUT_NUMBER 小计列跨行求和(非 0)', () => {
    const out = computeTabSubtotalsByColumn(comp);
    expect(out['汇率']).toBeCloseTo(10.12, 4);
  });
});
