import { describe, it, expect } from 'vitest';
import { computeTabSubtotalsByColumn, computeNonSubtotalColumnSums } from '../QuotationStep2';

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

// B4: 非 is_subtotal 数值列 footer 行合计
describe('computeNonSubtotalColumnSums — B4 footer 行合计', () => {
  it('INPUT_NUMBER(非 is_subtotal)列跨行累加', () => {
    const comp2: any = {
      componentCode: 'CP2', code: 'CP2',
      fields: [
        { name: '物料', field_type: 'INPUT_TEXT' },
        { name: '用量', field_type: 'INPUT_NUMBER' },           // 非 is_subtotal
        { name: '毛重', field_type: 'INPUT_NUMBER' },           // 非 is_subtotal
        { name: '单价', field_type: 'INPUT_NUMBER', is_subtotal: true }, // is_subtotal 不进本函数结果
      ],
      formulas: [],
      rows: [
        { 物料: 'M1', 用量: 2, 毛重: 1.5, 单价: 10 },
        { 物料: 'M2', 用量: 3, 毛重: 2,   单价: 20 },
      ],
    };
    const out = computeNonSubtotalColumnSums(comp2);
    expect(out['用量']).toBeCloseTo(5, 4);   // 2+3
    expect(out['毛重']).toBeCloseTo(3.5, 4); // 1.5+2
    expect(out['单价']).toBeUndefined();     // is_subtotal=true 不进本函数
    expect(out['物料']).toBeUndefined();     // INPUT_TEXT 不进
  });

  it('空行/非数字值按 0 累加', () => {
    const comp3: any = {
      componentCode: 'CP3', code: 'CP3',
      fields: [{ name: '数量', field_type: 'INPUT_NUMBER' }],
      formulas: [],
      rows: [{ 数量: '' }, { 数量: null }, { 数量: 5 }],
    };
    const out = computeNonSubtotalColumnSums(comp3);
    expect(out['数量']).toBeCloseTo(5, 4);
  });
});
